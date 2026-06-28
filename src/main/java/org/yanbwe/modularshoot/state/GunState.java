package org.yanbwe.modularshoot.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;

/**
 * Type-safe read/write view over the per-gun state payload stored on a
 * {@code modularshoot:gun} {@link ItemStack}.
 *
 * <p>Wraps the gun stack together with the {@link RegistryAccess} needed to
 * resolve the {@code modularshoot:states} dynamic registry, and exposes 14
 * typed get/set accessors (one pair per {@link StateValueType}) plus
 * {@link #hasState} and {@link #clearState}. All accessors are keyed by
 * state id ({@link ResourceLocation}).</p>
 *
 * <p><strong>Error handling</strong> — every accessor degrades gracefully
 * and never throws (设计文档 §错误处理):
 * <ul>
 *   <li>Unregistered state id → returns the type's zero value (get) or
 *       skips the write (set), plus one rate-limited {@code WARN} via
 *       {@link StateWarnLogger#warnUnregistered}.</li>
 *   <li>Registered with a non-{@link StateDomain#GUN} domain → zero value /
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
 * @see GunData
 */
public final class GunState {
    private final @Nullable ItemStack gunStack;
    private final @Nullable CompoundTag stateOverride;
    private final RegistryAccess registryAccess;

    private GunState(ItemStack gunStack, RegistryAccess registryAccess) {
        this.gunStack = gunStack;
        this.stateOverride = null;
        this.registryAccess = registryAccess;
    }

    private GunState(CompoundTag stateOverride, RegistryAccess registryAccess) {
        this.gunStack = null;
        this.stateOverride = stateOverride;
        this.registryAccess = registryAccess;
    }

    /**
     * Creates a {@link GunState} view over the given gun stack, deriving the
     * {@link RegistryAccess} from the supplied player (设计文档 §读写 API).
     *
     * @param gunStack the {@code modularshoot:gun} item stack to read/write
     * @param player   the player context used to resolve the runtime registry
     * @return a new {@link GunState}
     */
    public static GunState of(ItemStack gunStack, Player player) {
        Objects.requireNonNull(player, "player");
        return new GunState(gunStack, player.registryAccess());
    }

    /**
     * Creates a {@link GunState} view over the given gun stack, deriving the
     * {@link RegistryAccess} from the supplied level (设计文档 §读写 API).
     *
     * @param gunStack the {@code modularshoot:gun} item stack to read/write
     * @param level    the level used to resolve the runtime registry
     * @return a new {@link GunState}
     */
    public static GunState of(ItemStack gunStack, Level level) {
        Objects.requireNonNull(level, "level");
        return new GunState(gunStack, level.registryAccess());
    }

    /**
     * Creates a {@link GunState} view over the given gun stack using an
     * explicit {@link RegistryAccess}.
     *
     * <p>Package-private: external callers should prefer
     * {@link #of(ItemStack, Player)} or {@link #of(ItemStack, Level)} which
     * derive the registry view from a readily available context (设计文档
     * §读写 API — 无 RegistryAccess 参数). This overload is retained for
     * internal state-package classes that already hold a
     * {@link RegistryAccess}.</p>
     *
     * @param gunStack       the {@code modularshoot:gun} item stack to read/write
     * @param registryAccess the runtime registry view
     * @return a new {@link GunState}
     */
    static GunState of(ItemStack gunStack, RegistryAccess registryAccess) {
        return new GunState(gunStack, registryAccess);
    }

    /**
     * Creates a read-only {@link GunState} view over a standalone state
     * compound tag, decoupled from any item stack.
     *
     * <p>Used by client-side consumers that read the authoritative server
     * state from
     * {@link org.yanbwe.modularshoot.client.ClientGunDataStore#getState()}
     * rather than the local {@code GunData} component. All mutator
     * operations ({@link #setInt}, {@link #clearState}, etc.) are silent
     * no-ops on this view because there is no backing stack to write to.</p>
     *
     * @param state          the per-gun state compound tag to read from
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return a new read-only {@link GunState}
     */
    public static GunState of(CompoundTag state, RegistryAccess registryAccess) {
        return new GunState(state, registryAccess);
    }

    // ------------------------------------------------------------------
    // Typed get accessors
    // ------------------------------------------------------------------

    /**
     * Reads an {@code int}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0} on any access error
     */
    public int getInt(ResourceLocation stateId) {
        final Integer value = getTypedValue(stateId, StateValueType.INT, Integer.class);
        return value != null ? value : 0;
    }

