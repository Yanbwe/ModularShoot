package org.yanbwe.modularshoot.plugin;

import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * Result of a plugin installation validation check.
 *
 * <p>A {@link #success()} result carries no message and signals that the
 * checked condition is satisfied. An {@link #error(Component)} result carries
 * a player-facing error message that the framework surfaces when installation
 * is aborted (设计文档 §自定义安装校验). Messages are {@link Component}
 * values so failure reasons can be localized via translation keys; the
 * {@link #error(String)} overload wraps literal text for third-party
 * validators that have no translation keys.</p>
 *
 * <p>This type is immutable and null-hostile: factory methods never return
 * a {@code null} result, and {@link #error(Component)} wraps the message in an
 * {@link Optional}. Use {@link #errorMessage()} to obtain the optional
 * message for display.</p>
 *
 * @param valid        {@code true} when the validation passed; {@code false}
 *                     when it failed and installation should be aborted
 * @param errorMessage the optional player-facing failure message; empty for
 *                     a successful result
 */
public record ValidationResult(boolean valid, Optional<Component> errorMessage) {

    /**
     * Factory for a passing validation result.
     *
     * @return a new {@link ValidationResult} with {@code success = true} and
     *         no error message
     */
    public static ValidationResult success() {
        return new ValidationResult(true, Optional.empty());
    }

    /**
     * Factory for a failing validation result with a component message.
     *
     * <p>Preferred over {@link #error(String)} when the message comes from a
     * translation key, since {@link Component} keeps the localized form and
     * any embedded colour codes alive.</p>
     *
     * @param message the player-facing error message shown to the player;
     *                must not be {@code null}
     * @return a new {@link ValidationResult} with {@code success = false} and
     *         the given message wrapped in an {@link Optional}
     */
    public static ValidationResult error(Component message) {
        return new ValidationResult(false, Optional.of(message));
    }

    /**
     * Factory for a failing validation result with a literal string message.
     *
     * <p>Retained for compatibility with third-party {@link PluginValidator}
     * implementations that still return plain strings; the string is wrapped
     * in {@link Component#literal(String)}.</p>
     *
     * @param message the human-readable error message shown to the player;
     *                must not be {@code null}
     * @return a new {@link ValidationResult} with {@code success = false} and
     *         the given message wrapped in an {@link Optional}
     */
    public static ValidationResult error(String message) {
        return new ValidationResult(false, Optional.of(Component.literal(message)));
    }
}
