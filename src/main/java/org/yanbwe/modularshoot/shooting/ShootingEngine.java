package org.yanbwe.modularshoot.shooting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.AttributeResolver;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.ComposedBulletStyle;
import org.yanbwe.modularshoot.bullet.VisualCompositionService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.damage.ModularShootDamageTypes;
import org.yanbwe.modularshoot.degradation.GunDegradationHandler;
import org.yanbwe.modularshoot.network.BulletSyncService;
import org.yanbwe.modularshoot.plugin.TraitMergeService;
import org.yanbwe.modularshoot.network.ShootAnimSyncService;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.GunSounds;
import org.yanbwe.modularshoot.variant.VariantPoolService;

/**
 * Server-side shooting engine orchestrator (设计文档 §射击时序步骤 3-9).
 *
 * <p>Called by {@link ShootPacketHandler} after the fire-rate gate and
 * modifier-version anti-cheat have passed. This class implements the remaining
 * seven steps of the single-shot timeline:</p>
 * <ol start="3">
 *   <li><b>ShootPredicate</b> — runs every registered
 *       {@link ShootPredicate}; the first failure aborts the shot and shows
 *       the reason on the player's action bar.</li>
 *   <li><b>PreShootEvent</b> — fires a cancelable {@link PreShootEvent}; a
 *       canceled event aborts the shot without consuming the fire-rate
 *       cooldown.</li>
 *   <li><b>Attribute snapshot</b> — reads the final values of all ten
 *       framework attributes from the player, each resolved through its
 *       {@code attribute_meta} entry's {@code binds} target (逻辑属性 id →
 *       binds 目标原版属性 → 实体最终值), reads the gun's inherent boolean
 *       traits, resolves the damage type (per-gun state preset or framework
 *       default), and freezes everything into a {@link BulletSnapshot}.</li>
 *   <li><b>Spread per pellet</b> — for every pellet, derives an independent
 *       bullet direction from the player's server-side look angle and
 *       applies elliptical spread via {@link SpreadCalculator} (每颗独立
 *       采样，天然形成霰弹分布). The client's direction is never trusted.</li>
 *   <li><b>Register bullets</b> — registers one bullet per pellet at the
 *       player's eye position with the per-dimension {@link BulletManager},
 *       then marks each as created this tick so the tick-end sync sends a
 *       full packet even if the bullet is removed by collision in the same
 *       Pre step (设计文档 §短寿命子弹保证).</li>
 *   <li><b>Sound</b> — plays the gun's {@code shoot} sound slot at the
 *       shooter's position, if the gun definition defines one.</li>
 *   <li><b>PostShootEvent</b> — fires a non-cancelable
 *       {@link PostShootEvent} carrying every pellet's live
 *       {@link BulletRecord}.</li>
 * </ol>
 *
 * <p>Each step is a short-circuit guard: the first failure aborts the entire
 * shot. The class is not instantiable; all methods are static. Every method is
 * kept under 50 lines per the project code-quality standard.</p>
 *
 * <p><b>Trait merge:</b> the snapshot's traits are computed by
 * {@link org.yanbwe.modularshoot.plugin.TraitMergeService#computeTraits},
 * which merges the gun's inherent traits with all installed plugin traits
 * per 设计文档 §布尔特性合并规则 (plugins sorted by priority ascending,
 * then the gun's inherent traits override the final result).</p>
 */
public final class ShootingEngine {

    private ShootingEngine() {
    }

    // --- Attribute id constants (derived from the registered DeferredHolders) ---

