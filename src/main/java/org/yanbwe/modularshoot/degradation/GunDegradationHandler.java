package org.yanbwe.modularshoot.degradation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Handles graceful degradation when a gun's {@code gunId} points to a
 * definition that no longer exists in the {@code modularshoot:guns}
 * registry (设计文档 §枪械 gunId 失效降级).
 *
 * <p>When a datapack is removed or a gun definition is deleted, gun stacks
 * that still reference the old id must not crash, must not fire, and must
 * display a clear "unknown gun" indication. This handler centralises all
 * degradation checks so that the tooltip, shooting engine, and attribute
 * modifier service share a single source of truth.</p>
 *
 * <h2>Degradation contract</h2>
 * <ul>
 *   <li><b>Item name</b> — grey "未知枪械 (gunId path part)".</li>
 *   <li><b>Tooltip</b> — only [未知枪械] and the gunId; no state /
 *       attribute / trait / plugin bars.</li>
 *   <li><b>Shooting</b> — silently cancelled; no bullet is spawned; a
 *       rate-limited WARN is logged at most once per player per minute.</li>
 *   <li><b>Modifiers</b> — no framework AttributeModifier is mounted on
 *       the player (handled by {@code AttributeModifierService} which
 *       writes {@code EMPTY} when the definition is missing).</li>
 * </ul>
 *
 * <p>The class is not instantiable; all methods are static.</p>
 *
 * @see DegradationTextures
 */
public final class GunDegradationHandler {

    /** Dedicated subsystem logger; named so operators can filter degradation warnings. */
    private static final Logger LOGGER = LoggerFactory.getLogger("ModularShoot/Degradation");

    /** Per-player minute bucket of the last emitted shoot-silenced warning. */
    private static final Map<String, Long> LAST_WARN_BUCKETS = new ConcurrentHashMap<>();

    /** One minute in milliseconds; used to bucket warnings so each player warns at most once per minute. */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private GunDegradationHandler() {
    }

    /**
     * Checks whether the given gun stack's {@code gunId} points to a
     * definition that does not exist in the {@code modularshoot:guns}
     * registry.
     *
     * <p>Returns {@code false} when the stack is not a gun, carries no
     * {@code gun_data} component, or the referenced definition is present.
     * Returns {@code true} only when the stack is a gun with a non-null
     * {@code gunId} whose definition cannot be found.</p>
     *
     * @param stack          the item stack to inspect; must not be {@code null}
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return {@code true} when the gun definition is missing and the item
     *         should display in degraded mode
     */
    public static boolean isGunDefinitionMissing(ItemStack stack, RegistryAccess registryAccess) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registryAccess, "registryAccess");
        if (!ModularShootAPI.isGun(stack)) {
            return false;
        }
        ResourceLocation gunId = ModularShootAPI.getGunId(stack);
        if (gunId == null) {
            return false;
        }
        return ModularShootAPI.getGunDefinition(registryAccess, gunId).isEmpty();
    }

    /**
     * Builds the degraded display name for a gun whose definition is
     * missing.
     *
     * <p>The name is a grey {@code "未知枪械 (<path>)"} where
     * {@code <path>} is the path portion of the gunId (e.g. for
     * {@code somemod:heavy_rifle} the path is {@code heavy_rifle}).
     * When the stack carries no gunId at all, the placeholder
     * {@code "unknown"} is used.</p>
     *
     * @param stack the gun item stack; must not be {@code null}
     * @return a grey {@link Component} with the degraded name
     */
    public static Component getDegradedName(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ResourceLocation gunId = ModularShootAPI.getGunId(stack);
        String pathPart = gunId != null ? gunId.getPath() : "unknown";
        return Component.literal("未知枪械 (" + pathPart + ")")
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * Checks whether a shot from the given gun should be silently
     * cancelled because the gun definition is missing.
     *
     * <p>When the definition is missing this method logs a rate-limited
     * {@code WARN} (at most once per player per minute, keyed by the
     * player's UUID) and returns {@code true}. The caller is expected to
     * abort the shot immediately without spawning a bullet, playing a
     * sound, or firing events (设计文档 §射击静默失败).</p>
     *
     * @param player         the player attempting to shoot; must not be {@code null}
     * @param stack          the gun item stack being fired; must not be {@code null}
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return {@code true} when the shot must be silently cancelled;
     *         {@code false} when the definition exists and shooting may proceed
     */
    public static boolean shouldSilenceShoot(
            Player player, ItemStack stack, RegistryAccess registryAccess) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registryAccess, "registryAccess");
        if (!isGunDefinitionMissing(stack, registryAccess)) {
            return false;
        }
        warnShootSilenced(player, ModularShootAPI.getGunId(stack));
        return true;
    }

    /**
     * Emits a rate-limited {@code WARN} indicating that a player's shot
     * was silently cancelled because the gun definition is missing.
     *
     * <p>The rate limiter allows at most one warning per player per minute
     * bucket, keyed by the player's UUID string. This prevents log spam
     * when a player repeatedly left-clicks with a degraded gun
     * (设计文档 §每玩家每分钟最多一次).</p>
     *
     * @param player the player whose shot was cancelled
     * @param gunId  the missing gun definition id, or {@code null} when
     *               the stack carried no gunId
     */
    private static void warnShootSilenced(Player player, @Nullable ResourceLocation gunId) {
        final String key = player.getUUID().toString();
        final long currentBucket = System.currentTimeMillis() / WARN_INTERVAL_MS;
        final Long last = LAST_WARN_BUCKETS.get(key);
        if (last != null && last.longValue() == currentBucket) {
            return;
        }
        LAST_WARN_BUCKETS.put(key, currentBucket);
        LOGGER.warn(
                "Gun definition {} not found for player {}; shot silently cancelled.",
                gunId,
                player.getName().getString());
    }
}
