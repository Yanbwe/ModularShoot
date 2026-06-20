package org.yanbwe.modularshoot.datapack;

import java.util.List;

/**
 * Immutable result of validating a plugin datapack JSON entry.
 *
 * <p>Carries two categories of diagnostic messages (设计文档 §插件数据包 JSON
 * and §数据包JSON加载失败错误处理):</p>
 *
 * <ul>
 *   <li>{@code errors} &mdash; fatal problems that should prevent the entry
 *       from being registered (e.g. a missing required {@code item_icon}).
 *       When {@link #errors} is non-empty, {@link #valid} is {@code false}.</li>
 *   <li>{@code warnings} &mdash; non-fatal issues that allow registration but
 *       should be surfaced to the operator (e.g. an empty {@code tags} array
 *       which makes the plugin unmatchable). {@link #valid} stays {@code true}
 *       when only warnings are present.</li>
 * </ul>
 *
 * <p>This type is null-hostile and immutable: the factory methods copy their
 * list arguments into unmodifiable lists. The {@code DatapackErrorHandler}
 * (created in a later subtask) consumes the returned result to emit the
 * actual {@code WARN}/{@code ERROR} log lines &mdash; the validator itself
 * never logs, keeping it a pure function (设计文档 §错误处理).</p>
 *
 * @param valid    {@code true} when no fatal errors were found; {@code false}
 *                 when {@link #errors} is non-empty
 * @param errors   fatal error messages; empty for a valid result
 * @param warnings non-fatal warning messages; empty when there are none
 */
public record PluginValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings
) {
    /**
     * Factory for a fully passing result (no errors, no warnings).
     *
     * @return a new {@link PluginValidationResult} with {@code valid = true}
     *         and empty error/warning lists
     */
    public static PluginValidationResult success() {
        return new PluginValidationResult(true, List.of(), List.of());
    }

    /**
     * Factory for a passing result that carries non-fatal warnings.
     *
     * @param warnings the non-fatal warning messages; copied into an
     *                 unmodifiable list
     * @return a new {@link PluginValidationResult} with {@code valid = true},
     *         no errors, and the supplied warnings
     */
    public static PluginValidationResult successWithWarnings(List<String> warnings) {
        return new PluginValidationResult(true, List.of(), List.copyOf(warnings));
    }

    /**
     * Factory for a failing result with a single fatal error.
     *
     * @param error the fatal error message
     * @return a new {@link PluginValidationResult} with {@code valid = false}
     *         and a single-element error list
     */
    public static PluginValidationResult failure(String error) {
        return new PluginValidationResult(false, List.of(error), List.of());
    }

    /**
     * Factory for a failing result with multiple fatal errors.
     *
     * @param errors the fatal error messages; copied into an unmodifiable list
     * @return a new {@link PluginValidationResult} with {@code valid = false}
     *         and the supplied errors
     */
    public static PluginValidationResult failure(List<String> errors) {
        return new PluginValidationResult(false, List.copyOf(errors), List.of());
    }

    /**
     * Composes a result from accumulated error and warning lists.
     *
     * <p>When {@code errors} is empty the result is valid (with any supplied
     * warnings); otherwise it is invalid and warnings are discarded, since a
     * fatal error already prevents registration.</p>
     *
     * @param errors   the fatal error messages
     * @param warnings the non-fatal warning messages
     * @return a result whose validity depends on whether {@code errors} is
     *         empty
     */
    public static PluginValidationResult of(List<String> errors, List<String> warnings) {
        if (errors.isEmpty()) {
            return warnings.isEmpty() ? success() : successWithWarnings(warnings);
        }
        return failure(errors);
    }

    /**
     * @return {@code true} when this result carries at least one warning
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * @return {@code true} when this result carries at least one fatal error
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
