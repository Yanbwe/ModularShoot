package org.yanbwe.modularshoot.datapack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.Trait;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.shooter.ShooterDefinition;
import org.yanbwe.modularshoot.registry.shooter.ShooterRegistry;
import org.yanbwe.modularshoot.registry.variant.VariantDefinition;
import org.yanbwe.modularshoot.state.StateDefinition;

/**
 * NeoForge reload listener for framework-specific post-reload logic
 * (设计文档 §/reload 重载行为, line 2305).
 *
 * <p>NeoForge's {@code DataPackRegistryEvent} already handles automatic
 * reloading of the ten dynamic registries ({@code guns}, {@code plugins},
 * {@code plugin_types}, {@code traits}, {@code states},
 * {@code attribute_meta}, {@code variants}, {@code gun_items},
 * {@code plugin_items}, {@code shooters}). This listener
 * complements that
 * with framework-specific post-reload logic that runs in the reload phase
 * (on the game thread), after the vanilla registry pipeline has finished:</p>
 * <ol>
 *   <li>Logs the reload completion via {@link ModularShoot#LOGGER}.</li>
 *   <li>Transitions {@link LoadOrderManager} into the
 *       {@link LoadOrderManager.LoadPhase#DATAPACK} phase.</li>
 *   <li>Calls each {@code DatapackLoader}'s validation method and collects
 *       the results into {@link DatapackLoadSummary} instances.</li>
 *   <li>Formats and logs the per-registry summary.</li>
 *   <li>Delegates creative-tab rebuild and online-player gun refresh to
 *       {@link ReloadBehaviorHandler}.</li>
 * </ol>
 *
 * <h2>Design notes</h2>
 * <p>The listener extends {@link SimplePreparableReloadListener} with a
 * {@code Void} type parameter: no preparation work is needed (the registries
 * are already loaded by the vanilla pipeline before the event fires), so
 * {@code prepare} returns {@code null} and all logic runs in {@code apply}
 * (the reload phase, on the game thread).</p>
 *
 * <p>{@link AddReloadListenerEvent} is a game-bus event fired on
 * {@code NeoForge.EVENT_BUS}. The {@link EventBusSubscriber} annotation
 * auto-detects the bus from the event type (game bus for events that do
 * not implement {@code IModBusEvent}).</p>
 *
 * <p>The class is a singleton: the {@code @EventBusSubscriber} mechanism
 * requires the event handler to be static, but
 * {@link AddReloadListenerEvent#addListener(PreparableReloadListener)}
 * requires an instance. A static singleton resolves this cleanly.</p>
 *
 * @see ReloadBehaviorHandler
 * @see LoadOrderManager
 * @see DatapackLoadSummary
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class DatapackReloadListener extends SimplePreparableReloadListener<Void> {

    /** Singleton instance — the event handler is static but the listener must be an instance. */
    private static final DatapackReloadListener INSTANCE = new DatapackReloadListener();

    /**
     * Captured from {@link AddReloadListenerEvent} so {@code apply} can access
     * the reloaded registries. {@code volatile} for cross-thread visibility:
     * the event fires on the thread that drives the reload, while
     * {@code apply} runs on the game thread.
     */
    private volatile RegistryAccess registryAccess;

    private DatapackReloadListener() {
    }

    /**
     * Registers this listener on each reload and captures the registry
     * access for use in the {@code apply} phase.
     *
     * @param event the add-reload-listener event (game bus)
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        INSTANCE.registryAccess = event.getRegistryAccess();
        event.addListener(INSTANCE);
    }

    /**
     * No preparation work — the vanilla pipeline has already loaded the
     * registries before the event fires.
     */
    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    /**
     * Reload-phase entry point (game thread). Delegates to
     * {@link #handleReloadComplete(RegistryAccess)} when the registry
     * access was captured.
     */
    @Override
    protected void apply(Void prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (registryAccess != null) {
            handleReloadComplete(registryAccess);
        }
    }

    /**
     * Executes the post-reload logic: log, enter DATAPACK phase,
     * validate-and-summarise, cross-reference validation (D1), check
     * registration conflicts, complete phase, then delegate creative-tab
     * refresh and online-player gun refresh to
     * {@link ReloadBehaviorHandler}.
     *
     * @param access the reloaded registry access (all registries loaded and frozen)
     */
    static void handleReloadComplete(RegistryAccess access) {
        // Collect each registry's entries/keyset exactly once and share that
        // single view across the summary, cross-reference and conflict-check
        // phases (阶段 5 任务 5.1: 同一次 reload 中同一注册表只 collect 一次).
        ReloadSharedEntries shared = new ReloadSharedEntries(access);
        ModularShoot.LOGGER.info(
                "ModularShoot datapack reload complete — running post-reload validation.");
        LoadOrderManager.enterPhase(LoadOrderManager.LoadPhase.DATAPACK);
        List<DatapackLoadSummary> summaries = collectValidationSummaries(shared);
        ModularShoot.LOGGER.info(DatapackLoadSummary.formatAllSummaries(summaries));
        validateCrossReferences(shared);
        checkRegistrationConflicts(shared);
        LoadOrderManager.completePhase(LoadOrderManager.LoadPhase.DATAPACK);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            ReloadBehaviorHandler.onReloadComplete(access, server);
        } else {
            ModularShoot.LOGGER.warn(
                    "MinecraftServer unavailable after datapack reload — skipping creative-tab and player-inventory refresh.");
        }
    }

    /**
     * Builds a per-registry validation summary for all eight framework
     * registries.
     *
     * @param shared the reload's shared registry snapshot
     * @return a list of summaries, one per registry
     */
    private static List<DatapackLoadSummary> collectValidationSummaries(ReloadSharedEntries shared) {
        List<DatapackLoadSummary> summaries = new ArrayList<>();
        summaries.add(summarizeGuns(shared));
        summaries.add(summarizePlugins(shared));
        summaries.add(summarizePluginTypes(shared));
        summaries.add(summarizeTraits(shared));
        summaries.add(summarizeStates(shared));
        summaries.add(summarizeAttributeMeta(shared));
        summaries.add(summarizeVariants(shared));
        summaries.add(summarizeShooters(shared));
        return summaries;
    }

    /**
     * Runs the post-reload cross-reference validation (D1): every reference
     * a datapack entry carries to another framework table or to a vanilla
     * registry is checked, and dangling references emit an explicit
     * {@code WARN} via {@link DatapackErrorHandler#logReferenceWarning}
     * (拼错 ID 显式 WARN，不再静默失效).
     *
     * <p>Covers the four tables with a dedicated validator: guns, plugins,
     * variants and shooters. The {@code attribute_meta} table is
     * <em>not</em> checked
     * here: its {@code binds} validation is exclusively owned by
     * {@link AttributeMetaDatapackLoader#validateBindings} (invoked from
     * {@link #summarizeAttributeMeta} on every reload), so the same bad bind
     * is logged exactly once instead of twice.</p>
     *
     * <p>The two binding tables ({@code gun_items}, {@code plugin_items}) are
     * validated by {@link ItemBindingValidator}: bound item ids must exist in
     * the vanilla item registry, binding targets must exist in the
     * {@code guns}/{@code plugins} tables, and a bound item id may appear in
     * at most one entry (字典序最小 key 胜出, 其余 WARN) (设计规格 物品绑定系统
     * §3.2).</p>
     *
     * <p>Each validator returns immediately on an empty entries map, so a
     * missing framework registry does not cascade into warnings. Variants
     * are validated inside {@link #summarizeVariants} so the summary and its
     * reference warnings share the same entries map.</p>
     *
     * @param shared the reload's shared registry snapshot
     */
    private static void validateCrossReferences(ReloadSharedEntries shared) {
        CrossReferenceValidator.validateGuns(shared,
                shared.entries(ModularShootRegistries.GUNS_KEY));
        CrossReferenceValidator.validatePlugins(shared,
                shared.entries(ModularShootRegistries.PLUGINS_KEY));
        CrossReferenceValidator.validateShooters(shared,
                shared.entries(ModularShootRegistries.SHOOTERS_KEY));
        ItemBindingValidator.validateGunBindings(shared,
                shared.entries(ModularShootRegistries.GUN_ITEMS_KEY));
        ItemBindingValidator.validatePluginBindings(shared,
                shared.entries(ModularShootRegistries.PLUGIN_ITEMS_KEY));
    }

    /**
     * Builds a {@link DatapackLoadSummary} from validation results.
     *
     * <p>In the post-reload context every visible entry has already been
     * parsed and registered by the vanilla {@code RegistryDataLoader}, so
     * {@code failed} is always {@code 0}: a single parse failure aborts the
     * entire registry load with an {@code IllegalStateException} and this
     * listener never runs for that registry (see the
     * {@link DatapackLoadSummary} class javadoc). Entries that did not pass
     * the framework's post-load validation cleanly are counted as warnings
     * here.</p>
     *
     * @param name    the human-readable registry name (e.g. "gun definitions")
     * @param total   the total number of entries
     * @param results the validation results
     * @param isClean predicate returning {@code true} for entries that passed
     *                validation without warnings
     * @param <T>     the validation result type
     * @return a summary with {@code failed = 0} (architectural guarantee:
     *         parse failures abort the vanilla pipeline before this listener
     *         runs)
     */
    private static <T> DatapackLoadSummary summarize(
            String name, int total, Collection<T> results, Predicate<T> isClean) {
        int succeeded = (int) results.stream().filter(isClean).count();
        int warnings = total - succeeded;
        // failed 恒为 0：单条解析失败即整表中断（原版 RegistryDataLoader），本汇总不产出；
        // 详见 DatapackLoadSummary 类 javadoc
        return DatapackLoadSummary.of(name, total, succeeded, 0, warnings);
    }

    // ──────────────── Per-registry summary helpers ────────────────

    private static DatapackLoadSummary summarizeGuns(ReloadSharedEntries shared) {
        Map<ResourceLocation, GunDefinition> entries =
                shared.entries(ModularShootRegistries.GUNS_KEY);
        Map<ResourceLocation, GunDatapackLoader.GunValidation> results =
                GunDatapackLoader.validateGuns(entries);
        return summarize("gun definitions", entries.size(), results.values(),
                GunDatapackLoader.GunValidation::valid);
    }

    private static DatapackLoadSummary summarizePlugins(ReloadSharedEntries shared) {
        Map<ResourceLocation, PluginValidationResult> results =
                PluginDatapackLoader.validateEntries(shared.entries(ModularShootRegistries.PLUGINS_KEY));
        results.forEach(DatapackReloadListener::logPluginValidation);
        return summarize("plugin definitions", results.size(), results.values(),
                r -> r.valid() && r.warnings().isEmpty());
    }

    private static DatapackLoadSummary summarizePluginTypes(ReloadSharedEntries shared) {
        Map<ResourceLocation, PluginTypeDatapackLoader.TypeValidation> results =
                PluginTypeDatapackLoader.validateEntries(
                        shared.entries(ModularShootRegistries.PLUGIN_TYPES_KEY));
        return summarize("plugin type definitions", results.size(), results.values(),
                PluginTypeDatapackLoader.TypeValidation::tagsPresent);
    }

    private static DatapackLoadSummary summarizeTraits(ReloadSharedEntries shared) {
        Map<ResourceLocation, Trait> entries =
                shared.entries(ModularShootRegistries.TRAITS_KEY);
        Map<ResourceLocation, TraitDatapackLoader.TraitValidation> results =
                TraitDatapackLoader.validateTraits(entries);
        return summarize("trait definitions", entries.size(), results.values(),
                TraitDatapackLoader.TraitValidation::valid);
    }

    private static DatapackLoadSummary summarizeStates(ReloadSharedEntries shared) {
        Map<ResourceLocation, StateDefinition> entries =
                shared.entries(ModularShootRegistries.STATES_KEY);
        List<StateDatapackLoader.StateValidation> results = entries.entrySet().stream()
                .map(e -> StateDatapackLoader.validateState(e.getKey(), e.getValue()))
                .toList();
        return summarize("state definitions", results.size(), results,
                StateDatapackLoader.StateValidation::valid);
    }

    private static DatapackLoadSummary summarizeAttributeMeta(ReloadSharedEntries shared) {
        Map<ResourceLocation, AttributeMeta> entries =
                shared.entries(ModularShootRegistries.ATTRIBUTE_META_KEY);
        Map<ResourceLocation, AttributeMetaDatapackLoader.BindingValidation> results =
                AttributeMetaDatapackLoader.validateBindings(entries);
        return summarize("attribute metadata", entries.size(), results.values(),
                AttributeMetaDatapackLoader.BindingValidation::bindsRegistered);
    }

    private static DatapackLoadSummary summarizeVariants(ReloadSharedEntries shared) {
        Map<ResourceLocation, VariantDefinition> entries =
                shared.entries(ModularShootRegistries.VARIANTS_KEY);
        CrossReferenceValidator.validateVariants(shared, entries);
        logNonFiniteWeights(entries);
        return summarize("variant definitions", entries.size(), entries.values(),
                v -> Double.isFinite(v.baseWeight()));
    }

    /**
     * Summarises the {@code shooters} registry (follows the
     * {@link #summarizeGuns} collect-entries pattern).
     *
     * <p>Shooters have no dedicated per-entry validation loader: their
     * {@code attribute_binds} references are checked by
     * {@link CrossReferenceValidator#validateShooters} from
     * {@link #validateCrossReferences} on every reload, so every loaded entry
     * counts as clean here (warnings surface through the cross-reference
     * channel instead).</p>
     *
     * @param shared the reload's shared registry snapshot
     * @return the shooter summary
     */
    private static DatapackLoadSummary summarizeShooters(ReloadSharedEntries shared) {
        Map<ResourceLocation, ShooterDefinition> entries =
                shared.entries(ModularShootRegistries.SHOOTERS_KEY);
        return summarize("shooter definitions", entries.size(), entries.values(), s -> true);
    }

    /**
     * Logs a {@code WARN} per variant whose base weight is not finite
     * (NaN or Infinity), mirroring {@link GunDatapackLoader}'s stats finite
     * check. A non-finite weight corrupts the per-shot pool probabilities,
     * so it is surfaced as a reference warning.
     *
     * @param entries the variant id to {@link VariantDefinition} entries
     */
    private static void logNonFiniteWeights(Map<ResourceLocation, VariantDefinition> entries) {
        for (Map.Entry<ResourceLocation, VariantDefinition> entry : entries.entrySet()) {
            final double weight = entry.getValue().baseWeight();
            if (!Double.isFinite(weight)) {
                DatapackErrorHandler.logReferenceWarning(entry.getKey(),
                        "base_weight is not finite (" + weight
                                + "); entry registered with warning (degradation deferred).");
            }
        }
    }

    // ──────────────── Registration conflict checks (A-01) ────────────────

    /**
     * Checks all ten framework registries for conflicts between datapack
     * entries and ids claimed by the Java API via
     * {@link RegistrationCoordinator#markJavaApiRegistered}.
     *
     * <p>For each registry, the datapack entry ids are compared against the
     * Java-API-claimed ids. When a conflict is found,
     * {@link RegistrationCoordinator#attemptDatapackOverride} is called,
     * which logs a {@code WARN} and returns {@code false} (denying the
     * override). This implements the design rule "Java API takes priority
     * over datapack" (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>The {@code variants} registry has no Java API write path, so its
     * conflict set is always empty; it is included only for consistency
     * (变体仅由数据包 JSON 注册). The {@code shooters} registry has a Java
     * API write path ({@link ShooterRegistry#registerShooter}), so it is
     * checked like the others: a datapack JSON overriding a Java-API shooter
     * id is shadowed at the query layer and reported with a {@code WARN}.
     * The two binding tables ({@code gun_items}, {@code plugin_items}) are
     * also checked: {@code registerBinding} calls
     * {@link RegistrationCoordinator#markJavaApiRegistered} for them, so
     * omitting them would leave the javadoc-promised {@code WARN} dead
     * (稳健性修复).</p>
     *
     * @param shared the reload's shared registry snapshot
     */
    private static void checkRegistrationConflicts(ReloadSharedEntries shared) {
        checkConflictsForRegistry(shared, ModularShootRegistries.GUNS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.PLUGINS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.PLUGIN_TYPES_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.TRAITS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.STATES_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.ATTRIBUTE_META_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.VARIANTS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.SHOOTERS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.GUN_ITEMS_KEY);
        checkConflictsForRegistry(shared, ModularShootRegistries.PLUGIN_ITEMS_KEY);
    }

    /**
     * Checks one registry for Java-API-vs-datapack id conflicts.
     *
     * <p>Uses {@link RegistrationCoordinator#findConflicts} to identify all
     * conflicting ids in one pass, then calls
     * {@link RegistrationCoordinator#attemptDatapackOverride} for each
     * conflict to emit the {@code WARN} log line (设计文档 §容错优先
     * &mdash; collect all conflicts, then report).</p>
     *
     * <h2>Semantic note &mdash; return value intentionally ignored</h2>
     * <p>{@link RegistrationCoordinator#attemptDatapackOverride} returns a
     * {@code boolean} ({@code true} = datapack registration may proceed,
     * {@code false} = override denied). The return value is <strong>intentionally
     * ignored</strong> here. This is a deliberate architectural deviation from
     * the naive reading of the design rule "Java API takes priority over
     * datapack" (设计文档 §注册冲突与覆盖):</p>
     * <ul>
     *   <li>By the time this post-reload check runs, the vanilla
     *       {@code RegistryDataLoader} has already written both the
     *       Java-API entry <em>and</em> the datapack entry into the same
     *       registry. The vanilla pipeline does not consult
     *       {@link RegistrationCoordinator} before writing, so the
     *       datapack entry is physically present in the registry.</li>
     *   <li>"Datapack registration ignored" is therefore <strong>not</strong>
     *       implemented by preventing the registry write (that already
     *       happened). Instead, it is implemented at the <em>query layer</em>:
     *       runtime lookups consult the Java-API-registered definition first
     *       (via the Java API Map maintained by the registry classes), so the
     *       datapack entry is shadowed and never observed by gameplay code.</li>
     *   <li>The sole purpose of calling {@code attemptDatapackOverride} here
     *       is to emit the {@code WARN} log line so the operator is informed
     *       that a datapack attempted to override a Java-API entry. The
     *       {@code false} return value confirms the denial for logging
     *       purposes; no further action is needed because the query-layer
     *       shadowing already enforces the priority rule.</li>
     * </ul>
     *
     * @param shared      the reload's shared registry snapshot
     * @param registryKey the registry to check
     * @param <T>         the registry value type
     */
    private static <T> void checkConflictsForRegistry(
            ReloadSharedEntries shared, ResourceKey<Registry<T>> registryKey) {
        Set<ResourceLocation> datapackIds = shared.keys(registryKey);
        Set<ResourceLocation> conflicts =
                RegistrationCoordinator.findConflicts(registryKey, datapackIds);
        for (ResourceLocation conflictId : conflicts) {
            RegistrationCoordinator.attemptDatapackOverride(registryKey, conflictId);
        }
    }

    // ──────────────── Plugin validation logging (A-02) ────────────────

    /**
     * Routes a {@link PluginValidationResult} through
     * {@link DatapackErrorHandler}.
     *
     * <p>{@link PluginDatapackLoader} is a pure validator that returns
     * results without logging. This method consumes those results and emits
     * the appropriate log lines: {@code ERROR} for fatal errors (null
     * modifier operation) and {@code WARN} for non-fatal warnings (empty
     * tags), keeping validation and logging concerns separated
     * (设计文档 §数据包JSON加载失败错误处理).</p>
     *
     * @param pluginId the plugin entry id
     * @param result   the validation result produced by
     *                 {@link PluginDatapackLoader#validateLoadedPlugin}
     */
    private static void logPluginValidation(
            ResourceLocation pluginId, PluginValidationResult result) {
        final String filePath = buildPluginFilePath(pluginId);
        for (String error : result.errors()) {
            DatapackErrorHandler.logParseError(filePath, error);
        }
        for (String warning : result.warnings()) {
            DatapackErrorHandler.logReferenceWarning(pluginId, warning);
        }
    }

    /**
     * Builds the datapack file path for a plugin entry id, for use in
     * {@link DatapackErrorHandler#logParseError} messages.
     *
     * @param pluginId the plugin entry id
     * @return the file path string (e.g.
     *         {@code data/modularshoot/modularshoot/plugins/foo.json})
     */
    private static String buildPluginFilePath(ResourceLocation pluginId) {
        return "data/" + pluginId.getNamespace()
                + "/modularshoot/plugins/" + pluginId.getPath() + ".json";
    }
}
