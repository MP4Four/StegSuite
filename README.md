# StegSuite – analysis-only image forensics viewer (Java 8+ / Swing)

Requirements: **Java 8 or newer** to run (class files are Java 8, major version 52; tested on Temurin 1.8.0_504 and OpenJDK 21).

Build: `mvn package`  →  `target/stegsuite-1.0.0-all.jar` (runs the headless JUnit tests too)
- With JDK 9+ the build uses `--release 8`, which also rejects any API newer than Java 8.
- With JDK 8 the `jdk8-build` profile activates automatically and uses `-source/-target 1.8`.

Run:   `java [-Dstegsuite.scale=1.5] -jar target/stegsuite-1.0.0-all.jar [image] [--tab N]`  (needs a non-headless JRE 8+; see "UI scale" below)

Tabs (index for `--tab`): 0 Bit planes · 1 Data extract / scan · 2 File format · 3 Frames · 4 Combiner ·
5 Stereogram · 6 JPEG DCT (coefficient analysis, chi-square attack, calibration, JSteg-style extraction) ·
7 Detection (RS + SPA LSB-replacement estimators) · 8 Batch scan (folder scan with suspicion score).
Drag & drop a file anywhere in the window to open it. **File > Export report...** (Ctrl+E) writes an HTML /
Markdown / JSON report of the current file; **File > Batch scan folder...** (Ctrl+B) opens the batch tab.

Everything is analysis only: no feature writes hidden data (the only files written are reports, extracted
streams, the Combiner/Stereogram view and a repaired copy of a PNG).

## UI scale (HiDPI)
Java 9+ scales Swing automatically on HiDPI displays; **Java 8 does not**, so on a Windows display set to
125%/150%/200% the Java 8 UI looks tiny. StegSuite therefore scales itself on Java 8:
- **Auto** (default): on Java 8 the factor is screen DPI / 96 (e.g. 144 dpi -> 150%), snapped to 25% steps and
  clamped to 100%-300%; on Java 9+ it is always 100% because the JVM already scales (no double scaling).
- **View > UI scale**: Auto / 100% / 125% / 150% / 175% / 200%, saved with `java.util.prefs` (Windows registry
  `HKCU\Software\JavaSoft\Prefs\org\stegsuite`, Linux `~/.java/.userPrefs/org/stegsuite`); takes effect after a restart.
  On Java 9+ an explicit value is applied on top of the JVM's own scaling.
- **Command line** (overrides the saved setting): `java -Dstegsuite.scale=1.5 -jar stegsuite-1.0.0-all.jar`
  (also accepts `150%` or `auto`; explicit values are clamped to 50%-400%).

Fonts (incl. the monospaced hex/report views), table/tree row heights, margins, scrollbar and split-pane widths,
check box / radio / menu icons and the panels' fixed sizes and default window size (clamped to the screen) are scaled.
Image zoom is independent of the UI scale. `View > UI scale` and `Help > About` show the active factor and its source.

## Bit planes
Stegsolve-style views (R/G/B/A planes 0-7, inversion, gray bits, random colour maps, ...).
`< Prev` / `Next >` or **Alt+Left / Alt+Right** cycle views.
**LSB heatmap** (checkbox): semi-transparent overlay of a per-block chi-square test on the LSB value pairs
(2i, 2i+1) of all channels; red = pairs equalised (high p, typical of random LSB replacement), blue = natural,
no colour = too few samples. Block size 8-128 px and opacity are adjustable; hover shows the block's p. Computed
in the background (cancelable). Smooth/flat covers make it reliable; noisy photos give scattered red blocks too.

