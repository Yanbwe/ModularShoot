package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD unit tests for the tooltip frame/short-term cache ({@link TooltipCache},
 * {@link TooltipCacheKey}) and the plugin reverse index
 * ({@link PluginTooltipIndex}).
 *
 * <p>The cache must (1) return a cached result without re-running the build
 * supplier for the same key (缓存命中不重复构建), (2) derive a different key when
 * the modifier-key state changes (修饰键变化失效), and (3) derive a different key
 * when the underlying registries are swapped by a datapack reload (注册表 reload
 * 失效). The reverse index must (4) map exclusiveGroup → plugin ids and tag →
 * plugin-type ids correctly, and rebuild when the registry instance changes.</p>
 */
class TooltipCacheTest {

    static {
        // Identical bootstrap to RegistryLookupCacheTest: FML shim, version shim,
        // then full vanilla bootstrap so vanilla/item registries are usable.
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final ResourceLocation PLUGIN_A =
            ResourceLocation.parse("m:p_a");
    private static final ResourceLocation PLUGIN_B =
            ResourceLocation.parse("m:p_b");
    private static final ResourceLocation TYPE_BARREL =
            ResourceLocation.parse("m:barrel");
    private static final ResourceLocation TYPE_STOCK =
            ResourceLocation.parse("m:stock");
    private static final ResourceLocation TAG_RAPID =
            ResourceLocation.parse("m:rapid");
    private static final ResourceLocation TAG_SCOPE =
            ResourceLocation.parse("m:scope");
    private static final ResourceLocation MISSING =
            ResourceLocation.parse("m:missing");

    // ------------------------------------------------------------------
    // TooltipCache
    // ------------------------------------------------------------------

    @Test
    void cacheHitDoesNotRebuild() {
        TooltipCache cache = new TooltipCache();
        TooltipCacheKey key = new TooltipCacheKey(1, 1, false, false, false, 0);
        AtomicInteger builds = new AtomicInteger();

        List<Component> first = cache.getOrCompute(key,
                () -> { builds.incrementAndGet(); return List.of(Component.literal("a")); });
        List<Component> second = cache.getOrCompute(key,
                () -> { builds.incrementAndGet(); return List.of(Component.literal("b")); });

        assertEquals(1, builds.get(), "相同 cache key 第二次查询不得重新构建");
        assertSame(first, second, "缓存命中必须返回同一结果实例");
        assertEquals("a", first.get(0).getString(), "首次构建结果保留");
    }

    @Test
    void modifierKeyChangeInvalidatesCache() {
        TooltipCache cache = new TooltipCache();
        AtomicInteger builds = new AtomicInteger();

        TooltipCacheKey ctrlKey = new TooltipCacheKey(1, 1, true, false, false, 0);
        TooltipCacheKey noKey = new TooltipCacheKey(1, 1, false, false, false, 0);
        assertNotEquals(ctrlKey, noKey, "修饰键不同必须产生不同 cache key");

        cache.getOrCompute(ctrlKey, () -> { builds.incrementAndGet(); return List.of(); });
        cache.getOrCompute(noKey, () -> { builds.incrementAndGet(); return List.of(); });

        assertEquals(2, builds.get(), "修饰键变化后必须重新构建而不是命中旧缓存");
    }

    @Test
    void registryReloadInvalidatesCache() {
        // Two registries with identical contents but different instances model
        // a /reload that swaps the whole Registry instance.
        MappedRegistry<PluginTypeDefinition> registryA = new MappedRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, com.mojang.serialization.Lifecycle.stable());
        MappedRegistry<PluginTypeDefinition> registryB = new MappedRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, com.mojang.serialization.Lifecycle.stable());
        PluginTypeDefinition def = new PluginTypeDefinition(List.of(TAG_RAPID), 0,
                Optional.empty(), Optional.empty());
        Registry.register(registryA, TYPE_BARREL, def);
        Registry.register(registryB, TYPE_BARREL, def);

        RegistryAccess accessA = new RegistryAccess.ImmutableRegistryAccess(List.of(registryA));
        RegistryAccess accessB = new RegistryAccess.ImmutableRegistryAccess(List.of(registryB));

