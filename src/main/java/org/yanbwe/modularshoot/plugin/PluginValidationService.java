package org.yanbwe.modularshoot.plugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;

/**
 * Validation service for plugin installation.
 *
 * <p>Provides two concerns used by the install pipeline (设计文档 §安装交互
 * and §自定义安装校验):</p>
 *
 * <ul>
 *   <li>{@link #checkExclusiveGroup} &mdash; a pure framework check that
 *       rejects a candidate plugin whose {@code exclusiveGroup} collides with
 *       an already-installed plugin on the same gun. Plugins without an
 *       exclusive group are unrestricted.</li>
 *   <li>{@link #registerPluginValidator} / {@link #runCustomValidators}
 *       &mdash; an extension point where third-party mods append bespoke
 *       validation rules. Registered validators run after the framework
 *       default checks pass; the first failing result aborts the
 *       installation.</li>
 * </ul>
 *
 * <p>The validator list is a {@link CopyOnWriteArrayList} so that registration
 * from mod init threads and iteration from the server install path do not
 * require external synchronisation.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class PluginValidationService {

    /**
     * Thread-safe list of custom validators registered by third-party mods.
     *
     * <p>{@link CopyOnWriteArrayList} is chosen because registration happens
     * rarely (during mod init) while iteration happens on every install
     * attempt; the copy-on-write cost is therefore paid on the rare path and
     * iteration is lock-free.</p>
     */
    private static final List<PluginValidator> VALIDATORS = new CopyOnWriteArrayList<>();

    private PluginValidationService() {
    }

    /**
     * Registers a custom plugin installation validator.
     *
     * <p>Registered validators are executed by {@link #runCustomValidators}
     * after the framework default validation passes. The first validator that
     * returns a failing {@link ValidationResult} aborts the installation.</p>
     *
     * <p>Safe to call during mod common-setup; the underlying list is
     * thread-safe.</p>
     *
     * @param validator the validator to register; must not be {@code null}
     */
    public static void registerPluginValidator(PluginValidator validator) {
        VALIDATORS.add(validator);
    }

    /**
     * Checks whether a candidate plugin's exclusive group conflicts with any
     * already-installed plugin on the given gun.
     *
     * <p>This is a pure read-only check: it inspects the gun's
     * {@code gun_data} component and queries each installed plugin's
     * {@link PluginDefinition} via {@link PluginRegistry} to compare
     * {@code exclusiveGroup} values. Two plugins whose exclusive groups are
     * both present and equal cannot coexist on the same gun (设计文档
     * §插件互斥组). A plugin with an absent exclusive group is unrestricted
     * and never triggers a conflict.</p>
     *
     * <p>When an installed plugin's definition can no longer be resolved in
     * the registry (e.g. its owning mod was removed), it is skipped: a
     * missing definition cannot prove a conflict, so the check is lenient.
     * This matches the framework's "definition-lost degradation" policy
     * (设计文档 §安装交互).</p>
     *
     * @param gun            the target gun item stack; when it carries no
     *                       {@code gun_data} the check trivially passes
     * @param newPluginDef   the candidate plugin definition whose exclusive
     *                       group is being checked
     * @param registryAccess the runtime registry view used to resolve
     *                       installed plugins' definitions
     * @return {@link ValidationResult#success()} when no conflict exists or
     *         the candidate has no exclusive group; otherwise a failing
     *         {@link ValidationResult} whose message names the already-installed
     *         plugin's readable name (falling back to the group id), localized
     *         via {@code modularshoot.install.error.exclusive_group}
     */
    public static ValidationResult checkExclusiveGroup(
            ItemStack gun,
            PluginDefinition newPluginDef,
            RegistryAccess registryAccess) {
        Optional<String> newGroup = newPluginDef.exclusiveGroup();
        if (newGroup.isEmpty()) {
            return ValidationResult.success();
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return ValidationResult.success();
        }
        String group = newGroup.get();
        for (PluginInstance installed : gunData.installedPlugins()) {
            Optional<PluginDefinition> installedDef =
                    PluginRegistry.getPlugin(registryAccess, installed.pluginId());
            if (installedDef.isEmpty()) {
                continue;
            }
            Optional<String> installedGroup = installedDef.get().exclusiveGroup();
            if (installedGroup.isPresent() && installedGroup.get().equals(group)) {
                // P3 fix: 冲突报错带上已安装插件的可读名——玩家看到
                // "与已安装插件 §b精密枪管 互斥" 而不是原始英文 + 技术组 id。
                // Component.literal 保留 § 格式码（name 字段支持颜色代码）；
                // 名字缺失时回退显示互斥组 id。
                String displayName = installedDef.get().name()
                        .filter(s -> !s.isEmpty())
                        .orElse(group);
                return ValidationResult.error(Component.translatable(
                        "modularshoot.install.error.exclusive_group",
                        Component.literal(displayName)));
            }
        }
        return ValidationResult.success();
    }

    /**
     * Runs every registered custom validator against the candidate plugin.
     *
     * <p>Validators are executed in registration order. Iteration
     * short-circuits on the first failing result: per the design contract
     * "any validator returning failure aborts the installation" (设计文档
     * §自定义安装校验), remaining validators are not run once a failure is
     * observed.</p>
     *
     * <p>Exception isolation: a validator that throws an exception is logged
     * and skipped; the remaining validators still run and the failure
     * short-circuit is unaffected (抛异常的第三方 validator 被记录并跳过，
     * 继续执行其余 validator，不影响首败短路).</p>
     *
     * @param gun            the target gun item stack passed to each validator
     * @param pluginId       the candidate plugin definition id passed to each
     *                       validator
     * @param player         the player performing the installation, passed to
     *                       each validator for player-state checks
     * @param registryAccess the runtime registry view passed to each validator
     *                       for data-driven checks
     * @return {@link Optional#empty()} when all registered validators pass
     *         (or when none are registered); otherwise an {@link Optional}
     *         containing the first failing {@link ValidationResult}, whose
     *         error message should be shown to the player
     */
    public static Optional<ValidationResult> runCustomValidators(
            ItemStack gun, ResourceLocation pluginId, Player player, RegistryAccess registryAccess) {
        for (PluginValidator validator : VALIDATORS) {
            ValidationResult result;
            try {
                result = validator.validate(player, gun, pluginId, registryAccess);
            } catch (Exception e) {
                ModularShoot.LOGGER.error(
                        "PluginValidator threw an exception; skipping this validator", e);
                continue;
            }
            if (!result.valid()) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }
}
