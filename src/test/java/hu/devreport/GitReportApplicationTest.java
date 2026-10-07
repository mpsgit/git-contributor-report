package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitReportApplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversNormalBareAndWorktreeRepositoriesAndSkipsOutput() throws Exception {
        Path normal = Files.createDirectories(temporaryDirectory.resolve("normal"));
        Files.createDirectory(normal.resolve(".git"));

        Path bare = Files.createDirectories(temporaryDirectory.resolve("archives/project.git"));
        Files.createDirectory(bare.resolve("objects"));
        Files.writeString(bare.resolve("HEAD"), "ref: refs/heads/main\n");

        Path worktree = Files.createDirectories(temporaryDirectory.resolve("worktrees/feature"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../normal/.git/worktrees/feature\n");

        Path output = Files.createDirectories(temporaryDirectory.resolve("generated"));
        Path ignored = Files.createDirectories(output.resolve("nested-repository"));
        Files.createDirectory(ignored.resolve(".git"));

        List<Path> repositories = GitReportApplication.discoverRepositories(temporaryDirectory, output);

        assertEquals(List.of(bare, normal, worktree).stream().sorted().toList(), repositories);
        assertFalse(repositories.contains(ignored));
    }

    @Test
    void normalizesExtensionsNumbersAndSingleLineErrors() {
        assertEquals(".java", GitReportApplication.extension("src/main/App.java"));
        assertEquals(".ts", GitReportApplication.extension("src/{old => new}/app.ts"));
        assertEquals("(nincs kiterjesztés)", GitReportApplication.extension("Dockerfile"));
        assertEquals("(nincs kiterjesztés)", GitReportApplication.extension(".gitignore"));
        assertEquals(42, GitReportApplication.parseLong("42"));
        assertEquals(0, GitReportApplication.parseLong("nem-szám"));
        assertEquals("első sor második sor", GitReportApplication.oneLine("első sor\r\n  második sor"));
        assertEquals("ismeretlen Git hiba", GitReportApplication.oneLine(null));
    }

    @Test
    void executeCapturesProcessStandardOutputAndError() throws Exception {
        CommandResult success = GitReportApplication.execute(null, List.of("git", "--version"));
        CommandResult failure = GitReportApplication.execute(null,
                List.of("git", "rev-parse", "--verify", "biztosan-nem-letezo-ref"));

        assertEquals(0, success.exitCode);
        assertTrue(success.stdout.startsWith("git version"));
        assertTrue(success.stderr.isBlank());
        assertTrue(failure.exitCode != 0);
        assertFalse(failure.stderr.isBlank());
    }
}
