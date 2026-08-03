package org.yanbwe.modularshoot.bullet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Pure-function evaluator for state-condition thresholds in visual modifier
 * composition (设计规格 §3.5 "条件操作符 (op)" + §5 错误处理行
 * "UUID 不支持条件判定", "类型不匹配降级").
 *
 * <p>Independent of any registry: callers pass op + raw state value +
 * threshold; this class only decides whether the condition holds. UUID
 * rejection and type-mismatch both return {@code false} (no exceptions);
 * the caller is responsible for the {@code skip + WARN} path via
 * {@link org.yanbwe.modularshoot.state.StateWarnLogger} when {@code eval}
 * returns {@code false} under a known-mismatch.</p>
 */
public final class StateConditionEvaluator {

    private StateConditionEvaluator() {
    }

    /**
     * Comparison operators usable by state-condition modifiers (spec §3.5).
     * The {@link #symbol} is the JSON/wire token (e.g. {@code ">="}); the
     * {@link #CODEC} round-trips between the symbol string and the enum.
     */
    public enum Op {
        GE(">="),
        LE("<="),
        EQ("=="),
        GT(">"),
        LT("<");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        /**
         * StringRepresentable-style codec: {@code ">= "} &harr;
         * {@link Op#GE}, etc. Unknown symbol -> {@link DataResult#error}
         * (the modifier-list tolerant path catches that into a skip+WARN).
         */
        public static final Codec<Op> CODEC = Codec.STRING.flatXmap(
                s -> {
                    for (Op op : values()) {
                        if (op.symbol.equals(s)) {
                            return DataResult.success(op);
                        }
                    }
                    return DataResult.error(() -> "Unknown op: " + s);
                },
                op -> DataResult.success(op.symbol));
    }

    /**
     * Returns whether the condition ({@code op}/{@code threshold}) is
     * satisfied by the given {@code stateValue}. Never throws. Mismatches
     * degrade to {@code false}:
     *
     * <ul>
     *   <li>{@code null} {@code stateValue} or {@code threshold} →
     *       {@code false}</li>
     *   <li>UUID {@code stateValue} (any state whose runtime type is
     *       {@code UUID}) → {@code false} (UUID unsupported, caller logs
     *       WARN and skips)</li>
     *   <li>Numeric ops ({@code >=,<=,>,<}) against non-numeric values →
     *       {@code false}</li>
     *   <li>{@code EQ} supported across numeric, String, Boolean</li>
     *   <li>Numeric comparisons unify via {@code ((Number)x).doubleValue()}</li>
     * </ul>
     *
     * @param op         the comparison operator
     * @param stateValue the raw runtime state value (may be {@code null})
     * @param threshold  the typed threshold from the condition (may be
     *                   {@code null})
     * @return whether the condition holds; {@code false} on any mismatch
     */
    public static boolean eval(Op op, @Nullable Object stateValue, @Nullable Object threshold) {
        if (stateValue == null || threshold == null) return false;
        if (stateValue instanceof UUID || threshold instanceof UUID) return false;
        if (stateValue instanceof Boolean || threshold instanceof Boolean) {
            if (op != Op.EQ) return false;
            if (!(stateValue instanceof Boolean) || !(threshold instanceof Boolean)) return false;
            return stateValue.equals(threshold);
        }
        if (stateValue instanceof String || threshold instanceof String) {
            if (op != Op.EQ) return false;
            if (!(stateValue instanceof String) || !(threshold instanceof String)) return false;
            return stateValue.equals(threshold);
        }
        // numeric path
        if (stateValue instanceof Number && threshold instanceof Number) {
            double a = ((Number) stateValue).doubleValue();
            double b = ((Number) threshold).doubleValue();
            return switch (op) {
                case GE -> a >= b;
                case LE -> a <= b;
                case GT -> a > b;
                case LT -> a < b;
                case EQ -> a == b;
            };
        }
        // type mismatch not covered above
        return false;
    }
}