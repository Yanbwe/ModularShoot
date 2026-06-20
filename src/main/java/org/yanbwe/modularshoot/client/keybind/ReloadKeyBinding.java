package org.yanbwe.modularshoot.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Registers and tracks the {@code key.modularshoot.reload} key binding
 * (default: R), exposed under an independent {@code modularshoot.category}
 * group in the controls screen.
 *
 * <p><b>Registration:</b> the {@link KeyMapping} is constructed as a
 * {@code public static final} field so other classes can reference the
 * singleton directly, and handed to NeoForge through
 * {@link RegisterKeyMappingsEvent}. That event implements
 * {@code IModBusEvent}, so NeoForge auto-routes the handler to the mod event
 * bus (设计文档 §键位绑定). The class-level
 * {@code @EventBusSubscriber(value = Dist.CLIENT)} ensures the client-only
 * {@link KeyMapping} type is never class-loaded on a dedicated server.</p>
 *
 * <p><b>Press detection:</b> {@link ClientTickEvent.Pre} is polled every
 * client tick. {@link KeyMapping#consumeClick()} drains a single queued
 * press and returns {@code true} for exactly that tick, which is the
 * "single press" semantics the design doc requires. The result is latched
 * into {@link #reloadPressed} so the downstream {@code ReloadEvent}
 * (子任务20) can query it once via {@link #isReloadPressed()} without itself
 * touching the {@link KeyMapping} state.</p>
 *
 * <p><b>Conflict context:</b> {@link KeyConflictContext#IN_GAME} scopes the
 * binding to in-game play, so it never clashes with GUI-scoped keys that also
 * use R. The player can still rebind it freely under
 * Options → Controls → Key Bindings → ModularShoot.</p>
 *
 * @see ClientShootSender for the same {@code value = Dist.CLIENT} subscriber pattern
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class ReloadKeyBinding {

    /** Translation key of the reload binding shown in the controls screen. */
    public static final String TRANSLATION_KEY = "key.modularshoot.reload";

    /** Translation key of the independent ModularShoot key category. */
    public static final String CATEGORY_KEY = "modularshoot.category";

    /**
     * The singleton reload key mapping, defaulting to the R key.
     *
     * <p>Constructed eagerly so the field is usable the moment the class is
     * loaded. The NeoForge-added constructor accepts an
     * {@link KeyConflictContext} together with an {@link InputConstants.Type}
     * and key code, which keeps the declaration self-documenting and avoids
     * the vanilla constructor's implicit {@code UNIVERSAL} context.</p>
     */
    public static final KeyMapping RELOAD_KEY = new KeyMapping(
            TRANSLATION_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_R,
            CATEGORY_KEY);

    /**
     * Latched press flag, {@code true} for exactly one client tick after the
     * reload key is single-pressed while in-game. Reset to {@code false} the
     * following tick (or while a screen is open).
     */
    private static boolean reloadPressed;

    private ReloadKeyBinding() {
    }

    /**
     * Registers the reload key mapping with the client options.
     *
     * <p>Auto-routed to the <em>mod</em> event bus because
     * {@link RegisterKeyMappingsEvent} implements {@code IModBusEvent}.</p>
     *
     * @param event the key-mapping registration event (fired on the mod bus)
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RELOAD_KEY);
    }

    /**
     * Polls the reload key each client tick and latches a single-press flag.
     *
     * <p>{@link KeyMapping#consumeClick()} is invoked <em>every</em> tick
     * regardless of the in-game guard so queued presses never accumulate
     * while a screen is open and replay as a false reload on return to play.
     * The flag is only raised when the press happens in a playable world,
     * matching the {@link KeyConflictContext#IN_GAME} scope.</p>
     *
     * <p>Auto-routed to the <em>game</em> event bus because
     * {@link ClientTickEvent.Pre} is not an {@code IModBusEvent}.</p>
     *
     * @param event the pre client-tick event (unused beyond its presence)
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        boolean clicked = RELOAD_KEY.consumeClick();
        reloadPressed = clicked && isInGame();
    }

    /**
     * Returns whether the reload key was single-pressed this tick.
     *
     * <p>Intended for the {@code ReloadEvent} handler (子任务20) to query once
     * per tick. The flag is {@code true} only for the tick in which the press
     * was consumed and only while in-game; it is {@code false} on every
     * subsequent tick without a new press.</p>
     *
     * @return {@code true} for exactly one tick after an in-game reload press
     */
    public static boolean isReloadPressed() {
        return reloadPressed;
    }

    /**
     * Determines whether the client is currently in a playable world.
     *
     * <p>{@link Minecraft#player} and {@link Minecraft#level} are {@code null}
     * on the main menu and during world transitions, where a reload press is
     * meaningless. Mirrors the guard in {@code ClientShootSender}.</p>
     *
     * @return {@code true} when both the local player and the client level are loaded
     */
    private static boolean isInGame() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.level != null;
    }
}
