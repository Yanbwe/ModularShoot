package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Optional;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;

/**
 * Parsing and validation utility for the {@code modularshoot:guns}
 * datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around
 * {@link GunDefinition#CODEC}. It provides manual parsing entry points for
 * callers that need to decode a single gun JSON object outside of the
 * vanilla datapack registry pipeline (e.g. for testing, programmatic
 * registration, or pre-validation before insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/guns/<gun_id>.json}. The vanilla
 * datapack registry mechanism resolves the path and feeds the parsed JSON
 * element to {@link GunDefinition#CODEC}; this class exposes the same
 * decode step as a reusable static method (设计文档 §枪械数据包JSON格式).</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking internal consistency of a parsed
 * gun definition — namespacing, value ranges, cross-field sanity) is handled
 * by {@link GunDatapackLoader}.</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code name} — optional display name; supports colour codes and the
 *       {@code lang:} translation-key prefix</li>
 *   <li>{@code texture} — required, base texture path</li>
 *   <li>{@code shoot_texture} — optional texture swapped in while firing;
 *       absent → base texture is used throughout</li>
 *   <li>{@code shoot_texture_mode} — optional, defaults to
 *       {@code per_shot}; only effective when {@code shoot_texture} is
 *       present</li>
 *   <li>{@code stats} — attribute id → value; keys must be fully
 *       namespaced</li>
 *   <li>{@code traits} — trait id → flag; keys must be fully namespaced;
 *       empty object {@code {}} is valid (no inherent traits)</li>
 *   <li>{@code slots} — plugin category id → slot count; keys must be
 *       fully namespaced</li>
 *   <li>{@code sounds} — sound slot name → sound event id</li>
 *   <li>{@code bullet_style} — optional projectile appearance; absent →
 *       default bullet appearance (pure collision body)</li>
 * </ul>
 *
 * @see GunDefinition#CODEC
 * @see GunDatapackLoader
 */
public final class GunJsonCodec {
    private GunJsonCodec() {
    }

    /**
     * Parses a single JSON element into a {@link GunDefinition}.
     *
     * <p>Delegates to {@link GunDefinition#CODEC} bound to
     * {@link JsonOps#INSTANCE}. The {@code texture} field is declared
     * {@code fieldOf} (not {@code optionalFieldOf}) in the codec, so a
     * missing {@code texture} key causes the parse to fail with an error
     * message — satisfying the "texture required" constraint at the codec
     * layer. All other fields are optional with codec-level defaults
     * (设计文档 §可选字段缺失时使用框架默认值).</p>
     *
     * @param json the JSON element representing one gun entry
     * @return the decoded, immutable {@link GunDefinition}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. missing {@code texture}, wrong types, invalid
     *         {@link net.minecraft.resources.ResourceLocation})
     */
    public static GunDefinition parse(JsonElement json) {
        return parseSafe(json).getOrThrow(GunJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the
     * {@link GunDefinition}, or an error carrying a human-readable
     * message.</p>
     *
     * @param json the JSON element representing one gun entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link GunDefinition} or fails with a parse error message
     */
    public static DataResult<GunDefinition> parseSafe(JsonElement json) {
        return GunDefinition.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates that the required and non-nullable fields of a
     * {@link GunDefinition} are present and well-formed.
     *
     * <p>The codec already enforces {@code texture} as a required field and
     * supplies defaults for {@code shoot_texture_mode}, {@code stats},
     * {@code traits}, {@code slots}, and {@code sounds}, so a value parsed
     * through {@link #parse(JsonElement)} can never violate these. This
     * method provides an explicit, defensive check for {@link GunDefinition}
     * instances constructed programmatically (e.g. via the record
     * constructor) where the codec's guarantees do not apply.</p>
     *
     * <p>Checks performed:</p>
     * <ul>
     *   <li>{@code texture} is non-null (codec-required field)</li>
     *   <li>{@code shootTextureMode} is non-null (codec default is
     *       {@code PER_SHOT})</li>
     *   <li>{@code stats}, {@code traits}, {@code slots}, {@code sounds}
     *       maps are non-null (codec defaults are empty maps)</li>
     *   <li>{@code name}, {@code shootTexture}, {@code bulletStyle}
     *       Optionals are non-null and do not wrap null values</li>
     * </ul>
     *
     * @param gun the gun definition to check
     * @return the same {@code gun} instance, for chaining
     * @throws IllegalStateException if any non-nullable field is null
     */
    public static GunDefinition validateRequiredFields(GunDefinition gun) {
        if (gun.texture() == null) {
            throw new IllegalStateException(
                    "GunDefinition.texture must not be null — required field.");
        }
        if (gun.shootTextureMode() == null) {
            throw new IllegalStateException(
                    "GunDefinition.shootTextureMode must not be null — required field.");
        }
        validateMapField("stats", gun.stats());
        validateMapField("traits", gun.traits());
        validateMapField("slots", gun.slots());
        validateMapField("sounds", gun.sounds());
        validateOptionalField("name", gun.name());
        validateOptionalField("shoot_texture", gun.shootTexture());
        validateOptionalField("bullet_style", gun.bulletStyle());
        return gun;
    }

    /**
     * Parses and validates a single JSON element in one step.
     *
     * <p>Equivalent to {@code validateRequiredFields(parse(json))}.
     * Convenience for callers that want both the codec-level required-field
     * check and the explicit non-null guarantee in a single call.</p>
     *
     * @param json the JSON element representing one gun entry
     * @return the decoded and validated {@link GunDefinition}
     * @throws IllegalStateException if parsing fails or a required field is
     *         null
     */
    public static GunDefinition parseAndValidate(JsonElement json) {
        return validateRequiredFields(parse(json));
    }

    /**
     * Checks that a map field is non-null.
     *
     * @param fieldName the field name, used in the error message
     * @param map       the map to check
     * @throws IllegalStateException if {@code map} is null
     */
    private static void validateMapField(String fieldName, Map<?, ?> map) {
        if (map == null) {
            throw new IllegalStateException(
                    "GunDefinition." + fieldName + " must not be null — required field.");
        }
    }

    /**
     * Checks that an Optional field is non-null and does not wrap a null
     * value.
     *
     * @param fieldName the field name, used in the error message
     * @param value     the Optional to check
     * @param <T>       the wrapped type
     * @throws IllegalStateException if {@code value} is null or wraps null
     */
    private static <T> void validateOptionalField(String fieldName, Optional<T> value) {
        if (value == null) {
            throw new IllegalStateException(
                    "GunDefinition." + fieldName + " must not be null — required field.");
        }
        if (value.isPresent() && value.get() == null) {
            throw new IllegalStateException(
                    "GunDefinition." + fieldName + " must not wrap a null value.");
        }
    }

    /**
     * Builds a parse exception from a DataResult error message.
     *
     * @param message the error message produced by the codec
     * @return an {@link IllegalStateException} wrapping the message
     */
    private static RuntimeException parseException(String message) {
        return new IllegalStateException("Failed to parse gun JSON: " + message);
    }
}
