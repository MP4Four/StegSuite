package org.stegsuite.core.stat;

/** Progress sink / cancellation flag for long running analyses (implemented by SwingWorkers in the UI). */
public interface Progress {
    /** Fraction done, 0..1. */
    void update(double fraction);

    /** True when the caller wants the analysis to stop; analyses then throw {@link Cancelled}. */
    boolean cancelled();

    /** No progress reporting, never cancelled. */
    Progress NONE = new Progress() {
        @Override public void update(double fraction) {}
        @Override public boolean cancelled() { return false; }
    };

    /** Thrown by an analysis that noticed {@link #cancelled()}. */
    final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Cancelled() { super("cancelled"); }
    }

    /** Throws {@link Cancelled} if cancellation was requested. */
    static void check(Progress p) {
        if (p != null && p.cancelled()) throw new Cancelled();
    }
}
