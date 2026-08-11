package org.yanbwe.modularshoot.registry.gun;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.ModularShootAPI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dynamic gun definition provider channel
 * (改进① 动态定义来源, 2026-08-10 探讨整理).
 *
 * <p>查询顺序契约：Java API 注册 → 提供者 → 数据包注册表。测试仅依赖
 * {@link RegistryAccess#EMPTY}（无数据包注册表），因此"回退到数据包"
 * 的用例表现为 provider 返回空后 {@code getGun} 返回空（EMPTY 下数据包
 * 注册表不存在）。</p>
 *
 * <p>静态状态隔离：每个用例注册的 provider 只响应唯一的测试 gunId
 * （uuid 后缀），不会命中其它测试类查询的 id（项目既有模式，见
 * {@code GunRegistryEnsureGunDataTest} 类 javadoc）。</p>
 */
class GunDefinitionProviderTest {

    private static final RegistryAccess EMPTY = RegistryAccess.EMPTY;

    /** 构造一个最小合法的 GunDefinition（仅 texture 必填）。 */
    private static GunDefinition minimalDef(String path) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.fromNamespaceAndPath("modularshoot", path),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(), Map.of(), Map.of(), Map.of(),
                Optional.empty(), Map.of(), Map.of(), Optional.empty());
    }

    /** 生成唯一 gunId（静态状态隔离，见类 javadoc）。 */
    private static ResourceLocation uniqueId(String discriminator) {
        return ResourceLocation.parse(
                "mypack:provider_" + discriminator + "_" + UUID.randomUUID());
    }

    @Test
    void providerProvidesDefinition() {
        ResourceLocation id = uniqueId("provide");
        GunDefinition provided = minimalDef("provided");
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(provided) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "提供者声明的 gunId 必须被解析");
        assertSame(provided, result.get(), "必须返回提供者提供的同一实例");
    }

    @Test
    void javaApiTakesPriorityOverProvider() {
        ResourceLocation id = uniqueId("priority");
        GunDefinition javaApiDef = minimalDef("java_api");
        GunDefinition providerDef = minimalDef("provider");
        GunRegistry.registerGun(id, javaApiDef);
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(providerDef) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "Java API 注册 + 提供者同时存在时必须可解析");
        assertSame(javaApiDef, result.get(), "Java API 注册必须优先于提供者");
    }

    @Test
    void providerEmptyFallsThrough() {
        ResourceLocation id = uniqueId("fallthrough");
        GunRegistry.registerGunDefinitionProvider(providerId -> Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isEmpty(),
                "提供者返回空时必须回退下一来源（EMPTY 无数据包注册表 → 空）");
    }

    @Test
    void firstNonEmptyProviderWinsInRegistrationOrder() {
        ResourceLocation id = uniqueId("order");
        GunDefinition first = minimalDef("first");
        GunDefinition second = minimalDef("second");
        // Both providers respond to the same id; the earlier-registered one wins.
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(first) : Optional.empty());
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(second) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "注册序在前的非空提供者必须胜出");
        assertSame(first, result.get(), "返回第一个非空提供者的定义");
    }

    @Test
    void emptyFirstProviderFallsThroughToNextProvider() {
        ResourceLocation id = uniqueId("order_fallthrough");
        GunDefinition def = minimalDef("second_wins");
        GunRegistry.registerGunDefinitionProvider(providerId -> Optional.empty());
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(def) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "前序提供者返回空时必须由后序提供者接手");
        assertSame(def, result.get());
    }

    @Test
    void nullProviderResultTreatedAsEmpty() {
        ResourceLocation id = uniqueId("null_result");
        GunDefinition def = minimalDef("after_null");
        GunRegistry.registerGunDefinitionProvider(providerId -> null);
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(def) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "null 结果必须按空处理并回退下一来源");
        assertSame(def, result.get());
    }

    @Test
    void throwingProviderIsSwallowedAndFallsThrough() {
        ResourceLocation id = uniqueId("throwing");
        GunDefinition def = minimalDef("after_throwing");
        GunRegistry.registerGunDefinitionProvider(providerId -> {
            throw new IllegalStateException("provider exploded");
        });
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(def) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "抛异常的提供者必须被吞掉并回退下一来源");
        assertSame(def, result.get());
    }

    @Test
    void nullProviderRegistrationRejected() {
        assertThrows(NullPointerException.class,
                () -> GunRegistry.registerGunDefinitionProvider(null),
                "null provider must be rejected at the boundary");
    }

    @Test
    void unregisteredIdStaysEmptyWithoutProviders() {
        // 回归：不注册任何 provider 时 getGun 行为与现状一致。
        ResourceLocation id = uniqueId("regression");

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isEmpty(), "未注册 id 必须返回空（无 provider、无数据包注册表）");
    }

    @Test
    void providerSkippedForUnrelatedIds() {
        ResourceLocation registeredId = uniqueId("related");
        ResourceLocation otherId = uniqueId("other");
        GunDefinition def = minimalDef("related");
        GunRegistry.registerGunDefinitionProvider(providerId ->
                providerId.equals(registeredId) ? Optional.of(def) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, otherId);

        assertTrue(result.isEmpty(), "提供者不得响应未声明的 gunId");
    }

    @Test
    void providerListViewExposesRegisteredProviders() {
        GunDefinitionProvider p = providerId -> Optional.empty();
        GunRegistry.registerGunDefinitionProvider(p);

        List<GunDefinitionProvider> view = GunRegistry.getGunDefinitionProviders();

        assertTrue(view.contains(p), "注册视图必须包含已注册的提供者");
        assertThrows(UnsupportedOperationException.class, () -> view.add(p),
                "视图必须为不可变快照");
    }

    @Test
    void facadeRegistersProvider() {
        ResourceLocation id = uniqueId("facade");
        GunDefinition def = minimalDef("facade");
        ModularShootAPI.registerGunDefinitionProvider(providerId ->
                providerId.equals(id) ? Optional.of(def) : Optional.empty());

        Optional<GunDefinition> result = GunRegistry.getGun(EMPTY, id);

        assertTrue(result.isPresent(), "门面注册的提供者必须生效");
        assertSame(def, result.get(), "必须返回门面注册提供者的定义");
    }
}
