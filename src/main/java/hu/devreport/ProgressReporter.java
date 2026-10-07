package hu.devreport;

import java.util.Objects;

/** Weighted, monotonic progress reporting shared by the CLI engine and the interactive UI. */
final class ProgressReporter {
    static final ProgressListener NONE = update -> { };

    private final ProgressListener listener;
    private final int totalWeight;
    private final int phaseCount;
    private int completedWeight;
    private int phaseNumber;
    private int lastPercent = -1;
    private long lastEmissionNanos;

    ProgressReporter(ProgressListener listener, int totalWeight, int phaseCount) {
        this.listener = Objects.requireNonNullElse(listener, NONE);
        this.totalWeight = Math.max(1, totalWeight);
        this.phaseCount = Math.max(1, phaseCount);
    }

    Stage begin(int weight, String phase, String detail) {
        phaseNumber++;
        Stage stage = new Stage(Math.max(1, weight), phase, completedWeight);
        stage.emit(0, detail, 0, 0, true);
        return stage;
    }

    void complete(String detail) {
        completedWeight = totalWeight;
        emit(100, phaseCount, "Befejezés", detail, 1, 1, true);
    }

    void failed(String detail) {
        int percent = Math.max(0, lastPercent);
        emit(percent, Math.min(Math.max(1, phaseNumber), phaseCount), "Hiba", detail, 0, 0, true);
    }

    final class Stage {
        private final int weight;
        private final String phase;
        private final int startWeight;
        private boolean completed;

        private Stage(int weight, String phase, int startWeight) {
            this.weight = weight;
            this.phase = phase;
            this.startWeight = startWeight;
        }

        void update(double fraction, String detail) {
            update(fraction, detail, 0, 0);
        }

        void update(double fraction, String detail, long current, long total) {
            emit(fraction, detail, current, total, false);
        }

        void indeterminate(String detail, long current) {
            emit(0, detail, current, 0, false);
        }

        void finish(String detail) {
            if (completed) return;
            emit(1, detail, 1, 1, true);
            completedWeight = Math.min(totalWeight, startWeight + weight);
            completed = true;
        }

        private void emit(double fraction, String detail, long current, long total, boolean force) {
            double bounded = Math.max(0, Math.min(1, fraction));
            int percent = (int) Math.floor(100.0 * (startWeight + weight * bounded) / totalWeight);
            ProgressReporter.this.emit(percent, phaseNumber, phase, detail, current, total, force);
        }
    }

    private void emit(int percent, int number, String phase, String detail,
                      long current, long total, boolean force) {
        int boundedPercent = Math.max(lastPercent, Math.min(100, percent));
        long now = System.nanoTime();
        if (!force && boundedPercent == lastPercent && now - lastEmissionNanos < 150_000_000L) return;
        lastPercent = boundedPercent;
        lastEmissionNanos = now;
        listener.onProgress(new ProgressUpdate(boundedPercent, number, phaseCount, phase,
                detail == null ? "" : detail, current, total));
    }
}

@FunctionalInterface
interface ProgressListener {
    void onProgress(ProgressUpdate update);
}

record ProgressUpdate(int percent, int phaseNumber, int phaseCount, String phase,
                      String detail, long current, long total) {
}
