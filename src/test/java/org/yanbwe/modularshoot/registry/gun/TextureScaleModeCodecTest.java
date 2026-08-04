package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.PluginDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@code texture_scale} switch (auto / fixed) on the
 * gun and plugin definitions, plus the enum codec itself.
 */
class TextureScaleModeCodecTest {

    private static TextureScaleMode decodeMode(String value) {
        return TextureScaleMode.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString("\"" + value + "\""))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    // --- enum codec ---

    @Test
    void parsesAuto() {
        assertEquals(TextureScaleMode.AUTO, decodeMode("auto"));
    }

    @Test
    void parsesFixed() {
        assertEquals(TextureScaleMode.FIXED, decodeMode("fixed"));
    }

    @Test
    void rejectsUnknownValue() {
        var result = TextureScaleMode.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString("\"huge\""));
        assertTrue(result.error().isPresent(), "unknown enum value should yield a decode error");
    }

    @Test
    void roundtripsBothValues() {
        for (TextureScaleMode mode : TextureScaleMode.values()) {
            JsonElement encoded = TextureScaleMode.CODEC.encodeStart(JsonOps.INSTANCE, mode)
                    .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
            TextureScaleMode decoded = TextureScaleMode.CODEC.decode(JsonOps.INSTANCE, encoded)
                    .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                    .getFirst();
            assertEquals(mode, decoded);
        }
    }

    // --- GunDefinition field ---

    @Test
    void gunDefaultsToAutoWhenOmitted() {
        GunDefinition gun = parseGun("{}");
        assertEquals(TextureScaleMode.AUTO, gun.textureScale());
    }

    @Test
    void gunParsesFixed() {
        GunDefinition gun = parseGun("{\"texture_scale\": \"fixed\"}");
        assertEquals(TextureScaleMode.FIXED, gun.textureScale());
    }

    @Test
    void gunParsesExplicitAuto() {
        GunDefinition gun = parseGun("{\"texture_scale\": \"auto\"}");
        assertEquals(TextureScaleMode.AUTO, gun.textureScale());
    }

    @Test
    void gunRoundtripsTextureScale() {
        JsonObject json = new JsonObject();
        json.addProperty("texture", "m:tex");
        json.addProperty("texture_scale", "fixed");
        GunDefinition decoded = GunDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
        JsonElement encoded = GunDefinition.CODEC.encodeStart(JsonOps.INSTANCE, decoded)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
        assertEquals("fixed", encoded.getAsJsonObject().get("texture_scale").getAsString());
    }

    // --- PluginDefinition field ---

    @Test
    void pluginDefaultsToAutoWhenOmitted() {
        PluginDefinition plugin = parsePlugin("{}");
        assertEquals(TextureScaleMode.AUTO, plugin.textureScale());
    }

    @Test
    void pluginParsesFixed() {
        PluginDefinition plugin = parsePlugin("{\"texture_scale\": \"fixed\"}");
        assertEquals(TextureScaleMode.FIXED, plugin.textureScale());
    }

    // --- helpers ---

    private static GunDefinition parseGun(String textureScaleSection) {
        JsonObject json = JsonParser.parseString(textureScaleSection).getAsJsonObject();
        json.addProperty("texture", "m:tex");
        return GunDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static PluginDefinition parsePlugin(String textureScaleSection) {
        JsonObject json = JsonParser.parseString(textureScaleSection).getAsJsonObject();
        json.addProperty("item_icon", "m:icon");
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }
}
