package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.shooter.ShooterDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates every shipped datapack JSON against the real Codec classes used
 * at runtime. Currently covers the {@code attribute_meta} registry and the
 * {@code shooters} registry; the gun/plugin/trait/state codec round-trips are
 * covered by the inline-JSON codec tests (GunDefinitionKeyCodecTest,
 * PluginDefinitionCodecTest, etc.).
 *
 * <p>Since {@link AttributeMeta#CODEC} resolves {@code entity_types} through
 * {@code BuiltInRegistries.ENTITY_TYPE}, whose static initializer requires a
 * bootstrapped game, this class bootstraps the vanilla registries once per
 * JVM (probe-verified recipe of
 * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest}). The test
 * only parses JSON and never registers anything, so no
 * {@code GameData.unfreezeData()} is needed.</p>
 */
class DatapackJsonCodecTest {

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to ModularShootAPIItemBindingTest's probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static JsonElement load(String path) {
        InputStream in = DatapackJsonCodecTest.class.getClassLoader()
                .getResourceAsStream("data/modularshoot/modularshoot/" + path);
        assertNotNull(in, "missing resource: " + path);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static <T> T parse(String path, com.mojang.serialization.Codec<T> codec) {
        return codec.decode(JsonOps.INSTANCE, load(path))
                .getOrThrow(msg -> new AssertionError("Failed to parse " + path + ": " + msg))
                .getFirst();
    }

    // --- attribute_meta ---

    @Test
    void pelletCountMetaParses() {
        AttributeMeta meta = parse("attribute_meta/pellet_count.json", AttributeMeta.CODEC);
        assertEquals(1.0, meta.defaultValue(),
                "pellet_count defaults to a single pellet per shot (多弹丸规格 §3.2)");
        assertEquals(ResourceLocation.parse("modularshoot:pellet_count"), meta.binds(),
                "pellet_count binds to its own built-in attribute body");
    }

    @Test
    void fireRateMetaParsesUnit() {
        AttributeMeta meta = parse("attribute_meta/fire_rate.json", AttributeMeta.CODEC);
        assertEquals(Optional.of("modularshoot.unit.per_second"), meta.unit(),
                "fire_rate declares a per-second unit translation key");
    }

    @Test
    void metaWithoutUnitDefaultsToEmpty() {
        AttributeMeta meta = parse("attribute_meta/pellet_count.json", AttributeMeta.CODEC);
        assertEquals(Optional.empty(), meta.unit(),
                "pellet_count declares no unit -> tooltip shows the bare value");
    }

    // --- shooters ---

    @Test
    void exampleBoneShooterParses() {
        ShooterDefinition shooter =
                parse("shooters/example_bone_shooter.json", ShooterDefinition.CODEC);
        assertEquals(6.0,
                shooter.stats().get(ResourceLocation.parse("modularshoot:hit_damage")), 1e-9,
                "example bone shooter declares hit_damage 6.0 in its stats template");
        assertTrue(shooter.bulletStyle().isPresent(),
                "example bone shooter carries an independent-firing bullet_style");
    }
}
