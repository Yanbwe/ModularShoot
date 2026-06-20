package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import org.yanbwe.modularshoot.registry.Trait;

/**
 * Parsing and validation utility for the {@code modularshoot:traits}
 * datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around {@link Trait#CODEC}. It
 * provides manual parsing entry points for callers that need to decode a
 * single trait JSON object outside of the vanilla datapack registry pipeline
 * (e.g. for testing, programmatic registration, or pre-validation before
 * insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/traits/<trait_id>.json}. The vanilla
 * datapack registry mechanism resolves the path and feeds the parsed JSON
 * element to {@link Trait#CODEC}; this class exposes the same decode step as
 * a reusable static method (设计文档 §自定义特性数据包JSON格式).</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking internal consistency of a parsed
 * trait) is handled by {@link TraitDatapackLoader}.</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code default_value} — required, boolean default value</li>
 *   <li>{@code description} — optional, defaults to {@code ""}</li>
 *   <li>{@code name} — optional display name</li>
 *   <li>{@code color} — optional hex colour code</li>
 *   <li>{@code brief} — optional one-line short description</li>
 *   <li>{@code force_show} — optional tooltip flag, defaults to {@code false}</li>
 *   <li>{@code priority} — optional display priority, defaults to {@code 0}</li>
 * </ul>
 *
 * @see Trait#CODEC
 * @see TraitDatapackLoader
 */
public final class TraitJsonCodec {
    private TraitJsonCodec() {
    }

    /**
     * Parses a single JSON element into a {@link Trait}.
     *
     * <p>Delegates to {@link Trait#CODEC} bound to {@link JsonOps#INSTANCE}.
     * The {@code default_value} field is declared {@code fieldOf} (not
     * {@code optionalFieldOf}) in the codec, so a missing
     * {@code default_value} key causes the parse to fail with an error
     * message — satisfying the "default_value required" constraint at the
     * codec layer.</p>
     *
     * @param json the JSON element representing one trait entry
     * @return the decoded, immutable {@link Trait}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. missing {@code default_value}, wrong types)
     */
    public static Trait parse(JsonElement json) {
        return parseSafe(json).getOrThrow(TraitJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the {@link Trait}, or
     * an error carrying a human-readable message.</p>
     *
     * @param json the JSON element representing one trait entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link Trait} or fails with a parse error message
     */
    public static DataResult<Trait> parseSafe(JsonElement json) {
        return Trait.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates that the required and non-nullable fields of a {@link Trait}
     * are present and well-formed.
     *
     * <p>The codec already enforces {@code default_value} as a required
     * field and supplies defaults for {@code description}, {@code force_show}
     * and {@code priority}, so a value parsed through
     * {@link #parse(JsonElement)} can never violate these. This method
     * provides an explicit, defensive check for {@link Trait} instances
     * constructed programmatically (e.g. via the record constructor or
     * {@link Trait#of}) where the codec's guarantees do not apply.</p>
     *
     * <p>Checks performed:</p>
     * <ul>
     *   <li>{@code description} is non-null (codec default is {@code ""})</li>
     *   <li>{@code name}, {@code color}, {@code brief} Optionals are
     *       non-null and do not wrap null values</li>
     * </ul>
     *
     * @param trait the trait entry to check
     * @return the same {@code trait} instance, for chaining
     * @throws IllegalStateException if any non-nullable field is null
     */
    public static Trait validateRequiredFields(Trait trait) {
        if (trait.description() == null) {
            throw new IllegalStateException(
                    "Trait.description must not be null — required field.");
        }
        validateOptionalField("name", trait.name());
        validateOptionalField("color", trait.color());
        validateOptionalField("brief", trait.brief());
        return trait;
    }

    /**
     * Parses and validates a single JSON element in one step.
     *
     * <p>Equivalent to {@code validateRequiredFields(parse(json))}.
     * Convenience for callers that want both the codec-level
     * required-field check and the explicit non-null guarantee in a single
     * call.</p>
     *
     * @param json the JSON element representing one trait entry
     * @return the decoded and validated {@link Trait}
     * @throws IllegalStateException if parsing fails or a required field is
     *         null
     */
    public static Trait parseAndValidate(JsonElement json) {
        return validateRequiredFields(parse(json));
    }

    /**
     * Checks that an Optional field is non-null and does not wrap a null
     * value.
     *
     * @param fieldName the field name, used in the error message
     * @param value     the Optional to check
     * @throws IllegalStateException if {@code value} is null or wraps null
     */
    private static void validateOptionalField(String fieldName, Optional<String> value) {
        if (value == null) {
            throw new IllegalStateException(
                    "Trait." + fieldName + " must not be null — required field.");
        }
        if (value.isPresent() && value.get() == null) {
            throw new IllegalStateException(
                    "Trait." + fieldName + " must not wrap a null value.");
        }
    }

    /**
     * Builds a parse exception from a DataResult error message.
     *
     * @param message the error message produced by the codec
     * @return an {@link IllegalStateException} wrapping the message
     */
    private static RuntimeException parseException(String message) {
        return new IllegalStateException("Failed to parse trait JSON: " + message);
    }
}
