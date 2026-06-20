package org.yanbwe.modularshoot.datapack;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;

/**
 * Parsing and validation utility for the
 * {@code modularshoot:plugin_types} datapack JSON format.
 *
 * <p>This is a thin, pure-function wrapper around
 * {@link PluginTypeDefinition#CODEC}. It provides manual parsing entry points
 * for callers that need to decode a single plugin type JSON object outside of
 * the vanilla datapack registry pipeline (e.g. for testing, programmatic
 * registration, or pre-validation before insertion).</p>
 *
 * <p>The datapack JSON path is
 * {@code data/<namespace>/modularshoot/plugin_types/<type_id>.json}.
 * The vanilla datapack registry mechanism resolves the path and feeds the
 * parsed JSON element to {@link PluginTypeDefinition#CODEC}; this class
 * exposes the same decode step as a reusable static method.</p>
 *
 * <p>All methods are pure: they have no side effects and do not touch any
 * registry. Post-load validation (checking that {@code tags} is non-empty so
 * the category can match plugins) is surfaced as a warning marker via
 * {@link #validate(PluginTypeDefinition)} and
 * {@link #parseAndValidate(JsonElement)}; the actual {@code WARN} logging is
 * handled by {@link PluginTypeDatapackLoader}.</p>
 *
 * <h2>Supported fields</h2>
 * <ul>
 *   <li>{@code tags} — tag list for plugin matching; defaults to an empty
 *       list. An empty list means the category cannot match any plugin; this
 *       is not a codec error but is surfaced as a warning marker.</li>
 *   <li>{@code priority} — optional display priority, defaults to {@code 0}
 *       (guaranteed by the Codec).</li>
 *   <li>{@code name} — optional category display name.</li>
 *   <li>{@code color} — optional category display name colour.</li>
 * </ul>
 *
 * @see PluginTypeDefinition#CODEC
 * @see PluginTypeDatapackLoader
 */
public final class PluginTypeJsonCodec {
    private PluginTypeJsonCodec() {
    }

    /**
     * Warning marker text returned (not logged) when a plugin type has an
     * empty {@code tags} list. A category with no tags cannot match any
     * plugin via set intersection (设计文档 line 2200).
     */
    public static final String EMPTY_TAGS_WARNING =
            "Plugin type has an empty 'tags' list; it cannot match any plugin.";

    /**
     * Parses a single JSON element into a {@link PluginTypeDefinition}.
     *
     * <p>Delegates to {@link PluginTypeDefinition#CODEC} bound to
     * {@link JsonOps#INSTANCE}. All fields are optional in the codec, so a
     * completely empty JSON object parses successfully into a definition with
     * default values (empty tags, priority 0, no name, no colour).</p>
     *
     * @param json the JSON element representing one plugin type entry
     * @return the decoded, immutable {@link PluginTypeDefinition}
     * @throws IllegalStateException if the JSON does not conform to the codec
     *         (e.g. wrong types, invalid
     *         {@link net.minecraft.resources.ResourceLocation} in the tags
     *         list)
     */
    public static PluginTypeDefinition parse(JsonElement json) {
        return parseSafe(json).getOrThrow(PluginTypeJsonCodec::parseException);
    }

    /**
     * Parses a single JSON element into a {@link DataResult} without
     * throwing.
     *
     * <p>Use this when the caller wants to inspect the error message or
     * recover from malformed input without an exception. The returned
     * {@link DataResult} is either a success carrying the
     * {@link PluginTypeDefinition}, or an error carrying a human-readable
     * message.</p>
     *
     * @param json the JSON element representing one plugin type entry
     * @return a {@link DataResult} that succeeds with the decoded
     *         {@link PluginTypeDefinition} or fails with a parse error message
     */
    public static DataResult<PluginTypeDefinition> parseSafe(JsonElement json) {
        return PluginTypeDefinition.CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * Validates a parsed definition and returns a list of warning markers.
     *
     * <p>Pure: no side effects, no logging. Currently surfaces a single
     * warning marker when the {@code tags} list is empty
     * (设计文档 line 2200). The Codec already guarantees {@code priority}
     * defaults to {@code 0} when absent, so no warning is emitted for a
     * missing priority. An empty tags list is not a codec error — the
     * category is still registered, but it cannot match any plugin; the
     * caller decides whether to log this.</p>
     *
     * @param definition the definition to validate
     * @return an immutable list of warning markers; empty when the definition
     *         has no validation warnings
     */
    public static List<String> validate(PluginTypeDefinition definition) {
        return hasEmptyTags(definition) ? List.of(EMPTY_TAGS_WARNING) : List.of();
    }

    /**
     * Checks whether the definition's {@code tags} list is empty.
     *
     * <p>A category with no tags cannot match any plugin via set
     * intersection (设计文档 line 2200).</p>
     *
     * @param definition the definition to check
     * @return {@code true} when the tags list is empty
     */
    public static boolean hasEmptyTags(PluginTypeDefinition definition) {
        return definition.tags().isEmpty();
    }

    /**
     * Parses and validates a single JSON element in one step, returning both
     * the decoded definition and any validation warnings without throwing.
     *
     * <p>This is the pure, side-effect-free equivalent of
     * {@code validate(parse(json))}: on parse success it carries the
     * definition plus warning markers (e.g. an empty tags list); on parse
     * failure it carries the error message and no definition. Callers inspect
     * {@link ParseResult#isValid()} and {@link ParseResult#hasWarnings()} to
     * branch on the outcome.</p>
     *
     * @param json the JSON element representing one plugin type entry
     * @return a {@link ParseResult}; never {@code null}
     */
    public static ParseResult parseAndValidate(JsonElement json) {
        final DataResult<PluginTypeDefinition> result = parseSafe(json);
        final Optional<PluginTypeDefinition> parsed = result.result();
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
        return new IllegalStateException("Failed to parse plugin type JSON: " + message);
    }

    /**
     * Immutable outcome of parsing and validating a single plugin type JSON
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
            Optional<PluginTypeDefinition> definition,
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
        public static ParseResult success(PluginTypeDefinition definition, List<String> warnings) {
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
