package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * A single attribute modifier applied by an installed plugin.
 *
 * <p>Each modifier targets one attribute (identified by its string name, e.g.
 * {@code "fire_rate"}) and applies an arithmetic {@link Operation} with the
 * supplied {@code value}. The {@code Operation} enum mirrors the three vanilla
 * {@code net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation}
 * modes but uses shorter JSON names so datapack authors do not have to type the
 * verbose vanilla identifiers.</p>
 *
 * @param attribute target attribute name (e.g. {@code "fire_rate"})
 * @param operation arithmetic operation applied to the attribute
 * @param value     the modifier amount
 */
public record PluginModifier(String attribute, Operation operation, double value) {

    public static final Codec<PluginModifier> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.STRING.fieldOf("attribute").forGetter(PluginModifier::attribute),
                    Operation.CODEC.fieldOf("operation").forGetter(PluginModifier::operation),
                    Codec.DOUBLE.fieldOf("value").forGetter(PluginModifier::value)
            ).apply(instance, PluginModifier::new)
    );

    /**
     * Arithmetic operation a {@link PluginModifier} applies to its target
     * attribute.
     *
     * <p>Each constant maps to a vanilla
     * {@code net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation}:
     * <ul>
     *   <li>{@link #ADD} ({@code "add"}) &rarr; {@code ADD_VALUE} &mdash; adds
     *       the value to the attribute's base value.</li>
     *   <li>{@link #MULTIPLY} ({@code "multiply"}) &rarr;
     *       {@code ADD_MULTIPLIED_BASE} &mdash; adds {@code value * base} to
     *       the base value.</li>
     *   <li>{@link #MULTIPLY_TOTAL} ({@code "multiply_total"}) &rarr;
     *       {@code ADD_MULTIPLIED_TOTAL} &mdash; multiplies the final result
     *       by {@code (1 + value)}.</li>
     * </ul>
     */
    public enum Operation implements StringRepresentable {
        ADD("add"),
        MULTIPLY("multiply"),
        MULTIPLY_TOTAL("multiply_total");

        public static final Codec<Operation> CODEC = StringRepresentable.fromEnum(Operation::values);

        private final String name;

        Operation(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
