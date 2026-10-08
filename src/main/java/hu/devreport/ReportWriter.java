package hu.devreport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
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
import java.time.temporal.WeekFields;
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
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;

import static hu.devreport.GitReportApplication.*;

final class ReportWriter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] WEEKDAYS = {"Hétfő", "Kedd", "Szerda", "Csütörtök", "Péntek", "Szombat", "Vasárnap"};
    private static final String CHART_RESOURCE = "/META-INF/resources/webjars/echarts/6.1.0/dist/echarts.min.js";
    private static final int DAILY_CHART_LIMIT = 90;
    private static final int WEEKLY_CHART_LIMIT = 104;
    private static final int MONTHLY_CHART_LIMIT = 60;

    static void write(Path output, Analysis analysis, CliOptions args, ProgressReporter.Stage progress)
            throws IOException, InterruptedException {
        long commitCount = analysis.repositories.stream().mapToLong(repository -> repository.commits.size()).sum();
        long totalItems = 1L + analysis.developers.size() + analysis.repositories.size()
                + commitCount + commitCount;
        long[] completedItems = {0};
        long[] writtenBytes = {0};
        progress.update(0, "Kimeneti könyvtárak és régi generált oldalak előkészítése", 0, totalItems);
        Files.createDirectories(output);
        Files.createDirectories(output.resolve("developers"));
        Files.createDirectories(output.resolve("repositories"));
        Files.createDirectories(output.resolve("commits"));
        cleanGeneratedPages(output.resolve("developers"), ".html");
        cleanGeneratedPages(output.resolve("repositories"), ".html");
        cleanGeneratedPages(output.resolve("commits"), ".html");
        removeLegacyMarkdown(output);
        Map<String, String> developerFiles = developerFilenames(analysis.developers.values());
        Map<String, String> repositoryFiles = repositoryFilenames(analysis.repositories);
        Map<String, String> commitFiles = commitFilenames(analysis.repositories);
        long snapshotBase = completedItems[0];
        HtmlSnapshotExporter.SnapshotStats snapshots = HtmlSnapshotExporter.write(output, analysis,
                (current, total, repository, commit, changes, blobs, activity) -> updateReportProgress(progress,
                        snapshotBase + current, totalItems, writtenBytes[0],
                        activity + " | Offline snapshot " + current + "/" + total + " | " + repository + " | "
                                + shortHash(commit) + " | Delta: " + changes + " fájl | Egyedi blob: " + blobs));
        completedItems[0] += snapshots.commits();
        writtenBytes[0] += snapshots.bytes();
        updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0],
                "Fő indexoldalak renderelése");
        Path styleTarget = output.resolve("style.css");
        Path chartTarget = output.resolve("echarts.min.js");
        Path indexTarget = output.resolve("index.html");
        Path dashboardTarget = output.resolve("dashboard.html");
        Files.writeString(styleTarget, css(), StandardCharsets.UTF_8);
        Files.deleteIfExists(output.resolve("chart.umd.min.js"));
        writeChartLibrary(chartTarget);
        Files.writeString(indexTarget, index(analysis, args, developerFiles, repositoryFiles, commitFiles), StandardCharsets.UTF_8);
        Files.writeString(dashboardTarget, dashboardPage(analysis, args, commitFiles), StandardCharsets.UTF_8);
        writtenBytes[0] += Files.size(styleTarget) + Files.size(chartTarget) + Files.size(indexTarget)
                + Files.size(dashboardTarget);
        completedItems[0]++;
        updateReportProgress(progress, completedItems[0], totalItems, writtenBytes[0], "Fő indexoldalak elkészültek");
        AtomicLong parallelCompleted = new AtomicLong(completedItems[0]);
        AtomicLong parallelBytes = new AtomicLong(writtenBytes[0]);
        int pageTasks = analysis.developers.size() + analysis.repositories.size() + (int) commitCount;
        ParallelSupport.Activity pageActivity = ParallelSupport.activity(pageTasks, 8);
        updateReportProgress(progress, parallelCompleted.get(), totalItems, parallelBytes.get(),
                pageActivity.label() + " | Párhuzamos oldalírás indítása");
        ExecutorService pageExecutor = ParallelSupport.executor("report-page-", pageTasks, 8);
        List<Future<?>> pageFutures = new ArrayList<>();
        try {
            int developerIndex = 0;
            for (Developer developer : analysis.developers.values()) {
                developerIndex++;
                String developerPrefix = "Fejlesztő " + developerIndex + "/" + analysis.developers.size()
                        + " | " + developer.displayName;
                pageFutures.add(pageExecutor.submit(() -> {
                    try (ParallelSupport.Scope ignored = pageActivity.start()) {
                        updateReportProgress(progress, parallelCompleted.get(), totalItems, parallelBytes.get(),
                                pageActivity.label() + " | " + developerPrefix + " | indul");
                        Path target = output.resolve("developers").resolve(developerFiles.get(developer.key));
                        Files.writeString(target, developerPage(developer, args, repositoryFiles, commitFiles), StandardCharsets.UTF_8);
                        parallelBytes.addAndGet(Files.size(target));
                        long done = parallelCompleted.incrementAndGet();
                        updateReportProgress(progress, done, totalItems, parallelBytes.get(),
                                pageActivity.label() + " | " + developerPrefix + " | kész");
                        return null;
                    }
                }));
            }
            int repositoryIndex = 0;
            int commitIndex = 0;
            for (RepositoryStats repository : analysis.repositories) {
                repositoryIndex++;
                String repositoryPrefix = "Repó " + repositoryIndex + "/" + analysis.repositories.size()
                        + " | " + repository.name;
                pageFutures.add(pageExecutor.submit(() -> {
                    try (ParallelSupport.Scope ignored = pageActivity.start()) {
                        updateReportProgress(progress, parallelCompleted.get(), totalItems, parallelBytes.get(),
                                pageActivity.label() + " | " + repositoryPrefix + " | indul");
                        Path target = output.resolve("repositories").resolve(repositoryFiles.get(repository.name));
                        Files.writeString(target, repositoryPage(repository, args, analysis, developerFiles, commitFiles), StandardCharsets.UTF_8);
                        parallelBytes.addAndGet(Files.size(target));
                        long done = parallelCompleted.incrementAndGet();
                        updateReportProgress(progress, done, totalItems, parallelBytes.get(),
                                pageActivity.label() + " | " + repositoryPrefix + " | kész");
                        return null;
                    }
                }));
                for (CommitSummary commit : repository.commits) {
                    commitIndex++;
                    String filename = commitFiles.get(commitKey(commit));
                    String commitPrefix = "Commit " + commitIndex + "/" + commitCount + " | "
                            + repository.name + " | " + shortHash(commit);
                    pageFutures.add(pageExecutor.submit(() -> {
                        try (ParallelSupport.Scope ignored = pageActivity.start()) {
                            updateReportProgress(progress, parallelCompleted.get(), totalItems, parallelBytes.get(),
                                    pageActivity.label() + " | " + commitPrefix + " | indul");
                            String patch = args.includePatches ? patchText(commit) : "";
                            Path target = output.resolve("commits").resolve(filename);
                            Files.writeString(target, commitPage(commit, args, patch, repository.originUrl), StandardCharsets.UTF_8);
                            parallelBytes.addAndGet(Files.size(target));
                            long done = parallelCompleted.incrementAndGet();
                            updateReportProgress(progress, done, totalItems, parallelBytes.get(),
                                    pageActivity.label() + " | " + commitPrefix + " | kész");
                            return null;
                        }
                    }));
                }
            }
            ParallelSupport.await(pageFutures);
        } finally {
            pageExecutor.shutdownNow();
        }
        completedItems[0] = parallelCompleted.get();
        writtenBytes[0] = parallelBytes.get();
        updateReportProgress(progress, totalItems, totalItems, writtenBytes[0],
                "Riportfájlok elkészültek");
    }

    private static void removeLegacyMarkdown(Path output) throws IOException {
        for (String directory : List.of("developers", "repositories", "commits")) {
            cleanGeneratedPages(output.resolve(directory), ".md");
        }
        Files.deleteIfExists(output.resolve("index.md"));
        try (var files = Files.list(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("source-code-") && name.endsWith(".md")) Files.deleteIfExists(file);
            }
        }
    }

    private static void writeChartLibrary(Path target) throws IOException {
        try (InputStream input = ReportWriter.class.getResourceAsStream(CHART_RESOURCE)) {
            if (input == null) throw new IOException("A beágyazott Apache ECharts erőforrás nem található: " + CHART_RESOURCE);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void updateReportProgress(ProgressReporter.Stage progress, long completed, long total,
                                             long writtenBytes, String detail) {
        synchronized (progress) {
            progress.update(completed / (double) Math.max(1, total),
                    detail + " | Kiírva: " + progressBytes(writtenBytes), completed, total);
        }
    }

    private static String progressBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return "%.1f GB".formatted(bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024L) return "%.1f MB".formatted(bytes / (1024d * 1024d));
        if (bytes >= 1024L) return "%.1f KB".formatted(bytes / 1024d);
        return bytes + " B";
    }

    private static String shortHash(CommitSummary commit) {
        return shortHash(commit.hash);
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(12, hash.length()));
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

    private static String uniqueFilename(String value, Set<String> used) {
        String base = slug(value);
        String candidate = base + ".html";
        int suffix = 2;
        while (!used.add(candidate)) candidate = base + "-" + suffix++ + ".html";
        return candidate;
    }

    private static String remoteHtml(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return "—";
        String web = githubWebUrl(remoteUrl);
        return web.isBlank()
                ? "<code>" + html(remoteUrl) + "</code>"
                : "<a href=\"" + attr(web) + "\">" + html(web) + "</a>";
    }

    static String githubWebUrl(String remoteUrl) {
        if (remoteUrl == null) return "";
        String value = remoteUrl.trim();
        Matcher scp = Pattern.compile("^git@github\\.com:(.+)$", Pattern.CASE_INSENSITIVE).matcher(value);
        Matcher ssh = Pattern.compile("^ssh://git@(?:ssh\\.)?github\\.com(?::\\d+)?/(.+)$", Pattern.CASE_INSENSITIVE).matcher(value);
        Matcher https = Pattern.compile("^https?://github\\.com/(.+)$", Pattern.CASE_INSENSITIVE).matcher(value);
        String path;
        if (scp.matches()) path = scp.group(1);
        else if (ssh.matches()) path = ssh.group(1);
        else if (https.matches()) path = https.group(1);
        else return "";
        path = path.replaceFirst("(?i)\\.git/?$", "").replaceAll("^/+|/+$", "");
        return path.isBlank() ? "" : "https://github.com/" + path;
    }


    private static String index(Analysis analysis, CliOptions args, Map<String, String> developerFiles,
                                Map<String, String> repositoryFiles, Map<String, String> commitFiles) {
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
                    .append("</td><td>").append(remoteHtml(r.originUrl)).append("</td><td><code>")
                    .append(html(r.path.toString())).append("</code></td></tr>");
        }
        StringBuilder branchRows = new StringBuilder();
        for (RepositoryStats repository : analysis.repositories.stream().sorted(Comparator.comparing(x -> x.name)).toList()) {
            for (BranchStats branch : repository.branchStats.stream().sorted(Comparator.comparing(x -> x.name)).toList()) {
                branchRows.append("<tr><td><a href=\"repositories/").append(attr(repositoryFiles.get(repository.name)))
                        .append("\">").append(html(repository.name)).append("</a></td><td><code>")
                        .append(html(branch.name)).append("</code></td><td>").append(branch.remote ? "remote" : "local")
                        .append("</td><td><code>").append(html(branch.upstream.isBlank() ? "—" : branch.upstream))
                        .append("</code></td><td><code title=\"").append(attr(branch.tipHash)).append("\">")
                        .append(html(shortHash(branch.tipHash))).append("</code></td><td>").append(dateTime(branch.lastCommit))
                        .append("</td><td>").append(number(branch.reachableCommits)).append("</td><td>")
                        .append(number(branch.commits)).append("</td><td>").append(branch.authors.size()).append("</td></tr>");
            }
        }
        if (branchRows.isEmpty()) branchRows.append("<tr><td colspan=\"9\">Nem található lokális vagy remote branch/ref. Futtass <code>--fetch</code> opcióval, ha a remote adatok hiányoznak.</td></tr>");
        String warnings = analysis.warnings.isEmpty() ? "" : "<section><h2>Figyelmeztetések</h2><ul>" +
                analysis.warnings.stream().map(w -> "<li>" + html(w) + "</li>").reduce("", String::concat) + "</ul></section>";
        String sourceLink = "";
        String dashboardLink = "<section><div class=\"section-title\"><div><p class=\"kicker\">INTERAKTÍV ELEMZÉS</p><h2>Fejlesztői aktivitás dashboard</h2></div></div>"
                + "<p>A szűrhető grafikonok, PMD/CPD-idősorok, commit drill-down és offline forrássnapshot-böngésző külön oldalon érhető el.</p>"
                + "<p><a class=\"action-link\" href=\"dashboard.html\">Dashboard megnyitása →</a></p></section>";
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
                %s
                <section><div class="section-title"><div><p class="kicker">SZEMÉLYEK</p><h2>Közreműködők részletes összesítője</h2></div><p class="muted">A névre kattintva teljes adatlap nyílik.</p></div>
                <div class="table-wrap"><table class="sortable"><thead><tr><th>Név</th><th>Commit</th><th>Nem merge</th><th>Merge</th><th>Co-author</th><th>Repó</th><th>Branch*</th><th>Aktív nap</th><th>Fájlérintés</th><th>+ / − sor</th><th>Első</th><th>Utolsó</th></tr></thead><tbody>%s</tbody></table></div>
                <p class="footnote">* Olyan branch/ref, amelyből a személy legalább egy vizsgált commitja elérhető; ez nem feltétlenül az eredeti commitolási branch.</p></section>
                <section><div class="section-title"><div><p class="kicker">KÓDBÁZISOK</p><h2>Git/GitHub repók egyben</h2></div><p class="muted">A GitHub-link az origin remote-ból származik.</p></div><div class="table-wrap"><table><thead><tr><th>Repó</th><th>Branch/ref</th><th>Commit</th><th>Szerző</th><th>Fájlérintés</th><th>+ / − sor</th><th>GitHub / origin</th><th>Lokális útvonal</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">TELJES REF-LELTÁR</p><h2>Minden repó minden branch-e</h2></div><p class="muted">A teljes történet és a kiválasztott dátumablak számai külön látszanak.</p></div><div class="table-wrap"><table><thead><tr><th>Repó</th><th>Branch/ref</th><th>Típus</th><th>Upstream</th><th>Tip</th><th>Utolsó commit</th><th>Teljes commit</th><th>Időszakbeli commit</th><th>Szerző</th></tr></thead><tbody>%s</tbody></table></div><p class="footnote">Egy commit több branchből is elérhető lehet. A Git nem őrzi meg megbízhatóan, melyik branchen keletkezett eredetileg.</p></section>
                <section class="method"><h2>Módszertan és lefedettség</h2><div class="grid"><div><h3>Beleszámít</h3><ul><li>Minden lokális és remote ref által elérhető commit</li><li>Author és <code>Co-authored-by</code> identitás</li><li>Teljes commitüzenet, fájllista és Git diff</li><li>Bináris fájlok fájlérintésként, sorszám nélkül</li><li>A repó <code>.mailmap</code> szabályai</li></ul></div><div><h3>Nem állapítható meg Gitből</h3><ul><li>Kódminőség és üzleti hatás</li><li>A társszerző által írt pontos sorok</li><li>Mentoring, tervezés és kommunikáció</li><li>A commit eredeti branch-e</li><li>Munkaidő vagy ráfordított idő</li></ul></div></div></section>%s
                """.formatted(html(args.title), OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), html(analysis.root.toString()),
                period(args), analysis.repositories.size(), developers.size(), analysis.seenCommits.size(), branchCount,
                number(additions), number(deletions), sourceLink, dashboardLink, rows, repoRows, branchRows, warnings), "");
    }

    private static String dashboardPage(Analysis analysis, CliOptions args, Map<String, String> commitFiles) {
        return page("Dashboard – " + args.title, """
                <nav><a href="index.html">← Összesítő</a></nav>
                <header><p class="eyebrow">FEJLESZTŐI AKTIVITÁS DASHBOARD</p><h1>%s</h1>
                <p class="muted">A grafikonok, drill-down adatok és forrássnapshotok helyi riportfájlokból, internetkapcsolat nélkül működnek.</p></header>
                %s
                """.formatted(html(args.title), portfolioCharts(analysis, commitFiles)), "");
    }

    static String portfolioCharts(Analysis analysis) {
        return portfolioCharts(analysis, commitFilenames(analysis.repositories));
    }

    private static String portfolioCharts(Analysis analysis, Map<String, String> commitFiles) {
        List<CommitSummary> commits = analysis.repositories.stream().flatMap(repository -> repository.commits.stream())
                .sorted(Comparator.comparing(CommitSummary::date)).toList();
        Map<CommitSummary, String> developerByCommit = new HashMap<>();
        for (CommitSummary commit : commits) {
            Developer developer = analysis.resolvedDeveloper(commit.authorName, commit.authorEmail);
            developerByCommit.put(commit, developer == null ? commit.authorName : developer.displayName);
        }
        List<String> developerNames = developerByCommit.values().stream().distinct().sorted().toList();
        List<String> repositoryNames = commits.stream().map(commit -> commit.repository).distinct().sorted().toList();
        List<String> branchNames = commits.stream().flatMap(commit -> commit.branches.stream()
                .map(branch -> commit.repository + " :: " + branch)).distinct().sorted().toList();
        Map<String, Integer> developerIds = indexes(developerNames);
        Map<String, Integer> repositoryIds = indexes(repositoryNames);
        Map<String, Integer> branchIds = indexes(branchNames);
        Map<String, String> snapshotRoots = new HashMap<>();
        for (RepositoryStats repository : analysis.repositories) {
            snapshotRoots.put(repository.name, githubWebUrl(repository.originUrl));
        }
        StringBuilder data = new StringBuilder("{\"developers\":").append(stringArray(developerNames))
                .append(",\"repositories\":").append(stringArray(repositoryNames))
                .append(",\"branches\":").append(stringArray(branchNames)).append(",\"commits\":[");
        boolean first = true;
        for (CommitSummary commit : commits) {
            if (!first) data.append(',');
            first = false;
            data.append("{\"d\":").append(jsonString(DATE.format(commit.date)))
                    .append(",\"u\":").append(developerIds.get(developerByCommit.get(commit)))
                    .append(",\"r\":").append(repositoryIds.get(commit.repository))
                    .append(",\"b\":").append(integerArray(commit.branches.stream().sorted()
                            .map(branch -> branchIds.get(commit.repository + " :: " + branch)).toList()))
                    .append(",\"a\":").append(commit.additions)
                    .append(",\"x\":").append(commit.deletions)
                    .append(",\"f\":").append(commit.files)
                    .append(",\"h\":").append(jsonString(commit.hash))
                    .append(",\"s\":").append(jsonString(commit.subject))
                    .append(",\"p\":").append(jsonString("commits/" + commitFiles.get(commitKey(commit))));
            String snapshotRoot = snapshotRoots.get(commit.repository);
            data.append(",\"z\":").append(jsonString(snapshotRoot == null || snapshotRoot.isBlank()
                    ? "" : snapshotRoot + "/tree/" + commit.hash));
            if (commit.quality.status == QualityAssessment.Status.COMPLETE) {
                data.append(",\"q\":").append(commit.quality.score)
                        .append(",\"g\":").append(jsonString(commit.quality.grade))
                        .append(",\"n\":").append(commit.quality.findings.size());
            } else {
                data.append(",\"q\":null,\"g\":null,\"n\":0");
            }
            data.append('}');
        }
        data.append("]}");

        String template = """
                <section class="dashboard-section"><div class="section-title"><div><p class="kicker">INTERAKTÍV GRAFIKONLABOR</p>
                <h2>Fejlesztők, repók, branchek és kódminőség egy nézetben</h2></div><p class="muted">Minden szűrő kombinálható; az adatok a böngészőben, offline kerülnek újraszámításra.</p></div>
                <div class="dashboard-controls">
                  <fieldset><legend>Időszak és felbontás</legend><div class="control-row">
                    <label>From<input id="dashboard-from" type="date"></label><label>To<input id="dashboard-to" type="date"></label>
                    <label>Felbontás<select id="dashboard-granularity"><option value="day">Napi</option><option value="week">Heti</option><option value="month" selected>Havi</option></select></label>
                    <label>Aktivitás típusa<select id="dashboard-style"><option value="bar" selected>Oszlop</option><option value="line">Vonal</option></select></label>
                    <label>Összehasonlítás<select id="dashboard-grouping"><option value="developer" selected>Fejlesztőnként</option><option value="total">Összesítve</option></select></label>
                    <button type="button" id="dashboard-reset">Összes / alaphelyzet</button>
                  </div></fieldset>
                  <div class="filter-grid"><div id="dashboard-developers"></div><div id="dashboard-repositories"></div><div id="dashboard-branches"></div></div>
                  <fieldset><legend>Megjelenített mutatók</legend><div class="metric-options">
                    <label><input type="checkbox" data-metric="additions" checked> Hozzáadott sor</label>
                    <label><input type="checkbox" data-metric="deletions" checked> Törölt sor</label>
                    <label><input type="checkbox" data-metric="commits"> Commit</label>
                    <label><input type="checkbox" data-metric="files"> Fájlérintés</label>
                    <label><input type="checkbox" data-metric="qualityScore" checked> PMD/CPD score</label>
                    <label><input type="checkbox" data-metric="qualityFindings"> PMD/CPD megállapítás</label>
                    <label>Score aggregáció<select id="dashboard-quality-mode"><option value="average" selected>Átlag</option><option value="minimum">Minimum</option><option value="maximum">Maximum</option></select></label>
                    <label><input id="dashboard-quality-labels" type="checkbox" checked> Score és betű a pontokon</label>
                  </div></fieldset>
                </div>
                <div id="dashboard-summary" class="cards six dashboard-summary"></div>
                <p id="dashboard-empty" class="empty-chart" hidden>A kiválasztott szűrőkkel nincs megjeleníthető commit.</p>
                <article class="chart-card"><h3>Szűrt kódaktivitás és commitminőség</h3><p class="chart-range" id="dashboard-range"></p>
                  <div id="dashboard-developer-key" class="developer-key" aria-label="Fejlesztők színjelmagyarázata"></div>
                  <div class="chart-scroll"><div id="portfolio-chart" class="chart-shell dashboard-shell" role="img" aria-label="Szűrhető fejlesztői aktivitás és PMD/CPD pontszám"></div></div>
                  <p class="chart-hint">A színek a fejlesztőket, a jelmagyarázat feliratai a fejlesztőt és a mutatót jelölik. Kattints egy oszlopra vagy pontra az adott fejlesztő időszaki commitjainak megnyitásához.</p>
                </article>
                <section id="dashboard-drilldown" class="drilldown" hidden>
                  <div class="section-title"><div><p class="kicker">DRILL-DOWN</p><h2 id="dashboard-drilldown-title">Kiválasztott időszak</h2></div><button type="button" id="dashboard-drilldown-close">Bezárás</button></div>
                  <p class="muted">Válassz commitot: alatta az előtte/utána kódnézet nyílik meg. A teljes forráskód-pillanatkép helyben, internetkapcsolat nélkül böngészhető.</p>
                  <div id="dashboard-drilldown-list" class="drilldown-list"></div>
                  <div class="drilldown-actions"><a id="dashboard-commit-link" target="_blank" rel="noopener">Commit megnyitása külön lapon</a><a id="dashboard-snapshot-link" target="_blank" rel="noopener">Offline teljes forráskód-snapshot</a><a id="dashboard-github-link" target="_blank" rel="noopener" hidden>GitHub tree megnyitása</a></div>
                  <iframe id="dashboard-commit-frame" class="drilldown-frame" title="A kiválasztott commit kódváltozása"></iframe>
                </section>
                @@QUALITY_GUIDE@@
                <aside class="chart-explanation"><strong>Minőség értelmezése:</strong> a PMD/CPD-vonal csak a <code>--quality</code> kapcsolóval elemzett commitokat tartalmazza. A pontcímke például <code>86/B</code>; a betű az adott időpont aggregált pontjából készül. A branch-szűrés ref-elérhetőséget jelent, ezért ugyanaz a commit több branchhez tartozhat, de a grafikon egyszer számolja.</aside>
                <noscript><p class="empty-chart">Az interaktív szűréshez engedélyezni kell a helyi JavaScript futását.</p></noscript>
                </section>
                <script src="echarts.min.js"></script>
                <script>
                (() => {
                  const payload = @@PORTFOLIO_DATA@@;
                  const raw = payload.commits.map(item => ({date:item.d,developer:payload.developers[item.u],repository:payload.repositories[item.r],branches:item.b.map(id=>payload.branches[id]),additions:item.a,deletions:item.x,files:item.f,qualityScore:item.q,qualityGrade:item.g,qualityFindings:item.n,hash:item.h,subject:item.s,commitPage:item.p,snapshotUrl:item.z})).sort((a,b)=>a.date.localeCompare(b.date));
                  const byId = id => document.getElementById(id);
                  const unique = key => [...new Set(raw.flatMap(item => Array.isArray(item[key]) ? item[key] : [item[key]]))].filter(Boolean).sort((a,b) => a.localeCompare(b, 'hu'));
                  const developerPalette=['#12654f','#1769aa','#be3f52','#8b6f21','#6d43a5','#d16b17','#187b8c','#9b3f82','#4f6f2f','#3d5a80','#a64b2a','#5b5f97'];
                  const allDevelopers=unique('developer');
                  const developerColors=new Map(allDevelopers.map((name,index)=>[name,developerPalette[index%developerPalette.length]]));
                  let chart, currentFiltered = [], currentLabels = [], currentGranularity = 'month', currentSeriesDevelopers = new Map();

                  function makeFilter(id, title, values) {
                    const host = byId(id);
                    host.className = 'filter-picker';
                    const details = document.createElement('details');
                    const summary = document.createElement('summary');
                    summary.textContent = title + ': összes (' + values.length + ')';
                    details.appendChild(summary);
                    const actions = document.createElement('div'); actions.className = 'filter-actions';
                    const all = document.createElement('label');
                    all.innerHTML = '<input type="checkbox" value="*" checked> Összes';
                    actions.appendChild(all); details.appendChild(actions);
                    const list = document.createElement('div'); list.className = 'filter-options';
                    values.forEach(value => {
                      const label = document.createElement('label');
                      const input = document.createElement('input'); input.type = 'checkbox'; input.value = value;
                      label.appendChild(input); label.appendChild(document.createTextNode(' ' + value)); list.appendChild(label);
                    });
                    details.appendChild(list); host.appendChild(details);
                    const allInput = all.querySelector('input');
                    allInput.addEventListener('change', () => {
                      if (allInput.checked) {
                        list.querySelectorAll('input').forEach(input => input.checked = false);
                        summary.textContent = title + ': összes (' + values.length + ')';
                      }
                      update();
                    });
                    list.addEventListener('change', () => {
                      const count = list.querySelectorAll('input:checked').length;
                      allInput.checked = count === 0;
                      summary.textContent = count === 0 ? title + ': összes (' + values.length + ')' : title + ': ' + count + ' kiválasztva';
                      update();
                    });
                  }

                  function selected(id) {
                    const host = byId(id);
                    if (host.querySelector('input[value="*"]').checked) return null;
                    return new Set([...host.querySelectorAll('.filter-options input:checked')].map(input => input.value));
                  }
                  function grade(score) { return score == null ? '—' : score >= 90 ? 'A' : score >= 75 ? 'B' : score >= 60 ? 'C' : score >= 40 ? 'D' : 'E'; }
                  function isoWeek(value) {
                    const date = new Date(value + 'T00:00:00Z');
                    const day = date.getUTCDay() || 7; date.setUTCDate(date.getUTCDate() + 4 - day);
                    const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1));
                    const week = Math.ceil((((date - yearStart) / 86400000) + 1) / 7);
                    return date.getUTCFullYear() + '-W' + String(week).padStart(2, '0');
                  }
                  function bucket(date, granularity) { return granularity === 'day' ? date : granularity === 'week' ? isoWeek(date) : date.slice(0, 7); }
                  function qualityValue(scores, mode) {
                    if (!scores.length) return null;
                    if (mode === 'minimum') return Math.min(...scores);
                    if (mode === 'maximum') return Math.max(...scores);
                    return Math.round(scores.reduce((sum, value) => sum + value, 0) / scores.length * 10) / 10;
                  }
                  function metricEnabled(name) { return document.querySelector('[data-metric="' + name + '"]').checked; }
                  function summaryCard(value, label) { return '<article><strong>' + value + '</strong><span>' + label + '</span></article>'; }
                  function renderDeveloperKey(names, visible) {
                    const host=byId('dashboard-developer-key'); host.hidden=!visible; host.replaceChildren();
                    if(!visible)return;
                    names.forEach(name=>{const item=document.createElement('span'),swatch=document.createElement('i');swatch.style.background=developerColors.get(name);item.append(swatch,document.createTextNode(name));host.appendChild(item);});
                  }

                  function hideDrilldown() {
                    byId('dashboard-drilldown').hidden = true;
                    byId('dashboard-commit-frame').removeAttribute('src');
                  }
                  function selectCommit(item, button) {
                    document.querySelectorAll('.drilldown-commit').forEach(candidate => candidate.classList.toggle('active', candidate === button));
                    byId('dashboard-commit-frame').src = item.commitPage + '#code-before-after';
                    const commitLink = byId('dashboard-commit-link'); commitLink.href = item.commitPage + '#code-before-after';
                    const snapshotLink = byId('dashboard-snapshot-link');
                    snapshotLink.href = 'snapshot.html?repository=' + encodeURIComponent(item.repository) + '&commit=' + encodeURIComponent(item.hash);
                    const githubLink = byId('dashboard-github-link');
                    githubLink.hidden = !item.snapshotUrl;
                    if (item.snapshotUrl) githubLink.href = item.snapshotUrl; else githubLink.removeAttribute('href');
                  }
                  function showDrilldown(label, developer) {
                    const commits = currentFiltered.filter(item => bucket(item.date, currentGranularity) === label)
                      .filter(item => !developer || item.developer === developer)
                      .sort((left,right) => right.date.localeCompare(left.date) || left.repository.localeCompare(right.repository));
                    if (!commits.length) return;
                    const panel = byId('dashboard-drilldown'), list = byId('dashboard-drilldown-list');
                    byId('dashboard-drilldown-title').textContent = label + (developer ? ' · ' + developer : '') + ' · ' + commits.length + ' commit';
                    list.replaceChildren();
                    let firstButton;
                    commits.forEach((item,index) => {
                      const button = document.createElement('button'); button.type = 'button'; button.className = 'drilldown-commit';
                      const title = document.createElement('strong'); title.textContent = item.subject;
                      const meta = document.createElement('span');
                      meta.textContent = item.repository + ' · ' + item.developer + ' · ' + item.hash.slice(0,10)
                        + ' · +' + item.additions + ' /−' + item.deletions
                        + (item.qualityScore == null ? '' : ' · PMD/CPD ' + item.qualityScore + '/' + grade(item.qualityScore));
                      button.append(title, meta); button.addEventListener('click', () => selectCommit(item, button)); list.appendChild(button);
                      if (index === 0) firstButton = button;
                    });
                    panel.hidden = false; selectCommit(commits[0], firstButton); panel.scrollIntoView({behavior:'smooth',block:'start'});
                  }

                  function update() {
                    if (!chart) return;
                    const from = byId('dashboard-from').value, to = byId('dashboard-to').value;
                    const developers = selected('dashboard-developers'), repositories = selected('dashboard-repositories'), branches = selected('dashboard-branches');
                    const filtered = raw.filter(item => (!from || item.date >= from) && (!to || item.date <= to)
                      && (!developers || developers.has(item.developer)) && (!repositories || repositories.has(item.repository))
                      && (!branches || item.branches.some(branch => branches.has(branch))));
                    const granularity = byId('dashboard-granularity').value, qualityMode = byId('dashboard-quality-mode').value;
                    currentFiltered = filtered; currentGranularity = granularity;
                    const aggregate = items => {
                      const groups = new Map();
                      items.forEach(item => {
                        const key = bucket(item.date, granularity);
                        const current = groups.get(key) || {additions:0,deletions:0,commits:0,files:0,findings:0,scores:[]};
                        current.additions += item.additions; current.deletions += item.deletions; current.commits++; current.files += item.files;
                        current.findings += item.qualityFindings; if (item.qualityScore != null) current.scores.push(item.qualityScore); groups.set(key, current);
                      });
                      return groups;
                    };
                    const groups = aggregate(filtered);
                    const labels = [...groups.keys()].sort(), points = labels.map(label => groups.get(label));
                    currentLabels = labels;
                    const activityType = byId('dashboard-style').value;
                    const grouping = byId('dashboard-grouping').value;
                    const series = [];
                    currentSeriesDevelopers = new Map();
                    const activitySeries = (name, values, color, developer, deleted) => ({name,type:activityType,data:values,yAxisIndex:0,
                      stack:activityType === 'bar' ? (developer || 'összes') : undefined,smooth:activityType === 'line' ? .2 : false,
                      itemStyle:{color,opacity:deleted?.55:1},lineStyle:{color,width:deleted?1.5:2,type:deleted?'dashed':'solid'},
                      areaStyle:activityType === 'line' ? {color,opacity:deleted?.03:.08} : undefined,
                      barMaxWidth:34,emphasis:{focus:'series'}});
                    const countSeries = (name, values, color, developer, dashed) => ({name,type:'line',data:values,yAxisIndex:1,smooth:.2,
                      symbolSize:7,itemStyle:{color},lineStyle:{color,width:2,type:dashed?'dashed':'solid'},emphasis:{focus:'series'}});
                    const addQualitySeries = (name, values, color, developer, showLabels) => {
                      const labelStep = Math.max(1, Math.ceil(values.length / 30));
                      series.push({name,type:'line',data:values,yAxisIndex:2,smooth:.2,connectNulls:true,symbolSize:8,
                        itemStyle:{color},lineStyle:{color,width:3},label:{show:showLabels,position:'top',color,fontWeight:700,
                          formatter:params=>params.value != null && params.dataIndex % labelStep === 0 ? params.value+'/'+grade(params.value) : ''},emphasis:{focus:'series'}});
                      if (developer) currentSeriesDevelopers.set(name,developer);
                    };
                    if (grouping === 'developer') {
                      const names=[...new Set(filtered.map(item=>item.developer))].sort((a,b)=>a.localeCompare(b,'hu'));
                      renderDeveloperKey(names,true);
                      names.forEach(developer => {
                        const color=developerColors.get(developer), developerGroups=aggregate(filtered.filter(item=>item.developer===developer));
                        const developerPoints=labels.map(label=>developerGroups.get(label)||{additions:0,deletions:0,commits:0,files:0,findings:0,scores:[]});
                        const add=(metric,name,values,builder)=>{if(!metricEnabled(metric))return;series.push(builder(name,values,color,developer));currentSeriesDevelopers.set(name,developer);};
                        add('additions',developer+' · + sor',developerPoints.map(p=>p.additions),(n,v,c,d)=>activitySeries(n,v,c,d,false));
                        add('deletions',developer+' · − sor',developerPoints.map(p=>p.deletions),(n,v,c,d)=>activitySeries(n,v,c,d,true));
                        add('commits',developer+' · commit',developerPoints.map(p=>p.commits),(n,v,c,d)=>countSeries(n,v,c,d,false));
                        add('files',developer+' · fájl',developerPoints.map(p=>p.files),(n,v,c,d)=>countSeries(n,v,c,d,true));
                        add('qualityFindings',developer+' · PMD/CPD találat',developerPoints.map(p=>p.findings),(n,v,c,d)=>countSeries(n,v,c,d,true));
                        if(metricEnabled('qualityScore')) addQualitySeries(developer+' · PMD/CPD',developerPoints.map(p=>qualityValue(p.scores,qualityMode)),color,developer,byId('dashboard-quality-labels').checked&&names.length<=5);
                      });
                    } else {
                      renderDeveloperKey([],false);
                      const scores = points.map(point => qualityValue(point.scores, qualityMode));
                      if (metricEnabled('additions')) series.push(activitySeries('Összes · + sor',points.map(p=>p.additions),'#12654f',null,false));
                      if (metricEnabled('deletions')) series.push(activitySeries('Összes · − sor',points.map(p=>p.deletions),'#be3f52',null,true));
                      if (metricEnabled('commits')) series.push(countSeries('Összes · commit',points.map(p=>p.commits),'#1769aa'));
                      if (metricEnabled('files')) series.push(countSeries('Összes · fájl',points.map(p=>p.files),'#8b6f21'));
                      if (metricEnabled('qualityFindings')) series.push(countSeries('Összes · PMD/CPD találat',points.map(p=>p.findings),'#d16b17'));
                      if (metricEnabled('qualityScore')) addQualitySeries('Összes · PMD/CPD ('+qualityMode+')',scores,'#6d43a5',null,byId('dashboard-quality-labels').checked);
                    }
                    const showCode = metricEnabled('additions') || metricEnabled('deletions');
                    const showCount = metricEnabled('commits') || metricEnabled('files') || metricEnabled('qualityFindings');
                    const showQuality = metricEnabled('qualityScore');
                    chart.setOption({
                      animationDurationUpdate:300,
                      color:['#12654f','#be3f52','#1769aa','#8b6f21','#d16b17','#6d43a5'],
                      grid:{left:70,right:showCount && showQuality ? 135 : 80,top:55,bottom:105,containLabel:true},
                      legend:{type:'scroll',bottom:48},
                      toolbox:{right:12,feature:{dataZoom:{title:{zoom:'Nagyítás',back:'Vissza'}},restore:{title:'Alaphelyzet'},saveAsImage:{title:'Mentés képként',name:'git-contributor-dashboard'}}},
                      tooltip:{trigger:'axis',axisPointer:{type:activityType === 'bar' ? 'shadow' : 'line'},formatter:params=>{
                        if (!params.length) return '';
                        const quality=params.find(item=>item.seriesName.includes('PMD/CPD') && !item.seriesName.includes('találat'));
                        const rows=[params[0].axisValueLabel,...params.map(item=>item.marker+' '+item.seriesName+': <strong>'+(item.value == null ? '—' : item.value)+'</strong>')];
                        rows.push('PMD/CPD: '+(quality && quality.value != null ? quality.value+'/100 ('+grade(quality.value)+')' : 'nincs minősített commit'));
                        return rows.join('<br>');
                      }},
                      xAxis:{type:'category',name:'Időszak',nameLocation:'middle',nameGap:72,data:labels,boundaryGap:activityType === 'bar',axisLabel:{rotate:labels.length > 12 ? 35 : 0,hideOverlap:true}},
                      yAxis:[
                        {type:'value',name:'Módosított sorok',min:0,show:showCode,position:'left'},
                        {type:'value',name:'Commit / fájl / megállapítás',min:0,show:showCount,position:'right',offset:showQuality ? 65 : 0},
                        {type:'value',name:'PMD/CPD score',min:0,max:100,show:showQuality,position:'right'}
                      ],
                      dataZoom:[{type:'inside',xAxisIndex:0,filterMode:'none'},{type:'slider',xAxisIndex:0,filterMode:'none',show:labels.length > 20,height:18,bottom:10}],
                      series
                    },{notMerge:true});
                    const allScores = filtered.filter(item => item.qualityScore != null).map(item => item.qualityScore);
                    const average = qualityValue(allScores, qualityMode), additions = filtered.reduce((sum,item)=>sum+item.additions,0), deletions = filtered.reduce((sum,item)=>sum+item.deletions,0);
                    byId('dashboard-summary').innerHTML = summaryCard(filtered.length.toLocaleString('hu-HU'),'szűrt commit') + summaryCard((additions+deletions).toLocaleString('hu-HU'),'módosított sor')
                      + summaryCard(allScores.length.toLocaleString('hu-HU'),'minősített commit') + summaryCard(average == null ? '—' : average + '/100','PMD/CPD score')
                      + summaryCard(grade(average),'PMD/CPD kategória') + summaryCard(new Set(filtered.map(item=>item.developer)).size,'fejlesztő');
                    byId('dashboard-range').textContent = (from || 'első commit') + ' – ' + (to || 'utolsó commit') + ' · ' + labels.length + ' időpont · ' + (grouping==='developer'?'fejlesztőnként':'összesítve');
                    byId('dashboard-empty').hidden = filtered.length !== 0;
                    chart.resize();
                    hideDrilldown();
                  }

                  makeFilter('dashboard-developers', 'Fejlesztő', unique('developer'));
                  makeFilter('dashboard-repositories', 'Repó', unique('repository'));
                  makeFilter('dashboard-branches', 'Branch/ref', unique('branches'));
                  if (raw.length) { byId('dashboard-from').value = raw[0].date; byId('dashboard-to').value = raw[raw.length - 1].date; }
                  if (typeof echarts === 'undefined') return;
                  chart = echarts.init(byId('portfolio-chart'));
                  chart.on('click', params => {
                    if (params.componentType === 'series' && params.dataIndex != null && currentLabels[params.dataIndex]) showDrilldown(currentLabels[params.dataIndex],currentSeriesDevelopers.get(params.seriesName));
                  });
                  window.addEventListener('resize', () => chart.resize());
                  document.querySelectorAll('.dashboard-controls input, .dashboard-controls select').forEach(control => control.addEventListener('change', update));
                  byId('dashboard-reset').addEventListener('click', () => {
                    if (raw.length) { byId('dashboard-from').value=raw[0].date; byId('dashboard-to').value=raw[raw.length-1].date; }
                    document.querySelectorAll('.filter-picker input[value="*"]').forEach(input=>input.checked=true);
                    document.querySelectorAll('.filter-options input').forEach(input=>input.checked=false);
                    document.querySelectorAll('.filter-picker summary').forEach((summary,index)=>{const names=['Fejlesztő','Repó','Branch/ref'];const counts=[unique('developer').length,unique('repository').length,unique('branches').length];summary.textContent=names[index]+': összes ('+counts[index]+')';});
                    document.querySelectorAll('[data-metric]').forEach(input=>input.checked=['additions','deletions','qualityScore'].includes(input.dataset.metric));
                    byId('dashboard-quality-labels').checked=true; byId('dashboard-granularity').value='month'; byId('dashboard-style').value='bar'; byId('dashboard-grouping').value='developer'; byId('dashboard-quality-mode').value='average'; update();
                  });
                  byId('dashboard-drilldown-close').addEventListener('click', hideDrilldown);
                  update();
                })();
                </script>
                """;
        return template.replace("@@PORTFOLIO_DATA@@", data.toString())
                .replace("@@QUALITY_GUIDE@@", qualityExplanationHtml());
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
        String activityCharts = activityCharts(d);
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
                %s
                <section><div class="section-title"><div><p class="kicker">REPOSITORY</p><h2>Repónkénti bontás</h2></div></div><div class="table-wrap"><table><thead><tr><th>Repó</th><th>Commit</th><th>Merge</th><th>Aktív nap</th><th>Branch*</th><th>Fájl</th><th>+ / − sor</th><th>Első</th><th>Utolsó</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">IDŐSOR</p><h2>Havi aktivitás</h2></div></div><div class="table-wrap"><table><thead><tr><th>Hónap</th><th>Intenzitás</th><th>Commit</th><th>Aktív nap</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section class="grid"><article class="panel"><h2>Commit-típusok</h2><table><thead><tr><th>Típus</th><th>Darab</th></tr></thead><tbody>%s</tbody></table></article><article class="panel"><h2>Trailer-szerepek</h2><table><thead><tr><th>Szerep</th><th>Darab</th></tr></thead><tbody>%s</tbody></table></article></section>
                <section><div class="section-title"><div><p class="kicker">TECHNOLÓGIAI LÁBNYOM</p><h2>Fájltípusok</h2></div><p class="muted">A fájlérintés commitonként számít; ugyanaz a fájl többször is megjelenhet.</p></div><div class="table-wrap"><table><thead><tr><th>Kiterjesztés</th><th>Fájlérintés</th><th>+ sor</th><th>− sor</th><th>Változtatott sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><div class="section-title"><div><p class="kicker">MUNKARITMUS</p><h2>Aktivitás hét és napszak szerint</h2></div><p class="muted">A commit author-időbélyege alapján, a riportot futtató gép időzónájában.</p></div><div class="table-wrap"><table><thead><tr><th>Dimenzió</th><th>Érték</th><th>Commit</th><th>Arány</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><details><summary><strong>Branch/ref elérhetőség (%s)</strong></summary><ul class="columns">%s</ul><p class="footnote">Egy commit több branchből is elérhető lehet; ez nem a commit eredeti branchét jelenti.</p></details></section>
                <section><div class="section-title"><div><p class="kicker">AUDITÁLHATÓSÁG</p><h2>Commitok</h2></div><p class="muted">Legújabb elöl, minden vizsgált szerzői commit.</p></div><div class="table-wrap"><table><thead><tr><th>Dátum</th><th>Hash</th><th>Repó</th><th>Üzenet</th><th>Típus</th><th>Branch*</th><th>PMD</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                """.formatted(html(d.displayName), period(args), number(d.authoredCommits), number(d.nonMergeCommits) + " / " + number(d.mergeCommits),
                d.participationDays.size(), d.repositories.size(), d.branchRefs.size(), number(d.filesChanged), number(d.additions), number(d.deletions),
                signed(d.additions - d.deletions), commitsPerActiveDay, changePerCommit, number(d.issueLinkedCommits), html(d.displayName), aliases, emails,
                dateTime(d.firstActivity), dateTime(d.lastActivity), span, activityCharts, repositoryRows, monthRows, typeRows, roleRows,
                fileRows, rhythmRows, d.branchRefs.size(), branches, commitRows), "../");
    }

    static String activityCharts(Developer developer) {
        if (developer.daily.isEmpty()) {
            return """
                    <section><div class="section-title"><div><p class="kicker">KÓD AKTIVITÁSI IDŐSOR</p>
                    <h2>Napi, heti és havi kódmódosítás</h2></div></div>
                    <div class="empty-chart">Ehhez az identitáshoz nincs szerzőként rögzített, sorszámmal mérhető commit. A puszta társszerzői szerepet a grafikon nem tulajdonítja automatikusan kódírásnak.</div></section>
                    """;
        }
        ActivitySeries daily = dailySeries(developer.daily);
        ActivitySeries weekly = weeklySeries(developer.daily);
        ActivitySeries monthly = monthlySeries(developer.monthly);
        String cards = chartCard("activity-daily", "Napi bontás", daily)
                + chartCard("activity-weekly", "Heti bontás", weekly)
                + chartCard("activity-monthly", "Havi bontás", monthly);
        return """
                <section class="activity-section"><div class="section-title"><div><p class="kicker">KÓD AKTIVITÁSI IDŐSOR</p>
                <h2>Kódírás és -módosítás időben</h2></div><p class="muted">A jelmagyarázat elemei kapcsolhatók; az oszlop fölé állva részletes értékek jelennek meg.</p></div>
                <aside class="chart-explanation"><strong>Mit mutat?</strong> A zöld rész a hozzáadott, a piros a törölt sorok száma. A teljes oszlopmagasság a kódváltozás volumene, nem minőségi vagy teljesítménypont. A bináris fájlok fájlérintésként látszanak a tooltipben, de nincs sorszámuk.</aside>
                <div class="chart-grid">%s</div>
                <noscript><p class="empty-chart">A grafikonok megjelenítéséhez engedélyezni kell a helyi JavaScript futását. Külső hálózati kapcsolat nem történik.</p></noscript>
                </section>
                <script src="../echarts.min.js"></script>
                <script>
                (() => {
                  const chartSeries = {daily:%s,weekly:%s,monthly:%s};
                  function drawActivityChart(id, series, axisTitle) {
                    const element = document.getElementById(id);
                    if (!element || typeof echarts === 'undefined') return null;
                    const chart = echarts.init(element);
                    chart.setOption({
                      color:['#12654f','#be3f52'],
                      grid:{left:60,right:25,top:48,bottom:92,containLabel:true},
                      legend:{bottom:42},
                      toolbox:{right:8,feature:{dataZoom:{title:{zoom:'Nagyítás',back:'Vissza'}},restore:{title:'Alaphelyzet'},saveAsImage:{title:'Mentés képként',name:id}}},
                      tooltip:{trigger:'axis',axisPointer:{type:'shadow'},formatter:params=>{
                        if (!params.length) return '';
                        const index=params[0].dataIndex;
                        return [params[0].axisValueLabel,...params.map(item=>item.marker+' '+item.seriesName+': <strong>'+item.value+'</strong>'),
                          'Összes módosított sor: <strong>'+(series.additions[index]+series.deletions[index])+'</strong>',
                          'Commit: <strong>'+series.commits[index]+'</strong>','Fájlérintés: <strong>'+series.files[index]+'</strong>'].join('<br>');
                      }},
                      xAxis:{type:'category',name:axisTitle,nameLocation:'middle',nameGap:67,data:series.labels,axisLabel:{rotate:series.labels.length>12?35:0,hideOverlap:true}},
                      yAxis:{type:'value',name:'Módosított sorok',min:0},
                      dataZoom:[{type:'inside',filterMode:'none'},{type:'slider',filterMode:'none',show:series.labels.length>20,height:17,bottom:8}],
                      series:[
                        {name:'Hozzáadott sorok',type:'bar',stack:'code',data:series.additions,barMaxWidth:36,itemStyle:{color:'#12654f'},emphasis:{focus:'series'}},
                        {name:'Törölt sorok',type:'bar',stack:'code',data:series.deletions,barMaxWidth:36,itemStyle:{color:'#be3f52'},emphasis:{focus:'series'}}
                      ]
                    });
                    return chart;
                  }
                  const charts = [drawActivityChart('activity-daily', chartSeries.daily, 'Nap'),
                    drawActivityChart('activity-weekly', chartSeries.weekly, 'ISO-hét'),
                    drawActivityChart('activity-monthly', chartSeries.monthly, 'Hónap')].filter(Boolean);
                  window.addEventListener('resize', () => charts.forEach(chart => chart.resize()));
                })();
                </script>
                """.formatted(cards, activitySeriesJson(daily), activitySeriesJson(weekly), activitySeriesJson(monthly));
    }

    private static String chartCard(String id, String title, ActivitySeries series) {
        return """
                <article class="chart-card"><h3>%s</h3><p class="chart-range">%s</p>
                <div class="chart-scroll"><div id="%s" class="chart-shell" role="img" aria-label="%s: hozzáadott és törölt kódsorok"></div></div></article>
                """.formatted(html(title), html(series.coverage), attr(id), attr(title));
    }

    private static ActivitySeries dailySeries(Map<LocalDate, ContributionStats> source) {
        LocalDate actualStart = source.keySet().stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = source.keySet().stream().max(LocalDate::compareTo).orElseThrow();
        LocalDate limitedStart = end.minusDays(DAILY_CHART_LIMIT - 1L);
        LocalDate start = actualStart.isAfter(limitedStart) ? actualStart : limitedStart;
        List<ActivityPoint> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            points.add(point(day.toString(), source.get(day)));
        }
        return new ActivitySeries(points, coverage(start.toString(), end.toString(), start.isAfter(actualStart),
                DAILY_CHART_LIMIT + " nap"));
    }

    private static ActivitySeries weeklySeries(Map<LocalDate, ContributionStats> daily) {
        Map<LocalDate, ContributionStats> weekly = new TreeMap<>();
        daily.forEach((day, stats) -> {
            LocalDate monday = day.minusDays(day.getDayOfWeek().getValue() - 1L);
            weekly.computeIfAbsent(monday, ignored -> new ContributionStats()).merge(stats);
        });
        LocalDate actualStart = weekly.keySet().stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = weekly.keySet().stream().max(LocalDate::compareTo).orElseThrow();
        LocalDate limitedStart = end.minusWeeks(WEEKLY_CHART_LIMIT - 1L);
        LocalDate start = actualStart.isAfter(limitedStart) ? actualStart : limitedStart;
        WeekFields iso = WeekFields.ISO;
        List<ActivityPoint> points = new ArrayList<>();
        for (LocalDate week = start; !week.isAfter(end); week = week.plusWeeks(1)) {
            String label = week.get(iso.weekBasedYear()) + "-W%02d".formatted(week.get(iso.weekOfWeekBasedYear()));
            points.add(point(label, weekly.get(week)));
        }
        return new ActivitySeries(points, coverage(start.toString(), end.plusDays(6).toString(), start.isAfter(actualStart),
                WEEKLY_CHART_LIMIT + " hét"));
    }

    private static ActivitySeries monthlySeries(Map<YearMonth, ContributionStats> source) {
        YearMonth actualStart = source.keySet().stream().min(YearMonth::compareTo).orElseThrow();
        YearMonth end = source.keySet().stream().max(YearMonth::compareTo).orElseThrow();
        YearMonth limitedStart = end.minusMonths(MONTHLY_CHART_LIMIT - 1L);
        YearMonth start = actualStart.isAfter(limitedStart) ? actualStart : limitedStart;
        List<ActivityPoint> points = new ArrayList<>();
        for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            points.add(point(month.toString(), source.get(month)));
        }
        return new ActivitySeries(points, coverage(start.toString(), end.toString(), start.isAfter(actualStart),
                MONTHLY_CHART_LIMIT + " hónap"));
    }

    private static ActivityPoint point(String label, ContributionStats stats) {
        return stats == null
                ? new ActivityPoint(label, 0, 0, 0, 0)
                : new ActivityPoint(label, stats.additions, stats.deletions, stats.commits, stats.files);
    }

    private static String coverage(String start, String end, boolean limited, String limit) {
        return start + " – " + end + (limited ? " · az utolsó " + limit : " · teljes szerzői időszak");
    }

    private static String activitySeriesJson(ActivitySeries series) {
        return "{\"labels\":" + stringArray(series.points.stream().map(ActivityPoint::label).toList())
                + ",\"additions\":" + longArray(series.points.stream().map(ActivityPoint::additions).toList())
                + ",\"deletions\":" + longArray(series.points.stream().map(ActivityPoint::deletions).toList())
                + ",\"commits\":" + longArray(series.points.stream().map(ActivityPoint::commits).toList())
                + ",\"files\":" + longArray(series.points.stream().map(ActivityPoint::files).toList()) + "}";
    }

    private static String stringArray(List<String> values) {
        return values.stream().map(ReportWriter::jsonString).reduce("[", (left, value) -> left.equals("[")
                ? left + value : left + "," + value) + "]";
    }

    private static Map<String, Integer> indexes(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < values.size(); index++) result.put(values.get(index), index);
        return result;
    }

    private static String integerArray(List<Integer> values) {
        return values.stream().map(String::valueOf).reduce("[", (left, value) -> left.equals("[")
                ? left + value : left + "," + value) + "]";
    }

    private static String longArray(List<Long> values) {
        return values.stream().map(String::valueOf).reduce("[", (left, value) -> left.equals("[")
                ? left + value : left + "," + value) + "]";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026") + "\"";
    }

    private record ActivityPoint(String label, long additions, long deletions, long commits, long files) { }
    private record ActivitySeries(List<ActivityPoint> points, String coverage) { }

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
                .append("</span></td><td><code>").append(html(b.upstream.isBlank() ? "—" : b.upstream))
                .append("</code></td><td><code title=\"").append(attr(b.tipHash)).append("\">")
                .append(html(shortHash(b.tipHash))).append("</code></td><td>").append(dateTime(b.lastCommit))
                .append("</td><td>").append(number(b.reachableCommits)).append("</td><td>").append(number(b.commits))
                .append("</td><td>").append(b.authors.size()).append("</td></tr>"));
        String origin = remoteHtml(r.originUrl);
        return page(r.name + " – " + args.title, """
                <nav><a href="../index.html">← Összesítő</a></nav><header><p class="eyebrow">REPÓ-ADATLAP · RÉSZLETES</p><h1>%s</h1>
                <p class="muted"><code>%s</code></p></header>
                <section class="cards six"><article><strong>%s</strong><span>egyedi commit</span></article><article><strong>%s</strong><span>branch/ref</span></article><article><strong>%s</strong><span>szerző</span></article><article><strong>%s</strong><span>összes közreműködő</span></article><article><strong>%s</strong><span>fájlérintés</span></article><article><strong>%s / %s</strong><span>hozzáadott / törölt sor</span></article></section>
                <section class="grid"><article class="panel"><h2>Forrás</h2><dl><dt>Origin</dt><dd class="wrap">%s</dd><dt>Időszak</dt><dd>%s</dd><dt>Elérhető commit rekord</dt><dd>%s</dd></dl></article><article class="panel"><h2>Aktivitás</h2><dl><dt>Első</dt><dd>%s</dd><dt>Utolsó</dt><dd>%s</dd><dt>Aktív nap</dt><dd>%s</dd><dt>Merge commit</dt><dd>%s</dd></dl></article></section>
                <section><h2>Kód szerzői és társszerzői</h2><div class="table-wrap"><table><thead><tr><th>Név</th><th>Commit</th><th>Merge</th><th>Aktív nap</th><th>Branch*</th><th>Co-author</th><th>Fájl</th><th>+ / − sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><h2>Branch-ek és remote refek</h2><div class="table-wrap"><table><thead><tr><th>Név</th><th>Típus</th><th>Upstream</th><th>Tip</th><th>Utolsó commit</th><th>Teljes commit</th><th>Időszakbeli commit</th><th>Szerző</th></tr></thead><tbody>%s</tbody></table></div><p class="footnote">A commitok branchek között átfedhetnek, ezért a branch-sorok összege nem egyezik szükségszerűen az egyedi commitok számával.</p></section>
                <section><h2>Fájltípusok</h2><div class="table-wrap"><table><thead><tr><th>Kiterjesztés</th><th>Fájlérintés</th><th>+ sor</th><th>− sor</th><th>Változtatott sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section><h2>Commitok</h2><div class="table-wrap"><table><thead><tr><th>Dátum</th><th>Hash</th><th>Repó</th><th>Üzenet</th><th>Típus</th><th>Branch*</th><th>PMD</th><th>Fájl</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                """.formatted(html(r.name), html(r.path.toString()), r.uniqueCommits, r.branches, r.authors.size(),
                unionSize(r.authors, r.roleParticipants), number(r.total.files), number(r.total.additions), number(r.total.deletions),
                origin, period(args), r.reachableCommits, dateTime(r.total.first), dateTime(r.total.last), r.total.activeDays.size(),
                r.total.merges, people, branches, fileRows(r.fileTypes), commitRows(r.commits, r.name, commitFiles)), "../");
    }

    private static String commitPage(CommitSummary c, CliOptions args, String patchText, String originUrl) {
        StringBuilder files = new StringBuilder();
        for (FileChange file : c.fileChanges) files.append("<tr><td><code>").append(html(file.path)).append("</code></td><td>")
                .append(file.binary ? "bináris" : number(file.additions)).append("</td><td>")
                .append(file.binary ? "bináris" : number(file.deletions)).append("</td></tr>");
        if (files.isEmpty()) files.append("<tr><td colspan=\"3\">Nincs fájlváltozás (például üres commit).</td></tr>");
        String patch = patchText.isBlank() ? "<p class=\"muted\">Ehhez a commithoz nincs szöveges diff.</p>" :
                "<details open><summary><strong>Teljes patch megjelenítése</strong></summary><pre class=\"diff\">" + html(patchText) + "</pre></details>";
        String changedCode = changedCodeHtml(c, patchText);
        String commitBranches = c.branches.isEmpty()
                ? "<p class=\"muted\">A commit egyik felismert branch/refből sem érhető el.</p>"
                : c.branches.stream().sorted().map(branch -> "<span class=\"badge\"><code>" + html(branch) + "</code></span>")
                .reduce("", (left, right) -> left + right);
        String githubRoot = githubWebUrl(originUrl);
        String githubSnapshot = githubRoot.isBlank() ? "" : " <a href=\"" + attr(githubRoot + "/tree/" + c.hash)
                + "\" target=\"_blank\" rel=\"noopener\">GitHub tree →</a>";
        String snapshot = "<p><a class=\"action-link\" href=\"../snapshot.html?repository="
                + attr(urlQuery(c.repository)) + "&amp;commit=" + attr(urlQuery(c.hash))
                + "\" target=\"_blank\" rel=\"noopener\">Offline teljes forráskód-snapshot →</a>"
                + githubSnapshot + "</p>";
        return page(c.subject + " – " + args.title, """
                <nav><a href="../index.html">← Összesítő</a></nav><header><p class="eyebrow">COMMIT-ADATLAP · TELJES TARTALOM</p><h1>%s</h1>
                <p class="muted"><code>%s</code> · %s</p></header>
                <section class="cards six"><article><strong>%s</strong><span>repó</span></article><article><strong>%s</strong><span>dátum</span></article><article><strong>%s</strong><span>fájl</span></article><article><strong>%s</strong><span>hozzáadott sor</span></article><article><strong>%s</strong><span>törölt sor</span></article><article><strong>%s</strong><span>típus</span></article></section>
                <section class="grid"><article class="panel"><h2>Identitások</h2><dl><dt>Author</dt><dd>%s &lt;%s&gt;</dd><dt>Committer</dt><dd>%s &lt;%s&gt;</dd></dl></article>
                <article class="panel"><h2>Commit üzenet</h2><pre class="message">%s</pre></article></section>
                <section><div class="section-title"><div><p class="kicker">REF-ELÉRHETŐSÉG</p><h2>Branch-ek, amelyek tartalmazzák</h2></div></div><div class="branch-badges">%s</div><p class="footnote">Ez az aktuális refekből számított elérhetőség, nem feltétlenül a commit eredeti branch-e.</p></section>
                <section><div class="section-title"><div><p class="kicker">PILLANATKÉP</p><h2>A repository teljes forrása ezen a commiton</h2></div></div>%s</section>
                %s
                <section><h2>Érintett fájlok</h2><div class="table-wrap"><table><thead><tr><th>Útvonal</th><th>+ sor</th><th>− sor</th></tr></thead><tbody>%s</tbody></table></div></section>
                <section id="code-before-after"><div class="section-title"><div><p class="kicker">FORRÁSKÓD</p><h2>Kódváltozás előtte / utána</h2></div><p class="muted">Bal oldalon a commit által eltávolított, jobb oldalon a hozzáadott kódrészek.</p></div>%s</section>
                <section><div class="section-title"><div><p class="kicker">KÓDVÁLTOZÁS</p><h2>Teljes diff</h2></div><p class="muted">A Git által előállított patch, 3 sor kontextussal.</p></div>%s</section>
                """.formatted(html(c.subject), html(c.hash), html(c.repository), html(c.repository), dateTime(c.date), c.files,
                number(c.additions), number(c.deletions), c.merge ? "merge" : commitType(c.subject), html(c.authorName),
                html(c.authorEmail), html(c.committerName), html(c.committerEmail), html(codeFocusedMessage(c.message)),
                commitBranches, snapshot, qualityHtml(c.quality), files, changedCode, patch), "../");
    }

    private static String qualityHtml(QualityAssessment quality) {
        if (quality.status == QualityAssessment.Status.NOT_RUN) {
            return "<section class=\"quality quality-muted\"><div class=\"section-title\"><div><p class=\"kicker\">KÓDMINŐSÉG</p><h2>Commitminősítés</h2></div></div>"
                    + "<p class=\"muted\">Nem futott minőségelemzés. Indítás: <code>--quality</code>.</p></section>"
                    + qualityExplanationHtml();
        }
        if (quality.status != QualityAssessment.Status.COMPLETE) {
            String label = quality.status == QualityAssessment.Status.FAILED ? "Az elemzés hibával zárult" : "Nincs elemezhető támogatott forráskód";
            return "<section class=\"quality quality-muted\"><div class=\"section-title\"><div><p class=\"kicker\">KÓDMINŐSÉG</p><h2>"
                    + label + "</h2></div></div><p class=\"muted\">" + html(quality.summary) + "</p></section>"
                    + qualityExplanationHtml();
        }
        String tone = quality.score >= 90 ? "good" : quality.score >= 60 ? "warn" : "bad";
        StringBuilder findings = new StringBuilder();
        for (QualityFinding finding : quality.findings) {
            String rule = finding.externalUrl == null || finding.externalUrl.isBlank()
                    ? "<code>" + html(finding.rule) + "</code>"
                    : "<a href=\"" + html(finding.externalUrl) + "\"><code>" + html(finding.rule) + "</code></a>";
            findings.append("<tr><td>").append(html(finding.language)).append("</td><td>")
                    .append(html(finding.analyzer)).append("</td><td>").append(severity(finding.priority)).append("</td><td>")
                    .append(rule).append("</td><td><code>").append(html(finding.path)).append(':').append(finding.line)
                    .append("</code></td><td>").append(html(finding.message)).append("</td></tr>");
        }
        String coverage = quality.analyzedLanguages.entrySet().stream()
                .map(entry -> "<span>" + html(entry.getKey()) + ": <strong>" + entry.getValue() + "</strong></span>")
                .reduce("", String::concat);
        String notes = quality.notes.isEmpty() ? "" : "<div class=\"quality-notes\"><strong>Elemzési megjegyzések:</strong><ul>"
                + quality.notes.stream().map(note -> "<li>" + html(note) + "</li>").reduce("", String::concat) + "</ul></div>";
        String details = findings.isEmpty()
                ? "<p class=\"quality-clean\">Nem találtunk szabálysértést a hozzáadott sorokon.</p>"
                : "<div class=\"table-wrap\"><table><thead><tr><th>Nyelv</th><th>Elemző</th><th>Súlyosság</th><th>Szabály</th><th>Hely</th><th>Megállapítás</th></tr></thead><tbody>"
                + findings + "</tbody></table></div>";
        return "<section class=\"quality\"><div class=\"quality-head\"><div class=\"quality-score " + tone + "\"><strong>"
                + quality.score + "</strong><span>/ 100 · " + html(quality.grade) + "</span></div><div><p class=\"kicker\">PMD + CPD · HOZZÁADOTT FORRÁSKÓD</p><h2>Commitminősítés</h2><p>"
                + html(quality.summary) + "</p></div></div><div class=\"quality-meta\"><span>Elemzett fájl: <strong>"
                + quality.analyzedFiles + "</strong></span><span>Elemzett új sor: <strong>" + quality.analyzedAddedLines
                + "</strong></span><span>Megállapítás: <strong>" + quality.findings.size()
                + "</strong></span>" + coverage + "</div>" + details + notes
                + "<p class=\"footnote\">A pontszám a PMD prioritásaiból és a megállapítások új sorokra vetített sűrűségéből készül. Jelzés, nem emberi teljesítményértékelés.</p></section>"
                + qualityExplanationHtml();
    }

    private static String qualityExplanationHtml() {
        return """
                <details class="quality-guide"><summary><strong>Mit jelent a PMD, a CPD és a pontszám?</strong></summary>
                  <div class="quality-guide-grid">
                    <article><h3>PMD – szabályalapú kódelemzés</h3><p>A forráskódot ismert hibaminták, biztonsági, teljesítmény-, tervezési és karbantarthatósági szabályok alapján vizsgálja. Egy PMD-találat lehetséges probléma, amelyet embernek kell értelmeznie.</p></article>
                    <article><h3>CPD – másolt kód keresése</h3><p>A Copy/Paste Detector tokenek alapján ismétlődő kódrészeket keres. A riport legalább 50 tokenes egyezéseket vizsgál; az ismétlés lehet indokolt is, ezért ez sem automatikus hibítélet.</p></article>
                    <article><h3>Mit elemez a riport?</h3><p>A commit utáni teljes fájlt elemzi, de csak a commitban <strong>hozzáadott sorokra</strong> eső PMD/CPD-találatokat rendeli a fejlesztőhöz. Támogatott: Java, JavaScript, TypeScript, HTML, SQL és PL/SQL.</p></article>
                    <article><h3>Hogyan készül a 0–100 pont?</h3><p>A találatok súlya prioritásonként 25, 14, 8, 4 vagy 2 pont. A levonás ezt az elemzett új sorok számához viszonyítja, legalább 50 soros nevezővel. Kategóriák: A ≥90, B ≥75, C ≥60, D ≥40, E &lt;40.</p></article>
                  </div>
                  <p class="footnote"><strong>Fontos:</strong> a magas pontszám nem bizonyítja, hogy a kód helyes vagy jó, az alacsony pontszám pedig nem bizonyít gyenge fejlesztői teljesítményt. A tesztek, az üzleti helyesség, az architektúra, a feladat nehézsége és a csapatmunka külön értékelendő.</p>
                </details>
                """;
    }

    private static String severity(int priority) {
        return switch (priority) {
            case 1 -> "kritikus";
            case 2 -> "magas";
            case 3 -> "közepes";
            case 4 -> "alacsony";
            default -> "információ";
        };
    }

    private static String changedCodeHtml(CommitSummary commit, String patch) {
        List<ChangedCode> changes = changedCode(commit, patch);
        if (changes.stream().allMatch(c -> c.added.isBlank() && c.deleted.isBlank()))
            return "<p class=\"muted\">Nincs szöveges kódmódosítás.</p>";
        StringBuilder out = new StringBuilder();
        for (ChangedCode change : changes) {
            out.append("<article class=\"code-change\"><h3><code>").append(html(change.path)).append("</code></h3>");
            out.append("<div class=\"before-after\"><section class=\"code-side before\"><h4 class=\"deleted-label\">Előtte · törölt kód</h4>");
            if (change.deleted.isBlank()) out.append("<p class=\"muted\">Nincs eltávolított kódrész.</p>");
            else out.append("<pre class=\"source deleted\"><code>").append(html(change.deleted)).append("</code></pre>");
            out.append("</section><section class=\"code-side after\"><h4 class=\"added-label\">Utána · hozzáadott kód</h4>");
            if (change.added.isBlank()) out.append("<p class=\"muted\">Nincs hozzáadott kódrész.</p>");
            else out.append("<pre class=\"source added\"><code>").append(html(change.added)).append("</code></pre>");
            out.append("</section></div></article>");
        }
        return out.toString();
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
                .append(html(c.subject)).append("</td><td>").append(c.merge ? "merge" : commitType(c.subject)).append("</td><td title=\"")
                .append(attr(String.join(", ", c.branches.stream().sorted().toList()))).append("\">").append(c.branches.size()).append("</td><td>")
                .append(qualityCompact(c.quality)).append("</td><td>")
                .append(c.files).append("</td><td>").append(number(c.additions)).append("</td><td>").append(number(c.deletions)).append("</td></tr>"));
        return rows.isEmpty() ? "<tr><td colspan=\"10\">Nincs szerzői commit.</td></tr>" : rows.toString();
    }

    private static String qualityCompact(QualityAssessment quality) {
        return switch (quality.status) {
            case COMPLETE -> "<span class=\"badge\">" + quality.score + "/" + html(quality.grade) + "</span>";
            case NOT_APPLICABLE -> "n/a";
            case FAILED -> "hiba";
            case NOT_RUN -> "—";
        };
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

    static String attr(String value) { return html(value); }
    private static String urlQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
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
                dl{display:grid;grid-template-columns:minmax(110px,1fr) 2fr;gap:8px 16px;margin:0}dt{color:var(--muted)}dd{margin:0;text-align:right;font-weight:650;overflow-wrap:anywhere}.wrap{white-space:normal}.badge{display:inline-block;padding:2px 8px;border-radius:999px;background:var(--accent2);font-size:11px}.branch-badges{display:flex;flex-wrap:wrap;gap:8px}.branch-badges .badge{padding:6px 10px}.meter{display:block;width:120px;height:8px;background:#e4e9e6;border-radius:9px;overflow:hidden}.meter i{display:block;height:100%;background:var(--accent)}
                details{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}summary{cursor:pointer}.columns{columns:3;column-width:260px}.message,.diff,.source{white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0 0;font:13px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace}.diff{max-height:75vh;overflow:auto;background:#101713;color:#d9e7df;border-radius:9px;padding:16px;white-space:pre}.message{background:#f5f7f5;border-radius:8px;padding:12px}.code-change{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px;margin:12px 0}.code-change h3{margin:0 0 14px}.code-change h4{margin:0 0 6px}.before-after{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px}.code-side{min-width:0;padding:12px;border-radius:9px}.code-side.before{background:#fff8f8}.code-side.after{background:#f5fbf7}.added-label{color:#12633f}.deleted-label{color:#9b3131}.source{padding:14px;border-radius:8px;overflow:auto;white-space:pre}.source.added{background:#e9f7ef;border-left:4px solid #25834f}.source.deleted{background:#fbecec;border-left:4px solid #bb4545}.action-link{display:inline-block;background:#1769aa;color:white;padding:9px 13px;border-radius:8px;font-weight:700}footer{max-width:1440px;margin:auto;padding:0 28px 35px;color:var(--muted);font-size:13px}
                .quality{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px}.quality-muted{border-style:dashed}.quality-head{display:flex;gap:20px;align-items:center}.quality-head h2,.quality-head p{margin:0}.quality-score{flex:0 0 112px;height:112px;border-radius:50%;display:grid;place-content:center;text-align:center;border:8px solid #2a875c;background:#edf8f1}.quality-score.warn{border-color:#c58a18;background:#fff8e8}.quality-score.bad{border-color:#b94949;background:#fff0f0}.quality-score strong{display:block;font-size:34px;line-height:1}.quality-score span{font-size:12px;color:var(--muted)}.quality-meta{display:flex;flex-wrap:wrap;gap:10px 24px;margin:18px 0}.quality-clean{padding:14px 17px;border-radius:10px;background:#e9f7ef;color:#12633f;font-weight:650}
                .chart-grid{display:grid;gap:18px}.chart-card{min-width:0;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px;box-shadow:0 2px 12px #18382b0a}.chart-card h3{margin:0}.chart-range{margin:2px 0 12px;color:var(--muted);font-size:13px}.chart-scroll{overflow-x:auto;overflow-y:hidden;padding:4px 0 8px}.chart-shell{height:330px;max-width:none}.chart-explanation{margin-bottom:18px}.empty-chart{background:var(--card);border:1px dashed var(--line);border-radius:12px;padding:22px;color:var(--muted)}
                .dashboard-controls{display:grid;gap:14px;margin:18px 0}.dashboard-controls fieldset{border:1px solid var(--line);border-radius:12px;padding:12px 14px;background:var(--card)}.dashboard-controls legend{font-weight:750;padding:0 7px}.control-row,.metric-options{display:flex;flex-wrap:wrap;gap:12px 18px;align-items:end}.control-row label,.metric-options label{display:grid;gap:5px;font-size:13px;font-weight:650}.metric-options label:has(input[type=checkbox]){display:flex;align-items:center;gap:6px}.dashboard-controls input[type=date],.dashboard-controls select,.dashboard-controls button{font:inherit;border:1px solid var(--line);border-radius:8px;background:white;color:var(--ink);padding:8px 10px}.dashboard-controls button{cursor:pointer;font-weight:700;background:#eaf4ef}.filter-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.filter-picker details{position:relative;border:1px solid var(--line);border-radius:10px;background:var(--card)}.filter-picker summary{cursor:pointer;padding:11px 13px;font-weight:700}.filter-actions{border-top:1px solid var(--line);padding:8px 12px;background:#f4f8f6}.filter-options{max-height:210px;overflow:auto;padding:7px 12px}.filter-options label,.filter-actions label{display:block;padding:4px 0;font-size:13px}.dashboard-summary{margin:16px 0}.dashboard-shell{height:420px}.developer-key{display:flex;flex-wrap:wrap;gap:7px 14px;margin:5px 0 8px;padding:9px 11px;border:1px solid var(--line);border-radius:9px;background:#f7faf8}.developer-key span{display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:750}.developer-key i{width:11px;height:11px;border-radius:50%;box-shadow:0 0 0 1px #fff,0 0 0 2px #728177}.chart-hint{margin:10px 0 0;color:#1769aa;font-weight:700}.quality-notes{margin-top:15px;padding:12px 15px;background:#fff8e8;border-radius:10px}.quality-notes ul{margin-bottom:0}.quality-guide{margin:18px 0}.quality-guide-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-top:16px}.quality-guide-grid article{background:#f5f8f6;border-radius:10px;padding:14px}.quality-guide-grid h3{margin:0 0 7px}.quality-guide-grid p{margin:0}.drilldown{scroll-margin-top:18px}.drilldown .section-title button{font:inherit;border:1px solid var(--line);border-radius:8px;background:#eef5f1;padding:8px 11px;cursor:pointer}.drilldown-list{display:flex;gap:9px;overflow:auto;padding:3px 0 12px}.drilldown-commit{flex:0 0 300px;text-align:left;border:1px solid var(--line);border-radius:10px;background:var(--card);padding:11px;cursor:pointer;color:var(--ink)}.drilldown-commit strong,.drilldown-commit span{display:block}.drilldown-commit span{margin-top:5px;color:var(--muted);font-size:12px}.drilldown-commit.active{border-color:#1769aa;box-shadow:0 0 0 2px #1769aa22;background:#f5f9fd}.drilldown-actions{display:flex;align-items:center;gap:10px 18px;flex-wrap:wrap;margin:5px 0 12px}.drilldown-actions a{font-weight:700}.drilldown-frame{display:block;width:100%;height:72vh;min-height:560px;border:1px solid var(--line);border-radius:12px;background:white}
                .snapshot-browser{display:grid;grid-template-columns:minmax(260px,360px) minmax(0,1fr);gap:16px;align-items:start}.snapshot-files,.snapshot-preview{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px}.snapshot-files{position:sticky;top:56px}.snapshot-files label{display:grid;gap:6px;font-weight:700}.snapshot-files input{width:100%;font:inherit;border:1px solid var(--line);border-radius:8px;padding:9px 10px}.snapshot-files #snapshot-list{max-height:68vh;overflow:auto;border-top:1px solid var(--line)}.snapshot-file{display:block;width:100%;border:0;border-bottom:1px solid var(--line);background:transparent;text-align:left;padding:9px 7px;cursor:pointer;color:var(--ink);overflow-wrap:anywhere}.snapshot-file:hover,.snapshot-file.active{background:#eaf4ef;color:var(--accent)}.snapshot-preview h2{font-size:18px;overflow-wrap:anywhere}.snapshot-preview iframe{display:block;width:100%;height:72vh;min-height:560px;border:1px solid var(--line);border-radius:10px;background:white}.snapshot-blob{max-width:none;padding:22px}.snapshot-source{white-space:pre;overflow:auto;max-height:none}
                @media(max-width:720px){main{padding:28px 14px}.section-title{align-items:start;flex-direction:column}.columns{columns:1}th,td{padding:9px 10px}.cards strong{font-size:22px}.quality-head{align-items:flex-start;flex-direction:column}.quality-score{height:96px;width:96px;flex-basis:96px}.filter-grid,.quality-guide-grid,.before-after,.snapshot-browser{grid-template-columns:1fr}.control-row,.metric-options{align-items:stretch;flex-direction:column}.drilldown-frame{min-height:460px}.drilldown-commit{flex-basis:260px}.snapshot-files{position:static}.snapshot-preview iframe{min-height:460px}}
                @media print{body{background:white}main{max-width:none;padding:15px}.cards article,.panel,.method,.chart-card{box-shadow:none}nav{display:none}section{break-inside:avoid}.table-wrap,.chart-scroll{overflow:visible}.chart-shell{width:100%!important}th{position:static}}
                """;
    }
}
