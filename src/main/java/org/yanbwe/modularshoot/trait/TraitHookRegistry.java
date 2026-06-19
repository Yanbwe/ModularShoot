package org.yanbwe.modularshoot.trait;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        HOOKS.computeIfAbsent(traitId, k -> new EnumMap<>(TraitHookType.class))
                .computeIfAbsent(type, k -> new ArrayList<>())
                .add(callback);
    }
}
