package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateValueCodecs;

/**
 * Parsing and validation utility for the {@code modularshoot:states}
 * datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around
 * {@link StateDefinition#CODEC}. It provides manual parsing entry points
 * for callers that need to decode a single state JSON object outside of the
 * vanilla datapack registry pipeline (e.g. for testing, programmatic
 * registration, or pre-validation before insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/states/<state_id>.json}. The vanilla
 * datapack registry mechanism resolves the path and feeds the parsed JSON
 * element to {@link StateDefinition#CODEC}; this class exposes the same
 * decode step as a reusable static method (设计文档 §状态数据包 JSON).</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking domain and value_type validity
 * across all registered entries) is handled by
 * {@link StateDatapackLoader}.</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code domain} — required, ownership domain: {@code gun},
 *       {@code player}, or {@code bullet}</li>
 *   <li>{@code value_type} — required, value type: {@code int},
 *       {@code long}, {@code double}, {@code float}, {@code boolean},
 *       {@code string}, or {@code uuid}</li>
 *   <li>{@code default_value} — optional, initial value; type must match
 *       {@code value_type}</li>
 *   <li>{@code display} — required, display metadata object (name, color,
 *       format, priority, hide_default)</li>
 * </ul>
 *
 * @see StateDefinition#CODEC
 * @see StateDatapackLoader
 * @see StateValueCodecs#isTypeMatch
 */
public final class StateJsonCodec {
    private StateJsonCodec() {
    }

    /**
     * Parses a single JSON element into a {@link StateDefinition}.
     *
     * <p>Delegates to {@link StateDefinition#CODEC} bound to
     * {@link JsonOps#INSTANCE}. The {@code domain} and {@code value_type}
     * fields are declared {@code fieldOf} (not {@code optionalFieldOf}) in
     * the codec, so a missing required key causes the parse to fail with an
     * error message — satisfying the "domain and value_type required"
     * constraint at the codec layer.</p>
     *
     * @param json the JSON element representing one state entry
     * @return the decoded, immutable {@link StateDefinition}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. missing {@code domain} or {@code value_type}, wrong
     *         types, invalid {@code display} object)
     */
    public static StateDefinition parse(JsonElement json) {
        return parseSafe(json).getOrThrow(StateJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the
     * {@link StateDefinition}, or an error carrying a human-readable
     * message.</p>
     *
     * @param json the JSON element representing one state entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link StateDefinition} or fails with a parse error message
     */
    public static DataResult<StateDefinition> parseSafe(JsonElement json) {
        return StateDefinition.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates that the required fields of a {@link StateDefinition} are
     * present and that {@code default_value}'s runtime type matches the
     * declared {@code value_type}.
     *
     * <p>The codec already enforces {@code domain} and {@code value_type}
     * as required fields and supplies a default value when
     * {@code default_value} is omitted, so a value parsed through
     * {@link #parse(JsonElement)} can never violate these. This method
     * provides an explicit, defensive check for {@link StateDefinition}
     * instances constructed programmatically (e.g. via the record
     * constructor) where the codec's guarantees do not apply.</p>
     *
     * <p>Checks performed:</p>
     * <ul>
     *   <li>{@code domain} is non-null (required field)</li>
     *   <li>{@code value_type} is non-null (required field)</li>
     *   <li>{@code default_value}'s runtime type matches
     *       {@code value_type} (设计文档 §状态数据包 JSON —
     *       "default_value 类型须与 value_type 匹配")</li>
     * </ul>
     *
     * @param definition the state definition to check
     * @return the same {@code definition} instance, for chaining
     * @throws IllegalStateException if {@code domain} or {@code value_type}
     *         is null, or if {@code default_value}'s type does not match
     *         {@code value_type}
     */
    public static StateDefinition validate(StateDefinition definition) {
        checkDomain(definition);
        checkValueType(definition);
        checkDefaultValueType(definition);
        return definition;
    }

    /**
     * Parses and validates a single JSON element in one step.
     *
     * <p>Equivalent to {@code validate(parse(json))}. Convenience for
     * callers that want both the codec-level required-field check and the
     * explicit default_value type-match guarantee in a single call.</p>
     *
     * @param json the JSON element representing one state entry
     * @return the decoded and validated {@link StateDefinition}
     * @throws IllegalStateException if parsing fails or a validation check
     *         fails
     */
    public static StateDefinition parseAndValidate(JsonElement json) {
        return validate(parse(json));
    }

    // ──────────────── Private check helpers ────────────────

    /**
     * Checks that the {@code domain} field is non-null.
     *
     * @param definition the definition to check
     * @throws IllegalStateException if {@code domain} is null
     */
    private static void checkDomain(StateDefinition definition) {
        if (definition.domain() == null) {
            throw new IllegalStateException(
                    "StateDefinition.domain must not be null — required field.");
        }
    }

    /**
     * Checks that the {@code value_type} field is non-null.
     *
     * @param definition the definition to check
     * @throws IllegalStateException if {@code value_type} is null
     */
    private static void checkValueType(StateDefinition definition) {
        if (definition.valueType() == null) {
            throw new IllegalStateException(
                    "StateDefinition.valueType must not be null — required field.");
        }
    }

    /**
     * Checks that {@code default_value}'s runtime type matches the declared
     * {@code value_type}.
     *
     * <p>Delegates to {@link StateValueCodecs#isTypeMatch} which handles
     * all seven value types including the {@code null} zero value for
     * {@link org.yanbwe.modularshoot.state.StateValueType#UUID}. This
     * method assumes {@code value_type} is non-null (guaranteed by
     * {@link #checkValueType} which is called first in
     * {@link #validate}).</p>
     *
     * @param definition the definition to check
     * @throws IllegalStateException if the default_value type does not match
     *         the declared value_type
     */
    private static void checkDefaultValueType(StateDefinition definition) {
        if (!StateValueCodecs.isTypeMatch(definition.valueType(), definition.defaultValue())) {
            throw new IllegalStateException(
                    "StateDefinition.default_value type does not match value_type '"
                            + definition.valueType().getSerializedName() + "'.");
        }
    }

    /**
     * Builds a parse exception from a DataResult error message.
     *
     * @param message the error message produced by the codec
     * @return an {@link IllegalStateException} wrapping the message
     */
    private static RuntimeException parseException(String message) {
        return new IllegalStateException("Failed to parse state JSON: " + message);
    }
}
