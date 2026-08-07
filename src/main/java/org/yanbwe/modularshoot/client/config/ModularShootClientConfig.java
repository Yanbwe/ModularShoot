package org.yanbwe.modularshoot.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side {@link ModConfigSpec} — the framework's first configuration
 * file ({@code modularshoot-client.toml}), editable from the in-game config
 * screen ({@code Options → Mods → ModularShoot}, via the
 * {@code IConfigScreenFactory} registered in {@code ModularShootClient}).
 *
 * <p>Currently holds the near-camera bullet translucency settings
 * (系统七 §近相机距离透明度): the fade is applied to every bullet whose
 * distance to the camera is below the configured fade distance, using a
 * {@code smoothstep} curve from {@code minOpacity} at distance 0 to fully
 * opaque at {@code fadeDistance}.</p>
 *
 * <p><strong>Client-only class.</strong> Registered with
 * {@link net.neoforged.neoforge.common.ModConfig.Type#CLIENT} in the
 * {@code @Mod(dist = Dist.CLIENT)} constructor; never loaded on dedicated
 * servers.</p>
 *
 * <p>All values are read through {@code ModConfigSpec.ConfigValue#get()},
 * which returns the live value — edits made in the in-game config screen
 * take effect on the next render frame without a restart.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see org.yanbwe.modularshoot.client.render.DistanceAlphaCurve
 */
public final class ModularShootClientConfig {

    /** The built client config spec, registered via {@code ModContainer#registerConfig}. */
    public static final ModConfigSpec SPEC;

    /** Master switch — when off, bullets are always drawn fully opaque. */
    private static final ModConfigSpec.BooleanValue ENABLE_NEAR_TRANSLUCENCY;

    /** Distance (blocks) at which a bullet becomes fully opaque. */
    private static final ModConfigSpec.DoubleValue FADE_DISTANCE;

    /** Opacity at distance 0 (the minimum a bullet can be drawn with). */
    private static final ModConfigSpec.DoubleValue MIN_OPACITY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLE_NEAR_TRANSLUCENCY = builder
                .comment(
                        "子弹靠近摄像机时半透明渲染（近相机距离透明度）。",
                        "关闭后子弹始终完全不透明。")
                .define("nearTranslucency.enabled", true);

        FADE_DISTANCE = builder
                .comment(
                        "淡出距离（格）：子弹距离摄像机达到该值后完全不透明。",
                        "距离 0 处为最小不透明度，中间按 smoothstep 曲线平滑过渡。",
                        "范围 0.5–16.0，默认 4.0。")
                .defineInRange("nearTranslucency.fadeDistance", 4.0, 0.5, 16.0);

        MIN_OPACITY = builder
                .comment(
                        "最小不透明度（0.05–1.0）：子弹紧贴摄像机（距离 0）时的不透明度。",
                        "设为 1.0 等效关闭淡出效果。默认 0.2（即 20%）。")
                .defineInRange("nearTranslucency.minOpacity", 0.2, 0.05, 1.0);

        SPEC = builder.build();
    }

    private ModularShootClientConfig() {
    }

    /** Returns whether the near-camera translucency feature is enabled. */
    public static boolean isNearTranslucencyEnabled() {
        return ENABLE_NEAR_TRANSLUCENCY.get();
    }

    /** Returns the fade distance in blocks (opacity reaches 1.0 here). */
    public static double getFadeDistance() {
        return FADE_DISTANCE.get();
    }

    /** Returns the minimum opacity at distance 0, in {@code [0.05, 1.0]}. */
    public static float getMinOpacity() {
        return MIN_OPACITY.get().floatValue();
    }
}
