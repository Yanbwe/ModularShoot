package org.yanbwe.modularshoot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.client.render.DynamicOutlineTintRegistry;

@Mod(value = ModularShoot.MODID, dist = Dist.CLIENT)
public class ModularShootClient {
    public ModularShootClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Demo: the visual_gun_prism plugin's whole-gun outline cycles through
        // the hue wheel per frame. Integration mods register their own
        // providers keyed by their plugin ids here in the same way.
        DynamicOutlineTintRegistry.register(
                ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "visual_gun_prism"),
                ModularShootClient::prismOutlineTint);
    }

    /**
     * Demo rainbow outline tint for the {@code visual_gun_prism} plugin: the
     * hue rotates with game time (one full cycle every 60 ticks), so the
     * outline flows smoothly per frame in first person, third person and the
     * inventory GUI alike.
     *
     * @param stack       the gun item stack being rendered (unused by the demo)
     * @param partialTick the current frame's partial tick
     * @return the RGBA tint for this frame
     */
    private static Vector4f prismOutlineTint(ItemStack stack, float partialTick) {
        Level level = Minecraft.getInstance().level;
        float time = (level != null ? level.getGameTime() : 0L) + partialTick;
        float hue = (time * 6.0F) % 360.0F;
        return hsvToRgb(hue, 1.0F, 1.0F);
    }

    /**
     * Converts an HSV colour to RGBA (0..1).
     *
     * @param hue the hue in degrees (0..360)
     * @param sat the saturation (0..1)
     * @param val the value (0..1)
     * @return the equivalent opaque RGBA colour
     */
    private static Vector4f hsvToRgb(float hue, float sat, float val) {
        float c = val * sat;
        float x = c * (1.0F - Math.abs((hue / 60.0F) % 2.0F - 1.0F));
        float m = val - c;
        float r;
        float g;
        float b;
        if (hue < 60.0F) {
            r = c;
            g = x;
            b = 0.0F;
        } else if (hue < 120.0F) {
            r = x;
            g = c;
            b = 0.0F;
        } else if (hue < 180.0F) {
            r = 0.0F;
            g = c;
            b = x;
        } else if (hue < 240.0F) {
            r = 0.0F;
            g = x;
            b = c;
        } else if (hue < 300.0F) {
            r = x;
            g = 0.0F;
            b = c;
        } else {
            r = c;
            g = 0.0F;
            b = x;
        }
        return new Vector4f(r + m, g + m, b + m, 1.0F);
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