## Detection
**Run RS + SPA** (background, cancelable) estimates the LSB-replacement embedding rate per channel (a single
"gray" row when R=G=B): RS analysis (Fridrich, Goljan, Du 2001; mask 0110, horizontal + vertical groups) and
Sample Pair Analysis (Dumitrescu, Wu, Wang 2003). The table shows both estimates, their mean and the RS group
counts; the verdict flags a channel when both estimates are above 10%. Warnings are shown for JPEG/lossy, palette
and very small images. Accuracy on synthetic 512x512 covers: within about +-3 points for rates 0-75%,
0.94-1.0 at 100% (tests: +-6 points at 0/25/50%, > 0.9 at 100%). Only LSB *replacement* is measured; LSB matching
(+-1) and short sequential payloads (estimate scales with the payload's share of the image) are not.

## Data extract / scan
Manual mode: per-channel bit masks, channel order, LSB/MSB within sample, xy/yx walk, byte packing.
Auto scan (cancelable, progress bar, runs in parallel), two levels:

| Mode  | Combinations                                                                                   | Bytes/combination |
|-------|------------------------------------------------------------------------------------------------|-------------------|
| QUICK | channels r,g,b,rgb,bgr (+a,rgba,abgr); b1-b4 and single planes 1,2,3,7; lsb/msb; xy/yx; packing | 256 KiB           |
| DEEP  | QUICK + b5,b6,b7,b8, every single plane 0-7, rbg/grb/gbr/brg (+argb,bgra), and a bounded mixed set: per channel b0/b1/b2 on rgb/bgr(/rgba) e.g. `b1r+b2g,rg,lsb,xy` | 512 KiB |

Every extracted stream is searched at **any offset** for flag patterns (`flag{}`, `ctf{}`, `xxxctf{}`, `key{}`),
file signatures (header at offset 0, or anywhere when at least 6 bytes of magic + plausible header fields match,
e.g. ZIP version/method, JFIF/Exif tags, RIFF type) and natural text (printable run at offset 0 >= 8 chars, or
anywhere >= 20 chars that is word-like or a long base64/hex token; gradient/flat-area runs are filtered out).
Results show the offset (`@0x..` and the Offset column) and are ranked FLAG > FILE@0 > embedded FILE > TEXT,
with a small penalty for exotic parameters (more bits, mixed masks, packLSB, yx, permuted orders) so the
simplest matching configuration comes first. Click a row to load its config, double-click to view/save the stream.

## File format
PNG chunks + CRC check + width/height brute force & fix, JPEG markers/EXIF/comments, BMP, GIF,
trailing data after end marker, binwalk-like embedded signature search. (The DCT layer of a JPEG is analysed
in the "JPEG DCT" tab.) The PNG report also warns when the decompressed IDAT size does not match IHDR.
For JPEGs the report lists every marker (fill bytes, stray bytes between segments and a missing EOI are
warned about), the quantization tables and the **estimated JPEG quality** (IJG scale; "standard tables" when the
luminance table is exactly an Annex K table scaled by libjpeg, otherwise a least-squares "~N" estimate); the
quality is also shown above the report. Trailing data is only reported after a real EOI (earlier versions
reported the EOI itself as 2 bytes of trailing data on every JPEG).

**PNG size repair...** (button; also used by the automatic IHDR brute force in the report) - for PNGs whose
IHDR width/height were edited to hide part of the image:
- *CRC search*: every (width, height) in configurable ranges (default 1..20000 x 1..20000) whose IHDR CRC
  equals the stored CRC; all matches are listed. CRC-32 is affine over GF(2), so the search builds a sorted
  table of height contributions and looks up each width (O(W+H log H), multithreaded, cancelable, progress
  bar). It does **not** compute 400 million CRCs: it returns exactly the matches a full sweep would (verified
  against a naive sweep for matching and non-matching targets), in about 4-7 ms warm / ~50 ms on the first call
  for the default 20000 x 20000 range (8 cores, JDK 8 and 21). For comparison, a naive sweep that really computes
  all 4e8 IHDR CRCs with `java.util.zip.CRC32` (no early exit) takes ~1.0 s on 8 threads and ~7 s (JDK 21) /
  ~9 s (JDK 8) on one thread.
- *IDAT size inference*: when the CRC was changed as well, the inflated IDAT size must equal
  `h * (1 + ceil(w * bitsPerPixel / 8))` (non-interlaced); every width that divides it is listed with the
  matching height, checked against the per-row filter bytes (0..4), stored width first.
- *Manual*: type a width/height; IHDR is rewritten with a recomputed CRC.
- Live preview of the selected candidate and **Save repaired PNG...** (the original file is never modified).

Credit: the width/height search and the "reverse" (IDAT-size) idea follow
[AabyssZG/Deformed-Image-Restorer](https://github.com/AabyssZG/Deformed-Image-Restorer) (Python), merged here
into the existing PNG CRC fix instead of a separate tool.

## Frames
GIF and APNG frame browser. Raw mode shows each frame on an empty canvas; **Composite** mode renders what a
viewer displays: GIF disposal none/keep, restoreToBackground (frame rect cleared to transparent, as browsers do)
and restoreToPrevious; APNG `dispose_op` NONE/BACKGROUND/PREVIOUS (PREVIOUS on the first frame = BACKGROUND)
and `blend_op` SOURCE/OVER. The list shows delay, dispose and blend for each frame.

## Combiner
XOR / AND / OR / ADD / SUB / interlace etc. of the current image with a second image.

## Stereogram
Stegsolve-style stereogram solver: each pixel is compared with the pixel `offset` columns to the right
(wrapping), as XOR (Stegsolve) or absolute difference. Slider, `< Prev` / `Next >`, **Alt+Left / Alt+Right**
step the offset; the hidden shape appears at the pattern's repeat width (e.g. offset 60 for
`samples/demo_stereogram.png`). Save view writes the current result as PNG.
`-Dstegsuite.stereoOffset=N` sets the initial offset.

## JPEG DCT
The bundled plugin `org.stegsuite.plugin.dct.JpegDctPlugin` (registered in
`META-INF/services/org.stegsuite.plugin.JpegStegoPlugin`) decodes the entropy-coded DCT coefficients itself
(`org.stegsuite.core.jpeg`: baseline, extended sequential and progressive Huffman frames, restart markers,
own inverse DCT with libjpeg-compatible fancy chroma upsampling) and offers:

- **Analyze** – frame/component/quantization/Huffman table structure with the IJG quality of each
  quantization table, per-component signed coefficient histograms, the Westfeld/Pfitzmann chi-square test
  over coefficient value pairs {|v|=2i, 2i+1} with its p-value, the LSB parity balance of the coefficients
  LSB replacement can actually change (|v| > 1), a leading-window variant of the test as a hint about short
  sequential payloads, the JSteg capacity, the payloads found by every extraction layout below, a summary
  that puts the payload and the histogram indicators together, and a cross-check of our inverse DCT against
  the JDK's JPEG decoder.
- **Chi-square attack** (tab): the Westfeld & Pfitzmann attack curve - p-value of the pair test over the first
  1..100% of the non-zero AC coefficients in JSteg read order (200 steps), for two's-complement pairs (original
  JSteg) and magnitude pairs (sign kept), drawn as a Java2D chart. A sequential payload shows p near 1 up to its
  end and then a drop to 0; the estimated end (shaded) and payload length in bytes are refined by a linear fit
  of sqrt(chi - df) and only reported when shifted control pairs on the same prefix stay natural and the full
  image has enough statistical power. Example: 400-byte JSteg payload -> estimated ~381 bytes.
