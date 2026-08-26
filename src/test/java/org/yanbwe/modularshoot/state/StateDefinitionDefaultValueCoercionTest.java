package org.yanbwe.modularshoot.state;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for the {@link StateDefinition} default-value coercion (审查 R4):
 * the datapack decode chain tries DOUBLE before FLOAT, so a JSON
 * {@code value_type: "float"} with {@code default_value: 2.5} decodes as a
 * {@link Double}; the compact constructor must converge it onto the declared
 * type so typed readers ({@code getFloat}) never see a mistyped default.
 */
class StateDefinitionDefaultValueCoercionTest {

    private static StateDisplay display() {
        return StateDisplay.of("test", Optional.of("#FFFFFF"));
    }

    @Test
    void doubleDefaultConvergesOntoFloatType() {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, StateValueType.FLOAT, Double.valueOf(2.5), display(), List.of());

        assertInstanceOf(Float.class, def.defaultValue(),
                "double default must be coerced to the declared float type (审查 R4)");
        assertEquals(2.5f, (Float) def.defaultValue(), 0.0f);
    }

    @Test
    void integerDefaultConvergesOntoDoubleType() {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, StateValueType.DOUBLE, Integer.valueOf(3), display(), List.of());

        assertInstanceOf(Double.class, def.defaultValue());
        assertEquals(3.0, (Double) def.defaultValue(), 0.0);
    }

    @Test
    void integerDefaultConvergesOntoLongType() {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, StateValueType.LONG, Integer.valueOf(7), display(), List.of());

        assertInstanceOf(Long.class, def.defaultValue());
        assertEquals(7L, (Long) def.defaultValue());
    }

    @Test
    void wellTypedDefaultsPassThroughUnchanged() {
        StateDefinition floatDef = new StateDefinition(
                StateDomain.GUN, StateValueType.FLOAT, 1.5f, display(), List.of());
        StateDefinition stringDef = new StateDefinition(
                StateDomain.GUN, StateValueType.STRING, "mode", display(), List.of());

        assertEquals(1.5f, (Float) floatDef.defaultValue(), 0.0f);
        assertEquals("mode", stringDef.defaultValue());
    }

    @Test
    void incoercibleDefaultFallsBackToZeroValue() {
        // A string default for an int state cannot be coerced — degrade to
        // the zero value instead of surfacing a mistyped default later.
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, StateValueType.INT, "not-a-number", display(), List.of());

        assertEquals(StateValueType.INT.zeroValue(), def.defaultValue());
    }

    @Test
    void nullDefaultBecomesZeroValue() {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, StateValueType.INT, null, display(), List.of());

        assertEquals(StateValueType.INT.zeroValue(), def.defaultValue());
    }
}
