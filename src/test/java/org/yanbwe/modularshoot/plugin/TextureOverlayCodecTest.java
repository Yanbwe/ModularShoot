package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link TextureOverlay} placement/sizing fields:
 * {@code alignment} (nine-grid) and {@code fit} (none / fill / contain),
 * both defaulting to the legacy behaviour when omitted.
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
                OverlayFit.CONTAIN);
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
}
