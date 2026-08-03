package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.MapCodec;

/**
 * Non-failing sentinel produced by {@link Modifier#CODEC} when a modifier
 * entry's {@code "type"} key is not one of the three concrete permit names
 * (spec §5 "其余继续组合"). Carried through decode so the rest of the
 * modifiers list keeps parsing; the composition layer
 * ({@link org.yanbwe.modularshoot.bullet.VisualCompositionService}) skips it
 * and emits a {@code WARN} per occurrence.
 *
 * <p>The offending type key is preserved as {@link #unknownType()} so logs
 * can name the malformed entry. {@link #type()} returns the sentinel tag
 * {@link #TYPE_NAME} (not the offending key), because the dispatch codec's
 * resolution function uses the decoded {@code "type"} value to choose the
 * MapCodec — returning the offending key here would self-loop into the
 * unknown branch on encode. Encoding is handled by the sentinel MapCodec
 * via {@link Modifier} (see {@link Modifier#CODEC}); for a round-trip of an
 * unsupported entry, the entry re-encodes as {@code {"type":"<offendingKey>"}}
 * and re-decodes to a fresh sentinel carrying the same offending key
 * (preserved by the {@code Codec.unit} resolution branch).</p>
 */
public record UnsupportedModifier(String unknownType) implements Modifier {

    /** Sentinel type tag returned by {@link #type()}; never written to JSON. */
    public static final String TYPE_NAME = "unsupported";

    @Override
    public String type() {
        return TYPE_NAME;
    }

    /**
     * Builds a {@link MapCodec#unit} sentinel MapCodec that decodes any JSON
     * body (the dispatch consumes {@code "type"}) to an
     * {@link UnsupportedModifier} carrying the offending key. Used by
     * {@link Modifier#CODEC} for unknown-type keys.
     *
     * @param offendingKey the unrecognised {@code "type"} value, or
     *                     {@code "<missing>"} if the {@code "type"} field was
     *                     absent (defensive; dispatch should not pass null)
     * @return a unit MapCodec emitting a fresh sentinel
     */
    public static MapCodec<UnsupportedModifier> unitCodec(String offendingKey) {
        return MapCodec.unit(() -> new UnsupportedModifier(offendingKey));
    }
}