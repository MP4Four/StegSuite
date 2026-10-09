package org.stegsuite.ui;

import org.stegsuite.core.image.BitPlaneRenderer;
import org.stegsuite.core.stat.LsbHeatmap;

import javax.swing.*;
import java.awt.*;

/** Stegsolve-like bit plane / colour transform browser. */
public class BitPlanePanel extends JPanel implements FileAware {
    private final ImageView view = new ImageView();
    private final JComboBox<String> mode = new JComboBox<>();
    private final JCheckBox invert = new JCheckBox("Invert result");
    private AppState state;
    private final HeatmapBar heatBar = new HeatmapBar("LSB heatmap", new Integer[]{8, 16, 32, 64, 128}, 32,
            "block px:", this::refreshHeatmap);
    private final TaskRunner heatRunner = new TaskRunner(null);
    /** Last computed map and the image / block size it belongs to. */
    private double[][] heat;
    private java.awt.image.BufferedImage heatImage;
    private int heatBlock;

    public BitPlanePanel() {
        super(new BorderLayout());
        for (BitPlaneRenderer.View v : BitPlaneRenderer.views()) mode.addItem(v.name());
        mode.setMaximumRowCount(20);
        JButton prev = new JButton("< Prev"), next = new JButton("Next >"), save = new JButton("Save view...");
        prev.setToolTipText("Previous view (Alt+Left)");
        next.setToolTipText("Next view (Alt+Right)");
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        save.addActionListener(e -> Ui.saveImage(this, view.getImage(),
                Ui.baseName(state) + "_" + String.valueOf(mode.getSelectedItem()).replaceAll("[^A-Za-z0-9]+", "_") + ".png"));
        mode.addActionListener(e -> refresh());
        invert.addActionListener(e -> refresh());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(prev); bar.add(mode); bar.add(next); bar.add(invert);
        bar.add(new JLabel("Zoom:")); bar.add(ImageView.zoomBox(view)); bar.add(save);
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        JPanel heatRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        heatRow.add(heatBar);
        heatRow.add(heatRunner.component());
        north.add(heatRow, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(view), BorderLayout.CENTER);
        // Alt+Left / Alt+Right; only active while this tab is showing (Stereogram tab uses the same keys)
        Ui.installAltArrowNav(this, () -> step(-1), () -> step(1));
        add(new JLabel(" Tip: Alt+Left / Alt+Right cycles views; hover shows original pixel values. LSB heatmap: red ="
                + " pairs of values equalised in the block (chi-square p high), typical of random LSB replacement."), BorderLayout.SOUTH);
    }

    private void step(int d) {
        int n = mode.getItemCount();
        mode.setSelectedIndex(((mode.getSelectedIndex() + d) % n + n) % n);
    }

    private void refresh() {
        if (state == null || state.image() == null) { view.setImage(null, null); return; }
        view.setImage(BitPlaneRenderer.render(state.image(), mode.getSelectedIndex(), invert.isSelected()), state.image());
    }

    /** Shows / hides the overlay; computes the block map in the background when the image or block size changed. */
    private void refreshHeatmap() {
        if (!heatBar.enabled.isSelected() || state == null || state.image() == null) {
            heatRunner.cancel();
            view.setOverlay(null, 1, 1, null);
            return;
        }
        final java.awt.image.BufferedImage img = state.image();
        final int block = heatBar.cellSize();
        if (heat != null && heatImage == img && heatBlock == block) {
            view.setOverlay(LsbHeatmap.toOverlay(heat, heatBar.alpha()), block, block, heat);
            return;
        }
        heatRunner.start("LSB heatmap (" + block + " px blocks)", progress -> LsbHeatmap.compute(img, block, progress), map -> {
            if (state == null || state.image() != img) return;
            heat = map;
            heatImage = img;
            heatBlock = block;
            if (heatBar.enabled.isSelected()) view.setOverlay(LsbHeatmap.toOverlay(map, heatBar.alpha()), block, block, map);
        }, cause -> Ui.error(this, cause instanceof Exception ? (Exception) cause : new RuntimeException(cause)));
    }

    @Override public void onFile(AppState s) {
        state = s;
        heat = null;
        heatImage = null;
        heatRunner.cancel();
        view.setOverlay(null, 1, 1, null);
        refresh();
        refreshHeatmap();
    }
}
