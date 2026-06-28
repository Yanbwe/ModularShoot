package org.yanbwe.modularshoot.datapack;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable summary of one datapack registry's load outcome.
 *
 * <p>Records how many entries were attempted, how many succeeded, how many
 * failed, and how many carried warnings, for a single registry (e.g. the
 * {@code guns} table). After the datapack load phase completes, the caller
 * builds one summary per registry and emits them via
 * {@link #formatAllSummaries(List)} so operators see a single consolidated
 * line per registry (设计文档 §数据包 JSON 加载失败的错误处理:
 * "共加载 42 个枪械定义，3 个失败").</p>
 *
 * <h2>Architecture note &mdash; {@code failed} in the post-reload context</h2>
 * <p>Under NeoForge's {@code DataPackRegistryEvent} mechanism, JSON parsing
 * is performed by the vanilla {@code RegistryDataLoader} <em>before</em> the
 * post-reload listener fires. The vanilla pipeline isolates each entry with
 * its own try-catch (设计文档 §数据包 JSON 加载失败的错误处理: "对每条 JSON
 * 独立 try-catch"), so a single parse failure does not abort the remaining
 * entries. However, when <em>any</em> entry fails to parse,
 * {@code RegistryDataLoader.load()} throws an {@code IllegalStateException}
 * ("Failed to load registries due to above errors") and the entire registry
 * load is aborted &mdash; the post-reload listener never runs for that
 * registry.</p>
 *
 * <p>Consequently, when {@link DatapackReloadListener} does run, every entry
 * visible in the registry has already been parsed and registered
 * successfully. The {@code failed} count is therefore always {@code 0} in
 * this post-reload context. Parse failures are logged by the vanilla
 * pipeline (not by this framework) and prevent the post-reload summary from
 * being reached. The design-document phrase "3 个失败" describes the
 * intended operator-facing summary format; under the NeoForge architecture
 * the actual failure count is surfaced by the vanilla pipeline's own error
 * log, not by this summary.</p>
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
 * @param failed         the number of entries that failed to parse and were
 *                       skipped (not written to the registry); always
 *                       {@code 0} in the post-reload context because the
 *                       vanilla pipeline aborts on any parse failure
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
     * @param failed         the number of skipped (parse-failed) entries
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
     * <p>The format follows 设计文档 line 2383:
     * {@code "共加载 42 个枪械定义，3 个失败"}. When there are warnings, a
     * trailing {@code "，N 个警告"} segment is appended so operators can see
     * degraded-but-registered entries at a glance.</p>
     *
     * @return the formatted summary line
     */
    public String formatSummary() {
        final StringBuilder builder = new StringBuilder();
        builder.append("共加载 ").append(totalAttempted).append(" 个").append(registryName);
        builder.append("，").append(failed).append(" 个失败");
        if (warnings > 0) {
            builder.append("，").append(warnings).append(" 个警告");
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
            return "无数据包加载汇总。";
        }
        return summaries.stream()
                .map(DatapackLoadSummary::formatSummary)
                .collect(Collectors.joining("\n"));
    }
}
