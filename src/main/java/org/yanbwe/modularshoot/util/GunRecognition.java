package org.yanbwe.modularshoot.util;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.registry.binding.GunItemBindingRegistry;
import org.yanbwe.modularshoot.registry.binding.PluginItemBindingRegistry;

/**
 * Internal dual-channel item recognition core (设计规格 物品绑定系统 §4.1).
 *
 * <p>Centralizes the "is this stack a gun/plugin, and to which definition id
 * does it resolve?" logic so both the public {@code ModularShootAPI} facade
 * and the internal framework services share a single implementation. This
 * keeps the dependency graph one-directional: internal services and the
 * facade both call into this utility instead of internal services depending
 * on the facade (Task 6.1).</p>
 *
 * <p>Recognition runs <b>component channel first</b>
 * ({@code gun_data}/{@code plugin_data}) and falls back to the <b>binding
 * channel</b> ({@code modularshoot:gun_items} / {@code modularshoot:plugin_items}
 * binding tables, Java API + datapack).</p>
 *
 * <p>The class is not instantiable; every method is a static pure read.</p>
 */
public final class GunRecognition {

    private GunRecognition() {
    }

    // ---- Gun recognition -------------------------------------------------

    /**
     * Checks whether the given stack is a gun via the degraded channel
     * (component + Java-API binding only, {@link RegistryAccess#EMPTY}).
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a gun via either channel
     */
    public static boolean isGun(ItemStack stack) {
        return isGun(stack, RegistryAccess.EMPTY, false);
    }

    /**
     * Checks whether the given stack is a gun via the full channel
     * (component + Java API + datapack binding).
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return {@code true} when the stack is a gun via either channel
     */
    public static boolean isGun(ItemStack stack, RegistryAccess access) {
        return isGun(stack, access, true);
    }

    /**
     * Shared gun-recognition core: component channel first, binding channel as
     * fallback. {@code includeDatapack} selects the degraded query (Java-API
     * bindings only) or the full query (Java API + datapack).
     */
    private static boolean isGun(ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        return stack.has(ModularShootDataComponents.GUN_DATA.get())
                || findBoundGunId(stack, access, includeDatapack).isPresent();
    }

    /**
     * Resolves the gun definition id of a gun stack via the full channel:
     * component first, binding table as fallback.
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return the gun definition id, or empty when the stack is not a gun
     */
    public static Optional<ResourceLocation> resolveGunId(
            ItemStack stack, RegistryAccess access) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData != null) {
            return Optional.of(gunData.gunId());
        }
        return findBoundGunId(stack, access, true);
    }

    /**
     * Resolves the gun id bound to the stack's item id via the
     * {@code modularshoot:gun_items} binding table. Returns
     * {@link Optional#empty()} when the item id cannot be resolved or no
     * binding matches.
     */
    private static Optional<ResourceLocation> findBoundGunId(
            ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        return stack.getItemHolder().unwrapKey()
                .map(ResourceKey::location)
                .flatMap(itemId -> GunItemBindingRegistry.getBoundGunId(
                        includeDatapack ? access : RegistryAccess.EMPTY, itemId));
    }

    // ---- Plugin recognition ----------------------------------------------

    /**
     * Checks whether the given stack is a plugin via the degraded channel
     * (component + Java-API binding only, {@link RegistryAccess#EMPTY}).
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a plugin via either channel
     */
    public static boolean isPlugin(ItemStack stack) {
        return isPlugin(stack, RegistryAccess.EMPTY, false);
    }

    /**
     * Checks whether the given stack is a plugin via the full channel
     * (component + Java API + datapack binding).
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return {@code true} when the stack is a plugin via either channel
     */
    public static boolean isPlugin(ItemStack stack, RegistryAccess access) {
        return isPlugin(stack, access, true);
    }

    /**
     * Shared plugin-recognition core: component channel first, binding channel
     * as fallback. See {@link #isGun(ItemStack, RegistryAccess, boolean)} for
     * the {@code includeDatapack} semantics.
     */
    private static boolean isPlugin(ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        return stack.has(ModularShootDataComponents.PLUGIN_DATA.get())
                || findBoundPluginId(stack, access, includeDatapack).isPresent();
    }

    /**
     * Resolves the plugin definition id of a plugin stack via the full channel:
     * component first, binding table as fallback.
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return the plugin definition id, or empty when the stack is not a plugin
     */
    public static Optional<ResourceLocation> resolvePluginId(
            ItemStack stack, RegistryAccess access) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        PluginData pluginData = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        if (pluginData != null) {
            return Optional.of(pluginData.pluginId());
        }
        return findBoundPluginId(stack, access, true);
    }

    /**
     * Resolves the plugin id bound to the stack's item id via the
     * {@code modularshoot:plugin_items} binding table. Returns
     * {@link Optional#empty()} when the item id cannot be resolved or no
     * binding matches.
     */
    private static Optional<ResourceLocation> findBoundPluginId(
            ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        return stack.getItemHolder().unwrapKey()
                .map(ResourceKey::location)
                .flatMap(itemId -> PluginItemBindingRegistry.getBoundPluginId(
                        includeDatapack ? access : RegistryAccess.EMPTY, itemId));
    }
}
