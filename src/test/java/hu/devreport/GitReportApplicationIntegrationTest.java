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
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitReportApplicationIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesHtmlMarkdownAndSourceFromARealGitRepository() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        Path repository = createRepository(root.resolve("sample-project"));
        Path output = temporaryDirectory.resolve("report");
        List<ProgressUpdate> progress = new ArrayList<>();

        CliOptions options = CliOptions.parse(new String[]{
                "--root", root.toString(),
                "--output", output.toString(),
                "--title", "Integrációs tesztriport",
                "--outputs", "html,markdown,source",
                "--source-branches", "main"
        });
        GitReportApplication.run(options, progress::add);

        assertTrue(Files.isRegularFile(output.resolve("index.html")));
        assertTrue(Files.isRegularFile(output.resolve("index.md")));
        assertTrue(Files.isRegularFile(output.resolve("style.css")));
        assertTrue(Files.isRegularFile(output.resolve("source-code-index.md")));
        assertTrue(Files.isRegularFile(output.resolve("source-code-main.md")));
        assertTrue(Files.isDirectory(output.resolve("developers")));
        assertTrue(Files.isDirectory(output.resolve("repositories")));
        assertTrue(Files.isDirectory(output.resolve("commits")));
        assertFalse(Files.exists(output.resolve(".patch-cache")));

        String index = Files.readString(output.resolve("index.md"), StandardCharsets.UTF_8);
        String source = Files.readString(output.resolve("source-code-main.md"), StandardCharsets.UTF_8);
        String developerPages = readAllMarkdown(output.resolve("developers"));
        String commitPages = readAllMarkdown(output.resolve("commits"));

        assertTrue(index.contains("Integrációs tesztriport"));
        assertTrue(index.contains("Teszt Elek"));
        assertTrue(index.contains("Másik Fejlesztő"));
        assertTrue(developerPages.contains("feat(core): visszatérési érték módosítása"));
        assertTrue(developerPages.contains("return \"második\";"));
        assertTrue(commitPages.contains("src/App.java"));
        assertTrue(commitPages.contains("+    static String value() { return \"második\"; }"));
        assertTrue(source.contains("### FÁJL: `src/App.java`"));
        assertTrue(source.contains("return \"második\";"));
        assertTrue(source.contains("### FÁJL: `assets/logo.bin`"));
        assertTrue(source.contains("Bináris fájl"));
        assertFalse(source.contains("NEM_COMMITOLT_TARTALOM"));
        assertEquals(0, progress.getFirst().percent());
        assertEquals(100, progress.getLast().percent());
        assertEquals("Befejezés", progress.getLast().phase());

        assertTrue(Files.isRegularFile(repository.resolve("src/App.java")));
    }

    @Test
    void sourceOnlySkipsDeveloperReportsAndRemovesStaleSourceExports() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("source-repositories"));
        createRepository(root.resolve("sample-project"));
        Path output = Files.createDirectory(temporaryDirectory.resolve("source-report"));
        Files.writeString(output.resolve("source-code-obsolete.md"), "régi export", StandardCharsets.UTF_8);

        CliOptions options = CliOptions.parse(new String[]{
                "--root", root.toString(),
                "--output", output.toString(),
                "--source-only",
                "--source-ref", "main"
        });
        GitReportApplication.run(options);

        assertTrue(Files.isRegularFile(output.resolve("source-code-main.md")));
        assertTrue(Files.isRegularFile(output.resolve("source-code-index.md")));
        assertFalse(Files.exists(output.resolve("source-code-obsolete.md")));
        assertFalse(Files.exists(output.resolve("index.html")));
        assertFalse(Files.exists(output.resolve("index.md")));
        assertFalse(Files.exists(output.resolve("developers")));
    }

    @Test
    void localBranchSelectionExportsEveryLocalBranchWithSafeNames() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("branch-repositories"));
        createRepository(root.resolve("sample-project"));
        Path output = temporaryDirectory.resolve("branch-report");

        CliOptions options = CliOptions.parse(new String[]{
                "--root", root.toString(),
                "--output", output.toString(),
                "--source-only",
                "--source-branches", "local"
        });
        GitReportApplication.run(options);

        assertTrue(Files.isRegularFile(output.resolve("source-code-main.md")));
        assertTrue(Files.isRegularFile(output.resolve("source-code-feature-reporting.md")));
        String index = Files.readString(output.resolve("source-code-index.md"), StandardCharsets.UTF_8);
        assertTrue(index.contains("feature/reporting"));
        assertTrue(index.contains("source-code-feature-reporting.md"));
    }

    private Path createRepository(Path repository) throws Exception {
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "Teszt Elek");
        git(repository, "config", "user.email", "teszt.elek@test.invalid");

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

    private static String readAllMarkdown(Path directory) throws Exception {
        StringBuilder result = new StringBuilder();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                result.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return result.toString();
    }
}
