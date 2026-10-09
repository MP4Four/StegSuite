package org.stegsuite.plugin.dct;

import org.stegsuite.core.jpeg.DctImage;
import org.stegsuite.core.jpeg.DctImageCache;
import org.stegsuite.plugin.JpegStegoPlugin;

/**
 * Bundled JPEG DCT-domain plugin.
 *
 * <p>{@link #analyze} decodes the Huffman-coded DCT coefficients (baseline, extended sequential and
 * progressive frames) and reports the frame/table structure, coefficient histograms, LSB-replacement
 * steganalysis (Westfeld/Pfitzmann chi-square over coefficient value pairs, parity balance) and the
 * payloads found in the different LSB extraction layouts.
 *
 * <p>{@link #extract} returns the best payload found by those layouts - JSteg and its common variants -
 * or, when nothing is recognised, the raw JSteg LSB stream so the bytes can still be examined. A password
 * additionally enables a keyed permutation of the JSteg coefficient positions.
 */
public final class JpegDctPlugin implements JpegStegoPlugin {

    @Override public String name() {
        return "DCT coefficients / JSteg";
    }

    @Override public String description() {
        return "DCT coefficient histograms + chi-square/parity steganalysis, LSB extraction (JSteg and variants)";
    }

    @Override public String analyze(byte[] jpegBytes) throws Exception {
        return DctAnalysis.report(jpegBytes);
    }

    @Override public byte[] extract(byte[] jpegBytes, String password) throws Exception {
        DctImage img = DctImageCache.get(jpegBytes);       // shared with Analyze, chart and heatmap
        return DctPayloadSearch.bestPayload(img, password);
    }
}
