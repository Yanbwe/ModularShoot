package org.yanbwe.modularshoot.client.render;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.render.CompositeTextureBuilder.OverlayLayer;
import org.yanbwe.modularshoot.client.render.DynamicGunTextureCache.Key;
import org.yanbwe.modularshoot.client.render.PluginOverlayCompositor.OverlayRenderData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.OutlineSpec;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayBlend;
import org.yanbwe.modularshoot.plugin.OverlayFit;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the 阶段 4 / 任务 4.2 渲染热路径优化:
 *
 * <ul>
 *   <li>{@link DynamicGunTextureCache.Key} must cache its {@code hashCode} so
 *       per-frame {@link java.util.HashMap} lookups never re-traverse the whole
 *       {@code overlays} / {@code gunOutlines} lists again.</li>
 *   <li>{@link PluginOverlayCompositor} must reuse the cached immutable
 *       plugin-id list on a cache hit instead of re-running {@code List.copyOf}
 *       every frame.</li>
 * </ul>
 *
 * <p>Same headless JUnit bridge as {@code TraitMergeCacheTest} (bootstrap the
 * vanilla registries so a fresh {@link MappedRegistry} can back the plugins
 * registry).</p>
 */
class DynamicGunTextureCacheKeyTest {

    private static final ResourceLocation TEXTURE = ResourceLocation.parse("modularshoot:gun");
    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("modularshoot:cache_plugin_a");

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * A {@link List} that counts how many times its elements are read through
     * {@link #get(int)} / iteration. Lets the test prove a {@code hashCode}
     * call does not re-traverse the content.
     */
    private static final class CountingList<T> extends AbstractList<T> {
        private final List<T> delegate;
        int elementReads;

        CountingList(List<T> delegate) {
            this.delegate = List.copyOf(delegate);
        }

        @Override
        public T get(int index) {
            elementReads++;
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public Iterator<T> iterator() {
            // Route iteration through get() so every element read is counted
            // regardless of whether the caller traverses by index or for-each.
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < size();
                }

                @Override
                public T next() {
                    return get(index++);
                }
            };
        }
    }

    private static OverlayLayer layer(ResourceLocation texture) {
        return new OverlayLayer(texture, OverlayAlignment.TOP_LEFT, OverlayFit.NONE,
                new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), OverlayBlend.NORMAL, Optional.empty());
    }

    private static OutlineSpec spec() {
        return new OutlineSpec(new Vector3f(1.0F, 0.0F, 0.0F), 1.0F, 2);
    }

    private static PluginDefinition parsePlugin(String json) {
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static PluginInstance instance(ResourceLocation pluginId) {
        return new PluginInstance(pluginId, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
    }

    // ---- 1. Key hashCode caching ------------------------------------------

    @Test
    void keyHashCodeIsCachedAndDoesNotRetraverseLists() {
        CountingList<OverlayLayer> overlays =
                new CountingList<>(List.of(layer(TEXTURE), layer(ResourceLocation.parse("modularshoot:ov2"))));
        CountingList<OutlineSpec> gunOutlines = new CountingList<>(List.of(spec()));

        Key key = new Key(TEXTURE, overlays, gunOutlines, 7);

        int readsAfterConstruction = overlays.elementReads + gunOutlines.elementReads;

        int first = key.hashCode();
        int second = key.hashCode();
        int third = key.hashCode();

        assertEquals(first, second, "hashCode must be stable across calls");
        assertEquals(second, third, "hashCode must be stable across calls");
        assertEquals(
                readsAfterConstruction,
                overlays.elementReads + gunOutlines.elementReads,
                "Key hashCode 必须构造时缓存；后续调用不得再遍历 overlays/gunOutlines");
    }

    // ---- 2. Overlay collection cache-hit reuse -----------------------------

    @Test
    void overlayCollectionReusesCachedImmutablePluginIdListOnCacheHit() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"texture_overlay\":{\"texture\":\"m:ov\",\"layer\":0}}"));

        // Two distinct mutable probe lists with identical plugin-id content.
        List<ResourceLocation> probeA = new ArrayList<>(List.of(PLUGIN_A));
        List<ResourceLocation> probeB = new ArrayList<>(List.of(PLUGIN_A));

        List<ResourceLocation> canonicalA = PluginOverlayCompositor.canonicalPluginIds(probeA);
        List<ResourceLocation> canonicalB = PluginOverlayCompositor.canonicalPluginIds(probeB);

        assertSame(canonicalA, canonicalB,
                "缓存命中必须复用同一个不可变 plugin-id 列表，避免每帧重复 List.copyOf");
    }

    @Test
    void collectRenderDataReturnsSameCachedRenderDataAndOverlayListOnHit() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"texture_overlay\":{\"texture\":\"m:ov\",\"layer\":0}}"));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));

        // Different PluginInstance lists (fresh UUIDs) but identical plugin-id
        // content: the render data cache must still serve the same instance and
        // the same immutable overlay list (cache semantics unchanged).
        OverlayRenderData first =
                PluginOverlayCompositor.collectRenderData(List.of(instance(PLUGIN_A)), access);
        OverlayRenderData second =
                PluginOverlayCompositor.collectRenderData(List.of(instance(PLUGIN_A)), access);

        assertSame(first, second, "相同 plugin-id 内容必须复用缓存渲染数据");
        assertSame(first.overlays(), second.overlays(), "缓存命中必须返回同一个不可变 overlay 列表");
    }
}
