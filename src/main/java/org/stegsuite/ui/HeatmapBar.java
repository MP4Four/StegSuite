package org.stegsuite.ui;

import javax.swing.*;
import java.awt.*;

/** Heatmap toggle + cell size + opacity controls shared by the Bit planes and JPEG DCT tabs. */
final class HeatmapBar extends JPanel {
    final JCheckBox enabled;
    final JComboBox<Integer> size;
    final JSlider opacity = new JSlider(0, 100, 55);

    /**
     * @param sizes      selectable cell sizes
     * @param unit       label of the size selector, e.g. "block px" or "tile (8x8 blocks)"
     * @param onChange   called on any change
     */
    HeatmapBar(String title, Integer[] sizes, int defaultSize, String unit, Runnable onChange) {
        super(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));
        enabled = new JCheckBox(title);
        size = new JComboBox<>(sizes);
        size.setSelectedItem(defaultSize);
        opacity.setPreferredSize(UiScale.dim(110, 20));
        opacity.setToolTipText("Overlay opacity");
        add(enabled);
        add(new JLabel(unit));
        add(size);
        add(new JLabel("opacity"));
        add(opacity);
        enabled.addActionListener(e -> onChange.run());
        size.addActionListener(e -> onChange.run());
        opacity.addChangeListener(e -> { if (!opacity.getValueIsAdjusting()) onChange.run(); });
        setToolTipText("Red = pairs of values equalised (high chi-square p), as random LSB replacement leaves them");
    }

    int cellSize() { return (Integer) size.getSelectedItem(); }

    double alpha() { return opacity.getValue() / 100.0; }
}
