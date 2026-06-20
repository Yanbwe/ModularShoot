package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.yanbwe.modularshoot.plugin.PluginDefinition;

/**
 * Parsing and validation utility for the {@code modularshoot:plugins}
 * datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around
 * {@link PluginDefinition#CODEC}. It provides manual parsing entry points
 * for callers that need to decode a single plugin JSON object outside of
 * the vanilla datapack registry pipeline (e.g. for testing, programmatic
 * registration, or pre-validation before insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/plugins/<plugin_id>.json}.
 * The vanilla datapack registry mechanism resolves the path and feeds the
 * parsed JSON element to {@link PluginDefinition#CODEC}; this class exposes
 * the same decode step as a reusable static method (设计文档 §插件数据包
 * JSON).</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking that {@code tags} is non-empty
 * so the plugin can match categories) is surfaced as a warning marker via
 * {@link #validate(PluginDefinition)} and
 * {@link #parseAndValidate(JsonElement)}; the actual {@code WARN} logging
 * is handled by {@link PluginDatapackLoader} and the
 * {@code DatapackErrorHandler} (子任务08).</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code tags} — tag list for category intersection matching;
 *       defaults to an empty list. An empty list means the plugin cannot
 *       match any category; this is not a codec error but is surfaced as a
 *       warning marker.</li>
 *   <li>{@code priority} — optional conflict priority, defaults to
 *       {@code 0} (guaranteed by the Codec).</li>
 *   <li>{@code item_icon} — required inventory icon texture path. The
 *       Codec declares this as {@code fieldOf("item_icon")}, so a missing
 *       key causes the parse to fail at the codec layer.</li>
 *   <li>{@code modifiers} — attribute modifiers; defaults to an empty
 *       list. Each modifier's {@code operation} must be
 *       {@code add}/{@code multiply}/{@code multiply_total} (enforced by
 *       the Codec).</li>
 *   <li>{@code traits} — boolean trait overrides; defaults to an empty
 *       map.</li>
 *   <li>{@code exclusive_group} — optional mutual-exclusion group id.</li>
 *   <li>{@code bullet_style} — optional projectile appearance override.</li>
 *   <li>{@code texture_overlay} — optional texture overlay.</li>
 *   <li>{@code name}/{@code brief}/{@code description}/{@code color} —
 *       optional display fields.</li>
 * </ul>
 *
 * @see PluginDefinition#CODEC
 * @see PluginDatapackLoader
 */
public final class PluginJsonCodec {
    private PluginJsonCodec() {
    }

    /**
     * Warning marker text returned (not logged) when a plugin has an empty
     * {@code tags} list. A plugin with no tags cannot match any category
     * via set intersection and therefore cannot be installed
     * (设计文档 §插件数据包 JSON).
     */
    public static final String EMPTY_TAGS_WARNING =
            "Plugin has an empty 'tags' list; it cannot match any category and cannot be installed.";

