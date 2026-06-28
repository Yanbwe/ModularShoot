package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Display metadata for a state entry in the gun tooltip.
 *
 * <p>Controls how a state value is presented to the player: the name label,
 * its colour, the value formatting template, sort order, and whether to
 * suppress the line when the value equals the default.</p>
 *
 * @param name        state name shown in the tooltip; supports colour codes
 *                    ({@code §}) and the {@code lang:} translation-key prefix
 * @param color       optional hex colour code for the name (e.g.
 *                    {@code "#FFAA00"}); empty when omitted, in which case
 *                    the tooltip renderer falls back to its default colour
 * @param format      display template containing the {@code {value}}
 *                    placeholder; defaults to {@code "{value}"} when absent
 * @param priority    tooltip sort order — higher values appear earlier;
 *                    defaults to {@code 0}
 * @param hideDefault when {@code true} the line is hidden if the value
 *                    equals the default; defaults to {@code false}
 */
public record StateDisplay(
        String name,
        Optional<String> color,
        String format,
        int priority,
        boolean hideDefault
) {
    /** Default format template used when {@code format} is omitted from JSON. */
    public static final String DEFAULT_FORMAT = "{value}";

    public static final Codec<StateDisplay> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.STRING.fieldOf("name").forGetter(StateDisplay::name),
                    Codec.STRING.optionalFieldOf("color").forGetter(StateDisplay::color),
                    Codec.STRING.optionalFieldOf("format", DEFAULT_FORMAT).forGetter(StateDisplay::format),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(StateDisplay::priority),
                    Codec.BOOL.optionalFieldOf("hide_default", false).forGetter(StateDisplay::hideDefault)
            ).apply(instance, StateDisplay::new)
    );

    /**
     * Convenience factory that fills optional fields with their defaults.
     *
     * @param name  the tooltip display name
     * @param color the hex colour code, or empty to use the default colour
     * @return a new {@link StateDisplay} with default format, priority, and
     *         hideDefault
     */
    public static StateDisplay of(String name, Optional<String> color) {
        return new StateDisplay(name, color, DEFAULT_FORMAT, 0, false);
    }
}
