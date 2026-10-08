package hu.devreport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Portable SQLite cache and canonical archive of the generated HTML report. */
final class ReportDatabase implements AutoCloseable {
    static final int SCHEMA_VERSION = 1;
    static final String QUALITY_ENGINE = "pmd-7.27.0-cpd-50-rules-v1";
    private static final Set<String> HTML_ASSET_EXTENSIONS = Set.of(".html", ".css", ".js");

    private final Path path;
    private final Connection connection;
    private final ObjectMapper json = new ObjectMapper();
    private long qualityHits;
    private long qualityWrites;

    private ReportDatabase(Path path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    static ReportDatabase open(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            if (normalized.getParent() != null) Files.createDirectories(normalized.getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            ReportDatabase database = new ReportDatabase(normalized, connection);
            database.configure();
            database.createSchema();
            return database;
        } catch (SQLException exception) {
            throw new IOException("A SQLite adatbázis nem nyitható meg: " + normalized, exception);
        }
    }

    Path path() { return path; }

    static String repositoryKey(RepositoryStats repository) {
        if (repository.originUrl != null && !repository.originUrl.isBlank()) {
            return "origin:" + repository.originUrl.strip().replace('\\', '/').replaceAll("(?i)\\.git$", "")
                    .toLowerCase(Locale.ROOT);
        }
        String rootCommit = repository.commits.stream().min(Comparator.comparing(CommitSummary::date))
                .map(commit -> commit.hash).orElse("empty");
        return "local:" + repository.name.toLowerCase(Locale.ROOT) + ":" + rootCommit;
    }

    synchronized Map<String, QualityAssessment> loadQuality(String repositoryKey, Set<String> commitHashes)
            throws IOException {
        Map<String, QualityAssessment> result = new HashMap<>();
        String sql = "SELECT commit_hash, assessment_json FROM quality_cache "
                + "WHERE repository_key=? AND engine_key=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, repositoryKey);
            statement.setString(2, QUALITY_ENGINE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String hash = rows.getString(1);
                    if (!commitHashes.contains(hash)) continue;
                    try {
                        result.put(hash, fromDto(json.readValue(rows.getString(2), QualityDto.class)));
                        qualityHits++;
                    } catch (RuntimeException | IOException invalidEntry) {
                        // A sérült vagy régi bejegyzés cache missként újraszámolható.
                    }
                }
            }
            return result;
        } catch (SQLException exception) {
            throw new IOException("A PMD/CPD cache nem olvasható.", exception);
        }
    }

    synchronized void saveQuality(String repositoryKey, String commitHash, QualityAssessment assessment)
            throws IOException {
        if (assessment.status != QualityAssessment.Status.COMPLETE
                && assessment.status != QualityAssessment.Status.NOT_APPLICABLE) return;
        String sql = "INSERT INTO quality_cache(repository_key,commit_hash,engine_key,assessment_json,updated_at) "
                + "VALUES(?,?,?,?,?) ON CONFLICT(repository_key,commit_hash,engine_key) DO UPDATE SET "
                + "assessment_json=excluded.assessment_json,updated_at=excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, repositoryKey);
            statement.setString(2, commitHash);
            statement.setString(3, QUALITY_ENGINE);
            statement.setString(4, json.writeValueAsString(toDto(assessment)));
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
            qualityWrites++;
        } catch (SQLException exception) {
            throw new IOException("A PMD/CPD cache nem írható.", exception);
        }
    }

    synchronized ArchiveStats archiveHtml(Path output, BiConsumer<Long, Long> progress) throws IOException {
        List<Path> assets;
        try (var paths = Files.walk(output)) {
            assets = paths.filter(Files::isRegularFile)
                    .filter(file -> !file.toAbsolutePath().normalize().equals(path))
                    .filter(file -> HTML_ASSET_EXTENSIONS.contains(extension(file.getFileName().toString())))
                    .sorted().toList();
        }
        boolean previousAutoCommit;
        long originalBytes = 0;
        long storedBytes = 0;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM html_asset");
            }
            String sql = "INSERT INTO html_asset(path,content_gzip,original_bytes,updated_at) VALUES(?,?,?,?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                long current = 0;
                for (Path asset : assets) {
                    byte[] source = Files.readAllBytes(asset);
                    byte[] compressed = gzip(source);
                    String relative = output.toAbsolutePath().normalize().relativize(asset.toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                    insert.setString(1, relative);
                    insert.setBytes(2, compressed);
                    insert.setLong(3, source.length);
                    insert.setString(4, Instant.now().toString());
                    insert.addBatch();
                    originalBytes += source.length;
                    storedBytes += compressed.length;
                    current++;
                    if (current % 100 == 0) insert.executeBatch();
                    progress.accept(current, (long) assets.size());
                }
                insert.executeBatch();
            }
            putMetadata("schema_version", Integer.toString(SCHEMA_VERSION));
            putMetadata("html_archived_at", Instant.now().toString());
            putMetadata("html_asset_count", Integer.toString(assets.size()));
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
            return new ArchiveStats(assets.size(), originalBytes, storedBytes);
        } catch (SQLException exception) {
            rollbackQuietly();
            throw new IOException("A HTML-riport nem menthető az SQLite adatbázisba.", exception);
        }
    }

    synchronized ArchiveStats restoreHtml(Path output, BiConsumer<Long, Long> progress) throws IOException {
        try {
            long total;
            try (Statement count = connection.createStatement();
                 ResultSet row = count.executeQuery("SELECT COUNT(*) FROM html_asset")) {
                total = row.next() ? row.getLong(1) : 0;
            }
            if (total == 0) throw new IOException("Az adatbázis nem tartalmaz újragenerálható HTML-riportot.");
            Files.createDirectories(output);
            long current = 0;
            long originalBytes = 0;
            long storedBytes = 0;
            try (Statement select = connection.createStatement();
                 ResultSet rows = select.executeQuery(
                         "SELECT path,content_gzip,original_bytes FROM html_asset ORDER BY path")) {
                while (rows.next()) {
                    String relative = rows.getString(1);
                    Path target = safeTarget(output, relative);
                    byte[] compressed = rows.getBytes(2);
                    byte[] content = gunzip(compressed);
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
                    Files.write(temporary, content, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    current++;
                    originalBytes += rows.getLong(3);
                    storedBytes += compressed.length;
                    progress.accept(current, total);
                }
            }
            return new ArchiveStats((int) current, originalBytes, storedBytes);
        } catch (SQLException exception) {
            throw new IOException("A HTML-riport nem állítható vissza az SQLite adatbázisból.", exception);
        }
    }

    synchronized String cacheSummary() {
        return "PMD/CPD cache: " + qualityHits + " találat, " + qualityWrites + " új mentés";
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=30000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA temp_store=MEMORY");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS quality_cache("
                    + "repository_key TEXT NOT NULL,commit_hash TEXT NOT NULL,engine_key TEXT NOT NULL,"
                    + "assessment_json TEXT NOT NULL,updated_at TEXT NOT NULL,"
                    + "PRIMARY KEY(repository_key,commit_hash,engine_key))");
            statement.execute("CREATE TABLE IF NOT EXISTS html_asset("
                    + "path TEXT PRIMARY KEY,content_gzip BLOB NOT NULL,original_bytes INTEGER NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS quality_cache_repo ON quality_cache(repository_key,engine_key)");
        }
    }

    private void putMetadata(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static QualityDto toDto(QualityAssessment assessment) {
        return new QualityDto(assessment.status.name(), assessment.score, assessment.grade, assessment.summary,
                assessment.analyzedFiles, assessment.analyzedAddedLines, assessment.analyzedLanguages,
                assessment.notes, assessment.findings.stream().map(finding -> new FindingDto(finding.language,
                        finding.analyzer, finding.rule, finding.ruleSet, finding.message, finding.path,
                        finding.line, finding.priority, finding.externalUrl)).toList());
    }

    private static QualityAssessment fromDto(QualityDto dto) {
        return new QualityAssessment(QualityAssessment.Status.valueOf(dto.status), dto.score, dto.grade,
                dto.summary, dto.analyzedFiles, dto.analyzedAddedLines, dto.languages, dto.notes,
                dto.findings.stream().map(finding -> new QualityFinding(finding.language, finding.analyzer,
                        finding.rule, finding.ruleSet, finding.message, finding.path, finding.line,
                        finding.priority, finding.externalUrl)).toList());
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(input.length, 1024 * 1024));
        try (FastGzipOutputStream gzip = new FastGzipOutputStream(result)) {
            gzip.write(input);
        }
        return result.toByteArray();
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(ByteArrayOutputStream output) throws IOException {
            super(output);
            def.setLevel(1);
        }
    }

    private static byte[] gunzip(byte[] input) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private static Path safeTarget(Path output, String relative) throws IOException {
        Path root = output.toAbsolutePath().normalize();
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Nem biztonságos HTML-útvonal az adatbázisban: " + relative);
        }
        return target;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private void rollbackQuietly() {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            connection.close();
        } catch (SQLException exception) {
            throw new IOException("A SQLite adatbázis nem zárható le szabályosan.", exception);
        }
    }

    record ArchiveStats(int files, long originalBytes, long storedBytes) { }

    private record QualityDto(String status, int score, String grade, String summary, int analyzedFiles,
                              long analyzedAddedLines, Map<String, Integer> languages, List<String> notes,
                              List<FindingDto> findings) { }

    private record FindingDto(String language, String analyzer, String rule, String ruleSet, String message,
                              String path, int line, int priority, String externalUrl) { }
}
