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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hu.devreport.GitReportApplication.*;
import static hu.devreport.ReportWriter.*;

final class SourceCodeExporter {
    static SourceCodeStats write(Path output, List<Path> repositories, Path root, CliOptions args,
                                 ProgressReporter.Stage progress)
            throws IOException, InterruptedException {
        Files.createDirectories(output);
        progress.update(0, "Branchek felderítése " + repositories.size() + " repóban", 0, repositories.size());
        List<SourceBranch> branches = sourceBranches(repositories, root, args, progress);
        if (branches.isEmpty()) throw new IOException("Nem található kiírható branch.");
        progress.update(0.12, branches.size() + " exportálandó branch található", 0, branches.size());
        Set<String> usedNames = new HashSet<>();
        Map<SourceBranch, String> filenames = new LinkedHashMap<>();
        boolean multipleRepositories = repositories.size() > 1;
        for (SourceBranch branch : branches) {
            String prefix = multipleRepositories ? slug(branch.repositoryName()) + "--" : "";
            String base = "source-code-" + prefix + slug(branch.branchName());
            String filename = base + ".md";
            int suffix = 2;
            while (!usedNames.add(filename.toLowerCase(Locale.ROOT))) filename = base + "-" + suffix++ + ".md";
            filenames.put(branch, filename);
        }

        long textFiles = 0;
        long binaryFiles = 0;
        long totalBytes = 0;
        for (int branchIndex = 0; branchIndex < branches.size(); branchIndex++) {
            SourceBranch branch = branches.get(branchIndex);
            progress.update(0.12 + 0.80 * branchIndex / branches.size(),
                    "Branch " + (branchIndex + 1) + "/" + branches.size() + " | "
                            + branch.repositoryName() + " :: " + branch.branchName(),
                    branchIndex + 1, branches.size());
            SourceCodeStats stats = writeSourceSnapshot(output.resolve(filenames.get(branch)),
                    List.of(branch.repository()), root, branch.fullRef(), progress,
                    branchIndex, branches.size(), branch);
            textFiles += stats.textFiles();
            binaryFiles += stats.binaryFiles();
            totalBytes += stats.bytes();
        }
        progress.update(0.93, "Forráskód-index és elavult exportok rendezése", branches.size(), branches.size());
        writeSourceIndex(output.resolve("source-code-index.md"), root, branches, filenames);
        Set<String> currentFiles = new HashSet<>(filenames.values());
        currentFiles.add("source-code-index.md");
        try (var files = Files.list(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if ((name.equals("source-code.md") || (name.startsWith("source-code-") && name.endsWith(".md")))
                        && !currentFiles.contains(name)) Files.delete(file);
            }
        }
        List<Path> markdownFiles = new ArrayList<>();
        markdownFiles.add(output.resolve("source-code-index.md"));
        filenames.values().forEach(name -> markdownFiles.add(output.resolve(name)));
        progress.update(0.97, "Markdown méretkorlát ellenőrzése: " + markdownFiles.size() + " fájl",
                markdownFiles.size(), markdownFiles.size());
        MarkdownSplitter.enforce(markdownFiles, args.maxMarkdownBytes, (current, total, detail) ->
                progress.update(0.97 + 0.03 * current / Math.max(1d, total), detail, current, total));
        progress.update(1, branches.size() + " branch | " + textFiles + " szöveges | "
                + binaryFiles + " bináris fájl", branches.size(), branches.size());
        return new SourceCodeStats(branches.size(), textFiles, binaryFiles, totalBytes);
    }

