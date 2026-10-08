package hu.devreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParallelSupportTest {
    @Test
    void activityReportsTheLiveAndMaximumWorkerCount() {
        ParallelSupport.Activity activity = ParallelSupport.activity(3, 2);

        assertEquals("Aktív szál: 0/2", activity.label());
        try (ParallelSupport.Scope first = activity.start()) {
            assertEquals("Aktív szál: 1/2", activity.label());
            try (ParallelSupport.Scope second = activity.start()) {
                assertEquals("Aktív szál: 2/2", activity.label());
            }
            assertEquals("Aktív szál: 1/2", activity.label());
        }
        assertEquals("Aktív szál: 0/2", activity.label());
    }

    @Test
    void closingAScopeMoreThanOnceDoesNotCorruptTheCount() {
        ParallelSupport.Activity activity = ParallelSupport.activity(1, 8);
        ParallelSupport.Scope scope = activity.start();

        scope.close();
        scope.close();

        assertEquals("Aktív szál: 0/1", activity.label());
    }
}