    private static final ResourceLocation HIT_DAMAGE_ID =
            ModularShootAttributes.HIT_DAMAGE.getKey().location();
    private static final ResourceLocation FIRE_RATE_ID =
            ModularShootAttributes.FIRE_RATE.getKey().location();
    private static final ResourceLocation RANGE_ID =
            ModularShootAttributes.RANGE.getKey().location();
    private static final ResourceLocation ACCURACY_YAW_ID =
            ModularShootAttributes.ACCURACY_YAW.getKey().location();
    private static final ResourceLocation ACCURACY_PITCH_ID =
            ModularShootAttributes.ACCURACY_PITCH.getKey().location();
    private static final ResourceLocation ENTITY_PENETRATION_ID =
            ModularShootAttributes.ENTITY_PENETRATION.getKey().location();
    private static final ResourceLocation BULLET_SPEED_ID =
            ModularShootAttributes.BULLET_SPEED.getKey().location();
    private static final ResourceLocation BULLET_SIZE_ID =
            ModularShootAttributes.BULLET_SIZE.getKey().location();
    private static final ResourceLocation BLOCK_PENETRATION_ID =
            ModularShootAttributes.BLOCK_PENETRATION.getKey().location();
    private static final ResourceLocation PELLET_COUNT_ID =
            ModularShootAttributes.PELLET_COUNT.getKey().location();
    /** 单发弹丸数上限；超限 clamp + WARN（规格 §3.3）。 */
    private static final int MAX_PELLETS = 32;

    /** Per-gun state key for the reserved ammo-damage-type preset (设计文档 §伤害类型预设机制一). */
    private static final String AMMO_DAMAGE_TYPE_STATE_KEY = "modularshoot:ammo_damage_type";

    /** Sound slot name for the shoot sound (设计文档 §音效系统). */
    private static final String SHOOT_SOUND_SLOT = "shoot";

    /** Default shoot sound volume. */
    private static final float SHOOT_VOLUME = 1.0f;

    /** Default shoot sound pitch. */
    private static final float SHOOT_PITCH = 1.0f;

    // --- Entry point -----------------------------------------------------

    /**
     * Main entry point: orchestrates shooting steps 3-9 for a single shot.
     *
     * <p>Called by {@link ShootPacketHandler} after the fire-rate gate and
     * anti-cheat checks have passed. The {@code gunData} is the validated
     * {@link GunData} component read from the player's main-hand gun stack.
     * The main-hand stack is re-read here so that predicate and event steps
     * receive the live {@link ItemStack}.</p>
     *
     * @param player  the shooting server player; must not be {@code null}
     * @param gunData the gun data of the main-hand gun; must not be {@code null}
     */
    public static void fire(ServerPlayer player, GunData gunData) {
        ItemStack gunStack = player.getMainHandItem();
        // Early degradation check: if the gun definition is missing, silently
        // cancel the shot with a rate-limited WARN (设计文档 §枪械 gunId 失效降级).
        // This runs before predicates and PreShootEvent so that a degraded gun
        // never fires, never triggers predicate side-effects, and never fires
        // events that listeners might expect to be paired with a bullet.
        if (GunDegradationHandler.shouldSilenceShoot(player, gunStack, player.registryAccess())) {
            return;
        }
        // Step 3: ShootPredicate — abort on first failure (reason shown to player).
        if (!runPredicates(player, gunStack)) {
            return;
        }
        // Step 4: PreShootEvent — cancelable; abort without consuming fire-rate.
        if (!firePreShootEvent(player, gunStack, gunData.gunId())) {
            return;
        }
        // Resolve the gun definition once for snapshot + sound steps.
        GunDefinition gunDefinition =
                GunRegistry.getGun(player.registryAccess(), gunData.gunId()).orElse(null);
        if (gunDefinition == null) {
            // Defensive guard: shouldSilenceShoot already handled the missing
            // definition case above. This only triggers if the definition was
            // removed between the early check and here (a rare race).
            return;
        }
        // Step 5: build the frozen attribute/trait snapshot.
        BulletSnapshot snapshot = buildSnapshot(player, gunStack, gunData, gunDefinition);
        // Step 6+7: register one bullet per pellet; each pellet gets an independent
        // spread sample, bullet id and visual composition (规格 §3.2).
        List<BulletRecord> records = registerPellets(player, gunStack, snapshot, gunData, gunDefinition);
        // Step 8: sound + third-person animation stay once per shot (一枪一声).
        playShootSound(player, gunDefinition);
        ShootAnimSyncService.getInstance().onShootFired(player);
        // Step 9: PostShootEvent carrying every pellet.
        firePostShootEvent(player, gunStack, records);
    }

    // --- Step 3: ShootPredicate ------------------------------------------

