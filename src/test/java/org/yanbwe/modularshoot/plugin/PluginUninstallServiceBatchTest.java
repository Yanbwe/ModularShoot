package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.common.NeoForge;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.event.PostPluginUninstallEvent;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the batch-uninstall single modifier refresh (审查优化 P4: 批量卸载
 * 只重算一次属性).
 *
 * <p>Before the optimization {@code uninstallByUuids} refreshed the
 * {@code ATTRIBUTE_MODIFIERS} component after <em>every</em> removal (each
 * refresh iterates the whole {@code attribute_meta} registry plus every
 * remaining plugin). The batch path now defers the refresh until the end and
 * runs it exactly once with the final gun data. The deferral is observable
 * through {@link PostPluginUninstallEvent}: a listener reading the stack's
 * {@code ATTRIBUTE_MODIFIERS} mid-batch must see the <em>pre-batch</em>
 * component (the documented stale-window semantic), and the final component
 * must equal a from-scratch recomputation with the post-batch plugin list.</p>
 *
 * <p>Environment recipe identical to {@code PluginUninstallServiceGateTest}:
 * FML shim + vanilla bootstrap + unfreeze, plus the reflection bind of the
 * {@code GUN_DATA} DeferredHolder (probe recipe of
 * {@code ModularShootAPIItemBindingTest}) so the component channel works in
 * JUnit.</p>
 */
class PluginUninstallServiceBatchTest {

