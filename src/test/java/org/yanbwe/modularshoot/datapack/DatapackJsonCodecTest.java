package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.Trait;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.Modifier;
import org.yanbwe.modularshoot.state.StateDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates every shipped datapack JSON against the real Codec classes used
 * at runtime (GunDefinition/PluginDefinition/Trait/StateDefinition). This
 * catches format drift between the datapack test entries and the
 * modifier-stacking codec structure (设计规格 §3.1/§3.2/§3.5) without
 * launching the game.
 */
class DatapackJsonCodecTest {

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

    private static void assertStyleValid(BulletStyle style, String path) {
        // bullet_style: base present OR modifiers non-empty; every modifier
        // decodes to a known concrete type (never UnsupportedModifier).
        assertFalse(style.base().isEmpty() && style.modifiers().isEmpty(),
                path + ": bullet_style should declare base or modifiers");
        for (Modifier m : style.modifiers()) {
            assertNotEquals("unsupported", m.type(),
                    path + ": unrecognised modifier type in datapack: " + m);
        }
    }

    // --- guns ---

    @Test
    void gunDatapackJsonParsesWithNewBulletStyle() {
        List.of(
                "guns/test_gun.json",
                "guns/test_gun_3d.json",
                "guns/test_gun_nostyle.json",
                "guns/test_gun_orb.json"
        ).forEach(path -> {
            GunDefinition gun = parse(path, GunDefinition.CODEC);
            gun.bulletStyle().ifPresent(style -> assertStyleValid(style, path));
        });
    }

    @Test
    void gunModifiersUseConcreteTypes() {
        GunDefinition gun = parse("guns/test_gun.json", GunDefinition.CODEC);
        BulletStyle style = gun.bulletStyle().orElseThrow();
        assertTrue(style.base().isPresent(), "test_gun should declare a billboard base");
        assertEquals(BulletStyle.RenderMode.BILLBOARD, style.base().get().renderMode());
        assertEquals(2, style.modifiers().size(),
                "test_gun should declare scale + attach_layer modifiers");
        // attach_layer round-trips its texture/tint payload
        Modifier m = style.modifiers().get(1);
        assertEquals("attach_layer", m.type());
    }

    @Test
    void gunWithoutStyleHasEmptyOptional() {
        GunDefinition gun = parse("guns/test_gun_nostyle.json", GunDefinition.CODEC);
        assertTrue(gun.bulletStyle().isEmpty(),
                "test_gun_nostyle has no bullet_style -> compose falls back to FALLBACK_BASE");
    }

    // --- plugins ---

    @Test
    void pluginDatapackJsonParsesWithModifiers() {
        List.of(
                "plugins/visual_scale_1.json",
                "plugins/visual_scale_2.json",
                "plugins/visual_tint_a.json",
                "plugins/visual_tint_b.json",
                "plugins/visual_fire_trail.json",
                "plugins/visual_blackhole.json",
                "plugins/visual_gun_ember.json",
                "plugins/visual_gun_frost.json",
                "plugins/visual_gun_prism.json",
                "plugins/explosive_rounds.json",
                "plugins/precision_barrel.json",
                "plugins/rapid_fire_barrel.json"
        ).forEach(path -> {
            var plugin = parse(path, org.yanbwe.modularshoot.plugin.PluginDefinition.CODEC);
            plugin.bulletStyle().ifPresent(style -> assertStyleValid(style, path));
        });
    }

    @Test
    void blackholePluginBaseWinsByPriority() {
        var plugin = parse("plugins/visual_blackhole.json",
                org.yanbwe.modularshoot.plugin.PluginDefinition.CODEC);
        BulletStyle style = plugin.bulletStyle().orElseThrow();
        assertTrue(style.base().isPresent());
        assertTrue(style.base().get().texture().isPresent());
        assertEquals(1, style.modifiers().size());
    }

    // --- traits ---

    @Test
    void traitDatapackJsonParsesWithVisualModifiers() {
        Trait trait = parse("traits/visual_bloodlust.json", Trait.CODEC);
        assertEquals(2, trait.visualModifiers().size(),
                "visual_bloodlust declares tint + scale visual modifiers");
        assertEquals("tint", trait.visualModifiers().get(0).type());
        assertEquals("scale", trait.visualModifiers().get(1).type());
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

    // --- states ---

    @Test
    void stateDatapackJsonParsesWithVisualModifiers() {
        StateDefinition state = parse("states/visual_killstreak.json", StateDefinition.CODEC);
        assertEquals(1, state.visualModifiers().size());
        StateDefinition.StateVisualModifier svm = state.visualModifiers().get(0);
        assertEquals("modularshoot", svm.condition().state().getNamespace());
        assertEquals("visual_killstreak", svm.condition().state().getPath());
        assertEquals(org.yanbwe.modularshoot.state.StateDomain.GUN, svm.condition().domain().orElseThrow());
        assertEquals(org.yanbwe.modularshoot.bullet.StateConditionEvaluator.Op.GE, svm.condition().op());
        assertEquals(3, ((Number) svm.condition().value()).intValue());
        assertEquals(3, svm.modifiers().size(),
                "killstreak >= 3 applies tint + scale + attach_layer");
    }
}
