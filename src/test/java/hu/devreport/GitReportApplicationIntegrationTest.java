package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitReportApplicationIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoreRejectsAMissingDatabaseWithoutCreatingIt() {
        Path missing = temporaryDirectory.resolve("missing.sqlite");
        assertThrows(IllegalArgumentException.class, () -> GitReportApplication.run(CliOptions.parse(new String[]{
                "--render-db", missing.toString(), "--output", temporaryDirectory.resolve("html").toString()
        })));
        assertFalse(Files.exists(missing));
    }

    @Test
    void generatesHtmlAndPortableSqliteArchiveFromARealGitRepository() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        Path repository = createRepository(root.resolve("sample-project"));
        Path output = temporaryDirectory.resolve("report");
        List<ProgressUpdate> progress = new ArrayList<>();

        CliOptions options = CliOptions.parse(new String[]{
                "--root", root.toString(),
                "--output", output.toString(),
                "--title", "Integrációs tesztriport"
        });
        GitReportApplication.run(options, progress::add);

        assertTrue(Files.isRegularFile(output.resolve("index.html")));
        assertTrue(Files.isRegularFile(output.resolve("dashboard.html")));
        assertTrue(Files.isRegularFile(output.resolve("snapshot.html")));
        assertTrue(Files.isRegularFile(output.resolve("snapshot-data.js")));
        assertTrue(Files.isDirectory(output.resolve("snapshots/blobs")));
        assertTrue(Files.isRegularFile(output.resolve("report.sqlite")));
        assertTrue(Files.isRegularFile(output.resolve("style.css")));
        assertTrue(Files.size(output.resolve("echarts.min.js")) > 500_000);
        assertFalse(Files.exists(output.resolve("index.md")));
        assertFalse(Files.exists(output.resolve("source-code-index.md")));
        assertTrue(Files.isDirectory(output.resolve("developers")));
        assertTrue(Files.isDirectory(output.resolve("repositories")));
        assertTrue(Files.isDirectory(output.resolve("commits")));
        assertFalse(Files.exists(output.resolve(".patch-cache")));

        String htmlIndex = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        String dashboard = Files.readString(output.resolve("dashboard.html"), StandardCharsets.UTF_8);
        String snapshotData = Files.readString(output.resolve("snapshot-data.js"), StandardCharsets.UTF_8);
        String developerHtmlPages = readAll(output.resolve("developers"), ".html");

        assertTrue(htmlIndex.contains("Integrációs tesztriport"));
        assertTrue(htmlIndex.contains("Teszt Elek"));
        assertTrue(htmlIndex.contains("M&aacute;sik Fejlesztő"));
        assertFalse(htmlIndex.contains("<code>origin</code>"));
        assertTrue(htmlIndex.contains("https://github.com/example/sample-project"));
        assertTrue(htmlIndex.contains("Minden repó minden branch-e"));
        assertTrue(htmlIndex.contains("feature/reporting"));
        assertTrue(htmlIndex.contains("Git/GitHub repók egyben"));
        assertTrue(htmlIndex.contains("href=\"dashboard.html\""));
        assertFalse(htmlIndex.contains("id=\"portfolio-chart\""));
        assertTrue(dashboard.contains("INTERAKTÍV GRAFIKONLABOR"));
        assertTrue(dashboard.contains("dashboard-from"));
        assertTrue(dashboard.contains("dashboard-developers"));
        assertTrue(dashboard.contains("dashboard-grouping"));
        assertTrue(dashboard.contains("Fejlesztőnként"));
        assertTrue(dashboard.contains("currentSeriesDevelopers"));
        assertTrue(dashboard.contains("dashboard-repositories"));
        assertTrue(dashboard.contains("dashboard-branches"));
        assertTrue(dashboard.contains("PMD/CPD score"));
        assertTrue(dashboard.contains("dashboard-drilldown"));
        assertTrue(dashboard.contains("Mit jelent a PMD, a CPD"));
        assertTrue(dashboard.contains("snapshot.html?repository="));
        assertTrue(dashboard.contains("https://github.com/example/sample-project/tree/"));
        assertTrue(snapshotData.contains("sample-project"));
        assertTrue(snapshotData.contains("src/App.java"));
        try (var blobs = Files.list(output.resolve("snapshots/blobs"))) {
            assertTrue(blobs.anyMatch(path -> path.getFileName().toString().endsWith(".html")));
        }
        assertTrue(developerHtmlPages.contains("Kódírás és -módosítás időben"));
        assertTrue(developerHtmlPages.contains("activity-daily"));
        assertTrue(developerHtmlPages.contains("activity-weekly"));
        assertTrue(developerHtmlPages.contains("activity-monthly"));
        assertTrue(developerHtmlPages.contains("Hozzáadott sorok"));
        assertTrue(developerHtmlPages.contains("Törölt sorok"));
        assertTrue(developerHtmlPages.contains("echarts.min.js"));
        assertTrue(developerHtmlPages.contains("echarts.init"));
        assertTrue(readAll(output.resolve("commits"), ".html").contains("Kódváltozás előtte / utána"));
        assertTrue(readAll(output.resolve("commits"), ".html").contains("Offline teljes forráskód-snapshot"));
        assertEquals(0, progress.getFirst().percent());
        assertEquals(100, progress.getLast().percent());
        assertEquals("Befejezés", progress.getLast().phase());
        assertTrue(progress.stream().anyMatch(update -> update.detail().contains("Aktív szál:")));

        assertTrue(Files.isRegularFile(repository.resolve("src/App.java")));
        Path restored = temporaryDirectory.resolve("restored-html");
        GitReportApplication.run(CliOptions.parse(new String[]{
                "--render-db", output.resolve("report.sqlite").toString(), "--output", restored.toString()
        }));
        assertEquals(htmlIndex, Files.readString(restored.resolve("index.html"), StandardCharsets.UTF_8));
        assertEquals(dashboard, Files.readString(restored.resolve("dashboard.html"), StandardCharsets.UTF_8));
    }

    @Test
    void qualityModeScoresAddedJavaLinesWithoutPublishingPatches() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("quality-repositories"));
        Path repository = root.resolve("quality-project");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "Quality Tester");
        git(repository, "config", "user.email", "quality@test.invalid");
        Path source = repository.resolve("src/Problematic.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class Problematic {
                    void run() {
                        try {
                            System.out.println("teszt");
                        } catch (Exception exception) {
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("script.js"), "function isOne(value) { return value == 1; }\n", StandardCharsets.UTF_8);
        String duplicatedTypeScript = """
                export function calculate(input: number): number {
                    let result = input;
                    result = result + 1;
                    result = result * 2;
                    result = result - 3;
                    result = result + 4;
                    result = result * 5;
                    result = result - 6;
                    result = result + 7;
                    result = result * 8;
                    result = result - 9;
                    return result;
                }
                """;
        Files.writeString(repository.resolve("view.ts"), duplicatedTypeScript, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("copy.ts"), duplicatedTypeScript, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("page.html"), "<!doctype html><html><body><img src=\"test.png\"></body></html>\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("query.sql"), "BEGIN\n  NULL;\nEND;\n/\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("package.pkb"),
                "BEGIN\n  NULL;\nEXCEPTION\n  WHEN OTHERS THEN\n    NULL;\nEND;\n/\n", StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "feat: minőségteszt");

        Path output = temporaryDirectory.resolve("quality-report");
        CliOptions options = CliOptions.parse(new String[]{
                "--root", root.toString(), "--output", output.toString(),
                "--quality", "--no-patches"
        });
        GitReportApplication.run(options);

        String html = readAll(output.resolve("commits"), ".html");
        String qualityDashboard = Files.readString(output.resolve("dashboard.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("Commitminősítés"));
        assertTrue(html.contains("/ 100"));
        assertTrue(html.contains("EmptyCatchBlock"));
        assertTrue(html.contains("JavaScript"));
        assertTrue(html.contains("EqualComparison"));
        assertTrue(html.contains("TypeScript"));
        assertTrue(html.contains("CopyPasteDetector"));
        assertTrue(html.contains("HTML"));
        assertTrue(html.contains("UseAltAttributeForImages"));
        assertTrue(html.contains("SQL"));
        assertTrue(html.contains("PL/SQL"));
        assertTrue(html.contains("TomKytesDespair"));
        assertTrue(qualityDashboard.contains("dashboard-quality-labels"));
        assertTrue(qualityDashboard.contains("dataZoom"));
        assertTrue(qualityDashboard.matches("(?s).*\\\"q\\\":(?:[0-9]|[1-9][0-9]|100)(?:,|}).*"));
        assertFalse(html.contains("diff --git"));
        assertTrue(Files.isRegularFile(output.resolve("report.sqlite")));
        assertFalse(Files.exists(output.resolve(".patch-cache")));
    }

    @Test
    void parallelRepositoryAnalysisKeepsCrossRepositoryIdentityMergingAndSnapshots() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("parallel-repositories"));
        createMinimalRepository(root.resolve("alpha"), "Árvíz  Tűrő", "alpha@test.invalid", "class Alpha {}\n");
        createMinimalRepository(root.resolve("beta"), "ARVIZTURO", "beta@test.invalid", "class Beta {}\n");
        Path output = temporaryDirectory.resolve("parallel-report");

        GitReportApplication.run(CliOptions.parse(new String[]{
                "--root", root.toString(), "--output", output.toString()
        }));

        try (var developerFiles = Files.list(output.resolve("developers"))) {
            assertEquals(1, developerFiles.filter(path -> path.getFileName().toString().endsWith(".html")).count());
        }
        String developerHtml = readAll(output.resolve("developers"), ".html");
        assertTrue(developerHtml.contains("Árvíz Tűrő"));
        assertTrue(developerHtml.contains("ARVIZTURO"));
        String snapshotData = Files.readString(output.resolve("snapshot-data.js"), StandardCharsets.UTF_8);
        assertTrue(snapshotData.contains("alpha"));
        assertTrue(snapshotData.contains("beta"));
    }

    private static void createMinimalRepository(Path repository, String name, String email, String source)
            throws Exception {
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", name);
        git(repository, "config", "user.email", email);
        Files.writeString(repository.resolve("Example.java"), source, StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "feat: parallel fixture");
    }

    private Path createRepository(Path repository) throws Exception {
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "Teszt Elek");
        git(repository, "config", "user.email", "teszt.elek@test.invalid");
        git(repository, "remote", "add", "origin", "git@github.com:example/sample-project.git");

        Path source = repository.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Path binary = repository.resolve("assets/logo.bin");
        Files.createDirectories(binary.getParent());
        Files.write(binary, new byte[]{0, 1, 2, 3});
        Files.writeString(source,
                "class App {\n    static String value() { return \"első\"; }\n}\n",
                StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "chore: kezdeti változat");

        Files.writeString(source,
                "class App {\n    static String value() { return \"második\"; }\n}\n",
                StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "feat(core): visszatérési érték módosítása", "-m",
                "Co-authored-by: Másik Fejlesztő <masik@test.invalid>");
        git(repository, "branch", "feature/reporting");
        git(repository, "update-ref", "refs/remotes/origin/main", "HEAD");
        git(repository, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");

        Files.writeString(source,
                "class App {\n    static String value() { return \"NEM_COMMITOLT_TARTALOM\"; }\n}\n",
                StandardCharsets.UTF_8);
        return repository;
    }

    private static void git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        CommandResult result = GitReportApplication.execute(repository, command);
        assertEquals(0, result.exitCode, () -> String.join(" ", command) + "\n" + result.stderr);
    }

    private static String readAll(Path directory, String extension) throws Exception {
        StringBuilder result = new StringBuilder();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(extension)).sorted().toList()) {
                result.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return result.toString();
    }
}
