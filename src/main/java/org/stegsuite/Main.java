package org.stegsuite;

import org.stegsuite.ui.MainFrame;
import org.stegsuite.ui.UiScale;

import javax.swing.*;
import java.io.File;

/**
 * Entry point. Usage: java [-Dstegsuite.scale=1.5|auto] -jar stegsuite-1.0.0-all.jar [image] [--tab N]
 */
public final class Main {
    public static void main(String[] args) {
        String file = null;
        int tab = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--tab") && i + 1 < args.length) tab = Integer.parseInt(args[++i]);
            else file = args[i];
        }
        final String f = file;
        final int t = tab;
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
            // Java 8 has no HiDPI support: scale fonts/metrics manually (factor 1.0 on Java 9+ unless overridden).
            UiScale.init();
            UiScale.applyToLookAndFeel();
            MainFrame mf = new MainFrame();
            mf.setVisible(true);
            if (f != null) mf.open(new File(f));
            mf.tabs().setSelectedIndex(Math.max(0, Math.min(t, mf.tabs().getTabCount() - 1)));
        });
    }
}