    private static final ResourceLocation COMBAT = ResourceLocation.parse("modularshoot:combat");
    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.parse("minecraft:attack_damage");
    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:test_batch_gun");
    private static final ResourceLocation PLUGIN_A_ID = ResourceLocation.parse("modularshoot:test_batch_plugin_a");
    private static final ResourceLocation PLUGIN_B_ID = ResourceLocation.parse("modularshoot:test_batch_plugin_b");

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        net.neoforged.neoforge.registries.GameData.unfreezeData();
        bindGunDataHolder();
        // The game bus is created in the shutdown state and only started by
        // FML during mod loading; in JUnit, post() would silently no-op and
        // the PostPluginUninstallEvent listener below would never fire.
        NeoForge.EVENT_BUS.start();
    }

    /** Binds the {@code GUN_DATA} DeferredHolder to a direct holder (see class javadoc). */
    private static void bindGunDataHolder() {
        try {
            Field holderField = net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
        } catch (Exception e) {
            throw new IllegalStateException("GUN_DATA DeferredHolder bind failed", e);
        }
    }

    // ---- definition builders --------------------------------------------

    private static GunDefinition gunDef() {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("m:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(HIT_DAMAGE, 5.0),     // stats: base 5.0 for hit_damage
                Map.of(),
                Map.of(COMBAT, 2),           // slots: two combat sockets
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static PluginDefinition pluginDef(double modifierValue) {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(new PluginModifier(ATTACK_DAMAGE.toString(),
                        PluginModifier.Operation.ADD, modifierValue)),
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
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static PluginInstance inst(ResourceLocation pluginId, boolean locked) {
        return new PluginInstance(pluginId, UUID.randomUUID(), COMBAT, locked);
    }

    /** Stub registry view: guns + plugins + attribute_meta, same shape as GateTest section C. */
    private static RegistryAccess access() {
        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, GUN_ID, gunDef());
        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, PLUGIN_A_ID, pluginDef(1.0));
        Registry.register(plugins, PLUGIN_B_ID, pluginDef(2.0));
        MappedRegistry<AttributeMeta> metas = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 0.0));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(guns, plugins, metas));
    }

    private static ItemStack gunStack(GunData gunData) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), gunData);
        return stack;
    }

    private static GunData readData(ItemStack stack) {
        return stack.get(ModularShootDataComponents.GUN_DATA.get());
    }

    private static ItemAttributeModifiers currentModifiers(ItemStack stack) {
        return stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void batchUninstallDefersModifierRefreshUntilEnd() {
        RegistryAccess access = access();
        PluginInstance a = inst(PLUGIN_A_ID, false);
        PluginInstance b = inst(PLUGIN_B_ID, false);
        ItemStack stack = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(a, b), 0, new CompoundTag()));
        AttributeModifierService.refreshModifiers(stack, access);
        ItemAttributeModifiers preBatch = currentModifiers(stack);

        List<ItemAttributeModifiers> seenAtEvents = new ArrayList<>();
        Consumer<PostPluginUninstallEvent> listener = e ->
                seenAtEvents.add(e.getGun().get(DataComponents.ATTRIBUTE_MODIFIERS));
        NeoForge.EVENT_BUS.addListener(PostPluginUninstallEvent.class, listener);
        try {
            List<UninstallResult> results = PluginUninstallService.uninstallAllPlugins(
                    stack, null, false, false, access);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(UninstallResult::success),
                    "both plugins of a clean batch uninstall successfully");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        GunData finalData = readData(stack);
        assertTrue(finalData.installedPlugins().isEmpty(), "both plugins removed from gun_data");
        assertEquals(preBatch, currentModifiers(stack), "final refresh must recompute base + remaining plugins");
        assertEquals(
                AttributeModifierService.computeAllModifiers(gunDef(), finalData, access),
                currentModifiers(stack),
                "final ATTRIBUTE_MODIFIERS equals a from-scratch recomputation with the post-batch plugin list");

        assertEquals(2, seenAtEvents.size(),
                "one PostPluginUninstallEvent per removed plugin");
        for (ItemAttributeModifiers seen : seenAtEvents) {
            assertEquals(preBatch, seen,
                    "deferral: mid-batch events observe the pre-batch component, "
                            + "not a per-removal refresh");
        }
    }

    @Test
    void batchUninstallMixedLockedSkipsLockedAndRefreshesFinalState() {
        RegistryAccess access = access();
        PluginInstance locked = inst(PLUGIN_A_ID, true);
        PluginInstance unlocked = inst(PLUGIN_B_ID, false);
        ItemStack stack = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(locked, unlocked), 0, new CompoundTag()));
        AttributeModifierService.refreshModifiers(stack, access);
        ItemAttributeModifiers preBatch = currentModifiers(stack);

        List<ItemAttributeModifiers> seenAtEvents = new ArrayList<>();
        Consumer<PostPluginUninstallEvent> listener = e ->
                seenAtEvents.add(e.getGun().get(DataComponents.ATTRIBUTE_MODIFIERS));
        NeoForge.EVENT_BUS.addListener(PostPluginUninstallEvent.class, listener);
        try {
            List<UninstallResult> results = PluginUninstallService.uninstallAllPlugins(
                    stack, null, false, false, access);
            assertEquals(2, results.size());
            assertEquals(UninstallResult.Reason.LOCKED, results.get(0).reason());
            assertTrue(results.get(1).success(), "the unlocked plugin is removed");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }

        GunData finalData = readData(stack);
        assertEquals(1, finalData.installedPlugins().size(), "the locked plugin stays installed");
        assertEquals(PLUGIN_A_ID, finalData.installedPlugins().get(0).pluginId());
        assertEquals(
                AttributeModifierService.computeAllModifiers(gunDef(), finalData, access),
                currentModifiers(stack),
                "final refresh uses the post-batch state, keeping the skipped plugin's modifiers");
        assertEquals(1, seenAtEvents.size(), "only the actually removed plugin fires a post event");
        assertEquals(preBatch, seenAtEvents.get(0), "the single mid-batch event still sees the pre-batch component");
    }

    @Test
    void batchUninstallAllRejectedLeavesComponentUntouched() {
        RegistryAccess access = access();
        PluginInstance locked = inst(PLUGIN_A_ID, true);
        ItemStack stack = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(locked), 0, new CompoundTag()));
        AttributeModifierService.refreshModifiers(stack, access);
        ItemAttributeModifiers preBatch = currentModifiers(stack);
        int preBatchVersion = readData(stack).modifierVersion();

        List<UninstallResult> results = PluginUninstallService.uninstallAllPlugins(
                stack, null, false, false, access);

        assertEquals(1, results.size());
        assertEquals(UninstallResult.Reason.LOCKED, results.get(0).reason());
        assertEquals(preBatch, currentModifiers(stack),
                "no removal → no batch refresh → the component is untouched");
        assertEquals(preBatchVersion, readData(stack).modifierVersion(),
                "no removal → gun_data is untouched too");
    }
}
