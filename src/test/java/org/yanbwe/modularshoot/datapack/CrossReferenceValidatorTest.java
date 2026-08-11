package org.yanbwe.modularshoot.datapack;

import com.mojang.serialization.Lifecycle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.shooter.ShooterDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CrossReferenceValidator} (D1).
 *
 * <p>The pure-function tag-intersection rule of
 * {@link CrossReferenceValidator#findUnmatchedTags} gets full coverage here;
 * input sets are built as {@link LinkedHashSet} where the assertion depends
 * on order, because {@code findUnmatchedTags} preserves the input set's
 * iteration order and {@code Set.of()} does not guarantee one.</p>
 *
 * <p>The registry-access methods need a live {@link RegistryAccess}, which
 * cannot be constructed without a bootstrapped game. The
 * {@code validateShooters} case below builds minimal stub registries
 * ({@link MappedRegistry} instances wrapped in an
 * {@link RegistryAccess.ImmutableRegistryAccess}) after the probe-verified
 * bootstrap recipe, covering the "dangling attribute_binds emit WARN but the
 * entry stays registered" contract headlessly.</p>
 */
class CrossReferenceValidatorTest {

    private static final ResourceLocation TYPE_BARREL =
            ResourceLocation.parse("modularshoot:barrel");
    private static final ResourceLocation TYPE_SCOPE =
            ResourceLocation.parse("modularshoot:scope");

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to DatapackJsonCodecTest's probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void everyTagMatchedBySomeTypeReturnsEmpty() {
        Set<String> pluginTags = Set.of("modularshoot:barrel", "modularshoot:scope");
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"),
                TYPE_SCOPE, Set.of("modularshoot:scope"));
        assertTrue(CrossReferenceValidator.findUnmatchedTags(pluginTags, typeTagSets).isEmpty());
    }

    @Test
    void tagsIntersectingAnySingleTypeCountAsMatched() {
        Set<String> pluginTags = Set.of("modularshoot:barrel", "modularshoot:extended");
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel", "modularshoot:extended"));
        assertTrue(CrossReferenceValidator.findUnmatchedTags(pluginTags, typeTagSets).isEmpty());
    }

    @Test
    void disjointTagsReturnAllPluginTags() {
        Set<String> pluginTags = Set.of("modularshoot:barrel");
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_SCOPE, Set.of("modularshoot:scope"));
        assertEquals(List.of("modularshoot:barrel"),
                CrossReferenceValidator.findUnmatchedTags(pluginTags, typeTagSets));
    }

    @Test
    void partiallyMatchedTagsReturnOnlyUnmatched() {
        Set<String> pluginTags = new LinkedHashSet<>(List.of(
                "modularshoot:barrel", "modularshoot:typo_tag"));
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"));
        assertEquals(List.of("modularshoot:typo_tag"),
                CrossReferenceValidator.findUnmatchedTags(pluginTags, typeTagSets));
    }

    @Test
    void emptyPluginTagsReturnEmptyList() {
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"));
        assertTrue(CrossReferenceValidator.findUnmatchedTags(Set.of(), typeTagSets).isEmpty());
    }

    @Test
    void emptyTypeTagSetsReturnAllPluginTags() {
        Set<String> pluginTags = new LinkedHashSet<>(List.of(
                "modularshoot:barrel", "modularshoot:scope"));
        assertEquals(List.of("modularshoot:barrel", "modularshoot:scope"),
                CrossReferenceValidator.findUnmatchedTags(pluginTags, Map.of()));
    }

    // ---- matchesNoType (审查修复 M4) -------------------------------------

    /**
     * A plugin with one matched tag and one typo must not be reported as
     * "cannot be installed on any gun": only zero intersection with every
     * type's tag set triggers the WARN (审查修复 M4).
     */
    @Test
    void partialMatchDoesNotWarn() {
        Set<String> pluginTags = new LinkedHashSet<>(List.of(
                "modularshoot:barrel", "minecraft:typo"));
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"),
                TYPE_SCOPE, Set.of("modularshoot:scope"));
        assertFalse(
                CrossReferenceValidator.matchesNoType(pluginTags, typeTagSets));
    }

    /**
     * A plugin whose tags intersect no type's tag set at all triggers the
     * WARN (审查修复 M4).
     */
    @Test
    void noIntersectionWarns() {
        Set<String> pluginTags = new LinkedHashSet<>(List.of(
                "minecraft:a", "minecraft:b"));
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"),
                TYPE_SCOPE, Set.of("modularshoot:scope"));
        assertTrue(
                CrossReferenceValidator.matchesNoType(pluginTags, typeTagSets));
    }

    /**
     * Empty tags never satisfy "matches no type": the no-intersection
     * assertion does not hold for the empty set (审查修复 M4).
     */
    @Test
    void emptyTagsNeverMatch() {
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"));
        assertFalse(CrossReferenceValidator.matchesNoType(Set.of(), typeTagSets));
    }

    /**
     * A single matching tag is enough to disprove "matches no type"
     * (审查修复 M4).
     */
    @Test
    void singleMatchingTagIsNotNoType() {
        Set<String> pluginTags = new LinkedHashSet<>(List.of(
                "modularshoot:barrel"));
        Map<ResourceLocation, Set<String>> typeTagSets = Map.of(
                TYPE_BARREL, Set.of("modularshoot:barrel"));
        assertFalse(
                CrossReferenceValidator.matchesNoType(pluginTags, typeTagSets));
    }

    // ---- validateShooters (stub-registry headless case) ------------------

    /**
     * A shooter whose {@code attribute_binds} mixes a registered logical
     * attribute with a dangling id: {@code validateShooters} must not throw
     * and must leave the shooter registered (WARN only, degradation
     * deferred — 设计文档 §数据包JSON加载失败错误处理, line 2375).
     */
    @Test
    void validateShootersKeepsEntriesWhenBindsUnregistered() {
        ResourceLocation shooterId = ResourceLocation.parse("modularshoot:test_shooter");
        ShooterDefinition shooter = new ShooterDefinition(
                Map.of(ResourceLocation.parse("modularshoot:hit_damage"), 6.0),
                Map.of(),
                Optional.empty(), Optional.empty(),
                List.of(ResourceLocation.parse("modularshoot:hit_damage"),
                        ResourceLocation.parse("mypack:missing_attr")));
        // stub 注册表：attribute_meta 含 hit_damage；shooters 含该条目。
        MappedRegistry<AttributeMeta> metaRegistry = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metaRegistry, ResourceLocation.parse("modularshoot:hit_damage"),
                AttributeMeta.of(ResourceLocation.parse("modularshoot:hit_damage"), 1.0));
        MappedRegistry<ShooterDefinition> shooterRegistry = new MappedRegistry<>(
                ModularShootRegistries.SHOOTERS_KEY, Lifecycle.stable());
        Registry.register(shooterRegistry, shooterId, shooter);
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(metaRegistry, shooterRegistry));

        // 未注册 bind（mypack:missing_attr）→ WARN；条目仍算注册。
        CrossReferenceValidator.validateShooters(access, Map.of(shooterId, shooter));
        assertSame(shooter, shooterRegistry.get(shooterId));
    }
}
