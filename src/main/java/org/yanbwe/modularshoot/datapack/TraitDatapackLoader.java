package org.yanbwe.modularshoot.datapack;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.Trait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-load validation utility for the {@code modularshoot:traits} datapack
 * table.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link Trait} entries, this class performs a secondary validation pass:
 * it checks each entry's optional display fields for internal consistency
 * (valid hex colour, non-blank name/brief, sensible priority range).</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When a trait fails a consistency check, the entry is <strong>still
 * considered registered</strong> — it is not removed from the
 * {@code traits} registry. Instead, the validation returns a
 * {@link TraitValidation} carrying a warning marker. The actual degradation
 * handling (e.g. falling back to a default colour, hiding the entry from
 * tooltips) is deferred to the tooltip/render layer. This class only
 * <em>marks</em> the problem (设计文档 §definition-loss degradation).</p>
 *
 * <p>Unlike {@link AttributeMetaDatapackLoader}, traits do not bind to an
 * external vanilla registry, so there is no cross-registry resolution to
 * perform. The validation here is purely internal to the trait definition.</p>
 *
 * @see Trait
 * @see TraitJsonCodec
 */
public final class TraitDatapackLoader {
    /** Dedicated subsystem logger for trait validation. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/Trait");

    /** Pattern matching a 6-digit hex colour code with optional {@code #} prefix. */
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#?[0-9A-Fa-f]{6}$");

    /** Lower bound for a sensible display priority; below this a warning is emitted. */
    private static final int PRIORITY_MIN = 0;

    /** Upper bound for a sensible display priority; above this a warning is emitted. */
    private static final int PRIORITY_MAX = 1000;

    private TraitDatapackLoader() {
    }

    /**
     * Validates a single {@link Trait} entry for internal consistency.
     *
     * <p>This is a post-load validation: it assumes the {@link Trait} has
     * already been parsed and registered. The entry is never rejected —
     * when a consistency check fails, a {@link TraitValidation} with a
     * warning marker is returned so the caller can decide on degradation.</p>
     *
     * <p>Checks performed (all produce warnings, not errors):</p>
     * <ul>
     *   <li>{@code color} (if present) matches a 6-digit hex colour
     *       pattern</li>
     *   <li>{@code name} (if present) is non-blank</li>
     *   <li>{@code brief} (if present) is non-blank</li>
     *   <li>{@code priority} is within {@code [0, 1000]}</li>
     * </ul>
     *
     * @param traitId the trait id (the registry key path from
     *                {@code traits/<trait_id>.json}); used in warning
     *                messages for traceability
     * @param trait   the parsed trait entry to validate
     * @return a {@link TraitValidation} that is either {@code ok} (all
     *         checks passed) or {@code warned} (one or more checks failed
     *         with a warning message)
     */
    public static TraitValidation validateTrait(
            ResourceLocation traitId, Trait trait) {
        final Optional<String> colorWarning = checkColor(traitId, trait);
        final Optional<String> nameWarning = checkName(traitId, trait);
        final Optional<String> briefWarning = checkBrief(traitId, trait);
        final Optional<String> priorityWarning = checkPriority(traitId, trait);
        final Optional<String> warning = combineWarnings(
                colorWarning, nameWarning, briefWarning, priorityWarning);
        if (warning.isEmpty()) {
            return TraitValidation.ok(trait);
        }
        LOGGER.warn(warning.get());
        return TraitValidation.warned(trait, warning.get());
    }

