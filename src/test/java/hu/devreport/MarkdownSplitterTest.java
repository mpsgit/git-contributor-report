package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownSplitterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void splitsUtf8MarkdownAndKeepsCodeFencesBalanced() throws Exception {
        Path source = temporaryDirectory.resolve("forras.md");
        StringBuilder content = new StringBuilder("# Árvíztűrő tükörfúrógép\n\n```java\n");
        for (int index = 0; index < 2000; index++) {
            content.append("System.out.println(\"árvíztűrő-").append(index).append("\");\n");
        }
        content.append("```\n");
        Files.writeString(source, content, StandardCharsets.UTF_8);

        assertEquals(1, MarkdownSplitter.enforce(List.of(source), 16 * 1024));
        String manifest = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(manifest.contains("Darabolt Markdown-dokumentum"));

        List<Path> parts;
        try (var files = Files.list(temporaryDirectory)) {
            parts = files.filter(path -> path.getFileName().toString().matches("forras\\.part-\\d+\\.md"))
                    .sorted().toList();
        }
        assertTrue(parts.size() > 1);
        for (Path part : parts) {
            assertTrue(Files.size(part) <= 16 * 1024, part + " túl nagy");
            String text = Files.readString(part, StandardCharsets.UTF_8);
            long fences = text.lines().filter(line -> line.startsWith("```")).count();
            assertEquals(0, fences % 2, part + " kódblokkja nincs lezárva");
            assertTrue(!text.contains("�"), part + " sérült UTF-8 karaktert tartalmaz");
        }
    }

    @Test
    void removesOldPartsInOneBatchAndReportsEveryCheckedFile() throws Exception {
        Path commits = Files.createDirectory(temporaryDirectory.resolve("commits"));
        List<Path> markdownFiles = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            Path source = commits.resolve("commit-" + index + ".md");
            Files.writeString(source, "# Commit " + index, StandardCharsets.UTF_8);
            Files.writeString(commits.resolve("commit-" + index + ".part-001.md"), "régi rész",
                    StandardCharsets.UTF_8);
            markdownFiles.add(source);
        }
        int[] lastProgress = new int[2];

        assertEquals(0, MarkdownSplitter.enforce(markdownFiles, 0, (current, total, detail) -> {
            lastProgress[0] = current;
            lastProgress[1] = total;
        }));

        assertEquals(200, lastProgress[0]);
        assertEquals(200, lastProgress[1]);
        try (var files = Files.list(commits)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().contains(".part-")).count());
        }
        assertTrue(markdownFiles.stream().allMatch(Files::isRegularFile));
    }

    @Test
    void keepsMarkdownBelowTheLimitUnchanged() throws Exception {
        Path source = temporaryDirectory.resolve("small.md");
        String original = "# Rövid dokumentum\n\nÁrvíztűrő tükörfúrógép\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);

        assertEquals(0, MarkdownSplitter.enforce(List.of(source), 16 * 1024));
        assertEquals(original, Files.readString(source, StandardCharsets.UTF_8));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().contains(".part-")).count());
        }
    }
}
