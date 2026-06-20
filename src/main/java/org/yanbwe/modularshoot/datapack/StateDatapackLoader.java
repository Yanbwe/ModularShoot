package org.yanbwe.modularshoot.datapack;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateValueCodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-load validation utility for the {@code modularshoot:states}
 * datapack registry.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link StateDefinition} entries, this class performs a secondary
 * validation pass: it checks that each entry's {@code domain} and
 * {@code value_type} are non-null (required fields) and that
 * {@code default_value}'s runtime type matches the declared
 * {@code value_type} (设计文档 §状态数据包 JSON).</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When a state fails a validation check, the entry is <strong>still
 * considered registered</strong> — it is not removed from the
 * {@code states} registry. Instead, the validation returns a
 * {@link StateValidation} carrying an error marker. The actual degradation
 * handling (e.g. skipping the state at runtime, falling back to a safe
 * default) is deferred to the state read/write layer. This class only
 * <em>marks</em> the problem (设计文档 §definition-loss degradation).</p>
 *
 * <p>Unlike {@link AttributeMetaDatapackLoader}, states do not bind to an
 * external vanilla registry, so there is no cross-registry resolution to
 * perform. The validation here is purely internal to the state
 * definition.</p>
 *
 * @see StateDefinition
 * @see StateJsonCodec
 * @see StateValueCodecs#isTypeMatch
 */
public final class StateDatapackLoader {
    /** Dedicated subsystem logger for state validation. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/State");

    private StateDatapackLoader() {
    }

    /**
     * Validates a single {@link StateDefinition} entry for required-field
     * completeness and default_value type consistency.
     *
     * <p>This is a post-load validation: it assumes the
     * {@link StateDefinition} has already been parsed and registered. The
     * entry is never rejected — when a check fails, a
     * {@link StateValidation} with an error marker is returned so the
     * caller can decide on degradation.</p>
     *
     * <p>Checks performed (all produce errors, not warnings):</p>
     * <ul>
     *   <li>{@code domain} is non-null (required field)</li>
     *   <li>{@code value_type} is non-null (required field)</li>
     *   <li>{@code default_value}'s runtime type matches
     *       {@code value_type}</li>
     * </ul>
     *
     * @param stateId    the state id (the registry key path from
     *                   {@code states/<state_id>.json}); used in error
     *                   messages for traceability
     * @param definition the parsed state entry to validate
     * @return a {@link StateValidation} that is either {@code ok} (all
     *         checks passed) or {@code invalid} (one or more checks failed
     *         with an error message)
     */
    public static StateValidation validateState(
            ResourceLocation stateId, StateDefinition definition) {
        final Optional<String> domainError = checkDomain(stateId, definition);
        final Optional<String> valueTypeError = checkValueType(stateId, definition);
        final Optional<String> defaultValueError = checkDefaultValueType(stateId, definition);
        final Optional<String> error = combineErrors(
                domainError, valueTypeError, defaultValueError);
        if (error.isEmpty()) {
            return StateValidation.ok(definition);
        }
        LOGGER.warn(error.get());
        return StateValidation.invalid(definition, error.get());
    }

    /**
     * Validates every state in the {@code modularshoot:states} registry.
     *
     * <p>Each entry is validated independently; one bad entry does not
     * abort the batch. The returned list contains one
     * {@link StateValidation} per registered state, in registry order.</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return a list of validation results, one per registered state; empty
     *         when the registry is absent (e.g. on the main menu)
     */
    public static List<StateValidation> validateAllStates(RegistryAccess registryAccess) {
        final Optional<Registry<StateDefinition>> registry =
                registryAccess.registry(ModularShootRegistries.STATES_KEY);
        if (registry.isEmpty()) {
            return List.of();
        }
        return registry.get().holders()
                .map(holder -> validateState(holder.key().location(), holder.value()))
                .toList();
    }

    // ──────────────── Private check helpers ────────────────

