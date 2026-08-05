package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link TextureOverlay} placement/sizing fields:
 * {@code alignment} (nine-grid) and {@code fit} (none / fill / contain),
 * both defaulting to the legacy behaviour when omitted, plus the visual
 * fields {@code tint} / {@code blend} / {@code outline}.
 */
class TextureOverlayCodecTest {

    private static TextureOverlay parse(String json) {
        return TextureOverlay.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void defaultsToTopLeftAndNoFit() {
        TextureOverlay overlay = parse("{\"texture\": \"m:overlay\", \"layer\": 5}");
        assertEquals(ResourceLocation.fromNamespaceAndPath("m", "overlay"), overlay.texture());
        assertEquals(5, overlay.layer());
        assertEquals(OverlayAlignment.TOP_LEFT, overlay.alignment());
        assertEquals(OverlayFit.NONE, overlay.fit());
    }

    @Test
    void parsesExplicitCenterAndFill() {
        TextureOverlay overlay = parse(
                "{\"texture\": \"m:overlay\", \"layer\": 5, \"alignment\": \"center\", \"fit\": \"fill\"}");
        assertEquals(OverlayAlignment.CENTER, overlay.alignment());
        assertEquals(OverlayFit.FILL, overlay.fit());
    }

    @Test
    void parsesContainWithDefaultAlignment() {
        TextureOverlay overlay = parse("{\"texture\": \"m:overlay\", \"layer\": 5, \"fit\": \"contain\"}");
        assertEquals(OverlayAlignment.TOP_LEFT, overlay.alignment());
        assertEquals(OverlayFit.CONTAIN, overlay.fit());
    }

    @Test
    void parsesEveryAlignmentValue() {
        for (OverlayAlignment alignment : OverlayAlignment.values()) {
            TextureOverlay overlay = parse("{\"texture\": \"m:overlay\", \"layer\": 5, \"alignment\": \""
                    + alignment.getSerializedName() + "\"}");
            assertEquals(alignment, overlay.alignment(), alignment.getSerializedName());
        }
    }

    @Test
    void roundtripsBothNewFields() {
        TextureOverlay overlay = new TextureOverlay(
                ResourceLocation.fromNamespaceAndPath("m", "overlay"),
                5,
                OverlayAlignment.BOTTOM_CENTER,
                OverlayFit.CONTAIN,
                new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                OverlayBlend.NORMAL,
                Optional.empty());
        JsonElement encoded = TextureOverlay.CODEC.encodeStart(JsonOps.INSTANCE, overlay)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
        JsonObject json = encoded.getAsJsonObject();
        assertEquals("bottom_center", json.get("alignment").getAsString());
        assertEquals("contain", json.get("fit").getAsString());
        TextureOverlay decoded = TextureOverlay.CODEC.decode(JsonOps.INSTANCE, encoded)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
        assertEquals(overlay, decoded);
    }

    @Test
    void rejectsUnknownAlignment() {
        var result = TextureOverlay.CODEC.decode(JsonOps.INSTANCE,
                JsonParser.parseString("{\"texture\": \"m:overlay\", \"layer\": 5, \"alignment\": \"middle\"}"));
        assertTrue(result.error().isPresent(), "unknown alignment should yield a decode error");
    }

    @Test
    void rejectsUnknownFit() {
        var result = TextureOverlay.CODEC.decode(JsonOps.INSTANCE,
                JsonParser.parseString("{\"texture\": \"m:overlay\", \"layer\": 5, \"fit\": \"cover\"}"));
        assertTrue(result.error().isPresent(), "unknown fit should yield a decode error");
    }

    @Test
    void defaultsTintBlendOutline() {
        TextureOverlay overlay = parse("{\"texture\": \"m:o\", \"layer\": 5}");
        Vector4f tint = overlay.tint();
        assertEquals(1.0F, tint.x(), 1.0E-5F);
        assertEquals(1.0F, tint.y(), 1.0E-5F);
        assertEquals(1.0F, tint.z(), 1.0E-5F);
        assertEquals(1.0F, tint.w(), 1.0E-5F);
        assertEquals(OverlayBlend.NORMAL, overlay.blend());
        assertTrue(overlay.outline().isEmpty(), "outline should default to empty");
    }

    @Test
    void parsesTintBlendOutline() {
        TextureOverlay overlay = parse(
                "{\"texture\": \"m:o\", \"layer\": 5, \"tint\": [1.0, 0.5, 0.5, 1.0], "
                        + "\"blend\": \"add\", \"outline\": {\"color\": [1.0, 0.0, 0.0], \"width\": 2}}");
        Vector4f tint = overlay.tint();
        assertEquals(1.0F, tint.x(), 1.0E-5F);
        assertEquals(0.5F, tint.y(), 1.0E-5F);
        assertEquals(0.5F, tint.z(), 1.0E-5F);
        assertEquals(1.0F, tint.w(), 1.0E-5F);
        assertEquals(OverlayBlend.ADD, overlay.blend());
        assertTrue(overlay.outline().isPresent(), "outline should be present");
        OutlineSpec outline = overlay.outline().orElseThrow();
        assertEquals(1.0F, outline.color().x(), 1.0E-5F);
        assertEquals(0.0F, outline.color().y(), 1.0E-5F);
        assertEquals(0.0F, outline.color().z(), 1.0E-5F);
        assertEquals(1.0F, outline.alpha(), 1.0E-5F, "alpha should default to 1.0");
        assertEquals(2, outline.width());
    }

    @Test
    void rejectsTintWithThreeChannels() {
        assertThrows(AssertionError.class, () -> parse(
                "{\"texture\": \"m:o\", \"layer\": 5, \"tint\": [1.0, 0.5, 0.5]}"));
    }
}
