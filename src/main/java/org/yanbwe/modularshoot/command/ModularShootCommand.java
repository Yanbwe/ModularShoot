package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Registers the {@code /modularshoot} debug command tree on the NeoForge
 * game event bus.
 *
 * <p>The root node is gated behind op permission level {@code 2}, which
 * corresponds to the conceptual {@code modularshoot.command} permission node
 * (设计文档 §调试命令). The read-only {@code stats} and {@code plugins}
 * subcommands and the mutating {@code gun} / {@code plugin} / {@code bullets}
 * / {@code debug} subcommands are all wired in {@link #buildRoot()}.</p>
 *
 * @see StatsSubcommand
 * @see PluginsSubcommand
 * @see GunSubcommand
 * @see PluginSubcommand
 * @see BulletsSubcommand
 * @see DebugSubcommand
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootCommand {

    /** Op permission level required to run any {@code /modularshoot} subcommand. */
    private static final int PERMISSION_LEVEL = 2;

    private ModularShootCommand() {
    }

    /**
     * Fired on the NeoForge game bus whenever the command dispatcher is rebuilt.
     *
     * @param event the register-commands event carrying the dispatcher
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(buildRoot());
    }

    /**
     * Builds the {@code /modularshoot} root node with op gating and all
     * registered subcommands.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return Commands.literal("modularshoot")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(StatsSubcommand.create())
                .then(PluginsSubcommand.create())
                .then(GunSubcommand.create())
                .then(PluginSubcommand.create())
                .then(BulletsSubcommand.create())
                .then(DebugSubcommand.create());
    }
}
