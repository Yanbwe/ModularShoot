package org.yanbwe.modularshoot.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.event.PostPluginInstallEvent;
import org.yanbwe.modularshoot.plugin.event.PrePluginInstallEvent;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Installation pipeline that writes a plugin into a gun's data component
 * (设计文档 §系统四 安装/拆卸 API, lines 466-510).
 *
 * <p>The pipeline is split into a <em>validation + selection</em> phase
 * ({@link #validateAndSelect}) and a <em>write</em> phase
 * ({@link #executeInstall}) so the read-only checks stay separable from the
 * mutating writes, following the project code-quality standard of isolating
 * validation from side effects.</p>
 *
 * <p>Validation gates, in order:</p>
 * <ol>
 *   <li>tag-intersection matching with a free slot
 *       ({@link PluginMatchingService#getMatchingTypes});</li>
 *   <li>automatic category selection
 *       ({@link PluginMatchingService#selectPluginType});</li>
 *   <li>exclusive-group conflict
 *       ({@link PluginValidationService#checkExclusiveGroup});</li>
 *   <li>custom validators
 *       ({@link PluginValidationService#runCustomValidators});</li>
 *   <li>cancelable {@link PrePluginInstallEvent} on the game bus.</li>
 * </ol>
 *
 * <p>When every gate passes, the write phase appends a new
 * {@link PluginInstance} to the gun's {@link GunData}, bumps the
 * {@code modifierVersion}, consumes one plugin item, refreshes
 * {@code ATTRIBUTE_MODIFIERS}, and fires {@link PostPluginInstallEvent}.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class PluginInstallService {

    private PluginInstallService() {
    }

    /**
     * Carries the outcome of the validation + selection phase: either a failing
     * {@link ValidationResult} (with {@code selectedTypeId = null}) or a passing
     * result paired with the resolved category id to install into.
     *
     * @param result        the validation result; {@code valid()} is {@code true}
     *                      only when every gate passed
     * @param selectedTypeId the resolved category id to install into; {@code null}
     *                      when {@code result} is a failure
     */
    private record SelectionOutcome(ValidationResult result, ResourceLocation selectedTypeId) {
    }

    /**
     * Installs a plugin from a plugin item stack into a gun item stack.
     *
     * <p>Reads the {@code pluginId} from the plugin stack's {@link PluginData}
     * component, runs every validation gate, and &mdash; on success &mdash;
     * performs the write. The gun stack is mutated in place; the plugin stack
     * is shrunk by one. Both mutations happen only after all validations pass
     * and the {@link PrePluginInstallEvent} is not canceled.</p>
     *
     * @param gun            the gun item stack to install into (mutated on success)
     * @param pluginStack    the plugin item stack to install from (shrunk by one
     *                       on success); must carry {@link PluginData}
     * @param player         the player performing the installation; supplies the
     *                       random source for category auto-selection
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return {@link ValidationResult#success()} on a completed installation;
     *         otherwise a failing {@link ValidationResult} whose error message
     *         explains the rejection
     */
    public static ValidationResult installPlugin(
            ItemStack gun, ItemStack pluginStack, Player player, RegistryAccess registryAccess) {
        // a. Read the plugin id from the plugin stack's component.
        PluginData pluginData = pluginStack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        if (pluginData == null) {
            return ValidationResult.error("Item is not a plugin");
        }
        ResourceLocation pluginId = pluginData.pluginId();

        // b-h. Validate every gate and select the target category.
        SelectionOutcome outcome = validateAndSelect(gun, pluginId, player, registryAccess);
        if (!outcome.result().valid()) {
            return outcome.result();
        }

        // i-o. Execute the write, consume the plugin, refresh, and post the post-event.
        return executeInstall(gun, pluginStack, pluginId, outcome.selectedTypeId(), player, registryAccess);
    }

    /**
     * Runs every validation gate and selects the target category.
     *
     * <p>Gates (设计文档 lines 466-510): tag matching + free slot, auto-selection,
     * exclusive-group conflict, custom validators, and the cancelable
     * {@link PrePluginInstallEvent}. The first failing gate short-circuits the
     * rest and returns a failing {@link SelectionOutcome}.</p>
     *
     * @param gun            the target gun item stack (read-only here)
     * @param pluginId       the candidate plugin definition id
     * @param player         the player performing the installation
     * @param registryAccess the runtime registry view
     * @return a passing {@link SelectionOutcome} with the resolved category id,
     *         or a failing one whose {@code result} carries the error message
     */
    private static SelectionOutcome validateAndSelect(
            ItemStack gun, ResourceLocation pluginId, Player player, RegistryAccess registryAccess) {
        // b. Find matching categories that still have a free slot.
        List<PluginTypeDefinition> candidates =
                PluginMatchingService.getMatchingTypes(gun, pluginId, registryAccess);
        // c. No match → fail.
        if (candidates.isEmpty()) {
            return new SelectionOutcome(ValidationResult.error("No matching slot available"), null);
        }
        // d. Auto-select one category from the candidates.
        Optional<PluginTypeDefinition> selected =
                PluginMatchingService.selectPluginType(candidates, randomFrom(player));
        if (selected.isEmpty()) {
            return new SelectionOutcome(ValidationResult.error("No matching slot available"), null);
        }
        // The definition record does not carry its own registry id, so resolve it
        // back from the gun's slot configuration by value equality.
        Optional<ResourceLocation> typeId = resolveTypeId(gun, selected.get(), registryAccess);
        if (typeId.isEmpty()) {
            return new SelectionOutcome(ValidationResult.error("No matching slot available"), null);
        }
        // e. Look up the plugin definition for the exclusive-group check.
        Optional<PluginDefinition> pluginDef = PluginRegistry.getPlugin(registryAccess, pluginId);
        if (pluginDef.isEmpty()) {
            return new SelectionOutcome(ValidationResult.error("Plugin definition not found"), null);
        }
        // f. Exclusive-group conflict check.
        ValidationResult exclusiveResult =
                PluginValidationService.checkExclusiveGroup(gun, pluginDef.get(), registryAccess);
        if (!exclusiveResult.valid()) {
            return new SelectionOutcome(exclusiveResult, null);
        }
        // g. Custom validators registered by third-party mods.
        Optional<ValidationResult> customResult =
                PluginValidationService.runCustomValidators(gun, pluginId);
        if (customResult.isPresent()) {
            return new SelectionOutcome(customResult.get(), null);
        }
        // h. Pre-install event (cancelable); a canceled event aborts the install.
        PrePluginInstallEvent preEvent = new PrePluginInstallEvent(player, gun, pluginId);
        NeoForge.EVENT_BUS.post(preEvent);
        if (preEvent.isCanceled()) {
            return new SelectionOutcome(ValidationResult.error("Installation blocked by a listener"), null);
        }
        return new SelectionOutcome(ValidationResult.success(), typeId.get());
    }

    /**
     * Resolves the registry id of a selected category definition by matching it
     * against the gun's slot configuration.
     *
     * <p>{@link PluginTypeDefinition} does not carry its own registry id (the id
     * is supplied by the registry key, not the record). This helper iterates the
     * gun definition's slot key set, looks up each category, and returns the
     * first id whose definition equals the selected one. Because
     * {@link PluginMatchingService#getMatchingTypes} already filtered to the
     * gun's matching categories, the selected definition is guaranteed to be
     * among them.</p>
     *
     * @param gun            the target gun item stack
     * @param selected       the category definition selected by auto-selection
     * @param registryAccess the runtime registry view
     * @return the matching category id, or empty when the gun data or gun
     *         definition is missing
     */
    private static Optional<ResourceLocation> resolveTypeId(
            ItemStack gun, PluginTypeDefinition selected, RegistryAccess registryAccess) {
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Optional.empty();
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(registryAccess, gunData.gunId());
        if (gunDef.isEmpty()) {
            return Optional.empty();
        }
        for (ResourceLocation typeId : gunDef.get().slots().keySet()) {
            Optional<PluginTypeDefinition> typeDef =
                    PluginTypeRegistry.getPluginType(registryAccess, typeId);
            if (typeDef.isPresent() && typeDef.get().equals(selected)) {
                return Optional.of(typeId);
            }
        }
        return Optional.empty();
    }

    /**
     * Performs the actual write: appends the plugin instance, bumps the
     * modifier version, consumes the plugin item, refreshes attribute
     * modifiers, and fires the {@link PostPluginInstallEvent}.
     *
     * <p>This is the only phase that mutates state. It runs only after every
     * validation gate has passed, so the writes are guaranteed safe. The gun
     * stack's {@code gun_data} component is replaced with a new immutable
     * {@link GunData} (append + version bump); the plugin stack is shrunk by
     * one; {@code ATTRIBUTE_MODIFIERS} is refreshed from the new plugin list;
     * and the post-install event is fired so listeners observe the final
     * state (设计文档 lines 502-510).</p>
     *
     * @param gun            the gun item stack to update (mutated)
     * @param pluginStack    the plugin item stack to consume (shrunk by one)
     * @param pluginId       the installed plugin definition id
     * @param selectedTypeId the resolved category id to install into
     * @param player         the player performing the installation
     * @param registryAccess the runtime registry view
     * @return {@link ValidationResult#success()} (this phase cannot fail)
     */
    private static ValidationResult executeInstall(
            ItemStack gun, ItemStack pluginStack, ResourceLocation pluginId,
            ResourceLocation selectedTypeId, Player player, RegistryAccess registryAccess) {
        // i. Generate a unique instance id for this installation.
        UUID instanceUuid = UUID.randomUUID();
        // j. Create the immutable plugin instance (locked defaults to false).
        PluginInstance instance = PluginInstance.create(pluginId, instanceUuid, selectedTypeId);
        // k. Build the new gun data: append the instance, bump the version,
        //    carry over the gun id, instance uuid, and state unchanged.
        GunData oldData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        List<PluginInstance> newPlugins = new ArrayList<>(oldData.installedPlugins());
        newPlugins.add(instance);
        GunData newData = new GunData(
                oldData.gunId(),
                oldData.gunInstanceUuid(),
                List.copyOf(newPlugins),
                oldData.modifierVersion() + 1,
                oldData.state());
        // l. Write the new gun data back onto the stack.
        gun.set(ModularShootDataComponents.GUN_DATA.get(), newData);
        // m. Consume one plugin item from the carried stack.
        pluginStack.shrink(1);
        // n. Refresh attribute modifiers from the updated plugin list.
        AttributeModifierService.refreshModifiers(gun, registryAccess);
        // o. Fire the post-install event so listeners observe the final state.
        NeoForge.EVENT_BUS.post(new PostPluginInstallEvent(player, gun, pluginId, instanceUuid));
        return ValidationResult.success();
    }

    /**
     * Creates a {@link Random} seeded from the player's server-controlled
     * {@link net.minecraft.util.RandomSource RandomSource}.
     *
     * <p>{@link PluginMatchingService#selectPluginType} accepts a
     * {@code java.util.Random}, while {@link Player#getRandom()} returns a
     * {@code RandomSource}. Seeding a new {@code Random} from the player's
     * random source preserves server control over the outcome (the seed is
     * drawn from the authoritative server random) while bridging the type gap.
     * The seed is consumed once per install attempt.</p>
     *
     * @param player the player whose random source seeds the new {@code Random}
     * @return a new {@code Random} seeded from the player's random source
     */
    private static Random randomFrom(Player player) {
        return new Random(player.getRandom().nextLong());
    }
}
