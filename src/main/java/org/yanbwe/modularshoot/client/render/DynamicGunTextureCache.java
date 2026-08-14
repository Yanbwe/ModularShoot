package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.render.CompositeTextureBuilder.OverlayLayer;
import org.yanbwe.modularshoot.plugin.OutlineSpec;

/**
 * Client-side cache for dynamically composited gun / plugin item textures.
 *
 * <p>The framework renders gun and plugin items without any baked model
 * dependency: content authors only provide PNG textures (and, for guns,
 * optionally plugin {@code texture_overlay} layers). This cache is the bridge
 * between the resource-pack PNGs and the GPU: on a cache miss it loads the
 * base texture, composites the overlay layers on top (via
 * {@link CompositeTextureBuilder}), uploads the result as a
 * {@link DynamicTexture} under a framework-managed
 * {@code modularshoot:dynamic/...} location, and hands that location to the
 * caller for quad rendering (see {@link DynamicItemModelRenderer}).</p>
 *
 * <p><b>Outline masks:</b> when the key declares {@code gun_outline} specs,
 * a second white outline-mask texture (same dimensions, same cache key) is
 * registered alongside the composite — see {@link TextureHandle#maskLocation}.
 * The mask is extracted from the image <em>before</em> the static strokes are
 * baked, so its footprint matches them exactly; renderers draw it on top of
 * the composite multiplied by a per-frame tint to produce dynamically
 * coloured outlines without re-compositing (设计文档 §动态描边).</p>
 *
 * <p><b>Placeholder fallback:</b> when the base texture cannot be loaded
 * (missing PNG), a programmatically generated 16×16 placeholder image is
 * uploaded instead so the rendering pipeline stays visible and diagnosable —
 * a dark-grey block with a magenta diagonal instead of the vanilla
 * purple-black missing texture. The failure is logged once per distinct key.</p>
 *
 * <p><b>Key invalidation:</b> the cache key is
 * {@code (texture path, overlay list, gun outline list, modifierVersion)}.
 * Overlay and gun-outline changes are reflected by the key, and the caller
 * feeds the server-pushed modifier version
 * ({@link org.yanbwe.modularshoot.client.ClientGunDataStore#getModifierVersion})
 * so plugin installs/uninstalls produce a new key and a re-composite.
 * {@link #clear()} releases every registered texture and is invoked from the
 * {@code onResourceManagerReload} of the item renderers, so an F3+T resource
 * reload re-reads the (possibly changed) PNGs from disk.</p>
 *
 * <p><b>Threading:</b> all access happens on the main render thread (the
 * renderers are only invoked from the vanilla item pipeline). The singleton
 * instance is lazily created on first render, matching
 * {@link org.yanbwe.modularshoot.client.render.BulletRenderManager}.</p>
 *
 * @see CompositeTextureBuilder
 * @see DynamicItemModelRenderer
 * @see org.yanbwe.modularshoot.client.render.GunItemRenderer
 */
