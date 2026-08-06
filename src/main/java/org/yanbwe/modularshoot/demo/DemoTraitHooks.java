package org.yanbwe.modularshoot.demo;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookType;

/**
 * 随包演示内容（设计文档 §模组定位），上层模组勿依赖，可被覆盖/删除。
 *
 * <p>演示 {@code modularshoot:explosive} trait 的运行时钩子：注册
 * {@link TraitHookType#ON_HIT} 回调，使携带该 trait 的子弹命中实体时在
 * 命中点产生爆炸。该 trait 的静态定义位于
 * {@code data/modularshoot/modularshoot/traits/explosive.json}，演示插件
 * {@code explosive_rounds.json} 通过 {@code traits} 字段引用它——爆炸效果
 * 完全由本类注册的钩子提供，演示框架的 trait 钩子能力（设计文档 §特性运行时
 * 钩子）。</p>
 *
 * <p>演示爆炸的语义：命中点 2.5 格半径、破坏方块（
 * {@link Level.ExplosionInteraction#BLOCK}），伤害来源为射手
 * （{@link BulletRecord#getShooter()} 经 {@link ServerLevel#getEntity(UUID)}
 * 解析；非服务端 level 兜底 {@link Level#getPlayerByUUID(UUID)}）。
 * 射手离线或离开维度时来源退化为环境爆炸（{@code null} 来源），不影响演示
 * 效果。trait 是否激活以开火时冻结的快照为准
 * （{@link BulletSnapshot#getTrait(ResourceLocation)}），命中时更改插件配置
 * 不会追溯影响已在飞行中的子弹。</p>
 *
 * <p>注册时机：由 {@code ModularShoot} 构造函数调用 {@link #register()}，
 * 与数据包加载无关（钩子注册表独立于 {@code modularshoot:traits} 动态注册表）。</p>
 *
 * @see ModularShootAPI#registerTraitHook
 * @see TraitCallbacks.TraitHitCallback
 */
public final class DemoTraitHooks {

    /** 演示爆炸 trait 的注册表 id（与 traits/explosive.json 的键一致）。 */
    public static final ResourceLocation EXPLOSIVE_ID = ResourceLocation.parse("modularshoot:explosive");

    /** 演示爆炸半径（格）。 */
    private static final float EXPLOSION_RADIUS = 2.5f;

    private DemoTraitHooks() {
    }

    /**
     * 注册演示爆炸 trait 的 {@link TraitHookType#ON_HIT} 回调。
     *
     * <p>回调实现与 {@code BulletHookInvoker.fireOnHit} 的调用签名一致：
     * {@code onHit(BulletRecord, BulletSnapshot, Entity)}。伤害已先于钩子
     * 应用到目标，此处仅做副作用（爆炸）。</p>
     *
     * <p>幂等：重复调用会对同一 trait id 追加一个回调（框架按注册顺序触发），
     * 故本方法只应在 mod 构造函数中调用一次。</p>
     */
    public static void register() {
        TraitCallbacks.TraitHitCallback explosiveHook = (bullet, snapshot, target) -> {
            // 未激活直接返回——快照冻结于开火时，命中后更换插件不影响已发射子弹。
            if (!snapshot.getTrait(EXPLOSIVE_ID)) {
                return;
            }
            // 伤害来源为射手（bullet.getShooter() 解析）；射手不可达时退化为环境爆炸。
            @Nullable Entity shooter = resolveShooter(bullet, target);
            // 演示爆炸：命中点 2.5 格半径，破坏方块（ExplosionInteraction.BLOCK）。
            target.level().explode(
                    shooter,
                    target.getX(), target.getY(), target.getZ(),
                    EXPLOSION_RADIUS,
                    Level.ExplosionInteraction.BLOCK);
        };
        ModularShootAPI.registerTraitHook(EXPLOSIVE_ID, TraitHookType.ON_HIT, explosiveHook);
    }

    /**
     * 将子弹快照中冻结的射手 UUID 解析为存活实体。
     *
     * @param bullet 命中的子弹
     * @param target 被命中实体（提供解析所在的 level）
     * @return 射手实体，UUID 缺失或实体已离线/离开维度时为 {@code null}
     */
    @Nullable
    private static Entity resolveShooter(BulletRecord bullet, Entity target) {
        UUID shooterId = bullet.getShooter();
        if (shooterId == null) {
            return null;
        }
        // ON_HIT 为服务端钩子，实际必为 ServerLevel：O(1) 实体索引查找。
        // 保留非服务端兜底（与 DamageApplier.resolveShooterEntity 同一模式）。
        Level level = target.level();
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getEntity(shooterId);
        }
        return level.getPlayerByUUID(shooterId);
    }
}
