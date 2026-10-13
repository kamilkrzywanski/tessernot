package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.internal.image.Bgr;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotchedZTest {
    @Test
    void notchedZTakesADotAndASolidBarDoesNot() {
        assertNull(NotchedZ.paint(bar(true), "Z"));
        Bgr dotted = NotchedZ.paint(bar(false), "Z");
        assertNotNull(dotted);
        assertEquals(56, dotted.height());
        assertEquals(80, dotted.width());
        assertTrue(dark(dotted, 0, 16), "dot sits in the added band");
        assertTrue(dark(dotted, 16, dotted.height()), "the letter is still there");
        assertEquals("ChlebPszen-Żyt550g", NotchedZ.keep("ChlebPszen-Zyt550g", "ChlebPszen-żyt550g"));
        assertEquals("Żapłacono przelewem:", NotchedZ.keep("Zapłacono przelewem:", "żapłacono przelewem:"));
        assertNull(NotchedZ.keep("Zapłacono przelewem:", "Zapłacono przelewem:"));
        assertNull(NotchedZ.keep("SPRZEDAZ OPODATKOWANA C", "SPRŽEDAZ OPODATKOWANA C"));
        assertNull(NotchedZ.keep("Ziemia Kłodzka", "1 Ziemia Kłodzka"));
    }

    /** A 40px-tall Z. Solid top bar, or the same bar with a light gap in the middle. */
    private static Bgr bar(boolean solid) {
        int w = 80;
        int h = 40;
        byte[] data = new byte[w * h * 3];
        Arrays.fill(data, (byte) 255);
        for (int y = 8; y <= 28; y++) {
            for (int x = 16; x <= 52; x++) {
                boolean gap = !solid && y <= 9 && x >= 30 && x <= 38;
                if (!gap) {
                    int o = (y * w + x) * 3;
                    data[o] = data[o + 1] = data[o + 2] = 0;
                }
            }
        }
        return new Bgr(data, w, h);
    }

    private static boolean dark(Bgr image, int y0, int y1) {
        byte[] data = image.data();
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < image.width(); x++) {
                int o = (y * image.width() + x) * 3;
                if ((data[o] & 255) < 10) {
                    return true;
                }
            }
        }
        return false;
    }
}
