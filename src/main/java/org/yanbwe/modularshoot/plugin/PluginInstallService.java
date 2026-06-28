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
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.event.PostPluginInstallEvent;
import org.yanbwe.modularshoot.plugin.event.PrePluginInstallEvent;

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
     * Immutable result of an installation attempt.
     *
     * <p>On success {@link #success()} is {@code true}, {@link #installedGun()}
     * carries the modified gun (a copy of the original with the plugin installed),
     * and {@link #consumedPlugin()} carries the consumed plugin (a copy, shrunk
     * by one). The original input stacks are never mutated.</p>
     *
     * <p>On failure {@link #success()} is {@code false} and {@link #errorMessage()}
     * explains the rejection. The output stacks are {@code null} on failure.</p>
     *
     * @param success        {@code true} when installation completed
     * @param installedGun   the modified gun copy, or {@code null} on failure
     * @param consumedPlugin the consumed plugin copy, or {@code null} on failure
     * @param errorMessage   the rejection reason, or empty on success
     */
    public record InstallResult(
            boolean success,
            @Nullable ItemStack installedGun,
            @Nullable ItemStack consumedPlugin,
            Optional<String> errorMessage
    ) {
        public static InstallResult success(ItemStack gun, ItemStack plugin) {
            return new InstallResult(true, gun, plugin, Optional.empty());
        }

        public static InstallResult failure(String error) {
            return new InstallResult(false, null, null, Optional.of(error));
        }
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
     * works on <strong>copies</strong> of the input stacks so the originals
     * are never mutated. The caller receives the modified copies via
     * {@link InstallResult} and is responsible for writing them back to the
     * appropriate container slots via {@code Slot.set()} / {@code SlotAccess.set()}
     * (following the Apotheosis pattern).</p>
     *
     * @param gun            the gun item stack to inspect (not mutated)
     * @param pluginStack    the plugin item stack to inspect (not mutated);
     *                       must carry {@link PluginData}
     * @param player         the player performing the installation; supplies the
     *                       random source for category auto-selection
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return an {@link InstallResult} carrying the modified copies on success,
     *         or a failure with an error message
     */
    public static InstallResult installPlugin(
            ItemStack gun, ItemStack pluginStack, Player player, RegistryAccess registryAccess) {
        // a. Read the plugin id from the plugin stack's component.
        PluginData pluginData = pluginStack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        if (pluginData == null) {
            return InstallResult.failure("Item is not a plugin");
        }
        ResourceLocation pluginId = pluginData.pluginId();

        // a2. Guard: the target gun must carry a gun_data component. Without it
        //     the downstream matching service returns an empty candidate list,
        //     which would surface as the misleading "No matching slot available"
        //     message. Fail fast with an explicit reason instead (S29 fix).
        if (!gun.has(ModularShootDataComponents.GUN_DATA.get())) {
            return InstallResult.failure("Target gun has no gun_data component");
        }

        // b-h. Validate every gate and select the target category.
        SelectionOutcome outcome = validateAndSelect(gun, pluginId, player, registryAccess);
        if (!outcome.result().valid()) {
            return InstallResult.failure(outcome.result().errorMessage().orElse("Install failed"));
        }

        // i-o. Work on copies so the originals are never mutated (Apotheosis pattern).
        ItemStack resultGun = gun.copy();
        ItemStack resultPlugin = pluginStack.copy();

        executeInstall(resultGun, resultPlugin, pluginId, outcome.selectedTypeId(), player, registryAccess);

        return InstallResult.success(resultGun, resultPlugin);
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
        //    Each candidate carries its registry id alongside the definition,
        //    so no reverse-lookup is needed after selection.
        List<PluginMatchingService.TypeMatch> candidates =
                PluginMatchingService.getMatchingTypes(gun, pluginId, registryAccess);
        // c. No match → fail.
        if (candidates.isEmpty()) {
            return new SelectionOutcome(ValidationResult.error("No matching slot available"), null);
        }
        // d. Auto-select one category from the candidates; the selected id
        //    is returned directly, eliminating the previous reverse-lookup.
        Optional<ResourceLocation> selectedTypeId =
                PluginMatchingService.selectPluginType(candidates, randomFrom(player));
        if (selectedTypeId.isEmpty()) {
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
        return new SelectionOutcome(ValidationResult.success(), selectedTypeId.get());
    }

    /**
     * Performs the actual write on the given copies: appends the plugin instance,
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
     * <p>The instance uuid is derived from the player's random source via
     * {@link #deriveInstanceUuid} rather than {@link UUID#randomUUID()} so
     * that the client and server sides — which each fire
     * {@link net.neoforged.neoforge.event.ItemStackedOnOtherEvent}
     * independently — produce the same uuid (W4 fix).</p>
     *
     * @param gun            the gun item stack to update (mutated)
     * @param pluginStack    the plugin item stack to consume (shrunk by one)
     * @param pluginId       the installed plugin definition id
     * @param selectedTypeId the resolved category id to install into
     * @param player         the player performing the installation
     * @param registryAccess the runtime registry view
     */
    private static void executeInstall(
            ItemStack gun, ItemStack pluginStack, ResourceLocation pluginId,
            ResourceLocation selectedTypeId, Player player, RegistryAccess registryAccess) {
        // i. Generate a unique instance id for this installation. The uuid is
        //    derived from the player's random source (not UUID.randomUUID())
        //    so that both the client and server sides — which each fire
        //    ItemStackedOnOtherEvent independently — produce the same uuid.
        //    This eliminates the transient mismatch window where the client's
        //    gun_data would carry a different instance uuid than the server's
        //    authoritative copy until the server syncs back (W4 fix).
        UUID instanceUuid = deriveInstanceUuid(player);
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

    /**
     * Derives a deterministic instance uuid from the player's random source.
     *
     * <p>{@link net.neoforged.neoforge.event.ItemStackedOnOtherEvent} fires
     * independently on both the client and server sides. Using
     * {@link UUID#randomUUID()} would produce two different uuids — one per
     * side — leaving the client's {@code gun_data} with a transiently
     * mismatched instance uuid until the server's authoritative copy syncs
     * back via container synchronization. Deriving the uuid from the player's
     * {@link net.minecraft.util.RandomSource RandomSource} ensures both sides
     * consume the same random sequence (the player random is shared/synced
     * for this code path) and therefore produce the <em>same</em> uuid,
     * eliminating the mismatch window.</p>
     *
     * <p>The player random is advanced by exactly two {@code nextLong()}
     * calls per install. Because both sides execute the identical code path
     * (including the {@link #randomFrom} seed draw in the selection phase),
     * the random sequences stay aligned.</p>
     *
     * @param player the player whose random source derives the uuid
     * @return a new uuid built from two longs drawn from the player's random
     */
    private static UUID deriveInstanceUuid(Player player) {
        return new UUID(player.getRandom().nextLong(), player.getRandom().nextLong());
    }
}
