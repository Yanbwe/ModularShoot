package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.yanbwe.modularshoot.damage.ModularShootDamageTypes;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

/**
 * Chainable builder for {@link BulletSnapshot} instances of the
 * independent-firing flow (设计文档 §独立发射 / §独立发射的快照字段约定).
 *
 * <p>Independent firing is the non-player-source path (turret, trap, boss
 * attack, scripted scenario, ...): the snapshot is built by hand instead of
 * being frozen by the player shooting engine, so the engine-owned fields
 * follow the independent-firing convention — {@code gunId} and
 * {@code gunInstanceUuid} are always {@code null} (no firing gun exists) and
 * {@code shooter} is always {@code null} (the caller passes the shooter uuid
 * separately at {@code BulletManager#fireBullet} time). The builder has no
 * shooter/gun setters by design: those fields are engine-owned and read-only
 * on the snapshot.</p>
 *
 * <p>Accumulated {@code stats}/{@code traits}/{@code state} are kept in plain
 * {@link HashMap}s and handed to the {@link BulletSnapshot} constructor, which
 * copies them defensively — so the builder remains freely reusable after
 * {@link #build()}: mutating the builder afterwards never leaks into an
 * already-built snapshot. Each chainable setter overwrites the previous value
 * for the same id (last-write-wins), mirroring
 * {@link BulletSnapshot#setStat} / {@link BulletSnapshot#setTrait} /
 * {@link BulletSnapshot#setState}.</p>
 *
 * <p><b>Damage type:</b> {@link #build()} allows the damage type to be
 * unset — the snapshot then carries {@code null} (valid; the firing boundary
 * patches it in, see {@link #build(RegistryAccess)} and the
 * {@code ModularShootAPI#fireBullet} facade). Use
 * {@link #build(RegistryAccess)} when a runtime registry view is on hand to
 * fill in the framework default
 * ({@link ModularShootDamageTypes#holderOrThrow}) automatically.</p>
 *
 * <p><b>Visual channel:</b> {@link #style(BulletStyle)} writes the
 * <em>independent-firing visual channel</em> — the snapshot's
 * {@link BulletSnapshot#setVariantStyleOverride variant style override}, which
 * the server-side compose step
 * ({@link org.yanbwe.modularshoot.bullet.VisualCompositionService}) reads as
 * the bullet's visual base/stacking modifiers when no firing gun contributes a
 * style (design doc: variant style override is the declared entry point for
 * non-gun visual sources).</p>
 */
public final class BulletSnapshotBuilder {

    private final Map<ResourceLocation, Double> stats = new HashMap<>();
    private final Map<ResourceLocation, Boolean> traits = new HashMap<>();
    private final Map<ResourceLocation, Object> state = new HashMap<>();
    private BulletStyle style;
    private Holder<DamageType> damageType;

    /**
     * Sets (or overwrites) a frozen stat value for the given attribute id.
     *
     * @param id    the attribute id; must not be {@code null}
     * @param value the stat value
     * @return this builder, for chaining
     */
    public BulletSnapshotBuilder stat(ResourceLocation id, double value) {
        Objects.requireNonNull(id, "id");
        stats.put(id, value);
        return this;
    }

    /**
     * Sets (or overwrites) an activated trait flag for the given trait id.
     *
     * @param id    the trait id; must not be {@code null}
     * @param value {@code true} to mark the trait activated
     * @return this builder, for chaining
     */
    public BulletSnapshotBuilder trait(ResourceLocation id, boolean value) {
        Objects.requireNonNull(id, "id");
        traits.put(id, value);
        return this;
    }

