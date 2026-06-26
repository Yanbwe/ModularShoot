package org.yanbwe.modularshoot.degradation;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.state.StateRegistry;
import org.yanbwe.modularshoot.state.StateValueType;
import org.yanbwe.modularshoot.state.StateWarnLogger;

/**
 * Centralised graceful-degradation utilities for the case where a state
 * definition is missing from the {@code modularshoot:states} registry
 * (设计文档 §状态 ID 失效降级).
 *
 * <p>When a datapack removes or renames a state definition, any values
 * previously written for that state id remain physically in storage (gun
 * NBT, player attachment, bullet snapshot) but the framework can no longer
 * serialise them as typed values. This class provides the three core
 * degradation primitives mandated by the design doc:</p>
 *
 * <ul>
 *   <li><b>Existence check</b> — {@link #isStateDefinitionMissing} reports
 *       whether a state id is absent from the runtime registry.</li>
 *   <li><b>Safe read</b> — {@link #getSafeStateValue} returns the stored
 *       value when the definition exists, or the type's zero value plus a
 *       rate-limited {@code WARN} (via {@link StateWarnLogger}) when it is
 *       missing.</li>
 *   <li><b>Bulk filter</b> — {@link #filterRegisteredStates} retains only
 *       the state ids that still have a registered definition, for callers
 *       that hold a set of stored ids (e.g. tooltip, sync packets).</li>
 * </ul>
 *
 * <p><strong>Relationship to {@code GunState} / {@code PlayerState}</strong>
 * — those typed view classes already perform the same existence check and
 * WARN emission inside their {@code validateState} helpers. This class
 * extracts the logic into reusable pure functions so that non-view callers
 * (tooltip, snapshot serialisers, debug commands) can apply the same
 * degradation policy without duplicating it.</p>
 *
 * <p>All methods are static; the class is not instantiable. No method
 * throws — degradation always falls back silently to a zero value.</p>
 *
 * @see StateRegistry#isRegistered
 * @see StateWarnLogger#warnUnregistered
 */
public final class StateDegradationHandler {
    private StateDegradationHandler() {
    }

    /**
     * Checks whether a state definition is missing from the
     * {@code modularshoot:states} registry.
     *
     * <p>Returns {@code true} when the registry is absent (e.g. on the main
     * menu) or the id is not registered. This is the primary predicate used
     * by all other degradation paths.</p>
     *
     * @param stateId        the state definition id to test
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return {@code true} if the definition is missing; {@code false} if it
     *         is registered
     */
    public static boolean isStateDefinitionMissing(ResourceLocation stateId, RegistryAccess registryAccess) {
        return !StateRegistry.isRegistered(registryAccess, stateId);
    }

    /**
     * Resolves a state value with a safe fallback to the type's zero value
     * when the definition is missing.
     *
     * <p>When the definition exists the {@code storedValue} is returned
     * as-is (or the zero value when {@code storedValue} is {@code null},
     * which is the valid UUID zero value). When the definition is missing a
     * single rate-limited {@code WARN} is emitted via
     * {@link StateWarnLogger#warnUnregistered} and the type's zero value is
     * returned. This method never throws.</p>
     *
     * @param stateId        the state id to resolve
     * @param expectedType   the value type the caller expects; its
     *                       {@link StateValueType#zeroValue()} is used as
     *                       the fallback
     * @param registryAccess the runtime registry view
     * @param storedValue    the raw value read from storage, or {@code null}
     *                       when nothing is stored yet
     * @return the safe value — either {@code storedValue} when the
     *         definition exists, or the zero value when it is missing
     */
    public static Object getSafeStateValue(
            ResourceLocation stateId,
            StateValueType expectedType,
            RegistryAccess registryAccess,
            @Nullable Object storedValue) {
        if (isStateDefinitionMissing(stateId, registryAccess)) {
            StateWarnLogger.warnUnregistered(stateId);
            return expectedType.zeroValue();
        }
        return storedValue != null ? storedValue : expectedType.zeroValue();
    }

    /**
     * Filters a set of state ids, retaining only those that still have a
     * registered definition.
     *
     * <p>Used by callers that hold a collection of stored state ids (e.g.
     * the set of keys present in a gun's NBT) and need to skip ids whose
     * definitions have been removed by a datapack change. No {@code WARN}
     * is emitted here — this is a silent filter intended for bulk
     * operations where per-id warnings would be noisy.</p>
     *
     * @param stateIds       the candidate state ids
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set containing only the registered ids; empty
     *         when the input is empty or no id is registered
     */
    public static Set<ResourceLocation> filterRegisteredStates(
            Set<ResourceLocation> stateIds,
            RegistryAccess registryAccess) {
        return stateIds.stream()
                .filter(id -> !isStateDefinitionMissing(id, registryAccess))
                .collect(Collectors.toUnmodifiableSet());
    }
}