    /**
     * Reads a {@code long}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0L} on any access error
     */
    public long getLong(ResourceLocation stateId) {
        final Long value = getTypedValue(stateId, StateValueType.LONG, Long.class);
        return value != null ? value : 0L;
    }

    /**
     * Reads a {@code double}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0.0} on any access error
     */
    public double getDouble(ResourceLocation stateId) {
        final Double value = getTypedValue(stateId, StateValueType.DOUBLE, Double.class);
        return value != null ? value : 0.0;
    }

    /**
     * Reads a {@code float}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code 0.0f} on any access error
     */
    public float getFloat(ResourceLocation stateId) {
        final Float value = getTypedValue(stateId, StateValueType.FLOAT, Float.class);
        return value != null ? value : 0.0f;
    }

    /**
     * Reads a {@code boolean}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code false} on any access error
     */
    public boolean getBoolean(ResourceLocation stateId) {
        final Boolean value = getTypedValue(stateId, StateValueType.BOOLEAN, Boolean.class);
        return value != null ? value : false;
    }

    /**
     * Reads a {@code String}-typed state.
     *
     * @param stateId the state id to read
     * @return the stored value, or {@code ""} on any access error
     */
    public String getString(ResourceLocation stateId) {
        final String value = getTypedValue(stateId, StateValueType.STRING, String.class);
        return value != null ? value : "";
    }

    /**
     * Reads a {@code UUID}-typed state.
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
     * Writes an {@code int}-typed state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setInt(ResourceLocation stateId, int value) {
        setTypedValue(stateId, StateValueType.INT, value);
    }

    /**
     * Writes a {@code long}-typed state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setLong(ResourceLocation stateId, long value) {
        setTypedValue(stateId, StateValueType.LONG, value);
    }

    /**
     * Writes a {@code double}-typed state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setDouble(ResourceLocation stateId, double value) {
        setTypedValue(stateId, StateValueType.DOUBLE, value);
    }

    /**
     * Writes a {@code float}-typed state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setFloat(ResourceLocation stateId, float value) {
        setTypedValue(stateId, StateValueType.FLOAT, value);
    }

    /**
     * Writes a {@code boolean}-typed state.
     *
     * @param stateId the state id to write
     * @param value   the value to write
     */
    public void setBoolean(ResourceLocation stateId, boolean value) {
        setTypedValue(stateId, StateValueType.BOOLEAN, value);
    }

    /**
     * Writes a {@code String}-typed state. A {@code null} value is treated
     * as the zero value {@code ""} so that no exception escapes.
     *
     * @param stateId the state id to write
     * @param value   the value to write; {@code null} is coerced to {@code ""}
     */
    public void setString(ResourceLocation stateId, @Nullable String value) {
        setTypedValue(stateId, StateValueType.STRING, value == null ? "" : value);
    }

    /**
     * Writes a {@code UUID}-typed state. {@code null} is the valid UUID zero
     * value and clears any stored reference.
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
     * Checks whether a state id has a non-default value stored on this gun.
     *
     * <p>Returns {@code false} when the id is unregistered, belongs to a
     * non-{@link StateDomain#GUN} domain, the stack carries no
     * {@link GunData}, or the stored value equals the registry's
     * {@link StateDefinition#defaultValue()}.</p>
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
        if (def.domain() != StateDomain.GUN) {
            return false;
        }
        final GunData gunData = currentGunData();
        if (gunData == null) {
            return false;
        }
        final Object value = gunData.getStateValue(stateId, registryAccess);
        return !Objects.equals(value, def.defaultValue());
    }

    /**
     * Removes a state key from this gun, restoring it to its default value
     * on the next read.
     *
     * <p>No registry validation is performed because removal does not need
     * to know the value type. When the stack carries no {@link GunData} or
     * the key is absent, this is a no-op.</p>
     *
     * @param stateId the state id to remove
     */
    public void clearState(ResourceLocation stateId) {
        if (stateOverride != null) {
            // Read-only view (from ClientGunDataStore): writes are not supported.
            return;
        }
        final GunData gunData = currentGunData();
        if (gunData == null) {
            return;
        }
        final GunData newData = gunData.clearStateValue(stateId);
        gunStack.set(ModularShootDataComponents.GUN_DATA.get(), newData);
    }

