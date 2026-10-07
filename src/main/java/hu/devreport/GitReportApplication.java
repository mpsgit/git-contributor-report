package hu.devreport;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

final class GitReportApplication {
    private static final char RECORD_SEPARATOR = 0x1e;
    static final char UNIT_SEPARATOR = 0x1f;
    private static final char GROUP_SEPARATOR = 0x1d;
    static final Pattern TRAILER = Pattern.compile(
            "(?im)^(Co-authored-by|Reviewed-by|Tested-by|Acked-by|Signed-off-by|Reported-by):\\s*(.*?)\\s*<([^>]+)>\\s*$");
    static final Pattern CONVENTIONAL_COMMIT = Pattern.compile(
            "^(feat|fix|docs|refactor|test|build|ci|chore|perf|style|revert)(?:\\([^)]+\\))?!?:", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_REFERENCE = Pattern.compile("(?:#\\d+|\\b[A-Z][A-Z0-9]+-\\d+\\b)");

    private GitReportApplication() { }

    static void run(CliOptions args) throws IOException, InterruptedException {
        run(args, ProgressReporter.NONE);
    }

    static void run(CliOptions args, ProgressListener listener) throws IOException, InterruptedException {
        boolean reportRequested = args.outputs.contains("html") || args.outputs.contains("markdown");
        boolean sourceRequested = args.outputs.contains("source");
        int phaseCount = 3 + (args.fetch ? 1 : 0) + (reportRequested ? 2 : 0) + (sourceRequested ? 1 : 0);
        int totalWeight = 3 + 5 + (args.fetch ? 12 : 0) + (reportRequested ? 50 + 15 : 0)
                + (sourceRequested ? 25 : 0) + 2;
        ProgressReporter reporter = new ProgressReporter(listener, totalWeight, phaseCount);
        Path root = args.root.toAbsolutePath().normalize();
        Path output = args.output.toAbsolutePath().normalize();
        try {
            ProgressReporter.Stage preparation = reporter.begin(3, "Előkészítés", "Beállítások és Git ellenőrzése");
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("A gyökérkönyvtár nem létezik: " + root);
            }
            requireGit();
            preparation.finish("A Git elérhető; beállítások rendben");

            ProgressReporter.Stage discovery = reporter.begin(5, "Repók keresése", root.toString());
            ConsoleOutput.println("Git repók keresése: " + root);
            List<Path> repositories = discoverRepositories(root, output, discovery);
            if (repositories.isEmpty()) {
                throw new IllegalArgumentException("Nem található Git repó a megadott könyvtár alatt.");
            }
            discovery.finish(repositories.size() + " Git repó található");

            List<String> fetchWarnings = List.of();
            if (args.fetch) {
                ProgressReporter.Stage fetch = reporter.begin(12, "Remote frissítés",
                        repositories.size() + " repó: git fetch --all --prune");
                fetchWarnings = fetchRepositories(repositories, root, fetch);
                fetch.finish("Remote frissítés kész; figyelmeztetések: " + fetchWarnings.size());
            }

            if (!reportRequested && sourceRequested) {
                Files.createDirectories(output);
                ProgressReporter.Stage source = reporter.begin(25, "Forráskód-export", "Branchek felderítése");
                SourceCodeStats sourceStats = SourceCodeExporter.write(output, repositories, root, args, source);
                source.finish(sourceStats.repositories + " branch-pillanatkép elkészült");
                ConsoleOutput.printf("Kész: %d branch-pillanatkép, %d szöveges és %d bináris fájl.%n",
                        sourceStats.repositories, sourceStats.textFiles, sourceStats.binaryFiles);
                ConsoleOutput.println("Forráskód-index: " + output.resolve("source-code-index.md"));
                reporter.complete("A forráskód-export elkészült");
                return;
            }

            Path patchRoot = output.resolve(".patch-cache");
            deleteTree(patchRoot);
            Files.createDirectories(patchRoot);
            Analysis analysis = new Analysis(root, patchRoot);
            analysis.warnings.addAll(fetchWarnings);
            try {
                ProgressReporter.Stage analysisStage = reporter.begin(50, "Git-történet elemzése",
                        repositories.size() + " repó feldolgozása");
                for (int i = 0; i < repositories.size(); i++) {
                    Path repository = repositories.get(i);
                    ConsoleOutput.printf("[%d/%d] Elemzés: %s%n", i + 1, repositories.size(),
                            repositoryDisplayName(root, repository));
                    analyzeRepository(repository, analysis, args, analysisStage, i, repositories.size());
                }
                analysisStage.finish(analysis.seenCommits.size() + " commit, "
                        + analysis.developers.size() + " fejlesztő");

                ProgressReporter.Stage report = reporter.begin(15, "Riportfájlok írása",
                        "HTML és Markdown oldalak előkészítése");
                ReportWriter.write(output, analysis, args, report);
                report.finish("A fejlesztői riportfájlok elkészültek");
                if (sourceRequested) {
                    ProgressReporter.Stage source = reporter.begin(25, "Forráskód-export", "Branchek felderítése");
                    SourceCodeStats sourceStats = SourceCodeExporter.write(output, repositories, root, args, source);
                    source.finish(sourceStats.repositories + " branch-pillanatkép elkészült");
                    ConsoleOutput.printf("Forráskód: %d branch-pillanatkép.%n", sourceStats.repositories);
                }
                ConsoleOutput.printf("Kész: %d repó, %d egyedi fejlesztő, %d repón belül egyedi commit.%n",
                        analysis.repositories.size(), analysis.developers.size(), analysis.seenCommits.size());
                if (args.outputs.contains("html")) ConsoleOutput.println("HTML-riport: " + output.resolve("index.html"));
                if (args.outputs.contains("markdown")) ConsoleOutput.println("Markdown-riport: " + output.resolve("index.md"));
                reporter.complete("Minden kiválasztott kimenet elkészült");
            } finally {
                deleteTree(patchRoot);
            }
        } catch (IOException | InterruptedException | RuntimeException exception) {
            reporter.failed(oneLine(exception.getMessage()));
            throw exception;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.exists(root)) FileUtils.deleteDirectory(root.toFile());
    }

