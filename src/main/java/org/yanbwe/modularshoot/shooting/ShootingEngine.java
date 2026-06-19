package org.yanbwe.modularshoot.shooting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.damage.ModularShootDamageTypes;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

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
 *   <li><b>Attribute snapshot</b> — reads the final values of all nine
 *       framework attributes from the player, reads the gun's inherent
 *       boolean traits, resolves the damage type (per-gun state preset or
 *       framework default), and freezes everything into a
 *       {@link BulletSnapshot}.</li>
 *   <li><b>Spread</b> — derives the bullet direction from the player's
 *       server-side look angle and applies elliptical spread via
 *       {@link SpreadCalculator}. The client's direction is never trusted.</li>
 *   <li><b>Register bullet</b> — spawns a {@link BulletRecord} at the
 *       player's eye position and registers it with the per-dimension
 *       {@link BulletManager}.</li>
 *   <li><b>Sound</b> — plays the gun's {@code shoot} sound slot at the
 *       shooter's position, if the gun definition defines one.</li>
 *   <li><b>PostShootEvent</b> — fires a non-cancelable
 *       {@link PostShootEvent} carrying the live {@link BulletRecord}.</li>
 * </ol>
 *
 * <p>Each step is a short-circuit guard: the first failure aborts the entire
 * shot. The class is not instantiable; all methods are static. Every method is
 * kept under 50 lines per the project code-quality standard.</p>
 *
 * <p><b>Trait merge note:</b> this implementation uses the gun definition's
 * inherent traits directly ({@link GunDefinition#traits()}). Full plugin-trait
 * merging via {@code TraitMergeService} will be integrated in a later
 * subtask; until then plugin-installed traits do not appear in the snapshot.</p>
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
        // Step 3: ShootPredicate — abort on first failure (reason shown to player).
        if (!runPredicates(player, gunStack)) {
            return;
        }
        // Step 4: PreShootEvent — cancelable; abort without consuming fire-rate.
        if (!firePreShootEvent(player, gunStack)) {
            return;
        }
        // Resolve the gun definition once for snapshot + sound steps.
        GunDefinition gunDefinition =
                GunRegistry.getGun(player.registryAccess(), gunData.gunId()).orElse(null);
        if (gunDefinition == null) {
            ModularShoot.LOGGER.warn(
                    "Gun definition {} not found for player {}; aborting shot.",
                    gunData.gunId(),
                    player.getName().getString());
            return;
        }
        // Step 5: build the frozen attribute/trait snapshot.
        BulletSnapshot snapshot = buildSnapshot(player, gunData, gunDefinition);
        // Step 6: apply server-side spread to the look angle.
        Vec3 direction = applySpread(player, snapshot);
        // Step 7: register the bullet with the per-dimension BulletManager.
        BulletRecord bulletRecord = registerBullet(player, snapshot, direction);
        // Step 8: play the shoot sound at the shooter's position.
        playShootSound(player, gunDefinition);
        // Step 9: PostShootEvent — non-cancelable notification.
        firePostShootEvent(player, gunStack, bulletRecord);
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
     * fire-rate cooldown is <em>not</em> consumed when the event is canceled,
     * so the caller (fire-rate controller) is not notified to update its
     * state — that is handled by the caller only when this method returns
     * {@code true}.</p>
     *
     * @param player   the shooting player
     * @param gunStack the gun item stack being fired
     * @return {@code true} if the event was not canceled and the shot may
     *         continue; {@code false} if a listener canceled the shot
     */
    private static boolean firePreShootEvent(ServerPlayer player, ItemStack gunStack) {
        PreShootEvent event = new PreShootEvent(player, gunStack);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }

    // --- Step 5: Attribute snapshot --------------------------------------

    /**
     * Builds the {@link BulletSnapshot} freezing all nine framework attribute
     * values, the gun's inherent traits, the resolved damage type and the
     * shooter identity (设计文档 §步骤五).
     *
     * @param player         the shooting player (attributes read from here)
     * @param gunData        the gun data (gun id, instance uuid, per-gun state)
     * @param gunDefinition  the gun definition (inherent traits)
     * @return a new {@link BulletSnapshot} ready to be embedded in a bullet
     */
    private static BulletSnapshot buildSnapshot(
            ServerPlayer player, GunData gunData, GunDefinition gunDefinition) {
        Map<ResourceLocation, Double> stats = collectAttributeStats(player);
        // TODO(later): merge plugin traits via TraitMergeService.computeTraits(gunStack, registryAccess).
        Map<ResourceLocation, Boolean> traits = new HashMap<>(gunDefinition.traits());
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
     * Reads the final values of all nine framework attributes from the player
     * and returns them keyed by attribute id.
     *
     * <p>Uses a {@link LinkedHashMap} so the iteration order is deterministic
     * (insertion order), which makes snapshot debugging and log output
     * stable.</p>
     *
     * @param player the player to read attributes from
     * @return a mutable map of attribute id → final double value
     */
    private static Map<ResourceLocation, Double> collectAttributeStats(ServerPlayer player) {
        Map<ResourceLocation, Double> stats = new LinkedHashMap<>();
        stats.put(HIT_DAMAGE_ID, player.getAttributeValue(ModularShootAttributes.HIT_DAMAGE));
        stats.put(FIRE_RATE_ID, player.getAttributeValue(ModularShootAttributes.FIRE_RATE));
        stats.put(RANGE_ID, player.getAttributeValue(ModularShootAttributes.RANGE));
        stats.put(ACCURACY_YAW_ID, player.getAttributeValue(ModularShootAttributes.ACCURACY_YAW));
        stats.put(ACCURACY_PITCH_ID, player.getAttributeValue(ModularShootAttributes.ACCURACY_PITCH));
        stats.put(ENTITY_PENETRATION_ID, player.getAttributeValue(ModularShootAttributes.ENTITY_PENETRATION));
        stats.put(BULLET_SPEED_ID, player.getAttributeValue(ModularShootAttributes.BULLET_SPEED));
        stats.put(BULLET_SIZE_ID, player.getAttributeValue(ModularShootAttributes.BULLET_SIZE));
        stats.put(BLOCK_PENETRATION_ID, player.getAttributeValue(ModularShootAttributes.BLOCK_PENETRATION));
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

    // --- Step 7: Register bullet -----------------------------------------

    /**
     * Creates and registers a {@link BulletRecord} with the per-dimension
     * {@link BulletManager} (设计文档 §步骤七).
     *
     * <p>The bullet spawns at the player's eye position to avoid the body
     * collision box blocking the first tick. A unique bullet id is allocated
     * by the manager before the record is constructed and added.</p>
     *
     * @param player     the shooting player (eye position and level)
     * @param snapshot   the frozen bullet snapshot
     * @param direction  the initial flight direction (post-spread)
     * @return the live, registered {@link BulletRecord}
     */
    private static BulletRecord registerBullet(
            ServerPlayer player, BulletSnapshot snapshot, Vec3 direction) {
        Vec3 position = player.getEyePosition();
        BulletManager manager = BulletManager.get(player.level());
        int bulletId = manager.nextBulletId();
        BulletRecord bulletRecord =
                new BulletRecord(snapshot, player.getUUID(), position, direction, bulletId);
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
        ResourceLocation soundId = gunDefinition.sounds().get(SHOOT_SOUND_SLOT);
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
     * {@link BulletRecord} so that listeners observe the bullet in its
     * initial in-flight state. The event is never canceled; to suppress a
     * shot a listener must use {@link PreShootEvent} instead.</p>
     *
     * @param player       the shooting player
     * @param gunStack     the gun item stack the shot was fired from
     * @param bulletRecord the live bullet record that was registered
     */
    private static void firePostShootEvent(
            ServerPlayer player, ItemStack gunStack, BulletRecord bulletRecord) {
        PostShootEvent event = new PostShootEvent(player, gunStack, bulletRecord);
        NeoForge.EVENT_BUS.post(event);
    }
}