    // ------------------------------------------------------------------
    // Shared internals
    // ------------------------------------------------------------------

    /**
     * Validates that {@code stateId} is registered, belongs to the
     * {@link StateDomain#GUN} domain, and is declared with the requested
     * {@link StateValueType}.
     *
     * <p>On any mismatch a single rate-limited {@code WARN} is emitted via
     * {@link StateWarnLogger} and {@code null} is returned so the caller can
     * short-circuit to a zero value or skipped write.</p>
     *
     * @param stateId      the state id to validate
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
        if (def.domain() != StateDomain.GUN) {
            StateWarnLogger.warnDomainMismatch(stateId, StateDomain.GUN, def.domain());
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
     * <p>Validation failures and missing {@link GunData} yield {@code null}
     * so each typed getter can substitute its own zero value. A defensive
     * runtime type check guards against inconsistent stored data.</p>
     *
     * @param stateId      the state id to read
     * @param requestedType the declared type the accessor expects
     * @param valueClass   the Java class to cast the decoded value to
     * @param <T>          the Java value type
     * @return the decoded and cast value, or {@code null} on any failure
     *         (including the UUID zero value)
     */
    @Nullable
    private <T> T getTypedValue(ResourceLocation stateId, StateValueType requestedType, Class<T> valueClass) {
        final StateDefinition def = validateState(stateId, requestedType);
        if (def == null) {
            return null;
        }
        final GunData gunData = currentGunData();
        if (gunData == null) {
            return null;
        }
        final Object raw = gunData.getStateValue(stateId, registryAccess);
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
     * {@link GunData} back onto the stack.
     *
     * <p>Validation failures and missing {@link GunData} skip the write. The
     * {@link GunData#withStateValue} call is guarded so that no
     * {@link IllegalArgumentException} can escape even on an unexpected
     * type mismatch. The post-write throttle flag is raised outside the
     * guard and is null-checked against {@code gunInstanceUuid} so a
     * malformed stack never causes an {@link NullPointerException} to
     * escape the accessor (W27 fix).</p>
     *
     * @param stateId      the state id to write
     * @param requestedType the declared type the accessor expects
     * @param value        the value to write; {@code null} is only valid for UUID
     */
    private void setTypedValue(ResourceLocation stateId, StateValueType requestedType, @Nullable Object value) {
        if (stateOverride != null) {
            // Read-only view (from ClientGunDataStore): writes are not supported.
            return;
        }
        final StateDefinition def = validateState(stateId, requestedType);
        if (def == null) {
            return;
        }
        final GunData gunData = currentGunData();
        if (gunData == null) {
            return;
        }
        try {
            final GunData newData = gunData.withStateValue(stateId, value, registryAccess);
            gunStack.set(ModularShootDataComponents.GUN_DATA.get(), newData);
        } catch (IllegalArgumentException ex) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), value == null ? null : value.getClass());
            return;
        }
        // Flag the gun for a throttled state sync (设计文档 §同步节流策略).
        // The next GunSyncTickHandler tick flushes the change to the client,
        // subject to the 2-tick throttle interval. Critical-moment syncs
        // (main-hand switch, plugin install/uninstall, login) bypass this
        // throttle and are handled directly by GunSyncService.
        // Guarded against null gunInstanceUuid so a malformed stack never
        // causes an NPE to escape the accessor (W27 fix).
        final UUID gunInstanceUuid = gunData.gunInstanceUuid();
        if (gunInstanceUuid != null) {
            GunSyncThrottleManager.getInstance().markDirty(gunInstanceUuid);
        }
    }

    /**
     * Returns the {@link GunData} currently stored on the wrapped stack, or
     * {@code null} when the stack carries no gun data component.
     *
     * <p>In read-only mode (created via {@link #of(CompoundTag, RegistryAccess)})
     * a lightweight {@link GunData} is synthesised from the
     * {@code stateOverride} tag so that all read accessors work unchanged.
     * The synthesised data carries {@code null} {@code gunId}/
     * {@code gunInstanceUuid} and an empty plugin list, which is safe because
     * state-value reads only touch the {@code state} tag.</p>
     *
     * @return the current {@link GunData}, or {@code null}
     */
    @Nullable
    private GunData currentGunData() {
        if (stateOverride != null) {
            return new GunData(null, null, List.of(), 0, stateOverride);
        }
        return gunStack.get(ModularShootDataComponents.GUN_DATA.get());
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
