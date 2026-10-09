package org.stegsuite.report;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes {@link Report}s as self-contained HTML, Markdown or JSON. */
public final class ReportWriter {
    private ReportWriter() {}

    public enum Format {
        HTML("html"), MARKDOWN("md"), JSON("json");
        public final String extension;
        Format(String ext) { extension = ext; }

        /** Format for a file name's extension (.html/.htm, .md/.markdown, .json), or null. */
        public static Format forFile(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".html") || n.endsWith(".htm")) return HTML;
            if (n.endsWith(".md") || n.endsWith(".markdown")) return MARKDOWN;
            if (n.endsWith(".json")) return JSON;
            return null;
        }
    }

    public static String render(Report r, Format f) {
        switch (f) {
            case HTML: return html(r);
            case MARKDOWN: return markdown(r);
            default: return json(r);
        }
    }

    public static void write(Report r, Format f, Path out) throws IOException {
        Files.write(out, render(r, f).getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Report r, Format f, OutputStream out) throws IOException {
        out.write(render(r, f).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ JSON

    public static String json(Report r) {
        return Json.write(toMap(r));
    }

    static Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", r.tool);
        m.put("title", r.title);
        m.put("subject", r.subject);
        m.put("generated", r.generated);
        m.put("score", r.score);
        m.put("level", r.level);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String[] kv : r.summary) summary.put(kv[0], kv[1]);
        m.put("summary", summary);
        m.put("findings", new ArrayList<Object>(r.findings));
        if (r.table != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("columns", new ArrayList<Object>(r.table.columns));
            List<Object> rows = new ArrayList<>();
            for (List<Object> row : r.table.rows) rows.add(new ArrayList<>(row));
            t.put("rows", rows);
            m.put("table", t);
        }
        List<Object> sections = new ArrayList<>();
        for (Report.Section s : r.sections) sections.add(toMap(s));
        m.put("sections", sections);
        return m;
    }

    private static Map<String, Object> toMap(Report.Section s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", s.title);
        if (s.id != null) m.put("id", s.id);
        m.put("text", s.text);
        if (!s.data.isEmpty()) m.put("data", s.data);
        if (!s.figures.isEmpty()) {
            List<Object> f = new ArrayList<>();
            for (Report.Figure fig : s.figures) f.add(fig.caption);
            m.put("figures", f);
        }
        if (!s.children.isEmpty()) {
            List<Object> c = new ArrayList<>();
            for (Report.Section ch : s.children) c.add(toMap(ch));
            m.put("children", c);
        }
        return m;
    }

    // ------------------------------------------------------------------ Markdown

    public static String markdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(mdInline(r.title)).append("\n\n");
        sb.append("Generated ").append(r.generated).append(" by ").append(r.tool).append("\n\n");
        if (!r.summary.isEmpty()) {
            sb.append("| | |\n|---|---|\n");
            for (String[] kv : r.summary) sb.append("| ").append(mdCell(kv[0])).append(" | ").append(mdCell(kv[1])).append(" |\n");
            sb.append('\n');
        }
        if (!r.findings.isEmpty()) {
            sb.append("**Findings**\n\n");
            for (String f : r.findings) sb.append("- ").append(mdInline(f)).append('\n');
            sb.append('\n');
        }
        if (r.table != null) {
            sb.append("## Files\n\n|");
            for (String c : r.table.columns) sb.append(' ').append(mdCell(c)).append(" |");
            sb.append("\n|");
            for (int i = 0; i < r.table.columns.size(); i++) sb.append("---|");
            sb.append('\n');
            for (List<Object> row : r.table.rows) {
                sb.append('|');
                for (Object o : row) sb.append(' ').append(mdCell(o == null ? "" : String.valueOf(o))).append(" |");
                sb.append('\n');
            }
            sb.append('\n');
        }
        for (Report.Section s : r.sections) mdSection(sb, s, 2);
        sb.append("_Figures (charts, thumbnails) are embedded in the HTML export only._\n");
        return sb.toString();
    }

    private static void mdSection(StringBuilder sb, Report.Section s, int level) {
        for (int i = 0; i < Math.min(level, 6); i++) sb.append('#');
        sb.append(' ').append(mdInline(s.title)).append("\n\n");
        if (!s.text.isEmpty()) sb.append(fence(s.text)).append("\n\n");
        for (Report.Figure f : s.figures) sb.append("_Figure: ").append(mdInline(f.caption)).append("_\n\n");
        for (Report.Section c : s.children) mdSection(sb, c, level + 1);
    }

    /** Code fence longer than any backtick run in the text. */
    private static String fence(String text) {
        int longest = 0, run = 0;
        for (char c : text.toCharArray()) { run = c == '`' ? run + 1 : 0; longest = Math.max(longest, run); }
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < Math.max(3, longest + 1); i++) f.append('`');
        return f + "text\n" + text + "\n" + f;
    }

    private static String mdInline(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
                .replace("<", "&lt;").replace(">", "&gt;").replace("[", "\\[").replace("]", "\\]").replace("\n", " ");
    }

    private static String mdCell(String s) {
        return mdInline(s).replace("|", "\\|");
    }

    // ------------------------------------------------------------------ HTML

    public static String html(Report r) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>").append(esc(r.title)).append("</title>\n");
        sb.append("<meta name=\"generator\" content=\"").append(esc(r.tool)).append("\">\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");
        sb.append("<header><h1>").append(esc(r.title)).append("</h1>\n<p class=\"meta\">Generated ").append(esc(r.generated))
                .append(" by ").append(esc(r.tool)).append(" &middot; analysis only</p>\n");
        if (r.score != null)
            sb.append("<div class=\"score ").append(esc(r.level)).append("\">Suspicion score <b>").append(r.score)
                    .append("</b>/100 (").append(esc(r.level)).append(")</div>\n");
        sb.append("</header>\n");
        if (!r.summary.isEmpty()) {
            sb.append("<table class=\"kv\">\n");
            for (String[] kv : r.summary) sb.append("<tr><th>").append(esc(kv[0])).append("</th><td>").append(esc(kv[1])).append("</td></tr>\n");
            sb.append("</table>\n");
        }
        if (!r.findings.isEmpty()) {
            sb.append("<h2>Findings</h2>\n<ul class=\"findings\">\n");
            for (String f : r.findings) sb.append("<li>").append(esc(f)).append("</li>\n");
            sb.append("</ul>\n");
        }
        if (r.table != null) table(sb, r.table);
        for (Report.Section s : r.sections) section(sb, s, 2);
        sb.append("<footer>Self-contained report: images are embedded as base64 PNG; sections are collapsible (&lt;details&gt;).")
                .append(" Scores rank files for review and are not probabilities.</footer>\n");
        if (r.table != null) sb.append("<script>\n").append(SORT_JS).append("</script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void table(StringBuilder sb, Report.Table t) {
        sb.append("<h2>Files</h2>\n<p class=\"hint\">Click a column header to sort; click a file name for its details.</p>\n");
        sb.append("<table class=\"files sortable\">\n<thead><tr>");
        boolean thumbs = false;
        for (Object o : t.thumbnails) if (o != null) { thumbs = true; break; }
        if (thumbs) sb.append("<th class=\"nosort\"></th>");
        for (String c : t.columns) sb.append("<th>").append(esc(c)).append("</th>");
        sb.append("</tr></thead>\n<tbody>\n");
        for (int i = 0; i < t.rows.size(); i++) {
            List<Object> row = t.rows.get(i);
            Object score = row.size() > 3 ? row.get(3) : null;
            String cls = score instanceof Integer ? ((Integer) score >= 60 ? "high" : (Integer) score >= 30 ? "medium" : "") : "";
            sb.append("<tr class=\"").append(cls).append("\">");
            if (thumbs) {
                sb.append("<td>");
                BufferedImage th = i < t.thumbnails.size() ? t.thumbnails.get(i) : null;
                if (th != null) sb.append("<img class=\"thumb\" alt=\"\" src=\"").append(dataUri(org.stegsuite.ui.Charts.thumbnail(th, 64, 64))).append("\">");
                sb.append("</td>");
            }
            for (int c = 0; c < row.size(); c++) {
                Object o = row.get(c);
                String text = o == null ? "" : String.valueOf(o);
                sb.append(o instanceof Number ? "<td class=\"num\" data-v=\"" + text + "\">" : "<td>");
                String link = c == 0 && i < t.rowLinks.size() ? t.rowLinks.get(i) : null;
                if (link != null) sb.append("<a href=\"#").append(esc(link)).append("\">").append(esc(text)).append("</a>");
                else sb.append(esc(text));
                sb.append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
    }

    private static void section(StringBuilder sb, Report.Section s, int level) {
        sb.append("<details").append(s.open ? " open" : "").append(s.id != null ? " id=\"" + esc(s.id) + "\"" : "")
                .append(" class=\"l").append(level).append("\">\n<summary>").append(esc(s.title)).append("</summary>\n<div class=\"body\">\n");
        if (!s.text.isEmpty()) sb.append("<pre>").append(esc(s.text)).append("</pre>\n");
        if (!s.figures.isEmpty()) {
            sb.append("<div class=\"figs\">\n");
            for (Report.Figure f : s.figures)
                sb.append("<figure><img alt=\"").append(esc(f.caption)).append("\" src=\"").append(dataUri(f.image))
                        .append("\"><figcaption>").append(esc(f.caption)).append("</figcaption></figure>\n");
            sb.append("</div>\n");
        }
        for (Report.Section c : s.children) section(sb, c, level + 1);
        sb.append("</div>\n</details>\n");
    }

    static String dataUri(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') sb.append(String.format("&#x%x;", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS =
            "body{font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;margin:24px auto;max-width:1180px;color:#222;padding:0 16px}\n"
            + "h1{font-size:22px;margin:0 0 4px}h2{font-size:18px;margin:22px 0 8px}.meta,.hint{color:#666;font-size:13px;margin:2px 0}\n"
            + ".score{display:inline-block;margin:10px 0;padding:6px 12px;border-radius:6px;background:#e8f1e8;border:1px solid #9c9}\n"
            + ".score.medium{background:#fff4dc;border-color:#e0b050}.score.high{background:#fde4e1;border-color:#d9534f}\n"
            + "table{border-collapse:collapse;font-size:13px;margin:6px 0}th,td{border:1px solid #ddd;padding:4px 8px;text-align:left;vertical-align:top}\n"
            + "th{background:#f4f4f4}table.kv th{width:170px}td.num{text-align:right;font-variant-numeric:tabular-nums}\n"
            + "table.files th{cursor:pointer;user-select:none}table.files tr.high td{background:#fdecea}table.files tr.medium td{background:#fff8e6}\n"
            + "img.thumb{max-width:64px;max-height:64px;display:block}\n"
            + "details{border:1px solid #ddd;border-radius:6px;margin:8px 0;background:#fff}details>summary{cursor:pointer;padding:7px 10px;font-weight:600;background:#f7f7f9;border-radius:6px}\n"
            + "details[open]>summary{border-bottom:1px solid #e5e5e5;border-radius:6px 6px 0 0}details .body{padding:6px 12px}\n"
            + "details.l3,details.l4,details.l5{margin-left:4px}details.l3>summary,details.l4>summary{font-weight:500;background:#fafafa}\n"
            + "pre{font-family:Consolas,'DejaVu Sans Mono',Menlo,monospace;font-size:12px;white-space:pre-wrap;word-break:break-word;margin:6px 0;line-height:1.35}\n"
            + ".figs{display:flex;flex-wrap:wrap;gap:12px}figure{margin:6px 0}figure img{max-width:100%;border:1px solid #ddd}\n"
            + "figcaption{font-size:12px;color:#555;margin-top:3px;max-width:900px}ul.findings li{margin:2px 0}\n"
            + "footer{margin-top:24px;color:#777;font-size:12px;border-top:1px solid #eee;padding-top:8px}\n";

    private static final String SORT_JS =
            "document.querySelectorAll('table.sortable th:not(.nosort)').forEach(function(th){th.addEventListener('click',function(){\n"
            + "var t=th.closest('table'),b=t.tBodies[0],i=Array.prototype.indexOf.call(th.parentNode.children,th),asc=th.dataset.asc!=='1';\n"
            + "th.dataset.asc=asc?'1':'0';var rows=Array.prototype.slice.call(b.rows);rows.sort(function(x,y){\n"
            + "var a=x.cells[i],c=y.cells[i],va=a.dataset.v,vc=c.dataset.v;var r=(va!==undefined&&vc!==undefined)?(parseFloat(va)-parseFloat(vc)):a.textContent.localeCompare(c.textContent);\n"
            + "return asc?r:-r;});rows.forEach(function(r){b.appendChild(r);});});});\n"
            + "document.querySelectorAll('a[href^=\"#\"]').forEach(function(a){a.addEventListener('click',function(){\n"
            + "var d=document.getElementById(a.getAttribute('href').slice(1));if(d){d.open=true;}});});\n";
}
