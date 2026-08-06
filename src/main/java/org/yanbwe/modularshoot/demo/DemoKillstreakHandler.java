package org.yanbwe.modularshoot.demo;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.state.GunState;

/**
 * 随包演示内容（设计文档 §模组定位），上层模组勿依赖，可被覆盖/删除。
 *
 * <p>演示 {@code modularshoot:visual_killstreak} 状态的写入路径。该状态
 * （domain {@code gun}、int、默认 0，见
 * {@code data/modularshoot/modularshoot/states/visual_killstreak.json}）本身
 * 只声明了 display 条件视觉（连杀 &gt;= 3 时子弹染色放大），没有任何写入方
 * ——本类补齐演示语义的写入端：</p>
 *
 * <ul>
 *   <li><strong>击杀分支：</strong>玩家（{@link ServerPlayer}）击杀任意实体且
 *       主手持有枪械时，将主手枪的 {@code visual_killstreak} 状态 +1。</li>
 *   <li><strong>玩家死亡清零分支：</strong>玩家死亡时将其主手枪的
 *       {@code visual_killstreak} 置 0——连击中断的演示语义。</li>
 * </ul>
 *
 * <p>两条分支互不排斥：玩家自杀时先执行击杀 +1、再执行死亡清零，最终为 0，
 * 符合"连击中断"语义。状态写入走 {@link GunState#setInt} 的既有持久化与
 * 节流同步路径（{@code GunSyncThrottleManager}），客户端视觉由
 * {@code visual_killstreak.json} 的 display 条件驱动，无需本类同步。</p>
 *
 * <p>服务端专用：客户端早退（{@code level().isClientSide()}），避免双端
 * 竞写。</p>
 *
 * @see ModularShootAPI#getState
 * @see GunState#getInt
 * @see GunState#setInt
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class DemoKillstreakHandler {

    /** 连杀状态 id（与 states/visual_killstreak.json 的键一致，domain=gun）。 */
    public static final ResourceLocation KILLSTREAK_ID = ResourceLocation.parse("modularshoot:visual_killstreak");

    private DemoKillstreakHandler() {
    }

    /**
     * 服务端死亡事件：击杀者主手枪连杀 +1；死者（玩家）主手枪连杀清零。
     *
     * @param event 死亡事件
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        // 只有服务端对状态写入有权威（设计文档 §读写 API）。
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        // 击杀分支：击杀者为主手玩家枪时连杀 +1。
        if (event.getSource().getEntity() instanceof ServerPlayer shooter) {
            ItemStack mainHand = shooter.getMainHandItem();
            if (ModularShootAPI.isGun(mainHand)) {
                GunState state = ModularShootAPI.getState(mainHand, shooter);
                if (state != null) {
                    state.setInt(KILLSTREAK_ID, state.getInt(KILLSTREAK_ID) + 1);
                }
            }
        }

        // 玩家死亡清零分支：死者主手枪连杀置 0（连击中断演示语义）。
        if (event.getEntity() instanceof ServerPlayer dead) {
            ItemStack mainHand = dead.getMainHandItem();
            if (ModularShootAPI.isGun(mainHand)) {
                GunState state = ModularShootAPI.getState(mainHand, dead);
                if (state != null) {
                    state.setInt(KILLSTREAK_ID, 0);
                }
            }
        }
    }
}
