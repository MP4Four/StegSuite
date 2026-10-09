package org.stegsuite.ui;

import org.stegsuite.core.stat.Progress;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CancellationException;

/**
 * Runs one background analysis at a time for a panel: SwingWorker with a progress bar, a Cancel button,
 * status bar messages with timing, and stale-result protection (results of a task that was cancelled or
 * superseded, e.g. by opening another file, are dropped).
 */
final class TaskRunner {
    interface Job<T> { T run(Progress progress) throws Exception; }
    interface Done<T> { void accept(T result); }
    interface Failed { void accept(Throwable cause); }

    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JButton cancel = new JButton("Cancel");
    private final JPanel component = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));
    private final Runnable onStateChange;
    private SwingWorker<?, ?> worker;
    private int generation;

    TaskRunner(Runnable onStateChange) {
        this.onStateChange = onStateChange;
        bar.setStringPainted(true);
        bar.setString("idle");
        bar.setPreferredSize(UiScale.dim(160, 18));
        cancel.setEnabled(false);
        cancel.addActionListener(e -> cancel());
        component.add(bar);
        component.add(cancel);
    }

    JComponent component() { return component; }

    boolean running() { return worker != null; }

    /** Cancels the running task (if any); its result will be ignored. */
    void cancel() {
        generation++;
        if (worker != null) {
            worker.cancel(true);
            worker = null;
            bar.setValue(0);
            bar.setString("cancelled");
            cancel.setEnabled(false);
            if (onStateChange != null) onStateChange.run();
        }
    }

    <T> void start(String label, Job<T> job, Done<T> done, Failed failed) {
        cancel();
        final int myGeneration = ++generation;
        final long started = System.nanoTime();
        bar.setValue(0);
        bar.setString(label + "...");
        cancel.setEnabled(true);
        StatusBar.message(label + "...");
        final class Worker extends SwingWorker<T, Void> {
            void report(int v) { setProgress(v); }

            @Override protected T doInBackground() throws Exception {
                final Worker self = this;
                return job.run(new Progress() {
                    @Override public void update(double f) {
                        self.report((int) Math.max(0, Math.min(100, Math.round(f * 100))));
                    }
                    @Override public boolean cancelled() { return self.isCancelled() || Thread.currentThread().isInterrupted(); }
                });
            }

            @Override protected void done() {
                if (myGeneration != generation) return;
                worker = null;
                cancel.setEnabled(false);
                long ms = (System.nanoTime() - started) / 1_000_000;
                try {
                    T r = get();
                    bar.setValue(100);
                    bar.setString("done (" + ms + " ms)");
                    StatusBar.message(label + " finished in " + ms + " ms");
                    done.accept(r);
                } catch (CancellationException e) {
                    bar.setString("cancelled");
                    StatusBar.message(label + " cancelled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Throwable cause = DctPanel.rootCause(e);
                    if (cause instanceof Progress.Cancelled) {
                        bar.setString("cancelled");
                        StatusBar.message(label + " cancelled");
                    } else {
                        bar.setString("failed");
                        StatusBar.message(label + " failed after " + ms + " ms");
                        failed.accept(cause);
                    }
                } finally {
                    if (onStateChange != null) onStateChange.run();
                }
            }
        }
        Worker w = new Worker();
        w.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName()) && myGeneration == generation) {
                int v = (Integer) ev.getNewValue();
                bar.setValue(v);
                bar.setString(label + " " + v + "%");
            }
        });
        worker = w;
        if (onStateChange != null) onStateChange.run();
        w.execute();
    }
}