    private static List<SourceBranch> sourceBranches(List<Path> repositories, Path root, CliOptions args,
                                                     ProgressReporter.Stage progress)
            throws IOException, InterruptedException {
        List<SourceBranch> branches = new ArrayList<>();
        Set<String> matchedSelectors = new HashSet<>();
        for (int repositoryIndex = 0; repositoryIndex < repositories.size(); repositoryIndex++) {
            Path repository = repositories.get(repositoryIndex);
            String repositoryName = relativePath(root, repository);
            progress.update(0.12 * repositoryIndex / Math.max(1, repositories.size()),
                    "Branchlista: repó " + (repositoryIndex + 1) + "/" + repositories.size()
                            + " | " + repositoryName, repositoryIndex + 1, repositories.size());
            if (args.sourceRefExplicit) {
                CommandResult revision = execute(repository,
                        List.of("git", "rev-parse", "--verify", args.sourceRef + "^{commit}"));
                if (revision.exitCode != 0) {
                    throw new IOException(repositoryName + ": nem olvasható ref: " + args.sourceRef + " — "
                            + oneLine(revision.stderr));
                }
                branches.add(new SourceBranch(repository, repositoryName, args.sourceRef, args.sourceRef,
                        revision.stdout.strip()));
                continue;
            }
            CommandResult refs = execute(repository, List.of("git", "for-each-ref",
                    "--format=%(refname)%1f%(refname:short)%1f%(objectname)", "refs/heads", "refs/remotes"));
            if (refs.exitCode != 0) throw new IOException(repositoryName + ": " + oneLine(refs.stderr));
            for (String line : refs.stdout.lines().filter(value -> !value.isBlank()).toList()) {
                String[] fields = line.split(String.valueOf(UNIT_SEPARATOR), 3);
                if (fields.length != 3 || fields[0].endsWith("/HEAD")) continue;
                boolean remote = fields[0].startsWith("refs/remotes/");
                if (!branchSelected(args.sourceBranches, fields[0], fields[1], remote, matchedSelectors)) continue;
                branches.add(new SourceBranch(repository, repositoryName, fields[0], fields[1], fields[2]));
            }
            if ((args.sourceBranches.contains("all") || args.sourceBranches.contains("local"))
                    && branches.stream().noneMatch(branch -> branch.repository().equals(repository))) {
                CommandResult revision = execute(repository, List.of("git", "rev-parse", "--verify", "HEAD^{commit}"));
                if (revision.exitCode == 0) branches.add(new SourceBranch(repository, repositoryName,
                        "HEAD", "HEAD", revision.stdout.strip()));
            }
        }
        Set<String> missing = new LinkedHashSet<>(args.sourceBranches);
        missing.removeAll(Set.of("all", "local", "remote"));
        missing.removeAll(matchedSelectors);
        if (!missing.isEmpty()) {
            throw new IOException("A következő branch-ek egyik repóban sem találhatók: " + String.join(", ", missing));
        }
        return branches.stream().sorted(Comparator.comparing(SourceBranch::repositoryName)
                .thenComparing(SourceBranch::branchName)).toList();
    }

    private static boolean branchSelected(Set<String> selectors, String fullName, String shortName,
                                          boolean remote, Set<String> matchedSelectors) {
        boolean selected = selectors.contains("all")
                || (selectors.contains("local") && !remote)
                || (selectors.contains("remote") && remote);
        for (String selector : selectors) {
            if (selector.equals(fullName) || selector.equals(shortName)) {
                matchedSelectors.add(selector);
                selected = true;
            }
        }
        return selected;
    }

