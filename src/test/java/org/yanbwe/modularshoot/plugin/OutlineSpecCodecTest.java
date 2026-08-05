package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link OutlineSpec} stroke specification and the
 * {@link OverlayBlend} blending-mode enum.
 */
class OutlineSpecCodecTest {

    private static OutlineSpec parse(String json) {
        return OutlineSpec.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void parsesFullSpec() {
        OutlineSpec spec = parse("{\"color\": [1.0, 0.0, 0.0], \"alpha\": 0.8, \"width\": 2}");
        assertEquals(1.0F, spec.color().x);
        assertEquals(0.0F, spec.color().y);
        assertEquals(0.0F, spec.color().z);
        assertEquals(0.8F, spec.alpha());
        assertEquals(2, spec.width());
    }

    @Test
    void defaultsAlphaAndWidth() {
        OutlineSpec spec = parse("{\"color\": [0.5, 0.5, 0.5]}");
        assertEquals(1.0F, spec.alpha());
        assertEquals(1, spec.width());
    }

    @Test
    void rejectsWrongColorArity() {
        assertThrows(AssertionError.class, () -> parse("{\"color\": [1.0, 1.0]}"));
    }

    @Test
    void rejectsUnknownBlend() {
        assertThrows(AssertionError.class, () -> OverlayBlend.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString("\"glow\""))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg)));
    }

    @Test
    void parsesEveryBlendValue() {
        for (OverlayBlend blend : OverlayBlend.values()) {
            OverlayBlend parsed = OverlayBlend.CODEC
                    .decode(JsonOps.INSTANCE, JsonParser.parseString("\"" + blend.getSerializedName() + "\""))
                    .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                    .getFirst();
            assertEquals(blend, parsed, blend.getSerializedName());
        }
    }
}
