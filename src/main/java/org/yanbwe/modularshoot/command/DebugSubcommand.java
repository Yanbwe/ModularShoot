package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;

/**
 * {@code /modularshoot debug on|off} subcommand: toggles a live debug overlay
 * that continuously displays the executor's main-hand gun attribute values on
 * the action bar (设计文档 §调试命令).
 *
 * <p>When {@code debug on} is executed the static {@link #debugMode} flag is
 * set to {@code true} and the executor's uuid is recorded in
 * {@link #debugPlayerUuid}. A {@link LevelTickEvent.Post} listener then runs
 * every tick: it looks up the recorded player on the server, checks the main
 * hand for a gun, and sends an action-bar message with the stacked attribute
 * values. {@code debug off} clears both fields and stops the overlay.</p>
 *
 * <p>The flag and uuid are {@code volatile} because they are written from the
 * command thread (netty) and read from the server tick thread. Only one
 * player can be tracked at a time; a second {@code debug on} from another
 * player silently switches the tracked uuid.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class DebugSubcommand {

    /** Whether the live debug overlay is currently active. */
    private static volatile boolean debugMode = false;

    /** Uuid of the player whose main-hand gun should be reported; {@code null} when inactive. */
    private static volatile UUID debugPlayerUuid = null;

    /** Framework attributes to report on the action bar, in canonical display order. */
    private static final List<Holder<Attribute>> DEBUG_ATTRIBUTES = List.of(
            ModularShootAttributes.HIT_DAMAGE,
            ModularShootAttributes.FIRE_RATE,
            ModularShootAttributes.RANGE,
            ModularShootAttributes.ACCURACY_YAW,
            ModularShootAttributes.ACCURACY_PITCH,
            ModularShootAttributes.ENTITY_PENETRATION,
            ModularShootAttributes.BULLET_SPEED,
            ModularShootAttributes.BULLET_SIZE,
            ModularShootAttributes.BLOCK_PENETRATION);

    private DebugSubcommand() {
    }

    /**
     * Builds the {@code debug} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code debug} with
     *         {@code on} / {@code off} literal branches
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("debug")
                .then(Commands.literal("on").executes(ctx -> setDebug(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setDebug(ctx, false)));
    }

    private static int setDebug(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        debugMode = on;
        if (on) {
            debugPlayerUuid = player.getUUID();
            source.sendSuccess(() -> Component.literal("调试模式已开启")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            debugPlayerUuid = null;
            source.sendSuccess(() -> Component.literal("调试模式已关闭")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * Fired on the NeoForge game bus once per level tick, after the level
     * finishes its work. When the debug overlay is active and the tracked
     * player is online in this dimension, sends an action-bar message with
     * the main-hand gun's stacked attribute values.
     *
     * @param event the post level-tick event
     */
    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!debugMode || debugPlayerUuid == null) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(debugPlayerUuid);
        if (player == null) {
            return;
        }
        ItemStack gun = player.getMainHandItem();
        if (!ModularShootAPI.isGun(gun)) {
            return;
        }
        player.displayClientMessage(buildDebugComponent(player, gun), true);
    }

    /**
     * Assembles the single-line action-bar debug overlay: a prefix, the gun
     * display name, then each framework attribute with its stacked value.
     */
    private static Component buildDebugComponent(ServerPlayer player, ItemStack gun) {
        MutableComponent root = Component.literal("[MS Debug] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(gun.getHoverName().getString()).withStyle(ChatFormatting.WHITE));
        for (Holder<Attribute> holder : DEBUG_ATTRIBUTES) {
            double value = player.getAttributeValue(holder);
            root.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(holder.value().getDescriptionId()).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(": " + formatValue(value)).withStyle(ChatFormatting.GREEN));
        }
        return root;
    }

    /** Formats a double without a trailing {@code .0} for whole numbers. */
    private static String formatValue(double value) {
        return value == Math.floor(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
