package hu.devreport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded concurrency and thread-safe aggregate progress helpers. */
final class ParallelSupport {
    private ParallelSupport() { }

    static int workers(int tasks, int cap) {
        return Math.max(1, Math.min(Math.min(Math.max(1, tasks),
                Math.max(1, Runtime.getRuntime().availableProcessors())), Math.max(1, cap)));
    }

    static ExecutorService executor(String name, int tasks, int cap) {
        int workers = workers(tasks, cap);
        return Executors.newFixedThreadPool(workers, Thread.ofVirtual().name(name, 0).factory());
    }

    static Activity activity(int tasks, int cap) {
        return new Activity(workers(tasks, cap));
    }

    static void await(List<? extends Future<?>> futures) throws IOException, InterruptedException {
        for (Future<?> future : futures) result(future);
    }

    static <T> T result(Future<T> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("A párhuzamos feladat váratlan hibával leállt.", cause);
        }
    }

    static final class AggregateProgress {
        private final ProgressReporter.Stage stage;
        private final double base;
        private final double span;
        private final double[] fractions;
        private final Activity activity;

        AggregateProgress(ProgressReporter.Stage stage, int tasks, double base, double span) {
            this(stage, tasks, base, span, null);
        }

        AggregateProgress(ProgressReporter.Stage stage, int tasks, double base, double span, Activity activity) {
            this.stage = stage;
            this.base = base;
            this.span = span;
            this.fractions = new double[Math.max(1, tasks)];
            this.activity = activity;
        }

        synchronized void update(int task, double fraction, String detail, long current, long total) {
            int index = Math.max(0, Math.min(fractions.length - 1, task));
            fractions[index] = Math.max(fractions[index], Math.max(0, Math.min(1, fraction)));
            double sum = 0;
            for (double value : fractions) sum += value;
            stage.update(base + span * sum / fractions.length,
                    (activity == null ? "" : activity.label() + " | ") + detail, current, total);
        }
    }

    static final class Activity {
        private final int limit;
        private final AtomicInteger active = new AtomicInteger();

        private Activity(int limit) { this.limit = limit; }

        Scope start() {
            active.incrementAndGet();
            return new Scope(this);
        }

        String label() {
            return "Aktív szál: " + active.get() + "/" + limit;
        }

        int limit() { return limit; }

        private void finish() { active.decrementAndGet(); }
    }

    static final class Scope implements AutoCloseable {
        private Activity activity;

        private Scope(Activity activity) { this.activity = activity; }

        @Override
        public void close() {
            if (activity == null) return;
            activity.finish();
            activity = null;
        }
    }
}
