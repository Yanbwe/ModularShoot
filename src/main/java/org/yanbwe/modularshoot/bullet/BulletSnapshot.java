package org.yanbwe.modularshoot.bullet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateRegistry;
import org.yanbwe.modularshoot.state.StateValueType;
import org.yanbwe.modularshoot.state.StateValueCodecs;
import org.yanbwe.modularshoot.state.StateWarnLogger;

/**
 * Runtime-mutable snapshot of a bullet's state, frozen at firing time.
 *
 * <p>Captures the final attribute values, activated boolean traits, damage
 * type and shooter identity at the moment the bullet is created. The
 * {@code stats}, {@code traits} and {@code damageType} fields are
 * intentionally mutable so that trait runtime hooks ({@code onTick},
 * {@code onHit}, etc.) can modify them in-flight (设计文档 §子弹快照).</p>
 *
 * <p>The shooter, gun id and gun instance uuid are read-only and set only at
 * construction. For independent firing via
 * {@link BulletManager#fireBullet}, {@code gunId} and {@code gunInstanceUuid}
 * are always {@code null}; {@code shooter} depends on the caller-supplied
 * uuid (设计文档 §独立发射的快照字段约定).</p>
 *
 * <p><b>Per-bullet {@code state} (设计文档 §三层归属 / §持久化与同步):</b>
 * the {@code state} map is per-bullet <em>working memory</em>, distinct from
 * {@code stats}/{@code traits}. It does <em>not</em> participate in the
 * initial attribute-snapshot freezing flow — {@code stats} and
 * {@code traits} are "parameters frozen at firing time", while
 * {@code state} is "working variables that may evolve freely over the
 * bullet's lifetime". Hooks may read/write it via
 * {@link #getState} / {@link #setState} in any callback
 * ({@code onTick}, {@code onHit}, etc.). Bullets are never persisted, so
 * {@code state} is never saved to disk; its initial values are carried to
 * clients via {@code BulletS2CPacket} (see {@link #encodeState} /
 * {@link #decodeState}), and in-flight mutations are the hook's own
 * responsibility (typically surfaced through {@code onVisualTick}).</p>
 */
public final class BulletSnapshot {

    private final Map<ResourceLocation, Double> stats;
    private final Map<ResourceLocation, Boolean> traits;
    private Holder<DamageType> damageType;
    private final UUID shooter;
    private final ResourceLocation gunId;
    private final UUID gunInstanceUuid;
    /**
     * Per-bullet working-memory map (state id → value). Mutable over the
     * bullet's lifetime; hooks modify it via {@link #setState}. Not part of
     * the attribute-snapshot freezing flow and never persisted (设计文档
     * §三层归属 per-bullet).
     */
    private final Map<ResourceLocation, Object> state;

    /**
     * @param stats           attribute id → final value (copied defensively)
     * @param traits          trait id → activated flag (copied defensively)
     * @param damageType      damage type holder used for hurt() calls
     * @param shooter         shooter uuid, or {@code null} for independent firing
     * @param gunId           gun definition id, or {@code null} for independent firing
     * @param gunInstanceUuid gun instance uuid, or {@code null} for independent firing
     * @param state           per-bullet runtime state map (copied defensively)
     */
    public BulletSnapshot(
            Map<ResourceLocation, Double> stats,
            Map<ResourceLocation, Boolean> traits,
            Holder<DamageType> damageType,
            @Nullable UUID shooter,
            @Nullable ResourceLocation gunId,
            @Nullable UUID gunInstanceUuid,
            Map<ResourceLocation, Object> state) {
        this.stats = new HashMap<>(stats);
        this.traits = new HashMap<>(traits);
        this.damageType = damageType;
        this.shooter = shooter;
        this.gunId = gunId;
        this.gunInstanceUuid = gunInstanceUuid;
        this.state = new HashMap<>(state);
    }

    /**
     * Returns an unmodifiable view of all frozen stats (attribute id → value).
     *
     * <p>Used by {@code BulletSyncService} to build the client-side
     * {@code ClientBulletSnapshot} projection for visual-tick hooks. The
     * returned map is a live unmodifiable view of the internal map; mutations
     * made by server-side hooks via {@link #setStat} are visible to subsequent
     * readers.</p>
     *
     * @return an unmodifiable view of the stats map
     */
    public Map<ResourceLocation, Double> getStats() {
        return Collections.unmodifiableMap(stats);
    }

    /**
     * Returns an unmodifiable view of all activated traits (trait id → flag).
     *
     * <p>Used by {@code BulletSyncService} to build the client-side
     * {@code ClientBulletSnapshot} projection for visual-tick hooks. The
     * returned map is a live unmodifiable view of the internal map; mutations
     * made by server-side hooks via {@link #setTrait} are visible to subsequent
     * readers.</p>
     *
     * @return an unmodifiable view of the traits map
     */
    public Map<ResourceLocation, Boolean> getTraits() {
        return Collections.unmodifiableMap(traits);
    }

    /** Returns the stat value for the given attribute id, or {@code 0.0} if absent. */
    public double getStat(ResourceLocation id) {
        return stats.getOrDefault(id, 0.0);
    }

