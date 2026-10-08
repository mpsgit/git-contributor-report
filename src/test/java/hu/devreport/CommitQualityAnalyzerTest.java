package hu.devreport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitQualityAnalyzerTest {
    @Test
    void sizesParallelWorkerPoolByCommitsCpuAndUpperBound() {
        assertEquals(1, CommitQualityAnalyzer.workerCount(0, 16));
        assertEquals(3, CommitQualityAnalyzer.workerCount(3, 16));
        assertEquals(4, CommitQualityAnalyzer.workerCount(20, 4));
        assertEquals(8, CommitQualityAnalyzer.workerCount(20, 32));
    }

    @Test
    void extractsOnlyAddedJavaLineRangesFromUnifiedDiff() {
        String patch = """
                diff --git a/src/App.java b/src/App.java
                index 1111111..2222222 100644
                --- a/src/App.java
                +++ b/src/App.java
                @@ -2,2 +2,4 @@ class App {
                 unchanged
                +first();
                +second();
                 unchanged
                diff --git a/web/app.js b/web/app.js
                --- a/web/app.js
                +++ b/web/app.js
                @@ -0,0 +1,3 @@
                +alert('ignored');
                diff --git a/src/Deleted.java b/src/Deleted.java
                --- a/src/Deleted.java
                +++ /dev/null
                @@ -1 +0,0 @@
                -class Deleted {}
                """;

        Map<String, List<CommitQualityAnalyzer.LineRange>> ranges = CommitQualityAnalyzer.addedJavaRanges(patch);

        assertEquals(1, ranges.size());
        assertTrue(ranges.containsKey("src/App.java"));
        assertEquals(List.of(new CommitQualityAnalyzer.LineRange(3, 4)), ranges.get("src/App.java"));
        assertFalse(ranges.containsKey("web/app.js"));
    }

    @Test
    void extractsAllSupportedQualityLanguagesButIgnoresOtherFiles() {
        StringBuilder patch = new StringBuilder();
        for (String path : List.of("App.java", "query.sql", "page.html", "app.js", "view.tsx", "package.pkb", "notes.txt")) {
            patch.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                    .append("--- /dev/null\n+++ b/").append(path).append("\n@@ -0,0 +1,1 @@\n+content\n");
        }

        Map<String, List<CommitQualityAnalyzer.LineRange>> ranges = CommitQualityAnalyzer.addedSourceRanges(patch.toString());

        assertEquals(6, ranges.size());
        assertTrue(ranges.keySet().containsAll(List.of("App.java", "query.sql", "page.html", "app.js", "view.tsx", "package.pkb")));
        assertFalse(ranges.containsKey("notes.txt"));
    }
}
