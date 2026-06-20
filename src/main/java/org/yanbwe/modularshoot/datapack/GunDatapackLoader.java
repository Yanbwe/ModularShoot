package org.yanbwe.modularshoot.datapack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-load validation utility for the {@code modularshoot:guns} datapack
 * table.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link GunDefinition} entries, this class performs a secondary validation
 * pass: it checks each entry's fields for internal consistency (namespaced
 * keys, finite stat values, non-negative slot counts, non-empty sound slot
 * names, non-empty bullet style model, and shoot_texture differing from the
 * base texture).</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When a gun fails a consistency check, the entry is <strong>still
 * considered registered</strong> — it is not removed from the
 * {@code guns} registry. Instead, the validation returns a
 * {@link GunValidation} carrying a warning marker. The actual degradation
 * handling (e.g. falling back to default values, hiding the entry) is
 * deferred to {@code DatapackErrorHandler} (子任务08). This class only
 * <em>marks</em> the problem (设计文档 §definition-loss degradation).</p>
 *
 * <p>Unlike {@link AttributeMetaDatapackLoader}, gun definitions do not bind
 * to an external vanilla registry, so there is no cross-registry resolution
 * to perform. The validation here is purely internal to the gun
 * definition.</p>
 *
 * @see GunDefinition
 * @see GunJsonCodec
 */
public final class GunDatapackLoader {
    /** Dedicated subsystem logger for gun validation. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/Gun");

    private GunDatapackLoader() {
    }

    /**
     * Validates a single {@link GunDefinition} entry for internal
     * consistency.
     *
     * <p>This is a post-load validation: it assumes the {@link GunDefinition}
     * has already been parsed and registered. The entry is never rejected —
     * when a consistency check fails, a {@link GunValidation} with a warning
     * marker is returned so the caller can decide on degradation.</p>
     *
     * <p>Checks performed (all produce warnings, not errors):</p>
     * <ul>
     *   <li>{@code texture} is non-null</li>
     *   <li>{@code shoot_texture} (if present) differs from
     *       {@code texture}</li>
     *   <li>{@code stats}/{@code traits}/{@code slots} keys are fully
     *       namespaced (not using the default {@code minecraft}
     *       namespace)</li>
     *   <li>{@code stats} values are finite (not NaN or Infinite)</li>
     *   <li>{@code slots} counts are non-negative</li>
     *   <li>{@code sounds} slot names are non-empty</li>
     *   <li>{@code bullet_style} (if present) has a non-empty model map</li>
     * </ul>
     *
     * @param gunId the gun id (the registry key path from
     *              {@code guns/<gun_id>.json}); used in warning messages
     *              for traceability
     * @param gun   the parsed gun definition to validate
     * @return a {@link GunValidation} that is either {@code ok} (all checks
     *         passed) or {@code warned} (one or more checks failed with a
     *         warning message)
     */
    public static GunValidation validateGun(
            ResourceLocation gunId, GunDefinition gun) {
        final Optional<String> textureWarning = checkTexturePath(gunId, gun);
        final Optional<String> namespacingWarning = checkNamespacing(gunId, gun);
        final Optional<String> statValuesWarning = checkStatValues(gunId, gun);
        final Optional<String> slotCountsWarning = checkSlotCounts(gunId, gun);
        final Optional<String> soundBindingsWarning = checkSoundBindings(gunId, gun);
        final Optional<String> bulletStyleWarning = checkBulletStyle(gunId, gun);
        final Optional<String> warning = combineWarnings(
                textureWarning, namespacingWarning, statValuesWarning,
                slotCountsWarning, soundBindingsWarning, bulletStyleWarning);
        if (warning.isEmpty()) {
            return GunValidation.ok(gun);
        }
        LOGGER.warn(warning.get());
        return GunValidation.warned(gun, warning.get());
    }