    /**
     * Sets (or overwrites) a per-bullet working-memory value for the given
     * state id (设计文档 §三层归属 per-bullet).
     *
     * <p>These values seed the bullet's {@code state} map (initial
     * {@code BulletS2CPacket} carryover); they are <em>not</em> part of the
     * attribute-snapshot freezing flow. {@code null} values are allowed for
     * UUID-typed states, matching the lightweight
     * {@link BulletSnapshot#setState(ResourceLocation, Object)} contract.</p>
     *
     * @param id    the state id; must not be {@code null}
     * @param value the state value, or {@code null} for UUID-typed states
     * @return this builder, for chaining
     */
    public BulletSnapshotBuilder state(ResourceLocation id, Object value) {
        Objects.requireNonNull(id, "id");
        state.put(id, value);
        return this;
    }

    /**
     * Sets the independent-firing visual style, carried as the snapshot's
     * {@link BulletSnapshot#setVariantStyleOverride variant style override}
     * (the "independent-firing visual channel").
     *
     * <p>Server-side compose reads this override when no firing gun can be
     * resolved from the snapshot (always the case for independent firing,
     * since {@code gunId}/{@code gunInstanceUuid} are {@code null}):
     * {@code base} contributes the visual base, {@code modifiers} stack in
     * declared order (设计规格 §3.1 / §3.4). The override is never serialized
     * to the client.</p>
     *
     * @param style the visual style to attach; must not be {@code null}
     * @return this builder, for chaining
     */
    public BulletSnapshotBuilder style(BulletStyle style) {
        this.style = Objects.requireNonNull(style, "style");
        return this;
    }

    /**
     * Sets the damage type holder used when the bullet applies hurt.
     *
     * <p>Optional: {@link #build()} succeeds with a {@code null} damage type
     * (the firing boundary patches the framework default in).</p>
     *
     * @param holder the damage type holder; must not be {@code null}
     * @return this builder, for chaining
     */
    public BulletSnapshotBuilder damageType(Holder<DamageType> holder) {
        this.damageType = Objects.requireNonNull(holder, "holder");
        return this;
    }

    /**
     * Builds the {@link BulletSnapshot}, leaving the builder reusable.
     *
     * <p>Per the independent-firing convention the snapshot is constructed
     * with {@code shooter = null}, {@code gunId = null} and
     * {@code gunInstanceUuid = null} (设计文档 §独立发射的快照字段约定). The
     * damage type may be {@code null} when unset; use
     * {@link #build(RegistryAccess)} to fill in the framework default
     * automatically. When {@link #style(BulletStyle)} was called, the style is
     * attached as the snapshot's variant style override (independent-firing
     * visual channel).</p>
     *
     * @return a new, independent snapshot (defensive copies of all maps)
     */
    public BulletSnapshot build() {
        BulletSnapshot snapshot = new BulletSnapshot(
                stats, traits, damageType, null, null, null, state);
        if (style != null) {
            snapshot.setVariantStyleOverride(style);
        }
        return snapshot;
    }

    /**
     * Builds the {@link BulletSnapshot} as in {@link #build()}, but fills in
     * the framework default damage type
     * ({@link ModularShootDamageTypes#holderOrThrow}) when none was set via
     * {@link #damageType(Holder)}.
     *
     * <p>This is the convenience overload for callers that already hold a
     * runtime {@link RegistryAccess} (e.g. {@code level.registryAccess()}) and
     * want the snapshot fully wired before handing it to the firing boundary.
     * The resolved holder is stored on the builder, so subsequent
     * {@link #build()} calls reuse it.</p>
     *
     * @param registryAccess the runtime registry view used to resolve the
     *                       framework default damage type; must not be
     *                       {@code null}
     * @return a new, independent snapshot with a non-null damage type
     * @throws IllegalStateException when the framework default damage type is
     *                               missing from {@code registryAccess} (e.g.
     *                               {@link RegistryAccess#EMPTY}, which has no
     *                               {@code DAMAGE_TYPE} registry)
     */
    public BulletSnapshot build(RegistryAccess registryAccess) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        if (damageType == null) {
            damageType = ModularShootDamageTypes.holderOrThrow(registryAccess);
        }
        return build();
    }
}
