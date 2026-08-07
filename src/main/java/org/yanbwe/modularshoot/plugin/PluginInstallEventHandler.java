package org.yanbwe.modularshoot.plugin;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.GunSounds;

/**
 * Container-GUI right-click handler that triggers plugin installation,
 * following the Apotheosis gem-socketing pattern.
 *
 * <p>Registered manually via {@code NeoForge.EVENT_BUS.register(new PluginInstallEventHandler())}
 * in {@link org.yanbwe.modularshoot.ModularShoot}. Listens to
 * {@link net.neoforged.neoforge.event.ItemStackedOnOtherEvent}.</p>
 *
 * <p>Interaction routing (matching Apotheosis {@code AdventureEvents.stackedOnOther}):</p>
 * <ul>
 *   <li>Right-click + plugin-on-gun → validate, and on success write results
 *       via {@link Slot#set} / {@link SlotAccess#set}, cancel the event,
 *       and play a sound. On failure the event is cancelled too, which
 *       suppresses the vanilla swap so the plugin and gun stay in their
 *       original slots; the localized failure reason is shown in the action
 *       bar (P3 fix).</li>
 *   <li>All other cursor/slot combinations → ignored.</li>
 * </ul>
 *
 * <p>Unlike the previous design, this handler does <strong>not</strong>
 * discriminate between client and server — the install logic runs identically
 * on both sides, matching how Apotheosis handles gem socketing. Because
 * {@link PluginInstallService#installPlugin} now operates on <strong>copies</strong>
 * and the results are written via {@code slot.set()} / {@code access.set()},
 * container sync handles the rest.</p>
 *
 * <h2>Creative menu (Apotheosis pattern)</h2>
 * <p>Inside the creative inventory, {@code ItemStackedOnOtherEvent} fires only
 * on the client (the creative screen never sends container-click packets), so
 * the install runs on the client alone. It still persists because
 * {@link PluginInstallService#installPlugin} is deterministic (works on
 * copies, derives the instance uuid from the player's random source) and the
 * creative screen's {@code CreativeInventoryListener} re-syncs changed
 * inventory slots to the server via {@code ServerboundSetCreativeModeSlot},
 * which the server trusts for creative players. Consequences, matching the
 * vanilla screen's structure:</p>
 * <ul>
 *   <li>Installs work in the <b>inventory tab</b> (real inventory slots);
 *       the item-picker tab's {@code CONTAINER} slots are virtual templates
 *       that never reach {@code doClick}, so stacking there is untouched.</li>
 *   <li>Sound and rejection messages are shown locally on the client for
 *       creative-menu clicks, since the server never fires there (W7's
 *       single-audible-effect guarantee is preserved in real containers).</li>
 *   <li>The consumed plugin lives on the creative cursor (client-side only);
 *       this is invisible to the server and harmless in creative mode,
 *       exactly like Apotheosis's gem socketing.</li>
 * </ul>
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li><b>Sound feedback (W7).</b> The install sound is read from the gun
 *       definition's {@code sounds.plugin_install} slot (data-driven, 设计文档
 *       §音效系统); when the slot is unconfigured the install is silent. In
 *       real containers the sound is played only on the server side (and
 *       synced to the client) to avoid a doubled audible effect from the
 *       bilateral event firing; in the creative menu it is played locally on
 *       the client instead (see above). The random pitch draw is kept on both
 *       sides to preserve player-random alignment (see
 *       {@link PluginInstallService#deriveInstanceUuid}).
 *       Extension mods wanting a custom install sound can listen to
 *       {@code PostPluginInstallEvent} and play their own sound.</li>
 * </ul>
 *
 * @see PluginInstallService
 */
public final class PluginInstallEventHandler {

    /** Translation key for the action-bar warning shown when installation fails. */
    public static final String INSTALL_FAILED_KEY = "modularshoot.install_failed";

    /** 枪械定义 sounds 中的插件安装音效槽位名（设计文档 §音效系统）。 */
    private static final String INSTALL_SOUND_SLOT = "plugin_install";

    public PluginInstallEventHandler() {
    }

