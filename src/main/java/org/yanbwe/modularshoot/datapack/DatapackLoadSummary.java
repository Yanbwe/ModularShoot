package org.yanbwe.modularshoot.datapack;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable summary of one datapack registry's load outcome.
 *
 * <p>Records how many entries were attempted, how many registered cleanly,
 * and how many carried warnings, for a single registry (e.g. the
 * {@code guns} table). After the datapack load phase completes, the caller
 * builds one summary per registry and emits them via
 * {@link #formatAllSummaries(List)} so operators see a single consolidated
 * line per registry (设计文档 §数据包 JSON 加载失败的错误处理:
 * "共加载 42 个枪械定义，3 个失败").</p>
 *
 * <h2>Architecture note &mdash; {@code failed} is always {@code 0}</h2>
 * <p>Under NeoForge's {@code DataPackRegistryEvent} mechanism, JSON parsing
 * is performed by the vanilla {@code RegistryDataLoader} <em>before</em> the
 * post-reload listener fires. The vanilla pipeline parses every entry and
 * collects the errors, but when <em>any</em> entry fails to parse,
 * {@code RegistryDataLoader.load()} throws an {@code IllegalStateException}
 * ("Failed to load registries due to above errors") and the entire registry
 * load is aborted &mdash; the post-reload listener never runs for that
 * registry, so this summary is not produced at all. There is no "partial
 * success" state: a single bad JSON takes down the whole table, and with it
 * the datapack / world load.</p>
 *
 * <p>Consequently, when {@link DatapackReloadListener} does run, every entry
 * visible in the registry has already been parsed and registered
 * successfully, and {@code failed} is necessarily {@code 0} &mdash; an
 * architectural constant, not a measured value. When a load fails, locate
 * the first error in the vanilla pipeline's stack trace and error log
 * (emitted by {@code RegistryDataLoader.logErrors}); this summary never
 * reports such failures because it is not reached.</p>
 *
 * <p>This type is null-hostile and immutable: the compact constructor
 * validates its arguments and the record components are primitives plus a
 * {@code String}, all inherently immutable. It is a pure data carrier with
 * no side effects &mdash; formatting is delegated to {@link #formatSummary()}
 * which returns a string without logging, keeping validation/logging and
 * data concerns separated (设计文档 §错误处理, §函数&lt;50行).</p>
 *
 * @param registryName   the human-readable registry name used in the summary
 *                       line (e.g. {@code "枪械定义"}, {@code "插件定义"})
 * @param totalAttempted the total number of entries the loader attempted to
 *                       parse
 * @param succeeded      the number of entries that parsed and registered
 *                       successfully
 * @param failed         the number of entries that failed to parse; always
 *                       {@code 0} because a single parse failure aborts the
 *                       whole registry load in the vanilla pipeline, in
 *                       which case this summary is not produced (see the
 *                       class javadoc)
 * @param warnings       the number of entries that registered with a warning
 *                       (reference invalidation or missing resource)
 */
public record DatapackLoadSummary(
        String registryName,
        int totalAttempted,
        int succeeded,
        int failed,
        int warnings
) {
    /**
     * Compact constructor validating the record components.
     *
     * @throws IllegalArgumentException if {@code registryName} is null or
     *         blank, if any count is negative, or if
     *         {@code succeeded + failed} exceeds {@code totalAttempted}
     */
    public DatapackLoadSummary {
        if (registryName == null || registryName.isBlank()) {
            throw new IllegalArgumentException("registryName must not be null or blank");
        }
        if (totalAttempted < 0 || succeeded < 0 || failed < 0 || warnings < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (succeeded + failed > totalAttempted) {
            throw new IllegalArgumentException(
                    "succeeded + failed (" + (succeeded + failed)
                            + ") must not exceed totalAttempted (" + totalAttempted + ")");
        }
    }

    /**
     * Factory for a {@link DatapackLoadSummary}.
     *
     * @param registryName   the human-readable registry name
     * @param totalAttempted the total number of attempted entries
     * @param succeeded      the number of successfully registered entries
     * @param failed         the number of skipped (parse-failed) entries;
     *                       always {@code 0} in the post-reload context (see
     *                       the class javadoc)
     * @param warnings       the number of entries registered with a warning
     * @return a new immutable {@link DatapackLoadSummary}
     */
    public static DatapackLoadSummary of(
            String registryName, int totalAttempted, int succeeded, int failed, int warnings) {
        return new DatapackLoadSummary(registryName, totalAttempted, succeeded, failed, warnings);
    }

    /**
     * Formats this summary as a single human-readable log line.
     *
     * <p>The line reports the total and, when present, the warning count:
     * {@code "Loaded 42 gun definitions, 3 warnings"}. The failure segment is
     * intentionally omitted: {@code failed} is always {@code 0} in the
     * post-reload context (a single parse failure aborts the whole registry
     * load and this summary is never produced &mdash; see the class
     * javadoc), so a constant "0 failures" would only mislead operators into
     * believing a failed load still produced a summary.</p>
     *
     * @return the formatted summary line
     */
    public String formatSummary() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Loaded ").append(totalAttempted).append(" ").append(registryName);
        if (warnings > 0) {
            builder.append(", ").append(warnings).append(" warnings");
        }
        return builder.toString();
    }

    /**
     * Formats a batch of summaries into a single multi-line string.
     *
     * <p>Each summary occupies one line, separated by a newline. When the
     * list is empty, a placeholder line is returned so the log output is
     * never blank. The input list is not modified (pure function).</p>
     *
     * @param summaries the per-registry summaries to format
     * @return the concatenated summary lines, or a placeholder when the
     *         list is empty
     * @throws NullPointerException if {@code summaries} is {@code null}
     */
    public static String formatAllSummaries(List<DatapackLoadSummary> summaries) {
        if (summaries == null) {
            throw new IllegalArgumentException("summaries must not be null");
        }
        if (summaries.isEmpty()) {
            return "No datapack load summary.";
        }
        return summaries.stream()
                .map(DatapackLoadSummary::formatSummary)
                .collect(Collectors.joining("\n"));
    }
}