    private static void requireGit() throws IOException, InterruptedException {
        CommandResult result = execute(null, List.of("git", "--version"));
        if (result.exitCode != 0) {
            throw new IllegalArgumentException("A Git nem érhető el a PATH-on.");
        }
    }

    private static List<String> fetchRepositories(List<Path> repositories, Path root,
                                                  ProgressReporter.Stage progress)
            throws IOException, InterruptedException {
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < repositories.size(); i++) {
            Path repository = repositories.get(i);
            String displayName = repositoryDisplayName(root, repository);
            progress.update(i / (double) repositories.size(),
                    "Repó " + (i + 1) + "/" + repositories.size() + " | " + displayName,
                    i + 1, repositories.size());
            ConsoleOutput.printf("[%d/%d] Remote frissítése: %s%n",
                    i + 1, repositories.size(), displayName);
            CommandResult result = git(repository, "fetch", "--all", "--prune");
            if (result.exitCode != 0) {
                String warning = displayName + ": a fetch sikertelen, a riport a helyi Git-adatokból készül: "
                        + oneLine(sanitizeRemoteUrl(result.stderr));
                warnings.add(warning);
                ConsoleOutput.err().println("Figyelmeztetés: " + warning);
            }
            progress.update((i + 1) / (double) repositories.size(),
                    "Repó " + (i + 1) + "/" + repositories.size() + " kész | " + displayName,
                    i + 1, repositories.size());
        }
        return warnings;
    }

    private static String repositoryDisplayName(Path root, Path repository) {
        String displayName = root.relativize(repository).toString();
        return displayName.isBlank() ? repository.getFileName().toString() : displayName;
    }

    static List<Path> discoverRepositories(Path root, Path output) throws IOException {
        return discoverRepositories(root, output, null);
    }

    private static List<Path> discoverRepositories(Path root, Path output, ProgressReporter.Stage progress)
            throws IOException {
        List<Path> found = new ArrayList<>();
        long[] visitedDirectories = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                visitedDirectories[0]++;
                if (progress != null && (visitedDirectories[0] == 1 || visitedDirectories[0] % 50 == 0)) {
                    progress.indeterminate("Vizsgált mappák: " + visitedDirectories[0] + " | Talált repók: "
                            + found.size() + " | " + dir, visitedDirectories[0]);
                }
                Path normalized = dir.toAbsolutePath().normalize();
                if (normalized.equals(output) || normalized.startsWith(output)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                    found.add(dir.getParent());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(root) && Files.isDirectory(dir.resolve("objects")) && Files.isRegularFile(dir.resolve("HEAD"))) {
                    found.add(dir); // bare repository
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(".git")) {
                    found.add(file.getParent()); // worktree/submodule .git pointer
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found.stream().distinct().sorted().toList();
    }

    private static void analyzeRepository(Path repo, Analysis analysis, CliOptions args,
                                          ProgressReporter.Stage progress, int repositoryIndex,
                                          int repositoryCount)
            throws IOException, InterruptedException {
        String displayName = repositoryDisplayName(analysis.root, repo);
        updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 0.02,
                displayName + " | Branchek beolvasása", repositoryIndex + 1, repositoryCount);

        CommandResult branchResult = git(repo, "for-each-ref", "--format=%(refname)%1f%(refname:short)", "refs/heads", "refs/remotes");
        List<BranchRef> branches = branchResult.stdout.lines().map(String::trim).filter(s -> !s.isBlank())
                .map(line -> line.split(String.valueOf(UNIT_SEPARATOR), 2))
                .filter(parts -> parts.length == 2 && !parts[1].endsWith("/HEAD"))
                .map(parts -> new BranchRef(parts[0], parts[1], parts[0].startsWith("refs/remotes/")))
                .distinct().sorted(Comparator.comparing(BranchRef::shortName)).toList();

        updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 0.10,
                displayName + " | Commitelőzmények lekérése", repositoryIndex + 1, repositoryCount);
        List<String> command = new ArrayList<>(List.of(
                "log", "--all", "--use-mailmap", "--no-renames", "--no-textconv", "--no-ext-diff", "--date=iso-strict",
                "--pretty=format:%x1e%H%x1f%aN%x1f%aE%x1f%aI%x1f%cN%x1f%cE%x1f%cI%x1f%P%x1f%B%x1d",
                "--numstat"));
        if (args.since != null) command.add("--since=" + args.since);
        if (args.until != null) command.add("--until=" + args.until);
        CommandResult logResult = git(repo, command.toArray(String[]::new));
        if (logResult.exitCode != 0) {
            analysis.warnings.add(displayName + ": " + oneLine(logResult.stderr));
            return;
        }

        String originUrl = sanitizeRemoteUrl(git(repo, "config", "--get", "remote.origin.url").stdout.trim());
        RepositoryStats repositoryStats = new RepositoryStats(displayName, repo, originUrl, branches.size());
        analysis.repositories.add(repositoryStats);
        Map<String, Commit> localCommits = new HashMap<>();
        String[] rawRecords = logResult.stdout.split(String.valueOf(RECORD_SEPARATOR), -1);
        int processedRecords = 0;
        for (String rawRecord : rawRecords) {
            if (rawRecord.isBlank()) continue;
            processedRecords++;
            Commit commit = parseCommit(rawRecord);
            if (commit == null) continue;
            repositoryStats.reachableCommits++;
            localCommits.put(commit.hash, commit);
            if (!analysis.seenCommits.add(repo.toAbsolutePath().normalize() + "|" + commit.hash)) continue;
            repositoryStats.uniqueCommits++;
            applyCommit(analysis, repositoryStats, commit);
            if (processedRecords == 1 || processedRecords % 250 == 0) {
                double recordFraction = processedRecords / (double) Math.max(1, rawRecords.length - 1);
                updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 0.15 + recordFraction * 0.40,
                        displayName + " | Commitok: " + processedRecords + "/" + (rawRecords.length - 1),
                        processedRecords, Math.max(1, rawRecords.length - 1));
            }
        }
        if (args.includePatches) {
            updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 0.58,
                    displayName + " | Teljes diffek kinyerése: " + localCommits.size() + " commit",
                    localCommits.size(), localCommits.size());
            extractPatches(repo, repositoryStats, localCommits.keySet(), analysis, args);
        }
        updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 0.78,
                displayName + " | Branch-elérhetőség: 0/" + branches.size(), 0, branches.size());
        analyzeBranches(repo, branches, localCommits, analysis, repositoryStats,
                (completed, total, branch) -> updateRepositoryProgress(progress, repositoryIndex, repositoryCount,
                        0.78 + (total == 0 ? 0.22 : completed / (double) total * 0.22),
                        displayName + " | Branchek: " + completed + "/" + total + " | " + branch,
                        completed, total));
        updateRepositoryProgress(progress, repositoryIndex, repositoryCount, 1,
                displayName + " | Kész: " + repositoryStats.uniqueCommits + " commit, "
                        + branches.size() + " branch", repositoryIndex + 1, repositoryCount);
    }

    private static void updateRepositoryProgress(ProgressReporter.Stage progress, int repositoryIndex,
                                                 int repositoryCount, double repositoryFraction,
                                                 String detail, long current, long total) {
        double overall = (repositoryIndex + Math.max(0, Math.min(1, repositoryFraction)))
                / Math.max(1, repositoryCount);
        progress.update(overall, "Repó " + (repositoryIndex + 1) + "/" + repositoryCount + " | " + detail,
                current, total);
    }

    private static Commit parseCommit(String raw) {
        int statsStart = raw.indexOf(GROUP_SEPARATOR);
        if (statsStart < 0) return null;
        String header = raw.substring(0, statsStart);
        String stats = raw.substring(statsStart + 1);
        String[] fields = header.split(String.valueOf(UNIT_SEPARATOR), 9);
        if (fields.length < 9) return null;

        long additions = 0;
        long deletions = 0;
        int files = 0;
        Map<String, FileDelta> fileTypes = new HashMap<>();
        List<FileChange> fileChanges = new ArrayList<>();
        for (String line : stats.lines().map(String::trim).filter(s -> !s.isBlank()).toList()) {
            String[] values = line.split("\\t", 3);
            if (values.length < 3) continue;
            files++;
            long added = values[0].equals("-") ? 0 : parseLong(values[0]);
            long deleted = values[1].equals("-") ? 0 : parseLong(values[1]);
            additions += added;
            deletions += deleted;
            fileChanges.add(new FileChange(values[2], added, deleted, values[0].equals("-") || values[1].equals("-")));
            fileTypes.computeIfAbsent(extension(values[2]), ignored -> new FileDelta()).add(added, deleted);
        }
        String subject = fields[8].lines().findFirst().orElse("(nincs commit üzenet)").trim();
        return new Commit(fields[0].trim(), fields[1], fields[2], parseInstant(fields[3]),
                fields[4], fields[5], parseInstant(fields[6]), fields[7], fields[8], subject,
                additions, deletions, files, fileTypes, fileChanges);
    }

    private static void applyCommit(Analysis analysis, RepositoryStats repository, Commit commit) {
        Developer author = analysis.developer(commit.authorName, commit.authorEmail);
        author.authoredCommits++;
        author.additions += commit.additions;
        author.deletions += commit.deletions;
        author.filesChanged += commit.files;
        author.repositories.add(repository.name);
        author.activeDays.add(commit.authorDate.atZone(ZoneId.systemDefault()).toLocalDate());
        author.participationDays.add(commit.authorDate.atZone(ZoneId.systemDefault()).toLocalDate());
        author.observe(commit.authorDate);
        if (!commit.parents.isBlank() && commit.parents.trim().contains(" ")) author.mergeCommits++;
        else author.nonMergeCommits++;
        repository.authors.add(author.key);

        ContributionStats developerRepo = author.byRepository.computeIfAbsent(repository.name, ignored -> new ContributionStats());
        applyAuthoredStats(developerRepo, commit);
        applyAuthoredStats(repository.total, commit);
        repository.byDeveloper.put(author.key, developerRepo);
        author.monthly.computeIfAbsent(YearMonth.from(commit.authorDate.atZone(ZoneId.systemDefault())), ignored -> new ContributionStats());
        applyAuthoredStats(author.monthly.get(YearMonth.from(commit.authorDate.atZone(ZoneId.systemDefault()))), commit);
        int weekday = commit.authorDate.atZone(ZoneId.systemDefault()).getDayOfWeek().getValue() - 1;
        int hour = commit.authorDate.atZone(ZoneId.systemDefault()).getHour();
        author.weekdays[weekday]++;
        author.hours[hour]++;
        commit.fileTypes.forEach((type, delta) -> author.fileTypes.computeIfAbsent(type, ignored -> new FileDelta()).merge(delta));
        commit.fileTypes.forEach((type, delta) -> repository.fileTypes.computeIfAbsent(type, ignored -> new FileDelta()).merge(delta));
        Matcher conventional = CONVENTIONAL_COMMIT.matcher(commit.subject);
        if (conventional.find()) author.commitTypes.merge(conventional.group(1).toLowerCase(Locale.ROOT), 1L, Long::sum);
        else author.commitTypes.merge("egyéb", 1L, Long::sum);
        if (ISSUE_REFERENCE.matcher(commit.subject).find()) author.issueLinkedCommits++;
        CommitSummary summary = new CommitSummary(commit.hash, repository.name, commit.authorDate, commit.subject,
                commit.message, commit.authorName, commit.authorEmail, commit.committerName, commit.committerEmail,
                commit.files, commit.additions, commit.deletions, !commit.parents.isBlank() && commit.parents.trim().contains(" "),
                commit.fileChanges, analysis.patchRef(repository.name, commit.hash));
        author.commits.add(summary);
        repository.commits.add(summary);

        Matcher matcher = TRAILER.matcher(commit.message);
        Set<String> seenTrailerRoles = new HashSet<>();
        while (matcher.find()) {
            String role = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!role.equals("co-authored-by")) continue;
            Developer participant = analysis.developer(matcher.group(2), matcher.group(3));
            String marker = participant.key + "|" + role;
            if (!seenTrailerRoles.add(marker)) continue;
            participant.repositories.add(repository.name);
            participant.observe(commit.authorDate);
            participant.participationDays.add(commit.authorDate.atZone(ZoneId.systemDefault()).toLocalDate());
            participant.roles.merge(role, 1L, Long::sum);
            ContributionStats participantRepo = participant.byRepository.computeIfAbsent(repository.name, ignored -> new ContributionStats());
            participantRepo.roles.merge(role, 1L, Long::sum);
            participantRepo.activeDays.add(commit.authorDate.atZone(ZoneId.systemDefault()).toLocalDate());
            participantRepo.observe(commit.authorDate);
            repository.byDeveloper.put(participant.key, participantRepo);
            repository.roleParticipants.add(participant.key);
            boolean alreadyListed = participant.commits.stream()
                    .anyMatch(existing -> existing.hash.equals(summary.hash) && existing.repository.equals(summary.repository));
            if (!alreadyListed) participant.commits.add(summary);
        }
    }

    private static void applyAuthoredStats(ContributionStats stats, Commit commit) {
        stats.commits++;
        stats.files += commit.files;
        stats.additions += commit.additions;
        stats.deletions += commit.deletions;
        stats.activeDays.add(commit.authorDate.atZone(ZoneId.systemDefault()).toLocalDate());
        if (!commit.parents.isBlank() && commit.parents.trim().contains(" ")) stats.merges++;
        stats.observe(commit.authorDate);
    }

    private static void extractPatches(Path repo, RepositoryStats repository, Set<String> hashes,
                                      Analysis analysis, CliOptions args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-C", repo.toString(),
                "log", "--all", "--no-renames", "--no-textconv", "--no-ext-diff",
                "--pretty=format:%x1e%H%x1d", "--patch"));
        if (args.since != null) command.add("--since=" + args.since);
        if (args.until != null) command.add("--until=" + args.until);
        Path packFile = analysis.patchPack(repository.name);
        Files.createDirectories(packFile.getParent());
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectOutput(packFile.toFile()).start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread errThread = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), stderr));
        int exit = process.waitFor();
        errThread.join();
        if (exit != 0) {
            analysis.warnings.add(repository.name + " patch-ek: " + oneLine(stderr.toString(StandardCharsets.UTF_8)));
            throw new IOException("A Git patch-feldolgozás sikertelen: " + repository.name);
        }
        indexPatchPack(packFile, repository.name, hashes, analysis);
    }

    private static void indexPatchPack(Path packFile, String repository, Set<String> hashes, Analysis analysis)
            throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(packFile), 8 * 1024 * 1024)) {
            ByteArrayOutputStream header = new ByteArrayOutputStream(64);
            PatchRef currentPatch = null;
            long absolutePosition = 0;
            boolean inRecord = false;
            boolean headerComplete = false;
            byte[] buffer = new byte[8 * 1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                for (int i = 0; i < read; i++, absolutePosition++) {
                    int value = buffer[i] & 0xff;
                    if (value == RECORD_SEPARATOR) {
                        if (currentPatch != null) currentPatch.length = absolutePosition - currentPatch.offset;
                        currentPatch = null;
                        header.reset();
                        inRecord = true;
                        headerComplete = false;
                        continue;
                    }
                    if (!inRecord) continue;
                    if (!headerComplete) {
                        if (value == GROUP_SEPARATOR) {
                            String hash = header.toString(StandardCharsets.UTF_8).trim();
                            headerComplete = true;
                            if (hashes.contains(hash)) {
                                currentPatch = analysis.patchRef(repository, hash);
                                currentPatch.offset = absolutePosition + 1;
                            }
                        } else {
                            header.write(value);
                        }
                    }
                }
            }
            if (currentPatch != null) currentPatch.length = absolutePosition - currentPatch.offset;
        }
    }

    private static void analyzeBranches(Path repo, List<BranchRef> branches, Map<String, Commit> commits,
                                        Analysis analysis, RepositoryStats repository,
                                        BranchProgress progress)
            throws IOException, InterruptedException {
        for (int branchIndex = 0; branchIndex < branches.size(); branchIndex++) {
            BranchRef branch = branches.get(branchIndex);
            CommandResult result = git(repo, "rev-list", branch.fullName);
            if (result.exitCode != 0) {
                analysis.warnings.add(repository.name + " / " + branch.shortName + ": " + oneLine(result.stderr));
                progress.update(branchIndex + 1, branches.size(), branch.shortName + " (nem olvasható)");
                continue;
            }
            BranchStats stats = new BranchStats(branch.shortName, branch.remote);
            for (String hash : result.stdout.lines().map(String::trim).filter(s -> !s.isBlank()).toList()) {
                Commit commit = commits.get(hash);
                if (commit == null) continue; // outside the selected date window
                stats.commits++;
                Developer author = analysis.developer(commit.authorName, commit.authorEmail);
                stats.authors.add(author.key);
                author.branchRefs.add(repository.name + " :: " + branch.shortName);
                author.byRepository.computeIfAbsent(repository.name, ignored -> new ContributionStats())
                        .branches.add(branch.shortName);
            }
            repository.branchStats.add(stats);
            progress.update(branchIndex + 1, branches.size(), branch.shortName);
        }
    }

    @FunctionalInterface
    private interface BranchProgress {
        void update(int completed, int total, String branch);
    }

    static String extension(String path) {
        String clean = path.replace('\\', '/');
        int brace = clean.lastIndexOf(" => ");
        if (brace >= 0) clean = clean.substring(brace + 4).replace("}", "");
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "(nincs kiterjesztés)";
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeRemoteUrl(String url) {
        if (url == null || url.isBlank()) return "";
        return url.replaceFirst("(?i)^(https?://)[^/@]+@", "$1***@")
                .replaceAll("(?i)([?&](?:access_token|token|key|password)=)[^&]+", "$1***");
    }

    private static CommandResult git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        return execute(repo, command);
    }

    static CommandResult execute(Path directory, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        Process process = builder.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outThread = Thread.ofVirtual().start(() -> copy(process.getInputStream(), stdout));
        Thread errThread = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), stderr));
        int exit = process.waitFor();
        outThread.join();
        errThread.join();
        return new CommandResult(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    static void copy(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            IOUtils.copy(input, output);
        } catch (IOException ignored) { }
    }

    static long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return 0; }
    }

    private static Instant parseInstant(String value) {
        try { return OffsetDateTime.parse(value.trim()).toInstant(); }
        catch (Exception e) { return Instant.EPOCH; }
    }

    static String oneLine(String value) {
        return value == null ? "ismeretlen Git hiba" : value.replaceAll("\\s+", " ").trim();
    }

}
