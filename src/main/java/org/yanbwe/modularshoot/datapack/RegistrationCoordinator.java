package org.yanbwe.modularshoot.datapack;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised coordinator for resolving registration conflicts between the
 * Java API and the datapack JSON pipeline (设计文档 §注册冲突与覆盖).
 *
 * <p>The framework's six dynamic registries ({@code guns}, {@code plugins},
 * {@code plugin_types}, {@code traits}, {@code states}, {@code attribute_meta})
 * can be populated from two sources:</p>
 * <ol>
 *   <li><b>Java API</b> &mdash; entries registered programmatically by the
 *       framework or by add-on mods during mod initialisation.</li>
 *   <li><b>Datapack JSON</b> &mdash; entries loaded from
 *       {@code data/<namespace>/modularshoot/<registry>/} during world load
 *       or {@code /reload}.</li>
 * </ol>
 *
 * <p>When the same id appears in both sources, the <b>Java API wins</b>: the
 * datapack entry is ignored and a {@code WARN} is logged. Datapack entries
 * can never override a Java-API-registered id (设计文档 §注册冲突与覆盖,
 * line 2289).</p>
 *
 * <h2>Conflict rules implemented</h2>
 * <ul>
 *   <li>Same id in Java API <em>and</em> datapack &rarr; Java API wins,
 *       datapack ignored, {@code WARN} logged.</li>
 *   <li>Same id across multiple datapacks &rarr; later datapack (by
 *       {@code pack.mcmeta} priority) overrides earlier. This is handled
 *       automatically by NeoForge's {@code RegistryDataLoader}; this
 *       coordinator does <strong>not</strong> intervene in datapack-vs-
 *       datapack conflicts.</li>
 *   <li>Datapack overriding a Java-API entry &rarr; not supported; the
 *       attempt is rejected with a {@code WARN}.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * <p>Java API registration happens during mod init (main thread), while
 * datapack loading happens during world load / {@code /reload} (server
 * thread). The id sets are stored in {@link ConcurrentHashMap}-backed
 * {@link ConcurrentHashMap#newKeySet() key sets} so that reads from the
 * datapack loader thread safely observe writes from the mod-init thread
 * (设计文档 §注册表并发策略).</p>
 *
 * <p>This class is a pure coordinator: it only <em>tracks</em> which ids
 * the Java API has claimed and <em>answers</em> whether a datapack
 * registration is allowed. It does not perform the actual registry write
 * &mdash; that remains the caller's responsibility. All methods are static
 * and the class is not instantiable. Each method is under 50 lines
 * (设计文档 §函数&lt;50行).</p>
 *
 * @see LoadOrderManager
 * @see org.yanbwe.modularshoot.registry.ModularShootRegistries
 */
public final class RegistrationCoordinator {
    /** Dedicated subsystem logger for registration conflict resolution. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/Registration");

    /**
     * Per-registry set of ids claimed by the Java API.
     *
     * <p>Keyed by the registry's {@link ResourceLocation} (e.g.
     * {@code modularshoot:guns}) to avoid generic-erasure issues with
     * {@code ResourceKey<Registry<T>>}. The inner set is a
     * {@link ConcurrentHashMap#newKeySet() thread-safe key set} so that
     * concurrent reads from the datapack loader are safe.</p>
     */
    private static final ConcurrentHashMap<ResourceLocation, Set<ResourceLocation>> JAVA_API_IDS =
            new ConcurrentHashMap<>();

    private RegistrationCoordinator() {
    }

    /**
     * Marks an id as registered by the Java API.
     *
     * <p>Must be called during the {@link LoadOrderManager.LoadPhase#JAVA_API}
     * phase, before datapack loading begins. After this call,
     * {@link #attemptDatapackOverride} will return {@code false} for the
     * same registry + id, preventing datapack JSON from overriding the
     * Java-API entry (设计文档 §注册冲突与覆盖).</p>
     *
     * @param registryKey the registry the id is registered in
     * @param id          the entry id claimed by the Java API
     * @param <T>         the registry value type
     */
    public static <T> void markJavaApiRegistered(
            ResourceKey<Registry<T>> registryKey, ResourceLocation id) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(id, "id");
        JAVA_API_IDS.computeIfAbsent(registryKey.location(),
                k -> ConcurrentHashMap.newKeySet()).add(id);
    }

    /**
     * Checks whether an id has been claimed by the Java API in the given
     * registry.
     *
     * <p>This is a read-only query that does not log. Use
     * {@link #attemptDatapackOverride} when you need the coordinator to
     * emit a {@code WARN} on conflict.</p>
     *
     * @param registryKey the registry to check
     * @param id          the entry id to test
     * @param <T>         the registry value type
     * @return {@code true} if the id was marked via
     *         {@link #markJavaApiRegistered}; {@code false} otherwise
     */
    public static <T> boolean isJavaApiRegistered(
            ResourceKey<Registry<T>> registryKey, ResourceLocation id) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(id, "id");
        final Set<ResourceLocation> ids = JAVA_API_IDS.get(registryKey.location());
        return ids != null && ids.contains(id);
    }

    /**
     * Asks the coordinator whether a datapack registration may proceed for
     * the given registry + id.
     *
     * <p>Returns {@code false} (denying the override) when the id is already
     * claimed by the Java API, and logs a {@code WARN} describing the
     * conflict. Returns {@code true} when no Java-API entry claims the id,
     * allowing the datapack loader to proceed (设计文档 §注册冲突与覆盖,
     * line 2289).</p>
     *
     * <p>Datapack-vs-datapack conflicts are <strong>not</strong> handled
     * here; NeoForge's {@code RegistryDataLoader} resolves those by
     * {@code pack.mcmeta} priority automatically.</p>
     *
     * @param registryKey the registry the datapack entry targets
     * @param id          the datapack entry id
     * @param <T>         the registry value type
     * @return {@code true} if the datapack registration may proceed;
     *         {@code false} if a Java-API entry claims the id (override
     *         denied)
     */
    public static <T> boolean attemptDatapackOverride(
            ResourceKey<Registry<T>> registryKey, ResourceLocation id) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(id, "id");
        if (isJavaApiRegistered(registryKey, id)) {
            LOGGER.warn("Datapack registration of '{}' in registry '{}' was ignored: "
                    + "id already registered by Java API (Java API takes priority over "
                    + "datapack, 设计文档 §注册冲突与覆盖).",
                    id, registryKey.location());
            return false;
        }
        return true;
    }

    /**
     * Returns a snapshot of all ids claimed by the Java API in the given
     * registry.
     *
     * <p>The returned set is an unmodifiable copy; changes to the
     * coordinator's internal state after this call are not reflected in
     * the returned set.</p>
     *
     * @param registryKey the registry to inspect
     * @param <T>         the registry value type
     * @return an unmodifiable snapshot of Java-API-claimed ids; empty when
     *         no Java-API entries have been marked for this registry
     */
    public static <T> Set<ResourceLocation> getJavaApiRegisteredIds(
            ResourceKey<Registry<T>> registryKey) {
        Objects.requireNonNull(registryKey, "registryKey");
        final Set<ResourceLocation> ids = JAVA_API_IDS.get(registryKey.location());
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    /**
     * Finds which datapack ids would conflict with existing Java-API
     * entries in the given registry.
     *
     * <p>This is a pure query: it does not log and does not modify state.
     * Useful for batch validation before attempting individual overrides
     * (设计文档 §容错优先 &mdash; collect all conflicts, then report).</p>
     *
     * @param registryKey  the registry to check
     * @param datapackIds  the set of ids the datapack intends to register
     * @param <T>          the registry value type
     * @return an unmodifiable set of ids that appear in both the datapack
     *         set and the Java-API-claimed set; empty when there are no
     *         conflicts
     */
    public static <T> Set<ResourceLocation> findConflicts(
            ResourceKey<Registry<T>> registryKey, Set<ResourceLocation> datapackIds) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(datapackIds, "datapackIds");
        final Set<ResourceLocation> javaApiIds = JAVA_API_IDS.get(registryKey.location());
        if (javaApiIds == null || javaApiIds.isEmpty()) {
            return Set.of();
        }
        return datapackIds.stream()
                .filter(javaApiIds::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Clears all Java-API-claimed ids for one registry.
     *
     * <p>Intended for testing or for a full framework reset. Under normal
     * runtime conditions the Java-API ids are stable for the lifetime of
     * the process and should not be cleared.</p>
     *
     * @param registryKey the registry whose Java-API claims to clear
     * @param <T>         the registry value type
     */
    public static <T> void clearRegistry(ResourceKey<Registry<T>> registryKey) {
        Objects.requireNonNull(registryKey, "registryKey");
        JAVA_API_IDS.remove(registryKey.location());
    }

    /**
     * Clears all Java-API-claimed ids across all registries.
     *
     * <p>Intended for testing or for a full framework reset. Under normal
     * runtime conditions the Java-API ids are stable for the lifetime of
     * the process and should not be cleared.</p>
     */
    public static void clearAll() {
        JAVA_API_IDS.clear();
    }
}
