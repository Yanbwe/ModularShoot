package org.yanbwe.modularshoot.datapack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

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
 *   <li>Delegates creative-tab refresh to {@link ReloadBehaviorHandler}.</li>
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
     * Executes the four-step post-reload logic: log, enter DATAPACK phase,
     * validate-and-summarise, complete phase, then delegate creative-tab
     * refresh to {@link ReloadBehaviorHandler}.
     *
     * @param access the reloaded registry access (all registries loaded and frozen)
     */
    private static void handleReloadComplete(RegistryAccess access) {
        ModularShoot.LOGGER.info(
                "ModularShoot datapack reload complete — running post-reload validation.");
        LoadOrderManager.enterPhase(LoadOrderManager.LoadPhase.DATAPACK);
        List<DatapackLoadSummary> summaries = collectValidationSummaries(access);
        ModularShoot.LOGGER.info(DatapackLoadSummary.formatAllSummaries(summaries));
        LoadOrderManager.completePhase(LoadOrderManager.LoadPhase.DATAPACK);
        ReloadBehaviorHandler.onReloadComplete(access);
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
     * {@code failed} is always {@code 0} (parse failures are handled during
     * the vanilla pipeline, not here). Entries that did not pass validation
     * cleanly are counted as warnings.</p>
     *
     * @param name    the human-readable registry name (e.g. "枪械定义")
     * @param total   the total number of entries
     * @param results the validation results
     * @param isClean predicate returning {@code true} for entries that passed
     *                validation without warnings
     * @param <T>     the validation result type
     * @return a summary with {@code failed = 0}
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
}
