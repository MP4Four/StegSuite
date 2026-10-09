package org.stegsuite.ui;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.BorderLayout;

/** Global status bar: file info on the left, transient messages / pixel info on the right. */
public final class StatusBar extends JPanel {
    private static StatusBar instance;
    private final JLabel left = new JLabel("No file - drag & drop an image or use File > Open");
    private final JLabel right = new JLabel(" ");

    public StatusBar() {
        super(new BorderLayout());
        setBorder(new BevelBorder(BevelBorder.LOWERED));
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        instance = this;
    }

    public static void fileInfo(String s) { if (instance != null) SwingUtilities.invokeLater(() -> instance.left.setText(s)); }
    public static void message(String s) { if (instance != null) SwingUtilities.invokeLater(() -> instance.right.setText(s + "  ")); }
}
