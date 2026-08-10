package org.yanbwe.modularshoot.plugin;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable result of a single plugin uninstall operation.
 *
 * <p>Returned by every method in {@link PluginUninstallService}. The
 * {@code success} flag tells the caller whether the plugin was actually
 * removed; a {@code false} value means the plugin was skipped for one of
 * the reasons enumerated in {@link Reason}. Callers that need to react to
 * a specific failure (e.g. retry with {@code force} when the plugin is
 * locked) should inspect {@link #reason()} instead of guessing from the
 * {@code success} flag alone.</p>
 *
 * <p>When {@code success} is {@code false} the {@code pluginId} may be
 * {@code null} (e.g. when the plugin could not be found at all). The
 * {@code instanceUuid} is {@code null} only for the no-candidate case of
 * {@link PluginUninstallService#uninstallRandomPlugin}; in all other cases
 * it echoes the uuid that was submitted.</p>
 *
 * @param success      whether the plugin was successfully uninstalled
 * @param pluginId     the registry id of the plugin that was (or was attempted
 *                     to be) uninstalled, or {@code null} when the plugin could
 *                     not be located
 * @param instanceUuid the instance uuid of the plugin that was (or was
 *                     attempted to be) uninstalled, or {@code null} for the
 *                     no-candidate random-uninstall case
 * @param reason       the classification of the outcome; see {@link Reason}
 */
public record UninstallResult(
        boolean success,
        @Nullable ResourceLocation pluginId,
        @Nullable UUID instanceUuid,
        Reason reason
) {

    /**
     * Classifies the outcome of a single uninstall attempt.
     */
    public enum Reason {
        /** 确实移除。 */
        SUCCESS,
        /** 非枪或无 gun_data 组件。 */
        NOT_GUN_OR_NO_DATA,
        /** 未找到该实例 UUID。 */
        UUID_NOT_FOUND,
        /** 插件锁定且未请求 force。 */
        LOCKED,
        /** PrePluginUninstallEvent 被监听器取消。 */
        CANCELED,
        /** 卸载该插件将导致某槽位类型超编（非 force 时拒绝；force 可绕过）。 */
        WOULD_OVERFLOW,
        /** 随机卸载无候选（空列表或全部锁定且未 force）。 */
        NO_CANDIDATE
    }
}
