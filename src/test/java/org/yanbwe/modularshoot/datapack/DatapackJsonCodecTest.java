package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.Trait;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.PluginItemBinding;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.shooter.ShooterDefinition;
import org.yanbwe.modularshoot.registry.variant.VariantDefinition;
import org.yanbwe.modularshoot.state.StateDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates every shipped datapack JSON against the real Codec classes used
 * at runtime, for <em>every</em> table under
 * {@code data/modularshoot/modularshoot/}: guns, plugins, plugin_types,
 * traits, states, attribute_meta, variants, gun_items, plugin_items and
 * shooters. Any shipped JSON that fails to decode with the codec it is
 * loaded with at runtime fails this test immediately — a future rename of a
 * datapack field is exposed in CI instead of only surfacing in-game at
 * datapack load time (where a single malformed entry aborts the whole
 * table).
 *
 * <p>Since {@link AttributeMeta#CODEC} resolves {@code entity_types} through
 * {@code BuiltInRegistries.ENTITY_TYPE}, whose static initializer requires a
 * bootstrapped game, this class bootstraps the vanilla registries once per
 * JVM (probe-verified recipe of
 * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest}). The test
 * only parses JSON and never registers anything, so no
 * {@code GameData.unfreezeData()} is needed.</p>
 *
 * <p>The specific-value assertions below (existing coverage) are kept as
 * spot checks on the decoded entries; the table-driven test
 * {@link #everyShippedJsonDecodesWithItsRegistryCodec()} guarantees full
 * per-file coverage of every table.</p>
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

    /**
     * Maps every datapack table name to the codec its registry loads with,
     * mirroring {@code ModularShootRegistries.onDataPackRegistry}. A table
     * that gains shipped JSON without a codec mapping fails loudly in
     * {@link #everyShippedJsonDecodesWithItsRegistryCodec()}.
     */
    private static Map<String, Codec<?>> tableCodecs() {
        Map<String, Codec<?>> codecs = new LinkedHashMap<>();
        codecs.put("guns", GunDefinition.CODEC);
        codecs.put("plugins", PluginDefinition.CODEC);
        codecs.put("plugin_types", PluginTypeDefinition.CODEC);
        codecs.put("traits", Trait.CODEC);
        codecs.put("states", StateDefinition.CODEC);
        codecs.put("attribute_meta", AttributeMeta.CODEC);
        codecs.put("variants", VariantDefinition.CODEC);
        codecs.put("gun_items", GunItemBinding.CODEC);
        codecs.put("plugin_items", PluginItemBinding.CODEC);
        codecs.put("shooters", ShooterDefinition.CODEC);
        return codecs;
    }

    /**
     * Enumerates every {@code .json} file under each table directory of
     * {@code data/modularshoot/modularshoot/} on the test classpath. The
     * returned map is sorted by table name, file names are sorted within
     * each table, so coverage is deterministic and auto-adapts when new
     * datapack JSON is added.
     */
    private static Map<String, List<String>> tableJsonFiles() throws Exception {
        Map<String, List<String>> files = new TreeMap<>();
        boolean foundRoot = false;
        Enumeration<URL> roots = DatapackJsonCodecTest.class.getClassLoader()
                .getResources("data/modularshoot/modularshoot");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (!"file".equals(root.getProtocol())) {
                continue;
            }
            foundRoot = true;
            File base = new File(root.toURI());
            File[] tables = base.listFiles(File::isDirectory);
            if (tables == null) {
                continue;
            }
            for (File table : tables) {
                File[] jsons = table.listFiles((dir, name) -> name.endsWith(".json"));
                if (jsons == null || jsons.length == 0) {
                    continue;
                }
                List<String> paths = files.computeIfAbsent(table.getName(), k -> new ArrayList<>());
                for (File json : jsons) {
                    paths.add(table.getName() + "/" + json.getName());
                }
                paths.sort(String::compareTo);
            }
        }
        assertTrue(foundRoot,
                "classpath resource data/modularshoot/modularshoot not found as a directory — "
                        + "cannot enumerate shipped datapack tables");
        return files;
    }

    // --- table-driven coverage: every shipped JSON, every registry codec ---

    @Test
    void everyShippedJsonDecodesWithItsRegistryCodec() throws Exception {
        Map<String, List<String>> files = tableJsonFiles();
        assertFalse(files.isEmpty(),
                "no modularshoot datapack table with JSON found on the test classpath");
        Map<String, Codec<?>> codecs = tableCodecs();
        for (Map.Entry<String, List<String>> table : files.entrySet()) {
            Codec<?> codec = codecs.get(table.getKey());
            assertNotNull(codec,
                    () -> "table " + table.getKey() + " has shipped JSON but no registry codec "
                            + "mapping — add it to tableCodecs()");
            for (String path : table.getValue()) {
                DataResult<?> result = codec.decode(JsonOps.INSTANCE, load(path));
                assertTrue(result.error().isEmpty(),
                        () -> "Shipped datapack JSON failed to decode with its runtime codec: "
                                + path + " (" + codecName(codec) + "): "
                                + result.error().get().message());
                result.getOrThrow();
            }
        }
    }

    private static String codecName(Codec<?> codec) {
        String name = codec.getClass().getSimpleName();
        return name.isEmpty() ? codec.toString() : name;
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
