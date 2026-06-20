package org.yanbwe.modularshoot;

import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.creative.ModularShootCreativeTabs;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.state.ModularShootAttachmentTypes;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(ModularShoot.MODID)
public class ModularShoot {
    public static final String MODID = "modularshoot";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public ModularShoot(IEventBus modEventBus, ModContainer modContainer) {
        ModularShootAttributes.ATTRIBUTES.register(modEventBus);
        ModularShootDataComponents.DATA_COMPONENTS.register(modEventBus);
        ModularShootAttachmentTypes.ATTACHMENT_TYPES.register(modEventBus);
        BLOCKS.register(modEventBus);
        ModularShootItems.init();
        ITEMS.register(modEventBus);
        ModularShootCreativeTabs.init();
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
