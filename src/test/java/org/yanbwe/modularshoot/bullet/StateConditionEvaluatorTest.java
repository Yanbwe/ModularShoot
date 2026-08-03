package org.yanbwe.modularshoot.bullet;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link StateConditionEvaluator} op semantar:
 * numeric comparison ops across Number sub-types, boolean/string == only,
 * UUID rejection, type-mismatch degradation to {@code false}, and null
 * safety. These guard the leaf of
 * {@link VisualCompositionService#collectStateConditions} so skip + WARN
 * semantics (设计规格 §5) flow from {@code eval == false}.
 */
class StateConditionEvaluatorTest {

    private static boolean eval(StateConditionEvaluator.Op op, Object stateValue, Object threshold) {
        return StateConditionEvaluator.eval(op, stateValue, threshold);
    }

    // --- numeric ops ---

    @Test
    void numericGeTrue() { assertTrue(eval(StateConditionEvaluator.Op.GE, 5, 3)); }
    @Test
    void numericGeEqualityInclusive() { assertTrue(eval(StateConditionEvaluator.Op.GE, 3, 3)); }
    @Test
    void numericGeFalseUnder() { assertFalse(eval(StateConditionEvaluator.Op.GE, 2, 3)); }

    @Test
    void numericLeTrue() { assertTrue(eval(StateConditionEvaluator.Op.LE, 2, 3)); }
    @Test
    void numericLeEqualityInclusive() { assertTrue(eval(StateConditionEvaluator.Op.LE, 3, 3)); }
    @Test
    void numericLeFalseOver() { assertFalse(eval(StateConditionEvaluator.Op.LE, 4, 3)); }

    @Test
    void numericGtTrue() { assertTrue(eval(StateConditionEvaluator.Op.GT, 5, 3)); }
    @Test
    void numericGtEqualityFalse() { assertFalse(eval(StateConditionEvaluator.Op.GT, 3, 3)); }

    @Test
    void numericLtTrue() { assertTrue(eval(StateConditionEvaluator.Op.LT, 2, 3)); }
    @Test
    void numericLtEqualityFalse() { assertFalse(eval(StateConditionEvaluator.Op.LT, 3, 3)); }

    @Test
    void numericEqTrue() { assertTrue(eval(StateConditionEvaluator.Op.EQ, 3, 3)); }
    @Test
    void numericEqFalse() { assertFalse(eval(StateConditionEvaluator.Op.EQ, 3, 4)); }

    @Test
    void crossNumericTypeIntLongDouble() {
        assertTrue(eval(StateConditionEvaluator.Op.GE, 3L, 3));   // Long vs Integer
        assertTrue(eval(StateConditionEvaluator.Op.GE, 3.0, 3)); // Double vs Integer
        assertTrue(eval(StateConditionEvaluator.Op.EQ, 5L, 5.0)); // Long vs Double, 5.0 == 5L
    }

    @Test
    void floatVsDoubleEquality() {
        assertTrue(eval(StateConditionEvaluator.Op.EQ, 3.5f, 3.5)); // Float vs Double, same numeric value
    }

    // --- boolean op ---

    @Test
    void booleanEqTrue() { assertTrue(eval(StateConditionEvaluator.Op.EQ, true, true)); }
    @Test
    void booleanEqFalse() { assertFalse(eval(StateConditionEvaluator.Op.EQ, true, false)); }
    @Test
    void booleanNonEqRejected() {
        // Only == supported for boolean; other ops are mismatch → false
        assertFalse(eval(StateConditionEvaluator.Op.GE, true, false));
        assertFalse(eval(StateConditionEvaluator.Op.LT, true, false));
    }

    // --- string op ---

    @Test
    void stringEqTrue() { assertTrue(eval(StateConditionEvaluator.Op.EQ, "abc", "abc")); }
    @Test
    void stringEqFalse() { assertFalse(eval(StateConditionEvaluator.Op.EQ, "abc", "abd")); }
    @Test
    void stringNonEqRejected() {
        assertFalse(eval(StateConditionEvaluator.Op.GE, "abc", "abc"));
        assertFalse(eval(StateConditionEvaluator.Op.LT, "abc", "abc"));
    }

    // --- UUID rejection (返回 false；调用方负责 skip+WARN) ---

    @Test
    void uuidStateAlwaysFalse() {
        UUID u = UUID.randomUUID();
        assertFalse(eval(StateConditionEvaluator.Op.EQ, u, u));
        assertFalse(eval(StateConditionEvaluator.Op.GE, u, u));
    }

    // --- type mismatch 降级：返回 false ---

    @Test
    void mismatchNumericStateVsBooleanThreshold() {
        assertFalse(eval(StateConditionEvaluator.Op.GE, 5, true));
    }

    @Test
    void mismatchStringStateVsNumericThreshold() {
        assertFalse(eval(StateConditionEvaluator.Op.GE, "abc", 5));
    }

    // --- null safety ---

    @Test
    void nullStateValueReturnsFalse() {
        assertFalse(eval(StateConditionEvaluator.Op.GE, null, 5));
    }

    @Test
    void nullThresholdReturnsFalse() {
        assertFalse(eval(StateConditionEvaluator.Op.GE, 5, null));
    }
}