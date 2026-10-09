package org.stegsuite.report;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.stegsuite.JpegDctTestAccess;
import org.stegsuite.JpegRobustnessTest;
import org.stegsuite.core.stat.Progress;
import org.stegsuite.core.stat.SyntheticCovers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Batch folder scan on a temp folder with clean, stego, appended-data and non-image files. */
public class BatchScanTest {

    @ClassRule public static final TemporaryFolder TMP = new TemporaryFolder();
    static File dir;

    static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    @BeforeClass public static void makeFolder() throws Exception {
        dir = TMP.newFolder("mixed");
        BufferedImage cover = SyntheticCovers.smoothCover(160, 120, 11, 4.0);
        Files.write(new File(dir, "clean.png").toPath(), png(cover));
        Files.write(new File(dir, "stego.png").toPath(), png(SyntheticCovers.withRandomLsb(cover, 1.0, 5)));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("secret.txt"));
            z.write("hidden notes".getBytes("UTF-8"));
            z.closeEntry();
        }
        byte[] clean = png(SyntheticCovers.smoothCover(96, 96, 12, 4.0));
        byte[] appended = Arrays.copyOf(clean, clean.length + zip.size());
        System.arraycopy(zip.toByteArray(), 0, appended, clean.length, zip.size());
        Files.write(new File(dir, "appended_zip.png").toPath(), appended);
        File sub = new File(dir, "sub");
        assertTrue(sub.mkdir());
        ImageIO.write(SyntheticCovers.smoothCover(64, 48, 13, 4.0), "bmp", new File(sub, "nested.bmp"));
        byte[] jpg = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(256, 256, 8), 0.9f);
        Files.write(new File(dir, "clean.jpg").toPath(), jpg);
        // wrong extension on purpose: content sniffing decides the type
        Files.write(new File(dir, "flag_jsteg.dat").toPath(),
                JpegDctTestAccess.embedOriginalJstegWithHeader(jpg, "flag{batch_scan_jsteg}".getBytes("ISO-8859-1")));
        Files.write(new File(dir, "notes.txt").toPath(), "just text".getBytes("UTF-8"));
    }

    static Map<String, FileAnalysis.Result> byName(List<FileAnalysis.Result> rs) {
        Map<String, FileAnalysis.Result> m = new HashMap<>();
        for (FileAnalysis.Result r : rs) m.put(BatchScanner.relative(dir, r.file), r);
        return m;
    }

    @Test public void collectHonoursRecursionAndTypeFilter() throws Exception {
        EnumSet<FileAnalysis.Kind> all = EnumSet.allOf(FileAnalysis.Kind.class);
        List<File> rec = BatchScanner.collect(dir, true, all, 1000);
        List<File> flat = BatchScanner.collect(dir, false, all, 1000);
        assertEquals(7, rec.size());
        assertEquals(6, flat.size());
        List<File> png = BatchScanner.collect(dir, true, EnumSet.of(FileAnalysis.Kind.PNG), 1000);
        assertEquals(3, png.size());
        List<File> jpeg = BatchScanner.collect(dir, true, EnumSet.of(FileAnalysis.Kind.JPEG), 1000);
        assertEquals(2, jpeg.size());                              // flag_jsteg.dat is sniffed as JPEG
        assertEquals(1, BatchScanner.collect(dir, true, EnumSet.of(FileAnalysis.Kind.BMP), 1000).size());
        assertEquals(0, BatchScanner.collect(dir, false, EnumSet.of(FileAnalysis.Kind.BMP), 1000).size());
        assertEquals(2, BatchScanner.collect(dir, true, all, 2).size());
    }

    @Test public void scanScoresStegoAboveClean() throws Exception {
        BatchScanner.Options o = new BatchScanner.Options();
        o.kinds = EnumSet.of(FileAnalysis.Kind.PNG, FileAnalysis.Kind.JPEG, FileAnalysis.Kind.BMP);
        final AtomicInteger calls = new AtomicInteger();
        final double[] last = {0};
        List<FileAnalysis.Result> rs = BatchScanner.scan(dir, o,
                (r, i, total) -> { calls.incrementAndGet(); assertEquals(6, total); },
                new Progress() {
                    @Override public void update(double f) { assertTrue(f >= last[0] - 1e-9 || f == 1.0); last[0] = f; }
                    @Override public boolean cancelled() { return false; }
                });
        assertEquals(6, rs.size());
        assertEquals(6, calls.get());
        assertEquals(1.0, last[0], 1e-9);
        Map<String, FileAnalysis.Result> m = byName(rs);
        for (FileAnalysis.Result r : rs) assertNull(r.name + ": " + r.error, r.error);

        FileAnalysis.Result clean = m.get("clean.png"), stego = m.get("stego.png");
        assertTrue(stego.reasons.toString(), stego.score > clean.score);
        assertTrue(stego.reasons.toString(), stego.spatialRate() > 0.5);
        assertTrue(clean.spatialRate() < 0.1);

        FileAnalysis.Result zip = m.get("appended_zip.png");
        assertTrue(zip.format.hasTrailing());
        assertTrue(zip.reasons.toString(), zip.reasons.toString().contains("trailing data"));
        assertTrue(zip.score >= 30);

        FileAnalysis.Result bmp = m.get("sub/nested.bmp");
        assertNotNull(bmp);
        assertEquals(FileAnalysis.Kind.BMP, bmp.kind);
        assertNotNull(bmp.spatial);

        FileAnalysis.Result jpg = m.get("clean.jpg"), flag = m.get("flag_jsteg.dat");
        assertEquals(FileAnalysis.Kind.JPEG, flag.kind);
        assertNotNull(flag.dct);
        assertNotNull(flag.dct.payload);
        assertTrue(flag.dct.payload, flag.dct.payload.contains("flag{batch_scan_jsteg}"));
        assertTrue(flag.reasons.toString(), flag.reasons.toString().contains("DCT payload"));
        assertTrue(flag.score > jpg.score);
        assertEquals("high", flag.level());
        assertFalse("clean JPEG must not report trailing data: " + jpg.reasons, jpg.format.hasTrailing());
        for (FileAnalysis.Result r : rs) {
            assertNotNull(r.thumbnail);
            assertTrue(r.score >= 0 && r.score <= 100);
        }
    }

    @Test public void batchReportListsEveryFileSortedByScore() throws Exception {
        BatchScanner.Options o = new BatchScanner.Options();
        o.analysis.autoScan = false;
        List<FileAnalysis.Result> rs = BatchScanner.scan(dir, o, null, null);
        Report rep = ReportBuilder.batch(dir, rs, true);
        assertEquals(rs.size(), rep.table.rows.size());
        int prev = Integer.MAX_VALUE;
        int scoreCol = rep.table.columns.indexOf("Score");
        assertTrue(scoreCol >= 0);
        for (List<Object> row : rep.table.rows) {
            int s = ((Number) row.get(scoreCol)).intValue();
            assertTrue(s <= prev);
            prev = s;
        }
        String html = ReportWriter.html(rep);
        for (FileAnalysis.Result r : rs) assertTrue(html.contains(r.name));
        assertTrue(html.contains("data:image/png;base64,"));
    }

    @Test(expected = Progress.Cancelled.class) public void scanCanBeCancelled() throws Exception {
        final AtomicInteger done = new AtomicInteger();
        BatchScanner.scan(dir, new BatchScanner.Options(), (r, i, t) -> done.incrementAndGet(), new Progress() {
            @Override public void update(double f) {}
            @Override public boolean cancelled() { return done.get() >= 1; }
        });
    }
}
