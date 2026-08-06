package org.yanbwe.modularshoot.plugin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Functional interface for custom plugin installation validators.
 *
 * <p>Third-party mods register implementations of this interface via
 * {@link PluginValidationService#registerPluginValidator(PluginValidator)}
 * to append bespoke installation rules on top of the framework's default
 * checks (tag intersection, slot capacity, exclusive group). All registered
 * validators run <strong>after</strong> the framework default validation
 * passes; the first validator that returns a failing
 * {@link ValidationResult} aborts the installation and its error message is
 * shown to the player (设计文档 §自定义安装校验).</p>
 *
 * <p>Implementations should be pure and side-effect free: they receive the
 * player, the gun stack, the candidate plugin id and the registry view as
 * read-only inputs and must return a {@link ValidationResult} without
 * mutating the stack or any global state. The install path runs once on the
 * client side and once on the server side (each side validates
 * independently), so validators execute twice per install attempt — read
 * only synced state (e.g. persisted components) and never write any state
 * from inside a validator. Heavy work (registry lookups, NBT reads) should
 * be minimised since validators run on the server install path.</p>
 */
@FunctionalInterface
public interface PluginValidator {

    /**
     * Checks whether the given plugin may be installed on the given gun.
     *
     * @param player         the player performing (or requesting) the
     *                       installation; supplies player-state context such
     *                       as currency, permissions or cooldowns that a
     *                       validator may want to gate on
     * @param gun            the target gun item stack; carries {@code gun_data} when
     *                       it is a {@code modularshoot:gun} stack
     * @param pluginId       the candidate plugin definition id in the
     *                       {@code modularshoot:plugins} registry
     * @param registryAccess the runtime registry view; enables data-driven
     *                       validation against the plugin/type/gun
     *                       definitions
     * @return a {@link ValidationResult}; {@link ValidationResult#success()}
     *         to allow installation, or
     *         {@link ValidationResult#error(String)} with a player-facing
     *         message to abort it
     */
    ValidationResult validate(
            Player player, ItemStack gun, ResourceLocation pluginId, RegistryAccess registryAccess);
}
