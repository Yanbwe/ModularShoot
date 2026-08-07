package org.yanbwe.modularshoot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.yanbwe.modularshoot.client.config.ModularShootClientConfig;

@Mod(value = ModularShoot.MODID, dist = Dist.CLIENT)
public class ModularShootClient {
    public ModularShootClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // First client config: near-camera bullet translucency (系统七
        // §近相机距离透明度). Editable from Options → Mods → ModularShoot.
        container.registerConfig(ModConfig.Type.CLIENT, ModularShootClientConfig.SPEC);
    }
}
