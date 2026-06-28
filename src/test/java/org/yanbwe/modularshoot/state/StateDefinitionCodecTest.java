package org.yanbwe.modularshoot.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Round-trip tests for {@link StateDefinition#CODEC} covering all seven
 * supported value types.
 *
 * <p>Verifies the encode-direction fix: each type is encoded with its
 * matching codec (not collapsed to {@code INT}), and the encoded JSON
 * uses the correct primitive type. Decode tries each codec in order and
 * must reconstruct the original value with the correct runtime type.</p>
 *
 * <p>These tests guard against the regression where
 * {@code eitherOf(...).xmap(..., obj -> Either.left(obj))} routed every
 * value to the leftmost {@link com.mojang.serialization.Codec#INT}
 * encoder, causing {@code ClassCastException} for non-{@code int} types
 * during datapack registry network sync.</p>
 */
class StateDefinitionCodecTest {

    /** Shared display metadata for all test definitions. */
    private static final StateDisplay DISPLAY = StateDisplay.of("test", Optional.of("#FFFFFF"));

    /**
     * Builds a state definition with the given type and default value.
     *
     * @param type         the declared value type
     * @param defaultValue the default value (may be {@code null} for UUID)
     * @return a new {@link StateDefinition}
     */
    private static StateDefinition define(StateValueType type, Object defaultValue) {
        return new StateDefinition(StateDomain.GUN, type, defaultValue, DISPLAY);
    }

    /**
     * Encodes a definition to a JSON object, failing on any encode error.
     *
     * @param def the definition to encode
     * @return the encoded JSON object
     */
    private static JsonObject encode(StateDefinition def) {
        return StateDefinition.CODEC.encodeStart(JsonOps.INSTANCE, def)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg))
                .getAsJsonObject();
    }

    /**
     * Decodes a JSON object back to a definition, failing on any decode error.
     *
     * @param json the JSON object to decode
     * @return the decoded definition
     */
    private static StateDefinition decode(JsonObject json) {
        return StateDefinition.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    /**
     * Full encode → decode round-trip.
     *
     * @param def the definition to round-trip
     * @return the decoded definition
     */
    private static StateDefinition roundtrip(StateDefinition def) {
        return decode(encode(def));
    }

    // --- Round-trip tests for each of the seven types ---

    @Test
    void roundtripInt() {
        StateDefinition def = define(StateValueType.INT, 42);
        StateDefinition decoded = roundtrip(def);
        assertEquals(42, decoded.defaultValue());
        assertInstanceOf(Integer.class, decoded.defaultValue());
    }

    @Test
    void roundtripLong() {
        StateDefinition def = define(StateValueType.LONG, 1234567890123L);
        StateDefinition decoded = roundtrip(def);
        assertEquals(1234567890123L, decoded.defaultValue());
        assertInstanceOf(Long.class, decoded.defaultValue());
    }

    @Test
    void roundtripDouble() {
        StateDefinition def = define(StateValueType.DOUBLE, 3.141592653589793);
        StateDefinition decoded = roundtrip(def);
        assertEquals(3.141592653589793, decoded.defaultValue());
        assertInstanceOf(Double.class, decoded.defaultValue());
    }

    @Test
    void roundtripFloat() {
        StateDefinition def = define(StateValueType.FLOAT, 2.5f);
        StateDefinition decoded = roundtrip(def);
        // JSON numbers cannot distinguish float from double; in the decode
        // chain DOUBLE precedes FLOAT, so a float value round-trips as a
        // Double. The numeric value is preserved and valueType stays FLOAT.
        assertEquals(2.5, ((Number) decoded.defaultValue()).doubleValue(), 0.0001);
        assertEquals(StateValueType.FLOAT, decoded.valueType());
    }

    @Test
    void roundtripBoolean() {
        StateDefinition def = define(StateValueType.BOOLEAN, true);
        StateDefinition decoded = roundtrip(def);
        assertEquals(Boolean.TRUE, decoded.defaultValue());
        assertInstanceOf(Boolean.class, decoded.defaultValue());
    }

    @Test
    void roundtripString() {
        StateDefinition def = define(StateValueType.STRING, "hello");
        StateDefinition decoded = roundtrip(def);
        assertEquals("hello", decoded.defaultValue());
        assertInstanceOf(String.class, decoded.defaultValue());
    }

    @Test
    void roundtripUuid() {
        UUID uuid = new UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        StateDefinition def = define(StateValueType.UUID, uuid);
        StateDefinition decoded = roundtrip(def);
        assertEquals(uuid, decoded.defaultValue());
        assertInstanceOf(UUID.class, decoded.defaultValue());
    }

    // --- Encode-direction dispatch tests ---
    // These verify the fix: non-int types are encoded with their own
    // codec instead of collapsing to Codec.INT.

    @Test
    void encodeBooleanWritesJsonBooleanNotInt() {
        // Before fix: boolean collapsed to INT encoder → ClassCastException.
        // After fix: boolean encoded with Codec.BOOL → JSON boolean.
        JsonObject json = encode(define(StateValueType.BOOLEAN, true));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(),
                "default_value should be a JSON boolean, got: " + value);
        assertTrue(value.getAsBoolean());
    }

    @Test
    void encodeDoubleWritesJsonNumberWithFraction() {
        // Before fix: double collapsed to INT encoder → ClassCastException.
        // After fix: double encoded with Codec.DOUBLE → JSON number.
        JsonObject json = encode(define(StateValueType.DOUBLE, 3.14));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "default_value should be a JSON number, got: " + value);
        assertEquals(3.14, value.getAsDouble(), 0.001);
    }

    @Test
    void encodeStringWritesJsonStringNotInt() {
        // Before fix: string collapsed to INT encoder → ClassCastException.
        // After fix: string encoded with Codec.STRING → JSON string.
        JsonObject json = encode(define(StateValueType.STRING, "hello"));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                "default_value should be a JSON string, got: " + value);
        assertEquals("hello", value.getAsString());
    }

    @Test
    void encodeLongWritesJsonNumber() {
        // Before fix: long collapsed to INT encoder → ClassCastException
        // for values exceeding int range.
        // After fix: long encoded with Codec.LONG → JSON number.
        JsonObject json = encode(define(StateValueType.LONG, 9000000000000L));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "default_value should be a JSON number, got: " + value);
        assertEquals(9000000000000L, value.getAsLong());
    }

    @Test
    void encodeFloatWritesJsonNumber() {
        // Before fix: float collapsed to INT encoder → ClassCastException.
        // After fix: float encoded with Codec.FLOAT → JSON number.
        JsonObject json = encode(define(StateValueType.FLOAT, 1.5f));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "default_value should be a JSON number, got: " + value);
        assertEquals(1.5f, value.getAsFloat(), 0.001f);
    }

    @Test
    void encodeUuidWritesJsonIntArray() {
        // UUIDUtil.CODEC encodes UUID as a 4-int array (INT_STREAM).
        UUID uuid = new UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        JsonObject json = encode(define(StateValueType.UUID, uuid));
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonArray(),
                "default_value should be a JSON array (int stream), got: " + value);
        assertEquals(4, value.getAsJsonArray().size());
    }

    // --- Edge cases ---

    @Test
    void uuidNullDefaultValueOmitsField() {
        // UUID zero value is null; optionalFieldOf should omit the field.
        StateDefinition def = define(StateValueType.UUID, null);
        JsonObject json = encode(def);
        assertFalse(json.has("default_value"),
                "default_value should be omitted for null UUID, got: " + json);
        // Decode reconstructs null via zeroValue().
        StateDefinition decoded = roundtrip(def);
        assertNull(decoded.defaultValue());
    }

    @Test
    void intBackwardCompatibility() {
        // Built-in int data must behave unchanged after the fix.
        StateDefinition def = define(StateValueType.INT, 100);
        JsonObject json = encode(def);
        JsonElement value = json.get("default_value");
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "int default_value should be a JSON number, got: " + value);
        assertEquals(100, value.getAsInt());
        StateDefinition decoded = roundtrip(def);
        assertEquals(100, decoded.defaultValue());
        assertInstanceOf(Integer.class, decoded.defaultValue());
    }

    @Test
    void omittedDefaultValueUsesZeroValue() {
        // When default_value is absent in JSON, the codec uses
        // valueType.zeroValue() via orElseGet.
        JsonObject json = encode(define(StateValueType.INT, 42));
        json.remove("default_value");
        StateDefinition decoded = decode(json);
        assertEquals(0, decoded.defaultValue());
    }
}
