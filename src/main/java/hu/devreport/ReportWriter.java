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

import org.apache.commons.text.StringEscapeUtils;

import static hu.devreport.GitReportApplication.*;

final class ReportWriter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] WEEKDAYS = {"Hétfő", "Kedd", "Szerda", "Csütörtök", "Péntek", "Szombat", "Vasárnap"};

    static void write(Path output, Analysis analysis, CliOptions args, ProgressReporter.Stage progress)
            throws IOException, InterruptedException {
        boolean htmlOutput = args.outputs.contains("html");
        boolean markdownOutput = args.outputs.contains("markdown");
        long commitCount = analysis.repositories.stream().mapToLong(repository -> repository.commits.size()).sum();
        long embeddedCommitCount = markdownOutput
                ? analysis.developers.values().stream().mapToLong(developer -> developer.commits.size()).sum()
                : 0;
        long totalItems = 1L + analysis.developers.size() + embeddedCommitCount
                + analysis.repositories.size() + commitCount
                + (markdownOutput ? 1 : 0);
        long[] completedItems = {0};
        long[] writtenBytes = {0};
        progress.update(0, "Kimeneti könyvtárak és régi generált oldalak előkészítése", 0, totalItems);
        Files.createDirectories(output);
        Files.createDirectories(output.resolve("developers"));
        Files.createDirectories(output.resolve("repositories"));
        Files.createDirectories(output.resolve("commits"));
        if (htmlOutput) {
            cleanGeneratedPages(output.resolve("developers"), ".html");
            cleanGeneratedPages(output.resolve("repositories"), ".html");
            cleanGeneratedPages(output.resolve("commits"), ".html");
        }
        if (markdownOutput) {
            cleanGeneratedPages(output.resolve("developers"), ".md");
            cleanGeneratedPages(output.resolve("repositories"), ".md");
            cleanGeneratedPages(output.resolve("commits"), ".md");
        }
        Map<String, String> developerFiles = developerFilenames(analysis.developers.values());
        Map<String, String> repositoryFiles = repositoryFilenames(analysis.repositories);
        Map<String, String> commitFiles = commitFilenames(analysis.repositories);
        List<Path> markdownFiles = new ArrayList<>();
        updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                "Fő indexoldalak renderelése");
        if (htmlOutput) {
            Path styleTarget = output.resolve("style.css");
            Path indexTarget = output.resolve("index.html");
            Files.writeString(styleTarget, css(), StandardCharsets.UTF_8);
            Files.writeString(indexTarget, index(analysis, args, developerFiles, repositoryFiles), StandardCharsets.UTF_8);
            writtenBytes[0] += Files.size(styleTarget) + Files.size(indexTarget);
        }
        if (markdownOutput) {
            Path target = output.resolve("index.md");
            Files.writeString(target, markdownIndex(analysis, args, developerFiles, repositoryFiles), StandardCharsets.UTF_8);
            markdownFiles.add(target);
            writtenBytes[0] += Files.size(target);
        }
        completedItems[0]++;
        updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0], "Fő indexoldalak elkészültek");
        int developerIndex = 0;
        for (Developer developer : analysis.developers.values()) {
            developerIndex++;
            String developerPrefix = "Fejlesztő " + developerIndex + "/" + analysis.developers.size()
                    + " | " + developer.displayName;
            updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                    developerPrefix + " | adatlap renderelése");
            if (htmlOutput) {
                Path target = output.resolve("developers").resolve(developerFiles.get(developer.key));
                Files.writeString(target, developerPage(developer, args, repositoryFiles, commitFiles), StandardCharsets.UTF_8);
                writtenBytes[0] += Files.size(target);
            }
            if (markdownOutput) {
                Path target = output.resolve("developers").resolve(markdownName(developerFiles.get(developer.key)));
                long beforeEmbeddedCommits = completedItems[0];
                writeMarkdownDeveloper(target, developer, args, repositoryFiles, commitFiles,
                        (commitIndex, commits, commit) -> updateReportProgress(progress,
                                beforeEmbeddedCommits + commitIndex, totalItems, writtenBytes[0],
                                developerPrefix + " | Beágyazott commit " + (commitIndex + 1) + "/" + commits
                                        + " | " + shortHash(commit)));
                completedItems[0] += developer.commits.size();
                markdownFiles.add(target);
                writtenBytes[0] += Files.size(target);
            }
            completedItems[0]++;
            updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0], developerPrefix + " | kész");
        }
        int repositoryIndex = 0;
        long writtenCommits = 0;
        for (RepositoryStats repository : analysis.repositories) {
            repositoryIndex++;
            String repositoryPrefix = "Repó " + repositoryIndex + "/" + analysis.repositories.size()
                    + " | " + repository.name;
            updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                    repositoryPrefix + " | összesítő renderelése");
            if (htmlOutput) {
                Path target = output.resolve("repositories").resolve(repositoryFiles.get(repository.name));
                Files.writeString(target, repositoryPage(repository, args, analysis, developerFiles, commitFiles), StandardCharsets.UTF_8);
                writtenBytes[0] += Files.size(target);
            }
            if (markdownOutput) {
                Path target = output.resolve("repositories").resolve(markdownName(repositoryFiles.get(repository.name)));
                Files.writeString(target, markdownRepository(repository, args, analysis, developerFiles, commitFiles), StandardCharsets.UTF_8);
                markdownFiles.add(target);
                writtenBytes[0] += Files.size(target);
            }
            completedItems[0]++;
            updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0], repositoryPrefix + " | kész");
            for (CommitSummary commit : repository.commits) {
                String filename = commitFiles.get(commitKey(commit));
                String commitPrefix = "Commit " + (writtenCommits + 1) + "/" + commitCount + " | "
                        + repository.name + " | " + shortHash(commit);
                updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                        commitPrefix + " | diff beolvasása és renderelés");
                String patch = patchText(commit);
                if (htmlOutput) {
                    Path target = output.resolve("commits").resolve(filename);
                    Files.writeString(target, commitPage(commit, args, patch), StandardCharsets.UTF_8);
                    writtenBytes[0] += Files.size(target);
                }
                if (markdownOutput) {
                    Path target = output.resolve("commits").resolve(markdownName(filename));
                    updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                            commitPrefix + " | Markdown írása");
                    Files.writeString(target, markdownCommit(commit, args, patch), StandardCharsets.UTF_8);
                    markdownFiles.add(target);
                    writtenBytes[0] += Files.size(target);
                }
                writtenCommits++;
                completedItems[0]++;
                updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0], commitPrefix + " | kész");
            }
        }
        if (markdownOutput) {
            updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                    "Markdown méretkorlát ellenőrzése: " + markdownFiles.size() + " fájl");
            long beforeMarkdownCheck = completedItems[0];
            MarkdownSplitter.enforce(markdownFiles, args.maxMarkdownBytes, (current, total, detail) ->
                    progress.update((beforeMarkdownCheck + current / (double) Math.max(1, total)) / totalItems,
                            detail + " | Kiírva: " + progressBytes(writtenBytes[0]), current, total));
            completedItems[0]++;
        }
        updateReportProgress(progress, totalItems, totalItems, writtenBytes[0],
                "Riportfájlok elkészültek");
    }

    private static void updateReportProgress(ProgressReporter.Stage progress, long completed, long total,
                                             long writtenBytes, String detail) {
        progress.update(completed / (double) Math.max(1, total),
                detail + " | Kiírva: " + progressBytes(writtenBytes), completed, total);
    }

    private static String progressBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return "%.1f GB".formatted(bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024L) return "%.1f MB".formatted(bytes / (1024d * 1024d));
        if (bytes >= 1024L) return "%.1f KB".formatted(bytes / 1024d);
        return bytes + " B";
    }

    private static String shortHash(CommitSummary commit) {
        return commit.hash.substring(0, Math.min(12, commit.hash.length()));
    }

    private static void cleanGeneratedPages(Path directory, String extension) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .toList()) {
                Files.delete(file);
            }
        }
    }

    private static Map<String, String> developerFilenames(Iterable<Developer> developers) {
        Map<String, String> result = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (Developer developer : developers) result.put(developer.key, uniqueFilename(developer.displayName, used));
        return result;
    }

    private static Map<String, String> repositoryFilenames(Iterable<RepositoryStats> repositories) {
        Map<String, String> result = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (RepositoryStats repository : repositories) result.put(repository.name, uniqueFilename(repository.name, used));
        return result;
    }

    private static Map<String, String> commitFilenames(Iterable<RepositoryStats> repositories) {
        Map<String, String> result = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (RepositoryStats repository : repositories) {
            for (CommitSummary commit : repository.commits) {
                String base = slug(repository.name) + "-" + commit.hash.substring(0, Math.min(12, commit.hash.length()));
                String candidate = base + ".html";
                int suffix = 2;
                while (!used.add(candidate)) candidate = base + "-" + suffix++ + ".html";
                result.put(commitKey(commit), candidate);
            }
        }
        return result;
    }

    private static String commitKey(CommitSummary commit) { return commit.repository + "|" + commit.hash; }
    private static String markdownName(String htmlName) { return htmlName.replaceFirst("\\.html$", ".md"); }

    private static String uniqueFilename(String value, Set<String> used) {
        String base = slug(value);
        String candidate = base + ".html";
        int suffix = 2;
        while (!used.add(candidate)) candidate = base + "-" + suffix++ + ".html";
        return candidate;
    }

    private static String index(Analysis analysis, CliOptions args, Map<String, String> developerFiles,
                                Map<String, String> repositoryFiles) {
        List<Developer> developers = analysis.developers.values().stream()
                .sorted(Comparator.comparing(d -> d.displayName.toLowerCase(Locale.ROOT))).toList();
        long branchCount = analysis.repositories.stream().mapToLong(r -> r.branches).sum();
        long additions = developers.stream().mapToLong(d -> d.additions).sum();
        long deletions = developers.stream().mapToLong(d -> d.deletions).sum();
        StringBuilder rows = new StringBuilder();
        for (Developer d : developers) {
            rows.append("<tr><td><a href=\"developers/").append(attr(developerFiles.get(d.key))).append("\">")
                    .append(html(d.displayName)).append("</a></td><td data-sort=\"").append(d.authoredCommits).append("\">").append(number(d.authoredCommits))
                    .append("</td><td>").append(number(d.nonMergeCommits)).append("</td><td>").append(number(d.mergeCommits))
                    .append("</td><td>").append(number(d.trailerCount("co-authored-by"))).append("</td><td>").append(d.repositories.size())
                    .append("</td><td>").append(d.branchRefs.size()).append("</td><td>").append(d.participationDays.size())
                    .append("</td><td>").append(number(d.filesChanged)).append("</td><td>").append(number(d.additions)).append(" / ").append(number(d.deletions))
                    .append("</td><td>").append(date(d.firstActivity)).append("</td><td>").append(date(d.lastActivity)).append("</td></tr>");
        }
        StringBuilder repoRows = new StringBuilder();
        for (RepositoryStats r : analysis.repositories.stream().sorted(Comparator.comparing(x -> x.name)).toList()) {
            repoRows.append("<tr><td><a href=\"repositories/").append(attr(repositoryFiles.get(r.name))).append("\">")
                    .append(html(r.name)).append("</a></td><td>").append(r.branches).append("</td><td>").append(r.uniqueCommits)
                    .append("</td><td>").append(r.authors.size()).append("</td><td>").append(number(r.total.files))
                    .append("</td><td>").append(number(r.total.additions)).append(" / ").append(number(r.total.deletions))
                    .append("</td><td><code>").append(html(r.path.toString())).append("</code></td></tr>");
        }
        String warnings = analysis.warnings.isEmpty() ? "" : "<section><h2>Figyelmeztetések</h2><ul>" +
                analysis.warnings.stream().map(w -> "<li>" + html(w) + "</li>").reduce("", String::concat) + "</ul></section>";
        String sourceLink = args.outputs.contains("source")
                ? "<section><h2>Branchenkénti teljes forráskód</h2><p><a href=\"source-code-index.md\">Minden branch teljes forráskódja külön Markdown-fájlban →</a></p></section>"
                : "";
        return page(args.title, """
                <header><p class="eyebrow">GIT CONTRIBUTOR REPORT · RÉSZLETES ELEMZÉS</p><h1>%s</h1>
                <p class="muted">Generálva: %s · Gyökér: <code>%s</code> · Időszak: %s</p></header>
                <section class="cards six">
                  <article><strong>%s</strong><span>repó</span></article><article><strong>%s</strong><span>közreműködő</span></article>
                  <article><strong>%s</strong><span>repón belül egyedi commit</span></article><article><strong>%s</strong><span>branch / remote ref</span></article>
                  <article><strong>%s</strong><span>hozzáadott sor</span></article><article><strong>%s</strong><span>törölt sor</span></article>
                </section>
                <aside><strong>Értelmezési keret:</strong> ezek leíró aktivitási adatok, nem teljesítménypontok. A squash merge, generált kód, formázás, páros munka, review, mentoring, kutatás és eltérő feladatnehézség jelentősen torzíthatja az összehasonlítást.</aside>
                %s
                <section><div class="section-title"><div><p class="kicker">SZEMÉLYEK</p><h2>Közreműködők részletes összesítője</h2></div><p class="muted">A névre kattintva teljes adatlap nyílik.</p></div>
                <div class="table-wrap"><table class="sortable"><thead><tr><th>Név</th><th>Commit</th><th>Nem merge</th><th>Merge</th><th>Co-author</th><th>Repó</th><th>Branch*</th><th>Aktív nap</th><th>Fájlérintés</th><th>+ / − sor</th><th>Első</th><th>Utolsó</th></tr></thead><tbody>%s</tbody></table></div>
                <p class="footnote">* Olyan branch/ref, amelyből a személy legalább egy vizsgált commitja elérhető; ez nem feltétlenül az eredeti commitolási branch.</p></section>
                <section><div class="section-title"><div><p class="kicker">KÓDBÁZISOK</p><h2>Repók</h2></div></div><div class="table-wrap"><table><thead><tr><th>Repó</th><th>Branch/ref</th><th>Commit</th><th>Szerző</th><th>Fájlérintés</th><th>+ / − sor</th><th>Útvonal</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section class="method"><h2>Módszertan és lefedettség</h2><div class="grid"><div><h3>Beleszámít</h3><ul><li>Minden lokális és remote ref által elérhető commit</li><li>Author és <code>Co-authored-by</code> identitás</li><li>Teljes commitüzenet, fájllista és Git diff</li><li>Bináris fájlok fájlérintésként, sorszám nélkül</li><li>A repó <code>.mailmap</code> szabályai</li></ul></div><div><h3>Nem állapítható meg Gitből</h3><ul><li>Kódminőség és üzleti hatás</li><li>A társszerző által írt pontos sorok</li><li>Mentoring, tervezés és kommunikáció</li><li>A commit eredeti branch-e</li><li>Munkaidő vagy ráfordított idő</li></ul></div></div></section>%s
                """.formatted(html(args.title), OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), html(analysis.root.toString()),
                period(args), analysis.repositories.size(), developers.size(), analysis.seenCommits.size(), branchCount,
                number(additions), number(deletions), sourceLink, rows, repoRows, warnings), "");
    }

    private static String developerPage(Developer d, CliOptions args, Map<String, String> repositoryFiles,
                                        Map<String, String> commitFiles) {
        StringBuilder repositoryRows = new StringBuilder();
        d.byRepository.forEach((name, s) -> repositoryRows.append("<tr><td><a href=\"../repositories/")
                .append(attr(repositoryFiles.get(name))).append("\">").append(html(name)).append("</a></td><td>")
                .append(number(s.commits)).append("</td><td>").append(number(s.merges)).append("</td><td>").append(s.activeDays.size()).append("</td><td>").append(s.branches.size())
                .append("</td><td>").append(number(s.files)).append("</td><td>").append(number(s.additions)).append(" / ").append(number(s.deletions))
                .append("</td><td>").append(date(s.first)).append("</td><td>").append(date(s.last)).append("</td></tr>"));

        long maxMonth = d.monthly.values().stream().mapToLong(s -> s.commits).max().orElse(1);
        StringBuilder monthRows = new StringBuilder();
        d.monthly.forEach((month, s) -> monthRows.append("<tr><td>").append(month).append("</td><td>")
                .append(meter(s.commits, maxMonth)).append("</td><td>").append(s.commits).append("</td><td>")
                .append(s.activeDays.size()).append("</td><td>").append(number(s.files)).append("</td><td>")
                .append(number(s.additions)).append("</td><td>").append(number(s.deletions)).append("</td></tr>"));

        String roleRows = mapRows(d.roles, "Nincs felismert trailer-szerep.");
        String typeRows = mapRows(d.commitTypes, "Nincs szerzői commit.");
        String fileRows = fileRows(d.fileTypes);
        String rhythmRows = rhythmRows(d);
        String commitRows = commitRows(d.commits, null, commitFiles);
        String aliases = d.names.stream().sorted().map(ReportWriter::html).reduce((a, b) -> a + ", " + b).orElse("—");
        String emails = d.emails.stream().sorted().map(ReportWriter::html).reduce((a, b) -> a + ", " + b).orElse("—");
        String branches = d.branchRefs.stream().sorted().map(b -> "<li><code>" + html(b) + "</code></li>").reduce("", String::concat);
        long span = spanDays(d.firstActivity, d.lastActivity);
        double commitsPerActiveDay = d.activeDays.isEmpty() ? 0 : (double) d.authoredCommits / d.activeDays.size();
        double changePerCommit = d.authoredCommits == 0 ? 0 : (double) (d.additions + d.deletions) / d.authoredCommits;
        return page(d.displayName + " – " + args.title, """
                <nav><a href="../index.html">← Összesítő</a></nav>
                <header><p class="eyebrow">FEJLESZTŐI ADATLAP · RÉSZLETES</p><h1>%s</h1><p class="muted">Vizsgált időszak: %s</p></header>
                <section class="cards six"><article><strong>%s</strong><span>commit szerzőként</span></article><article><strong>%s</strong><span>nem merge / merge</span></article>
                  <article><strong>%s</strong><span>közreműködési nap</span></article><article><strong>%s</strong><span>repó</span></article><article><strong>%s</strong><span>branch-elérhetőség</span></article><article><strong>%s</strong><span>fájlérintés</span></article>
                <article><strong>%s</strong><span>hozzáadott sor</span></article><article><strong>%s</strong><span>törölt sor</span></article><article><strong>%s</strong><span>nettó sorváltozás</span></article>
                <article><strong>%.2f</strong><span>commit / szerzői aktív nap</span></article><article><strong>%.1f</strong><span>sorváltozás / commit</span></article><article><strong>%s</strong><span>issue-hivatkozásos commit</span></article></section>
                <section class="grid"><article class="panel"><h2>Identitás</h2><dl><dt>Elsődleges név</dt><dd>%s</dd><dt>Névváltozatok</dt><dd>%s</dd><dt>E-mail</dt><dd>%s</dd></dl></article>
                <article class="panel"><h2>Időbeli lefedettség</h2><dl><dt>Első aktivitás</dt><dd>%s</dd><dt>Utolsó aktivitás</dt><dd>%s</dd><dt>Naptári időtáv</dt><dd>%s nap</dd></dl></article></section>
                <aside><strong>Értelmezés:</strong> a nagyobb commit- vagy sorszám nem jelent automatikusan jobb teljesítményt. Az adatokhoz mindig társíts kódminőségi, üzleti, csapatmunka- és feladatnehézségi kontextust.</aside>
                <section><div class="section-title"><div><p class="kicker">REPOSITORY</p><h2>Repónkénti bontás</h2></div></div><div class="table-wrap"><table><thead><tr><th>Repó</th><th>Commit</th><th>Merge</th><th>Aktív nap</th><th>Branch*</th><th>Fájl</th><th>+ / − sor</th><th>Első</th><th>Utolsó</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">IDŐSOR</p><h2>Havi aktivitás</h2></div></div><div class="table-wrap"><table><thead><tr><th>Hónap</th><th>Intenzitás</th><th>Commit</th><th>Aktív nap</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section class="grid"><article class="panel"><h2>Commit-típusok</h2><table><thead><tr><th>Típus</th><th>Darab</th></tr></thead><tbody>%s</tbody></table></article><article class="panel"><h2>Trailer-szerepek</h2><table><thead><tr><th>Szerep</th><th>Darab</th></tr></thead><tbody>%s</tbody></table></article></section>
                <section><div class="section-title"><div><p class="kicker">TECHNOLÓGIAI LÁBNYOM</p><h2>Fájltípusok</h2></div><p class="muted">A fájlérintés commitonként számít; ugyanaz a fájl többször is megjelenhet.</p></div><div class="table-wrap"><table><thead><tr><th>Kiterjesztés</th><th>Fájlérintés</th><th>+ sor</th><th>− sor</th><th>Változtatott sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">MUNKARITMUS</p><h2>Aktivitás hét és napszak szerint</h2></div><p class="muted">A commit author-időbélyege alapján, a riportot futtató gép időzónájában.</p></div><div class="table-wrap"><table><thead><tr><th>Dimenzió</th><th>Érték</th><th>Commit</th><th>Arány</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><details><summary><strong>Branch/ref elérhetőség (%s)</strong></summary><ul class="columns">%s</ul><p class="footnote">Egy commit több branchből is elérhető lehet; ez nem a commit eredeti branchét jelenti.</p></details></section>
                <section><div class="section-title"><div><p class="kicker">AUDITÁLHATÓSÁG</p><h2>Commitok</h2></div><p class="muted">Legújabb elöl, minden vizsgált szerzői commit.</p></div><div class="table-wrap"><table><thead><tr><th>Dátum</th><th>Hash</th><th>Repó</th><th>Üzenet</th><th>Típus</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                """.formatted(html(d.displayName), period(args), number(d.authoredCommits), number(d.nonMergeCommits) + " / " + number(d.mergeCommits),
                d.participationDays.size(), d.repositories.size(), d.branchRefs.size(), number(d.filesChanged), number(d.additions), number(d.deletions),
                signed(d.additions - d.deletions), commitsPerActiveDay, changePerCommit, number(d.issueLinkedCommits), html(d.displayName), aliases, emails,
                dateTime(d.firstActivity), dateTime(d.lastActivity), span, repositoryRows, monthRows, typeRows, roleRows,
                fileRows, rhythmRows, d.branchRefs.size(), branches, commitRows), "../");
    }

    private static String repositoryPage(RepositoryStats r, CliOptions args, Analysis analysis,
                                         Map<String, String> developerFiles, Map<String, String> commitFiles) {
        StringBuilder people = new StringBuilder();
        r.byDeveloper.entrySet().stream().sorted(Comparator.comparing(e -> analysis.developers.get(e.getKey()).displayName))
                .forEach(entry -> {
                    Developer d = analysis.developers.get(entry.getKey());
                    ContributionStats s = entry.getValue();
                    long roles = s.roles.values().stream().mapToLong(Long::longValue).sum();
                    people.append("<tr><td><a href=\"../developers/").append(attr(developerFiles.get(d.key))).append("\">")
                            .append(html(d.displayName)).append("</a></td><td>").append(s.commits).append("</td><td>").append(s.merges).append("</td><td>").append(s.activeDays.size()).append("</td><td>")
                            .append(s.branches.size()).append("</td><td>").append(roles).append("</td><td>").append(number(s.files))
                            .append("</td><td>").append(number(s.additions)).append(" / ").append(number(s.deletions)).append("</td></tr>");
                });
        StringBuilder branches = new StringBuilder();
        r.branchStats.stream().sorted(Comparator.comparing(b -> b.name)).forEach(b -> branches.append("<tr><td><code>")
                .append(html(b.name)).append("</code></td><td><span class=\"badge\">").append(b.remote ? "remote" : "local")
                .append("</span></td><td>").append(b.commits).append("</td><td>").append(b.authors.size()).append("</td></tr>"));
        String origin = r.originUrl.isBlank() ? "—" : html(r.originUrl);
        return page(r.name + " – " + args.title, """
                <nav><a href="../index.html">← Összesítő</a></nav><header><p class="eyebrow">REPÓ-ADATLAP · RÉSZLETES</p><h1>%s</h1>
                <p class="muted"><code>%s</code></p></header>
                <section class="cards six"><article><strong>%s</strong><span>egyedi commit</span></article><article><strong>%s</strong><span>branch/ref</span></article><article><strong>%s</strong><span>szerző</span></article><article><strong>%s</strong><span>összes közreműködő</span></article><article><strong>%s</strong><span>fájlérintés</span></article><article><strong>%s / %s</strong><span>hozzáadott / törölt sor</span></article></section>
                <section class="grid"><article class="panel"><h2>Forrás</h2><dl><dt>Origin</dt><dd class="wrap">%s</dd><dt>Időszak</dt><dd>%s</dd><dt>Elérhető commit rekord</dt><dd>%s</dd></dl></article><article class="panel"><h2>Aktivitás</h2><dl><dt>Első</dt><dd>%s</dd><dt>Utolsó</dt><dd>%s</dd><dt>Aktív nap</dt><dd>%s</dd><dt>Merge commit</dt><dd>%s</dd></dl></article></section>
                <section><h2>Kód szerzői és társszerzői</h2><div class="table-wrap"><table><thead><tr><th>Név</th><th>Commit</th><th>Merge</th><th>Aktív nap</th><th>Branch*</th><th>Co-author</th><th>Fájl</th><th>+ / − sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><h2>Branch-ek és remote refek</h2><div class="table-wrap"><table><thead><tr><th>Név</th><th>Típus</th><th>Időszakbeli commit</th><th>Szerző</th></tr></thead><tbody>%s</tbody></table></div><p class="footnote">A commitok branchek között átfedhetnek, ezért a branch-sorok összege nem egyezik szükségszerűen az egyedi commitok számával.</p></section>
                <section><h2>Fájltípusok</h2><div class="table-wrap"><table><thead><tr><th>Kiterjesztés</th><th>Fájlérintés</th><th>+ sor</th><th>− sor</th><th>Változtatott sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><h2>Commitok</h2><div class="table-wrap"><table><thead><tr><th>Dátum</th><th>Hash</th><th>Repó</th><th>Üzenet</th><th>Típus</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                """.formatted(html(r.name), html(r.path.toString()), r.uniqueCommits, r.branches, r.authors.size(),
                unionSize(r.authors, r.roleParticipants), number(r.total.files), number(r.total.additions), number(r.total.deletions),
                origin, period(args), r.reachableCommits, dateTime(r.total.first), dateTime(r.total.last), r.total.activeDays.size(),
                r.total.merges, people, branches, fileRows(r.fileTypes), commitRows(r.commits, r.name, commitFiles)), "../");
    }

    private static String commitPage(CommitSummary c, CliOptions args, String patchText) {
        StringBuilder files = new StringBuilder();
        for (FileChange file : c.fileChanges) files.append("<tr><td><code>").append(html(file.path)).append("</code></td><td>")
                .append(file.binary ? "bináris" : number(file.additions)).append("</td><td>")
                .append(file.binary ? "bináris" : number(file.deletions)).append("</td></tr>");
        if (files.isEmpty()) files.append("<tr><td colspan=\"3\">Nincs fájlváltozás (például üres commit).</td></tr>");
        String patch = patchText.isBlank() ? "<p class=\"muted\">Ehhez a commithoz nincs szöveges diff.</p>" :
                "<details open><summary><strong>Teljes patch megjelenítése</strong></summary><pre class=\"diff\">" + html(patchText) + "</pre></details>";
        String changedCode = changedCodeHtml(c, patchText);
        return page(c.subject + " – " + args.title, """
                <nav><a href="../index.html">← Összesítő</a></nav><header><p class="eyebrow">COMMIT-ADATLAP · TELJES TARTALOM</p><h1>%s</h1>
                <p class="muted"><code>%s</code> · %s</p></header>
                <section class="cards six"><article><strong>%s</strong><span>repó</span></article><article><strong>%s</strong><span>dátum</span></article><article><strong>%s</strong><span>fájl</span></article><article><strong>%s</strong><span>hozzáadott sor</span></article><article><strong>%s</strong><span>törölt sor</span></article><article><strong>%s</strong><span>típus</span></article></section>
                <section class="grid"><article class="panel"><h2>Identitások</h2><dl><dt>Author</dt><dd>%s &lt;%s&gt;</dd><dt>Committer</dt><dd>%s &lt;%s&gt;</dd></dl></article>
                <article class="panel"><h2>Commit üzenet</h2><pre class="message">%s</pre></article></section>
                <section><h2>Érintett fájlok</h2><div class="table-wrap"><table><thead><tr><th>Útvonal</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">FORRÁSKÓD</p><h2>A konkrét megírt és törölt kód</h2></div></div>%s</section>
                <section><div class="section-title"><div><p class="kicker">KÓDVÁLTOZÁS</p><h2>Teljes diff</h2></div><p class="muted">A Git által előállított patch, 3 sor kontextussal.</p></div>%s</section>
                """.formatted(html(c.subject), html(c.hash), html(c.repository), html(c.repository), dateTime(c.date), c.files,
                number(c.additions), number(c.deletions), c.merge ? "merge" : commitType(c.subject), html(c.authorName),
                html(c.authorEmail), html(c.committerName), html(c.committerEmail), html(codeFocusedMessage(c.message)), files, changedCode, patch), "../");
    }

    private static String changedCodeHtml(CommitSummary commit, String patch) {
        List<ChangedCode> changes = changedCode(commit, patch);
        if (changes.stream().allMatch(c -> c.added.isBlank() && c.deleted.isBlank()))
            return "<p class=\"muted\">Nincs szöveges kódmódosítás.</p>";
        StringBuilder out = new StringBuilder();
        for (ChangedCode change : changes) {
            out.append("<article class=\"code-change\"><h3><code>").append(html(change.path)).append("</code></h3>");
            if (!change.added.isBlank()) out.append("<h4 class=\"added-label\">Hozzáadott kód</h4><pre class=\"source added\"><code>")
                    .append(html(change.added)).append("</code></pre>");
            if (!change.deleted.isBlank()) out.append("<h4 class=\"deleted-label\">Törölt kód</h4><pre class=\"source deleted\"><code>")
                    .append(html(change.deleted)).append("</code></pre>");
            out.append("</article>");
        }
        return out.toString();
    }

    private static String markdownIndex(Analysis analysis, CliOptions args, Map<String, String> developerFiles,
                                        Map<String, String> repositoryFiles) {
        StringBuilder out = new StringBuilder("# ").append(md(args.title)).append("\n\n")
                .append("Generálva: ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("  \n")
                .append("Gyökér: `").append(mdCode(analysis.root.toString())).append("`  \n")
                .append("Időszak: ").append(period(args)).append("\n\n")
                .append("## Összesítés\n\n")
                .append("| Repók | Közreműködők | Repón belül egyedi commitok | Branch/ref |\n|---:|---:|---:|---:|\n|")
                .append(analysis.repositories.size()).append('|').append(analysis.developers.size()).append('|')
                .append(analysis.seenCommits.size()).append('|').append(analysis.repositories.stream().mapToLong(r -> r.branches).sum()).append("|\n\n")
                .append("> **Értelmezési keret:** ezek leíró aktivitási adatok, nem automatikus teljesítménypontok.\n\n");
        if (args.outputs.contains("source")) out.append("[Branchenkénti teljes forráskód](source-code-index.md)\n\n");
        out.append("## Kód szerzői és társszerzői\n\n| Név | Commit | Co-author | Repó | Branch* | Aktív nap | + / − sor |\n|---|---:|---:|---:|---:|---:|---:|\n");
        analysis.developers.values().stream().sorted(Comparator.comparing(d -> d.displayName)).forEach(d -> out.append("|[")
                .append(md(d.displayName)).append("](developers/").append(markdownName(developerFiles.get(d.key))).append(")|")
                .append(d.authoredCommits).append('|').append(d.trailerCount("co-authored-by")).append('|').append(d.repositories.size()).append('|')
                .append(d.branchRefs.size()).append('|').append(d.participationDays.size()).append('|').append(d.additions).append(" / ").append(d.deletions).append("|\n"));
        out.append("\n## Repók\n\n| Repó | Commit | Branch/ref | Szerző | Fájlérintés | + / − sor |\n|---|---:|---:|---:|---:|---:|\n");
        analysis.repositories.stream().sorted(Comparator.comparing(r -> r.name)).forEach(r -> out.append("|[").append(md(r.name))
                .append("](repositories/").append(markdownName(repositoryFiles.get(r.name))).append(")|").append(r.uniqueCommits)
                .append('|').append(r.branches).append('|').append(r.authors.size()).append('|').append(r.total.files).append('|')
                .append(r.total.additions).append(" / ").append(r.total.deletions).append("|\n"));
        out.append("\n## Módszertani megjegyzések\n\n- Minden lokális és remote ref elérhető commitja szerepel.\n- A `.mailmap` identitásegyesítést alkalmazza.\n- A commitonkénti lapok a teljes Git diffet és a módosított kódsorokat tartalmazzák.\n");
        return out.toString();
    }

    private static String markdownDeveloper(Developer d, CliOptions args, Map<String, String> repositoryFiles,
                                            Map<String, String> commitFiles) {
        StringBuilder out = new StringBuilder("[← Összesítő](../index.md)\n\n# ").append(md(d.displayName)).append("\n\n")
                .append("Vizsgált időszak: ").append(period(args)).append("\n\n")
                .append("| Commit | Nem merge | Merge | Közreműködési nap | Repó | Branch* | Fájlérintés | + sor | − sor |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n|")
                .append(d.authoredCommits).append('|').append(d.nonMergeCommits).append('|').append(d.mergeCommits).append('|')
                .append(d.participationDays.size()).append('|').append(d.repositories.size()).append('|').append(d.branchRefs.size()).append('|')
                .append(d.filesChanged).append('|').append(d.additions).append('|').append(d.deletions).append("|\n\n")
                .append("## Identitás és időszak\n\n- Nevek: ").append(d.names.stream().sorted().map(ReportWriter::md).reduce((a,b) -> a + ", " + b).orElse("—"))
                .append("\n- E-mailek: ").append(d.emails.stream().sorted().map(e -> "`" + mdCode(e) + "`").reduce((a,b) -> a + ", " + b).orElse("—"))
                .append("\n- Első aktivitás: ").append(dateTime(d.firstActivity)).append("\n- Utolsó aktivitás: ").append(dateTime(d.lastActivity)).append("\n\n")
                .append("## Repónkénti bontás\n\n| Repó | Commit | Merge | Aktív nap | Branch* | Fájl | + / − sor |\n|---|---:|---:|---:|---:|---:|---:|\n");
        d.byRepository.forEach((name, s) -> out.append("|[").append(md(name)).append("](../repositories/").append(markdownName(repositoryFiles.get(name)))
                .append(")|").append(s.commits).append('|').append(s.merges).append('|').append(s.activeDays.size())
                .append('|').append(s.branches.size()).append('|').append(s.files).append('|').append(s.additions).append(" / ").append(s.deletions).append("|\n"));
        out.append("\n## Havi aktivitás\n\n| Hónap | Commit | Aktív nap | Fájl | + sor | − sor |\n|---|---:|---:|---:|---:|---:|\n");
        d.monthly.forEach((month, s) -> out.append('|').append(month).append('|').append(s.commits).append('|').append(s.activeDays.size())
                .append('|').append(s.files).append('|').append(s.additions).append('|').append(s.deletions).append("|\n"));
        out.append("\n## Trailer-szerepek\n\n").append(markdownMap(d.roles)).append("\n## Fájltípusok\n\n").append(markdownFiles(d.fileTypes))
                .append("\n## Branch/ref elérhetőség\n\n");
        d.branchRefs.stream().sorted().forEach(b -> out.append("- `").append(mdCode(b)).append("`\n"));
        out.append("\n## Commitok és teljes tartalmuk\n\n| Dátum | Commit | Repó | Üzenet | Fájl | + / − sor |\n|---|---|---|---|---:|---:|\n");
        d.commits.stream().sorted(Comparator.comparing(CommitSummary::date).reversed()).forEach(c -> out.append('|').append(dateTime(c.date)).append("|[")
                .append(c.hash.substring(0, Math.min(10, c.hash.length()))).append("](../commits/").append(markdownName(commitFiles.get(commitKey(c))))
                .append(")|").append(md(c.repository)).append('|').append(md(c.subject)).append('|').append(c.files).append('|')
                .append(c.additions).append(" / ").append(c.deletions).append("|\n"));
        return out.toString();
    }

    private static void writeMarkdownDeveloper(Path target, Developer developer, CliOptions args,
                                               Map<String, String> repositoryFiles, Map<String, String> commitFiles,
                                               EmbeddedCommitProgress progress)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(markdownDeveloper(developer, args, repositoryFiles, commitFiles));
            writer.write("\n## Commitok teljes tartalma\n\n");
            List<CommitSummary> commits = developer.commits.stream()
                    .sorted(Comparator.comparing(CommitSummary::date).reversed()).toList();
            for (int commitIndex = 0; commitIndex < commits.size(); commitIndex++) {
                CommitSummary commit = commits.get(commitIndex);
                progress.started(commitIndex, commits.size(), commit);
                StringBuilder section = new StringBuilder();
                appendEmbeddedCommit(section, commit);
                writer.write(section.toString());
            }
        }
        
    }

    private static void appendEmbeddedCommit(StringBuilder out, CommitSummary c) {
        String patch = patchText(c);
        out.append("### `").append(c.hash.substring(0, Math.min(12, c.hash.length()))).append("` — ")
                .append(md(c.subject)).append("\n\n")
                .append("- Teljes hash: `").append(c.hash).append("`\n- Repó: ").append(md(c.repository))
                .append("\n- Dátum: ").append(dateTime(c.date)).append("\n- Author: ").append(md(c.authorName))
                .append(" <").append(md(c.authorEmail)).append(">\n- Fájlok: ").append(c.files)
                .append("\n- Sorok: +").append(c.additions).append(" / −").append(c.deletions)
                .append("\n\n#### Commit üzenet\n\n```text\n").append(codeFocusedMessage(c.message))
                .append("\n```\n\n#### Érintett fájlok\n\n| Útvonal | + sor | − sor |\n|---|---:|---:|\n");
        if (c.fileChanges.isEmpty()) out.append("| _Nincs fájlváltozás_ | 0 | 0 |\n");
        for (FileChange file : c.fileChanges) out.append("|`").append(mdCode(file.path)).append("`|")
                .append(file.binary ? "bináris" : file.additions).append('|')
                .append(file.binary ? "bináris" : file.deletions).append("|\n");
        out.append("\n#### A konkrét megírt és törölt kód\n\n");
        appendChangedCodeMarkdown(out, c, patch);
        out.append("#### Teljes diff\n\n");
        if (patch.isBlank()) out.append("_Nincs szöveges diff._\n\n");
        else out.append("````diff\n").append(patch).append("\n````\n\n");
    }

    private static void appendChangedCodeMarkdown(StringBuilder out, CommitSummary commit, String patch) {
        List<ChangedCode> changes = changedCode(commit, patch);
        if (changes.stream().allMatch(c -> c.added.isBlank() && c.deleted.isBlank())) {
            out.append("_Nincs szöveges kódmódosítás._\n\n");
            return;
        }
        for (ChangedCode change : changes) {
            out.append("##### `").append(mdCode(change.path)).append("`\n\n");
            String language = languageFor(change.path);
            if (!change.added.isBlank()) out.append("**Hozzáadott kód:**\n\n~~~~").append(language).append('\n')
                    .append(change.added).append("\n~~~~\n\n");
            if (!change.deleted.isBlank()) out.append("**Törölt kód:**\n\n~~~~").append(language).append('\n')
                    .append(change.deleted).append("\n~~~~\n\n");
        }
    }

    private static List<ChangedCode> changedCode(CommitSummary commit, String patch) {
        if (patch.isBlank()) return List.of();
        String[] sections = patch.split("(?m)(?=^diff --git )");
        List<ChangedCode> result = new ArrayList<>();
        int fileIndex = 0;
        for (String section : sections) {
            if (section.isBlank()) continue;
            String path = fileIndex < commit.fileChanges.size() ? commit.fileChanges.get(fileIndex).path : "ismeretlen fájl";
            fileIndex++;
            StringBuilder added = new StringBuilder();
            StringBuilder deleted = new StringBuilder();
            for (String line : section.split("\\R", -1)) {
                if (line.startsWith("+++") || line.startsWith("---")) continue;
                if (line.startsWith("+")) added.append(line.substring(1)).append('\n');
                else if (line.startsWith("-")) deleted.append(line.substring(1)).append('\n');
            }
            result.add(new ChangedCode(path, added.toString().stripTrailing(), deleted.toString().stripTrailing()));
        }
        return result;
    }

    static String languageFor(String path) {
        String ext = extension(path);
        return switch (ext) {
            case ".java" -> "java"; case ".kt", ".kts" -> "kotlin"; case ".js", ".mjs", ".cjs" -> "javascript";
            case ".ts", ".tsx" -> "typescript"; case ".py" -> "python"; case ".cs" -> "csharp";
            case ".c", ".h" -> "c"; case ".cpp", ".cc", ".cxx", ".hpp" -> "cpp"; case ".go" -> "go";
            case ".rs" -> "rust"; case ".rb" -> "ruby"; case ".php" -> "php"; case ".sql" -> "sql";
            case ".html", ".htm" -> "html"; case ".css" -> "css"; case ".scss" -> "scss"; case ".xml" -> "xml";
            case ".json" -> "json"; case ".yaml", ".yml" -> "yaml"; case ".md" -> "markdown";
            case ".sh" -> "bash"; case ".ps1" -> "powershell"; default -> "text";
        };
    }

    private static String markdownRepository(RepositoryStats r, CliOptions args, Analysis analysis,
                                             Map<String, String> developerFiles, Map<String, String> commitFiles) {
        StringBuilder out = new StringBuilder("[← Összesítő](../index.md)\n\n# ").append(md(r.name)).append("\n\n- Útvonal: `")
                .append(mdCode(r.path.toString())).append("`\n- Origin: `").append(mdCode(r.originUrl.isBlank() ? "—" : r.originUrl))
                .append("`\n- Időszak: ").append(period(args)).append("\n\n")
                .append("| Commit | Branch/ref | Szerző | Fájlérintés | + sor | − sor |\n|---:|---:|---:|---:|---:|---:|\n|")
                .append(r.uniqueCommits).append('|').append(r.branches).append('|').append(r.authors.size()).append('|').append(r.total.files)
                .append('|').append(r.total.additions).append('|').append(r.total.deletions).append("|\n\n## Közreműködők\n\n")
                .append("| Név | Commit | Merge | Aktív nap | Branch* | Co-author | + / − sor |\n|---|---:|---:|---:|---:|---:|---:|\n");
        r.byDeveloper.forEach((key, s) -> { Developer d = analysis.developers.get(key); out.append("|[").append(md(d.displayName))
                .append("](../developers/").append(markdownName(developerFiles.get(key))).append(")|").append(s.commits).append('|')
                .append(s.merges).append('|').append(s.activeDays.size()).append('|').append(s.branches.size())
                .append('|').append(s.roles.values().stream().mapToLong(Long::longValue).sum()).append('|').append(s.additions).append(" / ").append(s.deletions).append("|\n"); });
        out.append("\n## Branchek és remote refek\n\n| Név | Típus | Commit | Szerző |\n|---|---|---:|---:|\n");
        r.branchStats.stream().sorted(Comparator.comparing(b -> b.name)).forEach(b -> out.append("|`").append(mdCode(b.name)).append("`|")
                .append(b.remote ? "remote" : "local").append('|').append(b.commits).append('|').append(b.authors.size()).append("|\n"));
        out.append("\n## Fájltípusok\n\n").append(markdownFiles(r.fileTypes)).append("\n## Commitok és teljes tartalmuk\n\n")
                .append("| Dátum | Commit | Üzenet | Fájl | + / − sor |\n|---|---|---|---:|---:|\n");
        r.commits.stream().sorted(Comparator.comparing(CommitSummary::date).reversed()).forEach(c -> out.append('|').append(dateTime(c.date)).append("|[")
                .append(c.hash.substring(0, Math.min(10, c.hash.length()))).append("](../commits/").append(markdownName(commitFiles.get(commitKey(c))))
                .append(")|").append(md(c.subject)).append('|').append(c.files).append('|').append(c.additions).append(" / ").append(c.deletions).append("|\n"));
        return out.toString();
    }

    private static String markdownCommit(CommitSummary c, CliOptions args, String patch) {
        StringBuilder out = new StringBuilder("[← Összesítő](../index.md)\n\n# ").append(md(c.subject)).append("\n\n")
                .append("- Hash: `").append(c.hash).append("`\n- Repó: ").append(md(c.repository)).append("\n- Dátum: ").append(dateTime(c.date))
                .append("\n- Author: ").append(md(c.authorName)).append(" <").append(md(c.authorEmail)).append(">\n- Committer: ")
                .append(md(c.committerName)).append(" <").append(md(c.committerEmail)).append(">\n- Fájlok: ").append(c.files)
                .append("\n- Sorok: +").append(c.additions).append(" / −").append(c.deletions).append("\n\n## Commit üzenet\n\n```text\n")
                .append(codeFocusedMessage(c.message)).append("\n```\n\n## Érintett fájlok\n\n| Útvonal | + sor | − sor |\n|---|---:|---:|\n");
        for (FileChange file : c.fileChanges) out.append("|`").append(mdCode(file.path)).append("`|")
                .append(file.binary ? "bináris" : file.additions).append('|').append(file.binary ? "bináris" : file.deletions).append("|\n");
        out.append("\n## Teljes diff\n\n");
        if (patch.isBlank()) out.append("_Nincs szöveges diff._\n");
        else out.append("````diff\n").append(patch).append("\n````\n");
        return out.toString();
    }

    @FunctionalInterface
    private interface EmbeddedCommitProgress {
        void started(int commitIndex, int total, CommitSummary commit);
    }

    private static String patchText(CommitSummary commit) {
        PatchRef patch = commit.patch;
        if (patch == null || patch.length <= 0 || patch.length > Integer.MAX_VALUE || !Files.exists(patch.file)) return "";
        try (FileChannel channel = FileChannel.open(patch.file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) patch.length);
            channel.position(patch.offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString().strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static String markdownMap(Map<String, Long> values) {
        if (values.isEmpty()) return "_Nincs adat._\n";
        StringBuilder out = new StringBuilder("| Típus | Darab |\n|---|---:|\n");
        values.forEach((key, value) -> out.append('|').append(md(key)).append('|').append(value).append("|\n"));
        return out.toString();
    }

    private static String markdownFiles(Map<String, FileDelta> values) {
        if (values.isEmpty()) return "_Nincs fájlváltozás._\n";
        StringBuilder out = new StringBuilder("| Kiterjesztés | Fájlérintés | + sor | − sor |\n|---|---:|---:|---:|\n");
        values.entrySet().stream().sorted(Comparator.<Map.Entry<String, FileDelta>>comparingLong(e -> e.getValue().files).reversed())
                .forEach(e -> out.append("|`").append(mdCode(e.getKey())).append("`|").append(e.getValue().files).append('|')
                        .append(e.getValue().additions).append('|').append(e.getValue().deletions).append("|\n"));
        return out.toString();
    }

    static String md(String value) { return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " "); }
    static String mdCode(String value) { return value == null ? "" : value.replace("`", "'"); }

    private static String codeFocusedMessage(String message) {
        if (message == null) return "";
        return message.lines().filter(line -> {
            Matcher matcher = TRAILER.matcher(line);
            return !matcher.matches() || matcher.group(1).equalsIgnoreCase("Co-authored-by");
        }).reduce((a, b) -> a + "\n" + b).orElse("").strip();
    }

    private static String commitRows(List<CommitSummary> commits, String repositoryOverride, Map<String, String> commitFiles) {
        StringBuilder rows = new StringBuilder();
        commits.stream().sorted(Comparator.comparing(CommitSummary::date).reversed()).forEach(c -> rows.append("<tr><td>")
                .append(dateTime(c.date)).append("</td><td><a href=\"../commits/").append(attr(commitFiles.get(commitKey(c))))
                .append("\"><code title=\"").append(attr(c.hash)).append("\">")
                .append(html(c.hash.substring(0, Math.min(10, c.hash.length())))).append("</code></a></td><td>")
                .append(html(repositoryOverride == null ? c.repository : repositoryOverride)).append("</td><td class=\"subject\">")
                .append(html(c.subject)).append("</td><td>").append(c.merge ? "merge" : commitType(c.subject)).append("</td><td>")
                .append(c.files).append("</td><td>").append(number(c.additions)).append("</td><td>").append(number(c.deletions)).append("</td></tr>"));
        return rows.isEmpty() ? "<tr><td colspan=\"8\">Nincs szerzői commit.</td></tr>" : rows.toString();
    }

    private static String fileRows(Map<String, FileDelta> values) {
        StringBuilder rows = new StringBuilder();
        values.entrySet().stream().sorted(Comparator.<Map.Entry<String, FileDelta>>comparingLong(e -> e.getValue().files).reversed())
                .forEach(e -> rows.append("<tr><td><code>").append(html(e.getKey())).append("</code></td><td>")
                        .append(number(e.getValue().files)).append("</td><td>").append(number(e.getValue().additions))
                        .append("</td><td>").append(number(e.getValue().deletions)).append("</td><td>")
                        .append(number(e.getValue().additions + e.getValue().deletions)).append("</td></tr>"));
        return rows.isEmpty() ? "<tr><td colspan=\"5\">Nincs szöveges fájlváltozás.</td></tr>" : rows.toString();
    }

    private static String rhythmRows(Developer d) {
        StringBuilder rows = new StringBuilder();
        long total = Math.max(1, d.authoredCommits);
        for (int i = 0; i < WEEKDAYS.length; i++) rows.append(rhythmRow("Hét napja", WEEKDAYS[i], d.weekdays[i], total));
        for (int hour = 0; hour < 24; hour++) if (d.hours[hour] > 0)
            rows.append(rhythmRow("Óra", String.format(Locale.ROOT, "%02d:00–%02d:59", hour, hour), d.hours[hour], total));
        return rows.toString();
    }

    private static String rhythmRow(String dimension, String value, long count, long total) {
        return "<tr><td>" + html(dimension) + "</td><td>" + html(value) + "</td><td>" + count +
                "</td><td>" + String.format(Locale.ROOT, "%.1f%%", count * 100.0 / total) + "</td></tr>";
    }

    private static String mapRows(Map<String, Long> values, String empty) {
        if (values.isEmpty()) return "<tr><td colspan=\"2\">" + html(empty) + "</td></tr>";
        StringBuilder rows = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> rows.append("<tr><td><code>").append(html(e.getKey())).append("</code></td><td>")
                        .append(number(e.getValue())).append("</td></tr>"));
        return rows.toString();
    }

    private static String meter(long value, long max) {
        long width = max <= 0 ? 0 : Math.max(2, Math.round(value * 100.0 / max));
        return "<span class=\"meter\"><i style=\"width:" + width + "%\"></i></span>";
    }

    private static String commitType(String subject) {
        Matcher matcher = CONVENTIONAL_COMMIT.matcher(subject);
        return matcher.find() ? html(matcher.group(1).toLowerCase(Locale.ROOT)) : "egyéb";
    }

    private static int unionSize(Set<String> first, Set<String> second) {
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return union.size();
    }

    private static long spanDays(Instant first, Instant last) {
        if (first == null || last == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
    }

    private static String period(CliOptions args) {
        if (args.since == null && args.until == null) return "teljes elérhető történet";
        return html(args.since == null ? "kezdet" : args.since) + " – " + html(args.until == null ? "napjaink" : args.until);
    }

    private static String page(String title, String body, String prefix) {
        return TemplateRenderer.page(title, prefix, body);
    }

    static String html(String value) {
        return value == null ? "" : StringEscapeUtils.escapeHtml4(value).replace("'", "&#39;");
    }

    private static String attr(String value) { return html(value); }
    private static String date(Instant instant) { return instant == null ? "—" : DATE.format(instant); }
    private static String dateTime(Instant instant) { return instant == null ? "—" : DATE_TIME.format(instant); }
    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value).replace(',', ' '); }
    private static String signed(long value) { return (value > 0 ? "+" : "") + number(value); }

    static String slug(String value) {
        String slug = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "item" : slug;
    }

    private static String css() {
        return """
                :root{--ink:#16221f;--muted:#64736d;--paper:#f3f6f2;--card:#fff;--line:#d9e1dc;--accent:#12654f;--accent2:#d9eee5;--head:#eaf0ec}
                *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
                main{max-width:1440px;margin:auto;padding:48px 28px 70px}header{margin:16px 0 30px}.eyebrow,.kicker{font-size:11px;letter-spacing:.18em;color:var(--accent);font-weight:800;margin:0 0 8px}
                h1{font-size:clamp(34px,5vw,62px);line-height:1.02;margin:.12em 0}h2{font-size:25px;margin:0}h3{margin-top:0}.muted,.footnote{color:var(--muted)}.footnote{font-size:13px}code{font-size:.88em;overflow-wrap:anywhere}
                nav{position:sticky;top:0;z-index:4;padding:12px 0;background:color-mix(in srgb,var(--paper) 92%,transparent);backdrop-filter:blur(8px)}a{color:var(--accent);font-weight:680;text-decoration:none}a:hover{text-decoration:underline}
                .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(165px,1fr));gap:12px;margin:26px 0}.cards article,.panel,.method{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:19px;box-shadow:0 2px 12px #18382b0a}
                .cards strong{display:block;font-size:26px;line-height:1.2}.cards span{color:var(--muted);font-size:13px}.six{grid-template-columns:repeat(auto-fit,minmax(155px,1fr))}section{margin:38px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(310px,1fr));gap:16px}
                .section-title{display:flex;align-items:end;justify-content:space-between;gap:20px;margin-bottom:13px}.section-title p{margin:0}aside{border-left:5px solid var(--accent);background:var(--accent2);padding:17px 20px;border-radius:4px 12px 12px 4px}
                .table-wrap{overflow:auto;border-radius:12px;border:1px solid var(--line)}table{width:100%;border-collapse:collapse;background:var(--card)}th,td{padding:11px 13px;text-align:left;border-bottom:1px solid var(--line);white-space:nowrap;vertical-align:top}th{position:sticky;top:0;background:var(--head);font-size:11px;text-transform:uppercase;letter-spacing:.05em;z-index:1}tr:last-child td{border-bottom:0}tr:hover td{background:#f8fbf9}.subject{white-space:normal;min-width:260px;max-width:620px}
                dl{display:grid;grid-template-columns:minmax(110px,1fr) 2fr;gap:8px 16px;margin:0}dt{color:var(--muted)}dd{margin:0;text-align:right;font-weight:650;overflow-wrap:anywhere}.wrap{white-space:normal}.badge{display:inline-block;padding:2px 8px;border-radius:999px;background:var(--accent2);font-size:11px}.meter{display:block;width:120px;height:8px;background:#e4e9e6;border-radius:9px;overflow:hidden}.meter i{display:block;height:100%;background:var(--accent)}
                details{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}summary{cursor:pointer}.columns{columns:3;column-width:260px}.message,.diff,.source{white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0 0;font:13px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace}.diff{max-height:75vh;overflow:auto;background:#101713;color:#d9e7df;border-radius:9px;padding:16px;white-space:pre}.message{background:#f5f7f5;border-radius:8px;padding:12px}.code-change{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px;margin:12px 0}.code-change h3{margin:0 0 14px}.code-change h4{margin:16px 0 6px}.added-label{color:#12633f}.deleted-label{color:#9b3131}.source{padding:14px;border-radius:8px;overflow:auto;white-space:pre}.source.added{background:#e9f7ef;border-left:4px solid #25834f}.source.deleted{background:#fbecec;border-left:4px solid #bb4545}footer{max-width:1440px;margin:auto;padding:0 28px 35px;color:var(--muted);font-size:13px}
                @media(max-width:720px){main{padding:28px 14px}.section-title{align-items:start;flex-direction:column}.columns{columns:1}th,td{padding:9px 10px}.cards strong{font-size:22px}}
                @media print{body{background:white}main{max-width:none;padding:15px}.cards article,.panel,.method{box-shadow:none}nav{display:none}section{break-inside:avoid}.table-wrap{overflow:visible}th{position:static}}
                """;
    }
}
