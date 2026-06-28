package org.yanbwe.modularshoot.trait;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Central registry for trait runtime hook callbacks.
 *
 * <p>Stores callbacks keyed by trait id ({@link ResourceLocation}) and hook
 * type ({@link TraitHookType}). A single trait id may register multiple
 * callbacks for the same hook type; they are stored in registration order
 * and invoked in that order by the bullet engine. Later callbacks observe
 * modifications made by earlier callbacks to the shared
 * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot} (设计文档
 * §特性钩子注册 API).</p>
 *
 * <h2>Registration</h2>
 * <p>Call {@link #register} during mod initialisation (e.g.
 * {@code FMLCommonSetupEvent}) so that all hooks are in place before any
 * bullet is fired. The method is generic: the callback must implement the
 * interface that corresponds to the given {@link TraitHookType}. This is
 * validated at runtime via an {@code instanceof} check against the expected
 * callback class.</p>
 *
 * <pre>{@code
 * TraitHookRegistry.register(
 *     ResourceLocation.parse("examplemod:ramping_damage"),
 *     TraitHookType.ON_TICK,
 *     (TraitTickCallback) (bullet, snapshot) -> { ... }
 * );
 * }</pre>
 *
 * <p>Because {@link TraitCallbacks.TraitTickCallback} and
 * {@link TraitCallbacks.TraitExpireCallback} share the same parameter list,
 * an inline lambda must be cast to the specific interface so the compiler
 * can infer the target type. Assigning the lambda to a typed variable first
 * avoids the cast (see {@link TraitCallbacks} for examples).</p>
 *
 * <h2>Querying</h2>
 * <p>Use {@link #getHooks(ResourceLocation, TraitHookType)} for generic
 * iteration over callbacks as {@link TraitCallbacks.TraitCallback}, or
 * {@link #getHooks(ResourceLocation, TraitHookType, Class)} for a
 * type-safe list of a specific callback interface. Both return an
 * unmodifiable view; the returned list is empty when no callbacks are
 * registered for the given trait id and hook type.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The outer map is a {@link ConcurrentHashMap} so that callback lists
 * registered during init are safely published to the server tick threads
 * that read them at runtime. Registration itself is expected to happen
 * single-threaded during mod init; concurrent registration for the same
 * trait id is not supported.</p>
 *
 * <p>This class is not instantiable. It is intended as a static accessor
 * that {@code ModularShootAPI} will delegate to in a later integration
 * step.</p>
 *
 * @see TraitHookType
 * @see TraitCallbacks
 * @see RemoveReason
 */
public final class TraitHookRegistry {

    private static final Map<ResourceLocation, EnumMap<TraitHookType, List<TraitCallbacks.TraitCallback>>> HOOKS =
            new ConcurrentHashMap<>();

    /**
     * Cached unmodifiable snapshot of {@link #HOOKS}'s key set.
     *
     * <p>The bullet engine calls {@link #getRegisteredTraitIds()} once per
     * hook fire per bullet, which at high bullet counts (e.g. 1000 bullets
     * per tick) would otherwise allocate a fresh {@code Set.copyOf} on every
     * call (W10). This cache is refreshed only when a new trait id is
     * registered (during mod init, single-threaded) and read on the server
     * tick threads at runtime.</p>
     *
     * <p>{@code volatile} guarantees the publication of the latest snapshot
     * from the init thread to the tick threads: the write happens-before
     * every subsequent read of the field, and the immutable {@code Set}
     * itself is safely published once its reference is visible.</p>
     */
    private static volatile Set<ResourceLocation> cachedTraitIds = Set.of();

    /**
     * Cached unmodifiable map from hook type to the set of trait ids that
     * have registered at least one callback for that hook type (S4).
     *
     * <p>The bullet engine fires hooks per hook type (e.g. {@code ON_TICK},
     * {@code ON_HIT}). Without this index, every {@code fire*} call in
     * {@link org.yanbwe.modularshoot.bullet.BulletHookInvoker} would iterate
     * over <em>all</em> registered trait ids and query each one for the
     * current hook type — most of those lookups return an empty list when a
     * trait registered only for a different hook type. This index lets the
     * invoker iterate only the trait ids that actually have callbacks for
     * the hook type being fired, eliminating wasted map lookups at high
     * bullet counts.</p>
     *
     * <p>Like {@link #cachedTraitIds}, this cache is rebuilt only during
     * mod init when a new (trait id, hook type) pair is registered
     * (single-threaded) and read on server tick threads at runtime. The
     * {@code volatile} modifier guarantees safe publication of the latest
     * immutable snapshot from the init thread to the tick threads.</p>
     */
    private static volatile Map<TraitHookType, Set<ResourceLocation>> cachedTraitIdsByHookType = Map.of();

    /**
     * Maps each hook type to the callback interface class that callers must
     * pass to {@link #register}. Used for runtime type validation.
     */
    private static final Map<TraitHookType, Class<? extends TraitCallbacks.TraitCallback>> EXPECTED_TYPES;

    static {
        Map<TraitHookType, Class<? extends TraitCallbacks.TraitCallback>> mapping =
                new EnumMap<>(TraitHookType.class);
        mapping.put(TraitHookType.ON_TICK, TraitCallbacks.TraitTickCallback.class);
        mapping.put(TraitHookType.ON_HIT, TraitCallbacks.TraitHitCallback.class);
        mapping.put(TraitHookType.ON_BLOCK_HIT, TraitCallbacks.TraitBlockHitCallback.class);
        mapping.put(TraitHookType.ON_EXPIRE, TraitCallbacks.TraitExpireCallback.class);
        mapping.put(TraitHookType.ON_REMOVE, TraitCallbacks.TraitRemoveCallback.class);
        mapping.put(TraitHookType.ON_VISUAL_TICK, TraitCallbacks.TraitVisualTickCallback.class);
        EXPECTED_TYPES = Map.copyOf(mapping);
    }

    private TraitHookRegistry() {
    }

    /**
     * Registers a runtime hook callback for the given trait id and hook type.
     *
     * <p>The callback must implement the interface that corresponds to
     * {@code type} (e.g. a {@link TraitHookType#ON_TICK} registration must
     * pass a {@link TraitCallbacks.TraitTickCallback}). This is checked at
     * runtime; a mismatch throws {@link IllegalArgumentException}.</p>
     *
     * <p>Multiple callbacks for the same trait id and hook type are stored
     * in registration order. The hook fires them in that order; later
     * callbacks observe earlier modifications to the shared snapshot.</p>
     *
     * @param traitId  the trait definition id; must not be {@code null}
     * @param type     the hook type to attach the callback to; must not be
     *                 {@code null}
     * @param callback the callback to register; must not be {@code null} and
     *                 must implement the interface expected for {@code type}
     * @param <T>      the callback interface type, inferred from
     *                 {@code callback}
     * @throws IllegalArgumentException if {@code callback} does not implement
     *         the interface expected for {@code type}
     */
    public static <T extends TraitCallbacks.TraitCallback> void register(
            ResourceLocation traitId, TraitHookType type, T callback) {
        Objects.requireNonNull(traitId, "traitId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
        validateCallbackType(type, callback);
        addHook(traitId, type, callback);
    }

    /**
     * Returns all callbacks registered for the given trait id and hook type.
     *
     * <p>The returned list is an unmodifiable view of the internal callback
     * list. It is empty when no callbacks are registered for the given
     * combination. Callers that need a specific callback interface should
     * use {@link #getHooks(ResourceLocation, TraitHookType, Class)} instead.</p>
     *
     * @param traitId the trait definition id; must not be {@code null}
     * @param type    the hook type to query; must not be {@code null}
     * @return an unmodifiable list of callbacks; empty when none registered
     */
    public static List<TraitCallbacks.TraitCallback> getHooks(
            ResourceLocation traitId, TraitHookType type) {
        Objects.requireNonNull(traitId, "traitId");
        Objects.requireNonNull(type, "type");
        EnumMap<TraitHookType, List<TraitCallbacks.TraitCallback>> traitHooks = HOOKS.get(traitId);
        if (traitHooks == null) {
            return List.of();
        }
        List<TraitCallbacks.TraitCallback> hooks = traitHooks.get(type);
        return hooks == null ? List.of() : Collections.unmodifiableList(hooks);
    }

    /**
     * Returns all callbacks registered for the given trait id and hook type,
     * typed to the requested callback interface.
     *
     * <p>This is the type-safe variant of
     * {@link #getHooks(ResourceLocation, TraitHookType)}. The
     * {@code callbackType} argument must be the interface that corresponds
     * to {@code type} (or a supertype such as
     * {@link TraitCallbacks.TraitCallback}); otherwise an
     * {@link IllegalArgumentException} is thrown.</p>
     *
     * @param traitId      the trait definition id; must not be {@code null}
     * @param type         the hook type to query; must not be {@code null}
     * @param callbackType the expected callback interface class; must not be
     *                     {@code null} and must be compatible with the
     *                     interface expected for {@code type}
     * @param <T>          the callback interface type
     * @return an unmodifiable list of callbacks typed to {@code T}; empty
     *         when none registered
     * @throws IllegalArgumentException if {@code callbackType} is not
     *         compatible with the interface expected for {@code type}
     */
    @SuppressWarnings("unchecked")
    public static <T extends TraitCallbacks.TraitCallback> List<T> getHooks(
            ResourceLocation traitId, TraitHookType type, Class<T> callbackType) {
        Objects.requireNonNull(traitId, "traitId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callbackType, "callbackType");
        validateCallbackTypeQuery(type, callbackType);
        return (List<T>) getHooks(traitId, type);
    }

    /**
     * Returns the set of trait ids that have registered at least one hook
     * callback.
     *
     * <p>The returned set is an unmodifiable <em>cached</em> snapshot of the
     * registry's trait-id key set, refreshed only when a new trait id is
     * registered via {@link #register}. The same immutable instance is
     * returned on every call until the next registration, so callers may
     * iterate it without allocating (W10). The bullet engine iterates this
     * set to dispatch runtime hooks so that only traits with actual
     * callbacks are queried, avoiding lookups for every registered trait
     * definition (设计文档 §特性运行时钩子).</p>
     *
     * <p>The returned set must not be modified by callers; it is shared
     * across all invocations.</p>
     *
     * @return an unmodifiable, cached set of trait ids with at least one
     *         registered hook; empty when no hooks have been registered
     */
    public static Set<ResourceLocation> getRegisteredTraitIds() {
        return cachedTraitIds;
    }

    /**
     * Returns the set of trait ids that have registered at least one
     * callback for the given hook type (S4).
     *
     * <p>This is a cached, hook-type-specific view of the registry, refreshed
     * only when a new (trait id, hook type) pair is registered via
     * {@link #register}. The bullet engine uses it to iterate only the trait
     * ids that have callbacks for the hook type being fired, avoiding wasted
     * map lookups for traits that registered only for other hook types
     * (设计文档 §特性运行时钩子).</p>
     *
     * <p>The returned set is an unmodifiable cached snapshot; the same
     * immutable instance is returned on every call until the next
     * registration that introduces a new (trait id, hook type) pair. Callers
     * may iterate it without allocating.</p>
     *
     * <p>The returned set must not be modified by callers; it is shared
     * across all invocations.</p>
     *
     * @param type the hook type to query; must not be {@code null}
     * @return an unmodifiable, cached set of trait ids with at least one
     *         registered callback for {@code type}; empty when none
     *         registered
     */
    public static Set<ResourceLocation> getTraitIdsForHookType(TraitHookType type) {
        Objects.requireNonNull(type, "type");
        Set<ResourceLocation> ids = cachedTraitIdsByHookType.get(type);
        return ids == null ? Set.of() : ids;
    }

    /**
     * Verifies that the callback's runtime type matches the interface
     * expected for the given hook type.
     *
     * @param type     the hook type to check against
     * @param callback the callback to validate
     * @throws IllegalArgumentException if the callback does not implement
     *         the expected interface for {@code type}
     */
    private static void validateCallbackType(TraitHookType type, TraitCallbacks.TraitCallback callback) {
        Class<? extends TraitCallbacks.TraitCallback> expected = EXPECTED_TYPES.get(type);
        if (!expected.isInstance(callback)) {
            throw new IllegalArgumentException(
                    "Callback type mismatch for hook " + type + ": expected "
                            + expected.getName() + ", got " + callback.getClass().getName());
        }
    }

    /**
     * Verifies that the requested callback type is compatible with the
     * interface expected for the given hook type.
     *
     * @param type         the hook type to check against
     * @param callbackType the requested callback interface class
     * @throws IllegalArgumentException if {@code callbackType} is not a
     *         supertype of the expected interface for {@code type}
     */
    private static void validateCallbackTypeQuery(TraitHookType type, Class<?> callbackType) {
        Class<? extends TraitCallbacks.TraitCallback> expected = EXPECTED_TYPES.get(type);
        if (!callbackType.isAssignableFrom(expected)) {
            throw new IllegalArgumentException(
                    "Callback type " + callbackType.getName() + " is not compatible with hook "
                            + type + " which expects " + expected.getName());
        }
    }

    /**
     * Appends a callback to the nested map structure, creating intermediate
     * maps and lists as needed.
     *
     * @param traitId  the trait definition id
     * @param type     the hook type
     * @param callback the validated callback to store
     */
    private static void addHook(
            ResourceLocation traitId, TraitHookType type, TraitCallbacks.TraitCallback callback) {
        // Detect whether this registration introduces a new trait id to the
        // key set, and/or a new hook type for an existing trait id.
        // Registration is single-threaded during mod init (per the class
        // thread-safety contract), so the contains/computeIfAbsent pair is
        // free of races. When a new trait id appears, the cached key-set
        // snapshot is rebuilt so getRegisteredTraitIds() returns an
        // up-to-date view without per-call allocation (W10). When a new
        // (trait id, hook type) pair appears, the hook-type index is rebuilt
        // so getTraitIdsForHookType() returns an up-to-date view (S4).
        boolean newTrait = !HOOKS.containsKey(traitId);
        EnumMap<TraitHookType, List<TraitCallbacks.TraitCallback>> traitHooks =
                HOOKS.computeIfAbsent(traitId, k -> new EnumMap<>(TraitHookType.class));
        boolean newHookType = !traitHooks.containsKey(type);
        traitHooks.computeIfAbsent(type, k -> new ArrayList<>())
                .add(callback);
        if (newTrait) {
            cachedTraitIds = Set.copyOf(HOOKS.keySet());
        }
        if (newHookType) {
            rebuildHookTypeIndex();
        }
    }

    /**
     * Rebuilds the cached {@link #cachedTraitIdsByHookType} index from the
     * current state of {@link #HOOKS}.
     *
     * <p>Called only when a new (trait id, hook type) pair is registered
     * (during mod init, single-threaded). Produces an immutable map of
     * immutable sets so that the {@code volatile} field publishes a safely
     * reusable snapshot to the server tick threads (S4).</p>
     */
    private static void rebuildHookTypeIndex() {
        Map<TraitHookType, Set<ResourceLocation>> index = new EnumMap<>(TraitHookType.class);
        HOOKS.forEach((traitId, traitHooks) ->
                traitHooks.keySet().forEach(type ->
                        index.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(traitId)));
        Map<TraitHookType, Set<ResourceLocation>> immutable = new EnumMap<>(TraitHookType.class);
        index.forEach((type, ids) -> immutable.put(type, Set.copyOf(ids)));
        cachedTraitIdsByHookType = Map.copyOf(immutable);
    }
}
