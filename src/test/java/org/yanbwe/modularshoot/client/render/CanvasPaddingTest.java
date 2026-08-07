package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.FastColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link CompositeTextureBuilder#padCanvas}: the canvas
 * enlargement that keeps whole-gun outlines visible when the silhouette
 * touches the original canvas edge (描边画布扩展).
 */
class CanvasPaddingTest {

    private static final int TRANSPARENT = FastColor.ABGR32.color(0, 0, 0, 0);
    private static final int WHITE = FastColor.ABGR32.color(255, 255, 255, 255);

    private static int alpha(NativeImage img, int x, int y) {
        return FastColor.ABGR32.alpha(img.getPixelRGBA(x, y));
    }

    @Test
    void padAddsTransparentBorderAndShiftsContent() {
        // 8×8 source with an opaque 4×4 square at (2,2)-(5,5).
        NativeImage src = new NativeImage(8, 8, false);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                src.setPixelRGBA(x, y, TRANSPARENT);
            }
        }
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                src.setPixelRGBA(x, y, WHITE);
            }
        }

        NativeImage padded = CompositeTextureBuilder.padCanvas(src, 2, 2);
        try {
            assertEquals(12, padded.getWidth());
            assertEquals(12, padded.getHeight());

            // Content shifted to (4,4)-(7,7).
            assertEquals(255, alpha(padded, 4, 4));
            assertEquals(255, alpha(padded, 7, 7));
            // Border fully transparent.
            for (int y = 0; y < 12; y++) {
                assertEquals(0, alpha(padded, 0, y), "left border");
                assertEquals(0, alpha(padded, 11, y), "right border");
            }
            for (int x = 0; x < 12; x++) {
                assertEquals(0, alpha(padded, x, 0), "top border");
                assertEquals(0, alpha(padded, x, 11), "bottom border");
            }
            // Source untouched.
            assertEquals(8, src.getWidth());
            assertEquals(255, alpha(src, 2, 2));
            assertEquals(0, alpha(src, 0, 0));
        } finally {
            padded.close();
            src.close();
        }
    }

    @Test
    void zeroPadReturnsSameInstance() {
        NativeImage img = new NativeImage(4, 4, false);
        try {
            assertSame(img, CompositeTextureBuilder.padCanvas(img, 0, 0));
        } finally {
            img.close();
        }
    }

    @Test
    void edgeTouchingContentStaysOpaqueAfterPad() {
        // 16×16 fully opaque content — the case that previously clipped the
        // outer outline: after padding, the content occupies the interior and
        // the border stays transparent, giving the outline room.
        NativeImage src = new NativeImage(16, 16, false);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                src.setPixelRGBA(x, y, WHITE);
            }
        }
        NativeImage padded = CompositeTextureBuilder.padCanvas(src, 2, 2);
        try {
            assertEquals(20, padded.getWidth());
            assertEquals(255, alpha(padded, 2, 2));
            assertEquals(255, alpha(padded, 17, 17));
            assertEquals(0, alpha(padded, 0, 0));
            assertEquals(0, alpha(padded, 19, 19));
        } finally {
            padded.close();
            src.close();
        }
    }
}
