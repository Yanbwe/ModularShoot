package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;

/**
 * Parsing and validation utility for the {@code modularshoot:attribute_meta}
 * datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around {@link AttributeMeta#CODEC}.
 * It provides manual parsing entry points for callers that need to decode a
 * single attribute metadata JSON object outside of the vanilla datapack
 * registry pipeline (e.g. for testing, programmatic registration, or
 * pre-validation before insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/attribute_meta/<logical_id>.json}.
 * The vanilla datapack registry mechanism resolves the path and feeds the
 * parsed JSON element to {@link AttributeMeta#CODEC}; this class exposes the
 * same decode step as a reusable static method.</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking that {@code binds} points to a
 * registered vanilla {@code Attribute}) is handled by
 * {@link AttributeMetaDatapackLoader}.</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code binds} — required, the registered vanilla attribute id</li>
 *   <li>{@code default_value} — required, the gun base value</li>
 *   <li>{@code description} — optional, defaults to {@code ""}</li>
 *   <li>{@code color} — optional hex color, defaults to {@code ""}</li>
 *   <li>{@code priority} — optional display priority, defaults to {@code 0}</li>
 *   <li>{@code force_show} — optional tooltip flag, defaults to {@code false}</li>
 * </ul>
 *
 * @see AttributeMeta#CODEC
 * @see AttributeMetaDatapackLoader
 */
public final class AttributeMetaJsonCodec {
    private AttributeMetaJsonCodec() {
    }

    /**
     * Parses a single JSON element into an {@link AttributeMeta}.
     *
     * <p>Delegates to {@link AttributeMeta#CODEC} bound to
     * {@link JsonOps#INSTANCE}. The {@code binds} field is declared
     * {@code fieldOf} (not {@code optionalFieldOf}) in the codec, so a
     * missing {@code binds} key causes the parse to fail with an error
     * message — satisfying the "binds required" constraint at the codec
     * layer.</p>
     *
     * @param json the JSON element representing one attribute metadata entry
     * @return the decoded, immutable {@link AttributeMeta}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. missing {@code binds} or {@code default_value}, wrong
     *         types, invalid {@link net.minecraft.resources.ResourceLocation})
     */
    public static AttributeMeta parse(JsonElement json) {
        return parseSafe(json).getOrThrow(AttributeMetaJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the
     * {@link AttributeMeta}, or an error carrying a human-readable
     * message.</p>
     *
     * @param json the JSON element representing one attribute metadata entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link AttributeMeta} or fails with a parse error message
     */
    public static DataResult<AttributeMeta> parseSafe(JsonElement json) {
        return AttributeMeta.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates that the {@code binds} field of an {@link AttributeMeta} is
     * non-null.
     *
     * <p>The codec already enforces {@code binds} as a required field, so a
     * value parsed through {@link #parse(JsonElement)} can never have a
     * {@code null} binds. This method provides an explicit, defensive check
     * for {@link AttributeMeta} instances constructed programmatically (e.g.
     * via the record constructor or {@link AttributeMeta#of}) where the
     * codec's required-field guarantee does not apply.</p>
     *
     * @param meta the metadata entry to check
     * @return the same {@code meta} instance, for chaining
     * @throws IllegalStateException if {@code meta.binds()} is {@code null}
     */
    public static AttributeMeta validateBinds(AttributeMeta meta) {
        if (meta.binds() == null) {
            throw new IllegalStateException(
                    "AttributeMeta.binds must not be null — binds is a required field.");
        }
        return meta;
    }

    /**
     * Parses and validates a single JSON element in one step.
     *
     * <p>Equivalent to {@code validateBinds(parse(json))}. Convenience for
     * callers that want both the codec-level required-field check and the
     * explicit non-null binds guarantee in a single call.</p>
     *
     * @param json the JSON element representing one attribute metadata entry
     * @return the decoded and validated {@link AttributeMeta}
     * @throws IllegalStateException if parsing fails or {@code binds} is null
     */
    public static AttributeMeta parseAndValidate(JsonElement json) {
        return validateBinds(parse(json));
    }

    /**
     * Builds a parse exception from a DataResult error message.
     *
     * @param message the error message produced by the codec
     * @return an {@link IllegalStateException} wrapping the message
     */
    private static RuntimeException parseException(String message) {
        return new IllegalStateException("Failed to parse attribute metadata JSON: " + message);
    }
}
