package org.yanbwe.modularshoot.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RegistryLookupCache} — the per-{@link Registry}
 * weak-reference cache that backs the definition-class registry facades.
 *
 * <p>The cache must (1) resolve each id from the underlying registry exactly
 * once per {@link Registry} instance, (2) rebuild after a {@code /reload}
 * swaps in a new {@link Registry} instance (never serving stale results from
 * the previous instance), and (3) preserve the original {@code empty}
 * semantics for ids that do not exist (including caching the miss so a
 * repeated missing-id query does not hit the underlying registry again).</p>
 */
class RegistryLookupCacheTest {

    private static final ResourceKey<Registry<PluginTypeDefinition>> KEY =
            ModularShootRegistries.PLUGIN_TYPES_KEY;
    private static final ResourceLocation ID =
            ResourceLocation.parse("examplemod:barrel");
    private static final ResourceLocation MISSING_ID =
            ResourceLocation.parse("examplemod:does_not_exist");

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to CrossReferenceValidatorTest's probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** A {@link MappedRegistry} that counts {@link #getOptional} invocations. */
    private static final class CountingRegistry extends MappedRegistry<PluginTypeDefinition> {
        final AtomicInteger getOptionalCalls = new AtomicInteger();

        CountingRegistry() {
            super(KEY, com.mojang.serialization.Lifecycle.stable());
        }

        @Override
        public Optional<PluginTypeDefinition> getOptional(ResourceLocation id) {
            getOptionalCalls.incrementAndGet();
            return super.getOptional(id);
        }
    }

    private static PluginTypeDefinition definition(String tag) {
        return new PluginTypeDefinition(
                List.of(ResourceLocation.parse(tag)), 0, Optional.empty(), Optional.empty());
    }

    @Test
    void repeatedQueryWithSameRegistryAccessHitsUnderlyingRegistryOnce() {
        CountingRegistry registry = new CountingRegistry();
        Registry.register(registry, ID, definition("mypack:kind"));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(registry));

        RegistryLookupCache<PluginTypeDefinition> cache = new RegistryLookupCache<>();

        Optional<PluginTypeDefinition> first = cache.get(access, KEY, ID);
        Optional<PluginTypeDefinition> second = cache.get(access, KEY, ID);

        assertTrue(first.isPresent(), "registered id must resolve");
        assertEquals(1, registry.getOptionalCalls.get(),
                "重复查询同一 Registry 实例必须只访问底层 registry 一次");
        assertSame(first.get(), second.orElseThrow(),
                "缓存命中必须返回同一解析结果");
    }

    @Test
    void newRegistryInstanceRebuildsCacheWithoutStaleResults() {
        CountingRegistry firstRegistry = new CountingRegistry();
        Registry.register(firstRegistry, ID, definition("mypack:old_kind"));
        RegistryAccess firstAccess = new RegistryAccess.ImmutableRegistryAccess(
                List.of(firstRegistry));

        CountingRegistry secondRegistry = new CountingRegistry();
        PluginTypeDefinition newDefinition = definition("mypack:new_kind");
        Registry.register(secondRegistry, ID, newDefinition);
        RegistryAccess secondAccess = new RegistryAccess.ImmutableRegistryAccess(
                List.of(secondRegistry));

        RegistryLookupCache<PluginTypeDefinition> cache = new RegistryLookupCache<>();

        Optional<PluginTypeDefinition> before = cache.get(firstAccess, KEY, ID);
        Optional<PluginTypeDefinition> after = cache.get(secondAccess, KEY, ID);

        assertTrue(before.isPresent() && after.isPresent());
        assertNotSame(before.get(), after.get(),
                "新 Registry 实例后不得返回旧实例的陈旧结果");
        assertSame(newDefinition, after.get(), "reload 后必须返回新实例解析出的定义");
        assertEquals(1, secondRegistry.getOptionalCalls.get(),
                "新 Registry 实例上第一次查询才会访问其底层 registry");
    }

    @Test
    void missingIdReturnsEmptyAndIsCachedConsistently() {
        CountingRegistry registry = new CountingRegistry();
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(registry));

        RegistryLookupCache<PluginTypeDefinition> cache = new RegistryLookupCache<>();

        Optional<PluginTypeDefinition> first = cache.get(access, KEY, MISSING_ID);
        Optional<PluginTypeDefinition> second = cache.get(access, KEY, MISSING_ID);

        assertTrue(first.isEmpty(), "不存在的 id 必须返回 empty（与原有语义一致）");
        assertTrue(second.isEmpty(), "缓存的不存在结果也必须返回 empty");
        assertEquals(1, registry.getOptionalCalls.get(),
                "不存在 id 的 miss 结果同样只访问底层 registry 一次");
    }

    @Test
    void facadeLookupsGoThroughCache() {
        CountingRegistry registry = new CountingRegistry();
        Registry.register(registry, ID, definition("mypack:kind"));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(registry));

        Optional<PluginTypeDefinition> first = PluginTypeRegistry.getPluginType(access, ID);
        Optional<PluginTypeDefinition> second = PluginTypeRegistry.getPluginType(access, ID);

        assertTrue(first.isPresent());
        assertSame(first.get(), second.orElseThrow());
        assertEquals(1, registry.getOptionalCalls.get(),
                "facade 的 get 查询必须复用 RegistryLookupCache 而不是每次直查");
    }

    @Test
    void getAllIdsReturnsCachedIdSet() {
        CountingRegistry registry = new CountingRegistry();
        Registry.register(registry, ID, definition("mypack:kind"));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(registry));

        RegistryLookupCache<PluginTypeDefinition> cache = new RegistryLookupCache<>();

        Set<ResourceLocation> ids = cache.getAllIds(access, KEY);
        Set<ResourceLocation> again = cache.getAllIds(access, KEY);

        assertEquals(Set.of(ID), ids);
        assertEquals(Set.of(ID), again);
    }
}
