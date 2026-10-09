package org.stegsuite.ui;

import org.stegsuite.core.image.ImageUtil;
import org.stegsuite.core.util.FileMagic;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Main window: menu, tabs, status bar and drag-and-drop file opening. */
public class MainFrame extends JFrame {
    private final List<FileAware> listeners = new ArrayList<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private BatchPanel batch;
    private AppState current;

    public MainFrame() {
        super("StegSuite - image forensics viewer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addTab("Bit planes", new BitPlanePanel());
        addTab("Data extract / scan", new ExtractPanel());
        addTab("File format", new FormatPanel());
        addTab("Frames", new FramesPanel());
        addTab("Combiner", new CombinerPanel());
        addTab("Stereogram", new StereogramPanel());
        addTab("JPEG DCT", new DctPanel());
        addTab("Detection", new DetectionPanel());
        batch = new BatchPanel(this::open);
        addTab("Batch scan", batch);
        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(new StatusBar(), BorderLayout.SOUTH);
        setJMenuBar(menu());
        setSize(UiScale.windowSize(1200, 800));
        setLocationRelativeTo(null);
        installDrop(getRootPane());
    }

    private void addTab(String name, JComponent c) {
        tabs.addTab(name, c);
        listeners.add((FileAware) c);
    }

    private JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open...");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        open.addActionListener(e -> { File f = Ui.chooseOpen(this); if (f != null) open(f); });
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> dispose());
        JMenuItem export = new JMenuItem("Export report...");
        export.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        export.addActionListener(e -> exportReport());
        JMenuItem batchScan = new JMenuItem("Batch scan folder...");
        batchScan.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        batchScan.addActionListener(e -> { tabs.setSelectedComponent(batch); batch.browse(); });
        JMenuItem batchExport = new JMenuItem("Export batch report...");
        batchExport.addActionListener(e -> {
            tabs.setSelectedComponent(batch);
            if (batch.results().isEmpty())
                JOptionPane.showMessageDialog(this, "Run a batch scan first (Batch scan tab).", "Export batch report", JOptionPane.INFORMATION_MESSAGE);
            else batch.exportReport();
        });
        file.add(open); file.addSeparator(); file.add(export); file.add(batchScan); file.add(batchExport);
        file.addSeparator(); file.add(exit);
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "StegSuite 1.0 - analysis-only image forensics viewer\n" +
                "Java " + System.getProperty("java.version") + ", UI scale " + UiScale.percent(UiScale.factor()) + " (" + UiScale.source() + ")\n" +
                "Bit planes, unified LSB extract + auto scan, file format analysis,\nframe browser (GIF/APNG disposal-aware), image combiner,\nstereogram solver, JPEG DCT analysis (coefficients, chi-square attack, calibration,\nJSteg extraction), RS / SPA detection, LSB heatmaps, batch folder scan\nand report export (HTML / Markdown / JSON)."));
        help.add(about);
        mb.add(file); mb.add(viewMenu()); mb.add(help);
        return mb;
    }

    private static final String[] SCALES = {"auto", "1.0", "1.25", "1.5", "1.75", "2.0"};

    private JMenu viewMenu() {
        JMenu view = new JMenu("View");
        JMenu scale = new JMenu("UI scale");
        String saved = UiScale.savedPreference();
        Double savedVal = UiScale.parse(saved);
        ButtonGroup g = new ButtonGroup();
        for (String v : SCALES) {
            Double d = UiScale.parse(v);
            String label = d == null ? "Auto (" + UiScale.percent(UiScale.autoFactor(
                    System.getProperty("java.specification.version"), UiScale.screenDpi())) + ")" : UiScale.percent(d);
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(label);
            it.setSelected(d == null ? savedVal == null : savedVal != null && Math.abs(savedVal - d) < 1e-3);
            it.addActionListener(e -> {
                UiScale.savePreference(v);
                String msg = "UI scale set to " + (d == null ? "Auto" : UiScale.percent(d))
                        + ". Restart StegSuite to apply.\nCurrent: " + UiScale.percent(UiScale.factor()) + " (" + UiScale.source() + ")";
                if (System.getProperty(UiScale.PROP) != null)
                    msg += "\nNote: -D" + UiScale.PROP + "=" + System.getProperty(UiScale.PROP) + " on the command line overrides this setting.";
                JOptionPane.showMessageDialog(this, msg, "UI scale", JOptionPane.INFORMATION_MESSAGE);
            });
            g.add(it);
            scale.add(it);
        }
        scale.addSeparator();
        JMenuItem cur = new JMenuItem("Current: " + UiScale.percent(UiScale.factor()) + " - " + UiScale.source());
        cur.setEnabled(false);
        scale.add(cur);
        view.add(scale);
        return view;
    }

    private void installDrop(Component root) {
        DropTargetAdapter dta = new DropTargetAdapter() {
            @Override public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) open(files.get(0));
                    e.dropComplete(true);
                } catch (Exception ex) { e.dropComplete(false); }
            }
        };
        apply(root, dta);
    }

    private void apply(Component c, DropTargetAdapter dta) {
        new DropTarget(c, DnDConstants.ACTION_COPY, dta, true);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) apply(ch, dta);
    }

    public void open(File f) {
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            java.awt.image.BufferedImage img = ImageUtil.decode(bytes);
            AppState s = new AppState(f, bytes, img);
            FileMagic.Sig sig = FileMagic.identify(bytes);
            setTitle("StegSuite - " + f.getName());
            StatusBar.fileInfo(String.format(" %s | %d bytes | %s | %s", f.getName(), bytes.length,
                    sig == null ? "unknown type" : sig.name(), img == null ? "not decodable as image" : img.getWidth() + "x" + img.getHeight()));
            current = s;
            for (FileAware l : listeners) l.onFile(s);
        } catch (Exception e) { Ui.error(this, e); }
    }

    public JTabbedPane tabs() { return tabs; }

    /** File &gt; Export report for the open file. */
    void exportReport() {
        if (current == null) {
            JOptionPane.showMessageDialog(this, "Open a file first.", "Export report", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new ExportReportDialog(this, current).setVisible(true);
    }

    BatchPanel batchPanel() { return batch; }
}
