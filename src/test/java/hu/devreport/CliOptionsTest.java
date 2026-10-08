package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsCreateHtmlAndSQLiteWithPatches() {
        CliOptions options = CliOptions.parse(new String[0]);

        assertTrue(options.includePatches);
        assertFalse(options.fetch);
        assertEquals(Path.of("report"), options.output);
        assertNull(options.database);
        assertNull(options.renderDatabase);
    }

    @Test
    void databaseAndHtmlRestoreOptionsAreParsed() {
        CliOptions options = CliOptions.parse(new String[]{
                "--database", "cache/team.sqlite", "--no-patches", "--fetch"
        });
        CliOptions restore = CliOptions.parse(new String[]{
                "--render-db", "archive/report.sqlite", "--output", "restored"
        });

        assertEquals(Path.of("cache/team.sqlite"), options.database);
        assertFalse(options.includePatches);
        assertTrue(options.fetch);
        assertEquals(Path.of("archive/report.sqlite"), restore.renderDatabase);
        assertEquals(Path.of("restored"), restore.output);
    }

    @Test
    void removedMarkdownAndSourceOptionsAreRejected() {
        assertThrows(CommandLine.ParameterException.class,
                () -> CliOptions.parse(new String[]{"--outputs", "markdown"}));
        assertThrows(CommandLine.ParameterException.class,
                () -> CliOptions.parse(new String[]{"--source-branches", "all"}));
        assertThrows(CommandLine.ParameterException.class,
                () -> CliOptions.parse(new String[]{"--max-md-size", "500MB"}));
    }

    @Test
    void helpExplainsSQLiteHtmlAndHungarianUsage() {
        String help = CliOptions.helpText();
        String compact = help.replaceAll("\\s+", " ");

        assertTrue(help.contains("Használat:"));
        assertTrue(help.contains("Opciók:"));
        assertTrue(help.contains("gyökérkönyvtár"));
        assertTrue(help.contains("--since 2026-01-01 --until 2026-12-31"));
        assertTrue(help.contains("--database"));
        assertTrue(help.contains("--render-db"));
        assertTrue(help.contains("report.sqlite"));
        assertTrue(help.contains("--interactive"));
        assertTrue(compact.contains("bizalmas fejlesztési adatként"));
        assertFalse(help.contains("--max-md-size"));
        assertFalse(help.contains("--source-branches"));
    }

    @Test
    void interactiveFlagKeepsCurrentCliDefaultsAvailable() {
        CliOptions options = CliOptions.parse(new String[]{
                "--interactive", "--root", "repos", "--output", "eredmeny",
                "--database", "cache.sqlite", "--since", "2026-01-01", "--until", "2026-12-31",
                "--no-patches", "--fetch", "--quality"
        });

        assertTrue(options.interactive);
        assertEquals(Path.of("repos"), options.root);
        assertEquals(Path.of("eredmeny"), options.output);
        assertEquals(Path.of("cache.sqlite"), options.database);
        assertEquals("2026-01-01", options.since);
        assertEquals("2026-12-31", options.until);
        assertFalse(options.includePatches);
        assertTrue(options.fetch);
        assertTrue(options.qualityAnalysis);
    }

    @Test
    void interactiveHelpExplainsDatabaseAndComparisonWorkflow() {
        String help = InteractiveConsole.completeHelpText();

        assertTrue(help.contains("Gyors kezdés"));
        assertTrue(help.contains("Alt+F"));
        assertTrue(help.contains("Git worktree-k"));
        assertTrue(help.contains("SQLite"));
        assertTrue(help.contains("HTML"));
        assertTrue(help.contains("F3"));
        assertTrue(help.contains("PMD/CPD"));
    }

    @Test
    void directoryPickerStartsFromNearestExistingParent() {
        Path missing = temporaryDirectory.resolve("new-report").resolve("nested");
        assertEquals(temporaryDirectory, InteractiveConsole.existingDirectory(missing.toString()));
    }

    @Test
    void parsesPathsTitleDatesVersionAndInteractiveAlias() {
        CliOptions options = CliOptions.parse(new String[]{
                "-i", "--root", "forrás", "--output", "eredmény", "--title", "Saját riport",
                "--since", "2 weeks ago", "--until", "yesterday"
        });

        assertTrue(options.interactive);
        assertEquals(Path.of("forrás"), options.root);
        assertEquals(Path.of("eredmény"), options.output);
        assertEquals("Saját riport", options.title);
        assertEquals("2 weeks ago", options.since);
        assertEquals("yesterday", options.until);
        assertTrue(CliOptions.helpText().contains("-V, --version"));
    }
}
