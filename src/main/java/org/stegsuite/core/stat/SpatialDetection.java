package org.stegsuite.core.stat;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.List;

/** Runs RS and SPA on every colour channel of a (lossless) image. */
public final class SpatialDetection {
    private SpatialDetection() {}

    /** Estimates above this rate are reported as likely LSB embedding. */
    public static final double DETECTION_THRESHOLD = 0.10;

    public static final class ChannelResult {
        public final String channel;
        public final RsAnalysis.Result rs;
        public final SamplePairAnalysis.Result spa;

        ChannelResult(String channel, RsAnalysis.Result rs, SamplePairAnalysis.Result spa) {
            this.channel = channel;
            this.rs = rs;
            this.spa = spa;
        }

        /** Mean of the two estimates (NaN estimates ignored). */
        public double combined() {
            if (Double.isNaN(rs.rate)) return spa.rate;
            if (Double.isNaN(spa.rate)) return rs.rate;
            return (rs.rate + spa.rate) / 2;
        }
    }

    public static final class Report {
        public final List<ChannelResult> channels = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final int width, height;

        Report(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public String verdict() {
            ChannelResult best = null;
            for (ChannelResult c : channels)
                if (!Double.isNaN(c.combined()) && (best == null || c.combined() > best.combined())) best = c;
            if (best == null) return "no estimate (image too small?)";
            if (best.combined() >= DETECTION_THRESHOLD)
                return String.format("LSB replacement likely: channel %s, estimated embedding rate ~%.0f%% of samples"
                        + " (RS %.1f%%, SPA %.1f%%)", best.channel, 100 * best.combined(), 100 * best.rs.rate, 100 * best.spa.rate);
            return String.format("no evidence of LSB replacement (highest estimate %.1f%% in channel %s; covers usually"
                    + " stay below %.0f%%)", 100 * best.combined(), best.channel, 100 * DETECTION_THRESHOLD);
        }
    }

    /**
     * @param lossyHint true when the file is known to be lossy (JPEG): a warning is added because RS/SPA
     *                  model LSB replacement in uncompressed samples
     */
    public static Report analyze(BufferedImage img, boolean lossyHint, Progress progress) {
        if (progress == null) progress = Progress.NONE;
        int w = img.getWidth(), h = img.getHeight();
        Report report = new Report(w, h);
        if (lossyHint) report.warnings.add("lossy (JPEG) file: RS/SPA assume LSB replacement in uncompressed samples; "
                + "use the JPEG DCT tab instead - these numbers are not meaningful here");
        if (img.getColorModel() instanceof IndexColorModel)
            report.warnings.add("palette image: the estimates are computed on the RGB values of the palette entries, "
                    + "palette-index steganography (e.g. EzStego) is not modelled");
        if ((long) w * h < 64 * 64) report.warnings.add("small image: estimates are noisy below about 64x64 pixels");
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        int[][] ch = new int[3][w * h];
        boolean gray = true;
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            ch[0][i] = (p >> 16) & 0xff;
            ch[1][i] = (p >> 8) & 0xff;
            ch[2][i] = p & 0xff;
            if (gray && (ch[0][i] != ch[1][i] || ch[1][i] != ch[2][i])) gray = false;
        }
        String[] names = gray ? new String[]{"gray"} : new String[]{"R", "G", "B"};
        for (int c = 0; c < names.length; c++) {
            Progress sub = scaled(progress, c / (double) names.length, 1.0 / names.length);
            RsAnalysis.Result rs = RsAnalysis.analyze(ch[c], w, h, sub);
            sub.update(0.6);
            SamplePairAnalysis.Result spa = SamplePairAnalysis.analyze(ch[c], w, h, sub);
            sub.update(1.0);
            report.channels.add(new ChannelResult(names[c], rs, spa));
        }
        return report;
    }

    private static Progress scaled(Progress p, double offset, double span) {
        return new Progress() {
            @Override public void update(double f) { p.update(offset + span * Math.max(0, Math.min(1, f))); }
            @Override public boolean cancelled() { return p.cancelled(); }
        };
    }
}