    /**
     * Validates a batch of trait entries.
     *
     * <p>Each entry is validated independently; one bad entry does not abort
     * the batch. The returned map preserves the input keys and is
     * unmodifiable.</p>
     *
     * @param entries the trait id to {@link Trait} mapping
     * @return an unmodifiable map of trait id to {@link TraitValidation}
     */
    public static Map<ResourceLocation, TraitValidation> validateTraits(
            Map<ResourceLocation, Trait> entries) {
        return entries.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> validateTrait(entry.getKey(), entry.getValue())));
    }

    /**
     * Checks that the {@code color} field (if present) is a valid hex colour.
     *
     * @param traitId the trait id for the warning message
     * @param trait   the trait to check
     * @return a warning message if the colour is invalid, empty otherwise
     */
    private static Optional<String> checkColor(ResourceLocation traitId, Trait trait) {
        return trait.color()
                .filter(c -> !HEX_COLOR_PATTERN.matcher(c).matches())
                .map(c -> buildWarning(traitId, "color",
                        "expected 6-digit hex (e.g. '#FF4444'), got '" + c + "'"));
    }

    /**
     * Checks that the {@code name} field (if present) is non-blank.
     *
     * @param traitId the trait id for the warning message
     * @param trait   the trait to check
     * @return a warning message if the name is blank, empty otherwise
     */
    private static Optional<String> checkName(ResourceLocation traitId, Trait trait) {
        return trait.name()
                .filter(String::isBlank)
                .map(n -> buildWarning(traitId, "name", "must not be blank when present"));
    }

    /**
     * Checks that the {@code brief} field (if present) is non-blank.
     *
     * @param traitId the trait id for the warning message
     * @param trait   the trait to check
     * @return a warning message if the brief is blank, empty otherwise
     */
    private static Optional<String> checkBrief(ResourceLocation traitId, Trait trait) {
        return trait.brief()
                .filter(String::isBlank)
                .map(b -> buildWarning(traitId, "brief", "must not be blank when present"));
    }

    /**
     * Checks that the {@code priority} field is within the sensible range.
     *
     * @param traitId the trait id for the warning message
     * @param trait   the trait to check
     * @return a warning message if the priority is out of range, empty
     *         otherwise
     */
    private static Optional<String> checkPriority(ResourceLocation traitId, Trait trait) {
        final int priority = trait.priority();
        if (priority < PRIORITY_MIN || priority > PRIORITY_MAX) {
            return Optional.of(buildWarning(traitId, "priority",
                    "expected [" + PRIORITY_MIN + ", " + PRIORITY_MAX + "], got "
                            + priority));
        }
        return Optional.empty();
    }

    /**
     * Combines multiple optional warnings into a single message.
     *
     * @param warnings the optional warning messages to combine
     * @return the combined warning, or empty if all inputs are empty
     */
    @SafeVarargs
    private static Optional<String> combineWarnings(Optional<String>... warnings) {
        final StringBuilder builder = new StringBuilder();
        for (Optional<String> warning : warnings) {
            warning.ifPresent(w -> builder.append(w).append(" "));
        }
        final String combined = builder.toString().trim();
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    /**
     * Builds a single-field warning message.
     *
     * @param traitId   the trait id
     * @param fieldName the field that failed validation
     * @param detail    the human-readable detail
     * @return the warning text
     */
    private static String buildWarning(
            ResourceLocation traitId, String fieldName, String detail) {
        return "Trait '" + traitId + "' field '" + fieldName + "': " + detail
                + "; entry registered with warning (degradation deferred).";
    }

    /**
     * Result of a post-load validation for one {@link Trait}.
     *
     * <p>Always carries the original {@code trait} so the caller can proceed
     * with registration regardless of the validation status. The
     * {@code valid} flag and optional {@code warning} tell the caller whether
     * degradation handling should kick in.</p>
     *
     * @param trait   the validated trait entry (never {@code null})
     * @param valid   {@code true} if all consistency checks passed
     * @param warning the warning message when {@code valid} is {@code false};
     *                empty when the trait is valid
     */
    public record TraitValidation(
            Trait trait,
            boolean valid,
            Optional<String> warning) {

        /**
         * Factory for a successful validation: all checks passed.
         *
         * @param trait the validated trait entry
         * @return a {@link TraitValidation} with {@code valid = true} and no
         *         warning
         */
        public static TraitValidation ok(Trait trait) {
            return new TraitValidation(trait, true, Optional.empty());
        }

        /**
         * Factory for a warning validation: one or more checks failed.
         *
         * @param trait   the validated trait entry (still registered)
         * @param warning the human-readable warning message
         * @return a {@link TraitValidation} with {@code valid = false} and
         *         the given warning
         */
        public static TraitValidation warned(Trait trait, String warning) {
            return new TraitValidation(trait, false, Optional.of(warning));
        }
    }
}