    /**
     * Checks that the {@code domain} field is non-null.
     *
     * @param stateId    the state id for the error message
     * @param definition the definition to check
     * @return an error message if {@code domain} is null, empty otherwise
     */
    private static Optional<String> checkDomain(
            ResourceLocation stateId, StateDefinition definition) {
        if (definition.domain() == null) {
            return Optional.of(buildError(stateId, "domain",
                    "must not be null — required field"));
        }
        return Optional.empty();
    }

    /**
     * Checks that the {@code value_type} field is non-null.
     *
     * @param stateId    the state id for the error message
     * @param definition the definition to check
     * @return an error message if {@code value_type} is null, empty
     *         otherwise
     */
    private static Optional<String> checkValueType(
            ResourceLocation stateId, StateDefinition definition) {
        if (definition.valueType() == null) {
            return Optional.of(buildError(stateId, "value_type",
                    "must not be null — required field"));
        }
        return Optional.empty();
    }

    /**
     * Checks that {@code default_value}'s runtime type matches the declared
     * {@code value_type}.
     *
     * <p>Skipped when {@code value_type} is null — that error is already
     * reported by {@link #checkValueType}. Delegates to
     * {@link StateValueCodecs#isTypeMatch} which handles all seven value
     * types including the {@code null} zero value for
     * {@link org.yanbwe.modularshoot.state.StateValueType#UUID}.</p>
     *
     * @param stateId    the state id for the error message
     * @param definition the definition to check
     * @return an error message if the default_value type does not match,
     *         empty otherwise
     */
    private static Optional<String> checkDefaultValueType(
            ResourceLocation stateId, StateDefinition definition) {
        if (definition.valueType() == null) {
            return Optional.empty();
        }
        if (!StateValueCodecs.isTypeMatch(definition.valueType(), definition.defaultValue())) {
            return Optional.of(buildError(stateId, "default_value",
                    "type does not match value_type '"
                            + definition.valueType().getSerializedName() + "'"));
        }
        return Optional.empty();
    }

    /**
     * Combines multiple optional error messages into a single message.
     *
     * @param errors the optional error messages to combine
     * @return the combined error, or empty if all inputs are empty
     */
    @SafeVarargs
    private static Optional<String> combineErrors(Optional<String>... errors) {
        final StringBuilder builder = new StringBuilder();
        for (Optional<String> error : errors) {
            error.ifPresent(e -> builder.append(e).append(" "));
        }
        final String combined = builder.toString().trim();
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    /**
     * Builds a single-field error message.
     *
     * @param stateId   the state id
     * @param fieldName the field that failed validation
     * @param detail    the human-readable detail
     * @return the error text
     */
    private static String buildError(
            ResourceLocation stateId, String fieldName, String detail) {
        return "State '" + stateId + "' field '" + fieldName + "': " + detail
                + "; entry registered with error (degradation deferred).";
    }

    /**
     * Result of a post-load validation for one {@link StateDefinition}.
     *
     * <p>Always carries the original {@code definition} so the caller can
     * proceed with registration regardless of the validation status. The
     * {@code valid} flag and optional {@code error} tell the caller whether
     * degradation handling should kick in.</p>
     *
     * @param definition the validated state entry (never {@code null})
     * @param valid      {@code true} if all validation checks passed
     * @param error      the error message when {@code valid} is
     *                   {@code false}; empty when the state is valid
     */
    public record StateValidation(
            StateDefinition definition,
            boolean valid,
            Optional<String> error) {

        /**
         * Factory for a successful validation: all checks passed.
         *
         * @param definition the validated state entry
         * @return a {@link StateValidation} with {@code valid = true} and
         *         no error
         */
        public static StateValidation ok(StateDefinition definition) {
            return new StateValidation(definition, true, Optional.empty());
        }

        /**
         * Factory for a failed validation: one or more checks failed.
         *
         * @param definition the validated state entry (still registered)
         * @param error      the human-readable error message
         * @return a {@link StateValidation} with {@code valid = false} and
         *         the given error
         */
        public static StateValidation invalid(StateDefinition definition, String error) {
            return new StateValidation(definition, false, Optional.of(error));
        }
    }
}
