package org.stegsuite.plugin;

/**
 * Extension point for JPEG DCT-domain analysis. StegSuite ships one implementation
 * ({@link org.stegsuite.plugin.dct.JpegDctPlugin}: coefficient statistics, LSB-replacement steganalysis and
 * JSteg-style extraction); further plugins can be added as described below.
 *
 * <p>To add a plugin:
 * <ol>
 *   <li>Implement this interface in a class with a public no-arg constructor.</li>
 *   <li>List the fully qualified class name in
 *       {@code META-INF/services/org.stegsuite.plugin.JpegStegoPlugin} (one per line).</li>
 *   <li>Put the jar on the classpath, e.g.
 *       {@code java -cp stegsuite-1.0.0-all.jar:myplugin.jar org.stegsuite.Main}.</li>
 * </ol>
 * The "JPEG DCT" tab discovers plugins with {@link java.util.ServiceLoader} and offers
 * Analyze / Extract buttons for each one. Both methods are called off the Swing event thread.
 */
public interface JpegStegoPlugin {

    /** Short display name shown in the plugin list. */
    String name();

    /** One-line description of what the plugin looks for. */
    default String description() { return ""; }

    /**
     * Inspect a JPEG file and return a human readable report (e.g. DCT coefficient histograms,
     * detection statistics). Must not modify {@code jpegBytes}.
     *
     * @param jpegBytes complete JPEG file contents
     * @return report text displayed in the tab
     */
    String analyze(byte[] jpegBytes) throws Exception;

    /**
     * Attempt to recover a payload from the JPEG.
     *
     * @param jpegBytes complete JPEG file contents
     * @param password  optional password/key supplied by the user, may be empty
     * @return recovered bytes (shown in the hex viewer), or {@code null} if nothing was found
     */
    byte[] extract(byte[] jpegBytes, String password) throws Exception;
}