    /**
     * Routes container right-click stacking into the plugin install pipeline.
     *
     * <p>Filter order:</p>
     * <ol>
     *   <li>right-click only ({@link ClickAction#SECONDARY});</li>
     *   <li>carried item is a {@code modularshoot:plugin} with {@code plugin_data};</li>
     *   <li>slot item is a {@code modularshoot:gun};</li>
     *   <li>slot allows modification ({@link Slot#allowModification}).</li>
     * </ol>
     * <p>On success the modified gun copy is placed into the slot via
     * {@link Slot#set}, the consumed plugin copy is placed onto the cursor via
     * {@link SlotAccess#set}, the event is cancelled, and a sound plays.
     * In real containers the sound is played only on the server side (synced
     * to the client); inside the creative menu — where the event fires on the
     * client only — sound and rejection messages are played locally
     * (W7 fix, see class Javadoc).
     * On failure the event is cancelled as well so the vanilla item swap is
     * suppressed and both items stay in their original slots; the localized
     * rejection reason is shown in the action bar.</p>
     *
     * @param event the stacking event fired by the container menu
     */
    @SubscribeEvent
    public void onItemStackedOnOther(ItemStackedOnOtherEvent event) {
        Player player = event.getPlayer();

        // Pre-install guard chain (creative guard, click action, plugin stack,
        // gun stack, slot modification) — see shouldHandle.
        if (!shouldHandle(player, event)) {
            return;
        }

        Slot slot = event.getSlot();
        SlotAccess access = event.getCarriedSlotAccess();
        ItemStack carriedItem = event.getCarriedItem();
        ItemStack stackedOnItem = event.getStackedOnItem();

        // 竞态兜底：绑定枪械的 gun_data 由服务端 tick 通道惰性附加（拿起 1 tick
        // 内，设计规格 §5.3），但容器槽位中的枪可能从未被拿起；安装手势前先确保
        // 组件存在，保证 PluginInstallService 的 no_gun_data 守卫通过。注意：
        // 本调用不改变本类的识别判定逻辑（is(GUN_ITEM) 判定改造属于后续任务）。
        GunRegistry.ensureGunData(stackedOnItem, player.registryAccess());

        // Attempt installation (operates on copies, never mutates originals).
        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                stackedOnItem, carriedItem, player, player.level().registryAccess());

