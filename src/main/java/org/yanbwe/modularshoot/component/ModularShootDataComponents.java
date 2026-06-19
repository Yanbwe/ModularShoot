package org.yanbwe.modularshoot.component;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Deferred register for framework {@link DataComponentType}s.
 *
 * <p>Registers the per-stack data components used by the framework:
 * {@code gun_data} stores the runtime {@link GunData} of a
 * {@code modularshoot:gun} stack, and {@code plugin_data} stores the
 * {@link PluginData} that binds a {@code modularshoot:plugin} stack to its
 * definition in the {@code modularshoot:plugins} registry.</p>
 */
public final class ModularShootDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ModularShoot.MODID);

    /** Per-gun runtime data: gun id, instance uuid, installed plugins, modifier version, state map. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GunData>> GUN_DATA =
            DATA_COMPONENTS.registerComponentType("gun_data", builder -> builder.persistent(GunData.CODEC));

    /** Plugin stack binding: the plugin definition id this stack refers to in the {@code modularshoot:plugins} registry. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PluginData>> PLUGIN_DATA =
            DATA_COMPONENTS.registerComponentType("plugin_data", builder -> builder.persistent(PluginData.CODEC));

    private ModularShootDataComponents() {
    }
}
