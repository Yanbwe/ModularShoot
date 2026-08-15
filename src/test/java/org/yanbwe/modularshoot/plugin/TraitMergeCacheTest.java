package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Cache tests for {@link TraitMergeService} (计划 §阶段 1 / 任务 1.3).
 *
 * <p>The service must resolve every installed plugin definition and run the
 * priority sort + merge <em>once</em> for a given
 * {@code (GunData 内容, modifierVersion, Registry 实例)}; a second call with
 * the same inputs must return the cached map without re-entering the plugin
 * registry. A {@code /reload} swaps the {@link Registry} instance, so the same
 * logical gun must then be recomputed against the new instance (weak-keyed
 * cache — never stale across reloads).
 *
 * <p>Same JUnit bridge as {@code TraitMergeServiceTest}: bootstrapped vanilla
 * registries + a reflection-bound {@code GUN_DATA} component holder so
 * {@code ItemStack} reads work headless. Every test uses a fresh
 * {@link MappedRegistry} so the shared static {@link PluginRegistry} lookup
 * cache cannot leak state across cases.</p>
 */
class TraitMergeCacheTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:cache_gun");
    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("modularshoot:cache_plugin_a");
    private static final ResourceLocation PLUGIN_B = ResourceLocation.parse("modularshoot:cache_plugin_b");
    private static final ResourceLocation TRAIT = ResourceLocation.parse("modularshoot:auto_fire");

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static boolean bindGunDataHolder() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            return true;
        } catch (Exception e) {
            System.err.println("[TraitMergeCacheTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    private static final boolean GUN_DATA_BOUND = bindGunDataHolder();

    /**
     * Fails fast when the {@code GUN_DATA} reflection bind did not succeed,
     * instead of silently skipping the component-dependent assertions (which
     * would let the cache invariant pass untested).
     */
    private static void requireGunDataBound() {
        if (!GUN_DATA_BOUND) {
            org.junit.jupiter.api.Assertions.fail(
                    "GUN_DATA DeferredHolder 未绑定，无法验证缓存不变量（见类 javadoc 桥接说明）");
        }
    }

    // ---- Counting infrastructure ------------------------------------------

    /**
     * A {@link RegistryAccess} that counts how many times the plugins registry
     * is requested. {@link PluginRegistry#getPlugin} resolves the registry view
     * on every lookup, so this count separates "resolving every plugin again"
     * (2&times;N per merge) from "only fetching the weak-key token once" (+1).
     */
    private static final class CountingRegistryAccess implements RegistryAccess {
        private final RegistryAccess delegate;
        final AtomicInteger pluginRegistryCalls = new AtomicInteger();

        CountingRegistryAccess(RegistryAccess delegate) {
            this.delegate = delegate;
        }

        @Override
        public <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key) {
            if (ModularShootRegistries.PLUGINS_KEY.equals(key)) {
                pluginRegistryCalls.incrementAndGet();
            }
            return delegate.registry(key);
        }

        @Override
        public Stream<RegistryAccess.RegistryEntry<?>> registries() {
            return delegate.registries();
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private static PluginDefinition parsePlugin(String json) {
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static GunDefinition gunWithTraits(Map<ResourceLocation, Boolean> traits) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                traits,
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static PluginInstance instance(ResourceLocation pluginId) {
        return new PluginInstance(pluginId, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
    }

    private static ItemStack stackWith(GunData gunData) {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), gunData);
        return stack;
    }

    // ---- Cache hit / invalidation tests ------------------------------------

    @Test
    void sameGunDataAndRegistryReturnsCachedResultWithoutPluginRescans() {
        requireGunDataBound();
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"priority\":100,\"traits\":{\"modularshoot:auto_fire\":true}}"));
        Registry.register(pluginRegistry, PLUGIN_B,
                parsePlugin("{\"item_icon\":\"m:icon\",\"priority\":200,\"traits\":{\"modularshoot:auto_fire\":false}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        GunDefinition gunDef = gunWithTraits(Map.of());
        ItemStack stack = stackWith(new GunData(
                GUN_ID, UUID.randomUUID(),
                List.of(instance(PLUGIN_A), instance(PLUGIN_B)), 0, new CompoundTag()));

        Map<ResourceLocation, Boolean> first = TraitMergeService.computeTraits(stack, access, gunDef);
        int pluginScansAfterFirst = access.pluginRegistryCalls.get();

        Map<ResourceLocation, Boolean> second = TraitMergeService.computeTraits(stack, access, gunDef);

        assertSame(first, second,
                "相同 (GunData, modifierVersion, Registry) 下第二次调用必须复用缓存的合并结果（不新建 map）");
        assertEquals(pluginScansAfterFirst + 1, access.pluginRegistryCalls.get(),
                "缓存命中后第二次调用不得再次解析插件定义（仅一次弱键 token 获取）");

        // 行为不变：priority 200 的插件覆盖 priority 100（false 胜出）。
        assertEquals(Boolean.FALSE, second.get(TRAIT));
    }

    @Test
    void modifierVersionChangeInvalidatesTraitCache() {
        requireGunDataBound();
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"priority\":100,\"traits\":{\"modularshoot:auto_fire\":true}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        GunDefinition gunDef = gunWithTraits(Map.of());
        List<PluginInstance> installed = List.of(instance(PLUGIN_A));

        // 安装/卸载/锁定都会递增 modifierVersion——同插件列表但版本不同必须重算。
        ItemStack v0 = stackWith(new GunData(GUN_ID, UUID.randomUUID(), installed, 0, new CompoundTag()));
        ItemStack v1 = stackWith(new GunData(GUN_ID, UUID.randomUUID(), installed, 1, new CompoundTag()));

        Map<ResourceLocation, Boolean> first = TraitMergeService.computeTraits(v0, access, gunDef);
        int scansAfterFirst = access.pluginRegistryCalls.get();
        Map<ResourceLocation, Boolean> second = TraitMergeService.computeTraits(v1, access, gunDef);

        assertNotSame(first, second,
                "modifierVersion 变化（安装/卸载/锁定）后必须重建合并结果");
        assertEquals(first, second, "插件列表不变时内容仍应一致");
        assertEquals(scansAfterFirst + 1 + 2, access.pluginRegistryCalls.get(),
                "失效后必须重新执行插件解析（2 次 getPlugin + 1 次 token 获取）");
    }

    @Test
    void reloadSwapNewRegistryInstanceRebuildsAndNeverServesStaleResults() {
        // 旧注册表：A 声明 auto_fire=true；新注册表（/reload 换实例）：A 声明 false。
        MappedRegistry<PluginDefinition> oldRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(oldRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"priority\":100,\"traits\":{\"modularshoot:auto_fire\":true}}"));
        RegistryAccess oldAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(oldRegistry));

        MappedRegistry<PluginDefinition> newRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(newRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"priority\":100,\"traits\":{\"modularshoot:auto_fire\":false}}"));
        RegistryAccess newAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(newRegistry));

        GunDefinition gunDef = gunWithTraits(Map.of());
        List<PluginInstance> installed = List.of(instance(PLUGIN_A));
        ItemStack stack = stackWith(new GunData(GUN_ID, UUID.randomUUID(), installed, 0, new CompoundTag()));

        Map<ResourceLocation, Boolean> before = TraitMergeService.computeTraits(stack, oldAccess, gunDef);
        Map<ResourceLocation, Boolean> after = TraitMergeService.computeTraits(stack, newAccess, gunDef);

        assertEquals(Boolean.TRUE, before.get(TRAIT), "旧 registry 实例解析出 true");
        assertEquals(Boolean.FALSE, after.get(TRAIT), "reload 后必须用新 registry 实例解析出 false（不得返回旧结果）");
        assertNotSame(before, after, "新 Registry 实例上不得复用旧实例的缓存条目");
    }
}