    /** Sets or overwrites the stat value for the given attribute id. */
    public void setStat(ResourceLocation id, double value) {
        stats.put(id, value);
    }

    /** Returns the trait flag for the given trait id, or {@code false} if absent. */
    public boolean getTrait(ResourceLocation id) {
        return traits.getOrDefault(id, false);
    }

    /** Sets or overwrites the trait flag for the given trait id. */
    public void setTrait(ResourceLocation id, boolean value) {
        traits.put(id, value);
    }

    /** Returns the damage type holder used when applying hurt to a target. */
    public Holder<DamageType> getDamageType() {
        return damageType;
    }

    /** Replaces the damage type holder (callable by trait hooks in-flight). */
    public void setDamageType(Holder<DamageType> damageType) {
        this.damageType = damageType;
    }

    /** Returns the per-bullet state value for the given state id, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> T getState(ResourceLocation id) {
        return (T) state.get(id);
    }

    /** Sets or overwrites the per-bullet state value for the given state id. */
    public <T> void setState(ResourceLocation id, T value) {
        state.put(id, value);
    }

    /**
     * Returns the per-bullet state value for the given state id, with
     * registry validation (设计文档 §状态存储系统 per-bullet, §错误处理).
     *
     * <p>Validation flow (no exceptions thrown — graceful degradation only):
     * <ol>
     *   <li>Look up the state id in the {@code modularshoot:states} registry.
     *       If unregistered → returns {@code null} and logs a WARN via
     *       {@link StateWarnLogger#warnUnregistered}.</li>
     *   <li>Check the registered {@link StateDomain}. If not
     *       {@link StateDomain#BULLET} → returns {@code null} and logs a
     *       WARN via
     *       {@link StateWarnLogger#warnDomainMismatch}.</li>
     *   <li>If the state id is absent from the per-bullet state map, returns
     *       the registry's {@link StateDefinition#defaultValue()} so hooks
     *       can read unset states without null checks.</li>
     *   <li>If the stored value's runtime type does not match the registered
     *       {@link StateValueType} → returns {@code null} and logs a WARN
     *       via {@link StateWarnLogger#warnTypeMismatch}.</li>
     *   <li>Otherwise returns the stored value cast to {@code T}.</li>
     * </ol>
     *
     * <p>Use this overload when a {@link RegistryAccess} is available (e.g.
     * inside a hook that receives the {@link net.minecraft.world.level.Level}).
     * Use {@link #getState(ResourceLocation)} for the lightweight,
     * non-validating variant when no registry view is on hand.</p>
     *
     * @param stateId        the state definition id to read
     * @param registryAccess the runtime registry view used to resolve the
     *                       state definition
     * @return the stored value, the registry default value when the id is
     *         registered but unset, or {@code null} on unregistered /
     *         domain mismatch / type mismatch
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getState(ResourceLocation stateId, RegistryAccess registryAccess) {
        final StateDefinition def = validateBulletState(stateId, registryAccess);
        if (def == null) {
            return null;
        }
        if (!state.containsKey(stateId)) {
            return (T) def.defaultValue();
        }
        final Object value = state.get(stateId);
        if (!StateValueCodecs.isTypeMatch(def.valueType(), value)) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), value == null ? null : value.getClass());
            return null;
        }
        return (T) value;
    }

    /**
     * Sets or overwrites the per-bullet state value, with registry
     * validation (设计文档 §状态存储系统 per-bullet, §错误处理).
     *
     * <p>Validation flow (no exceptions thrown — graceful degradation only):
     * <ol>
     *   <li>Look up the state id in the {@code modularshoot:states} registry.
     *       If unregistered → the write is skipped and a WARN is logged via
     *       {@link StateWarnLogger#warnUnregistered}.</li>
     *   <li>Check the registered {@link StateDomain}. If not
     *       {@link StateDomain#BULLET} → the write is skipped and a WARN is
     *       logged via
     *       {@link StateWarnLogger#warnDomainMismatch}.</li>
     *   <li>If the value's runtime type does not match the registered
     *       {@link StateValueType} → the write is skipped and a WARN is
     *       logged via {@link StateWarnLogger#warnTypeMismatch}.</li>
     *   <li>Otherwise the value is written to the per-bullet state map.</li>
     * </ol>
     *
     * <p>Use this overload when a {@link RegistryAccess} is available. Use
     * {@link #setState(ResourceLocation, Object)} for the lightweight,
     * non-validating variant when no registry view is on hand.</p>
     *
     * @param stateId        the state definition id to write
     * @param value          the value to write; {@code null} is only valid
     *                       for UUID-typed states
     * @param registryAccess the runtime registry view used to resolve the
     *                       state definition
     */
    public void setState(ResourceLocation stateId, @Nullable Object value, RegistryAccess registryAccess) {
        final StateDefinition def = validateBulletState(stateId, registryAccess);
        if (def == null) {
            return;
        }
        if (!StateValueCodecs.isTypeMatch(def.valueType(), value)) {
            StateWarnLogger.warnTypeMismatch(stateId, def.valueType(), value == null ? null : value.getClass());
            return;
        }
        state.put(stateId, value);
    }