    /**
     * Runs all registered {@link ShootPredicate}s (设计文档 §步骤三).
     *
     * <p>On the first failing predicate the reason is shown to the player on
     * the action bar and the shot is aborted. When no predicates are
     * registered the shot trivially passes.</p>
     *
     * @param player   the shooting player
     * @param gunStack the gun item stack being fired
     * @return {@code true} if all predicates pass and the shot may continue;
     *         {@code false} if a predicate failed and the shot was aborted
     */
    private static boolean runPredicates(ServerPlayer player, ItemStack gunStack) {
        ShootPredicateResult result = ShootPredicateRegistry.testAll(player, gunStack);
        if (result.isSuccess()) {
            return true;
        }
        String reason = result.getReason();
        player.displayClientMessage(Component.literal(reason != null ? reason : ""), true);
        return false;
    }

    // --- Step 4: PreShootEvent -------------------------------------------

    /**
     * Fires the cancelable {@link PreShootEvent} on the NeoForge event bus
     * (设计文档 §步骤四).
     *
     * <p>A canceled event aborts the shot. Per the design contract the
     * fire-rate cooldown is <em>not</em> consumed when the event is canceled:
     * the last-shoot tick recorded by the fire-rate gate is rolled back via
     * {@link FireRateController#rollbackLastShootTick}, so the next request
     * is timed from a fresh interval (兑现 javadoc 承诺).</p>
     *
     * @param player   the shooting player
     * @param gunStack the gun item stack being fired
     * @param gunId    the gun definition id（取消时用于回滚射速冷却）
     * @return {@code true} if the event was not canceled and the shot may
     *         continue; {@code false} if a listener canceled the shot
     */
    private static boolean firePreShootEvent(ServerPlayer player, ItemStack gunStack, ResourceLocation gunId) {
        PreShootEvent event = new PreShootEvent(player, gunStack);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            // 兑现 javadoc 承诺：取消的射击不消耗射速冷却（设计文档 §步骤四）。
            FireRateController.rollbackLastShootTick(player.getUUID(), gunId);
            return false;
        }
        return true;
    }

    // --- Step 5: Attribute snapshot --------------------------------------

    /**
     * Builds the {@link BulletSnapshot} freezing the final values of all ten
     * framework attributes (each resolved through its {@code attribute_meta}
     * entry's {@code binds} target), the gun's inherent traits, the resolved
     * damage type and the shooter identity (设计文档 §步骤五).
     *
     * @param player         the shooting player (attributes read from here)
     * @param gunStack       the gun item stack (used for trait merge)
     * @param gunData        the gun data (gun id, instance uuid, per-gun state)
     * @param gunDefinition  the gun definition (inherent traits)
     * @return a new {@link BulletSnapshot} ready to be embedded in a bullet
     */
    private static BulletSnapshot buildSnapshot(
            ServerPlayer player, ItemStack gunStack, GunData gunData, GunDefinition gunDefinition) {
        Map<ResourceLocation, Double> stats = collectAttributeStats(player);
        // Merge gun inherent traits with installed plugin traits per design doc §布尔特性合并规则.
        Map<ResourceLocation, Boolean> traits = new HashMap<>(
                TraitMergeService.computeTraits(gunStack, player.registryAccess()));
        Holder<DamageType> damageType = resolveDamageType(player, gunData);
        return new BulletSnapshot(
                stats,
                traits,
                damageType,
                player.getUUID(),
                gunData.gunId(),
                gunData.gunInstanceUuid(),
                Map.of());
    }

    /**
     * Reads the final values of all ten framework attributes from the player,
     * resolving each logical attribute id through its {@code attribute_meta}
     * entry's {@code binds} target before reading the entity's final value
     * (逻辑属性 id → binds 目标原版属性 → 实体最终值，设计文档 §属性元数据 binds 机制).
     *
     * <p><strong>Degradation semantics:</strong> any missing link in the
     * resolution chain — metadata entry absent, {@code binds} target
     * unregistered, or the bound attribute not mounted on the player —
     * degrades to {@code 0.0} without throwing, matching the degradation
     * contract of {@link org.yanbwe.modularshoot.degradation.AttributeBindsDegradationHandler}.</p>
     *
     * <p>The snapshot keys stay the logical attribute ids (never the bound
     * target ids): downstream consumers such as {@code PenetrationHandler},
     * {@code CollisionDetector}, {@code BulletTickHandler} and
     * {@link #applySpread} all read the snapshot by logical id. Uses a
     * {@link LinkedHashMap} so the iteration order is deterministic
     * (insertion order), which makes snapshot debugging and log output
     * stable.</p>
     *
     * @param player the player to read attributes from
     * @return a mutable map of logical attribute id → final double value
     */
    private static Map<ResourceLocation, Double> collectAttributeStats(ServerPlayer player) {
        Map<ResourceLocation, Double> stats = new LinkedHashMap<>();
        stats.put(HIT_DAMAGE_ID, AttributeResolver.readFinalValue(player, HIT_DAMAGE_ID, player.registryAccess()));
        stats.put(FIRE_RATE_ID, AttributeResolver.readFinalValue(player, FIRE_RATE_ID, player.registryAccess()));
        stats.put(RANGE_ID, AttributeResolver.readFinalValue(player, RANGE_ID, player.registryAccess()));
        stats.put(ACCURACY_YAW_ID, AttributeResolver.readFinalValue(player, ACCURACY_YAW_ID, player.registryAccess()));
        stats.put(ACCURACY_PITCH_ID, AttributeResolver.readFinalValue(player, ACCURACY_PITCH_ID, player.registryAccess()));
        stats.put(ENTITY_PENETRATION_ID, AttributeResolver.readFinalValue(player, ENTITY_PENETRATION_ID, player.registryAccess()));
        stats.put(BULLET_SPEED_ID, AttributeResolver.readFinalValue(player, BULLET_SPEED_ID, player.registryAccess()));
        stats.put(BULLET_SIZE_ID, AttributeResolver.readFinalValue(player, BULLET_SIZE_ID, player.registryAccess()));
        stats.put(BLOCK_PENETRATION_ID, AttributeResolver.readFinalValue(player, BLOCK_PENETRATION_ID, player.registryAccess()));
        stats.put(PELLET_COUNT_ID, AttributeResolver.readFinalValue(player, PELLET_COUNT_ID, player.registryAccess()));
        return stats;
    }

    /**
     * Resolves the damage type for the bullet (设计文档 §伤害类型预设机制一).
     *
     * <p>Reads the reserved per-gun state {@code modularshoot:ammo_damage_type}.
     * If the value is non-empty it is parsed as a {@link ResourceLocation} and
     * resolved to a {@link Holder<DamageType>} from the damage-type registry.
     * If the value is empty or the referenced damage type is not registered,
     * the framework default {@code modularshoot:bullet_damage} is used.</p>
     *
     * @param player  the shooting player (provides registry access)
     * @param gunData the gun data (carries the per-gun state)
     * @return the resolved damage type holder; never {@code null}
     */
    private static Holder<DamageType> resolveDamageType(ServerPlayer player, GunData gunData) {
        String damageTypeId = gunData.state().getString(AMMO_DAMAGE_TYPE_STATE_KEY);
        if (damageTypeId.isEmpty()) {
            return ModularShootDamageTypes.holderOrThrow(player.registryAccess());
        }
        ResourceKey<DamageType> key = ResourceKey.create(
                Registries.DAMAGE_TYPE, ResourceLocation.parse(damageTypeId));
        Optional<Holder.Reference<DamageType>> holder =
                player.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(key);
        if (holder.isPresent()) {
            return holder.get();
        }
        ModularShoot.LOGGER.warn(
                "Damage type {} not found in registry; falling back to default.",
                damageTypeId);
        return ModularShootDamageTypes.holderOrThrow(player.registryAccess());
    }

    // --- Step 6: Spread --------------------------------------------------

    /**
     * Applies elliptical spread to the player's server-side look angle
     * (设计文档 §步骤六).
     *
     * <p>The spread is computed entirely on the server to prevent cracked
     * clients from bypassing it. The {@code accuracy_yaw} and
     * {@code accuracy_pitch} values are read from the already-frozen
     * snapshot so that any in-flight trait modification after spawn does not
     * retroactively change this bullet's initial direction.</p>
     *
     * @param player   the shooting player (look angle and random source)
     * @param snapshot the frozen bullet snapshot (carries accuracy stats)
     * @return the deflected direction vector
     */
    private static Vec3 applySpread(ServerPlayer player, BulletSnapshot snapshot) {
        Vec3 lookAngle = player.getLookAngle();
        double accuracyYaw = snapshot.getStat(ACCURACY_YAW_ID);
        double accuracyPitch = snapshot.getStat(ACCURACY_PITCH_ID);
        RandomSource random = player.level().getRandom();
        return SpreadCalculator.applySpread(lookAngle, accuracyYaw, accuracyPitch, random);
    }

    // --- Step 7: Register bullets ----------------------------------------

    /**
     * 注册单发全部弹丸（规格 §3.2 步骤七改造）。每颗深拷贝快照、逐弹丸独立变体 roll
     * （机制四 §6.4 逐弹丸语义）、逐颗执行效果贡献者（机制三）、独立散布采样、独立
     * ID 与视觉组合，并逐颗调用 BulletSyncService.markBulletCreated（短寿命子弹
     * 保证为逐子弹语义）。音效与第三人称动画由调用方在循环外保持一次。
     *
     * @param player        the shooting player
     * @param gunStack      the gun item stack being fired
     * @param snapshot      the frozen per-shot snapshot (shared base; each pellet copies it)
     * @param gunData       the firing gun's data (already resolved in {@link #fire}'s scope)
     * @param gunDefinition the gun definition (declared variants; already resolved in
     *                      {@link #fire}'s scope)
     * @return all registered pellet records, in registration order
     */
    private static List<BulletRecord> registerPellets(
            ServerPlayer player, ItemStack gunStack, BulletSnapshot snapshot, GunData gunData,
            GunDefinition gunDefinition) {
        int pellets = resolvePelletCount(snapshot);
        List<BulletRecord> records = new ArrayList<>(pellets);
        for (int i = 0; i < pellets; i++) {
            BulletSnapshot copy = snapshot.copy();
            // 变体池逐弹丸独立 roll（规格 §6.4 逐弹丸语义）：每颗独立选举，霰弹中可
            // 混合出现不同变体；无选中 → 本颗普通弹（静默）。在 copy 之后、效果贡献者
            // 之前执行 → 变体 damage_type 覆盖 ammo 预设（变体优先），效果贡献者可
            // 叠加在变体结果之上。
            VariantPoolService.rollAndApply(player, copy, gunData, gunDefinition);
            // 机制三：效果贡献者按注册顺序改写本颗快照（规格 §5.1：copy 之后、applySpread 之前）。
            ShootEffectRegistry.applyEffects(player, gunStack, copy, i, pellets);
            Vec3 direction = applySpread(player, copy);        // 每颗独立散布采样（纯函数）
            BulletRecord record = registerBullet(player, copy, direction, gunData);
            BulletSyncService.markBulletCreated(player.level(), record);
            records.add(record);
            ModularShoot.LOGGER.debug("Pellet fired: id={}, gun={}, player={}, pellet={}/{}",
                    record.getBulletId(), gunData.gunId(), player.getName().getString(), i + 1, pellets);
        }
        return records;
    }

    /**
     * clamp(round(pellet_count), 1, MAX_PELLETS)；超上限 WARN（防恶意配置性能爆炸）。
     *
     * <p>极端值路径（行为安全，无需显式防御）：{@code Math.round(NaN) = 0} → 落到
     * {@code < 1} 分支，静默退化为单弹丸；{@code Math.round(+inf)} 溢出为
     * {@code Long.MAX_VALUE}，强转 {@code int} 截断为负 → 同样落到 {@code < 1}
     * 分支退化为单弹丸。两种情况都不会产生越界循环或非法值。</p>
     */
    private static int resolvePelletCount(BulletSnapshot snapshot) {
        int pellets = (int) Math.round(snapshot.getStat(PELLET_COUNT_ID));
        if (pellets < 1) {
            return 1;                       // 属性缺失/未挂载时静默退化为单弹丸
        }
        if (pellets > MAX_PELLETS) {
            ModularShoot.LOGGER.warn("Pellet count {} exceeds MAX_PELLETS {}; clamping.", pellets, MAX_PELLETS);
            return MAX_PELLETS;
        }
        return pellets;
    }

    /**
     * Creates and registers a {@link BulletRecord} with the per-dimension
     * {@link BulletManager} (设计文档 §步骤七).
     *
     * <p>The bullet spawns at the player's eye position to avoid the body
     * collision box blocking the first tick. A unique bullet id is allocated
     * by the manager before the record is constructed and added.</p>
     *
     * <p>As of the modifier-stacking redesign, this method also composes the
     * bullet's visual style exactly once at creation (设计规格 §2.1) via
     * {@link VisualCompositionService#compose}, supplying the in-scope
     * {@code gunData} directly so no reverse lookup is needed on the
     * player-firing path.</p>
     *
     * @param player     the shooting player (eye position and level)
     * @param snapshot   the frozen bullet snapshot
     * @param direction  the initial flight direction (post-spread)
     * @param gunData    the firing gun's data (already resolved in
     *                   {@link #fire}'s scope); never {@code null} on this path
     * @return the live, registered {@link BulletRecord} with
     *         creation-frozen composed style
     */
    private static BulletRecord registerBullet(
            ServerPlayer player, BulletSnapshot snapshot, Vec3 direction,
            GunData gunData) {
        Vec3 position = player.getEyePosition();
        BulletManager manager = BulletManager.get(player.level());
        int bulletId = manager.nextBulletId();
        // Compose the visual style once at creation (spec §2.1 / §4.1). The
        // player-firing path holds gunData in its own scope, so no reverse
        // lookup is required (contrast with BulletManager.fireBullet).
        ComposedBulletStyle composed = VisualCompositionService.INSTANCE.compose(
                player.registryAccess(), snapshot, gunData);
        BulletRecord bulletRecord =
                new BulletRecord(snapshot, player.getUUID(), position, direction, bulletId, composed);
        manager.addBullet(bulletRecord);
        return bulletRecord;
    }

    // --- Step 8: Sound ---------------------------------------------------

    /**
     * Plays the gun's {@code shoot} sound at the shooter's position
     * (设计文档 §步骤八, §音效系统).
     *
     * <p>The sound id is read from {@link GunDefinition#sounds()} under the
     * {@code "shoot"} slot. If the slot is absent or the sound event is not
     * registered, no sound is played. The first argument to
     * {@code playSound} is {@code null} so that every nearby player hears
     * the shot (the shooter is not excluded).</p>
     *
     * @param player         the shooting player (position and level)
     * @param gunDefinition  the gun definition (carries the sound bindings)
     */
    private static void playShootSound(ServerPlayer player, GunDefinition gunDefinition) {
        ResourceLocation soundId = GunSounds.get(gunDefinition, SHOOT_SOUND_SLOT).orElse(null);
        if (soundId == null) {
            return;
        }
        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(soundId);
        if (soundEvent == null) {
            ModularShoot.LOGGER.warn(
                    "Shoot sound {} not found in SOUND_EVENT registry; skipping playback.",
                    soundId);
            return;
        }
        Vec3 eye = player.getEyePosition();
        player.level().playSound(
                null,
                eye.x,
                eye.y,
                eye.z,
                soundEvent,
                SoundSource.PLAYERS,
                SHOOT_VOLUME,
                SHOOT_PITCH);
    }

    // --- Step 9: PostShootEvent ------------------------------------------

    /**
     * Fires the non-cancelable {@link PostShootEvent} on the NeoForge event
     * bus (设计文档 §步骤九).
     *
     * <p>This event carries the live, already-registered
     * {@link BulletRecord}s of every pellet so that listeners observe the
     * bullets in their initial in-flight state. The event is never canceled;
     * to suppress a shot a listener must use {@link PreShootEvent}
     * instead.</p>
     *
     * @param player   the shooting player
     * @param gunStack the gun item stack the shot was fired from
     * @param records  every live bullet record registered for this shot, in
     *                 registration order
     */
    private static void firePostShootEvent(
            ServerPlayer player, ItemStack gunStack, List<BulletRecord> records) {
        PostShootEvent event = new PostShootEvent(player, gunStack, records);
        NeoForge.EVENT_BUS.post(event);
    }
}
