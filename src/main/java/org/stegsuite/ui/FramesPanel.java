package org.stegsuite.ui;

import org.stegsuite.core.image.FrameExtractor;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** GIF / APNG frame browser. */
public class FramesPanel extends JPanel implements FileAware {
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> list = new JList<>(listModel);
    private final ImageView view = new ImageView();
    private final JCheckBox composite = new JCheckBox("Composite (draw over previous frames)");
    private List<FrameExtractor.Frame> frames = new ArrayList<>();
    private AppState state;

    public FramesPanel() {
        super(new BorderLayout());
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            view.setImage(i >= 0 && i < frames.size() ? frames.get(i).image() : null, null);
        });
        composite.addActionListener(e -> reload());
        JButton save = new JButton("Save frame..."), saveAll = new JButton("Save all to folder...");
        save.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) Ui.saveImage(this, frames.get(i).image(), Ui.baseName(state) + "_frame" + i + ".png");
        });
        saveAll.addActionListener(e -> saveAll());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(composite); bar.add(new JLabel("Zoom:")); bar.add(ImageView.zoomBox(view)); bar.add(save); bar.add(saveAll);
        add(bar, BorderLayout.NORTH);
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(UiScale.dim(260, 100));
        add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ls, new JScrollPane(view)), BorderLayout.CENTER);
    }

    private void saveAll() {
        if (frames.isEmpty()) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            for (int i = 0; i < frames.size(); i++)
                ImageIO.write(frames.get(i).image(), "png", new File(fc.getSelectedFile(), Ui.baseName(state) + String.format("_frame%03d.png", i)));
            StatusBar.message("Saved " + frames.size() + " frames");
        } catch (Exception ex) { Ui.error(this, ex); }
    }

    private void reload() {
        listModel.clear();
        frames = new ArrayList<>();
        view.setImage(null, null);
        if (state == null) return;
        try {
            frames = FrameExtractor.extract(state.bytes(), composite.isSelected());
        } catch (Exception e) {
            listModel.addElement("Error: " + e.getMessage());
            return;
        }
        for (int i = 0; i < frames.size(); i++) {
            FrameExtractor.Frame f = frames.get(i);
            listModel.addElement(String.format("#%d  %dx%d @(%d,%d) %s", i, f.width(), f.height(), f.x(), f.y(), f.info()));
        }
        if (!frames.isEmpty()) list.setSelectedIndex(0);
    }

    @Override public void onFile(AppState s) { state = s; reload(); }
}
