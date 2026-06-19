package org.yanbwe.modularshoot.plugin;

import java.util.Optional;

/**
 * Result of a plugin installation validation check.
 *
 * <p>A {@link #success()} result carries no message and signals that the
 * checked condition is satisfied. An {@link #error(String)} result carries a
 * human-readable error message that the framework surfaces to the player when
 * installation is aborted (设计文档 §自定义安装校验).</p>
 *
 * <p>This type is immutable and null-hostile: factory methods never return
 * a {@code null} result, and {@link #error(String)} wraps the message in an
 * {@link Optional}. Use {@link #errorMessage()} to obtain the optional
 * message for display.</p>
 *
 * @param valid        {@code true} when the validation passed; {@code false}
 *                     when it failed and installation should be aborted
 * @param errorMessage the optional human-readable failure message; empty for
 *                     a successful result
 */
public record ValidationResult(boolean valid, Optional<String> errorMessage) {

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
     * Factory for a failing validation result.
     *
     * @param message the human-readable error message shown to the player;
     *                must not be {@code null}
     * @return a new {@link ValidationResult} with {@code success = false} and
     *         the given message wrapped in an {@link Optional}
     */
    public static ValidationResult error(String message) {
        return new ValidationResult(false, Optional.of(message));
    }
}
