package org.stegsuite.ui;

import org.stegsuite.core.image.StereogramSolver;

import javax.swing.*;
import java.awt.*;

/** Stegsolve-like stereogram solver: image compared with itself shifted horizontally by an adjustable offset. */
public class StereogramPanel extends JPanel implements FileAware {
    private final ImageView view = new ImageView();
    private final JSlider slider = new JSlider(0, 0, 0);
    private final JLabel offLabel = new JLabel("Offset: 0");
    private final JComboBox<StereogramSolver.Mode> mode = new JComboBox<>(StereogramSolver.Mode.values());
    private AppState state;

    public StereogramPanel() {
        super(new BorderLayout());
        JButton prev = new JButton("< Prev"), next = new JButton("Next >"), save = new JButton("Save view...");
        prev.setToolTipText("Offset - 1 (Alt+Left)");
        next.setToolTipText("Offset + 1 (Alt+Right)");
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        slider.addChangeListener(e -> refresh());
        slider.setPreferredSize(new Dimension(UiScale.px(360), slider.getPreferredSize().height));
        mode.addActionListener(e -> refresh());
        save.addActionListener(e -> Ui.saveImage(this, view.getImage(),
                Ui.baseName(state) + "_stereo_" + mode.getSelectedItem().toString().split(" ")[0].toLowerCase() + "_off" + slider.getValue() + ".png"));
        offLabel.setPreferredSize(new Dimension(UiScale.px(130), offLabel.getPreferredSize().height));
        JComboBox<String> zoom = ImageView.zoomBox(view);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(prev); bar.add(slider); bar.add(next); bar.add(offLabel); bar.add(mode);
        bar.add(new JLabel("Zoom:")); bar.add(zoom); bar.add(save);
        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(view), BorderLayout.CENTER);
        add(new JLabel(" Tip: Alt+Left / Alt+Right change the offset by 1; the hidden shape appears at the repeat width."),
                BorderLayout.SOUTH);
        Ui.installAltArrowNav(this, () -> step(-1), () -> step(1));
    }

    private void step(int d) {
        int n = slider.getMaximum() + 1;
        if (n <= 1) return;
        slider.setValue(((slider.getValue() + d) % n + n) % n);
    }

    public int offset() { return slider.getValue(); }

    private void refresh() {
        if (state == null || state.image() == null) { view.setImage(null, null); offLabel.setText("Offset: -"); return; }
        int w = state.image().getWidth();
        offLabel.setText("Offset: " + slider.getValue() + " / " + w);
        view.setImage(StereogramSolver.render(state.image(), slider.getValue(), (StereogramSolver.Mode) mode.getSelectedItem()), state.image());
    }

    @Override public void onFile(AppState s) {
        state = s;
        int w = s != null && s.image() != null ? s.image().getWidth() : 1;
        slider.setMaximum(Math.max(0, w - 1));
        String init = System.getProperty("stegsuite.stereoOffset");
        slider.setValue(init != null ? Math.min(w - 1, Integer.parseInt(init)) : 0);
        refresh();
    }
}
