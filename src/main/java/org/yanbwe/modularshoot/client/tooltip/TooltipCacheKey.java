package org.yanbwe.modularshoot.client.tooltip;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Immutable cache key for a tooltip build result.
 *
 * <p>The key captures every input that can change the rendered tooltip so a
 * cached {@code List<Component>} is only reused when the output would be
 * identical:
 * <ul>
 *   <li><b>ItemStack identity</b> — {@link ItemStack#hashItemAndComponents}
 *       hashes the item and its full component map (including the per-stack
 *       {@code gun_instance_uuid}), so a stack with different components or a
 *       different instance gets its own key.</li>
 *   <li><b>Registry version</b> — derived from the identity of the framework's
 *       datapack {@link Registry} instances. A {@code /reload} swaps these
 *       instances, so the version changes and prior entries are never reused
 *       (key-based invalidation, safe across the render thread / main-thread
 *       reload split).</li>
 *   <li><b>Modifier keys</b> — Ctrl/Alt/Shift state, which expands different
 *       tooltip sections.</li>
 *   <li><b>Data version</b> — a caller-supplied counter for mutable per-gun
 *       data: the {@code GunData.modifierVersion} and/or
 *       {@link org.yanbwe.modularshoot.client.ClientGunDataStore} version/state
 *       so that tooltips re-render when the gun's authoritative data changes
 *       even though the rest of the key is unchanged.</li>
 * </ul>
 * </p>
 *
 * <p><b>Remaining per-frame cost (审查 Low):</b> {@link #of} still calls
 * {@link ItemStack#hashItemAndComponents} on <em>every</em> key construction,
 * which recursively hashes the item plus its full component map (including the
 * per-stack {@code GunData} state tag) each frame the mouse hovers the item.
 * This is the one remaining O(state) step per frame. A separate incremental
 * identity version for the stack-side {@code GunData} (tracking the component
 * reference like {@link TooltipVersion} does for {@code PLAYER_STATE}) was
 * considered but deliberately <em>not</em> introduced: it would require keying
 * the stack <em>without</em> its gun state and adding a second source of truth
 * that could disagree with the component hash on staleness. Given the cache is
 * bounded and correct-by-construction, the per-frame recursive hash is accepted
 * and documented rather than traded for a subtle cache-correctness risk.</p>
 *
 * <p><b>Collision tolerance (审查 Low):</b> the key folds its inputs into a
 * handful of {@code int}s (stack identity, registry version, data version), so
 * two genuinely different input sets can in principle collide to the same key.
 * This is an accepted, <em>lossy</em> short-term cache: the per-frame O(1)
 * win outweighs the astronomically unlikely event of a collision, and a
 * collision would at worst return a briefly-stale tooltip that corrects on the
 * next LRU eviction / clear — it can never return a wrong-but-persistent
 * result. Callers must not rely on the key being injective across all inputs.</p>
 * </p>
 *
 * @param stackIdentity   the item + components hash of the tooltip'd stack
 * @param registryVersion the version derived from the framework registry
 *                        instances (changes on datapack reload)
 * @param ctrl            whether Ctrl is held
 * @param alt             whether Alt is held
 * @param shift           whether Shift is held
 * @param dataVersion     the caller-supplied version folding every mutable and
 *                        viewer-dependent input (mutable gun/store data, the
 *                        holding/main-hand booleans, and the viewing player's
 *                        identity/state); see {@link TooltipVersion}
 */
public record TooltipCacheKey(
        int stackIdentity,
        int registryVersion,
        boolean ctrl,
        boolean alt,
        boolean shift,
        int dataVersion
) {
    /**
     * Builds a cache key for the given inputs.
     *
     * <p>{@code dataVersion} is supplied by the caller because different tooltip
     * segments derive it from different sources ({@code GunData.modifierVersion}
     * vs {@code ClientGunDataStore} version + state hash). In this codebase the
     * builders pass {@link TooltipVersion#mutableDataVersion(ItemStack, Player)},
     * which additionally folds the viewing-context booleans (is-main-hand,
     * is-holding-gun) and the viewing player's identity/state into the version.</p>
     *
     * @param stack        the tooltip'd item stack
     * @param access       the runtime registry view
     * @param ctrl         whether Ctrl is held
     * @param alt          whether Alt is held
     * @param shift        whether Shift is held
     * @param dataVersion  the mutable/viewer-dependent data version (see
     *                     {@link TooltipVersion#mutableDataVersion})
     * @return an immutable cache key
     */
    public static TooltipCacheKey of(
            ItemStack stack,
            RegistryAccess access,
            boolean ctrl,
            boolean alt,
            boolean shift,
            int dataVersion) {
        return new TooltipCacheKey(
                ItemStack.hashItemAndComponents(stack),
                registryVersion(access),
                ctrl,
                alt,
                shift,
                dataVersion);
    }

    /**
     * Computes a stable-enough version for the framework's datapack registries.
     *
     * <p>Reload swaps the {@link Registry} instances, so
     * {@link System#identityHashCode} differs between reloads by construction.
     * Two equal-content registries are keyed separately, which is exactly the
     * behaviour we need: a reload must invalidate cached tooltips. Only the
     * framework registries consulted by tooltips are folded in, so the version
     * is cheap to compute per frame.</p>
     *
     * @param access the runtime registry view
     * @return an int version that changes when any framework registry is swapped
     */
    public static int registryVersion(RegistryAccess access) {
        int version = 1;
        version = fold(version, access, ModularShootRegistries.PLUGINS_KEY);
        version = fold(version, access, ModularShootRegistries.PLUGIN_TYPES_KEY);
        version = fold(version, access, ModularShootRegistries.GUNS_KEY);
        version = fold(version, access, ModularShootRegistries.STATES_KEY);
        version = fold(version, access, ModularShootRegistries.ATTRIBUTE_META_KEY);
        version = fold(version, access, ModularShootRegistries.TRAITS_KEY);
        return version;
    }

    /**
     * Folds the identity of a single framework registry into the version.
     *
     * @param <T>     the registry value type
     * @param version the running version accumulator
     * @param access  the runtime registry view
     * @param key     the registry key
     * @return an updated version that differs when the registry instance differs
     */
    private static <T> int fold(
            int version, RegistryAccess access, ResourceKey<Registry<T>> key) {
        Registry<T> registry = access.registry(key).orElse(null);
        int identity = registry == null ? 0 : System.identityHashCode(registry);
        return version * 31 + identity;
    }
}
