package hu.devreport;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CPDReport;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PMD and CPD based assessment of source lines introduced by individual commits. */
final class CommitQualityAnalyzer {
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    private static final int CPD_MINIMUM_TOKENS = 50;
    private static final String CPD_URL = "https://pmd.github.io/pmd/pmd_userdocs_cpd.html";
    private static final String[] RULESETS = {
            "category/java/bestpractices.xml", "category/java/design.xml", "category/java/errorprone.xml",
            "category/java/multithreading.xml", "category/java/performance.xml", "category/java/security.xml",
            "category/ecmascript/bestpractices.xml", "category/ecmascript/codestyle.xml",
            "category/ecmascript/errorprone.xml", "category/ecmascript/performance.xml",
            "category/html/bestpractices.xml",
            "category/plsql/bestpractices.xml", "category/plsql/codestyle.xml",
            "category/plsql/design.xml", "category/plsql/errorprone.xml"
    };

    private CommitQualityAnalyzer() { }

    static void analyze(Path repository, RepositoryStats stats, Path workRoot, QualityProgress progress)
            throws IOException, InterruptedException {
        analyze(repository, stats, workRoot, 8, null, "", progress);
    }

    static void analyze(Path repository, RepositoryStats stats, Path workRoot, int maxWorkers,
                        QualityProgress progress) throws IOException, InterruptedException {
        analyze(repository, stats, workRoot, maxWorkers, null, "", progress);
    }

