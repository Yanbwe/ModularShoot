package org.yanbwe.modularshoot.client.tooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Modifier-key state access that is safe when no live Minecraft window exists
 * (main-menu population, headless tests).
 *
 * <p>{@link Screen#hasControlDown()} / {@link Screen#hasAltDown()} /
 * {@link Screen#hasShiftDown()} dereference
 * {@code Minecraft.getInstance().getWindow()}, which NPEs when the game window
 * is absent (e.g. the headless JUnit environment exercising the real
 * {@code buildStateBar} path). In the real client the window is always present,
 * so this wrapper returns exactly what {@code Screen} would — it only guards
 * the null-window case, where "no modifier is held" ({@code false}) is the
 * correct, stable answer.</p>
 */
final class ModifierKeys {
    private ModifierKeys() {
    }

    /**
     * @return {@code true} when Ctrl is held; {@code false} when no live window
     *         is available
     */
    static boolean controlDown() {
        return hasWindow() && Screen.hasControlDown();
    }

    /**
     * @return {@code true} when Alt is held; {@code false} when no live window
     *         is available
     */
    static boolean altDown() {
        return hasWindow() && Screen.hasAltDown();
    }

    /**
     * @return {@code true} when Shift is held; {@code false} when no live window
     *         is available
     */
    static boolean shiftDown() {
        return hasWindow() && Screen.hasShiftDown();
    }

    private static boolean hasWindow() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getWindow() != null;
    }
}
