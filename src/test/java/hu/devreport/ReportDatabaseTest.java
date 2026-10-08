package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void qualityCacheAndHtmlArchiveSurviveReopenAndArePortable() throws Exception {
        Path output = Files.createDirectories(temporaryDirectory.resolve("report"));
        Path databaseFile = output.resolve("report.sqlite");
        String indexHtml = "<section><h1>Árvíztűrő tükörfúrógép</h1></section>".repeat(1_000);
        Files.writeString(output.resolve("index.html"), indexHtml, StandardCharsets.UTF_8);
        Files.createDirectories(output.resolve("assets"));
        Files.writeString(output.resolve("assets/app.js"), "window.ready=true;", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("legacy.md"), "nem archiválandó", StandardCharsets.UTF_8);
        QualityAssessment quality = new QualityAssessment(QualityAssessment.Status.COMPLETE, 82, "B",
                "teszt", 2, 14, Map.of("Java", 2), List.of("megjegyzés"),
                List.of(new QualityFinding("Java", "PMD", "Rule", "Rules", "üzenet",
                        "src/App.java", 12, 2, "https://example.invalid/rule")));

        try (ReportDatabase database = ReportDatabase.open(databaseFile)) {
            database.saveQuality("origin:example/repo", "abc123", quality);
            ReportDatabase.ArchiveStats stats = database.archiveHtml(output, (current, total) -> { });
            assertEquals(2, stats.files());
            assertTrue(stats.storedBytes() < stats.originalBytes());
        }

        Path restored = temporaryDirectory.resolve("restored");
        try (ReportDatabase database = ReportDatabase.open(databaseFile)) {
            QualityAssessment cached = database.loadQuality("origin:example/repo", Set.of("abc123")).get("abc123");
            assertEquals(82, cached.score);
            assertEquals("Rule", cached.findings.getFirst().rule);
            database.restoreHtml(restored, (current, total) -> { });
        }

        assertEquals(indexHtml,
                Files.readString(restored.resolve("index.html"), StandardCharsets.UTF_8));
        assertEquals("window.ready=true;", Files.readString(restored.resolve("assets/app.js"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(restored.resolve("legacy.md")));
        assertTrue(Files.isRegularFile(databaseFile));
    }
}
