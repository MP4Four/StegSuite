package org.stegsuite;

import org.junit.Test;
import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.JpegDctDecoder;
import org.stegsuite.core.jpeg.StandardTables;
import org.stegsuite.core.jpeg.ZigZag;
import org.stegsuite.plugin.JpegStegoPlugin;
import org.stegsuite.plugin.PluginRegistry;
import org.stegsuite.plugin.dct.DctExtraction;
import org.stegsuite.plugin.dct.JpegDctPlugin;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Tests for the JPEG DCT coefficient decoder and the bundled DCT steganalysis plugin. */
public class JpegDctTest {

    // ------------------------------------------------------------------ decoder

    @Test public void decodesImageIoJpegExactlyLikeTheJdk() throws Exception {
        byte[] jpg = TestImages.jpeg(TestImages.noise(64, 48, 3));
        DctImage img = JpegDctDecoder.decode(jpg);
        assertEquals(64, img.width());
        assertEquals(48, img.height());
        assertEquals(3, img.components().size());
        assertEquals("baseline", img.frameType());
        double[] d = pixelDiff(img.toBufferedImage(), ImageUtil.decode(jpg));
        assertTrue("mean |diff| = " + d[0] + ", max = " + d[1], d[0] < 0.5);
        assertTrue("max |diff| = " + d[1], d[1] <= 6);
    }

    @Test public void decodesProgressiveJpeg() throws Exception {
        byte[] jpg = progressiveJpeg(TestImages.noise(64, 48, 5));
        DctImage img = JpegDctDecoder.decode(jpg);
        assertTrue("progressive frame expected", img.progressive());
        assertTrue("expected several scans, got " + img.scanCount(), img.scanCount() > 1);
        double[] d = pixelDiff(img.toBufferedImage(), ImageUtil.decode(jpg));
        assertTrue("mean |diff| = " + d[0] + ", max = " + d[1], d[0] < 0.5);
        assertTrue("max |diff| = " + d[1], d[1] <= 6);
    }

