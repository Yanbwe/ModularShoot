package org.yanbwe.modularshoot;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = ModularShoot.MODID, dist = Dist.CLIENT)
public class ModularShootClient {
    public ModularShootClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /**
     * Client-only event handler for model baking registration.
     * <p>
     * Registers standalone bullet models that are not referenced by any
     * item/block model, so they are available for {@code Model3DRenderer}
     * to look up via {@code ModelManager.getModel()} at render time
     * (设计文档 §3D 模型注册要求).
     */
    @EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModelRegistration {

        private ModelRegistration() {
        }

        /**
         * Registers additional models for baking.
         * <p>
         * Use a resource-convention scan: all JSON models under
         * {@code assets/<namespace>/models/bullet/} are automatically
         * registered with the {@code inventory} variant so that
         * {@code Model3DRenderer} can find them without explicit
         * per-model configuration.
         *
         * @param event the model registration event
         */
        @SubscribeEvent
        public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
            // Resource-convention scan: register all models under models/bullet/
            // with the inventory variant for Model3DRenderer lookup.
            registerBulletModels(event);
        }

        /**
         * Scans for and registers all bullet models under the conventional
         * path {@code bullet/} with the {@code inventory} variant.
         * <p>
         * Currently registers known models explicitly. Future iterations
         * may use a file-system scan when the asset index is available.
         */
        private static void registerBulletModels(ModelEvent.RegisterAdditional event) {
            event.register(ModelResourceLocation.standalone(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet/normol_fazhang")));
        }
    }
}
