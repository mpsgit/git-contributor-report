package hu.devreport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressReporterTest {
    @Test
    void reportsWeightedMonotonicProgressAndDetailedCounters() {
        List<ProgressUpdate> updates = new ArrayList<>();
        ProgressReporter reporter = new ProgressReporter(updates::add, 10, 3);

        ProgressReporter.Stage discovery = reporter.begin(2, "Repók keresése", "Indítás");
        discovery.indeterminate("Vizsgált mappák: 50", 50);
        discovery.finish("2 Git repó található");
        ProgressReporter.Stage analysis = reporter.begin(6, "Git-történet elemzése", "2 repó");
        analysis.update(0.5, "Repó 1/2 | Commitok: 125/250", 125, 250);
        analysis.finish("250 commit");
        reporter.complete("Kész");

        assertEquals(0, updates.getFirst().percent());
        assertEquals(100, updates.getLast().percent());
        assertEquals("Befejezés", updates.getLast().phase());
        assertTrue(updates.stream().anyMatch(update -> update.current() == 125 && update.total() == 250));
        for (int index = 1; index < updates.size(); index++) {
            assertTrue(updates.get(index).percent() >= updates.get(index - 1).percent());
        }
    }

    @Test
    void shortensLongProgressDetailsWithoutBreakingShortText() {
        assertEquals("rövid", InteractiveConsole.shorten("rövid", 10));
        assertEquals("hosszú sz…", InteractiveConsole.shorten("hosszú   szöveg", 10));
    }
}
