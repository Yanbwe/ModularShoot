package org.yanbwe.modularshoot.component;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Deferred register for framework {@link DataComponentType}s.
 *
 * <p>Currently registers the {@code gun_data} component that stores the
 * per-stack {@link GunData}. Plugin data and other components will be added
 * in later milestones.</p>
 */
public final class ModularShootDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ModularShoot.MODID);

    /** Per-gun runtime data: gun id, instance uuid, installed plugins, modifier version, state map. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GunData>> GUN_DATA =
            DATA_COMPONENTS.registerComponentType("gun_data", builder -> builder.persistent(GunData.CODEC));

    private ModularShootDataComponents() {
    }
}
