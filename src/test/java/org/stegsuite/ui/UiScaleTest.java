package org.stegsuite.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class UiScaleTest {
    private static final double E = 1e-9;

    @Test public void detectsJvmScaling() {
        assertFalse(UiScale.jvmScalesItself("1.8"));
        assertFalse(UiScale.jvmScalesItself("1.7"));
        assertFalse(UiScale.jvmScalesItself(null));
        assertFalse(UiScale.jvmScalesItself("garbage"));
        assertTrue(UiScale.jvmScalesItself("9"));
        assertTrue(UiScale.jvmScalesItself("11"));
        assertTrue(UiScale.jvmScalesItself("21"));
        assertTrue(UiScale.jvmScalesItself("25"));
    }

    @Test public void autoOnJava8UsesDpi() {
        assertEquals(1.0, UiScale.autoFactor("1.8", 96), E);
        assertEquals(1.25, UiScale.autoFactor("1.8", 120), E);
        assertEquals(1.5, UiScale.autoFactor("1.8", 144), E);
        assertEquals(1.75, UiScale.autoFactor("1.8", 168), E);
        assertEquals(2.0, UiScale.autoFactor("1.8", 192), E);
        assertEquals(1.0, UiScale.autoFactor("1.8", 100), E);  // snapped to 25% steps
        assertEquals(1.5, UiScale.autoFactor("1.8", 150), E);
        assertEquals(1.0, UiScale.autoFactor("1.8", 72), E);   // clamped low (e.g. macOS reports 72)
        assertEquals(3.0, UiScale.autoFactor("1.8", 600), E);  // clamped high
        assertEquals(1.0, UiScale.autoFactor("1.8", 0), E);    // bogus dpi
    }

    @Test public void autoOnJava9PlusIsOne() {
        assertEquals(1.0, UiScale.autoFactor("11", 192), E);
        assertEquals(1.0, UiScale.autoFactor("21", 144), E);
    }

    @Test public void parsesValues() {
        assertNull(UiScale.parse(null));
        assertNull(UiScale.parse(""));
        assertNull(UiScale.parse("auto"));
        assertNull(UiScale.parse(" AUTO "));
        assertNull(UiScale.parse("abc"));
        assertNull(UiScale.parse("-1"));
        assertNull(UiScale.parse("NaN"));
        assertEquals(1.5, UiScale.parse("1.5"), E);
        assertEquals(1.5, UiScale.parse("150%"), E);
        assertEquals(1.25, UiScale.parse("125"), E);
        assertEquals(2.0, UiScale.parse("2"), E);
        assertEquals(3.0, UiScale.parse("300"), E);
        assertEquals(4.0, UiScale.parse("900%"), E);  // clamped to max
        assertEquals(0.5, UiScale.parse("0.1"), E);   // clamped to min
    }

    @Test public void precedence() {
        assertEquals(2.0, UiScale.resolve("2", "1.5", "1.8", 144), E);        // -D wins
        assertEquals(1.5, UiScale.resolve(null, "1.5", "1.8", 192), E);       // preference
        assertEquals(1.25, UiScale.resolve("auto", "1.25", "21", 96), E);     // -D auto falls through to preference
        assertEquals(2.0, UiScale.resolve(null, "auto", "1.8", 192), E);      // auto, Java 8
        assertEquals(1.0, UiScale.resolve(null, "auto", "17", 192), E);       // auto, Java 9+
        assertEquals(1.75, UiScale.resolve("bad", null, "1.8", 168), E);      // unparsable -> auto
    }
}
