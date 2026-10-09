package org.stegsuite.report;

import org.junit.BeforeClass;
import org.junit.Test;
import org.stegsuite.JpegDctTestAccess;
import org.stegsuite.JpegRobustnessTest;
import org.stegsuite.core.stat.SyntheticCovers;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Single-file report export as JSON / HTML / Markdown. */
public class ReportExportTest {

    static FileAnalysis.Result jpeg, png;
    static Report jpegReport, pngReport;

    @BeforeClass public static void analyse() throws Exception {
        byte[] cover = JpegRobustnessTest.jpegAtQuality(SyntheticCovers.smoothCover(256, 256, 8), 0.9f);
        byte[] stego = JpegDctTestAccess.embedOriginalJstegWithHeader(cover, "flag{report_export}".getBytes("ISO-8859-1"));
        FileAnalysis.Options o = new FileAnalysis.Options();
        o.keepImage = true;
        jpeg = FileAnalysis.analyze(new File("stego \"<q>\" & co.jpg"), stego, o, null);
        jpegReport = ReportBuilder.single(jpeg);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(SyntheticCovers.withRandomLsb(SyntheticCovers.smoothCover(128, 96, 3, 4.0), 0.6, 2), "png", bo);
        png = FileAnalysis.analyze(new File("lsb.png"), bo.toByteArray(), o, null);
        pngReport = ReportBuilder.single(png);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { assertTrue(String.valueOf(o), o instanceof Map); return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { assertTrue(String.valueOf(o), o instanceof List); return (List<Object>) o; }

    static List<String> titles(List<Object> sections) {
        List<String> t = new ArrayList<>();
        for (Object s : sections) t.add((String) obj(s).get("title"));
        return t;
    }

    @Test public void jsonExportParses() {
        for (Report r : new Report[]{jpegReport, pngReport}) {
            Map<String, Object> m = obj(MiniJson.parse(ReportWriter.json(r)));
            assertEquals(r.title, m.get("title"));
            assertEquals(r.subject, m.get("subject"));
            assertEquals((long) r.score, ((Number) m.get("score")).longValue());
            assertEquals(r.level, m.get("level"));
            assertEquals(r.findings, m.get("findings"));
            List<String> t = titles(list(m.get("sections")));
            assertTrue(t.toString(), t.containsAll(Arrays.asList("Summary", "File format", "Data extract / auto scan")));
            assertTrue(obj(m.get("summary")).size() > 0);
        }
        Map<String, Object> m = obj(MiniJson.parse(ReportWriter.json(jpegReport)));
        assertTrue(m.get("subject").toString().contains("stego \"<q>\" & co.jpg"));
        List<Object> sections = list(m.get("sections"));
        assertTrue(titles(sections).contains("JPEG DCT"));
        Map<String, Object> dct = null;
        for (Object s : sections) if ("JPEG DCT".equals(obj(s).get("title"))) dct = obj(s);
        assertTrue(dct.get("text").toString(), String.valueOf(dct).contains("flag{report_export}"));
        assertTrue(titles(list(dct.get("children"))).contains("Full DCT report"));
        assertTrue(titles(list(obj(MiniJson.parse(ReportWriter.json(pngReport))).get("sections"))).contains("Detection (RS / SPA)"));
    }

    @Test public void jsonWriterRoundTripsTrickyValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        String tricky = "quote \" backslash \\ slash / nl \n tab \t cr \r nul \u0000 bell \u0007 unicode \u00e9\u4e2d\u6587 \ud83d\ude00 </script>";
        m.put("s", tricky);
        m.put("int", 42);
        m.put("neg", -7L);
        m.put("real", 0.125);
        m.put("whole", 3.0);
        m.put("nan", Double.NaN);
        m.put("bool", true);
        m.put("nil", null);
        m.put("list", Arrays.asList(1, "two", Collections.emptyList(), Collections.emptyMap()));
        m.put("ints", new int[]{1, 2, 3});
        Map<String, Object> back = obj(MiniJson.parse(Json.write(m)));
        assertEquals(tricky, back.get("s"));
        assertEquals(42L, back.get("int"));
        assertEquals(-7L, back.get("neg"));
        assertEquals(0.125, back.get("real"));
        assertEquals(3.0, back.get("whole"));
        assertNull(back.get("nan"));
        assertTrue(back.containsKey("nil"));
        assertEquals(Boolean.TRUE, back.get("bool"));
        assertEquals(Arrays.asList(1L, "two", Collections.emptyList(), Collections.emptyMap()), back.get("list"));
        assertEquals(Arrays.asList(1L, 2L, 3L), back.get("ints"));
    }

    @Test public void htmlExportHasTheSectionsAndInlineFigures() {
        String html = ReportWriter.html(jpegReport);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.trim().endsWith("</html>"));
        for (String t : new String[]{"Summary", "Bit planes", "File format", "Data extract / auto scan", "JPEG DCT",
                "Full DCT report", "Not included"})
            assertTrue(t, html.contains("<summary>" + ReportWriter.esc(t) + "</summary>"));
        assertEquals(count(html, "<details"), count(html, "</details>"));
        assertTrue(count(html, "<details") >= 6);
        assertTrue(html.contains("<details class=") || html.contains("<details open"));
        // figures are self-contained: base64 PNG data URIs, no external references
        Matcher img = Pattern.compile("<img [^>]*src=\"([^\"]*)\"").matcher(html);
        int images = 0;
        while (img.find()) {
            images++;
            String src = img.group(1);
            assertTrue(src.substring(0, Math.min(40, src.length())), src.startsWith("data:image/png;base64,"));
            byte[] png = Base64.getDecoder().decode(src.substring("data:image/png;base64,".length()));
            assertEquals((byte) 0x89, png[0]);
            assertEquals('P', png[1]);
        }
        assertTrue("figures: " + images, images >= 4);              // planes, chi-square, histogram, quant tables
        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("href=\"http"));
        // the file name is escaped
        assertTrue(html.contains("stego &quot;&lt;q&gt;&quot; &amp; co.jpg"));
        assertFalse(html.contains("<q>\""));
        assertTrue(html.contains("flag{report_export}"));
        String pngHtml = ReportWriter.html(pngReport);
        assertTrue(pngHtml.contains("<summary>Detection (RS / SPA)</summary>"));
        assertFalse(pngHtml.contains("<summary>JPEG DCT</summary>"));
    }

    @Test public void markdownExportHasTheSections() {
        String md = ReportWriter.markdown(jpegReport);
        assertTrue(md.startsWith("# "));
        for (String t : new String[]{"## Summary", "## File format", "## JPEG DCT", "### Full DCT report"})
            assertTrue(t, md.contains("\n" + t + "\n"));
        assertTrue(md.contains("flag{report_export}"));
        assertEquals(0, count(md, "```") % 2);
    }

    @Test public void formatIsChosenByExtension() {
        assertEquals(ReportWriter.Format.HTML, ReportWriter.Format.forFile("a.HTML"));
        assertEquals(ReportWriter.Format.HTML, ReportWriter.Format.forFile("a.htm"));
        assertEquals(ReportWriter.Format.MARKDOWN, ReportWriter.Format.forFile("x.md"));
        assertEquals(ReportWriter.Format.JSON, ReportWriter.Format.forFile("x.json"));
    }

    static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
