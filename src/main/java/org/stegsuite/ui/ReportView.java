package org.stegsuite.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text report split into collapsible sections at its "== Title ==" lines, with optional chart components
 * shown below the text of a section. Expand all / Collapse all in a small toolbar.
 */
public final class ReportView extends JPanel {
    private final SectionsPanel content = new SectionsPanel();
    private final JScrollPane scroll = new JScrollPane(content);
    private final List<SectionBox> boxes = new ArrayList<>();
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));

    public ReportView() {
        super(new BorderLayout());
        JButton expand = new JButton("Expand all"), collapse = new JButton("Collapse all");
        expand.addActionListener(e -> setAllExpanded(true));
        collapse.addActionListener(e -> setAllExpanded(false));
        toolbar.add(expand);
        toolbar.add(collapse);
        scroll.getVerticalScrollBar().setUnitIncrement(UiScale.px(16));
        add(toolbar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        // charts follow the visible width even when long text lines make the content wider than the view
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { content.revalidate(); }
        });
    }

    /** Holds a chart and sizes it to the visible part of the scroll pane rather than the full content width. */
    private static final class FitToViewport extends JPanel {
        private final JComponent chart;

        FitToViewport(JComponent chart) {
            super(null);
            this.chart = chart;
            setOpaque(false);
            add(chart);
        }

        @Override public Dimension getPreferredSize() {
            Insets in = getInsets();
            Dimension d = chart.getPreferredSize();
            return new Dimension(Math.min(d.width, UiScale.px(320)) + in.left + in.right, d.height + in.top + in.bottom);
        }

        @Override public void doLayout() {
            Insets in = getInsets();
            int w = getWidth() - in.left - in.right;
            JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (vp != null && vp.getView() != null) {
                int x = SwingUtilities.convertPoint(this, 0, 0, vp.getView()).x;
                w = Math.min(w, vp.getExtentSize().width - x - in.left - in.right);
            }
            chart.setBounds(in.left, in.top, Math.max(UiScale.px(240), w), getHeight() - in.top - in.bottom);
        }
    }

    /** One parsed section of a report text. */
    public static final class Section {
        public final String title;
        public final String text;

        Section(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /** Splits a report at lines of the form "== Title ==" (text before the first header becomes "Overview"). */
    public static List<Section> parse(String report) {
        List<Section> out = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : report.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("== ") && t.endsWith(" ==") && t.length() > 6) {
                flush(out, title, body);
                title = t.substring(3, t.length() - 3).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(out, title, body);
        return out;
    }

    private static void flush(List<Section> out, String title, StringBuilder body) {
        String text = body.toString().replaceAll("^\\n+", "").replaceAll("\\s+$", "");
        if (title == null && text.isEmpty()) return;
        out.add(new Section(title == null ? "Overview" : title, text));
    }

    /** Plain message without sections (status, errors). */
    public void setPlainText(String text) {
        setReport(text, Collections.<String, List<JComponent>>emptyMap(), true);
        toolbar.setVisible(false);
    }

    /**
     * @param extras components to show under the text of the section with that title
     */
    public void setReport(String report, Map<String, List<JComponent>> extras) {
        setReport(report, extras, false);
    }

    private void setReport(String report, Map<String, List<JComponent>> extras, boolean plain) {
        content.removeAll();
        boxes.clear();
        toolbar.setVisible(!plain);
        if (plain) {
            content.add(textArea(report));
        } else {
            for (Section s : parse(report)) {
                SectionBox b = new SectionBox(s, extras.get(s.title));
                boxes.add(b);
                content.add(b);
            }
        }
        content.add(Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
    }

    public void setAllExpanded(boolean expanded) {
        for (SectionBox b : boxes) b.setExpanded(expanded);
        content.revalidate();
    }

    /** Section titles in display order. */
    public List<String> titles() {
        List<String> t = new ArrayList<>();
        for (SectionBox b : boxes) t.add(b.section.title);
        return t;
    }

    /** Expands or collapses one section by title. */
    public void setExpanded(String title, boolean expanded) {
        for (SectionBox b : boxes) if (b.section.title.equals(title)) b.setExpanded(expanded);
        content.revalidate();
    }

    /** Whole report text (sections joined back). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (SectionBox b : boxes) sb.append("== ").append(b.section.title).append(" ==\n").append(b.section.text).append("\n\n");
        return sb.toString();
    }

    static JTextArea textArea(String text) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setFont(UiScale.mono(12));
        a.setBorder(BorderFactory.createEmptyBorder(UiScale.px(2), UiScale.px(8), UiScale.px(6), UiScale.px(4)));
        a.setAlignmentX(LEFT_ALIGNMENT);
        return a;
    }

    private static final class SectionBox extends JPanel {
        final Section section;
        private final JButton header = new JButton();
        private final JPanel body = new JPanel();
        private boolean expanded = true;

        SectionBox(Section s, List<JComponent> extras) {
            super(new BorderLayout());
            section = s;
            setAlignmentX(LEFT_ALIGNMENT);
            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setFocusPainted(false);
            header.setFont(header.getFont().deriveFont(Font.BOLD));
            header.addActionListener(e -> { setExpanded(!expanded); revalidate(); });
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.add(textArea(s.text));
            if (extras != null)
                for (JComponent c : extras) {
                    c.setAlignmentX(LEFT_ALIGNMENT);
                    JPanel wrap = new FitToViewport(c);
                    wrap.setAlignmentX(LEFT_ALIGNMENT);
                    wrap.setBorder(BorderFactory.createEmptyBorder(UiScale.px(2), UiScale.px(8), UiScale.px(8), UiScale.px(8)));
                    wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + UiScale.px(12)));
                    body.add(wrap);
                }
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setExpanded(true);
        }

        void setExpanded(boolean e) {
            expanded = e;
            body.setVisible(e);
            header.setText((e ? "\u25BE  " : "\u25B8  ") + section.title);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Vertical box that follows the viewport width unless its content is wider. */
    private static final class SectionsPanel extends JPanel implements Scrollable {
        SectionsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(UIManager.getColor("TextArea.background") == null ? Color.WHITE : UIManager.getColor("TextArea.background"));
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return UiScale.px(16); }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return o == SwingConstants.VERTICAL ? r.height : r.width; }
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() > getMinimumSize().width;
        }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public Dimension getMinimumSize() {
            int w = 0;
            for (Component c : getComponents()) if (c.isVisible()) w = Math.max(w, c.getPreferredSize().width);
            return new Dimension(w, super.getMinimumSize().height);
        }
    }

    /** Convenience for building the extras map. */
    public static Map<String, List<JComponent>> extras() { return new LinkedHashMap<>(); }
}