    static void analyze(Path repository, RepositoryStats stats, Path workRoot, int maxWorkers,
                        ReportDatabase database, String repositoryKey, QualityProgress progress)
            throws IOException, InterruptedException {
        Path repositoryWork = workRoot.resolve(Integer.toUnsignedString(repository.toString().hashCode(), 36));
        Files.createDirectories(repositoryWork);
        int total = stats.commits.size();
        Map<String, QualityAssessment> cached = database == null ? Map.of()
                : database.loadQuality(repositoryKey, stats.commits.stream().map(commit -> commit.hash)
                .collect(java.util.stream.Collectors.toSet()));
        for (CommitSummary commit : stats.commits) {
            QualityAssessment assessment = cached.get(commit.hash);
            if (assessment != null) commit.quality = assessment;
        }
        List<CommitSummary> missing = stats.commits.stream().filter(commit -> !cached.containsKey(commit.hash)).toList();
        int workers = Math.min(workerCount(missing.size(), Runtime.getRuntime().availableProcessors()),
                Math.max(1, maxWorkers));
        ParallelSupport.Activity activity = ParallelSupport.activity(missing.size(), workers);
        AtomicInteger completed = new AtomicInteger(cached.size());
        synchronized (progress) {
            progress.update(completed.get(), total, "indul",
                    activity.label() + " | DB cache: " + cached.size() + "/" + total);
        }
        if (missing.isEmpty()) return;
        ExecutorService executor = Executors.newFixedThreadPool(workers,
                Thread.ofVirtual().name("commit-quality-", 0).factory());
        try {
            List<Future<?>> futures = new ArrayList<>(missing.size());
            for (int index = 0; index < missing.size(); index++) {
                CommitSummary commit = missing.get(index);
                Path commitWork = repositoryWork.resolve(index + "-" + commit.hash.substring(0, Math.min(12, commit.hash.length())));
                futures.add(executor.submit(() -> {
                    try (ParallelSupport.Scope ignored = activity.start()) {
                        try {
                            Files.createDirectories(commitWork);
                            try (GitBlobReader blobs = new GitBlobReader(repository)) {
                                commit.quality = analyzeCommit(commit, commitWork, blobs);
                            }
                            if (database != null) {
                                try {
                                    database.saveQuality(repositoryKey, commit.hash, commit.quality);
                                } catch (IOException cacheError) {
                                    ConsoleOutput.println("Figyelmeztetés: a PMD/CPD cache nem írható: "
                                            + conciseMessage(cacheError));
                                }
                            }
                        } catch (Exception exception) {
                            commit.quality = QualityAssessment.failed("A statikus elemzés nem fejeződött be: "
                                    + conciseMessage(exception));
                        } finally {
                            try { deleteTree(commitWork); } catch (IOException ignoredDelete) { }
                            int done = completed.incrementAndGet();
                            synchronized (progress) {
                                progress.update(done, total, commit.hash, activity.label()
                                        + " | DB cache: " + cached.size() + "/" + total);
                            }
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    throw new IOException("A párhuzamos kódminőség-elemzés megszakadt.", exception.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
            deleteTree(repositoryWork);
        }
    }

    private static QualityAssessment analyzeCommit(CommitSummary commit, Path sourceRoot, GitBlobReader blobs)
            throws IOException {
        Map<String, List<LineRange>> addedRanges = addedSourceRanges(readPatch(commit.patch));
        if (addedRanges.isEmpty()) {
            return QualityAssessment.notApplicable("A commit nem adott hozzá elemezhető Java-, SQL-, HTML-, JavaScript-, TypeScript- vagy PL/SQL-forrássort.");
        }

        Map<Path, String> materializedFiles = new LinkedHashMap<>();
        Map<String, Integer> languages = new TreeMap<>();
        long addedLines = 0;
        for (Map.Entry<String, List<LineRange>> entry : addedRanges.entrySet()) {
            byte[] content = blobs.read(commit.hash + ":" + entry.getKey());
            if (content == null) continue;
            Path target = safeTarget(sourceRoot, entry.getKey());
            Files.createDirectories(target.getParent());
            try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                output.write(content);
            }
            materializedFiles.put(target.toAbsolutePath().normalize(), entry.getKey());
            languages.merge(languageForPath(entry.getKey()), 1, Integer::sum);
            addedLines += entry.getValue().stream().mapToLong(LineRange::length).sum();
        }
        if (materializedFiles.isEmpty()) {
            return QualityAssessment.notApplicable("A commit forrásváltozása törölt vagy nem olvasható fájlra esik.");
        }

        List<QualityFinding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        runPmd(sourceRoot, materializedFiles, addedRanges, findings, notes);
        runCpd(sourceRoot, materializedFiles, addedRanges, findings, notes);
        findings.sort(Comparator.comparingInt((QualityFinding finding) -> finding.priority)
                .thenComparing(finding -> finding.path).thenComparingInt(finding -> finding.line)
                .thenComparing(finding -> finding.rule));
        return assessment(materializedFiles.size(), addedLines, languages, notes, findings);
    }

    static int workerCount(int commits, int processors) {
        return Math.max(1, Math.min(Math.min(commits, Math.max(1, processors)), 8));
    }

    private static void runPmd(Path sourceRoot, Map<Path, String> files,
                               Map<String, List<LineRange>> addedRanges, List<QualityFinding> findings,
                               List<String> notes) {
        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setSourceEncoding(StandardCharsets.UTF_8);
        // A commitok külső worker poolban futnak párhuzamosan; commiton belül egy PMD-szál
        // megakadályozza a workers × PMD threads túlpörgetést és a memóriacsúcsokat.
        configuration.setThreads(1);
        configuration.setIgnoreIncrementalAnalysis(true);
        configuration.setFailOnError(false);
        configuration.setFailOnViolation(false);
        configuration.addRelativizeRoot(sourceRoot);
        for (String ruleset : RULESETS) configuration.addRuleSet(ruleset);

        Report report;
        try (PmdAnalysis analysis = PmdAnalysis.create(configuration)) {
            files.entrySet().stream().filter(entry -> supportsPmd(entry.getValue()))
                    .forEach(entry -> analysis.files().addFile(entry.getKey()));
            report = analysis.performAnalysisAndCollectReport();
        } catch (RuntimeException exception) {
            notes.add("PMD szabályelemzés: " + conciseMessage(exception));
            return;
        }

        for (Report.ProcessingError error : report.getProcessingErrors()) {
            notes.add("PMD nem tudta teljesen feldolgozni az egyik fájlt: " + error.getMsg());
        }
        for (RuleViolation violation : report.getViolations()) {
            Path violationFile = pathOrNull(violation.getFileId().getAbsolutePath());
            String gitPath = violationFile == null ? null : files.get(violationFile.toAbsolutePath().normalize());
            if (gitPath == null) gitPath = findBySuffix(files, violation.getFileId().getOriginalPath());
            if (gitPath == null || !contains(addedRanges.get(gitPath), violation.getBeginLine())) continue;
            String url = violation.getRule().getExternalInfoUrl();
            findings.add(new QualityFinding(languageForPath(gitPath), "PMD", violation.getRule().getName(),
                    violation.getRule().getRuleSetName(), violation.getDescription(), gitPath,
                    violation.getBeginLine(), violation.getRule().getPriority().getPriority(),
                    url == null ? "" : url));
        }
    }

    private static void runCpd(Path sourceRoot, Map<Path, String> files,
                               Map<String, List<LineRange>> addedRanges, List<QualityFinding> findings,
                               List<String> notes) {
        CPDConfiguration configuration = new CPDConfiguration();
        configuration.setSourceEncoding(StandardCharsets.UTF_8);
        configuration.setMinimumTileSize(CPD_MINIMUM_TOKENS);
        configuration.setSkipLexicalErrors(true);
        configuration.addRelativizeRoot(sourceRoot);
        CPDReport[] collected = new CPDReport[1];
        try (CpdAnalysis analysis = CpdAnalysis.create(configuration)) {
            files.keySet().forEach(path -> analysis.files().addFile(path));
            analysis.performAnalysis(report -> collected[0] = report);
        } catch (RuntimeException | IOException exception) {
            notes.add("PMD CPD másolatfelismerés: " + conciseMessage(exception));
            return;
        }
        if (collected[0] == null) return;
        for (Report.ProcessingError error : collected[0].getProcessingErrors()) {
            notes.add("CPD nem tudta teljesen feldolgozni az egyik fájlt: " + error.getMsg());
        }
        Set<String> seen = new HashSet<>();
        for (Match match : collected[0].getMatches()) {
            for (Mark mark : match.getMarkSet()) {
                String gitPath = findBySuffix(files, mark.getLocation().getFileId().getOriginalPath());
                int firstLine = mark.getLocation().getStartLine();
                int lastLine = mark.getLocation().getEndLine();
                if (gitPath == null || !overlaps(addedRanges.get(gitPath), firstLine, lastLine)) continue;
                String key = gitPath + ':' + firstLine + ':' + lastLine;
                if (!seen.add(key)) continue;
                findings.add(new QualityFinding(languageForPath(gitPath), "PMD CPD", "CopyPasteDetector",
                        "Másolt kód", "%d soros, %d tokenes ismétlődő kódrészlet."
                        .formatted(match.getLineCount(), match.getTokenCount()), gitPath, firstLine, 3, CPD_URL));
            }
        }
    }

    private static QualityAssessment assessment(int files, long addedLines, Map<String, Integer> languages,
                                                List<String> notes, List<QualityFinding> findings) {
        int weighted = findings.stream().mapToInt(finding -> switch (finding.priority) {
            case 1 -> 25; case 2 -> 14; case 3 -> 8; case 4 -> 4; default -> 2;
        }).sum();
        int penalty = (int) Math.min(100, Math.ceil(weighted * 100d / Math.max(50, addedLines)));
        int score = Math.max(0, 100 - penalty);
        String grade;
        String judgment;
        if (score >= 90) { grade = "A"; judgment = "erős"; }
        else if (score >= 75) { grade = "B"; judgment = "jó"; }
        else if (score >= 60) { grade = "C"; judgment = "közepes"; }
        else if (score >= 40) { grade = "D"; judgment = "kockázatos"; }
        else { grade = "E"; judgment = "kritikus felülvizsgálatot igényel"; }
        String summary = findings.isEmpty()
                ? "A PMD/CPD nem talált megállapítást a commit támogatott nyelveken hozzáadott sorain."
                : "A statikus elemzés %d megállapítást talált; a változás minősítése %s."
                .formatted(findings.size(), judgment);
        return new QualityAssessment(QualityAssessment.Status.COMPLETE, score, grade, summary,
                files, addedLines, languages, notes, findings);
    }

    static Map<String, List<LineRange>> addedSourceRanges(String patch) {
        Map<String, List<LineRange>> result = new LinkedHashMap<>();
        String path = null;
        int newLine = -1;
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("diff --git ")) {
                path = null;
                newLine = -1;
            } else if (line.startsWith("+++ ")) {
                String candidate = line.substring(4).trim();
                if (candidate.startsWith("b/")) candidate = candidate.substring(2);
                path = candidate.equals("/dev/null") || !isSupported(candidate) ? null : unquoteGitPath(candidate);
                newLine = -1;
            } else if (path != null) {
                Matcher matcher = HUNK.matcher(line);
                if (matcher.find()) {
                    newLine = Integer.parseInt(matcher.group(1));
                } else if (newLine >= 0 && line.startsWith("+") && !line.startsWith("+++")) {
                    addLine(result.computeIfAbsent(path, ignored -> new ArrayList<>()), newLine++);
                } else if (newLine >= 0 && !line.startsWith("-") && !line.startsWith("\\")) {
                    newLine++;
                }
            }
        }
        return result;
    }

    /** Kept for source compatibility with older tests/extensions. */
    static Map<String, List<LineRange>> addedJavaRanges(String patch) {
        Map<String, List<LineRange>> javaOnly = new LinkedHashMap<>();
        addedSourceRanges(patch).forEach((path, ranges) -> {
            if (path.toLowerCase(Locale.ROOT).endsWith(".java")) javaOnly.put(path, ranges);
        });
        return javaOnly;
    }

    private static boolean isSupported(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return List.of(".java", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".html", ".htm", ".sql",
                ".pls", ".plsql", ".pkb", ".pks", ".pkg", ".prc", ".fnc", ".trg")
                .stream().anyMatch(lower::endsWith);
    }

    private static boolean supportsPmd(String path) {
        return !languageForPath(path).equals("TypeScript");
    }

    private static String languageForPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) return "JavaScript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "TypeScript";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        if (lower.endsWith(".sql")) return "SQL";
        return "PL/SQL";
    }

    private static void addLine(List<LineRange> ranges, int line) {
        if (!ranges.isEmpty()) {
            LineRange previous = ranges.getLast();
            if (previous.end + 1 == line) {
                ranges.set(ranges.size() - 1, new LineRange(previous.start, line));
                return;
            }
        }
        ranges.add(new LineRange(line, line));
    }

    private static String unquoteGitPath(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return path;
    }

    private static boolean contains(List<LineRange> ranges, int line) {
        return ranges != null && ranges.stream().anyMatch(range -> range.contains(line));
    }

    private static boolean overlaps(List<LineRange> ranges, int start, int end) {
        return ranges != null && ranges.stream().anyMatch(range -> range.start <= end && range.end >= start);
    }

    private static String findBySuffix(Map<Path, String> files, String path) {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        return files.values().stream().filter(normalized::endsWith).findFirst().orElse(null);
    }

    private static Path pathOrNull(String value) {
        try { return value == null || value.isBlank() ? null : Path.of(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Path safeTarget(Path root, String gitPath) throws IOException {
        Path target = root.resolve(gitPath.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root.normalize())) throw new IOException("Nem biztonságos Git-fájlútvonal: " + gitPath);
        return target;
    }

    private static String readPatch(PatchRef patch) throws IOException {
        if (patch == null || patch.length <= 0 || patch.length > Integer.MAX_VALUE || !Files.exists(patch.file)) return "";
        try (FileChannel channel = FileChannel.open(patch.file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) patch.length);
            channel.position(patch.offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    private static String conciseMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.replaceAll("\\s+", " ").trim();
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    record LineRange(int start, int end) {
        boolean contains(int line) { return line >= start && line <= end; }
        int length() { return end - start + 1; }
    }

    @FunctionalInterface
    interface QualityProgress { void update(int completed, int total, String commit, String activity); }

    private static final class GitBlobReader implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedInputStream output;
        private final ByteArrayOutputStream error = new ByteArrayOutputStream();
        private final Thread errorReader;

        GitBlobReader(Path repository) throws IOException {
            process = new ProcessBuilder("git", "-c", "core.quotepath=false", "-C", repository.toString(), "cat-file", "--batch").start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedInputStream(process.getInputStream(), 1024 * 1024);
            errorReader = Thread.ofVirtual().start(() -> {
                try { process.getErrorStream().transferTo(error); } catch (IOException ignored) { }
            });
        }

        byte[] read(String object) throws IOException {
            input.write(object);
            input.newLine();
            input.flush();
            String header = readLine(output);
            if (header == null) throw new IOException("A git cat-file váratlanul befejeződött.");
            if (header.endsWith(" missing")) return null;
            int lastSpace = header.lastIndexOf(' ');
            if (lastSpace < 0) throw new IOException("Ismeretlen git cat-file válasz: " + header);
            long size = Long.parseLong(header.substring(lastSpace + 1));
            if (size > Integer.MAX_VALUE) throw new IOException("Túl nagy forrásfájl: " + object);
            byte[] bytes = output.readNBytes((int) size);
            if (bytes.length != size) throw new IOException("Csonka git objektum: " + object);
            if (output.read() != '\n') throw new IOException("Hibás git cat-file objektumhatár: " + object);
            return bytes;
        }

        private static String readLine(BufferedInputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') break;
                if (value != '\r') line.write(value);
            }
            return value == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException, InterruptedException {
            try { input.close(); output.close(); }
            finally { process.destroy(); process.waitFor(); errorReader.join(); }
            if (process.exitValue() != 0 && error.size() > 0) throw new IOException("git cat-file: " + error.toString(StandardCharsets.UTF_8).trim());
        }
    }
}
