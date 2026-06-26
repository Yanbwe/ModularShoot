package org.yanbwe.modularshoot.datapack;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.creative.ModularShootCreativeTabs;

/**
 * Handles framework-specific behaviours that must run after a datapack
 * reload completes (设计文档 §/reload 重载行为, line 2305).
 *
 * <p>After {@code /reload}, the six dynamic registries are automatically
 * repopulated by NeoForge's {@code DataPackRegistryEvent} mechanism.
 * However, the creative mode tab's cached item list is not automatically
 * rebuilt. This handler triggers a rebuild so that items picked from the
 * tab use the newly loaded definitions.</p>
 *
 * <h2>Creative-tab refresh mechanism</h2>
 * <p>The rebuild is triggered by calling
 * {@link CreativeModeTab#buildContents(CreativeModeTab.ItemDisplayParameters)}
 * on the framework tab. NeoForge patches this method to fire
 * {@link net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent},
 * which {@link ModularShootCreativeTabs#onBuildCreativeTab} handles to
 * enumerate gun and plugin variants from the reloaded registries.</p>
 *
 * <h2>Side behaviour</h2>
 * <p>On an integrated server (singleplayer), this rebuild immediately
 * affects the shared client tab. On a dedicated server, clients must
 * reconnect to receive the updated registries; their tabs rebuild when
 * the creative inventory is next opened.</p>
 *
 * <p>This class is not instantiable. All methods are static and each is
 * under 50 lines (设计文档 §函数&lt;50行).</p>
 *
 * @see DatapackReloadListener
 * @see ModularShootCreativeTabs
 */
public final class ReloadBehaviorHandler {

    private ReloadBehaviorHandler() {
    }

    /**
     * Called after the datapack reload completes. Triggers creative-tab
     * rebuild and any other framework-specific post-reload behaviours.
     *
     * @param registryAccess the reloaded registry access (all registries
     *                       loaded and frozen)
     */
    public static void onReloadComplete(RegistryAccess registryAccess) {
        refreshCreativeTab(registryAccess);
    }

    /**
     * Rebuilds the framework creative tab so that items picked from it
     * use the newly loaded definitions.
     *
     * <p>The {@link CreativeModeTab.ItemDisplayParameters} are constructed
     * with an empty {@link FeatureFlagSet} (the framework's items are not
     * feature-gated) and the reloaded {@link RegistryAccess} as the
     * {@link net.minecraft.core.HolderLookup.Provider}. Calling
     * {@link CreativeModeTab#buildContents} fires
     * {@link net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent}
     * via NeoForge's {@code EventHooks} patch, which
     * {@link ModularShootCreativeTabs#onBuildCreativeTab} handles to
     * populate the tab from the current registries.</p>
     *
     * @param registryAccess the reloaded registry access, used as the
     *                       {@link net.minecraft.core.HolderLookup.Provider}
     *                       for item-display parameter construction
     */
    private static void refreshCreativeTab(RegistryAccess registryAccess) {
        CreativeModeTab tab = ModularShootCreativeTabs.MODULARSHOOT_TAB.get();
        CreativeModeTab.ItemDisplayParameters params = new CreativeModeTab.ItemDisplayParameters(
                FeatureFlagSet.of(), false, registryAccess);
        tab.buildContents(params);
        ModularShoot.LOGGER.info(
                "ModularShoot creative tab rebuilt after datapack reload.");
    }
}
