package org.stegsuite.report;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Format-independent report model written by {@link ReportWriter} as HTML, Markdown or JSON. */
public final class Report {
    public String title = "StegSuite report";
    /** File or folder the report is about. */
    public String subject = "";
    /** ISO-8601 local time. */
    public String generated = "";
    public String tool = "StegSuite 1.0";
    /** Suspicion score 0-100 (single-file reports), or null. */
    public Integer score;
    public String level;
    /** Key / value overview rows. */
    public final List<String[]> summary = new ArrayList<>();
    /** Noteworthy findings (score contributions). */
    public final List<String> findings = new ArrayList<>();
    /** Batch reports: one row per file. */
    public Table table;
    public final List<Section> sections = new ArrayList<>();

    public static final class Section {
        public final String title;
        /** Plain text (monospaced in HTML), may be empty. */
        public String text = "";
        public final List<Figure> figures = new ArrayList<>();
        public final List<Section> children = new ArrayList<>();
        /** Structured values for the JSON export (String, Number, Boolean, List, Map, null). */
        public final Map<String, Object> data = new LinkedHashMap<>();
        /** Initially expanded in HTML. */
        public boolean open = true;
        /** HTML anchor id. */
        public String id;

        public Section(String title) { this.title = title; }

        public Section text(String t) { text = t == null ? "" : t; return this; }

        public Section child(Section s) { children.add(s); return this; }

        public Section figure(String caption, BufferedImage image) {
            if (image != null) figures.add(new Figure(caption, image));
            return this;
        }

        public Section put(String key, Object value) { data.put(key, value); return this; }
    }

    public static final class Figure {
        public final String caption;
        public final BufferedImage image;

        public Figure(String caption, BufferedImage image) {
            this.caption = caption;
            this.image = image;
        }
    }

    public static final class Table {
        public final List<String> columns = new ArrayList<>();
        /** Cells: String, Number or null. */
        public final List<List<Object>> rows = new ArrayList<>();
        /** Per row: id of the detail section (HTML link), or null. */
        public final List<String> rowLinks = new ArrayList<>();
        /** Per row: thumbnail (HTML only), or null. */
        public final List<BufferedImage> thumbnails = new ArrayList<>();
    }
}
