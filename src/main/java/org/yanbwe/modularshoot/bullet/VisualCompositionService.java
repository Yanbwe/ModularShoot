package org.yanbwe.modularshoot.bullet;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.Trait;
import org.yanbwe.modularshoot.registry.gun.AttachLayerModifier;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.Modifier;
import org.yanbwe.modularshoot.registry.gun.ScaleModifier;
import org.yanbwe.modularshoot.registry.gun.TintModifier;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateRegistry;
import org.yanbwe.modularshoot.state.StateWarnLogger;

/**
 * Pure-function composition service (设计规格 §4.2). Runs <em>exactly
 * once</em> per bullet at creation time (§2.1 "创建瞬间冻结"); the produced
 * {@link ComposedBulletStyle} is cached on
 * {@link org.yanbwe.modularshoot.bullet.BulletRecord} and read back by every
 * {@code BulletSyncService.toFullBulletEntry} call site.
 *
 * <h2>Sources and priority</h2>
 *
 * <p>Four source groups stack additively into the composed style (spec §4.2
 * algorithm):</p>
 *
 * <ol>
 *   <li><b>Gun base/modifiers</b> (priority 0) &mdash; the
 *       {@link GunDefinition#bulletStyle()} of the firing gun.</li>
 *   <li><b>Installed plugin base/modifiers</b> (priority =
 *       {@link PluginDefinition#priority()}) &mdash; each installed plugin's
 *       {@link PluginDefinition#bulletStyle()} in install order. Within a
 *       base-winner election, higher priority wins; equal priorities tie-break
 *       by install order (later-installed wins). All modifiers beyond base
 *       stack into the multiplicative scales/tints and the additive layers,</li>
 *   <li><b>Active trait modifiers</b> &mdash; every trait id in
 *       {@link BulletSnapshot#getTraits()} whose value is {@code true}
 *       contributes its {@link Trait#visualModifiers()}. Traits do not
 *       contribute a base.</li>
 *   <li><b>State condition modifiers</b> &mdash; each
 *       {@link StateDefinition.StateVisualModifier} whose
 *       condition holds against the per-bullet (first) or per-gun state map
 *       at creation time contributes its modifiers (spec §3.5 domain
 *       resolution).</li>
 * </ol>
 *
 * <p>Base election: last-wins by priority; equal priority later install
 * wins; no candidate &rarr; {@link ComposedBulletStyle#FALLBACK_BASE}.</p>
 *
 * <h2>Compute</h2>
 *
 * <ul>
 *   <li>{@code renderScale} = &prod; of every top-level {@link ScaleModifier#value()}
 *       across all sources. NaN/&le;0 skipped + WARN; the running product is
 *       clamped finite &gt;0 at the end (default {@code 1.0}).</li>
 *   <li>{@code composedTint} = channel-wise &prod; of every top-level
 *       {@link TintModifier#color()}. Negative/NaN channels skip + WARN;
 *       values &gt;1 clamp to 1 (no warn).</li>
 *   <li>{@code layers} = every {@link AttachLayerModifier} across all sources,
 *       in source order. Per-layer scale/tint apply <em>only</em> to the
 *       layer; they do not participate in the base
 *       {@code renderScale}/{@code composedTint} multiplications.</li>
 * </ul>
 *
 * <h2>Reliability</h2>
 *
 * <p>Never returns {@code null}, never throws; every fault degrades to a
 * skip + WARN (spec §5). Unknown modifier types (the
 * {@link org.yanbwe.modularshoot.registry.gun.UnsupportedModifier} sentinel)
 * skip silently via the {@code addModifiers} path. Unregistered plugin /
 * state ids, UUID state conditions, domain mismatches and threshold type
 * mismatches all skip + WARN through {@link StateWarnLogger} or
 * {@link ModularShoot#LOGGER}.</p>
 *
 * <h2>Dependency-injection seam</h2>
 *
 * <p>The four protected lookup methods ({@link #lookupGun},
 * {@link #lookupPlugin}, {@link #lookupTrait},
 * {@link #allStateDefsWithVisualModifiers}) are the only {@code RegistryAccess}
 * touch points. Pure-logic tests extend this class in the test source set and
 * override them to feed stubbed definitions &mdash; no MC server/clienteworthy
 * runtime is required (设计规格 §6).</p>
 */
