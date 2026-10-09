package org.stegsuite.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/** Zoomable image canvas (nearest-neighbour) that reports hovered pixel info in the status bar. */
public class ImageView extends JPanel implements Scrollable {
    private BufferedImage img;
    private BufferedImage source; // pixel values reported on hover
    private double zoom = 1.0;
    /** Optional semi-transparent overlay: one pixel per cell of cellW x cellH image pixels. */
    private BufferedImage overlay;
    private int cellW = 1, cellH = 1;
    private double[][] overlayValues;

    public ImageView() {
        setBackground(new Color(0x40, 0x40, 0x40));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                if (img == null) return;
                int x = (int) (e.getX() / zoom), y = (int) (e.getY() / zoom);
                if (x < img.getWidth() && y < img.getHeight()) {
                    BufferedImage s = source != null ? source : img;
                    int p = s.getRGB(x, y);
                    String heat = "";
                    if (overlayValues != null) {
                        int r = y / cellH, c = x / cellW;
                        if (r < overlayValues.length && c < overlayValues[r].length)
                            heat = Double.isNaN(overlayValues[r][c]) ? "  heat p=-" : String.format("  heat p=%.3f", overlayValues[r][c]);
                    }
                    StatusBar.message(String.format("x=%d y=%d  ARGB=%02x %02x %02x %02x%s", x, y, p >>> 24, (p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff, heat));
                }
            }
        };
        addMouseMotionListener(ma);
    }

    public void setImage(BufferedImage img, BufferedImage source) {
        this.img = img; this.source = source;
        revalidate(); repaint();
    }

    public BufferedImage getImage() { return img; }

    /**
     * Sets (or clears, with null) an overlay drawn over the image; each overlay pixel covers
     * {@code cellW x cellH} image pixels. {@code values} (optional) are reported on hover.
     */
    public void setOverlay(BufferedImage overlay, int cellW, int cellH, double[][] values) {
        this.overlay = overlay;
        this.cellW = Math.max(1, cellW);
        this.cellH = Math.max(1, cellH);
        this.overlayValues = overlay == null ? null : values;
        repaint();
    }

    public BufferedImage getOverlay() { return overlay; }

    public void setZoom(double z) { zoom = z; revalidate(); repaint(); }

    @Override public Dimension getPreferredSize() {
        return img == null ? UiScale.dim(200, 200) : new Dimension((int) (img.getWidth() * zoom), (int) (img.getHeight() * zoom));
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (img == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("Drop an image here", UiScale.px(20), UiScale.px(30));
            return;
        }
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int w = (int) (img.getWidth() * zoom), h = (int) (img.getHeight() * zoom);
        g2.drawImage(img, 0, 0, w, h, null);
        if (overlay != null) {
            Shape clip = g2.getClip();
            g2.clipRect(0, 0, w, h);
            g2.drawImage(overlay, 0, 0, (int) Math.round(overlay.getWidth() * cellW * zoom),
                    (int) Math.round(overlay.getHeight() * cellH * zoom), null);
            g2.setClip(clip);
        }
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return UiScale.px(16); }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return UiScale.px(128); }
    @Override public boolean getScrollableTracksViewportWidth() { return false; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    /** Zoom selector shared by image panels. */
    public static JComboBox<String> zoomBox(ImageView v) {
        JComboBox<String> z = new JComboBox<>(new String[]{"25%", "50%", "100%", "200%", "400%", "800%"});
        z.setSelectedItem("100%");
        z.addActionListener(e -> v.setZoom(Integer.parseInt(((String) z.getSelectedItem()).replace("%", "")) / 100.0));
        return z;
    }
}
