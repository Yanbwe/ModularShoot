package org.yanbwe.modularshoot.plugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EffectiveSlotService} aggregation and overflow
 * semantics (设计规格 §adds_slots 槽位扩展 §4).
 *
 * <p>Only the pure parts are exercised headless: {@link #aggregateSlots} and
 * {@link #overflowAfterRemoval} over hand-built definitions. The
 * {@link net.minecraft.world.item.ItemStack}-backed overloads are thin
 * delegations over these paths and require a bootstrapped
 * {@code modularshoot:plugins} / {@code modularshoot:guns} registry, which is
 * only available inside a running game; they are covered by
 * integration/manual verification.</p>
 */
class EffectiveSlotServiceTest {

    private static final ResourceLocation COMBAT = ResourceLocation.parse("modularshoot:combat");
    private static final ResourceLocation ACCESSORY = ResourceLocation.parse("modularshoot:accessory");

    /** Builds a {@link GunDefinition} carrying only the given slot configuration. */
    private static GunDefinition gun(Map<ResourceLocation, Integer> slots) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("m:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                slots,
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Builds a {@link PluginDefinition} carrying only the given adds_slots. */
    private static PluginDefinition def(Map<ResourceLocation, Integer> addsSlots) {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                addsSlots,
                Map.of(),
                Optional.empty());
    }

    /** Builds an installed plugin instance for the given plugin/type ids. */
    private static PluginInstance inst(String pluginId, String typeId) {
        return new PluginInstance(
                ResourceLocation.parse(pluginId),
                UUID.randomUUID(),
                ResourceLocation.parse(typeId),
                false);
    }

    // ------------------------------------------------------------------
    // aggregateSlots
    // ------------------------------------------------------------------

    @Test
    void baseSlotsKeptWhenNoPlugins() {
        Map<ResourceLocation, Integer> slots =
                EffectiveSlotService.aggregateSlots(gun(Map.of(COMBAT, 2)), List.of());
        assertEquals(Map.of(COMBAT, 2), slots, "no plugins leave the gun's slots unchanged");
    }

    @Test
    void pluginContributionSumsOntoBase() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of(COMBAT, 2)), List.of(def(Map.of(COMBAT, 1))));
        assertEquals(Map.of(COMBAT, 3), slots, "plugin contribution sums onto the gun's capacity");
    }

    @Test
    void pluginCanCreateNewType() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of(COMBAT, 2)), List.of(def(Map.of(ACCESSORY, 1))));
        assertEquals(Map.of(COMBAT, 2, ACCESSORY, 1), slots,
                "a plugin-declared type the gun never declared is created");
    }

    @Test
    void negativeContributionReducesCapacity() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of(COMBAT, 2)), List.of(def(Map.of(COMBAT, -1))));
        assertEquals(Map.of(COMBAT, 1), slots, "negative values occupy slots");
    }

    @Test
    void multiplePluginsStackOntoSameType() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of(COMBAT, 2)),
                List.of(def(Map.of(COMBAT, 1)), def(Map.of(COMBAT, 1))));
        assertEquals(Map.of(COMBAT, 4), slots,
                "every plugin's contribution sums onto the same type");
    }

    @Test
    void emptyGunSlotsWithPluginCreatesOnlyPluginKeys() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of()), List.of(def(Map.of(ACCESSORY, 1))));
        assertEquals(Map.of(ACCESSORY, 1), slots, "a slot-less gun gains only the plugin's types");
    }

    @Test
    void emptyGunAndNoPluginsYieldEmptyMap() {
        assertTrue(EffectiveSlotService.aggregateSlots(gun(Map.of()), List.of()).isEmpty(),
                "no slots and no plugins yield an empty map");
    }

    @Test
    void hugePositiveContributionsSaturateNotWrap() {
        // 两个 MAX_VALUE 贡献（如 15 亿 + 15 亿）若用 Integer::sum 会回绕为
        // 负容量，导致槽位永久不可装、非 force 卸载全被 WOULD_OVERFLOW 锁死。
        // 饱和加法必须钳制到 Integer.MAX_VALUE。
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of()),
                List.of(def(Map.of(COMBAT, Integer.MAX_VALUE)),
                        def(Map.of(COMBAT, Integer.MAX_VALUE))));
        assertEquals(Integer.MAX_VALUE, slots.get(COMBAT),
                "overflowing positive sums must saturate at Integer.MAX_VALUE, not wrap negative");
    }

    @Test
    void hugeNegativeContributionsSaturateNotWrap() {
        Map<ResourceLocation, Integer> slots = EffectiveSlotService.aggregateSlots(
                gun(Map.of()),
                List.of(def(Map.of(COMBAT, Integer.MIN_VALUE)),
                        def(Map.of(COMBAT, Integer.MIN_VALUE))));
        assertEquals(Integer.MIN_VALUE, slots.get(COMBAT),
                "overflowing negative sums must saturate at Integer.MIN_VALUE, not wrap");
    }

    // ------------------------------------------------------------------
    // overflowAfterRemoval
    // ------------------------------------------------------------------

    @Test
    void noOverflowWhenWithinCapacity() {
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 3)),
                List.of(inst("a", "modularshoot:combat"), inst("b", "modularshoot:combat")),
                List.of(def(Map.of()), def(Map.of())));
        assertFalse(overflow, "installed count within capacity never overflows");
    }

    @Test
    void noOverflowWhenExactlyAtCapacity() {
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 2)),
                List.of(inst("a", "modularshoot:combat"), inst("b", "modularshoot:combat")),
                List.of(def(Map.of()), def(Map.of())));
        assertFalse(overflow, "count exactly at capacity is not an overflow");
    }

    @Test
    void removalKeepsSurvivingSlotAddersContributions() {
        // Two slot-adding plugins (+1 each) with one combat plugin installed:
        // removing one adder leaves the other's contribution, so no overflow.
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 1)),
                List.of(inst("x", "modularshoot:combat"), inst("b", "modularshoot:combat")),
                List.of(def(Map.of()), def(Map.of(COMBAT, 1))));
        assertFalse(overflow, "the surviving slot adder still contributes its capacity");
    }

    @Test
    void negativeCapacityOverflowsWithInstalledPlugins() {
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, -1)),
                List.of(inst("a", "modularshoot:combat")),
                List.of(def(Map.of())));
        assertTrue(overflow, "a negative net capacity with any installed plugin overflows");
    }

    @Test
    void overflowWhenRemovalShrinksCapacity() {
        // The slot-adding plugin was already removed; capacity shrank back to
        // 2 while 3 plugins remain installed.
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 2)),
                List.of(inst("a", "modularshoot:combat"),
                        inst("b", "modularshoot:combat"),
                        inst("c", "modularshoot:combat")),
                List.of(def(Map.of()), def(Map.of()), def(Map.of())));
        assertTrue(overflow, "installed count above capacity overflows");
    }

    @Test
    void overflowWithDegradedPluginDefsSkipped() {
        // Plugin "b" has no resolvable definition (null): its adds_slots
        // contribution counts as zero.
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 1)),
                List.of(inst("a", "modularshoot:combat"), inst("b", "modularshoot:combat")),
                Arrays.asList(def(Map.of()), null));
        assertTrue(overflow, "a degraded plugin contributes no capacity");
    }

    @Test
    void overflowByCreatedTypeCountsOnlyInstalled() {
        // The slot-adding plugin that created the accessory type was removed;
        // the type's capacity is gone but one plugin remains installed in it.
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of()),
                List.of(inst("a", "modularshoot:accessory")),
                Collections.singletonList((PluginDefinition) null));
        assertTrue(overflow, "an installed plugin in a vanished created type overflows");
    }

    @Test
    void emptyRemainingNeverOverflows() {
        boolean overflow = EffectiveSlotService.overflowAfterRemoval(
                gun(Map.of(COMBAT, 1)), List.of(), List.of());
        assertFalse(overflow, "no remaining plugins can never overflow");
    }
}