        if (result.success()) {
            applyInstallResult(event, player, slot, access, stackedOnItem, result);
        } else {
            rejectInstall(event, player, result);
        }
    }

    /**
     * Runs the pre-install guard chain for a stacking event.
     *
     * <p>Filter order (matching the Javadoc of
     * {@link #onItemStackedOnOther}): right-click only, carried item is a
     * {@code modularshoot:plugin}, slot item is a {@code modularshoot:gun},
     * slot allows modification.</p>
     *
     * @param player the player involved in the stacking event
     * @param event  the stacking event fired by the container menu
     * @return {@code true} when the event should proceed to installation,
     *         {@code false} when it should be ignored
     */
    private static boolean shouldHandle(Player player, ItemStackedOnOtherEvent event) {
        // Only handle right-click.
        if (event.getClickAction() != ClickAction.SECONDARY) {
            return false;
        }
        // Carried item must be a modularshoot:plugin with plugin_data.
        if (!isPluginStack(event.getCarriedItem())) {
            return false;
        }
        // Slot item must be a modularshoot:gun.
        if (!event.getStackedOnItem().is(ModularShootItems.GUN_ITEM.get())) {
            return false;
        }
        // Slot must allow modification (Apotheosis guard).
        return event.getSlot().allowModification(player);
    }

    /**
     * Writes a successful install back into the container and cancels the
     * event.
     *
     * <p>The modified gun copy is placed into the slot via {@link Slot#set}
     * and the consumed plugin copy onto the cursor via {@link SlotAccess#set};
     * cancelling suppresses the vanilla item swap. Data-driven sound feedback
     * (W7 fix): the pitch is drawn independently on each side (client and
     * server random sources are unrelated, so there is no alignment promise),
     * but the sound is only played on the server side (and synced to the
     * client) to avoid a doubled audible effect from the bilateral event
     * firing. The sound event itself is read from the gun definition's
     * {@code sounds.plugin_install} slot; unconfigured slots stay silent.</p>
     *
     * @param event         the stacking event to cancel
     * @param player        the player performing the install
     * @param slot          the slot holding the gun
     * @param access        the carried slot access for the plugin
     * @param stackedOnItem the gun stack (source of the install sound lookup)
     * @param result        the successful install result
     */
    private static void applyInstallResult(ItemStackedOnOtherEvent event, Player player,
            Slot slot, SlotAccess access, ItemStack stackedOnItem,
            PluginInstallService.InstallResult result) {
        slot.set(result.installedGun());
        access.set(result.consumedPlugin());
        event.setCanceled(true);
        float pitch = 1.5F + 0.35F * (1 - 2 * player.getRandom().nextFloat());
        // One sound per perceived event: server side (synced to the client) in
        // real containers, or locally on the client inside the creative menu
        // where the server never fires (W7 fix, see class Javadoc).
        if (!player.level().isClientSide() || isCreativeMenuClick(player)) {
            playInstallSound(player, stackedOnItem, pitch);
        }
    }

    /**
     * Cancels the event on failure, suppressing the vanilla item swap.
     *
     * <p>P3 fix: the plugin and gun stay in their original slots instead of
     * confusingly exchanging places. The localized rejection reason is shown
     * in the action bar — on the server side in real containers, or locally
     * on the client inside the creative menu where the server never fires
     * (see class Javadoc). The {@value #INSTALL_FAILED_KEY} lang value already
     * ends with a colon, so the error message is appended directly without an
     * extra ": " separator.</p>
     *
     * @param event  the stacking event to cancel
     * @param player the player to notify
     * @param result the failed install result
     */
    private static void rejectInstall(ItemStackedOnOtherEvent event, Player player,
            PluginInstallService.InstallResult result) {
        event.setCanceled(true);
        if (!player.level().isClientSide() || isCreativeMenuClick(player)) {
            Component message = Component.translatable(INSTALL_FAILED_KEY)
                    .append(result.errorMessage()
                            .orElse(Component.translatable("modularshoot.install.error.generic")));
            player.displayClientMessage(message, true);
        }
    }

    /**
     * Detects a stacking click inside the creative inventory menu.
     *
     * <p>The creative screen fires {@link ItemStackedOnOtherEvent} only on the
     * client (its {@code doClick} path runs through
     * {@code player.inventoryMenu} with no server-side counterpart), so sound
     * and rejection feedback must be produced locally there. The client-only
     * screen check is never evaluated on the server: the callers short-circuit
     * on {@code player.level().isClientSide()} first, so the {@code Minecraft}
     * reference is never resolved server-side.</p>
     *
     * @param player the player involved in the stacking event
     * @return {@code true} when the click happened inside the creative
     *         inventory menu and the player is in creative mode
     */
    private static boolean isCreativeMenuClick(Player player) {
        if (!player.level().isClientSide() || !player.isCreative()) {
            return false;
        }
        return Minecraft.getInstance().screen instanceof CreativeModeInventoryScreen;
    }

    /**
     * Checks whether a stack is a framework plugin item carrying
     * {@link org.yanbwe.modularshoot.component.PluginData}.
     *
     * @param stack the stack to test
     * @return {@code true} when the stack is a {@code modularshoot:plugin} item
     *         with a {@code plugin_data} component
     */
    private static boolean isPluginStack(ItemStack stack) {
        if (!stack.is(ModularShootItems.PLUGIN_ITEM.get())) {
            return false;
        }
        return stack.has(ModularShootDataComponents.PLUGIN_DATA.get());
    }

    /**
     * 播放插件安装音效（设计文档 §音效系统 — 数据驱动安装音效）。
     *
     * <p>音效从枪械定义 {@code sounds} 的 {@value #INSTALL_SOUND_SLOT} 槽位读取；
     * 槽位未配置、枪械定义不存在或音效未注册时静音，不播放任何声音。真实容器内
     * 仅服务端调用（同步给客户端），保持 W7 的"单次可闻效果"约定；创造菜单内由
     * 客户端本地调用（服务端在该场景从不触发事件）。扩展模组需要自定义安装音效
     * 可监听 {@code PostPluginInstallEvent}。</p>
     *
     * <p>枪械定义声明 {@code sound_range} 时按固定可闻半径播放（与
     * {@code ShootingEngine.playShootSound} 一致），缺省用音效事件自带 range。</p>
     *
     * @param player 执行安装的玩家
     * @param gun    被安装插件的枪械 ItemStack
     * @param pitch  随机音调（由调用方计算；双端各自独立抽取、无对齐承诺，仅播放端抽取）
     */
    private static void playInstallSound(Player player, ItemStack gun, float pitch) {
        GunData data = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (data == null) {
            return;
        }
        GunRegistry.getGun(player.level().registryAccess(), data.gunId())
                .flatMap(def -> GunSounds.get(def, INSTALL_SOUND_SLOT)
                        .map(id -> BuiltInRegistries.SOUND_EVENT.get(id))
                        .filter(Objects::nonNull)
                        .map(soundEvent -> GunSounds.getRange(def)
                                .map(range -> SoundEvent.createFixedRangeEvent(
                                        soundEvent.getLocation(), range))
                                .orElse(soundEvent)))
                .ifPresent(sound -> player.playSound(sound, 1.0F, pitch));
    }
}
