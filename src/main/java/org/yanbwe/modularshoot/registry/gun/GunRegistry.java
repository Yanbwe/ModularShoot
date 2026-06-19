package org.yanbwe.modularshoot.registry.gun;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query and factory API for the {@code modularshoot:guns} dynamic registry.
 *
 * <p>The gun registry is a datapack-driven dynamic registry registered via
 * NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#GUNS_KEY}). Its contents are populated from
 * datapack JSONs when a world is loaded and synced to clients on connect;
 * it is <strong>empty on the main menu</strong> (设计文档 §加载顺序). Every
 * query method therefore takes a {@link RegistryAccess} (or a {@link Level}
 * that provides one) so the caller supplies the correct runtime view.</p>
 *
 * <p>This class provides only read access and {@link ItemStack} creation.
 * Java API registration into the dynamic registry is handled by the
 * DataPackRegistry mechanism during world load; a full Java write API is
 * deferred to M6 (设计文档 §注册冲突与覆盖).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class GunRegistry {
    private GunRegistry() {
    }

    /**
     * Looks up a gun definition by id in the {@code modularshoot:guns}
     * registry.
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param gunId          the gun definition id, e.g.
     *                       {@code modularshoot:sniper_rifle}
     * @return the matching {@link GunDefinition}, or {@code Optional.empty()}
     *         when the registry is absent or the id is not registered
     */
    public static Optional<GunDefinition> getGun(RegistryAccess registryAccess, ResourceLocation gunId) {
        return registryAccess.registry(ModularShootRegistries.GUNS_KEY)
                .flatMap(registry -> registry.getOptional(gunId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level  the world providing the {@link RegistryAccess}
     * @param gunId  the gun definition id
     * @return the matching {@link GunDefinition}, or {@code Optional.empty()}
     *         when the id is not registered
     */
    public static Optional<GunDefinition> getGun(Level level, ResourceLocation gunId) {
        return getGun(level.registryAccess(), gunId);
    }

    /**
     * Returns every registered gun id in the {@code modularshoot:guns}
     * registry.
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all gun ids; an empty set when the
     *         registry is absent (e.g. on the main menu)
     */
    public static Set<ResourceLocation> getAllGunIds(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.GUNS_KEY)
                .map(Registry::keySet)
                .orElse(Set.of());
    }

    /**
     * Creates a gun {@link ItemStack} for the given gun id with a freshly
     * generated random instance uuid.
     *
     * <p>The stack is backed by the framework {@code modularshoot:gun} item
     * and carries a {@link GunData} component identifying the gun definition.
     * No registry lookup is performed, so this is safe to call before a world
     * is loaded (e.g. for creative tabs).</p>
     *
     * @param gunId the gun definition id to bind to the stack
     * @return a new {@link ItemStack} with {@code gun_data} set
     */
    public static ItemStack createGunStack(ResourceLocation gunId) {
        return createGunStack(gunId, UUID.randomUUID());
    }

    /**
     * Creates a gun {@link ItemStack} for the given gun id with a caller
     * supplied instance uuid.
     *
     * <p>Intended for tests or other scenarios that require deterministic
     * uuids. Prefer {@link #createGunStack(ResourceLocation)} for general
     * use.</p>
     *
     * @param gunId       the gun definition id to bind to the stack
     * @param instanceUuid the per-stack instance uuid
     * @return a new {@link ItemStack} with {@code gun_data} set
     */
    public static ItemStack createGunStack(ResourceLocation gunId, UUID instanceUuid) {
        ItemStack stack = new ItemStack(ModularShootItems.GUN_ITEM.get());
        stack.set(ModularShootDataComponents.GUN_DATA.get(), GunData.create(gunId, instanceUuid));
        return stack;
    }
}
