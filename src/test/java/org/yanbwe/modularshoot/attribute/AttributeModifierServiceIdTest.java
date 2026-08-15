package org.yanbwe.modularshoot.attribute;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginModifier;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * TDD tests for the unambiguous, cross-instance stable plugin modifier id
 * (加固 / 任务 5.2 修复加固).
 *
 * <p>The modifier id each plugin instance gets is derived from its
 * {@code pluginId} plus its occurrence index, <em>not</em> from the per-gun
 * {@code instanceUuid}, so identical plugin configurations on different guns
 * share the same id (and thus one cached result). The code-review review of the
 * original {@code plugin_<ns>_<path>_<idx>} scheme found an inherent ambiguity:
 * two <em>distinct</em> legal plugin ids such as {@code a:b_c} and
 * {@code a_b:c} both collapse to {@code plugin_a_b_c_<idx>} at the same
 * occurrence index. These tests pin the hardened (length-prefixed) encoding and
 * the reload weak-key invalidation of the {@code plugins} registry:</p>
 * <ol>
 *   <li><b>No collision across ambiguous ids.</b> Two plugin ids that collide
 *       under the old {@code plugin_<ns>_<path>_<idx>} scheme must produce
 *       distinct modifier ids at the same occurrence index.</li>
 *   <li><b>Cross-instance stability.</b> The same plugin configuration on two
 *       guns with different {@code instanceUuid}s must yield the same modifier
 *       id, so the shared cache stays correct for both.</li>
 *   <li><b>Plugins-registry weak-key invalidation.</b> Replacing only the
 *       {@code modularshoot:plugins} registry instance (a {@code /reload} that
 *       swaps the registry but keeps {@code attribute_meta} stable) must force a
 *       recompute — the old instance's cached result is never reused.</li>
 * </ol>
 *
 * <p>Same JUnit bridge as {@code ReloadInventoryRefreshTest}: FML shim +
 * vanilla bootstrap + a reflection-bound {@code GUN_DATA} holder for headless
 * {@link ItemStack} reads. Each test uses fresh {@link MappedRegistry}s so the
 * shared static caches cannot leak state across cases.</p>
 */
class AttributeModifierServiceIdTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:test_gun");
    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.parse("minecraft:generic.attack_damage");
    private static final ResourceLocation TYPE = ResourceLocation.parse("modularshoot:type");

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to the other datapack/attribute tests' probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        bindGunDataHolder();
    }

    /** Binds the {@code GUN_DATA} DeferredHolder to a direct holder for headless stack reads. */
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

    // ──────────────── 1. Colliding ids under the old encoding stay distinct ────────────────

    /**
     * Two plugin ids that collapsed to the same modifier id under the old
     * {@code plugin_<ns>_<path>_<idx>} scheme — {@code a:b_c} and
     * {@code a_b:c} — must yield <em>different</em> modifier ids at the same
     * occurrence index under the hardened length-prefixed encoding, so
     * distinct plugin configurations never collide on an attribute.
     */
    @Test
    void collidingPluginIdsUnderOldEncodingGetDistinctModifierIds() {
        ResourceLocation id1 = ResourceLocation.parse("a:b_c");
        ResourceLocation id2 = ResourceLocation.parse("a_b:c");

        RegistryAccess access = access(
                metas(),
                Map.of(id1, pluginDef(1.0), id2, pluginDef(2.0)));

        // Both plugins are the only installed plugin on their respective gun, so
        // both occupy occurrenceIndex 0 — exactly the configuration that used to
        // collide under plugin_a_b_c_0.
        ItemStack gun1 = gunStack(installed(id1));
        ItemStack gun2 = gunStack(installed(id2));

        AttributeModifierService.refreshModifiers(gun1, access);
        AttributeModifierService.refreshModifiers(gun2, access);

        ResourceLocation modifierId1 = onlyModifierId(current(gun1));
        ResourceLocation modifierId2 = onlyModifierId(current(gun2));

        assertNotEquals(modifierId1, modifierId2,
                "两个旧编码下会碰撞的插件 id（a:b_c vs a_b:c）在相同 occurrence index 必须得到不同的修饰符 id");
    }

    // ──────────────── 2. Cross-instance stability ────────────────

    /**
     * Two guns with the <em>same</em> plugin configuration (same ordered plugin
     * ids, same occurrence index) but different {@code instanceUuid}s must yield
     * the <em>same</em> modifier id — the id is derived from the plugin
     * configuration, not the per-gun instance, so the shared cache stays valid
     * across players and gun copies.
     */
    @Test
    void samePluginConfigurationAcrossInstancesYieldsSameModifierId() {
        ResourceLocation id = ResourceLocation.parse("modularshoot:rapid_barrel");
        RegistryAccess access = access(metas(), Map.of(id, pluginDef(1.0)));

        // Two stacks with different per-gun instance uuids (as real game installs
        // would generate) but identical plugin config.
        ItemStack gunA = gunStack(installed(id));
        ItemStack gunB = gunStack(installed(id));

        AttributeModifierService.refreshModifiers(gunA, access);
        AttributeModifierService.refreshModifiers(gunB, access);

        ResourceLocation idA = onlyModifierId(current(gunA));
        ResourceLocation idB = onlyModifierId(current(gunB));

        assertEquals(idA, idB,
                "相同插件配置跨实例必须得到相同的修饰符 id（维持缓存共享）");
    }

    // ──────────────── 3. Plugins-registry weak-key invalidation ────────────────

    /**
     * A {@code /reload} that swaps in a brand-new {@code modularshoot:plugins}
     * registry instance — while keeping the {@code attribute_meta} registry the
     * same — must NOT reuse the old instance's cached result. The plugin's
     * modifier value changed (1.0 &rarr; 2.0); the gun must be recomputed against
     * the new plugins registry.
     */
    @Test
    void replacingOnlyPluginsRegistryInstanceInvalidatesWeakKeyCache() {
        MappedRegistry<AttributeMeta> metas = metas();  // shared across both registry views

        // Old plugins registry: the plugin contributes 1.0.
        MappedRegistry<PluginDefinition> oldPlugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(oldPlugins, ResourceLocation.parse("modularshoot:boost"),
                pluginDef(1.0));
        RegistryAccess oldAccess = accessOf(metas, oldPlugins);

        // New plugins registry (/reload: new instance): the same plugin now contributes 2.0.
        MappedRegistry<PluginDefinition> newPlugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(newPlugins, ResourceLocation.parse("modularshoot:boost"),
                pluginDef(2.0));
        RegistryAccess newAccess = accessOf(metas, newPlugins);

        ItemStack stack = gunStack(installed(ResourceLocation.parse("modularshoot:boost")));

        AttributeModifierService.refreshModifiers(stack, oldAccess);
        ItemAttributeModifiers before = current(stack);

        AttributeModifierService.refreshModifiers(stack, newAccess);
        ItemAttributeModifiers after = current(stack);

        assertEquals(1.0, onlyModifierAmount(before), "旧 plugins 注册表解析出 1.0");
        assertEquals(2.0, onlyModifierAmount(after),
                "只替换 plugins 注册表实例后必须重算（1.0 不得被旧缓存复用）");
    }

    // ──────────────── Test data helpers ────────────────

    private static MappedRegistry<AttributeMeta> metas() {
        MappedRegistry<AttributeMeta> metas = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        return metas;
    }

    private static PluginDefinition pluginDef(double value) {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("modularshoot:textures/plugin/icon.png"),
                TextureScaleMode.AUTO,
                List.of(new PluginModifier(ATTACK_DAMAGE.toString(),
                        PluginModifier.Operation.ADD, value)),
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

    private static PluginInstance installed(ResourceLocation id) {
        return new PluginInstance(id, UUID.randomUUID(), TYPE, false);
    }

    /** Gun with no declared stats, so only the plugin modifier / meta default apply. */
    private static GunDefinition gunDef() {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Full registry view: guns + the given plugins + attribute_meta. */
    private static RegistryAccess access(
            MappedRegistry<AttributeMeta> metas, Map<ResourceLocation, PluginDefinition> plugins) {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        plugins.forEach((id, def) -> Registry.register(pluginRegistry, id, def));
        return accessOf(metas, pluginRegistry);
    }

    private static RegistryAccess accessOf(
            MappedRegistry<AttributeMeta> metas, MappedRegistry<PluginDefinition> plugins) {
        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, GUN_ID, gunDef());
        return new RegistryAccess.ImmutableRegistryAccess(List.of(guns, plugins, metas));
    }

    private static ItemStack gunStack(GunData gunData) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), gunData);
        return stack;
    }

    private static ItemStack gunStack(PluginInstance plugin) {
        return gunStack(new GunData(GUN_ID, UUID.randomUUID(), List.of(plugin), 0, new CompoundTag()));
    }

    private static ItemAttributeModifiers current(ItemStack stack) {
        return stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    /** Returns the id of the single plugin modifier in an {@link ItemAttributeModifiers}. */
    private static ResourceLocation onlyModifierId(ItemAttributeModifiers modifiers) {
        return onlyPluginModifier(modifiers).modifier().id();
    }

    /** Returns the amount of the single plugin modifier in an {@link ItemAttributeModifiers}. */
    private static double onlyModifierAmount(ItemAttributeModifiers modifiers) {
        return onlyPluginModifier(modifiers).modifier().amount();
    }

    /**
     * Returns the single plugin modifier, filtering out the framework's base
     * modifier (whose id is {@code modularshoot:gun_base}).
     */
    private static ItemAttributeModifiers.Entry onlyPluginModifier(ItemAttributeModifiers modifiers) {
        List<ItemAttributeModifiers.Entry> pluginModifiers = modifiers.modifiers().stream()
                .filter(e -> e.modifier().id().getPath().startsWith("plugin_"))
                .toList();
        assertEquals(1, pluginModifiers.size(), "expected exactly one plugin modifier");
        return pluginModifiers.get(0);
    }
}