    @Test public void readsBackExactlyTheCoefficientsWeWrote() throws Exception {
        int blocksPerLine = 8, blocksPerColumn = 6;
        int[] coeffs = randomCoefficients(blocksPerLine * blocksPerColumn, 17, 100, 4);
        int[] quant = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 90);
        byte[] jpg = JpegTestWriter.grayscale(blocksPerLine * 8, blocksPerColumn * 8, blocksPerLine, blocksPerColumn,
                coeffs, quant, 0);
        assertNotNull("the crafted JPEG must be readable by ImageIO", ImageIO.read(new ByteArrayInputStream(jpg)));
        DctImage img = JpegDctDecoder.decode(jpg);
        assertEquals(1, img.components().size());
        assertCoefficientsEqual(coeffs, img.components().get(0), blocksPerLine * blocksPerColumn);
        assertArrayEquals(quant, img.quantTableOf(img.components().get(0)).values());
    }

    @Test public void handlesRestartMarkers() throws Exception {
        int blocksPerLine = 9, blocksPerColumn = 7;
        int[] coeffs = randomCoefficients(blocksPerLine * blocksPerColumn, 23, 300, 5);
        int[] quant = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 75);
        byte[] jpg = JpegTestWriter.grayscale(blocksPerLine * 8, blocksPerColumn * 8, blocksPerLine, blocksPerColumn,
                coeffs, quant, 4);
        DctImage img = JpegDctDecoder.decode(jpg);
        assertEquals(4, img.restartInterval());
        assertCoefficientsEqual(coeffs, img.components().get(0), blocksPerLine * blocksPerColumn);
    }

    @Test public void flatImageHasNoNonZeroAcCoefficients() throws Exception {
        BufferedImage flat = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = flat.createGraphics();
        g.setColor(java.awt.Color.GRAY);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        DctImage img = JpegDctDecoder.decode(TestImages.jpeg(flat));
        assertEquals(0, img.components().get(0).nonZeroAcCount());
    }

    /** 4:2:0 with an odd size exercises interleaved MCUs, padding data units and the shared chroma tables. */
    @Test public void readsInterleavedSubsampledColorCoefficients() throws Exception {
        int width = 60, height = 44;                       // not a multiple of 16 -> padding data units
        int yQuant[] = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 85);
        int cQuant[] = StandardTables.scaleQuant(StandardTables.QUANT_CHROMINANCE, 85);
        JpegTestWriter.Comp luma = new JpegTestWriter.Comp(2, 2, 0, yQuant, width, height, 2, 2);
        JpegTestWriter.Comp cb = new JpegTestWriter.Comp(1, 1, 1, cQuant, width, height, 2, 2);
        JpegTestWriter.Comp cr = new JpegTestWriter.Comp(1, 1, 1, cQuant, width, height, 2, 2);
        luma.coefficients[0 * 64] = 120;
        cb.coefficients[0 * 64] = -30;
        cr.coefficients[0 * 64] = 45;
        fillAc(luma.coefficients, 5, 21);
        fillAc(cb.coefficients, 3, 22);
        fillAc(cr.coefficients, 3, 23);
        byte[] jpg = JpegTestWriter.color(new JpegTestWriter.Comp[]{luma, cb, cr}, width, height, 3);

        assertNotNull("the crafted JPEG must be readable by ImageIO", ImageIO.read(new ByteArrayInputStream(jpg)));
        DctImage img = JpegDctDecoder.decode(jpg);
        assertEquals(3, img.components().size());
        assertEquals("restart interval", 3, img.restartInterval());
        assertEquals(2, img.maxH());
        assertEquals(2, img.maxV());
        assertEquals("component 0 blocks", luma.blocksPerLine * luma.blocksPerColumn, img.components().get(0).blockCount());
        assertEquals("component 1 blocks", cb.blocksPerLine * cb.blocksPerColumn, img.components().get(1).blockCount());
        assertCoefficientsEqual(luma.coefficients, img.components().get(0), luma.blocksPerLine * luma.blocksPerColumn);
        assertCoefficientsEqual(cb.coefficients, img.components().get(1), cb.blocksPerLine * cb.blocksPerColumn);
        assertCoefficientsEqual(cr.coefficients, img.components().get(2), cr.blocksPerLine * cr.blocksPerColumn);
        assertEquals(2, img.quantTables().size());
        assertEquals(1, img.quantTableOf(img.components().get(1)).id());
    }

    @Test public void reconstructs444And422And420LikeTheJdk() throws Exception {
        for (int[] sampling : new int[][]{{1, 1}, {2, 1}, {2, 2}}) {
            int width = 61, height = 43;
            int maxH = sampling[0], maxV = sampling[1];
            int[] yQuant = StandardTables.scaleQuant(StandardTables.QUANT_LUMINANCE, 90);
            int[] cQuant = StandardTables.scaleQuant(StandardTables.QUANT_CHROMINANCE, 90);
            JpegTestWriter.Comp luma = new JpegTestWriter.Comp(maxH, maxV, 0, yQuant, width, height, maxH, maxV);
            JpegTestWriter.Comp cb = new JpegTestWriter.Comp(1, 1, 1, cQuant, width, height, maxH, maxV);
            JpegTestWriter.Comp cr = new JpegTestWriter.Comp(1, 1, 1, cQuant, width, height, maxH, maxV);
            // a smooth gradient in all three components, so chroma upsampling is exercised
            for (int b = 0; b < luma.blocksPerLine * luma.blocksPerColumn; b++)
                luma.coefficients[b * 64] = 40 * ((b % luma.blocksPerLine) + 1);
            for (int b = 0; b < cb.blocksPerLine * cb.blocksPerColumn; b++)
                cb.coefficients[b * 64] = 20 * ((b % cb.blocksPerLine) + 1);
            for (int b = 0; b < cr.blocksPerLine * cr.blocksPerColumn; b++)
                cr.coefficients[b * 64] = -15 * ((b % cr.blocksPerLine) + 1);
            byte[] jpg = JpegTestWriter.color(new JpegTestWriter.Comp[]{luma, cb, cr}, width, height, 0);
            DctImage img = JpegDctDecoder.decode(jpg);
            assertNotNull("ImageIO could not read the " + sampling[0] + "x" + sampling[1] + " file",
                    ImageIO.read(new ByteArrayInputStream(jpg)));
            double[] d = pixelDiff(img.toBufferedImage(), ImageUtil.decode(jpg));
            assertTrue(sampling[0] + "x" + sampling[1] + " mean |diff| = " + d[0], d[0] < 1.0);
            assertTrue(sampling[0] + "x" + sampling[1] + " max |diff| = " + d[1], d[1] <= 4);
        }
    }

    @Test public void rejectsNonJpegData() {
        try {
            JpegDctDecoder.decode("not a jpeg at all".getBytes());
            fail("expected an IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("JPEG"));
        }
    }

    // ------------------------------------------------------------------ plugin

    @Test public void pluginIsRegisteredAsAService() {
        List<JpegStegoPlugin> plugins = PluginRegistry.jpegPlugins();
        assertFalse("no JPEG DCT plugin was discovered", plugins.isEmpty());
        JpegStegoPlugin plugin = plugins.get(0);
        assertNotNull(plugin.name());
        assertFalse(plugin.description().isEmpty());
    }

    @Test public void reportDescribesFrameTablesAndSteganalysis() throws Exception {
        String report = new JpegDctPlugin().analyze(TestImages.jpeg(TestImages.noise(80, 60, 13)));
        assertTrue(report, report.contains("== JPEG DCT analysis =="));
        assertTrue(report, report.contains("Quantization tables: 2"));
        assertTrue(report, report.contains("component 1"));
        assertTrue(report, report.contains("Annex K luminance table"));
        assertTrue(report, report.contains("Annex K DC luminance"));
        assertTrue(report, report.contains("== DCT coefficients =="));
        assertTrue(report, report.contains("== Steganalysis (DCT domain) =="));
        assertTrue(report, report.contains("chi2/df"));
        assertTrue(report, report.contains("== DCT LSB extraction attempts =="));
        assertTrue(report, report.contains("== Summary =="));
        assertTrue(report, report.contains("Payload: none found"));
        assertTrue(report, report.contains("Histogram indicators: "));
        assertTrue(report, report.contains("== Decoder cross-check =="));
        assertTrue(report, report.contains("decoders agree"));
    }

    @Test public void progressiveReportMentionsTheScans() throws Exception {
        String report = new JpegDctPlugin().analyze(progressiveJpeg(TestImages.noise(64, 48, 14)));
        assertTrue(report, report.contains("progressive"));
        assertTrue(report, report.contains("scans"));
    }

    @Test public void recoversAJstegPayloadFromTheCoefficients() throws Exception {
        byte[] payload = "flag{dct_lsb_round_trip}".getBytes("ISO-8859-1");
        byte[] stego = embedJstegInLuma(TestImages.jpeg(TestImages.noise(64, 48, 15)), payload, false, 0);
        byte[] extracted = new JpegDctPlugin().extract(stego, "");
        assertNotNull(extracted);
        assertTrue("expected the payload, got \"" + new String(extracted, 0, Math.min(40, extracted.length), "ISO-8859-1") + "\"",
                startsWith(extracted, payload));
        String report = new JpegDctPlugin().analyze(stego);
        assertTrue(report, report.contains("FLAG"));
        assertTrue(report, report.contains("flag{dct_lsb_round_trip}"));
        assertTrue(report, report.contains("Payload: FLAG flag{dct_lsb_round_trip} @0x0"));
        assertTrue(report, report.contains("leading window"));
    }

    @Test public void fullyEmbeddedImageIsFlaggedAsLsbReplacement() throws Exception {
        byte[] source = TestImages.jpeg(TestImages.noise(96, 72, 16));
        byte[] clean = embedJstegInLuma(source, new byte[0], false, 0);
        byte[] stego = embedJstegInLuma(source, new byte[0], true, 31);
        String cleanReport = new JpegDctPlugin().analyze(clean);
        String stegoReport = new JpegDctPlugin().analyze(stego);
        double cleanChi = chiSquarePerDf(cleanReport), stegoChi = chiSquarePerDf(stegoReport);
        assertTrue("clean chi2/df = " + cleanChi, cleanChi > 4.0);
        assertTrue("stego chi2/df = " + stegoChi, stegoChi < 2.5);
        assertTrue("the histogram test must separate the two cases", cleanChi > 4 * stegoChi);
        assertTrue(stegoReport, stegoReport.contains("consistent with JSteg-style sequential LSB replacement"));
        assertTrue(cleanReport, cleanReport.contains("no sign of JSteg-style LSB replacement"));
        assertTrue(cleanReport, cleanReport.contains("Payload: none found"));
    }

    @Test public void keyedExtractionIsDeterministicAndPasswordDependent() throws Exception {
        byte[] stego = embedJstegInLuma(TestImages.jpeg(TestImages.noise(64, 48, 17)),
                "flag{keyed}".getBytes("ISO-8859-1"), false, 0);
        byte[] a = DctExtraction.extract(JpegDctDecoder.decode(stego), DctExtraction.Mode.KEYED, "hunter2");
        byte[] b = DctExtraction.extract(JpegDctDecoder.decode(stego), DctExtraction.Mode.KEYED, "hunter2");
        byte[] c = DctExtraction.extract(JpegDctDecoder.decode(stego), DctExtraction.Mode.KEYED, "other");
        assertArrayEquals(a, b);
        assertFalse("a different password must produce a different order", java.util.Arrays.equals(a, c));
    }

    // ------------------------------------------------------------------ plugin helpers

    /** chi2/df of the frame wide histogram test, read from the report like a user would. */
    private static double chiSquarePerDf(String report) {
        Matcher m = Pattern.compile("all components: pairs \\d+, chi2 [\\d.]+, chi2/df ([\\d.]+)").matcher(report);
        assertTrue("no frame wide chi2/df line in:\n" + report, m.find());
        return Double.parseDouble(m.group(1));
    }

    /**
     * Test fixture: decodes the JPEG, replaces the least significant bit of the magnitude of the non-zero
     * AC coefficients of the luma component (JSteg with the reference implementation's skip rule) and writes
     * the modified coefficients back as a single component JPEG.
     *
     * @param payload    bits are taken from here, most significant bit first
     * @param fillRandom continue with random bits after the payload (to saturate the image)
     */
    static byte[] embedJstegInLuma(byte[] jpeg, byte[] payload, boolean fillRandom, long seed) throws Exception {
        DctImage img = JpegDctDecoder.decode(jpeg);
        DctImage.Component y = img.components().get(0);
        DctImage.QuantTable quant = img.quantTableOf(y);
        int blocksPerLine = y.blocksPerLine(), blocksPerLineCount = y.blocksPerColumn(), blocks = y.blockCount();
        int[] coefficients = new int[blocks * 64];
        for (int b = 0; b < blocks; b++)
            for (int i = 0; i < 64; i++) coefficients[b * 64 + i] = y.coefficient(b, i);

        Random random = new Random(seed);
        int bit = 0, totalBits = payload.length * 8;
        boolean done = false;
        for (int b = 0; b < blocks && !done; b++) {
            for (int k = 1; k < 64; k++) {
                int natural = ZigZag.NATURAL[k];
                int v = coefficients[b * 64 + natural];
                if (v == 0 || v == 1 || v == -1) continue;   // the reference implementation skips these
                int wanted;
                if (bit < totalBits) wanted = (payload[bit / 8] >> (7 - bit % 8)) & 1;
                else if (fillRandom) wanted = random.nextInt(2);
                else { done = true; break; }
                bit++;
                int magnitude = (Math.abs(v) & ~1) | wanted;
                coefficients[b * 64 + natural] = v > 0 ? magnitude : -magnitude;
            }
        }
        return JpegTestWriter.grayscale(blocksPerLine * 8, blocksPerLineCount * 8, blocksPerLine,
                blocksPerLineCount, coefficients, quant.values(), 0);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------ decoder helpers

    /** Writes a progressive JPEG with the JDK encoder. */
    static byte[] progressiveJpeg(BufferedImage img) throws Exception {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = it.next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        assertTrue("JDK JPEG writer cannot write progressive files", p.canWriteProgressive());
        p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /** Coefficients with a large DC and small AC values so they fit the Annex K luminance tables. */
    static int[] randomCoefficients(int blocks, long seed, int dcRange, int acRange) {
        Random r = new Random(seed);
        int[] c = new int[blocks * 64];
        for (int b = 0; b < blocks; b++) {
            c[b * 64] = r.nextInt(2 * dcRange + 1) - dcRange;
            for (int k = 1; k < 64; k++)
                if (r.nextInt(3) == 0) c[b * 64 + ZigZag.NATURAL[k]] = r.nextInt(2 * acRange + 1) - acRange;
        }
        return c;
    }

    /** Fills a few AC coefficients of every block with values of the given magnitude. */
    private static void fillAc(int[] coefficients, int seed, int magnitude) {
        Random r = new Random(seed);
        for (int b = 0; b + 64 <= coefficients.length; b += 64)
            for (int k = 1; k < 64; k++)
                if (r.nextInt(4) == 0)
                    coefficients[b + ZigZag.NATURAL[k]] = (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(magnitude));
    }

    private static void assertCoefficientsEqual(int[] expected, DctImage.Component c, int blocks) {
        for (int b = 0; b < blocks; b++)
            for (int i = 0; i < 64; i++)
                assertEquals("block " + b + " coefficient " + i, expected[b * 64 + i], c.coefficient(b, i));
    }

    /** {mean absolute difference, maximum absolute difference} over the R/G/B channels. */
    static double[] pixelDiff(BufferedImage a, BufferedImage b) {
        assertNotNull("reference decode failed", b);
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        long sum = 0;
        int max = 0;
        long n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int d = Math.abs(((pa >> shift) & 0xff) - ((pb >> shift) & 0xff));
                    sum += d;
                    max = Math.max(max, d);
                    n++;
                }
            }
        return new double[]{sum / (double) n, max};
    }
}
