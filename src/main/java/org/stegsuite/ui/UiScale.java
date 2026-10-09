package org.stegsuite.ui;

import javax.swing.*;
import javax.swing.plaf.DimensionUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.IconUIResource;
import javax.swing.plaf.InsetsUIResource;
import javax.swing.plaf.UIResource;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Manual HiDPI support for Java 8.
 * <p>
 * Java 9+ scales Swing itself (JEP 263), so the automatic factor is 1.0 there. Java 8 renders at 1x, so on a
 * Windows display set to 150% / 200% everything looks tiny; in that case the automatic factor is
 * {@code screenDPI / 96}. Fonts, row heights, margins, scrollbar widths, check/radio icons and the hard-coded pixel
 * sizes in the panels are multiplied by the factor ({@link #px(int)}). Image zoom is independent of this.
 * <p>
 * Precedence: {@code -Dstegsuite.scale=1.5|150%|auto} &gt; saved preference (View &gt; UI scale) &gt; automatic.
 */
public final class UiScale {
    public static final String PROP = "stegsuite.scale";
    public static final String PREF_KEY = "uiScale";
    public static final double MIN = 1.0, MAX = 3.0;
    /** Explicit values (property / preference) may also shrink a bit. */
    public static final double MIN_EXPLICIT = 0.5, MAX_EXPLICIT = 4.0;

    private static double factor = 1.0;
    private static String source = "default";

    private UiScale() {}

    // ------------------------------------------------------------------ pure logic (unit tested)

    /** True for "9", "11", "21", ...; false for "1.8", "1.7", null or garbage. */
    public static boolean jvmScalesItself(String specVersion) {
        if (specVersion == null) return false;
        String s = specVersion.trim();
        if (s.startsWith("1.")) return false;
        try {
            int dot = s.indexOf('.');
            return Integer.parseInt(dot < 0 ? s : s.substring(0, dot)) >= 9;
        } catch (NumberFormatException e) { return false; }
    }

    /**
     * Automatic factor: 1.0 on Java 9+, else dpi/96 snapped to the nearest 25% step (the Windows display-scaling
     * steps; avoids odd factors like 104% from a 100-dpi X server) and clamped to [1, 3].
     */
    public static double autoFactor(String specVersion, int screenDpi) {
        if (jvmScalesItself(specVersion) || screenDpi <= 0) return 1.0;
        return clamp(Math.round(screenDpi / 96.0 * 4.0) / 4.0, MIN, MAX);
    }

    /**
     * Parses "1.5", "150%", "150" (values &gt; 8 are read as percent) or "auto". Returns {@code null} for auto,
     * empty or unparsable input, otherwise the value clamped to [0.5, 4].
     */
    public static Double parse(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto")) return null;
        boolean pct = s.endsWith("%");
        if (pct) s = s.substring(0, s.length() - 1).trim();
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return null;
            if (pct || d > 8) d /= 100.0;
            return round(clamp(d, MIN_EXPLICIT, MAX_EXPLICIT));
        } catch (NumberFormatException e) { return null; }
    }

    /** Resolves the factor: system property, then saved preference, then automatic. */
    public static double resolve(String sysProp, String pref, String specVersion, int screenDpi) {
        Double d = parse(sysProp);
        if (d != null) return d;
        d = parse(pref);
        if (d != null) return d;
        return autoFactor(specVersion, screenDpi);
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    // ------------------------------------------------------------------ runtime

    public static double factor() { return factor; }

    /** Where the factor came from: "-Dstegsuite.scale", "preference", "auto (Java 9+ scales itself)", "auto (N dpi)". */
    public static String source() { return source; }

    public static int px(int v) { return (int) Math.round(v * factor); }

    public static Dimension dim(int w, int h) { return new Dimension(px(w), px(h)); }

    public static Font mono(int size) { return new Font(Font.MONOSPACED, Font.PLAIN, px(size)); }

    public static String savedPreference() {
        try { return prefs().get(PREF_KEY, "auto"); } catch (Exception | Error e) { return "auto"; }
    }

    /** Saves "auto" or a factor such as "1.5". */
    public static void savePreference(String v) {
        try { Preferences p = prefs(); p.put(PREF_KEY, v); p.flush(); } catch (Exception | Error ignored) {}
    }

    private static Preferences prefs() { return Preferences.userRoot().node("org/stegsuite"); }

    public static int screenDpi() {
        try {
            if (GraphicsEnvironment.isHeadless()) return 96;
            return Toolkit.getDefaultToolkit().getScreenResolution();
        } catch (Exception | Error e) { return 96; }
    }

    /** Computes the factor. Call once at startup, before any component is created. */
    public static double init() {
        String spec = System.getProperty("java.specification.version");
        String sys = System.getProperty(PROP);
        String pref = savedPreference();
        int dpi = screenDpi();
        factor = resolve(sys, pref, spec, dpi);
        if (parse(sys) != null) source = "-D" + PROP;
        else if (parse(pref) != null) source = "preference";
        else if (jvmScalesItself(spec)) source = "auto (Java " + spec + " scales itself)";
        else source = "auto (" + dpi + " dpi)";
        return factor;
    }

    /** Scales the current look-and-feel defaults by {@link #factor()}. Call after setLookAndFeel, before creating components. */
    public static void applyToLookAndFeel() {
        if (Math.abs(factor - 1.0) < 1e-3) return;
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        List<Object> keys = new ArrayList<>(defaults.keySet());
        for (Object key : keys) {
            if (!(key instanceof String)) continue;
            String k = (String) key;
            Object v;
            try { v = UIManager.get(k); } catch (Exception | Error e) { continue; } // resolves lazy/active values
            Object nv = scaleValue(k, v);
            if (nv != null) UIManager.put(k, nv);
        }
        // JTable / JTree pick these up when set (BasicTableUI / BasicTreeUI); also set explicitly where we know them.
        if (!(UIManager.get("Table.rowHeight") instanceof Integer)) UIManager.put("Table.rowHeight", px(16));
    }

    private static final Set<String> INT_KEYS = new HashSet<>(Arrays.asList(
            "ScrollBar.width", "SplitPane.dividerSize", "Tree.rowHeight", "Table.rowHeight", "Tree.leftChildIndent", "Tree.rightChildIndent",
            "TabbedPane.textIconGap", "Slider.majorTickLength", "Slider.trackWidth", "ComboBox.buttonWidth",
            "Spinner.arrowButtonWidth", "Button.textIconGap", "CheckBox.textIconGap", "RadioButton.textIconGap",
            "MenuItem.minimumTextOffset", "ToolBar.separatorSize", "List.cellHeight", "ProgressBar.cellLength",
            "ProgressBar.cellSpacing", "TextField.caretWidth"));

    private static Object scaleValue(String k, Object v) {
        if (v instanceof FontUIResource || (v instanceof Font && !(v instanceof UIResource))) {
            Font f = (Font) v;
            return new FontUIResource(f.deriveFont((float) Math.round(f.getSize2D() * factor)));
        }
        if (v instanceof InsetsUIResource) {
            Insets i = (Insets) v;
            return new InsetsUIResource(px(i.top), px(i.left), px(i.bottom), px(i.right));
        }
        if (v instanceof DimensionUIResource) {
            Dimension d = (Dimension) v;
            return new DimensionUIResource(px(d.width), px(d.height));
        }
        if (v instanceof Integer && INT_KEYS.contains(k)) {
            int i = (Integer) v;
            return i > 0 ? px(i) : null;
        }
        if (v instanceof Icon && scaleIcon(k)) return new ScaledIcon((Icon) v, factor);
        return null;
    }

    private static boolean scaleIcon(String k) {
        return k.equals("CheckBox.icon") || k.equals("RadioButton.icon")
                || k.endsWith("MenuItem.checkIcon") || k.endsWith(".arrowIcon")
                || k.equals("Slider.horizontalThumbIcon") || k.equals("Slider.verticalThumbIcon")
                || k.equals("Tree.expandedIcon") || k.equals("Tree.collapsedIcon")
                || (k.startsWith("OptionPane.") && k.endsWith("Icon"))
                || (k.startsWith("FileChooser.") && k.endsWith("Icon"))
                || (k.startsWith("FileView.") && k.endsWith("Icon"))
                || (k.startsWith("Tree.") && (k.endsWith("openIcon") || k.endsWith("closedIcon") || k.endsWith("leafIcon")));
    }

    /** Paints a 1x icon through a scaled Graphics2D (vector-drawn L&amp;F icons stay crisp). */
    static final class ScaledIcon extends IconUIResource {
        private final Icon delegate;
        private final double s;

        ScaledIcon(Icon delegate, double s) { super(delegate); this.delegate = delegate; this.s = s; }

        @Override public int getIconWidth() { return (int) Math.round(delegate.getIconWidth() * s); }
        @Override public int getIconHeight() { return (int) Math.round(delegate.getIconHeight() * s); }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.scale(s, s);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                delegate.paintIcon(c, g2, 0, 0);
            } finally { g2.dispose(); }
        }
    }

    /** Scales a window size, clamped to the usable screen area. */
    public static Dimension windowSize(int w, int h) {
        Dimension d = dim(w, h);
        try {
            Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            d.width = Math.min(d.width, r.width);
            d.height = Math.min(d.height, r.height);
        } catch (Exception | Error ignored) {}
        return d;
    }

    /** Label for menus/About, e.g. "150%". */
    public static String percent(double f) { return Math.round(f * 100) + "%"; }
}
