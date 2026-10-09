package org.stegsuite.ui;

import org.stegsuite.core.image.ImageCombiner;
import org.stegsuite.core.image.ImageUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/** Combine the current image (A) with a second image (B). */
public class CombinerPanel extends JPanel implements FileAware {
    private final ImageView view = new ImageView();
    private final JComboBox<ImageCombiner.Op> op = new JComboBox<>(ImageCombiner.Op.values());
    private final JLabel bLabel = new JLabel("Image B: (none)");
    private AppState state;
    private BufferedImage imgB;

    public CombinerPanel() {
        super(new BorderLayout());
        JButton chooseB = new JButton("Choose image B..."), save = new JButton("Save result...");
        chooseB.addActionListener(e -> {
            File f = Ui.chooseOpen(this);
            if (f == null) return;
            try {
                imgB = ImageUtil.decode(Files.readAllBytes(f.toPath()));
                if (imgB == null) throw new Exception("Not a readable image: " + f.getName());
                bLabel.setText("Image B: " + f.getName() + " (" + imgB.getWidth() + "x" + imgB.getHeight() + ")");
                refresh();
            } catch (Exception ex) { Ui.error(this, ex); }
        });
        op.addActionListener(e -> refresh());
        save.addActionListener(e -> Ui.saveImage(this, view.getImage(), Ui.baseName(state) + "_combined_" + ((ImageCombiner.Op) op.getSelectedItem()).name() + ".png"));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("A = current file")); bar.add(chooseB); bar.add(bLabel); bar.add(op);
        bar.add(new JLabel("Zoom:")); bar.add(ImageView.zoomBox(view)); bar.add(save);
        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(view), BorderLayout.CENTER);
    }

    private void refresh() {
        if (state == null || state.image() == null || imgB == null) { view.setImage(state == null ? null : state.image(), null); return; }
        view.setImage(ImageCombiner.combine(state.image(), imgB, (ImageCombiner.Op) op.getSelectedItem()), null);
    }

    @Override public void onFile(AppState s) { state = s; refresh(); }
}
