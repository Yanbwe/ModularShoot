package org.yanbwe.modularshoot.datapack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

/**
 * NeoForge reload listener for framework-specific post-reload logic
 * (设计文档 §/reload 重载行为, line 2305).
 *
 * <p>NeoForge's {@code DataPackRegistryEvent} already handles automatic
 * reloading of the six dynamic registries ({@code guns}, {@code plugins},
 * {@code plugin_types}, {@code traits}, {@code states},
 * {@code attribute_meta}). This listener complements that with
 * framework-specific post-reload logic that runs in the reload phase
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
     * validate-and-summarise, check registration conflicts and missing
     * resources, complete phase, then delegate creative-tab refresh and
     * online-player gun refresh to {@link ReloadBehaviorHandler}.
     *
     * @param access the reloaded registry access (all registries loaded and frozen)
     */
    private static void handleReloadComplete(RegistryAccess access) {
        ModularShoot.LOGGER.info(
                "ModularShoot datapack reload complete — running post-reload validation.");
        LoadOrderManager.enterPhase(LoadOrderManager.LoadPhase.DATAPACK);
        List<DatapackLoadSummary> summaries = collectValidationSummaries(access);
        ModularShoot.LOGGER.info(DatapackLoadSummary.formatAllSummaries(summaries));
        checkRegistrationConflicts(access);
        checkMissingResources(access);
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
     * Builds a per-registry validation summary for all six framework
     * registries.
     *
     * @param access the reloaded registry access
     * @return a list of summaries, one per registry
     */
    private static List<DatapackLoadSummary> collectValidationSummaries(RegistryAccess access) {
        List<DatapackLoadSummary> summaries = new ArrayList<>();
        summaries.add(summarizeGuns(access));
        summaries.add(summarizePlugins(access));
        summaries.add(summarizePluginTypes(access));
        summaries.add(summarizeTraits(access));
        summaries.add(summarizeStates(access));
        summaries.add(summarizeAttributeMeta(access));
        return summaries;
    }

    /**
     * Extracts all entries from a dynamic registry into an unmodifiable
     * map keyed by id.
     *
     * <p>Used for the three loaders that do not offer a
     * {@code RegistryAccess}-based convenience method
     * ({@link GunDatapackLoader}, {@link TraitDatapackLoader},
     * {@link AttributeMetaDatapackLoader}).</p>
     *
     * @param access the registry access
     * @param key    the registry key
     * @param <T>    the registry value type
     * @return an unmodifiable map of id to entry; empty when the registry is absent
     */
    private static <T> Map<ResourceLocation, T> collectEntries(
            RegistryAccess access, ResourceKey<Registry<T>> key) {
        return access.registry(key)
                .map(reg -> reg.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                e -> e.getKey().location(),
                                e -> e.getValue())))
                .orElse(Map.of());
    }

    /**
     * Builds a {@link DatapackLoadSummary} from validation results.
     *
     * <p>In the post-reload context all entries are already registered, so
     * {@code failed} is always {@code 0}. This is an architectural
     * consequence of NeoForge's {@code DataPackRegistryEvent} mechanism:
     * the vanilla {@code RegistryDataLoader} parses every JSON entry
     * <em>before</em> this listener fires. Each entry is isolated by its
     * own try-catch inside {@code loadContentsFromManager}, so a single
     * parse failure does not abort the remaining entries; however, when
     * <em>any</em> entry fails, {@code RegistryDataLoader.load()} throws
     * an {@code IllegalStateException} and the entire registry load is
     * aborted &mdash; this post-reload listener never runs for that
     * registry. Therefore, when this method <em>is</em> reached, every
     * visible entry has already been parsed and registered successfully,
     * and the parse-failure count is necessarily zero.</p>
     *
     * <p>Parse failures are logged by the vanilla pipeline itself (via
     * {@code RegistryDataLoader.logErrors}), not by this framework. The
     * design-document summary format "共加载 42 个枪械定义，3 个失败"
     * (设计文档 §数据包 JSON 加载失败的错误处理) describes the
     * operator-facing intent; under the NeoForge architecture the actual
     * failure count is surfaced by the vanilla pipeline's error log, which
     * precedes this summary. Entries that did not pass the framework's
     * post-load validation cleanly are counted as warnings here.</p>
     *
     * @param name    the human-readable registry name (e.g. "枪械定义")
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
        return DatapackLoadSummary.of(name, total, succeeded, 0, warnings);
    }

    // ──────────────── Per-registry summary helpers ────────────────

    private static DatapackLoadSummary summarizeGuns(RegistryAccess access) {
        Map<ResourceLocation, GunDefinition> entries =
                collectEntries(access, ModularShootRegistries.GUNS_KEY);
        Map<ResourceLocation, GunDatapackLoader.GunValidation> results =
                GunDatapackLoader.validateGuns(entries);
        return summarize("枪械定义", entries.size(), results.values(),
                GunDatapackLoader.GunValidation::valid);
    }

    private static DatapackLoadSummary summarizePlugins(RegistryAccess access) {
        Map<ResourceLocation, PluginValidationResult> results =
                PluginDatapackLoader.validateLoadedPlugins(access);
        results.forEach(DatapackReloadListener::logPluginValidation);
        return summarize("插件定义", results.size(), results.values(),
                r -> r.valid() && r.warnings().isEmpty());
    }

    private static DatapackLoadSummary summarizePluginTypes(RegistryAccess access) {
        Map<ResourceLocation, PluginTypeDatapackLoader.TypeValidation> results =
                PluginTypeDatapackLoader.validateLoadedTypes(access);
        return summarize("插件类型定义", results.size(), results.values(),
                PluginTypeDatapackLoader.TypeValidation::tagsPresent);
    }

    private static DatapackLoadSummary summarizeTraits(RegistryAccess access) {
        Map<ResourceLocation, Trait> entries =
                collectEntries(access, ModularShootRegistries.TRAITS_KEY);
        Map<ResourceLocation, TraitDatapackLoader.TraitValidation> results =
                TraitDatapackLoader.validateTraits(entries);
        return summarize("特性定义", entries.size(), results.values(),
                TraitDatapackLoader.TraitValidation::valid);
    }

    private static DatapackLoadSummary summarizeStates(RegistryAccess access) {
        List<StateDatapackLoader.StateValidation> results =
                StateDatapackLoader.validateAllStates(access);
        return summarize("状态定义", results.size(), results,
                StateDatapackLoader.StateValidation::valid);
    }

    private static DatapackLoadSummary summarizeAttributeMeta(RegistryAccess access) {
        Map<ResourceLocation, AttributeMeta> entries =
                collectEntries(access, ModularShootRegistries.ATTRIBUTE_META_KEY);
        Map<ResourceLocation, AttributeMetaDatapackLoader.BindingValidation> results =
                AttributeMetaDatapackLoader.validateBindings(entries);
        return summarize("属性元数据", entries.size(), results.values(),
                AttributeMetaDatapackLoader.BindingValidation::bindsRegistered);
    }

    // ──────────────── Registration conflict checks (A-01) ────────────────

    /**
     * Checks all six framework registries for conflicts between datapack
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
     * @param access the reloaded registry access
     */
    private static void checkRegistrationConflicts(RegistryAccess access) {
        checkConflictsForRegistry(access, ModularShootRegistries.GUNS_KEY);
        checkConflictsForRegistry(access, ModularShootRegistries.PLUGINS_KEY);
        checkConflictsForRegistry(access, ModularShootRegistries.PLUGIN_TYPES_KEY);
        checkConflictsForRegistry(access, ModularShootRegistries.TRAITS_KEY);
        checkConflictsForRegistry(access, ModularShootRegistries.STATES_KEY);
        checkConflictsForRegistry(access, ModularShootRegistries.ATTRIBUTE_META_KEY);
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
     * @param access      the reloaded registry access
     * @param registryKey the registry to check
     * @param <T>         the registry value type
     */
    private static <T> void checkConflictsForRegistry(
            RegistryAccess access, ResourceKey<Registry<T>> registryKey) {
        Set<ResourceLocation> datapackIds = collectEntries(access, registryKey).keySet();
        Set<ResourceLocation> conflicts =
                RegistrationCoordinator.findConflicts(registryKey, datapackIds);
        for (ResourceLocation conflictId : conflicts) {
            RegistrationCoordinator.attemptDatapackOverride(registryKey, conflictId);
        }
    }

    // ──────────────── Missing resource checks (A-02) ────────────────

    /**
     * Checks registered entries for obviously invalid resource paths
     * (null or empty).
     *
     * <p>The server cannot preload client-side assets (textures, models), so
     * this check only verifies that required resource path fields are
     * non-null. When a null path is found,
     * {@link DatapackErrorHandler#logMissingResource} is called so the
     * operator is warned that the runtime will fall back to a default asset
     * (设计文档 line 2380).</p>
     *
     * @param access the reloaded registry access
     */
    private static void checkMissingResources(RegistryAccess access) {
        checkGunTextures(access);
    }

    /**
     * Checks gun definitions for null texture paths.
     *
     * @param access the reloaded registry access
     */
    private static void checkGunTextures(RegistryAccess access) {
        Map<ResourceLocation, GunDefinition> guns =
                collectEntries(access, ModularShootRegistries.GUNS_KEY);
        for (Map.Entry<ResourceLocation, GunDefinition> entry : guns.entrySet()) {
            final GunDefinition gun = entry.getValue();
            if (gun.texture() == null) {
                DatapackErrorHandler.logMissingResource(entry.getKey(), "texture (null)");
            }
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