    /**
     * Returns whether a per-bullet state id has been explicitly written to
     * the state map, with registry validation (设计文档 §状态存储系统
     * per-bullet).
     *
     * <p>Validation flow (no exceptions thrown):
     * <ol>
     *   <li>If the state id is unregistered → returns {@code false} and
     *       logs a WARN via
     *       {@link StateWarnLogger#warnUnregistered}.</li>
     *   <li>If the registered {@link StateDomain} is not
     *       {@link StateDomain#BULLET} → returns {@code false} and logs a
     *       WARN via
     *       {@link StateWarnLogger#warnDomainMismatch}.</li>
     *   <li>Otherwise returns {@code true} if the state id is present in
     *       the per-bullet state map.</li>
     * </ol>
     *
     * @param stateId        the state definition id to test
     * @param registryAccess the runtime registry view used to resolve the
     *                       state definition
     * @return {@code true} if the state id is registered as
     *         {@link StateDomain#BULLET} and present in the state map;
     *         {@code false} otherwise
     */
    public boolean hasState(ResourceLocation stateId, RegistryAccess registryAccess) {
        final StateDefinition def = validateBulletState(stateId, registryAccess);
        if (def == null) {
            return false;
        }
        return state.containsKey(stateId);
    }

    /**
     * Removes a per-bullet state entry from the state map, restoring it to
     * the "unset" state (设计文档 §状态存储系统 per-bullet).
     *
     * <p>No registry access is required because removal does not need to
     * know the value type. Subsequent reads via
     * {@link #getState(ResourceLocation, RegistryAccess)} will return the
     * registry's default value.</p>
     *
     * @param stateId the state definition id to remove
     */
    public void clearState(ResourceLocation stateId) {
        state.remove(stateId);
    }

    /**
     * Validates that a state id is registered in the
     * {@code modularshoot:states} registry with domain
     * {@link StateDomain#BULLET}.
     *
     * <p>Logs a WARN via {@link StateWarnLogger} for unregistered ids and
     * domain mismatches. Never throws.</p>
     *
     * @param stateId        the state definition id to validate
     * @param registryAccess the runtime registry view
     * @return the matching {@link StateDefinition} when the id is
     *         registered as {@code BULLET}; {@code null} otherwise (with
     *         a WARN already logged)
     */
    @Nullable
    private StateDefinition validateBulletState(ResourceLocation stateId, RegistryAccess registryAccess) {
        final Optional<StateDefinition> definition = StateRegistry.getState(registryAccess, stateId);
        if (definition.isEmpty()) {
            StateWarnLogger.warnUnregistered(stateId);
            return null;
        }
        final StateDefinition def = definition.get();
        if (def.domain() != StateDomain.BULLET) {
            StateWarnLogger.warnDomainMismatch(stateId, StateDomain.BULLET, def.domain());
            return null;
        }
        return def;
    }

    /**
     * Encodes the per-bullet {@code state} map to an NBT {@link CompoundTag}
     * for network sync of initial values (设计文档 §持久化与同步 per-bullet).
     *
     * <p>Used by {@code BulletS2CPacket} to carry the state map's initial
     * values to the client when a bullet enters render distance. In-flight
     * mutations after this snapshot are the hook's own responsibility
     * (typically surfaced through {@code onVisualTick}).</p>
     *
     * @param registryAccess the runtime registry view used to resolve
     *                       declared state types
     * @return a {@link CompoundTag} containing all encoded state entries
     */
    public CompoundTag encodeState(RegistryAccess registryAccess) {
        return StateValueCodecs.encodeStateMap(state, registryAccess);
    }

    /**
     * Replaces this snapshot's {@code state} map with the decoded contents
     * of the given NBT {@link CompoundTag} (设计文档 §持久化与同步 per-bullet).
     *
     * <p>Used on the client to populate the per-bullet working memory from
     * the initial values carried by {@code BulletS2CPacket}. The existing
     * state map is cleared before the decoded entries are loaded.</p>
     *
     * @param tag            the compound tag produced by
     *                       {@link #encodeState} /
     *                       {@link StateValueCodecs#encodeStateMap}
     * @param registryAccess the runtime registry view used to resolve
     *                       declared state types; unregistered state ids
     *                       are silently skipped
     */
    public void decodeState(CompoundTag tag, RegistryAccess registryAccess) {
        state.clear();
        state.putAll(StateValueCodecs.decodeStateMap(tag, registryAccess));
    }

    /** Returns the shooter uuid, or {@code null} for independent firing. */
    @Nullable
    public UUID getShooter() {
        return shooter;
    }

    /** Returns the gun definition id, or {@code null} for independent firing. */
    @Nullable
    public ResourceLocation getGunId() {
        return gunId;
    }

    /** Returns the gun instance uuid, or {@code null} for independent firing. */
    @Nullable
    public UUID getGunInstanceUuid() {
        return gunInstanceUuid;
    }
}
