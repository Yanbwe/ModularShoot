package org.yanbwe.modularshoot.datapack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CrossReferenceValidator#findUnmatchedTags} (D1).
 *
 * <p>The registry-access methods of {@link CrossReferenceValidator} require a
 * live {@code RegistryAccess} and are therefore not unit-tested; the tag
 * intersection rule is extracted as a pure package-private function so the
 * core matching semantics get full coverage here.</p>
 *
 * <p>Input sets are built as {@link LinkedHashSet} where the assertion
 * depends on order, because {@code findUnmatchedTags} preserves the input
 * set's iteration order and {@code Set.of()} does not guarantee one.</p>
 */
class CrossReferenceValidatorTest {

    private static final ResourceLocation TYPE_BARREL =
            ResourceLocation.parse("modularshoot:barrel");
    private static final ResourceLocation TYPE_SCOPE =
            ResourceLocation.parse("modularshoot:scope");

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
}
