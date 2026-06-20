package org.yanbwe.modularshoot.state;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Type-safe read/write view over a {@link Player}'s per-player state payload.
 *
 * <p>This is the per-player counterpart to {@code GunState}: the two share an
 * identical method surface (设计文档 §读写 API — PlayerState 签名同 GunState),
 * differing only in the owning object ({@link Player} vs an
 * {@link net.minecraft.world.item.ItemStack}) and the ownership domain
 * enforced on lookup ({@link StateDomain#PLAYER} vs {@link StateDomain#GUN}).
 *
 * <p>State is persisted on the player through the
 * {@link ModularShootAttachmentTypes#PLAYER_STATE} NeoForge
 * {@link net.neoforged.neoforge.attachment.AttachmentType}, which wraps a
 * {@link PlayerStateData}. Reads delegate to
 * {@link PlayerStateData#getStateValue} and writes produce a new immutable
 * {@link PlayerStateData} via {@link PlayerStateData#withStateValue} that is
 * stored back with {@link Player#setData}. Per NeoForge {@code getData}
 * semantics the attachment is created on first access, so the payload is
 * always present (unlike the optional {@code GunData} component on a gun
 * stack).</p>
 *
 * <p><strong>Error handling</strong> — every accessor degrades gracefully
 * and never throws (设计文档 §错误处理):
 * <ul>
 *   <li>Unregistered state id → returns the type's zero value (get) or
 *       skips the write (set), plus one rate-limited {@code WARN} via
 *       {@link StateWarnLogger#warnUnregistered}.</li>
 *   <li>Registered with a non-{@link StateDomain#PLAYER} domain → zero value /
 *       skipped write, plus {@link StateWarnLogger#warnDomainMismatch}.</li>
 *   <li>Registered with a value type that does not match the accessor (e.g.
 *       registered as {@code int} but read via {@link #getDouble}) → zero
 *       value / skipped write, plus
 *       {@link StateWarnLogger#warnTypeMismatch}.</li>
 * </ul>
 * </p>
 *
 * <p>The zero values are: {@code int→0}, {@code long→0L},
 * {@code double→0.0}, {@code float→0.0f}, {@code boolean→false},
 * {@code String→""}, {@code UUID→null}.</p>
 *
 * <p>Instances are lightweight value views; they are not cached and may be
 * created freely via {@link #of}.</p>
 *
 * @see StateRegistry
 * @see PlayerStateData
 * @see ModularShootAttachmentTypes#PLAYER_STATE
 */
public final class PlayerState {
    private final Player player;
    private final RegistryAccess registryAccess;

    private PlayerState(Player player, RegistryAccess registryAccess) {
        this.player = player;
        this.registryAccess = registryAccess;
    }

    /**
     * Creates a {@link PlayerState} view over the given player.
     *
     * @param player         the player whose state is read/written
     * @param registryAccess the runtime registry view (from a loaded world,
     *                       e.g. {@code level.registryAccess()})
     * @return a new {@link PlayerState}
     */
    public static PlayerState of(Player player, RegistryAccess registryAccess) {
        return new PlayerState(player, registryAccess);
    }

    // ------------------------------------------------------------------
    // Typed get accessors
    // ------------------------------------------------------------------

    /**
     * Reads an {@code int}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0} on any access error
     */
    public int getInt(ResourceLocation stateId) {
        final Integer value = getTypedValue(stateId, StateValueType.INT, Integer.class);
        return value != null ? value : 0;
    }

    /**
     * Reads a {@code long}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0L} on any access error
     */
    public long getLong(ResourceLocation stateId) {
        final Long value = getTypedValue(stateId, StateValueType.LONG, Long.class);
        return value != null ? value : 0L;
    }

    /**
     * Reads a {@code double}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0.0} on any access error
     */
    public double getDouble(ResourceLocation stateId) {
        final Double value = getTypedValue(stateId, StateValueType.DOUBLE, Double.class);
        return value != null ? value : 0.0;
    }

    /**
     * Reads a {@code float}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0.0f} on any access error
     */
    public float getFloat(ResourceLocation stateId) {
        final Float value = getTypedValue(stateId, StateValueType.FLOAT, Float.class);
        return value != null ? value : 0.0f;
    }

    /**
     * Reads a {@code boolean}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code false} on any access error
     */
    public boolean getBoolean(ResourceLocation stateId) {
        final Boolean value = getTypedValue(stateId, StateValueType.BOOLEAN, Boolean.class);
        return value != null ? value : false;
    }

    /**
     * Reads a {@code String}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code ""} on any access error
     */
    public String getString(ResourceLocation stateId) {
        final String value = getTypedValue(stateId, StateValueType.STRING, String.class);
        return value != null ? value : "";
    }

    /**
     * Reads a {@code UUID}-typed per-player state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code null} on any access error or when
     *         the state holds the UUID zero value
     */
    @Nullable
    public UUID getUuid(ResourceLocation stateId) {
        return getTypedValue(stateId, StateValueType.UUID, UUID.class);
    }

    // ------------------------------------------------------------------
    // Typed set accessors
    // ------------------------------------------------------------------

    /**
     * Writes an {@code int}-typed per-player state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setInt(ResourceLocation stateId, int value) {
        setTypedValue(stateId, StateValueType.INT, value);
    }

    /**
     * Writes a {@code long}-typed per-player state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setLong(ResourceLocation stateId, long value) {
        setTypedValue(stateId, StateValueType.LONG, value);
    }

    /**
     * Writes a {@code double}-typed per-player state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setDouble(ResourceLocation stateId, double value) {
        setTypedValue(stateId, StateValueType.DOUBLE, value);
    }

    /**
     * Writes a {@code float}-typed per-player state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setFloat(ResourceLocation stateId, float value) {
        setTypedValue(stateId, StateValueType.FLOAT, value);
    }

    /**
     * Writes a {@code boolean}-typed per-player state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setBoolean(ResourceLocation stateId, boolean value) {
        setTypedValue(stateId, StateValueType.BOOLEAN, value);
    }

    /**
     * Writes a {@code String}-typed per-player state. A {@code null} value is
     * treated as the zero value {@code ""} so that no exception escapes.
     *
     * @param stateId the state id to write
     * @param value   the value to write; {@code null} is coerced to {@code ""}
     */
    public void setString(ResourceLocation stateId, @Nullable String value) {
        setTypedValue(stateId, StateValueType.STRING, value == null ? "" : value);
    }

    /**
     * Writes a {@code UUID}-typed per-player state. {@code null} is the valid
     * UUID zero value and clears any stored reference.
     *
     * @param stateId the state id to write
     * @param value   the value to write; {@code null} is allowed
     */
    public void setUuid(ResourceLocation stateId, @Nullable UUID value) {
        setTypedValue(stateId, StateValueType.UUID, value);
    }

    // ------------------------------------------------------------------
    // Generic accessors
    // ------------------------------------------------------------------

    /**
     * Checks whether a state id has a non-default value stored on this player.
     *
     * <p>Returns {@code false} when the id is unregistered, belongs to a
     * non-{@link StateDomain#PLAYER} domain, or the stored value equals the
     * registry's {@link StateDefinition#defaultValue()}.</p>
     *
     * @param stateId the state id to test
     * @return {@code true} if a value distinct from the default is stored
     */
    public boolean hasState(ResourceLocation stateId) {
        final Optional<StateDefinition> opt = StateRegistry.getState(registryAccess, stateId);
        if (opt.isEmpty()) {
            return false;
        }
        final StateDefinition def = opt.get();
        if (def.domain() != StateDomain.PLAYER) {
            return false;
        }
        final Object value = currentPlayerData().getStateValue(stateId, registryAccess);
        return !Objects.equals(value, def.defaultValue());
    }

    /**
     * Removes a state key from this player, restoring it to its default value
     * on the next read.
     *
     * <p>No registry validation is performed because removal does not need
     * to know the value type. The operation is always safe and never
     * throws.</p>
     *
     * @param stateId the state id to remove
     */
    public void clearState(ResourceLocation stateId) {
        final PlayerStateData newData = currentPlayerData().clearStateValue(stateId);
        player.setData(ModularShootAttachmentTypes.PLAYER_STATE.get(), newData);
    }

    // ------------------------------------------------------------------
    // Shared internals
    // ------------------------------------------------------------------

    /**
     * Validates that {@code stateId} is registered, belongs to the
     * {@link StateDomain#PLAYER} domain, and is declared with the requested
     * {@link StateValueType}.
     *
     * <p>On any mismatch a single rate-limited {@code WARN} is emitted via
     * {@link StateWarnLogger} and {@code null} is returned so the caller can
     * short-circuit to a zero value or skipped write.</p>
     *
     * @param stateId       the state id to validate
     * @param requestedType the {@link StateValueType} the accessor expects
     * @return the matching {@link StateDefinition}, or {@code null} on any
     *         validation failure
     */
    @Nullable
    private StateDefinition validateState(ResourceLocation stateId, StateValueType requestedType) {
        final Optional<StateDefinition> opt = StateRegistry.getState(registryAccess, stateId);
        if (opt.isEmpty()) {
            StateWarnLogger.warnUnregistered(stateId);
            return null;
        }
        final StateDefinition def = opt.get();
        if (def.domain() != StateDomain.PLAYER) {
            StateWarnLogger.warnDomainMismatch(stateId, StateDomain.PLAYER, def.domain());
            return null;
        }
        if (def.valueType() != requestedType) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), javaClassOf(requestedType));
            return null;
        }
        return def;
    }

    /**
     * Reads, type-checks, and casts a state value.
     *
     * <p>Validation failures yield {@code null} so each typed getter can
     * substitute its own zero value. A defensive runtime type check guards
     * against inconsistent stored data.</p>
     *
     * @param stateId       the state id to read
     * @param requestedType the declared type the accessor expects
     * @param valueClass    the Java class to cast the decoded value to
     * @param <T>           the Java value type
     * @return the decoded and cast value, or {@code null} on any failure
     *         (including the UUID zero value)
     */
    @Nullable
    private <T> T getTypedValue(ResourceLocation stateId, StateValueType requestedType, Class<T> valueClass) {
        final StateDefinition def = validateState(stateId, requestedType);
        if (def == null) {
            return null;
        }
        final Object raw = currentPlayerData().getStateValue(stateId, registryAccess);
        if (raw == null) {
            return null; // valid UUID zero value; other types never decode to null
        }
        if (!valueClass.isInstance(raw)) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), raw.getClass());
            return null;
        }
        return valueClass.cast(raw);
    }

    /**
     * Validates and writes a state value, persisting the updated
     * {@link PlayerStateData} back onto the player.
     *
     * <p>Validation failures skip the write. The
     * {@link PlayerStateData#withStateValue} call is guarded so that no
     * {@link IllegalArgumentException} can escape even on an unexpected
     * type mismatch.</p>
     *
     * @param stateId       the state id to write
     * @param requestedType the declared type the accessor expects
     * @param value         the value to write; {@code null} is only valid for UUID
     */
    private void setTypedValue(ResourceLocation stateId, StateValueType requestedType, @Nullable Object value) {
        final StateDefinition def = validateState(stateId, requestedType);
        if (def == null) {
            return;
        }
        try {
            final PlayerStateData newData = currentPlayerData().withStateValue(stateId, value, registryAccess);
            player.setData(ModularShootAttachmentTypes.PLAYER_STATE.get(), newData);
        } catch (IllegalArgumentException ex) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), value == null ? null : value.getClass());
        }
    }

    /**
     * Returns the {@link PlayerStateData} currently attached to the wrapped
     * player.
     *
     * <p>Per NeoForge {@code getData} semantics the attachment is created on
     * first access, so this never returns {@code null} (unlike the optional
     * {@code GunData} component on a gun stack).</p>
     *
     * @return the current per-player state payload (never {@code null})
     */
    private PlayerStateData currentPlayerData() {
        return player.getData(ModularShootAttachmentTypes.PLAYER_STATE.get());
    }

    /**
     * Maps a {@link StateValueType} to its corresponding Java wrapper class,
     * used to produce meaningful type-mismatch warnings.
     *
     * @param type the declared value type
     * @return the matching Java class
     */
    private static Class<?> javaClassOf(StateValueType type) {
        return switch (type) {
            case INT -> Integer.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
            case FLOAT -> Float.class;
            case BOOLEAN -> Boolean.class;
            case STRING -> String.class;
            case UUID -> UUID.class;
        };
    }
}
