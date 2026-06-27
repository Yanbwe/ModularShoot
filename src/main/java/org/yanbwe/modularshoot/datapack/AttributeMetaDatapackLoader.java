package org.yanbwe.modularshoot.datapack;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Post-load validation utility for the {@code modularshoot:attribute_meta}
 * datapack table.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link AttributeMeta} entries, this class performs a secondary validation
 * pass: it checks that each entry's {@code binds} field points to an
 * attribute that actually exists in {@link BuiltInRegistries#ATTRIBUTE}.</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When {@code binds} points to an unregistered attribute, the entry is
 * <strong>still considered registered</strong> — it is not removed from the
 * {@code attribute_meta} registry. Instead, the validation returns a
 * {@link BindingValidation} carrying a warning marker. The actual degradation
 * handling (e.g. falling back to a no-op attribute, hiding the entry from
 * tooltips, or retrying resolution after late-registered attributes load) is
 * deferred to subtask 13. This class only <em>marks</em> the problem.</p>
 *
 * <p>The attribute <em>body</em> must be registered via vanilla
 * {@code DeferredRegister} into {@code BuiltInRegistries.ATTRIBUTE} during
 * mod loading; the datapack only registers metadata. This validator bridges
 * the two worlds: metadata (hot-reloadable) vs. attribute bodies (static).</p>
 *
 * @see AttributeMeta
 * @see AttributeMetaJsonCodec
 * @see BuiltInRegistries#ATTRIBUTE
 */
public final class AttributeMetaDatapackLoader {

    private AttributeMetaDatapackLoader() {
    }

    /**
     * Checks whether a vanilla {@link Attribute} is registered under the
     * given id in {@link BuiltInRegistries#ATTRIBUTE}.
     *
     * <p>Uses {@link net.minecraft.core.Registry#getOptional(ResourceLocation)}
     * which returns {@code Optional.empty()} for both unregistered ids and
     * {@code null} input, making this method null-safe.</p>
     *
     * @param attributeId the attribute id to look up; {@code null} returns
     *                    {@code false}
     * @return {@code true} if a vanilla {@link Attribute} is registered under
     *         the given id
     */
    public static boolean isAttributeRegistered(@Nullable ResourceLocation attributeId) {
        return BuiltInRegistries.ATTRIBUTE.getOptional(attributeId).isPresent();
    }

    /**
     * Resolves the vanilla {@link Attribute} bound by an {@link AttributeMeta}.
     *
     * @param meta the metadata entry whose {@code binds} to resolve
     * @return the registered {@link Attribute}, or {@code Optional.empty()}
     *         if {@code binds} is unregistered or {@code null}
     */
    public static Optional<Attribute> getBoundAttribute(AttributeMeta meta) {
        return BuiltInRegistries.ATTRIBUTE.getOptional(meta.binds());
    }

    /**
     * Validates that an {@link AttributeMeta} entry's {@code binds} points
     * to a registered vanilla {@link Attribute}.
     *
     * <p>This is a post-load validation: it assumes the {@link AttributeMeta}
     * has already been parsed and registered. The entry is never rejected —
     * when {@code binds} is unregistered, a {@link BindingValidation} with
     * a warning marker is returned so the caller can decide on degradation
     * (subtask 13).</p>
     *
     * @param logicalId the logical id of the entry (the registry key path
     *                  from {@code attribute_meta/<logical_id>.json}); used
     *                  in the warning message for traceability
     * @param meta      the parsed metadata entry to validate
     * @return a {@link BindingValidation} that is either {@code ok} (binds
     *         registered) or {@code unbound} (binds not registered, with a
     *         warning message)
     */
    public static BindingValidation validateBinding(
            ResourceLocation logicalId, AttributeMeta meta) {
        if (isAttributeRegistered(meta.binds())) {
            return BindingValidation.ok(meta);
        }
        final String warning = buildUnboundWarning(logicalId, meta.binds());
        DatapackErrorHandler.logReferenceWarning(logicalId, warning);
        return BindingValidation.unbound(meta, warning);
    }

    /**
     * Validates {@code binds} for a batch of attribute metadata entries.
     *
     * <p>Each entry is validated independently; one unregistered bind does
     * not abort the batch. The returned map preserves the input keys and is
     * unmodifiable.</p>
     *
     * @param entries the logical id to {@link AttributeMeta} mapping
     * @return an unmodifiable map of logical id to {@link BindingValidation}
     */
    public static Map<ResourceLocation, BindingValidation> validateBindings(
            Map<ResourceLocation, AttributeMeta> entries) {
        return entries.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> validateBinding(entry.getKey(), entry.getValue())));
    }

    /**
     * Builds the warning message for an unregistered bind.
     *
     * @param logicalId   the entry's logical id
     * @param bindsTarget the unregistered attribute id
     * @return the warning text
     */
    private static String buildUnboundWarning(
            ResourceLocation logicalId, ResourceLocation bindsTarget) {
        return "AttributeMeta '" + logicalId + "' binds to unregistered attribute '"
                + bindsTarget + "'; entry registered with warning (degradation deferred).";
    }

    /**
     * Result of a post-load binding validation for one {@link AttributeMeta}.
     *
     * <p>Always carries the original {@code meta} so the caller can proceed
     * with registration regardless of the binding status. The
     * {@code bindsRegistered} flag and optional {@code warning} tell the
     * caller whether degradation handling (subtask 13) should kick in.</p>
     *
     * @param meta           the validated metadata entry (never {@code null})
     * @param bindsRegistered {@code true} if {@code meta.binds()} resolves to
     *                       a registered vanilla {@link Attribute}
     * @param warning        the warning message when {@code bindsRegistered}
     *                       is {@code false}; empty when the binding is valid
     */
    public record BindingValidation(
            AttributeMeta meta,
            boolean bindsRegistered,
            Optional<String> warning) {

        /**
         * Factory for a successful validation: {@code binds} is registered.
         *
         * @param meta the validated metadata entry
         * @return a {@link BindingValidation} with {@code bindsRegistered = true}
         *         and no warning
         */
        public static BindingValidation ok(AttributeMeta meta) {
            return new BindingValidation(meta, true, Optional.empty());
        }

        /**
         * Factory for a warning validation: {@code binds} is not registered.
         *
         * @param meta    the validated metadata entry (still registered)
         * @param warning the human-readable warning message
         * @return a {@link BindingValidation} with {@code bindsRegistered = false}
         *         and the given warning
         */
        public static BindingValidation unbound(AttributeMeta meta, String warning) {
            return new BindingValidation(meta, false, Optional.of(warning));
        }
    }
}
