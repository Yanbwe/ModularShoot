package org.yanbwe.modularshoot.attribute;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads a single player-side attribute value by its logical id.
 *
 * <p>Player-side guns no longer carry the framework-computed item
 * {@code ATTRIBUTE_MODIFIERS} component; the mounting responsibility is
 * transferred to the declaring side. This reader is the bridge used by the
 * tooltip layer to obtain the final value without requiring a real
 * {@link net.minecraft.world.entity.player.Player} entity — implementations
 * may read from synced data, a player entity, or any other declaring-side
 * source.</p>
 *
 * <p>The returned value is keyed by the logical ModularShoot attribute id
 * (e.g. {@code modularshoot:hit_damage}), not by the bound vanilla attribute
 * id. A {@link RegistryAccess} is supplied so implementations can resolve
 * definitions or registries when needed.</p>
 */
@FunctionalInterface
public interface PlayerAttributeValueReader {

    /**
     * Reads the final value for a logical attribute id.
     *
     * @param logicalId the logical ModularShoot attribute id; must not be
     *                  {@code null}
     * @param access    the runtime registry view; must not be {@code null}
     * @return the final attribute value
     */
    double read(ResourceLocation logicalId, RegistryAccess access);
}