- **Calibration** (Fridrich, Goljan, Hogea): the image is cropped by 4 px, re-compressed with the same luma
  quantization table, and the F5 shrinkage ratio beta is estimated from the (0,1), (1,0), (1,1) modes (pooled
  least squares over modes with >= 50 coefficients). Beta > 0.10 is flagged; the calibrated histogram is also
  used as the reference for the chi-square verdict. Clean JPEGs (q 50-90) give |beta| < 0.07; simulated F5
  shrinkage 0.1 / 0.25 / 0.5 is estimated as about 0.10-0.15 / 0.30-0.32 / 0.63-0.68 (overestimated at high rates).
- **Original JSteg length header**: the 5-bit size-of-length + length (MSB first) of Derek Upham's JSteg is
  parsed and, when plausible, the payload is sliced to the exact length (layout "JSteg header" in the list).
- **Block heatmap** (tab): per-tile chi-square p of the luminance coefficient pairs, drawn over the image with
  the 8x8 block grid; tile = 1-16 blocks, adjustable opacity, hover shows p.
- All DCT work runs in a background task with progress, Cancel and status-bar messages.
- **Charts in the report**: the Report tab is split into collapsible sections (click a header; Expand all /
  Collapse all). "JPEG DCT analysis" shows an **8x8 heatmap of each quantization table** (cell = step, darker =
  coarser, with its quality estimate) and "DCT coefficients" a **coefficient histogram** (non-zero AC values
  -12..12 per component, grey bands mark the magnitude pairs the chi-square test compares).
