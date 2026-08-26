package org.yanbwe.modularshoot.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common-side {@link ModConfigSpec} — {@code modularshoot-common.toml}.
 *
 * <p>Holds the server-side bullet-sync tuning knobs (审查 O7 — 同步参数此前全部硬编码，
 * 服务器无法调优): the distance bands and per-band update intervals of the
 * incremental delta sync, plus the periodic force-full-sync interval. All
 * values are server-authoritative: they only change how often the server
 * emits sync packets; clients need no matching values.</p>
 *
 * <p>Registered with {@link net.neoforged.fml.config.ModConfig.Type#COMMON}
 * in the common mod constructor; live values are read through
 * {@code ModConfigSpec.ConfigValue#get()}, so edits take effect without a
 * restart.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see org.yanbwe.modularshoot.network.BulletDeltaQuantizer
 */
public final class ModularShootCommonConfig {

    /** The built common config spec, registered via {@code ModContainer#registerConfig}. */
    public static final ModConfigSpec SPEC;

    /** Close-band radius (blocks): bullets inside may send a delta every tick. */
    private static final ModConfigSpec.DoubleValue CLOSE_DISTANCE;

    /** Mid-band radius (blocks): bullets between close and mid use the mid interval. */
    private static final ModConfigSpec.DoubleValue MID_DISTANCE;

    /** Minimum ticks between delta updates for mid-band bullets. */
    private static final ModConfigSpec.IntValue MID_INTERVAL_TICKS;

    /** Minimum ticks between delta updates for bullets beyond the mid band. */
    private static final ModConfigSpec.IntValue FAR_INTERVAL_TICKS;

    /** Ticks between periodic force-full-sync (drift recovery) packets. */
    private static final ModConfigSpec.LongValue FULL_SYNC_INTERVAL_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("bulletSync");

        CLOSE_DISTANCE = builder
                .comment(
                        "Close-band radius in blocks: bullets within this distance of a player",
                        "may send position updates every tick.",
                        "Range 8-128, default 32.")
                .defineInRange("closeDistance", 32.0, 8.0, 128.0);

        MID_DISTANCE = builder
                .comment(
                        "Mid-band radius in blocks: bullets between closeDistance and this value",
                        "are throttled to midIntervalTicks. Must be >= closeDistance.",
                        "Range 16-256, default 64.")
                .defineInRange("midDistance", 64.0, 16.0, 256.0);

        MID_INTERVAL_TICKS = builder
                .comment(
                        "Minimum server ticks between delta updates for mid-band bullets.",
                        "Range 1-20, default 2.")
                .defineInRange("midIntervalTicks", 2, 1, 20);

        FAR_INTERVAL_TICKS = builder
                .comment(
                        "Minimum server ticks between delta updates for bullets beyond midDistance.",
                        "Range 1-40, default 4.")
                .defineInRange("farIntervalTicks", 4, 1, 40);

        FULL_SYNC_INTERVAL_TICKS = builder
                .comment(
                        "Ticks between periodic force-full-sync packets (drift recovery for",
                        "dropped deltas). 100 ticks = 5 seconds.",
                        "Range 20-1200, default 100.")
                .defineInRange("fullSyncIntervalTicks", 100L, 20L, 1200L);

        builder.pop();
        SPEC = builder.build();
    }

    private ModularShootCommonConfig() {
    }

    /** Returns the close-band radius in blocks. */
    public static double getCloseDistance() {
        return CLOSE_DISTANCE.get();
    }

    /** Returns the mid-band radius in blocks (never below the close band). */
    public static double getMidDistance() {
        return Math.max(MID_DISTANCE.get(), getCloseDistance());
    }

    /** Returns the mid-band minimum tick interval between delta updates. */
    public static int getMidIntervalTicks() {
        return MID_INTERVAL_TICKS.get();
    }

    /** Returns the far-band minimum tick interval between delta updates (never below the mid band). */
    public static int getFarIntervalTicks() {
        return Math.max(FAR_INTERVAL_TICKS.get(), getMidIntervalTicks());
    }

    /** Returns the force-full-sync interval in ticks. */
    public static long getFullSyncIntervalTicks() {
        return FULL_SYNC_INTERVAL_TICKS.get();
    }
}