public class VisualCompositionService {

    /** Shared singleton used by both bullet-creation call sites. */
    public static final VisualCompositionService INSTANCE = new VisualCompositionService();

    // ------------------------------------------------------------------
    // Dependency-injection override points (no-op defaults delegate to real
    // registry lookups; tests override them with stubbed data).
    // ------------------------------------------------------------------

    /**
     * Resolves a gun definition by id (override point for tests).
     *
     * @param ra    the runtime registry view (ignored by stubs)
     * @param gunId the gun definition id, never {@code null}
     * @return the gun definition, or empty when unregistered
     */
    protected Optional<GunDefinition> lookupGun(RegistryAccess ra, ResourceLocation gunId) {
        return GunRegistry.getGun(ra, gunId);
    }

    /**
     * Resolves a plugin definition by id (override point for tests).
     *
     * @param ra       the runtime registry view (ignored by stubs)
     * @param pluginId the plugin definition id, never {@code null}
     * @return the plugin definition, or empty when unregistered
     */
    protected Optional<PluginDefinition> lookupPlugin(RegistryAccess ra, ResourceLocation pluginId) {
        return PluginRegistry.getPlugin(ra, pluginId);
    }

    /**
     * Resolves a trait definition by id (override point for tests).
     *
     * @param ra      the runtime registry view (ignored by stubs)
     * @param traitId the trait definition id, never {@code null}
     * @return the trait definition, or empty when unregistered
     */
    protected Optional<Trait> lookupTrait(RegistryAccess ra, ResourceLocation traitId) {
        return ra.registry(ModularShootRegistries.TRAITS_KEY)
                .flatMap(reg -> reg.getOptional(traitId));
    }

    /**
     * Returns every registered state definition that declares at least one
     * {@link StateDefinition#visualModifiers()} entry (override point).
     *
     * @param ra the runtime registry view (ignored by stubs)
     * @return a stream of state definitions with non-empty visual modifiers
     */
    protected Stream<StateDefinition> allStateDefsWithVisualModifiers(RegistryAccess ra) {
        return StateRegistry.getAllStates(ra)
                .filter(def -> !def.visualModifiers().isEmpty());
    }

    // ------------------------------------------------------------------
    // Main entry
    // ------------------------------------------------------------------

    /**
     * Composes the bullet's visual style exactly once at creation time
     * (spec §2.1 / §4.2). Never returns {@code null}, never throws.
     *
     * @param ra       the runtime registry view; {@code null} only in tests
     *                 that override all four lookup points
     * @param snapshot the per-bullet frozen snapshot ({@code null} tolerated,
     *                 returns {@link ComposedBulletStyle#DEFAULT})
     * @param gunData  the firing gun's data, or {@code null} for independent
     *                 firing (no gun/plugin/state-by-gun sources)
     * @return the composed style, never {@code null}
     */
    public ComposedBulletStyle compose(
            @Nullable RegistryAccess ra,
            @Nullable BulletSnapshot snapshot,
            @Nullable GunData gunData) {
        if (snapshot == null) {
            return ComposedBulletStyle.DEFAULT;
        }
        SourceCollector sources = new SourceCollector(ra, snapshot, gunData, this);
        sources.collectAll();
        BulletStyle.Base base = sources.pickBase();
        float renderScale = computeRenderScale(sources.scaleMods);
        Vector4f composedTint = computeComposedTint(sources.tintMods);
        List<ComposedBulletStyle.LayerEntry> layers = buildLayers(sources.layerMods);
        return new ComposedBulletStyle(base, renderScale, composedTint, layers);
    }

    // ------------------------------------------------------------------
    // Compute helpers
    // ------------------------------------------------------------------

    private float computeRenderScale(List<ScaleModifier> scaleMods) {
        float product = 1.0f;
        for (ScaleModifier m : scaleMods) {
            float v = m.value();
            if (!Float.isFinite(v) || v <= 0.0f) {
                ModularShoot.LOGGER.warn(
                        "Scale modifier with non-finite or non-positive value {}; skipping", v);
                continue;
            }
            product *= v;
        }
        if (!Float.isFinite(product) || product <= 0.0f) {
            ModularShoot.LOGGER.warn(
                    "Composed renderScale underflow {}; clamping to 1.0", product);
            product = 1.0f;
        }
        return product;
    }

