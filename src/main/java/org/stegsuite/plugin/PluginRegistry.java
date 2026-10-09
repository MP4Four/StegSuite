package org.stegsuite.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Discovers {@link JpegStegoPlugin} implementations on the classpath. */
public final class PluginRegistry {
    private PluginRegistry() {}

    public static List<JpegStegoPlugin> jpegPlugins() {
        List<JpegStegoPlugin> l = new ArrayList<>();
        for (JpegStegoPlugin p : ServiceLoader.load(JpegStegoPlugin.class)) l.add(p);
        return l;
    }
}