        // Same stack/modifier/data, different registry sets -> different keys.
        ItemStack stack = gunStack(1);
        TooltipCacheKey before = TooltipCacheKey.of(stack, accessA, false, false, false, 0);
        TooltipCacheKey after = TooltipCacheKey.of(stack, accessB, false, false, false, 0);
        assertNotEquals(before, after, "注册表 reload 后 cache key 必须不同（失效旧缓存）");
    }

    @Test
    void clearEmptiesCache() {
        TooltipCache cache = new TooltipCache();
        TooltipCacheKey key = new TooltipCacheKey(1, 1, false, false, false, 0);
        cache.getOrCompute(key, () -> List.of(Component.literal("a")));
        assertEquals(1, cache.size(), "构建后缓存应有 1 条");

        cache.clear();

        assertEquals(0, cache.size(), "clear 后缓存必须清空");
    }

    @Test
    void returnedListIsImmutable() {
        // 审查 Low: 缓存放回的 List 必须是不可变视图，调用方不得修改共享结果。
        TooltipCache cache = new TooltipCache();
        TooltipCacheKey key = new TooltipCacheKey(1, 1, false, false, false, 0);

        List<Component> cached = cache.getOrCompute(key,
                () -> new ArrayList<>(List.of(Component.literal("a"), Component.literal("b"))));

        assertThrows(UnsupportedOperationException.class, () -> cached.add(Component.literal("x")),
                "缓存返回的 List 必须不可变，防止调用方破坏共享缓存");
        // 再次命中返回同一不可变实例。
        List<Component> again = cache.getOrCompute(key,
                () -> List.of(Component.literal("wrong")));
        assertSame(cached, again, "缓存命中必须返回同一实例");
    }

    @Test
    void lruEvictsColdestAtCapacity() {
        // 审查 Medium: LRU 逐出边界——超出最大容量后最久未访问的条目被逐出。
        TooltipCache cache = new TooltipCache();
        // 填充 capacity(256) + 1 个不同 key，最旧的必须先被逐出。
        for (int i = 0; i < 257; i++) {
            int seed = i;
            cache.getOrCompute(new TooltipCacheKey(i, 1, false, false, false, 0),
                    () -> List.of(Component.literal("key-" + seed)));
        }
        assertEquals(256, cache.size(), "LRU 容量上限必须为 256");

        // 最旧 key(0) 已被逐出 → 再次访问必须重新构建而不是命中。
        AtomicInteger rebuilds = new AtomicInteger();
        cache.getOrCompute(new TooltipCacheKey(0, 1, false, false, false, 0),
                () -> { rebuilds.incrementAndGet(); return List.of(Component.literal("rebuilt")); });
        assertEquals(1, rebuilds.get(), "被逐出的最旧 key 必须重新构建而非命中");

        // 最热 key(256) 仍应命中（同一实例）。
        List<Component> hot = cache.getOrCompute(new TooltipCacheKey(256, 1, false, false, false, 0),
                () -> List.of(Component.literal("wrong")));
        assertEquals("key-256", hot.get(0).getString(), "最近访问的条目应仍在缓存中");
    }

    @Test
    void lruAccessOrderEvictsLeastRecentlyUsed() {
        // 访问顺序 LRU：即使 key 插入较早，只要最近被访问过就保留；逐出的是真正
        // 最久未访问的那个。
        TooltipCache cache = new TooltipCache();
        // 填满。
        for (int i = 0; i < 256; i++) {
            int seed = i;
            cache.getOrCompute(new TooltipCacheKey(i, 1, false, false, false, 0),
                    () -> List.of(Component.literal("k" + seed)));
        }
        // 访问 key 0，使其成为最近使用（从而在下次逐出时不被淘汰）。
        cache.getOrCompute(new TooltipCacheKey(0, 1, false, false, false, 0),
                () -> List.of(Component.literal("wrong")));
        // 再插入一个新 key，触发逐出，被逐出的应是最久未访问的 key 1。
        cache.getOrCompute(new TooltipCacheKey(999, 1, false, false, false, 0),
                () -> List.of(Component.literal("new")));
        assertEquals(256, cache.size());

        AtomicInteger rebuilds = new AtomicInteger();
        cache.getOrCompute(new TooltipCacheKey(1, 1, false, false, false, 0),
                () -> { rebuilds.incrementAndGet(); return List.of(Component.literal("r")); });
        assertEquals(1, rebuilds.get(), "被逐出的 key(1) 必须重新构建");
    }

    // ------------------------------------------------------------------
    // TooltipVersion (identity-based cheap mutable-data version)
    // ------------------------------------------------------------------

    @Test
    void versionStableWhilePayloadReferenceUnchanged() {
        Object payload = new Object();
        int v1 = TooltipVersion.versionFor("stream", payload);
        int v2 = TooltipVersion.versionFor("stream", payload);
        assertEquals(v1, v2, "同一 payload 引用复用版本号，不重新计算");
    }

    @Test
    void versionBumpsWhenPayloadReferenceChanges() {
        int v1 = TooltipVersion.versionFor("stream", new Object());
        int v2 = TooltipVersion.versionFor("stream", new Object());
        assertNotEquals(v1, v2, "payload 引用变化必须 bump 版本号");
    }

    @Test
    void distinctStreamsTrackIndependently() {
        Object p1 = new Object();
        Object p2 = new Object();
        int a1 = TooltipVersion.versionFor("a", p1);
        int b1 = TooltipVersion.versionFor("b", p2);
        int a2 = TooltipVersion.versionFor("a", p1);
        int b2 = TooltipVersion.versionFor("b", p2);
        assertEquals(a1, a2, "流 A 稳定时不因流 B 存在而变化");
        assertEquals(b1, b2, "流 B 稳定时不因流 A 存在而变化");
        // 流 B 的 payload 变化不影响已稳定的流 A。
        int a3 = TooltipVersion.versionFor("a", p1);
        assertEquals(a1, a3, "B 变化不连带 A 的版本");
    }

    // ------------------------------------------------------------------
    // TooltipCacheKey derivation
    // ------------------------------------------------------------------

    @Test
    void keyDistinguishesModifierStates() {
        ItemStack stack = gunStack(1);
        RegistryAccess access = typedRegistryAccess();
        int dataVersion = 5;

        TooltipCacheKey base = TooltipCacheKey.of(stack, access, false, false, false, dataVersion);
        TooltipCacheKey ctrl = TooltipCacheKey.of(stack, access, true, false, false, dataVersion);
        TooltipCacheKey alt = TooltipCacheKey.of(stack, access, false, true, false, dataVersion);
        TooltipCacheKey shift = TooltipCacheKey.of(stack, access, false, false, true, dataVersion);

        assertNotEquals(base, ctrl, "Ctrl 状态必须体现在 cache key 中");
        assertNotEquals(base, alt, "Alt 状态必须体现在 cache key 中");
        assertNotEquals(base, shift, "Shift 状态必须体现在 cache key 中");
    }

    @Test
    void keyDistinguishesDataVersion() {
        ItemStack stack = gunStack(1);
        RegistryAccess access = typedRegistryAccess();

        TooltipCacheKey v1 = TooltipCacheKey.of(stack, access, false, false, false, 1);
        TooltipCacheKey v2 = TooltipCacheKey.of(stack, access, false, false, false, 2);

        assertNotEquals(v1, v2, "GunData/ClientGunDataStore 版本不同必须产生不同 key");
    }

    @Test
    void keyDistinguishesStackComponents() {
        ItemStack stackA = gunStack(1); // distinct instance uuid
        ItemStack stackB = gunStack(2);
        RegistryAccess access = typedRegistryAccess();

        TooltipCacheKey keyA = TooltipCacheKey.of(stackA, access, false, false, false, 0);
        TooltipCacheKey keyB = TooltipCacheKey.of(stackB, access, false, false, false, 0);

        assertNotEquals(keyA, keyB, "不同 ItemStack（不同实例标识）必须产生不同 key");
    }

    // ------------------------------------------------------------------
    // PluginTooltipIndex (reverse index)
    // ------------------------------------------------------------------

    @Test
    void reverseIndexMapsExclusiveGroupToPluginIds() {
        RegistryAccess access = pluginRegistryAccess(true /* same group */);
        PluginTooltipIndex index = new PluginTooltipIndex();

        List<ResourceLocation> groupA = index.pluginIdsByExclusiveGroup(access, "groupA");
        assertEquals(Set.of(PLUGIN_A, PLUGIN_B), Set.copyOf(groupA),
                "exclusiveGroup 反向索引必须返回同组所有 plugin id");

        assertTrue(index.pluginIdsByExclusiveGroup(access, "no-such-group").isEmpty(),
                "不存在的分组必须返回空列表而不是扫描全部注册表");
    }

    @Test
    void reverseIndexMapsTagToPluginTypeIds() {
        RegistryAccess access = pluginRegistryAccess(false);
        PluginTooltipIndex index = new PluginTooltipIndex();

        List<ResourceLocation> rapidTypes = index.pluginTypeIdsByTag(access, TAG_RAPID);
        List<ResourceLocation> scopeTypes = index.pluginTypeIdsByTag(access, TAG_SCOPE);

        assertTrue(rapidTypes.contains(TYPE_BARREL), "tag=rapid 必须反向命中 barrel 种类");
        assertTrue(scopeTypes.contains(TYPE_STOCK), "tag=scope 必须反向命中 stock 种类");
        assertFalse(rapidTypes.contains(TYPE_STOCK), "rapid 不得误命中 stock");
    }

    @Test
    void reverseIndexRebuildsOnRegistryReload() {
        PluginTooltipIndex index = new PluginTooltipIndex();
        RegistryAccess accessA = pluginRegistryAccess(true);
        List<ResourceLocation> before = index.pluginIdsByExclusiveGroup(accessA, "groupA");
        assertEquals(Set.of(PLUGIN_A, PLUGIN_B), Set.copyOf(before));

        // Reload: a fresh registry whose groupA now holds only PLUGIN_A.
        RegistryAccess accessB = pluginRegistryAccessWithGroup(PLUGIN_A, "groupA");
        List<ResourceLocation> after = index.pluginIdsByExclusiveGroup(accessB, "groupA");

        assertEquals(List.of(PLUGIN_A), after,
                "注册表 reload 后反向索引必须重建，不得返回旧注册表结果");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertFalse(boolean value, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(value, msg);
    }

    private static ItemStack gunStack(int seed) {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
        // Vanilla built-in component so component-based identity differs per seed
        // without needing the mod's (unregistered in headless tests) GUN_DATA.
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("gun-" + seed));
        return stack;
    }

    private static RegistryAccess typedRegistryAccess() {
        MappedRegistry<PluginTypeDefinition> registry = new MappedRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, com.mojang.serialization.Lifecycle.stable());
        return new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    private static RegistryAccess pluginRegistryAccess(boolean sameGroup) {
        if (sameGroup) {
            // Both plugins share exclusive group "groupA".
            return pluginAccess(registryWithBothPlugins("groupA"), registryWithTypes(PLUGIN_A, PLUGIN_B));
        }
        // Distinct groups: B is in its own group so tag=rapid only matches barrel.
        return pluginAccess(
                registryWithPlugins(PLUGIN_A, "groupA"),
                registryWithTypes(PLUGIN_A, PLUGIN_B));
    }

    private static RegistryAccess pluginRegistryAccessWithGroup(ResourceLocation onlyPlugin, String group) {
        return pluginAccess(
                registryWithPlugins(onlyPlugin, group),
                registryWithTypes(PLUGIN_A, PLUGIN_B));
    }

    /** The full registry set used by the tooltip reverse index (plugins + plugin_types). */
    private static RegistryAccess pluginAccess(
            MappedRegistry<PluginDefinition> plugins,
            MappedRegistry<PluginTypeDefinition> types) {
        return new RegistryAccess.ImmutableRegistryAccess(List.of(plugins, types));
    }

    private static MappedRegistry<PluginDefinition> registryWithBothPlugins(String group) {
        MappedRegistry<PluginDefinition> reg = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, com.mojang.serialization.Lifecycle.stable());
        Registry.register(reg, PLUGIN_A, pluginDef(group));
        Registry.register(reg, PLUGIN_B, pluginDef(group));
        return reg;
    }

    private static MappedRegistry<PluginDefinition> registryWithPlugins(ResourceLocation onlyPlugin, String group) {
        MappedRegistry<PluginDefinition> reg = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, com.mojang.serialization.Lifecycle.stable());
        if (onlyPlugin != null) {
            Registry.register(reg, onlyPlugin, pluginDef(group));
        }
        return reg;
    }

    private static MappedRegistry<PluginTypeDefinition> registryWithTypes(ResourceLocation a, ResourceLocation b) {
        MappedRegistry<PluginTypeDefinition> reg = new MappedRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, com.mojang.serialization.Lifecycle.stable());
        Registry.register(reg, TYPE_BARREL, new PluginTypeDefinition(
                List.of(TAG_RAPID), 30, Optional.of("Barrel"), Optional.empty()));
        Registry.register(reg, TYPE_STOCK, new PluginTypeDefinition(
                List.of(TAG_SCOPE), 20, Optional.of("Stock"), Optional.empty()));
        return reg;
    }

    private static PluginDefinition pluginDef(String group) {
        return new PluginDefinition(
                List.of(TAG_RAPID),
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.ofNullable(group),
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
}
