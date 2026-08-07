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
                        "Draw bullets semi-transparent when they fly close to the camera.",
                        "When disabled, bullets are always fully opaque.")
                .translation("modularshoot.configuration.nearTranslucency.enabled")
                .define("nearTranslucency.enabled", true);

        FADE_DISTANCE = builder
                .comment(
                        "Fade distance (blocks): bullets become fully opaque at this distance from the camera.",
                        "At distance 0 the minimum opacity applies, ramping smoothly via a smoothstep curve in between.",
                        "Range 0.5-16.0, default 4.0.")
                .translation("modularshoot.configuration.nearTranslucency.fadeDistance")
                .defineInRange("nearTranslucency.fadeDistance", 4.0, 0.5, 16.0);

        MIN_OPACITY = builder
                .comment(
                        "Minimum opacity (0.05-1.0): the opacity of a bullet at distance 0 (right against the camera).",
                        "Set to 1.0 to effectively disable the fade. Default 0.2 (20%).")
                .translation("modularshoot.configuration.nearTranslucency.minOpacity")
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
