package org.yanbwe.modularshoot.client.render;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.client.PlayerShootStateManager;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;

/**
 * Resolves which texture a gun should display at render time based on its
 * {@link ShootTextureMode} and the holding player's shoot state.
 *
 * <p>This is the single decision point that drives shoot-texture switching
 * (设计文档 §枪械纹理, line 1293-1298). It is a pure query against the
 * client-side {@link PlayerShootStateManager} state and the immutable
 * {@link GunDefinition}; it performs no rendering and mutates no state.</p>
 *
 * <p><b>Selection rules:</b></p>
 * <ul>
 *   <li><b>No shoot texture defined</b> ({@link GunDefinition#shootTexture()}
 *       is empty) &mdash; the base {@link GunDefinition#texture()} is always
 *       returned, regardless of mode or player state. The
 *       {@code shoot_texture_mode} field has no effect in this case.</li>
 *   <li><b>{@link ShootTextureMode#PER_SHOT}</b> &mdash; the shoot texture is
 *       shown while the player's {@code shootAnimTimer} is greater than zero
 *       (i.e. for the few ticks immediately following a shot), then reverts
 *       to the base texture. On high-rate weapons the texture flickers per
 *       round, giving a crisp "flash per shot" feel.</li>
 *   <li><b>{@link ShootTextureMode#WHILE_FIRING}</b> &mdash; the shoot
 *       texture is held for as long as the player's {@code isFiring} flag is
 *       true (attack key held with a gun in the main hand), then reverts on
 *       release. Suited to full-auto weapons where per-shot flicker would be
 *       distracting.</li>
 * </ul>
 *
 * <p><b>Why {@code isFiring} is separate from the timer for
 * {@code WHILE_FIRING}:</b> the animation timer peaks for only a few ticks
 * and decays quickly; on low-fire-rate guns (e.g. 0.5/s, 40-tick interval)
 * the timer would hit zero between shots, causing the texture to flicker
 * back to base &mdash; contradicting the "hold while firing" intent. The
 * independent {@code isFiring} flag stays true for the entire trigger-press
 * duration (设计文档 line 1354-1357).</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see PlayerShootStateManager
 * @see GunDefinition
 * @see ShootTextureMode
 */
public final class ShootTextureResolver {

    private ShootTextureResolver() {
    }

    /**
     * Resolves the texture path that the renderer should use for the given
     * gun and player.
     *
     * <p>The returned {@link ResourceLocation} is the exact texture path
     * stored in the {@link GunDefinition} &mdash; either
     * {@link GunDefinition#texture()} (base) or
     * {@link GunDefinition#shootTexture()} (shoot). The caller is
     * responsible for mapping this texture path to a baked model and feeding
     * it into the vanilla item render pipeline.</p>
     *
     * @param gunDef     the gun definition carrying the texture paths and
     *                   the shoot-texture mode; must not be {@code null}
     * @param playerUuid the uuid of the player holding the gun, used to
     *                   query {@link PlayerShootStateManager}; must not be
     *                   {@code null}
     * @return the texture path to render (never {@code null})
     */
    public static ResourceLocation resolveTexture(GunDefinition gunDef, UUID playerUuid) {
        Objects.requireNonNull(gunDef, "gunDef");
        Objects.requireNonNull(playerUuid, "playerUuid");

        // No shoot texture defined → base texture throughout, mode is irrelevant.
        if (gunDef.shootTexture().isEmpty()) {
            return gunDef.texture();
        }

        ResourceLocation shootTexture = gunDef.shootTexture().get();
        PlayerShootStateManager stateManager = PlayerShootStateManager.getInstance();

        if (gunDef.shootTextureMode() == ShootTextureMode.PER_SHOT) {
            // timer > 0 → just fired → show shoot texture; timer == 0 → base.
            return stateManager.getAnimTimer(playerUuid) > 0f
                    ? shootTexture
                    : gunDef.texture();
        }

        // WHILE_FIRING: isFiring → shoot texture; released → base texture.
        return stateManager.isFiring(playerUuid)
                ? shootTexture
                : gunDef.texture();
    }
}
