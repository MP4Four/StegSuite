package org.stegsuite.report;

import org.stegsuite.core.stat.Progress;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Batch folder scan: runs {@link FileAnalysis} on every matching file of a folder (optionally recursive). */
public final class BatchScanner {
    private BatchScanner() {}

    public static final class Options {
        public boolean recursive = true;
        /** File types to include (classified by content, not by extension). */
        public EnumSet<FileAnalysis.Kind> kinds = EnumSet.of(FileAnalysis.Kind.PNG, FileAnalysis.Kind.JPEG,
                FileAnalysis.Kind.BMP, FileAnalysis.Kind.GIF);
        /** Larger files are listed with an error instead of being read. */
        public long maxFileSize = 256L * 1024 * 1024;
        public int maxFiles = 20000;
        public FileAnalysis.Options analysis = new FileAnalysis.Options();
    }

    /** Called after every file (from the scanning thread). */
    public interface Listener {
        void fileDone(FileAnalysis.Result result, int index, int total);
    }

    /** Files of the folder whose content type is in {@code kinds}, sorted by path. */
    public static List<File> collect(File dir, boolean recursive, EnumSet<FileAnalysis.Kind> kinds, int maxFiles) throws IOException {
        if (!dir.isDirectory()) throw new IOException("not a folder: " + dir);
        List<Path> paths;
        try (Stream<Path> s = recursive ? Files.walk(dir.toPath(), new FileVisitOption[0]) : Files.list(dir.toPath())) {
            paths = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<File> out = new ArrayList<>();
        for (Path p : paths) {
            if (out.size() >= maxFiles) break;
            FileAnalysis.Kind k = sniff(p.toFile());
            if (k != null && kinds.contains(k)) out.add(p.toFile());
        }
        return out;
    }

    /** Content type from the first bytes, or null if unreadable. */
    static FileAnalysis.Kind sniff(File f) {
        byte[] head = new byte[64];
        int n = 0;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
        } catch (IOException e) {
            return null;
        }
        byte[] h = n == head.length ? head : java.util.Arrays.copyOf(head, n);
        return FileAnalysis.kindOf(h, f.getName());
    }

    /**
     * Scans the folder. Results keep the file order; a file that cannot be read or analysed gets
     * {@link FileAnalysis.Result#error} set and score 0.
     *
     * @throws Progress.Cancelled when cancelled (results so far are delivered through the listener)
     */
    public static List<FileAnalysis.Result> scan(File dir, Options o, Listener listener, Progress progress) throws IOException {
        if (progress == null) progress = Progress.NONE;
        List<File> files = collect(dir, o.recursive, o.kinds, o.maxFiles);
        List<FileAnalysis.Result> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Progress.check(progress);
            File f = files.get(i);
            final int index = i, total = files.size();
            final Progress outer = progress;
            Progress sub = new Progress() {
                // parallel stages (auto scan) report out of order: only ever move the bar forwards
                private double max;
                @Override public synchronized void update(double fr) {
                    double v = (index + Math.max(0, Math.min(1, fr))) / total;
                    if (v <= max) return;
                    max = v;
                    outer.update(v);
                }
                @Override public boolean cancelled() { return outer.cancelled(); }
            };
            FileAnalysis.Result r;
            try {
                if (f.length() > o.maxFileSize) throw new IOException("file larger than " + o.maxFileSize / (1024 * 1024) + " MiB, skipped");
                byte[] data = Files.readAllBytes(f.toPath());
                r = FileAnalysis.analyze(f, data, o.analysis, sub);
            } catch (Progress.Cancelled c) {
                throw c;
            } catch (Exception | OutOfMemoryError e) {
                r = new FileAnalysis.Result();
                r.file = f;
                r.name = f.getName();
                r.size = f.length();
                r.error = e.getMessage() == null ? e.toString() : e.getMessage();
                r.reasons.add("not analysed: " + r.error);
            }
            results.add(r);
            if (listener != null) listener.fileDone(r, i, total);
        }
        progress.update(1.0);
        return Collections.unmodifiableList(results);
    }

    /** Path of a result relative to the scanned folder (for tables and reports). */
    public static String relative(File dir, File f) {
        if (dir == null || f == null) return f == null ? "" : f.getName();
        try {
            return dir.toPath().toAbsolutePath().normalize().relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return f.getPath();
        }
    }
}
