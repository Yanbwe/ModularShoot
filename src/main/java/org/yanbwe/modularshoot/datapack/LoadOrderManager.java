package org.yanbwe.modularshoot.datapack;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the three-phase load order of the ModularShoot registration
 * pipeline (设计文档 §加载顺序, line 2299).
 *
 * <p>The framework populates its six dynamic registries in a strict
 * sequence of phases:</p>
 * <ol>
 *   <li>{@link LoadPhase#PRESET} &mdash; framework preset content: the
 *       nine {@code Attribute} bodies registered via
 *       {@code DeferredRegister}, the nine {@code attribute_meta}
 *       entries, and the built-in {@code bullet_damage} damage type.
 *       These are baked into the mod jar and never change at runtime.</li>
 *   <li>{@link LoadPhase#JAVA_API} &mdash; Java API registration: add-on
 *       mods register entries programmatically during mod init. The
 *       {@link RegistrationCoordinator} tracks every id claimed in this
 *       phase so that later datapack entries cannot override them.</li>
 *   <li>{@link LoadPhase#DATAPACK} &mdash; datapack JSON loading: the
 *       vanilla datapack registry pipeline (driven by NeoForge's
 *       {@code DataPackRegistryEvent}) parses JSON files from
 *       {@code data/<namespace>/modularshoot/<registry>/}. Datapack-vs-
 *       datapack priority is resolved by {@code pack.mcmeta} ordering;
 *       Java-API-vs-datapack conflicts are resolved by
 *       {@link RegistrationCoordinator}.</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 * <p>Phase transitions are driven by the mod-loading / world-loading
 * lifecycle, which is single-threaded. However, the completed-phase set
 * and the callback list use thread-safe containers
 * ({@link ConcurrentHashMap#newKeySet()} and {@link CopyOnWriteArrayList})
 * so that read-only queries from any thread are safe and callback
 * registration during init is safely published to the loader thread
 * (设计文档 §注册表并发策略).</p>
 *
 * <p>The current phase is stored in a {@code volatile} field so that
 * reads by datapack loaders observe the latest write.</p>
 *
 * <p>This class is not instantiable. All methods are static and each is
 * under 50 lines (设计文档 §函数&lt;50行).</p>
 *
 * @see RegistrationCoordinator
 */
public final class LoadOrderManager {
    /** Dedicated subsystem logger for load-order tracking. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/LoadOrder");

    /**
     * The current load phase. {@code volatile} so that reads from the
     * datapack loader thread observe the latest write from the
     * lifecycle driver thread.
     */
    private static volatile LoadPhase currentPhase = LoadPhase.PRESET;

    /** Set of phases that have been marked complete via {@link #completePhase}. */
    private static final Set<LoadPhase> COMPLETED_PHASES = ConcurrentHashMap.newKeySet();

    /**
     * Callbacks invoked when a phase completes. Uses
     * {@link CopyOnWriteArrayList} because callbacks are registered rarely
     * (during mod init) but iterated on every phase completion.
     */
    private static final CopyOnWriteArrayList<Consumer<LoadPhase>> PHASE_COMPLETE_CALLBACKS =
            new CopyOnWriteArrayList<>();

    private LoadOrderManager() {
    }

    /**
     * The three sequential phases of the ModularShoot registration
     * pipeline (设计文档 §加载顺序).
     *
     * <p>Constants are declared in execution order; {@link #ordinal()}
     * therefore reflects the phase sequence and is used by
     * {@link #isAfter(LoadPhase)} for ordering queries.</p>
     *
     * <table>
     *   <caption>Load phases and their content</caption>
     *   <tr><th>Constant</th><th>Content</th><th>When</th></tr>
     *   <tr><td>{@link #PRESET}</td><td>Framework preset content
     *       (attributes, attribute_meta, bullet_damage)</td>
     *       <td>Mod loading / common setup</td></tr>
     *   <tr><td>{@link #JAVA_API}</td><td>Java API programmatic
     *       registration by add-on mods</td>
     *       <td>Mod init, before world load</td></tr>
     *   <tr><td>{@link #DATAPACK}</td><td>Datapack JSON loading via
     *       NeoForge {@code DataPackRegistryEvent}</td>
     *       <td>World load / {@code /reload}</td></tr>
     * </table>
     */
    public enum LoadPhase {
        /**
         * Framework preset content phase.
         *
         * <p>Registers the nine {@code Attribute} bodies via
         * {@code DeferredRegister}, the nine {@code attribute_meta}
         * entries, and the built-in {@code bullet_damage} damage type.
         * This content is baked into the mod jar and never changes at
         * runtime (设计文档 §加载顺序, line 2299).</p>
         */
        PRESET,

        /**
         * Java API registration phase.
         *
         * <p>Add-on mods register entries programmatically during mod
         * init. Every id claimed in this phase is tracked by
         * {@link RegistrationCoordinator#markJavaApiRegistered} so that
         * later datapack entries cannot override them (设计文档
         * §注册冲突与覆盖).</p>
         */
        JAVA_API,

        /**
         * Datapack JSON loading phase.
         *
         * <p>The vanilla datapack registry pipeline (driven by NeoForge's
         * {@code DataPackRegistryEvent}) parses JSON files from
         * {@code data/<namespace>/modularshoot/<registry>/}. Datapack-vs-
         * datapack priority is resolved by {@code pack.mcmeta} ordering;
         * Java-API-vs-datapack conflicts are resolved by
         * {@link RegistrationCoordinator#attemptDatapackOverride}
         * (设计文档 §加载顺序, line 2299).</p>
         */
        DATAPACK
    }

    /**
     * Returns the current load phase.
     *
     * @return the current phase; defaults to {@link LoadPhase#PRESET}
     *         before any explicit transition
     */
    public static LoadPhase currentPhase() {
        return currentPhase;
    }

    /**
     * Transitions the pipeline into the given phase.
     *
     * <p>Logs the transition at {@code INFO} level. If the target phase
     * was already marked complete (e.g. on {@code /reload}), a
     * {@code WARN} is logged to flag the re-entry, but the transition
     * still proceeds so that reload scenarios are supported.</p>
     *
     * @param phase the phase to enter; must not be {@code null}
     */
    public static void enterPhase(LoadPhase phase) {
        Objects.requireNonNull(phase, "phase");
        if (COMPLETED_PHASES.contains(phase)) {
            LOGGER.warn("Re-entering completed load phase: {}; "
                    + "this may indicate a /reload or test reset.", phase);
        }
        currentPhase = phase;
        LOGGER.info("Entering load phase: {}", phase);
    }

    /**
     * Marks a phase as complete and fires all registered phase-complete
     * callbacks.
     *
     * <p>The callbacks are invoked in registration order. Each callback
     * receives the completed phase, allowing it to emit a log summary
     * (e.g. counting registered entries, reporting skipped conflicts)
     * (设计文档 §容错优先 &mdash; summary log after load).</p>
     *
     * @param phase the phase to mark complete; must not be {@code null}
     */
    public static void completePhase(LoadPhase phase) {
        Objects.requireNonNull(phase, "phase");
        COMPLETED_PHASES.add(phase);
        LOGGER.info("Completed load phase: {}", phase);
        firePhaseCompleteCallbacks(phase);
    }

    /**
     * Invokes every registered phase-complete callback for the given
     * phase.
     *
     * @param phase the phase that just completed
     */
    private static void firePhaseCompleteCallbacks(LoadPhase phase) {
        for (Consumer<LoadPhase> callback : PHASE_COMPLETE_CALLBACKS) {
            callback.accept(phase);
        }
    }

    /**
     * Checks whether a phase has been marked complete.
     *
     * @param phase the phase to query; must not be {@code null}
     * @return {@code true} if {@link #completePhase} was called for this
     *         phase
     */
    public static boolean isPhaseComplete(LoadPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return COMPLETED_PHASES.contains(phase);
    }

    /**
     * Checks whether the pipeline is currently in the given phase.
     *
     * @param phase the phase to test; must not be {@code null}
     * @return {@code true} if {@link #currentPhase()} equals {@code phase}
     */
    public static boolean isInPhase(LoadPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return currentPhase == phase;
    }

    /**
     * Checks whether the current phase is <em>after</em> the given phase
     * in the execution order.
     *
     * <p>Uses {@link LoadPhase#ordinal()} which reflects the declaration
     * order ({@code PRESET=0}, {@code JAVA_API=1}, {@code DATAPACK=2}).
     * For example, during the {@code DATAPACK} phase,
     * {@code isAfter(JAVA_API)} returns {@code true}.</p>
     *
     * @param phase the phase to compare against; must not be {@code null}
     * @return {@code true} if the current phase's ordinal is greater than
     *         the given phase's ordinal
     */
    public static boolean isAfter(LoadPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return currentPhase.ordinal() > phase.ordinal();
    }

    /**
     * Registers a callback that is invoked whenever any phase completes.
     *
     * <p>Callbacks fire in registration order. Safe to call during mod
     * common-setup; the {@link CopyOnWriteArrayList} ensures the callback
     * list is safely published to the loader thread (设计文档
     * §注册表并发策略).</p>
     *
     * @param callback the callback to register; must not be {@code null}
     */
    public static void registerPhaseCompleteCallback(Consumer<LoadPhase> callback) {
        Objects.requireNonNull(callback, "callback");
        PHASE_COMPLETE_CALLBACKS.add(callback);
    }

    /**
     * Resets the manager to its initial state.
     *
     * <p>Clears the completed-phase set and resets the current phase to
     * {@link LoadPhase#PRESET}. Intended for testing or for a full
     * framework reset. Under normal runtime conditions the load order is
     * driven once per world load and should not be reset.</p>
     */
    public static void reset() {
        currentPhase = LoadPhase.PRESET;
        COMPLETED_PHASES.clear();
    }
}