public final class DynamicGunTextureCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Size of the programmatic placeholder texture in pixels. */
    private static final int PLACEHOLDER_SIZE = 16;

    /**
     * Upper bound on cached texture entries. Plugin installs/uninstalls
     * produce new cache keys (modifier version), so the cache is bounded:
     * each insert over the limit evicts the least-recently-used entry,
     * releasing the GPU textures of long-forgotten gun/plugin configurations.
     */
    private static final int MAX_ENTRIES = 64;

    private static DynamicGunTextureCache instance;

    /**
     * Cache key → registered texture location, in <em>true LRU</em> order
     * (审查优化 P19: 插入序逐出是 FIFO，会逐出正在使用的变体——例如 per_shot
     * 交替访问 base/shoot 双纹理时；accessOrder 才是真 LRU)：命中会刷新条目
     * 位置，逐出时淘汰最久未访问的条目。逐出策略封装在 {@link LruStore}，
     * 便于单元测试。
     */
    private final LruStore<Key, TextureHandle> locations =
            new LruStore<>(MAX_ENTRIES, DynamicGunTextureCache::releaseHandle);
    private int nextId;

    private DynamicGunTextureCache() {
    }

    /**
     * Bounded LRU store backing the texture cache (审查优化 P19 配套：逐出
     * 策略提取为纯容器，可直接单元测试).
     *
     * <p>An access-order {@link LinkedHashMap}: a {@link #get} hit or a
     * {@link #put} replacing an existing key refreshes that entry to
     * most-recently-used. When {@link #put} would insert a new entry beyond
     * {@code maxEntries}, the least-recently-used entry is evicted first via
     * {@code onEvict} (GPU texture release in production). Not thread-safe —
     * the texture cache is only touched on the render thread.</p>
     */
    static final class LruStore<K, V> {

        private final int maxEntries;
        private final Map<K, V> map = new LinkedHashMap<>(16, 0.75f, true);
        private final Consumer<V> onEvict;

        LruStore(int maxEntries, Consumer<V> onEvict) {
            this.maxEntries = maxEntries;
            this.onEvict = onEvict;
        }

        /**
         * Returns the value for a key, refreshing its recency when present.
         *
         * @param key the key to look up
         * @return the stored value, or {@code null} when absent
         */
        @Nullable
        V get(K key) {
            return map.get(key);
        }

        /**
         * Inserts or replaces a value. Replacing an existing key refreshes
         * its recency without evicting anything; inserting a new key evicts
         * the least-recently-used entry first when at capacity.
         *
         * @param key   the key to insert
         * @param value the value to store
         */
        void put(K key, V value) {
            if (map.containsKey(key)) {
                // Replacement refreshes recency; size does not grow, so no
                // eviction may run (a naive evict-then-put would wrongly
                // drop the oldest entry for a same-size replace).
                map.put(key, value);
                return;
            }
            while (map.size() >= maxEntries) {
                Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
                Map.Entry<K, V> oldest = it.next();
                it.remove();
                onEvict.accept(oldest.getValue());
            }
            map.put(key, value);
        }

        int size() {
            return map.size();
        }

        boolean isEmpty() {
            return map.isEmpty();
        }

        /** Live view of the stored values (iteration order = recency order). */
        Collection<V> values() {
            return map.values();
        }

        void clear() {
            map.clear();
        }
    }

    /**
     * Cache key identifying a unique composited texture.
     *
     * @param texturePath     the resolved base (or shoot) texture path
     * @param overlays        the sorted overlay layers, bottom-to-top, each
     *                        carrying its placement/sizing parameters;
     *                        empty when the item has no overlay layers
     * @param gunOutlines     the whole-gun outline specs painted around the
     *                        composited silhouette, in installation order;
     *                        part of the cache key so editing a plugin's
     *                        {@code gun_outline} definition and reloading
     *                        (F3+T) triggers a re-composite
     * @param modifierVersion the gun's anti-cheat modifier version, used to
     *                        force a re-composite when plugins change even if
     *                        the overlay list is unchanged
     */
    public record Key(ResourceLocation texturePath, List<OverlayLayer> overlays,
                      List<OutlineSpec> gunOutlines, int modifierVersion) {

        /**
         * 值语义相等性（审查优化 P5）：JOML {@link Vector4f}/{@link Vector3f}
         * 不覆写 equals/hashCode，朴素 record 相等性会退化为向量对象身份——
         * 任何重新解码的定义实例都会使缓存 miss 并触发每帧全量 PNG 解码+合成
         * +GPU 上传。此处对 tint/outline 颜色按 float 位值比较，值相同即相等，
         * 与实例身份无关。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            if (modifierVersion != other.modifierVersion || !texturePath.equals(other.texturePath)) {
                return false;
            }
            return listsEqual(overlays, other.overlays, Key::overlayLayerEquals)
                    && listsEqual(gunOutlines, other.gunOutlines, Key::outlineSpecEquals);
        }

        @Override
        public int hashCode() {
            int result = texturePath.hashCode();
            result = 31 * result + modifierVersion;
            for (OverlayLayer layer : overlays) {
                result = 31 * result + overlayLayerHash(layer);
            }
            for (OutlineSpec spec : gunOutlines) {
                result = 31 * result + outlineSpecHash(spec);
            }
            return result;
        }

        private static <T> boolean listsEqual(
                List<T> a, List<T> b, java.util.function.BiPredicate<T, T> elementEquals) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!elementEquals.test(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean overlayLayerEquals(OverlayLayer a, OverlayLayer b) {
            if (!a.texture().equals(b.texture())
                    || a.alignment() != b.alignment()
                    || a.fit() != b.fit()
                    || a.blend() != b.blend()) {
                return false;
            }
            if (!floatBitsEqual(a.tint(), b.tint())) {
                return false;
            }
            return a.outline().isEmpty() && b.outline().isEmpty()
                    || a.outline().isPresent() && b.outline().isPresent()
                    && outlineSpecEquals(a.outline().get(), b.outline().get());
        }

        private static int overlayLayerHash(OverlayLayer layer) {
            int result = layer.texture().hashCode();
            result = 31 * result + layer.alignment().hashCode();
            result = 31 * result + layer.fit().hashCode();
            result = 31 * result + layer.blend().hashCode();
            result = 31 * result + floatBitsHash(layer.tint());
            if (layer.outline().isPresent()) {
                result = 31 * result + outlineSpecHash(layer.outline().get());
            }
            return result;
        }

        private static boolean outlineSpecEquals(OutlineSpec a, OutlineSpec b) {
            if (a.alpha() != b.alpha() || a.width() != b.width()) {
                return false;
            }
            return Float.floatToIntBits(a.color().x) == Float.floatToIntBits(b.color().x)
                    && Float.floatToIntBits(a.color().y) == Float.floatToIntBits(b.color().y)
                    && Float.floatToIntBits(a.color().z) == Float.floatToIntBits(b.color().z);
        }

        private static int outlineSpecHash(OutlineSpec spec) {
            int result = Float.floatToIntBits(spec.color().x);
            result = 31 * result + Float.floatToIntBits(spec.color().y);
            result = 31 * result + Float.floatToIntBits(spec.color().z);
            result = 31 * result + Float.floatToIntBits(spec.alpha());
            return 31 * result + spec.width();
        }

        private static boolean floatBitsEqual(Vector4f a, Vector4f b) {
            return Float.floatToIntBits(a.x) == Float.floatToIntBits(b.x)
                    && Float.floatToIntBits(a.y) == Float.floatToIntBits(b.y)
                    && Float.floatToIntBits(a.z) == Float.floatToIntBits(b.z)
                    && Float.floatToIntBits(a.w) == Float.floatToIntBits(b.w);
        }

        private static int floatBitsHash(Vector4f v) {
            int result = Float.floatToIntBits(v.x);
            result = 31 * result + Float.floatToIntBits(v.y);
            result = 31 * result + Float.floatToIntBits(v.z);
            return 31 * result + Float.floatToIntBits(v.w);
        }
    }

    /**
     * Resolved dynamic texture plus the pixel dimensions of the composited
     * content and the complete item quad geometry.
     *
     * <p>When the key declares gun outlines, the canvas is padded around the
     * content (描边画布扩展) so outlines drawn outside the silhouette stay
     * visible even when the content touches the original canvas edge. The
     * padding never changes the content dimensions ({@code width} /
     * {@code height} stay the content size) — instead the geometry expands:
     * {@code quads} contains the front/back faces (a 9-grid over the content
     * rect plus the outline ring, built by
     * {@link SideQuadBuilder#buildMainFaces}) and the 1px silhouette edge
     * strips ({@link SideQuadBuilder#build}); every quad uses content-space
     * model coordinates and canvas-space UVs. The mask texture shares the
     * same dimensions and layout, so the same quad list renders both.</p>
     *
     * @param location     the registered texture location for quad rendering
     * @param maskLocation the registered white outline-mask texture location
     *                     (same dimensions as {@code location}), or
     *                     {@code null} when the key declares no gun outlines
     * @param width        the composited <em>content</em> width in pixels
     * @param height       the composited <em>content</em> height in pixels
     * @param quads        the complete item quad geometry (front/back faces
     *                     plus silhouette edge strips), in content-space
     *                     model coordinates and canvas-space UVs
     */
    public record TextureHandle(
            ResourceLocation location,
            @Nullable ResourceLocation maskLocation,
            int width,
            int height,
            List<SideQuad> quads) {
    }

    /**
     * Returns the singleton cache instance, creating it on first call.
     *
     * @return the dynamic texture cache
     */
    public static synchronized DynamicGunTextureCache getInstance() {
        if (instance == null) {
            instance = new DynamicGunTextureCache();
        }
        return instance;
    }

    /**
     * Returns the registered texture handle for the given key, compositing
     * and uploading a new dynamic texture on a cache miss.
     *
     * <p>Never returns {@code null}: a failed base-texture load falls back to
     * the programmatic placeholder so the renderer always has a texture to
     * draw. On miss the loaded PNGs are composited via
     * {@link CompositeTextureBuilder#composite} and the result is uploaded
     * through a {@link DynamicTexture} registered at a fresh
     * {@code modularshoot:dynamic/gun_<n>} location. The handle carries the
     * composited image's pixel dimensions (16×16 for the placeholder) so
     * callers can scale the render geometry to the texture resolution.</p>
     *
     * @param key the cache key; must not be {@code null}
     * @return the texture handle for the composited image (never
     *         {@code null})
     */
    public TextureHandle getOrCreate(Key key) {
        TextureHandle existing = locations.get(key);
        if (existing != null) {
            return existing;
        }

        // LRU bound enforcement lives inside LruStore.put: inserting a new
        // entry over the limit evicts the least-recently-used one, releasing
        // the GPU textures of stale configurations.
        ResourceLocation location =
                ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "dynamic/gun_" + nextId++);

        // Composite WITHOUT baking the gun outlines: the outline mask must be
        // derived from the original silhouette (strokes would widen the mask
        // if they were already painted), so the outlines are baked here only
        // after the mask has been extracted from the same image.
        NativeImage image = CompositeTextureBuilder.composite(key.texturePath(), key.overlays(), List.of());
        if (image == null) {
            LOGGER.warn(
                    "Gun texture {} could not be loaded; using programmatic placeholder",
                    key.texturePath());
            image = createPlaceholder();
        }
        // Read the content dimensions before ownership transfers to the
        // DynamicTexture: geometry scaling is always based on the content
        // size, never the padded canvas (描边画布扩展).
        int width = image.getWidth();
        int height = image.getHeight();

        // Outlines painted outside the silhouette need canvas room beyond the
        // content edges; pad by the widest outline so an edge-touching
        // silhouette keeps its full outer stroke instead of clipping it at
        // the canvas border. The content is shifted to the pad; render
        // geometry scales from the content size and the UV rect becomes a
        // content sub-rect of the canvas.
        int pad = 0;
        if (!key.gunOutlines().isEmpty()) {
            pad = key.gunOutlines().stream().mapToInt(OutlineSpec::width).max().orElse(0);
        }
        NativeImage canvas = pad > 0 ? CompositeTextureBuilder.padCanvas(image, pad, pad) : image;
        if (canvas != image) {
            image.close();
        }

        ResourceLocation maskLocation = null;
        if (!key.gunOutlines().isEmpty()) {
            NativeImage maskImage = CompositeTextureBuilder.buildOutlineMask(canvas, key.gunOutlines());
            maskLocation = ResourceLocation.fromNamespaceAndPath(
                    ModularShoot.MODID, "dynamic/gun_mask_" + nextId++);
            DynamicTexture maskTexture = new DynamicTexture(maskImage);
            Minecraft.getInstance().getTextureManager().register(maskLocation, maskTexture);
            CompositeTextureBuilder.applyGunOutlines(canvas, key.gunOutlines());
        }

        // DynamicTexture(NativeImage) prepares and uploads the GL texture,
        // deferring to the render thread when necessary.
        DynamicTexture texture = new DynamicTexture(canvas);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        // Complete item geometry: front/back faces (content rect plus the
        // padded outline ring) followed by the silhouette edge strips. All
        // quads use content-space model coordinates and canvas-space UVs —
        // with an unpadded canvas (pad == 0) both coincide.
        List<SideQuad> quads = new ArrayList<>(18 + 16);
        quads.addAll(SideQuadBuilder.buildMainFaces(width, height, pad, canvas.getWidth(), canvas.getHeight()));
        quads.addAll(SideQuadBuilder.build(canvas, pad, pad, width, height));
        locations.put(key, new TextureHandle(location, maskLocation, width, height, quads));
        return locations.get(key);
    }

    /**
     * Releases every GPU texture owned by a handle (the composite texture and,
     * when present, its outline-mask texture).
     *
     * @param handle the handle whose registered locations are released
     */
    private static void releaseHandle(TextureHandle handle) {
        var textureManager = Minecraft.getInstance().getTextureManager();
        textureManager.release(handle.location());
        if (handle.maskLocation() != null) {
            textureManager.release(handle.maskLocation());
        }
    }

    /**
     * Releases every registered dynamic texture and clears the cache.
     *
     * <p>Called from the item renderers' {@code onResourceManagerReload} so a
     * resource reload (F3+T) re-reads the current PNGs from the resource
     * packs. Old {@code modularshoot:dynamic/...} locations are released and
     * never referenced again (the next miss allocates fresh ids).</p>
     */
    public void clear() {
        if (locations.isEmpty()) {
            return;
        }
        for (TextureHandle handle : locations.values()) {
            releaseHandle(handle);
        }
        locations.clear();
    }

    /**
     * Builds the programmatic placeholder image: a dark-grey 16×16 square
     * with a magenta diagonal cross, making it instantly recognisable as a
     * missing-texture fallback in-game.
     *
     * @return a newly allocated 16×16 placeholder image
     */
    private static NativeImage createPlaceholder() {
        NativeImage image = new NativeImage(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE, false);
        int grey = FastColor.ABGR32.color(255, 64, 64, 64);
        int magenta = FastColor.ABGR32.color(255, 170, 0, 170);
        for (int y = 0; y < PLACEHOLDER_SIZE; y++) {
            for (int x = 0; x < PLACEHOLDER_SIZE; x++) {
                boolean diagonal = x == y || x == PLACEHOLDER_SIZE - 1 - y;
                image.setPixelRGBA(x, y, diagonal ? magenta : grey);
            }
        }
        return image;
    }
}
