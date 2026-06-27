package org.yanbwe.modularshoot.creative;

import java.util.Set;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Registration and population of the framework creative mode tab
 * {@code modularshoot:modularshoot}.
 *
 * <p>The tab itself is registered into {@link ModularShoot#CREATIVE_MODE_TABS}
 * during mod construction (via {@link #init()}). Its contents are populated at
 * runtime through {@link BuildCreativeModeTabContentsEvent}: every registered
 * gun variant is added as an independent {@link ItemStack} carrying a preset
 * {@code gun_data} component, so the player receives a fully identified gun
 * (with a concrete {@code gunId}) when picking it from the tab — never a blank
 * gun item.</p>
 *
 * <p>Because gun definitions live in a datapack-driven dynamic registry
 * ({@code modularshoot:guns}), the tab is <strong>empty on the main menu</strong>
 * where dynamic registries have not yet been synced. Once a world is loaded the
 * registry is populated and the tab refreshes with all gun variants. This empty
 * main-menu state is the expected behaviour, not a bug
 * (设计文档 §其他 / §主菜单预览阶段).</p>
 *
 * <p>The {@link RegistryAccess} used to enumerate gun ids is obtained from the
 * event's {@link CreativeModeTab.ItemDisplayParameters#holders() display
 * parameters} rather than from the {@code Minecraft} client singleton. This
 * keeps the handler side-agnostic (the event may fire on the server when a mod
 * requests tab contents) and follows the explicit-dependency principle from the
 * project code-quality standard.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootCreativeTabs {

    /**
     * Resource key of the {@code modularshoot:modularshoot} creative tab.
     *
     * <p>Used both as the {@code DeferredRegister} name and as the identity
     * check inside {@link #onBuildCreativeTab(BuildCreativeModeTabContentsEvent)}.</p>
     */
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "modularshoot"));

    /**
     * The framework creative tab.
     *
     * <p>The {@code displayItems} generator is intentionally empty; actual
     * entries are added via {@link BuildCreativeModeTabContentsEvent} so that
     * the runtime {@link RegistryAccess} (available from the event parameters)
     * can be used to enumerate gun variants. The icon uses the framework gun
     * item; M1 accepts the plain item as the icon (设计文档 §其他).</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MODULARSHOOT_TAB =
            ModularShoot.CREATIVE_MODE_TABS.register("modularshoot", () -> CreativeModeTab.builder()
                    .title(Component.translatable("modularshoot.creative_tab"))
                    .icon(() -> new ItemStack(ModularShootItems.GUN_ITEM.get()))
                    .displayItems((params, output) -> {
                    })
                    .build());

    private ModularShootCreativeTabs() {
    }

    /**
     * Forces class initialization so the {@code MODULARSHOOT_TAB} deferred
     * entry is added to {@link ModularShoot#CREATIVE_MODE_TABS} before the
     * register is attached to the mod event bus.
     *
     * <p>Must be called once during mod construction, before
     * {@code CREATIVE_MODE_TABS.register(modEventBus)}. The body is
     * intentionally empty: merely referencing the class triggers Java to run
     * its static initializers, which is where the {@code register(...)} call
     * lives.</p>
     */
    public static void init() {
    }

    /**
     * Populates the {@code modularshoot} tab with one {@link ItemStack} per
     * registered gun and plugin variant.
     *
     * <p>Each gun stack is created via {@link GunRegistry#createGunStack} and
     * carries a preset {@code gun_data} component, so the picked item is a
     * fully identified gun rather than a blank gun item. Plugin stacks are
     * created via {@link PluginRegistry#createPluginStack} with their
     * {@code plugin_data} component pre-set. The registry access
     * is obtained from the event's display parameters
     * ({@link CreativeModeTab.ItemDisplayParameters#holders()}), which is a
     * {@link RegistryAccess} at runtime. On the main menu the dynamic gun
     * registry is absent, so {@link GunRegistry#getAllGunIds} returns an empty
     * set and the tab stays empty until a world is loaded.</p>
     *
     * @param event the creative tab contents build event (fired on the mod bus)
     */
    @SubscribeEvent
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        // Ignore events for other tabs.
        if (!event.getTabKey().equals(TAB_KEY)) {
            return;
        }
        // The holders provider from the event parameters is a RegistryAccess at
        // runtime (passed by the creative inventory screen as
        // localPlayer.level().registryAccess()). Guard with instanceof in case a
        // custom provider without full registry access is supplied.
        if (!(event.getParameters().holders() instanceof RegistryAccess registryAccess)) {
            return;
        }
        Set<ResourceLocation> gunIds = GunRegistry.getAllGunIds(registryAccess);
        for (ResourceLocation gunId : gunIds) {
            ItemStack gunStack = GunRegistry.createGunStack(gunId);
            // Apply attribute modifiers so guns from the creative tab have
            // non-zero stats (fire_rate, etc.). Without this the vanilla base
            // (0) is used, making the gun unable to fire.
            AttributeModifierService.refreshModifiers(gunStack, registryAccess);
            event.accept(gunStack);
        }
        Set<ResourceLocation> pluginIds = PluginRegistry.getAllPluginIds(registryAccess);
        for (ResourceLocation pluginId : pluginIds) {
            ItemStack pluginStack = PluginRegistry.createPluginStack(pluginId);
            event.accept(pluginStack);
        }
    }
}