    private Vector4f computeComposedTint(List<TintModifier> tintMods) {
        float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;
        for (TintModifier t : tintMods) {
            Vector4f c = t.color();
            if (Float.isNaN(c.x) || Float.isNaN(c.y) || Float.isNaN(c.z) || Float.isNaN(c.w)
                    || c.x < 0.0f || c.y < 0.0f || c.z < 0.0f || c.w < 0.0f) {
                ModularShoot.LOGGER.warn(
                        "Tint modifier with NaN or negative channel {}; skipping", c);
                continue;
            }
            r *= clamp01(c.x);
            g *= clamp01(c.y);
            b *= clamp01(c.z);
            a *= clamp01(c.w);
        }
        return new Vector4f(clamp01(r), clamp01(g), clamp01(b), clamp01(a));
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0.0f;
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private List<ComposedBulletStyle.LayerEntry> buildLayers(List<AttachLayerModifier> layerMods) {
        ImmutableList.Builder<ComposedBulletStyle.LayerEntry> builder = ImmutableList.builder();
        for (AttachLayerModifier m : layerMods) {
            Vector4f tint = m.tint() == null ? ComposedBulletStyle.WHITE_TINT : m.tint();
            Vec3 off = m.offset() == null ? Vec3.ZERO : m.offset();
            builder.add(new ComposedBulletStyle.LayerEntry(
                    m.renderMode(), m.texture(), m.model(),
                    m.followRotation(), m.followScale(),
                    (float) off.x, (float) off.y, (float) off.z,
                    m.scale(),
                    new Vector4f(clamp01(tint.x), clamp01(tint.y), clamp01(tint.z), clamp01(tint.w))));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Source collection
    // ------------------------------------------------------------------

    /**
     * Internal collector that walks the four source groups, accumulating
     * base candidates, top-level scale/tint modifiers and additive layer
     * modifiers. Mutates its own lists; the outer {@link #compose} method
     * reads them after {@link #collectAll()} completes.
     */
    private static final class SourceCollector {
        @Nullable private final RegistryAccess ra;
        private final BulletSnapshot snapshot;
        @Nullable private final GunData gunData;
        private final VisualCompositionService svc;

        final List<BaseCandidate> baseCands = new ArrayList<>();
        final List<ScaleModifier> scaleMods = new ArrayList<>();
        final List<TintModifier> tintMods = new ArrayList<>();
        final List<AttachLayerModifier> layerMods = new ArrayList<>();

        SourceCollector(@Nullable RegistryAccess ra, BulletSnapshot snapshot,
                        @Nullable GunData gd, VisualCompositionService svc) {
            this.ra = ra;
            this.snapshot = snapshot;
            this.gunData = gd;
            this.svc = svc;
        }

        void collectAll() {
            if (ra == null) {
                return;
            }
            collectGun(0);
            collectPlugins();
            collectTraits();
            collectStateConditions();
        }

        private void collectGun(int priority) {
            if (gunData == null) {
                return;
            }
            Optional<GunDefinition> gd = svc.lookupGun(ra, gunData.gunId());
            if (gd.isEmpty()) {
                return;
            }
            gd.get().bulletStyle().ifPresent(style -> {
                style.base().ifPresent(b -> baseCands.add(new BaseCandidate(b, priority, 0)));
                addModifiers(style.modifiers());
            });
        }

        private void collectPlugins() {
            if (gunData == null) {
                return;
            }
            int installIdx = 0;
            for (PluginInstance inst : gunData.installedPlugins()) {
                Optional<PluginDefinition> pd = svc.lookupPlugin(ra, inst.pluginId());
                if (pd.isEmpty()) {
                    ModularShoot.LOGGER.warn(
                            "Installed plugin id {} not found in registry; skipping its visual contributions",
                            inst.pluginId());
                    installIdx++;
                    continue;
                }
                int priority = pd.get().priority();
                pd.get().bulletStyle().ifPresent(style -> {
                    style.base().ifPresent(b -> baseCands.add(new BaseCandidate(b, priority, installIdx)));
                    addModifiers(style.modifiers());
                });
                installIdx++;
            }
        }

        private void collectTraits() {
            for (Map.Entry<ResourceLocation, Boolean> e : snapshot.getTraits().entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) {
                    continue;
                }
                Optional<Trait> td = svc.lookupTrait(ra, e.getKey());
                if (td.isEmpty()) {
                    continue;
                }
                addModifiers(td.get().visualModifiers());
            }
        }

        private void collectStateConditions() {
            svc.allStateDefsWithVisualModifiers(ra).forEach(def -> {
                for (StateDefinition.StateVisualModifier svm : def.visualModifiers()) {
                    @Nullable Object stateValue = resolveStateValue(svm.condition());
                    if (stateValue == null) {
                        continue;
                    }
                    if (!StateConditionEvaluator.eval(svm.condition().op(), stateValue,
                            svm.condition().value())) {
                        continue;
                    }
                    addModifiers(svm.modifiers());
                }
            });
        }

        /**
         * Resolves the raw state value backing a
         * {@link StateDefinition.VisualCondition} against the per-bullet
         * (first) or per-gun state map (spec §3.5 domain resolution).
         *
         * <p>Explicit {@code domain}: BULLET reads the snapshot, GUN reads
         * the gun-data state map, PLAYER is unsupported &rarr;
         * {@code null} + WARN. Implicit (empty {@code domain}): per-bullet
         * then per-gun, falling back to the registered default value when
         * neither holds a stored value.</p>
         *
         * @param cond the condition to evaluate
         * @return the resolved raw state value, or {@code null} on
         *         unregistered id / domain mismatch / unsupported player
         *         domain
         */
        private @Nullable Object resolveStateValue(StateDefinition.VisualCondition cond) {
            Optional<StateDefinition> def = StateRegistry.getState(ra, cond.state());
            if (def.isEmpty()) {
                StateWarnLogger.warnUnregistered(cond.state());
                return null;
            }
            Optional<StateDomain> explicitDomain = cond.domain();
            @Nullable Object perBullet = snapshot.getState(cond.state());
            @Nullable Object perGun = gunData == null ? null : gunData.getStateValue(cond.state(), ra);
            if (explicitDomain.isPresent()) {
                return switch (explicitDomain.get()) {
                    case BULLET -> perBullet != null ? perBullet : def.get().defaultValue();
                    case GUN -> perGun != null ? perGun : def.get().defaultValue();
                    case PLAYER -> {
                        StateWarnLogger.warnDomainMismatch(cond.state(),
                                StateDomain.BULLET, StateDomain.PLAYER);
                        yield null;
                    }
                };
            }
            // absent domain -> per-bullet first, then per-gun, else default
            if (perBullet != null) {
                return perBullet;
            }
            if (perGun != null) {
                return perGun;
            }
            return def.get().defaultValue();
        }

        /**
         * Routes a list of modifiers from one source into the per-type
         * buckets. Unknown modifier types (the {@code UnsupportedModifier}
         * sentinel emitted by the {@link Modifier#CODEC} for an unrecognised
         * {@code "type"} field) skip + WARN here, never reaching the composed
         * result.
         *
         * @param mods the modifier list to route (may be empty, never null)
         */
        private void addModifiers(List<Modifier> mods) {
            for (Modifier m : mods) {
                if (m instanceof ScaleModifier s) {
                    scaleMods.add(s);
                } else if (m instanceof TintModifier t) {
                    tintMods.add(t);
                } else if (m instanceof AttachLayerModifier l) {
                    layerMods.add(l);
                } else {
                    // UnsupportedModifier sentinel, or future-proof name
                    ModularShoot.LOGGER.warn("Unknown modifier type {}; skipping", m.type());
                }
            }
        }

        /**
         * Picks the winning base candidate: highest priority, then higher
         * install index (later-installed) at equal priority. Empty list
         * &rarr; {@link ComposedBulletStyle#FALLBACK_BASE}.
         *
         * @return the winning base, never {@code null}
         */
        BulletStyle.Base pickBase() {
            if (baseCands.isEmpty()) {
                return ComposedBulletStyle.FALLBACK_BASE;
            }
            BaseCandidate winner = baseCands.get(0);
            for (BaseCandidate c : baseCands) {
                if (c.priority > winner.priority
                        || (c.priority == winner.priority && c.installIdx > winner.installIdx)) {
                    winner = c;
                }
            }
            return winner.base;
        }

        private record BaseCandidate(BulletStyle.Base base, int priority, int installIdx) {
        }
    }
}