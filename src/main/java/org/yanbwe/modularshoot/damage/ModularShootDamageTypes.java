package org.yanbwe.modularshoot.damage;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Framework preset damage types registered to the vanilla {@code DAMAGE_TYPE} registry.
 *
 * <p>The framework ships a single data-driven damage type {@code modularshoot:bullet_damage}
 * as the default for bullets. It is declared in
 * {@code data/modularshoot/damage_type/bullet_damage.json} and loaded by vanilla's data-driven
 * damage type pipeline — no code-side registration is required.
 *
 * <p>The bullet damage type is configured for predictable, consistent behavior:
 * <ul>
 *   <li>{@code scaling = never} — damage does not scale with difficulty;</li>
 *   <li>{@code exhaustion = 0.0} — bullets drain no hunger;</li>
 *   <li>{@code effects = hurt} — the standard hurt sound plays (no burning/freezing audio);</li>
 *   <li>{@code message_id = bullet} — death messages resolve to {@code death.attack.bullet}.</li>
 * </ul>
 *
 * <p>Other mods may register their own damage types via the vanilla {@code damage_type} data pack
 * directory and swap them onto a {@link org.yanbwe.modularshoot.bullet.BulletSnapshot} through
 * feature hooks, as described in the design document.
 */
public final class ModularShootDamageTypes {

    /**
     * Resource key of the framework's preset bullet damage type
     * ({@code modularshoot:bullet_damage}).
     */
    public static final ResourceKey<DamageType> BULLET = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet_damage"));

    private ModularShootDamageTypes() {
    }

    /**
     * Resolves the {@link Holder} of the preset {@link #BULLET} damage type from the given
     * registry access.
     *
     * <p>Use this to construct a {@link net.minecraft.world.damagesource.DamageSource} when
     * applying bullet damage, e.g.
     * {@code new DamageSource(ModularShootDamageTypes.holderOrThrow(level.registryAccess()), shooter)}.
     *
     * @param access the registry access (typically {@code level.registryAccess()})
     * @return the holder of the bullet damage type
     * @throws IllegalStateException if the bullet damage type is missing from the registry
     */
    public static Holder<DamageType> holderOrThrow(RegistryAccess access) {
        return access.registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BULLET);
    }
}
