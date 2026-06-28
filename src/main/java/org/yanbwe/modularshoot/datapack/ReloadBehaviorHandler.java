package org.yanbwe.modularshoot.datapack;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.creative.ModularShootCreativeTabs;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Handles framework-specific behaviours that must run after a datapack
 * reload completes (设计文档 §/reload 重载行为, line 2305).
 *
 * <p>After {@code /reload}, the six dynamic registries are automatically
 * repopulated by NeoForge's {@code DataPackRegistryEvent} mechanism.
 * However, two framework-specific side effects are not automatically
 * handled:</p>
 * <ol>
 *   <li>The creative mode tab's cached item list is not rebuilt. This
 *       handler triggers a rebuild so that items picked from the tab use
 *       the newly loaded definitions.</li>
 *   <li>Gun stacks already held by online players retain stale
 *       {@code ATTRIBUTE_MODIFIERS} components computed from the old
 *       definitions. This handler scans every online player's inventory
 *       and offhand, refreshing the component on each gun stack via
 *       {@link AttributeModifierService#refreshModifiers} so that updated
 *       stats (e.g. range 50&rarr;100) take effect immediately
 *       (设计文档 §/reload 重载行为, K1 fix).</li>
 * </ol>
 *
 * <h2>Creative-tab refresh mechanism</h2>
 * <p>The rebuild is triggered by calling
 * {@link CreativeModeTab#buildContents(CreativeModeTab.ItemDisplayParameters)}
 * on the framework tab. NeoForge patches this method to fire
 * {@link net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent},
 * which {@link ModularShootCreativeTabs#onBuildCreativeTab} handles to
 * enumerate gun and plugin variants from the reloaded registries.</p>
 *
 * <h2>Player-inventory refresh mechanism</h2>
 * <p>Each online {@link ServerPlayer} is obtained from the server's
 * {@link net.minecraft.server.players.PlayerList}. The player's 36-slot
 * main inventory ({@code getInventory().items}) and offhand slot
 * ({@code getOffhandItem()}) are scanned for gun stacks. Each gun stack's
 * {@code ATTRIBUTE_MODIFIERS} component is recomputed from the reloaded
 * registries. Client-side synchronisation happens automatically on the
 * next tick via {@code ServerPlayer.containerMenu.broadcastChanges()}.</p>
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
 * @see AttributeModifierService
 */
public final class ReloadBehaviorHandler {

    private ReloadBehaviorHandler() {
    }

    /**
     * Called after the datapack reload completes. Triggers creative-tab
     * rebuild and refreshes {@code ATTRIBUTE_MODIFIERS} on gun stacks held
     * by online players so they reflect the newly loaded definitions.
     *
     * @param registryAccess the reloaded registry access (all registries
     *                       loaded and frozen)
     * @param server         the running Minecraft server, used to enumerate
     *                       online players (K1 fix)
     */
    public static void onReloadComplete(RegistryAccess registryAccess, MinecraftServer server) {
        refreshCreativeTab(registryAccess);
        refreshOnlinePlayerGuns(server, registryAccess);
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

    /**
     * Refreshes the {@code ATTRIBUTE_MODIFIERS} component on every gun
     * stack held by online players, so stats changed by the reload take
     * effect immediately rather than lingering as stale data (K1 fix).
     *
     * <p>Iterates all online players via the server's player list and
     * delegates per-player inventory scanning to
     * {@link #refreshGunsInInventory}. The total number of refreshed gun
     * stacks is logged for operator visibility.</p>
     *
     * @param server         the running Minecraft server
     * @param registryAccess the reloaded registry access
     */
    private static void refreshOnlinePlayerGuns(MinecraftServer server, RegistryAccess registryAccess) {
        int totalRefreshed = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            totalRefreshed += refreshGunsInInventory(player, registryAccess);
        }
        ModularShoot.LOGGER.info(
                "Refreshed ATTRIBUTE_MODIFIERS on {} gun stack(s) across online players after datapack reload.",
                totalRefreshed);
    }

    /**
     * Scans a single player's main inventory and offhand for gun stacks
     * and refreshes each one's {@code ATTRIBUTE_MODIFIERS} component.
     *
     * <p>{@code getInventory().items} covers the 36-slot main inventory
     * (hotbar + main grid). The offhand is checked separately via
     * {@link ServerPlayer#getOffhandItem()}. Armor slots are intentionally
     * skipped since a gun cannot be equipped there. Each gun stack is
     * refreshed in place by
     * {@link AttributeModifierService#refreshModifiers}, which overwrites
     * the component with values computed from the reloaded registries
     * (设计文档 §组件刷新时机).</p>
     *
     * @param player         the player whose inventory to scan
     * @param registryAccess the reloaded registry access
     * @return the number of gun stacks refreshed
     */
    private static int refreshGunsInInventory(ServerPlayer player, RegistryAccess registryAccess) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModularShootItems.GUN_ITEM.get())) {
                AttributeModifierService.refreshModifiers(stack, registryAccess);
                count++;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(ModularShootItems.GUN_ITEM.get())) {
            AttributeModifierService.refreshModifiers(offhand, registryAccess);
            count++;
        }
        return count;
    }
}
