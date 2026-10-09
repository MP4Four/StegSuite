package org.stegsuite.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/** Small Swing helpers shared by panels. */
public final class Ui {
    private Ui() {}

    private static File lastDir = new File(System.getProperty("user.dir"));

    public static File chooseSave(Component parent, String suggested) {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setSelectedFile(new File(lastDir, suggested));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        lastDir = fc.getSelectedFile().getParentFile();
        return fc.getSelectedFile();
    }

    /** Save dialog for a report: HTML / Markdown / JSON filters, extension added from the chosen filter. */
    public static File chooseReport(Component parent, String baseName) {
        JFileChooser fc = new JFileChooser(lastDir);
        javax.swing.filechooser.FileNameExtensionFilter html = new javax.swing.filechooser.FileNameExtensionFilter("HTML report (*.html)", "html", "htm");
        javax.swing.filechooser.FileNameExtensionFilter md = new javax.swing.filechooser.FileNameExtensionFilter("Markdown report (*.md)", "md");
        javax.swing.filechooser.FileNameExtensionFilter json = new javax.swing.filechooser.FileNameExtensionFilter("JSON report (*.json)", "json");
        fc.addChoosableFileFilter(html);
        fc.addChoosableFileFilter(md);
        fc.addChoosableFileFilter(json);
        fc.setFileFilter(html);
        fc.setSelectedFile(new File(lastDir, baseName + ".html"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        if (org.stegsuite.report.ReportWriter.Format.forFile(f.getName()) == null) {
            String ext = fc.getFileFilter() == md ? ".md" : fc.getFileFilter() == json ? ".json" : ".html";
            f = new File(f.getParentFile(), f.getName() + ext);
        }
        lastDir = f.getParentFile();
        return f;
    }

    /** Folder chooser starting at the last used directory. */
    public static File chooseFolder(Component parent, File start) {
        JFileChooser fc = new JFileChooser(start != null && start.isDirectory() ? start : lastDir);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        lastDir = fc.getSelectedFile();
        return fc.getSelectedFile();
    }

    public static File chooseOpen(Component parent) {
        JFileChooser fc = new JFileChooser(lastDir);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        lastDir = fc.getSelectedFile().getParentFile();
        return fc.getSelectedFile();
    }

    public static void saveBytes(Component parent, byte[] data, String suggested) {
        if (data == null) return;
        File f = chooseSave(parent, suggested);
        if (f == null) return;
        try { Files.write(f.toPath(), data); StatusBar.message("Saved " + data.length + " bytes to " + f); }
        catch (Exception e) { error(parent, e); }
    }

    public static void saveImage(Component parent, BufferedImage img, String suggested) {
        if (img == null) return;
        File f = chooseSave(parent, suggested);
        if (f == null) return;
        try { ImageIO.write(img, "png", f); StatusBar.message("Saved image to " + f); }
        catch (Exception e) { error(parent, e); }
    }

    public static void error(Component parent, Exception e) {
        JOptionPane.showMessageDialog(parent, String.valueOf(e.getMessage() != null ? e.getMessage() : e), "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static String baseName(AppState s) {
        if (s == null || s.file() == null) return "output";
        String n = s.file().getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /**
     * Binds Alt+Left / Alt+Right to prev/next on {@code panel} (WHEN_IN_FOCUSED_WINDOW). The binding is disabled while
     * the panel is not showing, so several tabs can register the same keys.
     */
    public static void installAltArrowNav(JComponent panel, Runnable prev, Runnable next) {
        InputMap im = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, java.awt.event.InputEvent.ALT_DOWN_MASK), "nav-prev");
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.InputEvent.ALT_DOWN_MASK), "nav-next");
        panel.getActionMap().put("nav-prev", navAction(panel, prev));
        panel.getActionMap().put("nav-next", navAction(panel, next));
    }

    private static Action navAction(JComponent panel, Runnable r) {
        return new AbstractAction() {
            @Override public boolean isEnabled() { return panel.isShowing(); }
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }
}
