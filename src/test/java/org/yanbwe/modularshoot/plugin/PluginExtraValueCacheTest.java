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
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cache tests for {@link PluginExtraValueService} (计划 §阶段 1 / 任务 1.3).
 *
 * <p>The {@code ItemStack}-backed aggregate must resolve every installed
 * plugin definition and re-sum all {@code extra_values} <em>once</em> for a
 * given {@code (GunData 内容, modifierVersion, Registry 实例)}; a second call
 * with identical inputs returns the cached sums without re-parsing the plugin
 * definitions. Changing the {@code modifierVersion} (install/uninstall/lock)
 * or swapping the plugins {@link Registry} instance ({@code /reload}) must
 * rebuild.</p>
 *
 * <p>Same JUnit bridge as {@code TraitMergeServiceTest} (bootstrapped vanilla
 * + reflection-bound {@code GUN_DATA} holder). Fresh registries per test keep
 * the shared {@link PluginRegistry} lookup cache isolated.</p>
 */
class PluginExtraValueCacheTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:extra_gun");
    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("modularshoot:extra_plugin_a");
    private static final ResourceLocation PLUGIN_B = ResourceLocation.parse("modularshoot:extra_plugin_b");
    private static final ResourceLocation RARITY = ResourceLocation.parse("raritycore:rarity");

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
            System.err.println("[PluginExtraValueCacheTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    private static final boolean GUN_DATA_BOUND = bindGunDataHolder();

    // ---- Counting infrastructure ------------------------------------------

    /** Counts requests for the plugins registry view (per-plugin resolution). */
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

    /** Gun definition carrying only the given base {@code extra_values}. */
    private static GunDefinition gunWithExtraValues(Map<ResourceLocation, Double> extraValues) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                extraValues,
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
    void sameStackAndRegistryReturnsCachedSumsWithoutParsingPluginsAgain() {
        assumeTrue(GUN_DATA_BOUND, "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        GunRegistry.registerGun(GUN_ID, gunWithExtraValues(Map.of(RARITY, 10.0)));
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"extra_values\":{\"raritycore:rarity\":5.0}}"));
        Registry.register(pluginRegistry, PLUGIN_B,
                parsePlugin("{\"item_icon\":\"m:icon\",\"extra_values\":{\"raritycore:rarity\":3.0}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        ItemStack stack = stackWith(new GunData(
                GUN_ID, UUID.randomUUID(),
                List.of(instance(PLUGIN_A), instance(PLUGIN_B)), 0, new CompoundTag()));

        Map<ResourceLocation, Double> first = PluginExtraValueService.aggregate(stack, access);
        int pluginScansAfterFirst = access.pluginRegistryCalls.get();

        Map<ResourceLocation, Double> second = PluginExtraValueService.aggregate(stack, access);

        assertSame(first, second,
                "相同 (ItemStack, modifierVersion, Registry) 下第二次调用必须复用缓存的聚合结果");
        assertEquals(pluginScansAfterFirst + 1, access.pluginRegistryCalls.get(),
                "缓存命中后第二次调用不得从零解析全部插件定义（仅一次弱键 token 获取）");

        // 行为不变：10 + 5 + 3 = 18。
        assertEquals(18.0, second.get(RARITY), 1e-9);
    }

    @Test
    void modifierVersionChangeInvalidatesExtraValueCache() {
        assumeTrue(GUN_DATA_BOUND, "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        GunRegistry.registerGun(GUN_ID, gunWithExtraValues(Map.of(RARITY, 10.0)));
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"extra_values\":{\"raritycore:rarity\":5.0}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        List<PluginInstance> installed = List.of(instance(PLUGIN_A));
        ItemStack v0 = stackWith(new GunData(GUN_ID, UUID.randomUUID(), installed, 0, new CompoundTag()));
        ItemStack v1 = stackWith(new GunData(GUN_ID, UUID.randomUUID(), installed, 1, new CompoundTag()));

        Map<ResourceLocation, Double> first = PluginExtraValueService.aggregate(v0, access);
        int scansAfterFirst = access.pluginRegistryCalls.get();
        Map<ResourceLocation, Double> second = PluginExtraValueService.aggregate(v1, access);

        assertNotSame(first, second, "modifierVersion 变化后必须重建聚合结果");
        assertEquals(first, second, "插件列表不变时内容仍应一致");
        assertEquals(scansAfterFirst + 1 + 2, access.pluginRegistryCalls.get(),
                "失效后必须重新解析插件定义（2 次 getPlugin + 1 次 token 获取）");
    }

    @Test
    void reloadSwapNewRegistryInstanceRebuildsAndNeverServesStaleSums() {
        // 旧 registry：A 贡献 5.0；新 registry（/reload 换实例）：A 贡献 50.0。
        GunRegistry.registerGun(GUN_ID, gunWithExtraValues(Map.of(RARITY, 10.0)));
        MappedRegistry<PluginDefinition> oldRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(oldRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"extra_values\":{\"raritycore:rarity\":5.0}}"));
        RegistryAccess oldAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(oldRegistry));

        MappedRegistry<PluginDefinition> newRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(newRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"extra_values\":{\"raritycore:rarity\":50.0}}"));
        RegistryAccess newAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(newRegistry));

        ItemStack stack = stackWith(new GunData(
                GUN_ID, UUID.randomUUID(), List.of(instance(PLUGIN_A)), 0, new CompoundTag()));

        Map<ResourceLocation, Double> before = PluginExtraValueService.aggregate(stack, oldAccess);
        Map<ResourceLocation, Double> after = PluginExtraValueService.aggregate(stack, newAccess);

        assertEquals(15.0, before.get(RARITY), 1e-9, "旧 registry 实例解析出 10 + 5");
        assertEquals(60.0, after.get(RARITY), 1e-9, "reload 后必须用新 registry 实例解析出 10 + 50");
        assertNotSame(before, after, "新 Registry 实例上不得复用旧实例的缓存条目");
    }
}
