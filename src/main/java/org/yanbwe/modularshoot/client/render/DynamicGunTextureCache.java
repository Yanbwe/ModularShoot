package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;
import org.yanbwe.modularshoot.ModularShoot;

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
 * <p><b>Placeholder fallback:</b> when the base texture cannot be loaded
 * (missing PNG), a programmatically generated 16×16 placeholder image is
 * uploaded instead so the rendering pipeline stays visible and diagnosable —
 * a dark-grey block with a magenta diagonal instead of the vanilla
 * purple-black missing texture. The failure is logged once per distinct key.</p>
 *
 * <p><b>Key invalidation:</b> the cache key is
 * {@code (texture path, overlay list, modifierVersion)}. Overlay changes are
 * reflected by the key, and the caller feeds the server-pushed modifier
 * version ({@link org.yanbwe.modularshoot.client.ClientGunDataStore#getModifierVersion})
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
     * produce new cache keys (modifier version), so the cache is bounded by
     * evicting everything once it grows past this size — releasing the GPU
     * textures of long-forgotten gun/plugin configurations.
     */
    private static final int MAX_ENTRIES = 64;

    private static DynamicGunTextureCache instance;

    /**
     * Cache key → registered texture location. A {@link LinkedHashMap}
     * preserves insertion order so the oldest entries can be evicted first
     * when the cache exceeds {@link #MAX_ENTRIES}.
     */
    private final Map<Key, ResourceLocation> locations = new LinkedHashMap<>();
    private int nextId;

    private DynamicGunTextureCache() {
    }

    /**
     * Cache key identifying a unique composited texture.
     *
     * @param texturePath     the resolved base (or shoot) texture path
     * @param overlays        the sorted overlay texture paths, bottom-to-top;
     *                        empty when the item has no overlay layers
     * @param modifierVersion the gun's anti-cheat modifier version, used to
     *                        force a re-composite when plugins change even if
     *                        the overlay list is unchanged
     */
    public record Key(ResourceLocation texturePath, List<ResourceLocation> overlays, int modifierVersion) {
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
     * Returns the registered texture location for the given key, compositing
     * and uploading a new dynamic texture on a cache miss.
     *
     * <p>Never returns {@code null}: a failed base-texture load falls back to
     * the programmatic placeholder so the renderer always has a texture to
     * draw. On miss the loaded PNGs are composited via
     * {@link CompositeTextureBuilder#composite} and the result is uploaded
     * through a {@link DynamicTexture} registered at a fresh
     * {@code modularshoot:dynamic/gun_<n>} location.</p>
     *
     * @param key the cache key; must not be {@code null}
     * @return the texture location for the composited image (never
     *         {@code null})
     */
    public ResourceLocation getOrCreate(Key key) {
        ResourceLocation existing = locations.get(key);
        if (existing != null) {
            return existing;
        }

        // Bound the cache: evict the oldest entries (insertion order) until
        // under the limit, releasing the GPU textures of stale configurations.
        while (locations.size() >= MAX_ENTRIES) {
            var first = locations.entrySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(first.getValue());
            locations.remove(first.getKey());
        }

        ResourceLocation location =
                ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "dynamic/gun_" + nextId++);

        NativeImage image = CompositeTextureBuilder.composite(key.texturePath(), key.overlays());
        if (image == null) {
            LOGGER.warn(
                    "Gun texture {} could not be loaded; using programmatic placeholder",
                    key.texturePath());
            image = createPlaceholder();
        }

        // DynamicTexture(NativeImage) prepares and uploads the GL texture,
        // deferring to the render thread when necessary.
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        locations.put(key, location);
        return location;
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
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : locations.values()) {
            textureManager.release(location);
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
