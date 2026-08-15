package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for 阶段 3 / 任务 3.2 — StateValueCodecs 快速 NBT 分支.
 *
 * <p>These tests drive the new strong-typed fast branch API
 * ({@code StateValueCodecs#encodeEntryFast}/{@code StateValueCodecs#decodeEntryFast}):
 * <ul>
 *   <li><b>快速编解码往返</b> — INT/LONG/DOUBLE/FLOAT/BOOLEAN/STRING/UUID
 *       written directly into an entry {@link CompoundTag} via the fast
 *       encoder must round-trip through the fast decoder preserving value and
 *       runtime type, including the {@code null} UUID zero value.</li>
 *   <li><b>与 codec 路径一致</b> — the entry produced by the fast encoder
 *       must be exactly equal to the entry produced by the existing
 *       {@link StateValueCodecs#encodeValue} codec path, so the wire/disk
 *       {@code {type, value}} format is unchanged; fast decode must agree
 *       with codec decode.</li>
 *   <li><b>未知/不支持类型回退 codec</b> — the public codec path
 *       ({@link StateValueCodecs#encodeValue}/{@link StateValueCodecs#decodeValue})
 *       remains the authoritative fallback and must still round-trip every
 *       supported type.</li>
 *   <li><b>类型防护</b> — the fast encoder rejects values whose runtime type
 *       does not match the declared type, and the fast decoder rejects entries
 *       whose stored tag type does not match the declared type, matching the
 *       codec path contract.</li>
 * </ul>
 * </p>
 */
class StateFastCodecTest {

    private static final ResourceLocation INT_STATE =
            ResourceLocation.parse("modularshoot:test_heat");
    private static final ResourceLocation LONG_STATE =
            ResourceLocation.parse("modularshoot:test_counter");
    private static final ResourceLocation DOUBLE_STATE =
            ResourceLocation.parse("modularshoot:test_charge");
    private static final ResourceLocation FLOAT_STATE =
            ResourceLocation.parse("modularshoot:test_progress");
    private static final ResourceLocation BOOLEAN_STATE =
            ResourceLocation.parse("modularshoot:test_flag");
    private static final ResourceLocation STRING_STATE =
            ResourceLocation.parse("modularshoot:test_mode");
    private static final ResourceLocation UUID_STATE =
            ResourceLocation.parse("modularshoot:test_target");

    /** A registry access that registers all seven supported value types. */
    private static final RegistryAccess REGISTRY = buildStateRegistry();

    private static RegistryAccess buildStateRegistry() {
        Registry<StateDefinition> states = new MappedRegistry<>(
                ModularShootRegistries.STATES_KEY, Lifecycle.stable());
        register(states, INT_STATE, StateValueType.INT);
        register(states, LONG_STATE, StateValueType.LONG);
        register(states, DOUBLE_STATE, StateValueType.DOUBLE);
        register(states, FLOAT_STATE, StateValueType.FLOAT);
        register(states, BOOLEAN_STATE, StateValueType.BOOLEAN);
        register(states, STRING_STATE, StateValueType.STRING);
        register(states, UUID_STATE, StateValueType.UUID);
        return new RegistryAccess.ImmutableRegistryAccess(List.of(states));
    }

    private static void register(
            Registry<StateDefinition> states, ResourceLocation id, StateValueType type) {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, type, type.zeroValue(),
                StateDisplay.of("test", Optional.of("#FFFFFF")), List.of());
        Registry.register(states, id, def);
    }

    // ------------------------------------------------------------------
    // 1. Fast-path round-trip through GunStateStorage (registration + wire-in)
    // ------------------------------------------------------------------

    @Test
    void roundTripIntViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), INT_STATE, 42, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, INT_STATE, REGISTRY);
        assertEquals(42, read);
        assertInstanceOf(Integer.class, read);
    }

    @Test
    void roundTripLongViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), LONG_STATE, 1234567890123L, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, LONG_STATE, REGISTRY);
        assertEquals(1234567890123L, read);
        assertInstanceOf(Long.class, read);
    }

    @Test
    void roundTripDoubleViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), DOUBLE_STATE, 3.141592653589793, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, DOUBLE_STATE, REGISTRY);
        assertEquals(3.141592653589793, read);
        assertInstanceOf(Double.class, read);
    }

    @Test
    void roundTripFloatViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), FLOAT_STATE, 2.5f, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, FLOAT_STATE, REGISTRY);
        assertEquals(2.5f, read);
        assertInstanceOf(Float.class, read);
    }

    @Test
    void roundTripBooleanViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), BOOLEAN_STATE, true, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, BOOLEAN_STATE, REGISTRY);
        assertEquals(Boolean.TRUE, read);
        assertInstanceOf(Boolean.class, read);
    }

    @Test
    void roundTripStringViaFastPath() {
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), STRING_STATE, "semi-auto", REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, STRING_STATE, REGISTRY);
        assertEquals("semi-auto", read);
        assertInstanceOf(String.class, read);
    }

    @Test
    void roundTripUuidViaFastPath() {
        UUID uuid = new UUID(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), UUID_STATE, uuid, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, UUID_STATE, REGISTRY);
        assertEquals(uuid, read);
        assertInstanceOf(UUID.class, read);
    }

    @Test
    void roundTripUuidNullZeroViaStorageOmitsValueField() {
        // UUID zero value is null: fast encode must omit the value field,
        // and fast decode must return null.
        CompoundTag tag = GunStateStorage.setStateValue(new CompoundTag(), UUID_STATE, null, REGISTRY);
        Object read = GunStateStorage.getStateValue(tag, UUID_STATE, REGISTRY);
        assertNull(read, "null UUID zero value must decode back to null");
        CompoundTag entry = tag.getCompound(UUID_STATE.toString());
        assertEquals(StateValueType.UUID.getSerializedName(), entry.getString("type"));
        assertFalse(entry.contains("value"),
                "null UUID fast encode must omit the value field");
    }

    // ------------------------------------------------------------------
    // 2. Direct fast API round-trip for each common type
    // ------------------------------------------------------------------

    @Test
    void fastEncoderDecoderRoundTripEveryCommonType() {
        assertFastRoundTrip(StateValueType.INT, 7);
        assertFastRoundTrip(StateValueType.LONG, 9876543210L);
        assertFastRoundTrip(StateValueType.DOUBLE, 2.718281828459045);
        assertFastRoundTrip(StateValueType.FLOAT, 1.25f);
        assertFastRoundTrip(StateValueType.BOOLEAN, false);
        assertFastRoundTrip(StateValueType.STRING, "burst");
        assertFastRoundTrip(StateValueType.UUID, new UUID(0L, 1L));
    }

    private static void assertFastRoundTrip(StateValueType type, Object value) {
        CompoundTag entry = new CompoundTag();
        StateValueCodecs.encodeEntryFast(entry, type, value);
        Object decoded = StateValueCodecs.decodeEntryFast(type, entry);
        assertEquals(value, decoded, "fast round-trip must preserve value for " + type);
    }

    @Test
    void fastUuidNullZeroOmitsValueField() {
        CompoundTag entry = new CompoundTag();
        StateValueCodecs.encodeEntryFast(entry, StateValueType.UUID, null);
        assertFalse(entry.contains("value"),
                "null UUID fast encode must omit the value field");
        assertNull(StateValueCodecs.decodeEntryFast(StateValueType.UUID, entry),
                "fast decode of omitted UUID value must return null");
    }

    // ------------------------------------------------------------------
    // 3. Consistency with the codec path (unchanged {type, value} format)
    // ------------------------------------------------------------------

    @Test
    void fastEncodeMatchesCodecEncodeForEveryType() {
        assertFastMatchesCodec(StateValueType.INT, 7);
        assertFastMatchesCodec(StateValueType.LONG, 9876543210L);
        assertFastMatchesCodec(StateValueType.DOUBLE, 2.718281828459045);
        assertFastMatchesCodec(StateValueType.FLOAT, 1.25f);
        assertFastMatchesCodec(StateValueType.BOOLEAN, false);
        assertFastMatchesCodec(StateValueType.STRING, "burst");
        assertFastMatchesCodec(StateValueType.UUID, new UUID(0L, 1L));
    }

    /** Asserts the fast encoder writes the exact same entry as the codec path. */
    private static void assertFastMatchesCodec(StateValueType type, Object value) {
        CompoundTag fastEntry = new CompoundTag();
        StateValueCodecs.encodeEntryFast(fastEntry, type, value);

        CompoundTag codecEntry = new CompoundTag();
        codecEntry.putString("type", type.getSerializedName());
        Tag encoded = StateValueCodecs.encodeValue(type, value, REGISTRY);
        if (encoded != null) {
            codecEntry.put("value", encoded);
        }

        assertEquals(codecEntry, fastEntry,
                "fast-path entry must be byte-identical to the codec-path entry for " + type);
    }

    @Test
    void fastDecodeMatchesCodecDecodeForEveryType() {
        assertFastDecodeMatchesCodec(StateValueType.INT, 99);
        assertFastDecodeMatchesCodec(StateValueType.LONG, 555L);
        assertFastDecodeMatchesCodec(StateValueType.DOUBLE, 6.25);
        assertFastDecodeMatchesCodec(StateValueType.FLOAT, 3.75f);
        assertFastDecodeMatchesCodec(StateValueType.BOOLEAN, true);
        assertFastDecodeMatchesCodec(StateValueType.STRING, "auto");
        assertFastDecodeMatchesCodec(StateValueType.UUID, new UUID(0x0FEDCBA987654321L, 0x123456789ABCDEF0L));
    }

    private static void assertFastDecodeMatchesCodec(StateValueType type, Object value) {
        // Build via fast encoder, decode via both fast and codec paths.
        CompoundTag entry = new CompoundTag();
        StateValueCodecs.encodeEntryFast(entry, type, value);

        Object fastRead = StateValueCodecs.decodeEntryFast(type, entry);
        Tag valueTag = entry.get("value");
        Object codecRead = valueTag == null
                ? type.zeroValue()
                : StateValueCodecs.decodeValue(type, valueTag, REGISTRY);

        assertEquals(codecRead, fastRead,
                "fast decode must agree with codec decode for " + type);
        assertEquals(value, fastRead, "fast decode must reconstruct the original value for " + type);
    }

    // ------------------------------------------------------------------
    // 4. Codec path remains the authoritative fallback
    // ------------------------------------------------------------------

    @Test
    void codecPathStillRoundTripsAllTypesAsFallback() {
        // The fast branch is an optimisation; the full codec path must keep
        // working for every type so any type without a fast branch (or any
        // future type) still serialises correctly.
        assertCodecRoundTrips(StateValueType.INT, 4);
        assertCodecRoundTrips(StateValueType.LONG, 8000000000L);
        assertCodecRoundTrips(StateValueType.DOUBLE, 1.23456789);
        assertCodecRoundTrips(StateValueType.FLOAT, 0.5f);
        assertCodecRoundTrips(StateValueType.BOOLEAN, true);
        assertCodecRoundTrips(StateValueType.STRING, "mode_b");
        assertCodecRoundTrips(StateValueType.UUID, new UUID(1L, 2L));
    }

    private static void assertCodecRoundTrips(StateValueType type, Object value) {
        Tag encoded = StateValueCodecs.encodeValue(type, value, REGISTRY);
        Object decoded = StateValueCodecs.decodeValue(type, encoded, REGISTRY);
        assertEquals(value, decoded,
                "codec path (fallback) must round-trip " + type);
    }

    @Test
    void fastUuidUsesSameIntArrayWireFormatAsCodec() {
        // putUUID/getUUID must produce the same NBT wire format as
        // UUIDUtil.CODEC's IntArrayTag, so legacy codec-written UUIDs remain
        // readable by the fast path and vice versa.
        UUID uuid = new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        CompoundTag entry = new CompoundTag();
        StateValueCodecs.encodeEntryFast(entry, StateValueType.UUID, uuid);
        Tag fastValue = entry.get("value");

        Tag codecValue = StateValueCodecs.encodeValue(StateValueType.UUID, uuid, REGISTRY);

        assertInstanceOf(IntArrayTag.class, fastValue);
        assertEquals(codecValue, fastValue,
                "fast UUID encoding must use the identical IntArrayTag wire format");
        assertTrue(entry.hasUUID("value"),
                "fast UUID entry must be readable via CompoundTag.hasUUID");
    }

    // ------------------------------------------------------------------
    // 5. Type-mismatch / corrupt value behaviour (fixed in review 3.2)
    // ------------------------------------------------------------------

    @Test
    void fastEncodeRejectsTypeMismatch() {
        CompoundTag entry = new CompoundTag();
        assertThrows(IllegalArgumentException.class,
                () -> StateValueCodecs.encodeEntryFast(entry, StateValueType.INT, "not-an-int"));
    }

    @Test
    void getStateValueAndDecodeStateMapAgreeOnTypeMismatch() {
        // Declared INT but the stored value is a StringTag: the fast single-key
        // path and the whole-map codec path must both throw IllegalStateException
        // instead of silently coercing to zero.
        CompoundTag stateTag = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("type", StateValueType.INT.getSerializedName());
        entry.putString("value", "corrupt");
        stateTag.put(INT_STATE.toString(), entry);

        assertThrows(IllegalStateException.class,
                () -> GunStateStorage.getStateValue(stateTag, INT_STATE, REGISTRY),
                "single-key fast read must throw on type mismatch");
        assertThrows(IllegalStateException.class,
                () -> StateValueCodecs.decodeStateMap(stateTag, REGISTRY),
                "whole-map codec path must also throw on type mismatch");
    }

    @Test
    void decodeEntryFastThrowsOnLongMismatch() {
        CompoundTag entry = new CompoundTag();
        entry.putString("value", "corrupt");
        assertThrows(IllegalStateException.class,
                () -> StateValueCodecs.decodeEntryFast(StateValueType.LONG, entry));
    }

    @Test
    void decodeEntryFastThrowsOnCorruptUuid() {
        // UUID must be an IntArrayTag of length 4: a wrong tag type or wrong
        // length must throw rather than silently return null / garbage.
        CompoundTag wrongType = new CompoundTag();
        wrongType.putString("value", "not-a-uuid");
        assertThrows(IllegalStateException.class,
                () -> StateValueCodecs.decodeEntryFast(StateValueType.UUID, wrongType));

        CompoundTag wrongLength = new CompoundTag();
        wrongLength.putIntArray("value", new int[]{1, 2, 3});
        assertThrows(IllegalStateException.class,
                () -> StateValueCodecs.decodeEntryFast(StateValueType.UUID, wrongLength));
    }

    @Test
    void missingValueFieldReturnsZeroForEveryNonNullZeroType() {
        // When the "value" field is absent every non-UUID type must return its
        // zero value (the same behaviour the codec path guarantees).
        CompoundTag entry = new CompoundTag();
        entry.putString("type", StateValueType.INT.getSerializedName());

        assertEquals(StateValueType.INT.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.INT, entry));
        assertEquals(StateValueType.LONG.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.LONG, entry));
        assertEquals(StateValueType.DOUBLE.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.DOUBLE, entry));
        assertEquals(StateValueType.FLOAT.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.FLOAT, entry));
        assertEquals(StateValueType.BOOLEAN.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.BOOLEAN, entry));
        assertEquals(StateValueType.STRING.zeroValue(),
                StateValueCodecs.decodeEntryFast(StateValueType.STRING, entry));
        assertNull(StateValueCodecs.decodeEntryFast(StateValueType.UUID, entry));
    }

    @Test
    void missingValueFieldReturnsZeroThroughStorage() {
        // Verified through the public single-key read path, too.
        CompoundTag stateTag = new CompoundTag();
        CompoundTag intEntry = new CompoundTag();
        intEntry.putString("type", StateValueType.INT.getSerializedName());
        stateTag.put(INT_STATE.toString(), intEntry);

        assertEquals(0, GunStateStorage.getStateValue(stateTag, INT_STATE, REGISTRY));

        CompoundTag boolEntry = new CompoundTag();
        boolEntry.putString("type", StateValueType.BOOLEAN.getSerializedName());
        stateTag.put(BOOLEAN_STATE.toString(), boolEntry);

        assertEquals(Boolean.FALSE, GunStateStorage.getStateValue(stateTag, BOOLEAN_STATE, REGISTRY));
    }
}
