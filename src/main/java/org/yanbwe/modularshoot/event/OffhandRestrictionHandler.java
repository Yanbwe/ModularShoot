package org.yanbwe.modularshoot.event;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Enforces the design-mandated offhand restriction for guns.
 *
 * <p>Guns must never reside in the offhand slot. Their {@code ATTRIBUTE_MODIFIERS}
 * component is scoped to {@code MAINHAND}, so an offhand gun would silently lose its
 * attribute bonuses and mislead the player. To keep behaviour predictable regardless
 * of how the item ends up in the offhand (F-key swap, command, dispenser, etc.) the
 * framework polls every server tick: when a gun is detected in the offhand it is
 * returned to the player in priority order (inventory, then main hand) and only
 * dropped as an item entity when neither can hold it, with an action-bar notice.</p>
 *
 * <p>Server-side only. The client tick is ignored via a {@code level().isClientSide()}
 * guard so inventory state is authored on the authoritative side only, matching the
 * NeoForge-recommended pattern for {@link PlayerTickEvent} handlers.</p>
 *
 * @see ModularShootAPI#isGun(ItemStack, RegistryAccess)
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class OffhandRestrictionHandler {

    /** Translation key for the action-bar warning shown when a gun is ejected. */
    public static final String OFFHAND_RESTRICTED_KEY = "modularshoot.offhand_restricted";

    private OffhandRestrictionHandler() {
    }

    /**
     * Polled every tick on both logical sides; guarded to run only on the server.
     *
     * <p>Using {@link PlayerTickEvent.Pre} lets us strip the gun before the rest of
     * the tick (attribute re-evaluation, interaction checks) observes it in the
     * offhand, so the restricted state never propagates into downstream logic.
     * Return priority is inventory → main hand → drop, so an accidental F-key
     * swap never loses the gun unless both slots are genuinely full.</p>
     *
     * @param event the pre-tick event carrying the ticking player
     */
    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        // Only the server is authoritative for inventory changes.
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack offhand = player.getOffhandItem();
        // Non-gun items (including empty stacks) are left untouched.
        if (!ModularShootAPI.isGun(offhand, player.registryAccess())) {
            return;
        }

        // P7 fix：优先归还（背包 → 主手），背包也满才掉落。
        // add() 失败时传入栈可能被部分合并，故传副本保护原栈；枪械 maxStack=1
        // 无合并路径，copy 仅为通用防御（1.21.1 Inventory.add 语义）。
        if (player.getInventory().add(offhand.copy())) {
            // 背包放置成功（副本已 copyAndClear，原栈未动）——显式清空副手槽。
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return;
        }
        if (player.getMainHandItem().isEmpty()) {
            // 背包满：主手空则换到主手（不丢）。
            player.setItemSlot(EquipmentSlot.MAINHAND, offhand);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return;
        }
        // 背包与主手都放不下：沿现有路径掉落 + 动作栏提示。
        // 先移除副手槽的枪，使掉落实体独占该栈（不与物品栏引用别名）。
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        // 沿视线抛出；false = 无投掷者归属，拾取延迟后可被任何人拾取。
        player.drop(offhand, false);
        // 动作栏提示（瞬时、非侵入）。
        player.displayClientMessage(Component.translatable(OFFHAND_RESTRICTED_KEY), true);
    }
}
