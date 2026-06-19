package org.yanbwe.modularshoot.registry;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Central declaration of the framework's six dynamic datapack registries.
 *
 * <p>Five framework registries ({@code guns}, {@code plugins},
 * {@code plugin_types}, {@code traits}, {@code states}) and one attribute
 * metadata table ({@code attribute_meta}) are registered through NeoForge's
 * {@link DataPackRegistryEvent.NewRegistry} mechanism rather than static
 * {@code DeferredRegister}s. This keeps them hot-reloadable via {@code /reload}
 * and allows datapack JSON to populate the same registry instances used by the
 * Java API.</p>
 *
 * <p>All six registries are registered with a non-null network codec so their
 * contents are synced to clients on connect (clients must have the mod to join
 * a server that uses these registries).</p>
 *
 * <p>Data JSONs are loaded from
 * {@code data/<datapack_namespace>/modularshoot/<registry_path>/}.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootRegistries {
    private ModularShootRegistries() {}

    /** Registry key for {@code modularshoot:guns} — gun definitions. */
    public static final ResourceKey<Registry<GunDefinition>> GUNS_KEY =
            createRegistryKey("guns");

    /** Registry key for {@code modularshoot:plugins} — plugin definitions. */
    public static final ResourceKey<Registry<PluginDefinition>> PLUGINS_KEY =
            createRegistryKey("plugins");

    /** Registry key for {@code modularshoot:plugin_types} — plugin categories. */
    public static final ResourceKey<Registry<PluginTypeDefinition>> PLUGIN_TYPES_KEY =
            createRegistryKey("plugin_types");

    /** Registry key for {@code modularshoot:traits} — boolean trait definitions. */
    public static final ResourceKey<Registry<Trait>> TRAITS_KEY =
            createRegistryKey("traits");

    /** Registry key for {@code modularshoot:states} — persistent state definitions. */
    public static final ResourceKey<Registry<StateDefinition>> STATES_KEY =
            createRegistryKey("states");

    /** Registry key for {@code modularshoot:attribute_meta} — attribute metadata. */
    public static final ResourceKey<Registry<AttributeMeta>> ATTRIBUTE_META_KEY =
            createRegistryKey("attribute_meta");

    /**
     * Builds a root registry key under the mod's namespace.
     *
     * @param path the registry path (e.g. {@code "guns"})
     * @param <T>  the registry value type
     * @return a new {@link ResourceKey} pointing at {@code modularshoot:<path>}
     */
    private static <T> ResourceKey<Registry<T>> createRegistryKey(String path) {
        return ResourceKey.createRegistryKey(
                ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, path));
    }

    /**
     * Registers all six framework datapack registries.
     *
     * <p>Each registry is registered with its codec as both the load codec and
     * the network codec, so entries are synced to clients. This event fires on
     * the mod event bus on both logical sides.</p>
     *
     * @param event the datapack registry registration event
     */
    @SubscribeEvent
    public static void onDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        register(event, GUNS_KEY, GunDefinition.CODEC);
        register(event, PLUGINS_KEY, PluginDefinition.CODEC);
        register(event, PLUGIN_TYPES_KEY, PluginTypeDefinition.CODEC);
        register(event, TRAITS_KEY, Trait.CODEC);
        register(event, STATES_KEY, StateDefinition.CODEC);
        register(event, ATTRIBUTE_META_KEY, AttributeMeta.CODEC);
    }

    /**
     * Registers a single datapack registry with client syncing enabled.
     *
     * @param event  the event to register through
     * @param key    the registry key
     * @param codec  the codec used for both datapack loading and network sync
     * @param <T>    the registry value type
     */
    private static <T> void register(
            DataPackRegistryEvent.NewRegistry event,
            ResourceKey<Registry<T>> key,
            Codec<T> codec) {
        event.dataPackRegistry(key, codec, codec);
    }
}
