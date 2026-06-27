package org.yanbwe.modularshoot.client.tooltip;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared utility methods for all tooltip builders (设计文档 §系统九).
 *
 * <p>Centralises three cross-cutting concerns used by every bar builder:
 * <ul>
 *   <li><b>Colour parsing</b> — hex strings ({@code "#FF4444"}) to RGB ints,
 *       with a white fallback on parse failure so a malformed colour never
 *       crashes the tooltip.</li>
 *   <li><b>Localised text resolution</b> — display strings that may use the
 *       {@code lang:} translation-key prefix (设计文档 §本地化, lines
 *       1616-1643). Strings starting with {@code "lang:"} are treated as
 *       translation keys; all others are literal text.</li>
 *   <li><b>Numeric formatting</b> — trims trailing zeros via
 *       {@code #.##} so {@code 2.0} renders as {@code "2"} and {@code 0.5}
 *       renders as {@code "0.5"} (设计文档 §属性栏: 数值使用灰色).</li>
 * </ul>
 * </p>
 *
 * <p>The class is not instantiable; all methods are static.</p>
 *
 * @see AttributeTooltipBuilder
 * @see TraitTooltipBuilder
 * @see StateTooltipBuilder
 * @see PluginBarTooltipBuilder
 */
public final class TooltipUtils {

    /** Decimal format for attribute values: trims trailing zeros (#.##). */
    private static final DecimalFormat VALUE_FORMAT = new DecimalFormat(
            "#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private TooltipUtils() {
    }

    /**
     * Parses a hex colour string to an RGB integer.
     *
     * <p>Accepts both {@code "#FFAA00"} and {@code "FFAA00"} formats.
     * Falls back to white ({@code 0xFFFFFF}) on parse failure so a
     * malformed colour never crashes the tooltip.</p>
     *
     * @param hex the hex colour string; may be empty
     * @return the RGB integer value, or {@code 0xFFFFFF} on parse failure
     */
    public static int parseHexColor(String hex) {
        try {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(clean, 16);
        } catch (NumberFormatException ex) {
            return 0xFFFFFF;
        }
    }

    /**
     * Resolves a display text that may use the {@code lang:} translation-key
     * prefix (设计文档 §本地化, lines 1616-1643).
     *
     * <p>Strings starting with {@code "lang:"} are treated as translation
     * keys: the prefix is stripped and the remainder is passed to
     * {@link Component#translatable}. All other strings are wrapped in
     * {@link Component#literal}.</p>
     *
     * @param text the raw display text, possibly prefixed with {@code "lang:"}
     * @return a {@link MutableComponent} for the resolved text
     */
    public static MutableComponent resolveText(String text) {
        if (text.startsWith("lang:")) {
            return Component.translatable(text.substring(5));
        }
        return Component.literal(text);
    }

    /**
     * Formats a numeric value, trimming trailing zeros.
     *
     * <p>Uses the {@code #.##} pattern so integers render without a decimal
     * point ({@code 2.0} → {@code "2"}) and fractional values keep up to
     * two decimals ({@code 0.5} → {@code "0.5"}, {@code 3.14} →
     * {@code "3.14"}).</p>
     *
     * @param value the numeric value to format
     * @return the formatted string
     */
    public static String formatValue(double value) {
        return VALUE_FORMAT.format(value);
    }
}
