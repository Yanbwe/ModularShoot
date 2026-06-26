package org.yanbwe.modularshoot.datapack;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Unified error-handling sink for datapack loading diagnostics.
 *
 * <p>This is the single logging entry point for the three categories of
 * datapack problems defined in 设计文档 §数据包JSON加载失败错误处理
 * (lines 2362&ndash;2383):</p>
 *
 * <ul>
 *   <li><b>Parse failure</b> &mdash; a single JSON entry failed to parse
 *       (syntax error, missing required field, wrong field type). The entry
 *       is skipped (not written to the registry) and an {@code ERROR} is
 *       logged with the file path, cause, and line number when locatable.
 *       Other entries are unaffected because each JSON is isolated by its
 *       own try-catch at the call site (设计文档 line 2383).</li>
 *   <li><b>Reference invalidation</b> &mdash; the entry registered fine, but
 *       a reference it carries cannot be resolved at runtime (e.g. a
 *       plugin's {@code tags} match no registered category). A {@code WARN}
 *       is logged; the entry stays registered and degrades at runtime
 *       (设计文档 line 2375).</li>
 *   <li><b>Missing resource</b> &mdash; the entry registered fine, but a
 *       referenced asset path (texture, model) does not exist. The entry
 *       stays registered (the registry only stores the path string, it does
 *       not preload the asset); the runtime falls back to the framework's
 *       default asset and a {@code WARN} is logged (设计文档 line 2380).</li>
 * </ul>
 *
 * <p>All methods route through {@link ModularShoot#LOGGER} so operators have
 * one consistent channel for datapack diagnostics. This class is a pure
 * logging facade: it never inspects or mutates registry contents &mdash; the
 * decision to skip an entry is made by the caller <em>before</em> invoking
 * {@link #logParseError(String, Throwable)} (设计文档 §错误处理).</p>
 *
 * <p>The class is not instantiable. Every method is well under 50 lines
 * (设计文档 §函数&lt;50行).</p>
 *
 * @see ModularShoot#LOGGER
 * @see DatapackLoadSummary
 */
public final class DatapackErrorHandler {
    private DatapackErrorHandler() {
    }

    /**
     * Logs an {@code ERROR} for a single JSON entry that failed to parse.
     *
     * <p>The entry should already have been skipped by the caller (not
     * written to the registry). The throwable's stack trace &mdash; which
     * carries source line numbers when locatable &mdash; is attached to the
     * log record so operators can pinpoint the failing line
     * (设计文档 line 2368). SLF4J treats a trailing {@link Throwable}
     * argument as the throwable to attach, so the {@code {}} placeholders
     * are filled by {@code filePath} and the exception message while the
     * full stack trace is printed alongside.</p>
     *
     * @param filePath the datapack file path that failed to parse (e.g.
     *                 {@code data/modularshoot/guns/foo.json})
     * @param error    the parse exception; its message and stack trace are
     *                 logged
     * @throws NullPointerException if {@code filePath} or {@code error} is
     *         {@code null}
     */
    public static void logParseError(String filePath, Throwable error) {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(error, "error");
        ModularShoot.LOGGER.error("Failed to parse datapack JSON '{}': {}",
                filePath, error.getMessage(), error);
    }

    /**
     * Logs an {@code ERROR} for a single JSON entry that failed to parse,
     * when no throwable is available.
     *
     * <p>Use this overload when the failure was detected by a manual check
     * rather than an exception. When a throwable <em>is</em> available,
     * prefer {@link #logParseError(String, Throwable)} so the stack trace
     * (with line numbers) is recorded (设计文档 line 2368).</p>
     *
     * @param filePath the datapack file path that failed to parse
     * @param reason   the human-readable failure reason
     * @throws NullPointerException if {@code filePath} or {@code reason} is
     *         {@code null}
     */
    public static void logParseError(String filePath, String reason) {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(reason, "reason");
        ModularShoot.LOGGER.error("Failed to parse datapack JSON '{}': {}",
                filePath, reason);
    }

    /**
     * Logs a {@code WARN} for an entry that registered successfully but
     * whose references cannot be resolved at runtime.
     *
     * <p>Example: a plugin's {@code tags} list references tags that no
     * registered category carries, so the plugin can never be installed
     * onto any gun. The entry stays registered; only a warning is emitted
     * so operators can identify the dangling reference
     * (设计文档 line 2375).</p>
     *
     * @param id      the registry id of the entry with the broken reference
     * @param message a human-readable description of which reference could
     *                not be resolved
     * @throws NullPointerException if {@code id} or {@code message} is
     *         {@code null}
     */
    public static void logReferenceWarning(ResourceLocation id, String message) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
        ModularShoot.LOGGER.warn("Reference warning for '{}': {}", id, message);
    }

    /**
     * Logs a {@code WARN} for an entry that registered successfully but
     * whose referenced asset path does not exist.
     *
     * <p>The entry stays registered (the registry only stores the path
     * string, it does not preload the asset). At runtime the framework
     * falls back to its default asset (e.g. the grey {@code "?"} icon for
     * textures) and this warning is emitted (设计文档 line 2380).</p>
     *
     * @param id           the registry id of the entry whose asset is missing
     * @param resourcePath the asset path that could not be found
     * @throws NullPointerException if {@code id} or {@code resourcePath} is
     *         {@code null}
     */
    public static void logMissingResource(ResourceLocation id, String resourcePath) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resourcePath, "resourcePath");
        ModularShoot.LOGGER.warn("Missing resource for '{}': path '{}' not found; "
                        + "entry registered, runtime will use fallback asset.",
                id, resourcePath);
    }
}
