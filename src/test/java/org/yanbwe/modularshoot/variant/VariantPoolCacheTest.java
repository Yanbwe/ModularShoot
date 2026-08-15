package org.yanbwe.modularshoot.variant;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-shot reassembly tests for {@link VariantPoolService#buildPool} (计划
 * §阶段 1 / 任务 1.3).
 *
 * <p>{@link VariantContributor#contribute} receives no per-shot/per-player
 * context, so a contributor is legitimately allowed to depend on external
 * state (player / time / world). A pool cached across shots or players would
 * therefore serve stale weights — the original per-shot assembly semantics
 * ("every shot rebuilds its pool") must be preserved. The only allowed reuse
 * is <em>within</em> a single shot: {@code ShootingEngine#registerPellets}
 * builds one {@link VariantPoolService.PoolBuild} per shot and shares it
 * across that shot's pellets (the per-pellet roll remains independent).</p>
 *
 * <p><b>Invariant under test:</b> every {@code buildPool} call (i) produces a
 * fresh (not identity-equal) pool and (ii) re-runs the rebuild work — it
 * re-invokes the registered {@link VariantContributor} and re-resolves the
 * installed plugin definitions. None of that work may be skipped by a
 * cross-call cache, because the contributor could return different weights on
 * the next shot.</p>
 */
class VariantPoolCacheTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:cache_gun");
    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("modularshoot:cache_plugin_a");
    private static final ResourceLocation VARIANT_A = ResourceLocation.parse("modularshoot:variant_a");

    @BeforeEach
    void clearContributors() {
        VariantContributorRegistry.clear();
    }

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

    private static GunDefinition gunWithVariants(Map<ResourceLocation, Double> variants) {
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
                variants,
                Map.of(),
                Optional.empty());
    }

    private static PluginInstance instance(ResourceLocation pluginId) {
        return new PluginInstance(pluginId, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
    }

    private static GunData gunDataWith(List<ResourceLocation> pluginIds, int modifierVersion) {
        return new GunData(GUN_ID, UUID.randomUUID(),
                pluginIds.stream().map(VariantPoolCacheTest::instance).toList(),
                modifierVersion, new CompoundTag());
    }

    // ---- Per-shot reassembly tests ----------------------------------------

    @Test
    void everyBuildReassemblesPoolAndReinvokesContributorAndPluginLookups() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"adds_variants\":{\"modularshoot:variant_a\":2.0}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        AtomicInteger contributorCalls = new AtomicInteger();
        VariantContributorRegistry.register(sink -> contributorCalls.incrementAndGet());

        GunDefinition gunDef = gunWithVariants(Map.of());
        GunData gunData = gunDataWith(List.of(PLUGIN_A), 0);

        VariantPoolService.PoolBuild first = VariantPoolService.buildPool(access, gunDef, gunData);
        int contributorRunsAfterFirst = contributorCalls.get();
        int pluginScansAfterFirst = access.pluginRegistryCalls.get();

        VariantPoolService.PoolBuild second = VariantPoolService.buildPool(access, gunDef, gunData);

        assertNotSame(first, second,
                "每次 buildPool 都必须重新组装（不允许跨发缓存的同一实例复用）");
        assertEquals(contributorRunsAfterFirst + 1, contributorCalls.get(),
                "每次 buildPool 都必须重新 collect 贡献者（第三方可依赖玩家/时间/世界状态）");
        assertTrue(access.pluginRegistryCalls.get() > pluginScansAfterFirst,
                "每次 buildPool 都必须重新解析已安装插件定义（不跨发缓存）");

        // 确定性输入下内容应当一致（只是不允许共享同一实例）。
        assertEquals(first, second, "相同输入下组装的池内容应一致");
        assertEquals(1, second.candidates().size(), "插件声明的变体候选仍进入池");
        assertEquals(VARIANT_A, second.candidates().get(0).id());
        assertEquals(2.0, second.candidates().get(0).weight(), 1e-9);
    }

    @Test
    void contributorExternalStateChangeIsReflectedOnNextBuild() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));

        // 枪械自身声明一个专用变体（基础权重 0）：候选恒存在，最终权重完全由
        // 贡献者的 ADD_VALUE 决定。使用独立 id 避免污染共享的
        // `modularshoot:variant_a`（contributor 注册表是全局静态的，本测试结束后
        // 其它测试类不清理 contributor）。
        ResourceLocation statefulVariant = ResourceLocation.parse("modularshoot:stateful_variant");
        AtomicInteger externalState = new AtomicInteger();
        VariantContributorRegistry.register(sink ->
                sink.add(statefulVariant, new AttributeModifier(
                        ResourceLocation.parse("modularshoot:stateful"),
                        externalState.incrementAndGet(),
                        AttributeModifier.Operation.ADD_VALUE)));

        GunDefinition gunDef = gunWithVariants(Map.of(statefulVariant, 0.0));
        GunData gunData = gunDataWith(List.of(), 0);

        VariantPoolService.PoolBuild first = VariantPoolService.buildPool(raw, gunDef, gunData);
        VariantPoolService.PoolBuild second = VariantPoolService.buildPool(raw, gunDef, gunData);

        assertNotSame(first, second, "每次射击都必须重新组装变体池");
        assertEquals(1.0, first.candidates().get(0).weight(), 1e-9,
                "第一次射击使用外部状态首次采样的权重");
        assertEquals(2.0, second.candidates().get(0).weight(), 1e-9,
                "第二次射击必须用最新的外部状态，不得返回缓存的陈旧权重");
    }

    @Test
    void candidatesListIsImmutable() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"adds_variants\":{\"modularshoot:variant_a\":2.0}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));

        GunDefinition gunDef = gunWithVariants(Map.of());
        GunData gunData = gunDataWith(List.of(PLUGIN_A), 0);

        VariantPoolService.PoolBuild build = VariantPoolService.buildPool(raw, gunDef, gunData);

        assertThrows(UnsupportedOperationException.class, () -> build.candidates().clear(),
                "PoolBuild.candidates 必须不可变，防止调用方破坏共享的池结果");
    }
}
