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
 * <p>Instances are lightweight value views and may be created freely via
 * {@link #of}. The view itself is not cached across calls. A read-only view
 * (see {@link #of(CompoundTag, RegistryAccess)}) does, however, lazily cache
 * the single synthesised {@link GunData} it derives from the wrapped tag, so
 * repeated {@link #currentGunData()} calls on the <em>same</em> view reuse one
 * immutable instance (零分配). The wrapped tag reference is fixed for the
 * view's lifetime — see {@link #currentGunData()} for the stale-reference
 * caveat.</p>
 *
 * @see StateRegistry
 * @see GunData
 */
public final class GunState {
    private final @Nullable ItemStack gunStack;
    private final @Nullable CompoundTag stateOverride;
    private final RegistryAccess registryAccess;

    /**
     * Lazily-cached {@link GunData} synthesised from {@link #stateOverride} in
     * read-only mode.
     *
     * <p>In the read-only view ({@link #of(CompoundTag, RegistryAccess)}) the
     * synthesised {@link GunData} is immutable (it only wraps the same state
     * tag), so it can be created once and reused on every access. Caching it
     * turns {@link #currentGunData()} into a zero-allocation getter instead of
     * allocating a fresh record per call (审查优化 P: 只读视图不重复分配).</p>
     *
     * <p><strong>防御性说明</strong> — the {@link GunState} read-only view is
     * immutable: {@code stateOverride} is {@code final} and never reassigned,
     * so this cache can never go stale <em>within a single view</em>. It only
     * wraps the exact tag captured at construction. If the backing store's tag
     * is replaced, the caller must build a new view (see
     * {@link #of(CompoundTag, RegistryAccess)}) — the cache intentionally holds
     * the original reference and is <em>not</em> invalidated, because the view
     * has no knowledge of the store.</p>
     */
    private @Nullable GunData cachedReadOnlyGunData;

    private GunState(ItemStack gunStack, RegistryAccess registryAccess) {
        this.gunStack = gunStack;
        this.stateOverride = null;
        this.registryAccess = registryAccess;
    }

    private GunState(CompoundTag stateOverride, RegistryAccess registryAccess) {
        this.gunStack = null;
        this.stateOverride = stateOverride;
        this.registryAccess = registryAccess;
        this.cachedReadOnlyGunData = null;
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
     *
     * <p><strong>固定引用语义</strong> — this view wraps the supplied
     * {@code state} tag <em>by reference</em> and holds it for the view's
     * lifetime (the view is immutable: {@code stateOverride} is final and never
     * reassigned). The lazily-cached {@link GunData} in
     * {@link #currentGunData()} therefore always wraps the exact tag passed in
     * here. If the caller swaps the underlying store to a new state tag (e.g. a
     * {@code ClientGunDataStore} replacement), it must <em>rebuild the
     * view</em> via this factory — reusing the old view would silently read the
     * original (stale) tag.</p>
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
        // 键不存在 → 无变化，跳过拷贝与组件 set（审查优化 P2 同款短路）。
        if (!gunData.state().contains(stateId.toString())) {
            return;
        }
        final GunData newData = gunData.clearStateValue(stateId);
        gunStack.set(ModularShootDataComponents.GUN_DATA.get(), newData);
        // Flag the gun for a throttled state sync, mirroring setTypedValue
        // (审查 R8): without this the client keeps the stale cleared value
        // until the next critical-moment sync.
        final UUID gunInstanceUuid = gunData.gunInstanceUuid();
        if (gunInstanceUuid != null) {
            GunSyncThrottleManager.getInstance().markDirty(gunInstanceUuid);
        }
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
        // 值未变化 → 跳过整次写路径（NBT 深拷贝 + 组件 set + 原版整栈同步 +
        // 节流标记）。高频写入（heat 累积等）若每 tick 写相同值，此检查将其
        // 降为零成本；值确实变化时才走完整写路径（审查优化 P2）。
        final Object previous = gunData.getStateValue(stateId, registryAccess);
        if (Objects.equals(previous, value)) {
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
     * <p><strong>固定 tag 引用</strong> — for a read-only view this method
     * always returns a {@link GunData} wrapping the <em>exact</em> tag passed
     * to {@link #of(CompoundTag, RegistryAccess)} at construction; the view
     * holds that reference for its lifetime. Across a store replacement the
     * caller must rebuild the view — reusing the old view returns the original
     * (stale) tag (3.1 审查 Medium 2).</p>
     *
     * @return the current {@link GunData}, or {@code null}
     */
    @Nullable
    GunData currentGunData() {
        if (stateOverride != null) {
            // 防御性：stateOverride 为 final（视图不可变），缓存只会包裹构造时
            // 传入的 tag；跨 store 替换须重建视图（见 of(CompoundTag, ...)）。
            if (cachedReadOnlyGunData == null) {
                cachedReadOnlyGunData = new GunData(null, null, List.of(), 0, stateOverride);
            }
            return cachedReadOnlyGunData;
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