- **Decoder cross-check**: by default the first 256 rows are compared with the JDK decoder (about 25 ms on a
  24 MP image); **Full cross-check** compares the whole image on demand (about 0.4 s).
- **Extract** – the best payload found by those layouts (flags, file headers and natural text are searched
  at any offset, ranked like the bit-plane auto scan), or the raw JSteg LSB stream when nothing is
  recognised. The password field enables an extra keyed permutation of the coefficient positions.

Layouts: LSB of the non-zero AC coefficients (JSteg: all components, first component only, or skipping
`|v|<=1` like the reference `jsteg.c`), DC included, zeros included, raster instead of zig-zag coefficient
order, and the keyed permutation. Arithmetic-coded and 12-bit JPEGs, and CMYK/YCCK layouts, are reported as
unsupported rather than guessed at.

The chi-square/parity/calibration numbers are indicators, not proof: a heavily denoised or noisy image can look
"equalised" for other reasons - always confirm with a payload found by Extract. Limitations: the chi-square
attack needs a sequential payload and enough coefficients (scattered/keyed embedding like F5 or OutGuess gives
no curve drop); calibration uses Annex K Huffman tables and the luma component only and overestimates beta at
high rates; the JSteg header has no magic, so a random stream can occasionally look plausible. Further plugins can still be added: implement
`org.stegsuite.plugin.JpegStegoPlugin`, list the class in
`META-INF/services/org.stegsuite.plugin.JpegStegoPlugin` and run with
`java -cp stegsuite-1.0.0-all.jar:plugin.jar org.stegsuite.Main`.

### Decoding performance
The decoded coefficients and the reconstructed preview are cached per opened file (`DctImageCache`, 2 files),
so Analyze, Extract, the charts and the block heatmap decode once. The marker walk is shared between the decoder
and the File format report (`JpegSegments`). Huffman decoding uses a 9-bit lookup table (canonical bit-by-bit
fallback for longer codes; coefficients and warnings are identical, tested on baseline, progressive, restart,
truncated and corrupted files), the inverse DCT is the AAN float algorithm (libjpeg `jidctflt`; within 1 of the
exact reference transform, mean 0.0006 on random blocks), colour conversion writes the pixel buffer directly (no per-pixel
`getRGB`/`setRGB`), and calibration and the extraction layouts run in parallel.

JPEG DCT tab on a 6000x4000 q85 JPEG (4.3 MB), Analyze + preview, cold cache, 8 cores:

| | JDK 21 | JDK 8 (1.8.0_504) |
|-|-|-|
| before (round 3) | 5.6 s first run, 4.3-4.5 s warm | 6.7 s first run, 5.0-5.5 s warm |
| after | 2.1 s first run, 1.2-1.6 s warm | 2.1 s first run, 1.7-1.8 s warm |

(Coefficient decode 240 -> 165 ms, colour reconstruction 1.0-1.3 s -> 0.33 s, preview after Analyze 1.0 s -> 0;
reconstruction differs from the previous one by mean 0.002, max 2 levels, and from the JDK decoder by mean 0.03.)

## Batch scan
Tab 8 / **File > Batch scan folder...**: choose a folder, **Recursive**, the types to include (PNG / JPEG / BMP /
GIF / Other, detected from the content, not the extension) and whether to run the quick auto scan. The scan runs
in the background with progress and Cancel; files appear in the table as they finish and the table is sorted by
score at the end (click a header to re-sort). Per file:
- format warnings (CRC errors, IDAT size vs IHDR, non-standard chunks, trailing data, embedded signatures of a
  different type or after the end marker, flag strings in the raw bytes);
