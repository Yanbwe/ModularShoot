package org.yanbwe.modularshoot.registry.shooter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec tests for {@link ShooterDefinition#CODEC}: required {@code stats}
 * (with non-empty validation), optional {@code traits} / {@code bullet_style}
 * / {@code shoot_sound} / {@code attribute_binds}, and the
 * {@link ShooterDefinition.ShootSound} volume/pitch defaults.
 *
 * <p><b>Bootstrap.</b> The {@code bullet_style} codec only touches plain
 * record/enum codecs and does not consult {@code BuiltInRegistries}, but the
 * rule for this task is "when in doubt, bootstrap — it is harmless". The
 * static block below follows the probe-verified recipe of
 * {@link org.yanbwe.modularshoot.datapack.DatapackJsonCodecTest} (FML shim +
 * game-version shim + {@code Bootstrap.bootStrap()}; no
 * {@code GameData.unfreezeData()} because nothing is registered here).</p>
 */
class ShooterDefinitionCodecTest {

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to DatapackJsonCodecTest's probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static ShooterDefinition decode(JsonObject json) {
        return ShooterDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static JsonObject encode(ShooterDefinition definition) {
        return ShooterDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg))
                .getAsJsonObject();
    }

    private static ShooterDefinition roundtrip(ShooterDefinition definition) {
        return decode(encode(definition));
    }

    private static DataResult<ShooterDefinition> decodeResult(JsonObject json) {
        return ShooterDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .map(pair -> pair.getFirst());
    }

    // ---- Round trips -----------------------------------------------------

    @Test
    void fullJsonRoundTrips() {
        JsonObject json = parse("""
                {
                  "stats": {
                    "modularshoot:hit_damage": 6.0,
                    "modularshoot:bullet_speed": 2.5
                  },
                  "traits": {
                    "modularshoot:ignite": true
                  },
                  "bullet_style": {
                    "base": {
                      "render_mode": "billboard",
                      "texture": "modularshoot:textures/bullet/default.png"
                    }
                  },
                  "shoot_sound": {
                    "id": "modularshoot:test_shoot",
                    "volume": 0.8,
                    "pitch": 1.2
                  },
                  "attribute_binds": [
                    "modularshoot:hit_damage",
                    "mypack:custom_damage"
                  ]
                }
                """);
        ShooterDefinition def = decode(json);
        assertEquals(Map.of(
                ResourceLocation.parse("modularshoot:hit_damage"), 6.0,
                ResourceLocation.parse("modularshoot:bullet_speed"), 2.5), def.stats());
        assertEquals(Map.of(ResourceLocation.parse("modularshoot:ignite"), true), def.traits());
        assertTrue(def.bulletStyle().isPresent());
        BulletStyle.Base base = def.bulletStyle().get().base().orElseThrow();
        assertEquals(BulletStyle.RenderMode.BILLBOARD, base.renderMode());
        assertEquals(Optional.of(ResourceLocation.parse(
                "modularshoot:textures/bullet/default.png")), base.texture());
        assertTrue(def.shootSound().isPresent());
        assertEquals(ResourceLocation.parse("modularshoot:test_shoot"),
                def.shootSound().get().id());
        assertEquals(0.8f, def.shootSound().get().volume(), 1e-6);
        assertEquals(1.2f, def.shootSound().get().pitch(), 1e-6);
        assertEquals(List.of(
                ResourceLocation.parse("modularshoot:hit_damage"),
                ResourceLocation.parse("mypack:custom_damage")), def.attributeBinds());
        // encode → decode round trip preserves every field.
        assertEquals(def, roundtrip(def));
    }

    @Test
    void minimalJsonDefaults() {
        ShooterDefinition def = decode(parse("""
                {"stats": {"m:a": 1.0}}
                """));
        assertEquals(Map.of(ResourceLocation.parse("m:a"), 1.0), def.stats());
        assertTrue(def.traits().isEmpty());
        assertTrue(def.bulletStyle().isEmpty());
        assertTrue(def.shootSound().isEmpty());
        assertTrue(def.attributeBinds().isEmpty());
    }

    @Test
    void shootSoundDefaultsVolumePitch() {
        ShooterDefinition def = decode(parse("""
                {"stats": {"m:a": 1.0}, "shoot_sound": {"id": "m:s"}}
                """));
        assertTrue(def.shootSound().isPresent());
        assertEquals(1.0f, def.shootSound().get().volume(), 1e-6);
        assertEquals(1.0f, def.shootSound().get().pitch(), 1e-6);
    }

    // ---- Required-field / validation failures ----------------------------

    @Test
    void statsMissingFails() {
        assertTrue(decodeResult(parse("{}")).error().isPresent(),
                "stats is required — a shooter without a stats template is invalid");
    }

    @Test
    void statsEmptyFails() {
        assertTrue(decodeResult(parse("{\"stats\":{}}")).error().isPresent(),
                "an empty stats template is rejected (stats must not be empty)");
    }
}
