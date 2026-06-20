package org.yanbwe.modularshoot.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared rate-limited {@code WARN} logger for state access errors.
 *
 * <p>State accessors ({@link GunState} and the future {@code PlayerState})
 * degrade gracefully on misuse: unregistered ids, wrong domain, and type
 * mismatches all yield a zero value (or a skipped write) instead of an
 * exception. To avoid log spam when a buggy caller repeats the same bad
 * access every tick, each distinct state id is allowed at most one
 * {@code WARN} line per minute (设计文档 §错误处理).</p>
 *
 * <p>The rate limiter is a {@link ConcurrentHashMap} keyed by the state id's
 * string form, storing the minute bucket of the last emitted warning. The
 * class is thread-safe and not instantiable.</p>
 *
 * @see GunState
 */
public final class StateWarnLogger {
    /** Dedicated subsystem logger; named so operators can filter state warnings. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/State");

    /** Per-state-id minute bucket of the last emitted warning. */
    private static final Map<String, Long> LAST_WARN_BUCKETS = new ConcurrentHashMap<>();

    /** One minute in milliseconds; used to bucket warnings so each state warns at most once per minute. */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private StateWarnLogger() {
    }

    /**
     * Emits a rate-limited {@code WARN} for an access to an unregistered
     * state id.
     *
     * @param stateId the state id that was not found in the
     *                {@code modularshoot:states} registry
     */
    public static void warnUnregistered(ResourceLocation stateId) {
        warn(stateId, "State '" + stateId + "' is not registered in the modularshoot:states registry; "
                + "returning zero value / skipping write.");
    }

    /**
     * Emits a rate-limited {@code WARN} for a state access whose requested
     * Java type does not match the registered {@link StateValueType}.
     *
     * @param stateId  the state id that was accessed
     * @param expected the declared {@link StateValueType} from the registry
     * @param actual   the Java class the caller used to access the value;
     *                 {@code null} when the caller supplied a {@code null} value
     */
    public static void warnTypeMismatch(ResourceLocation stateId, StateValueType expected, @Nullable Class<?> actual) {
        warn(stateId, "State '" + stateId + "' is declared as '" + expected.getSerializedName()
                + "' but was accessed as '" + (actual == null ? "null" : actual.getSimpleName())
                + "'; returning zero value / skipping write.");
    }

    /**
     * Emits a rate-limited {@code WARN} for a state access whose ownership
     * domain does not match the accessor's target domain.
     *
     * @param stateId  the state id that was accessed
     * @param expected the domain the accessor requires (e.g. {@link StateDomain#GUN})
     * @param actual   the domain the state is registered under
     */
    public static void warnDomainMismatch(ResourceLocation stateId, StateDomain expected, StateDomain actual) {
        warn(stateId, "State '" + stateId + "' belongs to domain '" + actual.getSerializedName()
                + "' but was accessed via a '" + expected.getSerializedName() + "' accessor; "
                + "returning zero value / skipping write.");
    }

    /**
     * Emits {@code LOGGER.warn(message)} at most once per state id per
     * minute bucket.
     *
     * <p>Uses {@code System.currentTimeMillis() / WARN_INTERVAL_MS} as the
     * bucket key so that repeated identical misuse within the same minute
     * is suppressed. The map is keyed by the state id's string form.</p>
     *
     * @param stateId the state id used to derive the rate-limit key
     * @param message the warning text
     */
    private static void warn(ResourceLocation stateId, String message) {
        final String key = stateId.toString();
        final long currentBucket = System.currentTimeMillis() / WARN_INTERVAL_MS;
        final Long last = LAST_WARN_BUCKETS.get(key);
        if (last != null && last.longValue() == currentBucket) {
            return;
        }
        LAST_WARN_BUCKETS.put(key, currentBucket);
        LOGGER.warn(message);
    }
}
