package org.yanbwe.modularshoot.registry.attribute;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Metadata entry for the {@code modularshoot:attribute_meta} datapack table.
 *
 * <p>The attribute metadata table is a hot-reloadable mapping from a logical
 * attribute id to its metadata. It does <strong>not</strong> create the vanilla
 * {@code Attribute} instance; it only describes how the framework references and
 * presents an already-registered vanilla attribute.</p>
 *
 * <p>Attribute <em>bodies</em> must be registered via vanilla
 * {@code DeferredRegister} into {@code BuiltInRegistries.ATTRIBUTE} during mod
 * loading and cannot be hot-reloaded. This record captures the orthogonal,
 * hot-reloadable concerns: default value, display info, and the binding to a
 * registered vanilla attribute.</p>
 *
 * @param binds         required, points to an already-registered vanilla
 *                      {@code Attribute} id. The framework resolves the
 *                      attribute holder at runtime through this field.
 *                      For built-in attributes the logical id equals the body
 *                      id and {@code binds} points to itself.
 * @param defaultValue  the gun's base value when a gun definition does not
 *                      declare this attribute. Participates in {@code ADD_VALUE}
 *                      calculations. Stored in the metadata table and is
 *                      hot-reloadable. Unrelated to the vanilla attribute's
 *                      {@code base} (which is always 0 for framework attributes).
 * @param description   optional human-readable description text; empty when
 *                      absent.
 * @param color         optional hex color code (e.g. {@code "#FF4444"}) for the
 *                      attribute name display; empty when absent.
 * @param priority      optional display priority; higher values appear earlier
 *                      in the tooltip. Defaults to {@code 0}.
 * @param forceShow     optional flag; when {@code true} the attribute is shown
 *                      in the tooltip even if its value equals the default.
 *                      Defaults to {@code false}.
 */
public record AttributeMeta(
        ResourceLocation binds,
        double defaultValue,
        String description,
        Optional<String> color,
        int priority,
        boolean forceShow
) {
    public static final Codec<AttributeMeta> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("binds").forGetter(AttributeMeta::binds),
                    Codec.DOUBLE.fieldOf("default_value").forGetter(AttributeMeta::defaultValue),
                    Codec.STRING.optionalFieldOf("description", "").forGetter(AttributeMeta::description),
                    Codec.STRING.optionalFieldOf("color").forGetter(AttributeMeta::color),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(AttributeMeta::priority),
                    Codec.BOOL.optionalFieldOf("force_show", false).forGetter(AttributeMeta::forceShow)
            ).apply(instance, AttributeMeta::new)
    );

    /**
     * Convenience factory for creating an {@link AttributeMeta} with only the
     * required fields. Optional fields are filled with their defaults
     * (empty description, no color, priority {@code 0}, forceShow {@code false}).
     *
     * @param binds        the registered vanilla attribute id to bind to
     * @param defaultValue the gun base value used when a gun omits this attribute
     * @return a new immutable {@link AttributeMeta} instance
     */
    public static AttributeMeta of(ResourceLocation binds, double defaultValue) {
        return new AttributeMeta(binds, defaultValue, "", Optional.empty(), 0, false);
    }
}