- quick auto scan hits (lossless images);
- JPEG: DCT verdict (payload found by the extraction layouts, histogram/pairs verdict, chi-square attack length,
  calibration beta) - quality is shown in the DCT column;
- PNG/BMP (and GIF): RS/SPA embedding-rate estimate (largest channel).

The details pane shows the indicators of the selected row; **double-click opens the file in the main window**.
Files over 256 MiB are skipped; auto scan and RS/SPA are skipped for images above 50 megapixels.
**Export batch report...** writes the table (with thumbnails in HTML) and a collapsed section per file.

**Suspicion score** (0-100, a ranking aid, not a probability): sum of fixed weights, capped at 100.

| Indicator | Weight |
|-|-|
| trailing data after the end marker | 30 |
| embedded file signature | 20 |
| flag string in the raw bytes | 40 |
| PNG CRC error / IDAT size mismatch / non-standard chunk / other warning | 25 / 20 / 10 / 5 |
| auto scan hit FLAG / FILE / TEXT (best hit) | 50 / 35 / 15 |
| DCT payload: flag / strong / weak | 60 / 40 / 20 |
| DCT pairs equalised (JSteg-like) / weak pair sign | 30 / 10 |
| chi-square attack finds a sequential payload | 30 |
| calibration beta > 0.10 (F5-like) | 25 |
| RS/SPA rate r >= 10% | min(40, 20 + 40 r) |

Levels: high >= 60, medium >= 30, low > 0.

## Report export
**File > Export report...** (Ctrl+E) analyses the current file in the background (all tabs' conclusions:
summary and score, bit-plane images and LSB heatmap, File format report, auto scan, JPEG DCT report with the
chi-square attack, coefficient histogram and quantization heatmap charts, RS/SPA, frame count) and writes:
- **HTML** - one self-contained file: charts and thumbnails are inline base64 PNGs, sections are `<details>`
  (collapsible without JavaScript), batch tables are sortable (small inline script). Example:
  `samples/report_example.html`.
- **Markdown** - the same text sections (figures are omitted, a note says so).
- **JSON** - machine-readable: title, score, level, summary, findings, batch table and nested sections with
  their data fields (figure captions only).

The format follows the file extension chosen in the dialog (.html / .md / .json). Options: include the auto
scan, full decoder cross-check.

## Samples / screenshots
`samples/`: `report_example.html` (exported report of a JPEG with a JSteg flag), `demo_lsb_trailing.png` (LSB flag + trailing data), `demo_stereogram.png` (random-dot stereogram,
period 60), `demo_anim.gif` (3 frames with keep / restoreToBackground disposal).
`screenshots/`: one PNG per main tab, captured under Xvfb; `java8_scale200.png` = Java 8 with `-Dstegsuite.scale=2` on a 2560x1600 screen.
Detection features: `detection_tab.png` (RS + SPA on a 50% LSB image), `dct_chi_square_chart.png` (400-byte
JSteg payload), `dct_report.png`, `dct_block_heatmap.png` (JSteg in the top half), `bitplanes_heatmap.png`
(LSB in the top-left 256x256), `file_format_png_crc.png`, `png_repair_crc_search.png`, `png_repair_idat_inference.png`.
Batch scan and reports: `batch_scan.png` (batch tab after scanning a mixed folder), `dct_report_charts.png`
(collapsible DCT report with the coefficient histogram), `file_format_jpeg_quality.png`,
`report_html_single.png`, `report_html_dct_charts.png` and `report_html_batch.png` (exported HTML rendered by
headless Chrome).

## Limitations
- The suspicion score is a heuristic sum of indicator weights; it ranks files for review and has false
  positives (e.g. a weak auto scan TEXT hit on noisy/synthetic images, +15) and false negatives (LSB matching,
  keyed or scattered JPEG embedding with a small payload).
- The batch scan analyses files one after another (each file's analysis is internally parallel).
- Markdown reports contain no images; JSON lists figure captions only.
- The default decoder cross-check covers the first 256 rows; use Full cross-check for the whole image.
- The DCT cache keeps the last 2 files.
