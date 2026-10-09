package org.stegsuite.ui;

import java.awt.image.BufferedImage;
import java.io.File;

/** Currently opened file; image is null when no ImageIO decoder understands the bytes. */
public final class AppState {
    private final File file;
    private final byte[] bytes;
    private final BufferedImage image;
    public AppState(File file, byte[] bytes, BufferedImage image) {
        this.file = file;
        this.bytes = bytes;
        this.image = image;
    }
    public File file() { return file; }
    public byte[] bytes() { return bytes; }
    public BufferedImage image() { return image; }
    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AppState)) return false;
        AppState o = (AppState) obj;
        return java.util.Objects.equals(file, o.file) && java.util.Objects.equals(bytes, o.bytes) && java.util.Objects.equals(image, o.image);
    }
    @Override public int hashCode() { return java.util.Objects.hash(file, bytes, image); }
    @Override public String toString() { return "AppState[" + "file=" + file + ", " + "bytes=" + bytes + ", " + "image=" + image + "]"; }
}