    /**
     * Parses a single JSON element into a {@link PluginDefinition}.
     *
     * <p>Delegates to {@link PluginDefinition#CODEC} bound to
     * {@link JsonOps#INSTANCE}. The {@code item_icon} field is declared
     * {@code fieldOf} (not {@code optionalFieldOf}) in the codec, so a
     * missing {@code item_icon} key causes the parse to fail with an error
     * message — satisfying the "item_icon required" constraint at the
     * codec layer. All other fields are optional with codec-level defaults
     * (设计文档 §可选字段缺失时使用框架默认值).</p>
     *
     * @param json the JSON element representing one plugin entry
     * @return the decoded, immutable {@link PluginDefinition}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. missing {@code item_icon}, wrong types, invalid
     *         {@link net.minecraft.resources.ResourceLocation})
     */
    public static PluginDefinition parse(JsonElement json) {
        return parseSafe(json).getOrThrow(PluginJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the
     * {@link PluginDefinition}, or an error carrying a human-readable
     * message.</p>
     *
     * @param json the JSON element representing one plugin entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link PluginDefinition} or fails with a parse error message
     */
    public static DataResult<PluginDefinition> parseSafe(JsonElement json) {
        return PluginDefinition.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates a parsed definition and returns a list of warning markers.
     *
     * <p>Pure: no side effects, no logging. Currently surfaces a single
     * warning marker when the {@code tags} list is empty
     * (设计文档 §插件数据包 JSON). The Codec already guarantees
     * {@code item_icon} is present (via {@code fieldOf}) and
     * {@code priority} defaults to {@code 0} when absent, so no warning is
     * emitted for those. An empty tags list is not a codec error — the
     * plugin is still registered, but it cannot match any category; the
     * caller decides whether to log this.</p>
     *
     * @param definition the definition to validate
     * @return an immutable list of warning markers; empty when the
     *         definition has no validation warnings
     */
    public static List<String> validate(PluginDefinition definition) {
        List<String> warnings = new ArrayList<>();
        if (hasEmptyTags(definition)) {
            warnings.add(EMPTY_TAGS_WARNING);
        }
        return List.copyOf(warnings);
    }

    /**
     * Checks whether the definition's {@code tags} list is empty.
     *
     * <p>A plugin with no tags cannot match any category via set
     * intersection and therefore cannot be installed (设计文档 §插件数据包
     * JSON).</p>
     *
     * @param definition the definition to check
     * @return {@code true} when the tags list is empty
     */
    public static boolean hasEmptyTags(PluginDefinition definition) {
        return definition.tags().isEmpty();
    }

    /**
     * Checks whether the definition's {@code item_icon} is non-null.
     *
     * <p>The Codec enforces {@code item_icon} presence at parse time via
     * {@code fieldOf("item_icon")}, so a definition obtained through
     * {@link #parse(JsonElement)} always satisfies this check. This
     * predicate is a defensive guard for definitions constructed directly
     * in Java (bypassing the Codec), where {@code item_icon} could be
     * {@code null}.</p>
     *
     * @param definition the definition to check
     * @return {@code true} when {@code item_icon} is non-null
     */
    public static boolean hasItemIcon(PluginDefinition definition) {
        return definition.itemIcon() != null;
    }

    /**
     * Parses and validates a single JSON element in one step, returning both
     * the decoded definition and any validation warnings without throwing.
     *
     * <p>This is the pure, side-effect-free equivalent of
     * {@code validate(parse(json))}: on parse success it carries the
     * definition plus warning markers (e.g. an empty tags list); on parse
     * failure it carries the error message and no definition. Callers
     * inspect {@link ParseResult#isValid()} and
     * {@link ParseResult#hasWarnings()} to branch on the outcome.</p>
     *
     * @param json the JSON element representing one plugin entry
     * @return a {@link ParseResult}; never {@code null}
     */
    public static ParseResult parseAndValidate(JsonElement json) {
        final DataResult<PluginDefinition> result = parseSafe(json);
        final Optional<PluginDefinition> parsed = result.result();
        if (parsed.isEmpty()) {
            final String error = result.error()
                    .map(DataResult.Error::message)
                    .orElse("Unknown parse error");
            return ParseResult.failure(error);
        }
        return ParseResult.success(parsed.get(), validate(parsed.get()));
    }

    /**
     * Builds a parse exception from a DataResult error message.
     *
     * @param message the error message produced by the codec
     * @return an {@link IllegalStateException} wrapping the message
     */
    private static RuntimeException parseException(String message) {
        return new IllegalStateException("Failed to parse plugin JSON: " + message);
    }

    /**
     * Immutable outcome of parsing and validating a single plugin JSON
     * entry.
     *
     * <p>On success {@link #definition()} is present and {@link #error()} is
     * empty; {@link #warnings()} carries any post-parse validation warning
     * markers (e.g. an empty tags list). On failure {@link #definition()} is
     * empty and {@link #error()} carries the parse error message;
     * {@link #warnings()} is then empty.</p>
     *
     * @param definition the parsed definition; empty on parse failure
     * @param error      the parse error message; empty on success
     * @param warnings   post-parse validation warning markers; empty on
     *                   parse failure
     */
    public record ParseResult(
            Optional<PluginDefinition> definition,
            Optional<String> error,
            List<String> warnings
    ) {
        /**
         * Factory for a successful parse.
         *
         * @param definition the parsed definition; must not be {@code null}
         * @param warnings   validation warning markers; copied into an
         *                   immutable list
         * @return a success result
         */
        public static ParseResult success(PluginDefinition definition, List<String> warnings) {
            return new ParseResult(
                    Optional.of(definition),
                    Optional.empty(),
                    List.copyOf(warnings));
        }

        /**
         * Factory for a failed parse.
         *
         * @param error the human-readable parse error message
         * @return a failure result with no definition and no warnings
         */
        public static ParseResult failure(String error) {
            return new ParseResult(Optional.empty(), Optional.of(error), List.of());
        }

        /** @return {@code true} when a definition was successfully parsed */
        public boolean isValid() {
            return definition.isPresent();
        }

        /** @return {@code true} when there are validation warnings */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }
}
