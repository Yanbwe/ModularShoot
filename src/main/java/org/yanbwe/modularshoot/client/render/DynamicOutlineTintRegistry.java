package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;

/**
 * Client-side registry of per-plugin dynamic gun-outline tint providers
 * (设计文档 §动态描边).
 *
 * <p>Gun outlines are normally baked into the composited texture with their
 * static {@code gun_outline} colour. Integration mods that want a
 * <em>dynamically coloured</em> outline (e.g. a continuously shifting
 * rainbow) register a {@link GunOutlineTintProvider} keyed by the id of the
 * plugin that carries the outline: whenever a gun with that plugin installed
 * is rendered, {@link GunItemRenderer} resolves the tint and draws the
 * white outline mask multiplied by it, per frame, without touching the
 * cached textures.</p>
 *
 * <p><b>Registration:</b> call {@link #register} during client
 * initialisation (e.g. in the {@code Dist.CLIENT} mod constructor). The map
 * is a {@link ConcurrentHashMap}, so registration is safely published to the
 * render thread that reads it. {@link #resolve} is called on the render
 * thread for every gun draw; it returns the first provider whose plugin id
 * appears in the gun's installed-plugin list (install order).</p>
 *
 * <p><b>中文说明：</b>本注册表是客户端专用。集成模组在客户端初始化时以
 * 插件 id 为键注册变色 provider，之后凡安装该插件的枪械，其描边在渲染时
 * 逐帧取 provider 的颜色（绘制遮罩并乘以该颜色），缓存纹理无需重建。
 * 未注册 provider 的插件描边保持静态烘焙颜色，行为不变。</p>
 */
public final class DynamicOutlineTintRegistry {

    private static final Map<ResourceLocation, GunOutlineTintProvider> PROVIDERS =
            new ConcurrentHashMap<>();

    private DynamicOutlineTintRegistry() {
    }

    /**
     * Supplies the per-frame outline tint for a gun being rendered.
     *
     * <p>Called once per gun draw on the render thread. The returned colour
     * multiplies the white outline mask (RGBA in 0..1; alpha is honoured, so
     * a provider may also fade the outline).</p>
     */
    @FunctionalInterface
    public interface GunOutlineTintProvider {

        /**
         * @param stack       the gun item stack being rendered
         * @param partialTick the current frame's partial tick (0..1),
         *                    typically combined with
         *                    {@code level.getGameTime()} for time-based
         *                    effects
         * @return the outline tint colour (RGBA, 0..1); must not be
         *         {@code null}
         */
        Vector4f getColor(ItemStack stack, float partialTick);
    }

    /**
     * Registers a dynamic tint provider for the given plugin id.
     *
     * <p>Registering a provider for a plugin id makes <em>every</em> gun
     * carrying that plugin render its outline with the provider's per-frame
     * colour. The plugin does not need to be installed at registration time
     * (the datapack registry may even be empty on the main menu); the lookup
     * happens per render. A later registration for the same plugin id
     * replaces the earlier one.</p>
     *
     * @param pluginId the id of the plugin whose gun outline becomes dynamic;
     *                 must not be {@code null}
     * @param provider the per-frame colour supplier; must not be
     *                 {@code null}
     */
    public static void register(ResourceLocation pluginId, GunOutlineTintProvider provider) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.put(pluginId, provider);
    }

    /**
     * Resolves the dynamic outline tint for a gun's installed plugins, in
     * install order.
     *
     * <p>Iterates the plugin id list (the gun's installed plugins that carry
     * a {@code gun_outline}) and returns the tint of the first plugin with a
     * registered provider. When no installed plugin has a provider, the
     * outline stays static (baked colour) and {@code Optional.empty()} is
     * returned — the caller then skips the mask pass entirely.</p>
     *
     * @param pluginIds    the gun's outline-carrying plugin ids, in install
     *                     order; must not be {@code null}
     * @param stack        the gun item stack being rendered (passed to the
     *                     provider)
     * @param partialTick  the current frame's partial tick (passed to the
     *                     provider)
     * @return the first matching provider's tint, or empty when no installed
     *         plugin has a registered provider
     */
    public static Optional<Vector4f> resolve(
            List<ResourceLocation> pluginIds, ItemStack stack, float partialTick) {
        for (ResourceLocation pluginId : pluginIds) {
            GunOutlineTintProvider provider = PROVIDERS.get(pluginId);
            if (provider != null) {
                return Optional.ofNullable(provider.getColor(stack, partialTick));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether any of the given plugin ids has a registered tint provider.
     *
     * <p>Unlike {@link #resolve}, this only checks registration and never
     * invokes a provider, so it is a cheap existence query with no caller
     * state or side effects. Used by {@link DynamicGunTextureCache} to decide
     * whether a white outline mask should be built and uploaded at all: the
     * mask exists only to be multiplied by a dynamic per-frame tint, so
     * without a provider for any of the gun's outline-carrying plugins the
     * mask texture is pure waste (审查优化: 描边 mask 懒生成).</p>
     *
     * <p>Package-private: consumed by the texture cache and unit tests.</p>
     *
     * @param pluginIds the outline-carrying plugin ids, in install order;
     *                  must not be {@code null}
     * @return {@code true} when at least one plugin id has a registered
     *         provider
     */
    static boolean hasTintProvider(List<ResourceLocation> pluginIds) {
        for (ResourceLocation pluginId : pluginIds) {
            if (PROVIDERS.containsKey(pluginId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears every registered provider.
     *
     * <p>Package-private: used by unit tests to reset the shared static
     * state. Not intended as a runtime API.</p>
     */
    static void clear() {
        PROVIDERS.clear();
    }
}
