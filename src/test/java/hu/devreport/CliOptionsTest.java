package hu.devreport;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Set;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsEnableEveryOutputAndPatches() {
        CliOptions options = CliOptions.parse(new String[0]);

        assertEquals(Set.of("html", "markdown", "source"), options.outputs);
        assertTrue(options.includePatches);
        assertEquals(Set.of("all"), options.sourceBranches);
        assertFalse(options.sourceRefExplicit);
        assertFalse(options.fetch);
    }

    @Test
    void outputsAndPatchCollectionCanBeRestricted() {
        CliOptions options = CliOptions.parse(new String[]{
                "--outputs", "html,markdown", "--no-patches", "--fetch", "--max-md-size", "10MB"
        });

        assertEquals(Set.of("html", "markdown"), options.outputs);
        assertFalse(options.includePatches);
        assertTrue(options.fetch);
        assertEquals(10L * 1024 * 1024, options.maxMarkdownBytes);
    }

    @Test
    void sourceOnlyAndExplicitRefAreParsed() {
        CliOptions options = CliOptions.parse(new String[]{
                "--source-only", "--source-branches", "main,origin/develop", "--source-ref", "origin/develop"
        });

        assertEquals(Set.of("source"), options.outputs);
        assertEquals(Set.of("main", "origin/develop"), options.sourceBranches);
        assertEquals("origin/develop", options.sourceRef);
        assertTrue(options.sourceRefExplicit);
    }

    @Test
    void sourceBranchModesAndNamesCanBeCombined() {
        CliOptions options = CliOptions.parse(new String[]{
                "--source-branches", "local,Origin/Release"
        });

        assertEquals(Set.of("local", "Origin/Release"), options.sourceBranches);
    }

    @Test
    void rejectsUnknownOutput() {
        assertThrows(CommandLine.ParameterException.class,
                () -> CliOptions.parse(new String[]{"--outputs", "html,pdf"}));
    }

    @Test
    void helpIsHungarianAndContainsAccentedCharacters() {
        String help = CliOptions.helpText();
        String compactHelp = help.replaceAll("\\s+", " ");

        assertTrue(help.contains("Használat:"));
        assertTrue(help.contains("Opciók:"));
        assertTrue(help.contains("gyökérkönyvtár"));
        assertTrue(help.contains("Alapérték:"));
        assertTrue(help.contains("--since 2026-01-01 --until 2026-12-31"));
        assertTrue(help.contains("ÉÉÉÉ-HH-NN"));
        assertTrue(help.contains("git fetch --all --prune"));
        assertTrue(help.contains("main,develop,origin/release"));
        assertTrue(help.contains("--source-branches main,develop"));
        assertTrue(help.contains("--interactive"));
        assertTrue(help.contains("Midnight Commander-stílusú"));
        assertTrue(help.contains("--max-md-size"));
        assertTrue(help.contains("10MB"));
        assertTrue(help.contains("Részletes használat és működés:"));
        assertTrue(help.contains("Bemenet és elemzés"));
        assertTrue(compactHelp.contains("nem önmagukban használható teljesítménypontok"));
        assertTrue(compactHelp.contains("bizalmas fejlesztési adatként"));
    }

    @Test
    void interactiveHelpExplainsEveryMajorWorkflow() {
        String help = InteractiveConsole.completeHelpText();

        assertTrue(help.contains("Gyors kezdés"));
        assertTrue(help.contains("Alt+F"));
        assertTrue(help.contains("Git worktree-k"));
        assertTrue(help.contains("2026-01-01"));
        assertTrue(help.contains("Teljes commit diffek"));
        assertTrue(help.contains("main,develop,origin/release"));
        assertTrue(help.contains("Markdown méretkorlát"));
        assertTrue(help.contains("git fetch --all --prune"));
        assertTrue(help.contains("nem önmagukban használható teljesítménypontok"));
        assertTrue(help.contains("bizalmas fejlesztési adatként"));
        assertTrue(help.contains("--max-md-size 500MB"));
    }

    @Test
    void interactiveFlagKeepsAllCliDefaultsAvailable() {
        CliOptions options = CliOptions.parse(new String[]{
                "--interactive", "--root", "repos", "--output", "eredmeny", "--outputs", "markdown,source",
                "--since", "2026-01-01", "--until", "2026-12-31", "--no-patches", "--fetch",
                "--source-branches", "main,develop"
        });

        assertTrue(options.interactive);
        assertEquals("repos", options.root.toString());
        assertEquals("eredmeny", options.output.toString());
        assertEquals(Set.of("markdown", "source"), options.outputs);
        assertEquals("2026-01-01", options.since);
        assertEquals("2026-12-31", options.until);
        assertFalse(options.includePatches);
        assertTrue(options.fetch);
        assertEquals(Set.of("main", "develop"), options.sourceBranches);
    }

    @Test
    void interactiveBranchListParserTrimsAndDeduplicates() {
        assertEquals(Set.of("main", "develop", "origin/release"),
                InteractiveConsole.parseBranches(" main, develop,main, origin/release "));
    }

    @Test
    void directoryPickerStartsFromNearestExistingParent() {
        Path missing = temporaryDirectory.resolve("new-report").resolve("nested");

        assertEquals(temporaryDirectory, InteractiveConsole.existingDirectory(missing.toString()));
    }

    @Test
    void parsesHumanReadableMarkdownLimits() {
        assertEquals(0, CliOptions.parseByteSize("0"));
        assertEquals(512L * 1024, CliOptions.parseByteSize("512 KB"));
        assertEquals(1572864L, CliOptions.parseByteSize("1.5MB"));
        assertEquals(500L * 1024 * 1024, CliOptions.parseByteSize("500MB"));
        assertEquals(750L * 1024 * 1024, CliOptions.parseByteSize("750 MB"));
        assertEquals(1024L * 1024 * 1024, CliOptions.parseByteSize("1024MB"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parseByteSize("12KB"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parseByteSize("nagy"));
    }

    @Test
    void acceptsBinaryUnitAliasesDecimalCommaAndLongOptionName() {
        CliOptions options = CliOptions.parse(new String[]{"--max-markdown-size", "1,5MiB"});

        assertEquals(1572864L, options.maxMarkdownBytes);
        assertEquals("1 GB", CliOptions.formatByteSize(1024L * 1024 * 1024));
        assertEquals("750 MB", CliOptions.formatByteSize(750L * 1024 * 1024));
        assertEquals("korlátlan", CliOptions.formatByteSize(0));
    }

    @Test
    void rejectsEmptyBranchesFractionalBytesAndOverflow() {
        assertThrows(CommandLine.ParameterException.class,
                () -> CliOptions.parse(new String[]{"--source-branches", " "}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parseByteSize("16.1B"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parseByteSize("999999999999999999999GB"));
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