    private static void writeSourceIndex(Path target, Path root, List<SourceBranch> branches,
                                         Map<SourceBranch, String> filenames) throws IOException {
        StringBuilder out = new StringBuilder("# Branchenkénti teljes forráskód\n\n")
                .append("Generálva: ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("  \n")
                .append("Gyökér: `").append(mdCode(root.toAbsolutePath().normalize().toString())).append("`  \n")
                .append("Branch-pillanatképek: ").append(branches.size()).append("\n\n")
                .append("| Repó | Branch | Commit | Forráskód |\n|---|---|---|---|\n");
        for (SourceBranch branch : branches) out.append('|').append(md(branch.repositoryName())).append("|`")
                .append(mdCode(branch.branchName())).append("`|`").append(branch.commit()).append("`|[")
                .append(md(filenames.get(branch))).append("](").append(filenames.get(branch)).append(")|\n");
        writeAtomically(target, out.toString());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static SourceCodeStats writeSourceSnapshot(Path target, List<Path> repositories, Path root, String sourceRef,
                                                       ProgressReporter.Stage progress, int branchIndex,
                                                       int branchCount, SourceBranch branch)
            throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        long textFiles = 0;
        long binaryFiles = 0;
        long totalBytes = 0;
        int writtenRepositories = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write("# Teljes forráskód\n\n");
                writer.write("Generálva: " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "  \n");
                writer.write("Gyökér: `" + mdCode(root.toAbsolutePath().normalize().toString()) + "`  \n");
                writer.write("Git branch/ref: `" + mdCode(sourceRef) + "`\n\n");
                writer.write("> A tartalom a Git objektum-adatbázisából származó, commitolt pillanatkép; "
                        + "a munkakönyvtár nem commitolt módosításait nem tartalmazza.\n\n");

                for (Path repository : repositories) {
                    CommandResult revision = execute(repository,
                            List.of("git", "rev-parse", "--verify", sourceRef + "^{commit}"));
                    String relativeRepository = relativePath(root, repository);
                    if (revision.exitCode != 0) {
                        writer.write("## Repó: " + md(relativeRepository) + "\n\n");
                        writer.write("> **Nem olvasható ref:** `" + mdCode(sourceRef) + "` — "
                                + md(oneLine(revision.stderr)) + "\n\n");
                        continue;
                    }
                    String commit = revision.stdout.strip();
                    CommandResult tree = execute(repository,
                            List.of("git", "ls-tree", "-r", "-z", "--long", commit));
                    if (tree.exitCode != 0) {
                        writer.write("## Repó: " + md(relativeRepository) + "\n\n");
                        writer.write("> **A fa nem olvasható:** " + md(oneLine(tree.stderr)) + "\n\n");
                        continue;
                    }
                    List<TreeEntry> entries = parseTree(tree.stdout);
                    writtenRepositories++;
                    writer.write("## Repó: " + md(relativeRepository) + "\n\n");
                    writer.write("- Repó útvonala: `" + mdCode(repository.toAbsolutePath().normalize().toString()) + "`\n");
                    writer.write("- Git branch/ref: `" + mdCode(sourceRef) + "`\n");
                    writer.write("- Commit: `" + commit + "`\n");
                    writer.write("- Követett blobok: " + entries.size() + "\n\n");

                    Process batch = new ProcessBuilder("git", "-C", repository.toString(), "cat-file", "--batch").start();
                    ByteArrayOutputStream batchError = new ByteArrayOutputStream();
                    Thread errorThread = Thread.ofVirtual().start(() -> copy(batch.getErrorStream(), batchError));
                    try (OutputStreamWriter request = new OutputStreamWriter(batch.getOutputStream(), StandardCharsets.US_ASCII);
                         InputStream response = new BufferedInputStream(batch.getInputStream())) {
                        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                            TreeEntry entry = entries.get(entryIndex);
                            request.write(entry.hash());
                            request.write('\n');
                            request.flush();
                            String header = readAsciiLine(response);
                            String[] headerParts = header.split(" ");
                            if (headerParts.length < 3 || "missing".equals(headerParts[1])) {
                                throw new IOException("Git blob nem olvasható: " + entry.path() + " (" + header + ")");
                            }
                            long announcedSize = parseLong(headerParts[2]);
                            if (announcedSize < 0 || announcedSize > Integer.MAX_VALUE) {
                                throw new IOException("Túl nagy Git blob a Markdown kimenethez: " + entry.path());
                            }
                            byte[] content = response.readNBytes((int) announcedSize);
                            if (content.length != announcedSize) {
                                throw new IOException("Csonka Git blob: " + entry.path());
                            }
                            int terminator = response.read();
                            if (terminator != '\n') throw new IOException("Hibás git cat-file válasz: " + entry.path());
                            totalBytes += announcedSize;
                            boolean binary = isBinary(content);
                            if (binary) binaryFiles++; else textFiles++;
                            writeSourceEntry(writer, relativeRepository, entry, content, binary);
                            double branchFraction = (entryIndex + 1) / (double) Math.max(1, entries.size());
                            double overall = 0.12 + 0.80 * (branchIndex + branchFraction) / Math.max(1, branchCount);
                            progress.update(overall,
                                    "Branch " + (branchIndex + 1) + "/" + branchCount + " | "
                                            + branch.repositoryName() + " :: " + branch.branchName()
                                            + " | Fájl " + (entryIndex + 1) + "/" + entries.size()
                                            + " | " + entry.path(),
                                    entryIndex + 1, entries.size());
                        }
                    } finally {
                        batch.getOutputStream().close();
                        int exit = batch.waitFor();
                        errorThread.join();
                        if (exit != 0) {
                            throw new IOException("git cat-file hiba: " + oneLine(batchError.toString(StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return new SourceCodeStats(writtenRepositories, textFiles, binaryFiles, totalBytes);
    }

    private static List<TreeEntry> parseTree(String rawTree) {
        List<TreeEntry> entries = new ArrayList<>();
        for (String record : rawTree.split("\\u0000", -1)) {
            if (record.isEmpty()) continue;
            int tab = record.indexOf('\t');
            if (tab < 0) continue;
            String[] metadata = record.substring(0, tab).trim().split("\\s+");
            if (metadata.length < 4 || !"blob".equals(metadata[1])) continue;
            entries.add(new TreeEntry(metadata[0], metadata[1], metadata[2], parseLong(metadata[3]),
                    record.substring(tab + 1)));
        }
        return entries;
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0 && value != '\n') line.write(value);
        if (value < 0 && line.size() == 0) throw new IOException("A git cat-file váratlanul befejeződött.");
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static boolean isBinary(byte[] content) {
        int sample = Math.min(content.length, 8192);
        for (int i = 0; i < sample; i++) if (content[i] == 0) return true;
        return false;
    }

    private static void writeSourceEntry(BufferedWriter writer, String repository, TreeEntry entry,
                                         byte[] content, boolean binary) throws IOException {
        String separator = "-".repeat(96);
        int slash = entry.path().lastIndexOf('/');
        String folder = slash < 0 ? "." : entry.path().substring(0, slash);
        String filename = slash < 0 ? entry.path() : entry.path().substring(slash + 1);
        String language = languageFor(entry.path());
        writer.write(separator + "\n\n");
        writer.write("### FÁJL: `" + mdCode(entry.path()) + "`\n\n");
        writer.write("- Repó: `" + mdCode(repository) + "`\n");
        writer.write("- Mappa: `" + mdCode(folder) + "`\n");
        writer.write("- Fájlnév: `" + mdCode(filename) + "`\n");
        writer.write("- Git blob: `" + entry.hash() + "`\n");
        writer.write("- Git mód: `" + entry.mode() + "`\n");
        writer.write("- Méret: " + entry.size() + " bájt\n");
        writer.write("- Nyelv: `" + language + "`\n\n");
        writer.write(separator + "\n\n");
        if (binary) {
            writer.write("_Bináris fájl – a tartalma nem ágyazható be Markdown szövegként._\n\n");
            return;
        }
        String text = new String(content, StandardCharsets.UTF_8);
        String fence = markdownFence(text);
        writer.write(fence + language + "\n");
        writer.write(text);
        if (!text.endsWith("\n")) writer.write('\n');
        writer.write(fence + "\n\n");
    }

    private static String markdownFence(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') longest = Math.max(longest, ++current);
            else current = 0;
        }
        return "`".repeat(Math.max(4, longest + 1));
    }

    private static String relativePath(Path root, Path repository) {
        try {
            String relative = root.toAbsolutePath().normalize().relativize(repository.toAbsolutePath().normalize()).toString();
            return relative.isBlank() ? repository.getFileName().toString() : relative.replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return repository.getFileName().toString();
        }
    }

}
