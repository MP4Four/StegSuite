package org.stegsuite.ui;

import org.stegsuite.core.util.HexDump;
import org.stegsuite.core.util.TextHeuristics;

import javax.swing.*;
import java.awt.*;

/** Reusable hex + ASCII viewer with a Save button. Shows at most {@link #MAX_SHOWN} bytes. */
public class HexViewer extends JPanel {
    public static final int MAX_SHOWN = 256 * 1024;
    private final JTextArea area = new JTextArea();
    private final JLabel info = new JLabel(" ");
    private byte[] data = new byte[0];
    private String saveName = "data.bin";

    public HexViewer() {
        super(new BorderLayout());
        area.setEditable(false);
        area.setFont(UiScale.mono(12));
        JButton save = new JButton("Save bytes...");
        save.addActionListener(e -> Ui.saveBytes(this, data, saveName));
        JButton text = new JButton("Copy as text");
        text.addActionListener(e -> {
            String s = new String(data, 0, Math.min(data.length, MAX_SHOWN), java.nio.charset.StandardCharsets.ISO_8859_1);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null);
            StatusBar.message("Copied " + Math.min(data.length, MAX_SHOWN) + " bytes as text");
        });
        JPanel top = new JPanel(new BorderLayout());
        top.add(info, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(4), 0));
        btns.add(text); btns.add(save);
        top.add(btns, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);
    }

    public void setData(byte[] d, String saveName) {
        this.data = d == null ? new byte[0] : d;
        this.saveName = saveName;
        int[] lp = TextHeuristics.longestPrintable(this.data);
        info.setText(String.format(" %d bytes%s | longest text run %d @0x%x", data.length,
                data.length > MAX_SHOWN ? " (showing first " + MAX_SHOWN + ")" : "", lp[1], lp[0]));
        area.setText(HexDump.dump(this.data, MAX_SHOWN));
        area.setCaretPosition(0);
    }

    public byte[] getData() { return data; }
}