    /**
     * Validates a batch of gun entries.
     *
     * <p>Each entry is validated independently; one bad entry does not abort
     * the batch. The returned map preserves the input keys and is
     * unmodifiable.</p>
     *
     * @param entries the gun id to {@link GunDefinition} mapping
     * @return an unmodifiable map of gun id to {@link GunValidation}
     */
    public static Map<ResourceLocation, GunValidation> validateGuns(
            Map<ResourceLocation, GunDefinition> entries) {
        return entries.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> validateGun(entry.getKey(), entry.getValue())));
    }

    /**
     * Checks that the base texture is non-null and that shoot_texture (if
     * present) differs from the base texture.
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message if texture is null or shoot_texture equals
     *         texture, empty otherwise
     */
    private static Optional<String> checkTexturePath(
            ResourceLocation gunId, GunDefinition gun) {
        if (gun.texture() == null) {
            return Optional.of(buildWarning(gunId, "texture", "is null"));
        }
        if (gun.shootTexture().isPresent()
                && gun.shootTexture().get().equals(gun.texture())) {
            return Optional.of(buildWarning(gunId, "shoot_texture",
                    "equals texture; swap has no effect"));
        }
        return Optional.empty();
    }

    /**
     * Checks that stats/traits/slots keys are fully namespaced — i.e. do
     * not use the default {@code minecraft} namespace, which usually means
     * the author forgot to prefix the key with their mod id (设计文档
     * §键名必须完全命名空间化).
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message listing offending keys, empty if all keys
     *         are explicitly namespaced
     */
    private static Optional<String> checkNamespacing(
            ResourceLocation gunId, GunDefinition gun) {
        final List<String> offending = Stream.of(
                collectDefaultNamespaceKeys(gun.stats(), "stats"),
                collectDefaultNamespaceKeys(gun.traits(), "traits"),
                collectDefaultNamespaceKeys(gun.slots(), "slots"))
            .flatMap(List::stream)
            .toList();
        if (offending.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildWarning(gunId, "namespacing",
                "keys using default namespace: " + String.join(", ", offending)));
    }

    /**
     * Collects keys from a map that use the default {@code minecraft}
     * namespace.
     *
     * @param map   the map whose keys to scan
     * @param field the field name for the offending-key label
     * @return a list of {@code "field:'key'"} strings for offending keys
     */
    private static List<String> collectDefaultNamespaceKeys(
            Map<ResourceLocation, ?> map, String field) {
        return map.keySet().stream()
                .filter(key -> ResourceLocation.DEFAULT_NAMESPACE.equals(key.getNamespace()))
                .map(key -> field + ":'" + key + "'")
                .toList();
    }

    /**
     * Checks that all stat values are finite (not NaN or Infinite).
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message listing non-finite stats, empty if all
     *         values are finite
     */
    private static Optional<String> checkStatValues(
            ResourceLocation gunId, GunDefinition gun) {
        final List<String> bad = gun.stats().entrySet().stream()
                .filter(entry -> {
                    final double v = entry.getValue();
                    return Double.isNaN(v) || Double.isInfinite(v);
                })
                .map(entry -> "'" + entry.getKey() + "'=" + entry.getValue())
                .toList();
        if (bad.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildWarning(gunId, "stats",
                "non-finite values: " + String.join(", ", bad)));
    }

    /**
     * Checks that all slot counts are non-negative.
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message listing negative slot counts, empty if all
     *         counts are non-negative
     */
    private static Optional<String> checkSlotCounts(
            ResourceLocation gunId, GunDefinition gun) {
        final List<String> bad = gun.slots().entrySet().stream()
                .filter(entry -> entry.getValue() < 0)
                .map(entry -> "'" + entry.getKey() + "'=" + entry.getValue())
                .toList();
        if (bad.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildWarning(gunId, "slots",
                "negative slot counts: " + String.join(", ", bad)));
    }

    /**
     * Checks that all sound slot names are non-empty.
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message if any sound slot name is empty, empty
     *         otherwise
     */
    private static Optional<String> checkSoundBindings(
            ResourceLocation gunId, GunDefinition gun) {
        final boolean hasEmpty = gun.sounds().keySet().stream()
                .anyMatch(name -> name == null || name.isEmpty());
        if (!hasEmpty) {
            return Optional.empty();
        }
        return Optional.of(buildWarning(gunId, "sounds",
                "one or more sound slot names are empty"));
    }

    /**
     * Checks that bullet_style (if present) has a non-empty model map.
     *
     * @param gunId the gun id for the warning message
     * @param gun   the gun definition to check
     * @return a warning message if bullet_style.model is empty, empty
     *         otherwise
     */
    private static Optional<String> checkBulletStyle(
            ResourceLocation gunId, GunDefinition gun) {
        if (gun.bulletStyle().isEmpty()) {
            return Optional.empty();
        }
        if (gun.bulletStyle().get().model().isEmpty()) {
            return Optional.of(buildWarning(gunId, "bullet_style",
                    "model map is empty"));
        }
        return Optional.empty();
    }

    /**
     * Combines multiple optional warnings into a single message.
     *
     * @param warnings the optional warning messages to combine
     * @return the combined warning, or empty if all inputs are empty
     */
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
     * @param gunId     the gun id
     * @param fieldName the field that failed validation
     * @param detail    the human-readable detail
     * @return the warning text
     */
    private static String buildWarning(
            ResourceLocation gunId, String fieldName, String detail) {
        return "Gun '" + gunId + "' field '" + fieldName + "': " + detail
                + "; entry registered with warning (degradation deferred).";
    }

    /**
     * Result of a post-load validation for one {@link GunDefinition}.
     *
     * <p>Always carries the original {@code gun} so the caller can proceed
     * with registration regardless of the validation status. The
     * {@code valid} flag and optional {@code warning} tell the caller
     * whether degradation handling should kick in.</p>
     *
     * @param gun     the validated gun definition (never {@code null})
     * @param valid   {@code true} if all consistency checks passed
     * @param warning the warning message when {@code valid} is {@code false};
     *                empty when the gun is valid
     */
    public record GunValidation(
            GunDefinition gun,
            boolean valid,
            Optional<String> warning) {

        /**
         * Factory for a successful validation: all checks passed.
         *
         * @param gun the validated gun definition
         * @return a {@link GunValidation} with {@code valid = true} and no
         *         warning
         */
        public static GunValidation ok(GunDefinition gun) {
            return new GunValidation(gun, true, Optional.empty());
        }

        /**
         * Factory for a warning validation: one or more checks failed.
         *
         * @param gun     the validated gun definition (still registered)
         * @param warning the human-readable warning message
         * @return a {@link GunValidation} with {@code valid = false} and
         *         the given warning
         */
        public static GunValidation warned(GunDefinition gun, String warning) {
            return new GunValidation(gun, false, Optional.of(warning));
        }
    }
}
