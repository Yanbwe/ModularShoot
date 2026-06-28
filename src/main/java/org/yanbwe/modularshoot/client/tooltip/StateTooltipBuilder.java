package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.client.ClientGunDataStore;
import org.yanbwe.modularshoot.degradation.StateDegradationHandler;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.state.GunState;
import org.yanbwe.modularshoot.state.PlayerState;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDisplay;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateValueType;

/**
 * Builds the state-bar section of a gun's tooltip (设计文档 §tooltip 集成).
 *
 * <p>Collects all registered states applicable to a gun tooltip — per-gun
 * states from the gun stack and per-player states from the viewing player
 * (only when the player is holding a gun) — filters out entries whose value
 * equals the default when {@code hide_default} is set, sorts by
 * {@code display.priority} (descending) then by state id (ascending), and
 * renders each as a coloured {@link Component} line.</p>
 *
 * <p>This is the M5 minimal-viable state bar. Attribute, trait, and plugin
 * tooltip bars are handled by separate builders in later milestones.</p>
 *
 * @see TooltipBuilder
 */
public final class StateTooltipBuilder {
    private StateTooltipBuilder() {
    }

    /**
     * Builds the state-bar tooltip lines for a gun stack.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Collect every registered state definition from the
     *       {@code modularshoot:states} registry.</li>
     *   <li>Read each state's current value: {@link StateDomain#GUN} from
     *       the gun stack, {@link StateDomain#PLAYER} from the viewing
     *       player (only when holding a gun), {@link StateDomain#BULLET}
     *       skipped.</li>
     *   <li>Filter out entries where {@code hide_default} is {@code true}
     *       and the current value equals the default value.</li>
     *   <li>Sort by {@code display.priority} descending, then by state id
     *       ascending.</li>
     *   <li>Render a grey {@code "状态:"} header followed by one line per
     *       entry.</li>
     * </ol>
     * </p>
     *
     * @param gunStack       the gun item stack to read per-gun states from
     * @param viewingPlayer  the player viewing the tooltip (for per-player
     *                       states); may be {@code null} (e.g. main menu)
     * @param registryAccess the runtime registry view for state lookups
     * @return an ordered list of state-bar components starting with the
     *         grey {@code "状态:"} header; empty when no state is
     *         displayable or the registry is empty
     */
    public static List<Component> buildStateBar(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        List<StateEntry> entries = collectEntries(gunStack, viewingPlayer, registryAccess);
        List<StateEntry> visible = filterVisible(entries);
        if (visible.isEmpty()) {
            return List.of();
        }
        visible.sort(StateTooltipBuilder::compareEntries);
        List<Component> lines = new ArrayList<>(visible.size() + 1);
        lines.add(Component.literal("状态:").withStyle(ChatFormatting.GRAY));
        for (StateEntry entry : visible) {
            lines.add(buildLine(entry));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    /**
     * Collects all candidate state entries from the registry, reading the
     * current value of each from the appropriate source.
     *
     * @param gunStack       the gun stack to read per-gun states from
     * @param viewingPlayer  the viewing player (may be {@code null})
     * @param registryAccess the runtime registry view
     * @return a list of {@link StateEntry} with current values; empty when
     *         the registry is absent
     */
    private static List<StateEntry> collectEntries(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        GunState gunState = resolveGunState(gunStack, viewingPlayer, registryAccess);
        @Nullable PlayerState playerState = createPlayerStateIfHoldingGun(viewingPlayer, registryAccess);

        Optional<Registry<StateDefinition>> registryOpt =
                registryAccess.registry(ModularShootRegistries.STATES_KEY);
        if (registryOpt.isEmpty()) {
            return List.of();
        }
        Registry<StateDefinition> registry = registryOpt.get();

        List<StateEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceKey<StateDefinition>, StateDefinition> regEntry : registry.entrySet()) {
            ResourceLocation stateId = regEntry.getKey().location();
            StateDefinition def = regEntry.getValue();
            // Defensive degradation guard (设计文档 §状态 ID 失效降级): skip
            // states whose definition is missing from the registry. The loop
            // iterates the registry so this is normally redundant, but the
            // guard documents the degradation contract explicitly and
            // protects against future refactors that might source state ids
            // from stored NBT keys rather than the registry.
            if (StateDegradationHandler.isStateDefinitionMissing(stateId, registryAccess)) {
                continue;
            }
            collectOne(entries, stateId, def, gunState, playerState);
        }
        return entries;
    }

    /**
     * Resolves the {@link GunState} view to read per-gun state values from.
     *
     * <p>When the tooltip is being built for the local player's own
     * main-hand gun and {@link ClientGunDataStore} has received a sync
     * snapshot, the state is read from the authoritative server-pushed tag
     * via {@link GunState#of(CompoundTag, RegistryAccess)}. Otherwise the
     * local {@code GunData} component on the stack is used as a fallback
     * (设计文档 §GunSyncS2CPacket 客户端用途, lines 2054-2056).</p>
     *
     * @param gunStack       the gun stack being tooltip'd
     * @param viewingPlayer  the viewing player, or {@code null}
     * @param registryAccess the runtime registry view
     * @return a {@link GunState} view backed by either the sync store or
     *         the local stack
     */
    private static GunState resolveGunState(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        ClientGunDataStore store = ClientGunDataStore.getInstance();
        if (store.hasSyncData() && isLocalMainHand(gunStack, viewingPlayer)) {
            return GunState.of(store.getState(), registryAccess);
        }
        if (viewingPlayer != null) {
            return GunState.of(gunStack, viewingPlayer);
        }
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        if (level != null) {
            return GunState.of(gunStack, level);
        }
        // Fallback: read-only view from the sync store (no level available)
        return GunState.of(store.getState(), registryAccess);
    }

    /**
     * Checks whether the tooltip'd gun stack is the local player's own
     * main-hand item.
     *
     * <p>This guards the sync-store read path so that tooltips for guns in
     * chests, the off-hand, or other inventories never accidentally use the
     * main-hand sync snapshot.</p>
     *
     * @param gunStack      the gun stack being tooltip'd
     * @param viewingPlayer the viewing player, or {@code null}
     * @return {@code true} if {@code gunStack} is the local player's
     *         main-hand item
     */
    private static boolean isLocalMainHand(ItemStack gunStack, @Nullable Player viewingPlayer) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || viewingPlayer != localPlayer) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(gunStack, localPlayer.getMainHandItem());
    }

    /**
     * Creates a {@link PlayerState} view only when the viewing player is
     * non-null and is holding a gun in their main hand.
     *
     * <p>Per the design doc (§tooltip 集成), per-player states are shown
     * only when the player is currently holding a gun. When the player is
     * not holding a gun (e.g. viewing a gun in a chest), per-player states
     * are not displayed.</p>
     *
     * @param viewingPlayer  the viewing player, or {@code null}
     * @param registryAccess the runtime registry view
     * @return a {@link PlayerState}, or {@code null} when the player is
     *         absent or not holding a gun
     */
    @Nullable
    private static PlayerState createPlayerStateIfHoldingGun(
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        if (viewingPlayer == null) {
            return null;
        }
        if (!ModularShootAPI.isGun(viewingPlayer.getMainHandItem())) {
            return null;
        }
        return PlayerState.of(viewingPlayer);
    }

    /**
     * Reads a single state's value and adds it to the entries list if its
     * domain is applicable to the gun tooltip.
     *
     * @param entries     the accumulator list
     * @param stateId     the state definition id
     * @param def         the state definition
     * @param gunState    the gun state view for reading per-gun values
     * @param playerState the player state view, or {@code null} when
     *                    per-player states should be skipped
     */
    private static void collectOne(
            List<StateEntry> entries,
            ResourceLocation stateId,
            StateDefinition def,
            GunState gunState,
            @Nullable PlayerState playerState) {
        switch (def.domain()) {
            case GUN -> entries.add(new StateEntry(stateId, def,
                    readGunValue(def.valueType(), stateId, gunState)));
            case PLAYER -> {
                if (playerState != null) {
                    entries.add(new StateEntry(stateId, def,
                            readPlayerValue(def.valueType(), stateId, playerState)));
                }
            }
            case BULLET -> {
                // Per-bullet states are not shown in the gun tooltip.
            }
        }
    }

    /**
     * Reads a typed value from a {@link GunState} view.
     *
     * @param type    the declared value type
     * @param stateId the state id to read
     * @param gunState the gun state view
     * @return the boxed value (may be {@code null} for UUID zero value)
     */
    private static Object readGunValue(StateValueType type, ResourceLocation stateId, GunState gunState) {
        return switch (type) {
            case INT -> gunState.getInt(stateId);
            case LONG -> gunState.getLong(stateId);
            case DOUBLE -> gunState.getDouble(stateId);
            case FLOAT -> gunState.getFloat(stateId);
            case BOOLEAN -> gunState.getBoolean(stateId);
            case STRING -> gunState.getString(stateId);
            case UUID -> gunState.getUuid(stateId);
        };
    }

    /**
     * Reads a typed value from a {@link PlayerState} view.
     *
     * @param type        the declared value type
     * @param stateId     the state id to read
     * @param playerState the player state view
     * @return the boxed value (may be {@code null} for UUID zero value)
     */
    private static Object readPlayerValue(StateValueType type, ResourceLocation stateId, PlayerState playerState) {
        return switch (type) {
            case INT -> playerState.getInt(stateId);
            case LONG -> playerState.getLong(stateId);
            case DOUBLE -> playerState.getDouble(stateId);
            case FLOAT -> playerState.getFloat(stateId);
            case BOOLEAN -> playerState.getBoolean(stateId);
            case STRING -> playerState.getString(stateId);
            case UUID -> playerState.getUuid(stateId);
        };
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    /**
     * Filters out entries whose value equals the default when
     * {@code hide_default} is {@code true}.
     *
     * @param entries all collected entries
     * @return a new list containing only visible entries
     */
    private static List<StateEntry> filterVisible(List<StateEntry> entries) {
        List<StateEntry> visible = new ArrayList<>();
        for (StateEntry entry : entries) {
            if (!isHiddenDefault(entry)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    /**
     * Checks whether an entry should be hidden because its value equals
     * the default and {@code hide_default} is {@code true}.
     *
     * @param entry the entry to check
     * @return {@code true} if the entry should be hidden
     */
    private static boolean isHiddenDefault(StateEntry entry) {
        StateDisplay display = entry.definition().display();
        return display.hideDefault()
                && Objects.equals(entry.value(), entry.definition().defaultValue());
    }

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    /**
     * Compares two state entries for tooltip ordering.
     *
     * <p>Primary sort key: {@code display.priority} descending (higher
     * priority appears first). Secondary sort key: state id
     * ({@link ResourceLocation}) ascending (dictionary order).</p>
     *
     * @param a the first entry
     * @param b the second entry
     * @return a negative integer if {@code a} should appear before
     *         {@code b}, a positive integer if after, zero if equal
     */
    private static int compareEntries(StateEntry a, StateEntry b) {
        int byPriority = Integer.compare(
                b.definition().display().priority(),
                a.definition().display().priority());
        if (byPriority != 0) {
            return byPriority;
        }
        return a.id().compareTo(b.id());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Builds a single tooltip line for a state entry.
     *
     * <p>Format: {@code "  <name>: <formatted-value>"} where the name is
     * coloured with {@code display.color} and the value is grey, matching
     * the design doc layout (§默认 tooltip 布局).</p>
     *
     * @param entry the state entry to render
     * @return a {@link Component} line
     */
    private static Component buildLine(StateEntry entry) {
        StateDisplay display = entry.definition().display();
        int nameColor = display.color().map(TooltipUtils::parseHexColor).orElse(0xFFFFFF);
        String valueText = formatValue(entry.value(), entry.definition().valueType(), display.format());
        return Component.empty()
                .append(Component.literal("  "))
                .append(TooltipUtils.resolveText(display.name()).withColor(nameColor))
                .append(Component.literal(": "))
                .append(Component.literal(valueText).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Formats a state value using the display template.
     *
     * <p>Replaces the {@code {value}} placeholder in the format template
     * with the string representation of the value.</p>
     *
     * @param value  the raw value (may be {@code null} for UUID)
     * @param type   the declared value type
     * @param format the format template containing {@code {value}}
     * @return the formatted value string
     */
    private static String formatValue(Object value, StateValueType type, String format) {
        String valueStr = valueToString(value, type);
        return format.replace("{value}", valueStr);
    }

    /**
     * Converts a typed state value to its display string.
     *
     * <p>Type-specific formatting:
     * <ul>
     *   <li>{@code int}/{@code long} — {@code String.valueOf}</li>
     *   <li>{@code double}/{@code float} — {@link TooltipUtils#formatValue}
     *       (trims trailing zeros, consistent with attribute values), or
     *       {@code "-"} when the value is {@code null}</li>
     *   <li>{@code boolean} — {@code "true"}/{@code "false"}</li>
     *   <li>{@code string} — the raw string</li>
     *   <li>{@code uuid} — first 8 characters, or {@code "-"} when null</li>
     * </ul>
     * </p>
     *
     * <p><b>Defensive null handling (S18 fix).</b> The {@code double} and
     * {@code float} cases null-check the boxed value before unboxing it
     * for {@link TooltipUtils#formatValue}, returning the same {@code "-"}
     * sentinel used by the {@code uuid} case. Although the current
     * {@link GunState#getDouble} / {@link PlayerState#getDouble} (and
     * {@code float} counterparts) return primitive {@code double}/
     * {@code float} — which auto-box to non-null {@code Double}/
     * {@code Float} — this method receives a generic {@link Object} and
     * could be fed a {@code null} by a future reader path or a custom
     * state source. An unchecked unbox of {@code null} would throw
     * {@link NullPointerException} and crash the entire tooltip. The guard
     * keeps the render path robust regardless of caller, matching the
     * existing {@code uuid} null-sentinel pattern (设计文档 §防御性编程).</p>
     *
     * @param value the raw value (may be {@code null} for UUID, DOUBLE, or
     *              FLOAT; rendered as {@code "-"} in those cases)
     * @param type  the declared value type
     * @return the display string
     */
    private static String valueToString(Object value, StateValueType type) {
        return switch (type) {
            case INT, LONG -> String.valueOf(value);
            case DOUBLE -> value != null ? TooltipUtils.formatValue((Double) value) : "-";
            case FLOAT -> value != null ? TooltipUtils.formatValue((Float) value) : "-";
            case BOOLEAN -> String.valueOf(value);
            case STRING -> String.valueOf(value);
            case UUID -> value != null ? ((UUID) value).toString().substring(0, 8) : "-";
        };
    }

    // ------------------------------------------------------------------
    // Internal data carrier
    // ------------------------------------------------------------------

    /**
     * Immutable carrier for a collected state entry's id, definition, and
     * current value.
     *
     * @param id         the state definition id (registry key)
     * @param definition the state definition (display metadata, default value)
     * @param value      the current value read from the gun or player
     */
    private record StateEntry(
            ResourceLocation id,
            StateDefinition definition,
            Object value
    ) {
    }
}
