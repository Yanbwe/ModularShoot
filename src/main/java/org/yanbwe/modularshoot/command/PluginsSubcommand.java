package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.PluginInstance;

/**
 * {@code /modularshoot plugins} subcommand: lists every plugin installed on
 * the executor's main-hand gun.
 *
 * <p>For each {@link PluginInstance} the command reports the plugin definition
 * id, the install-time category id, the stable instance uuid and the lock
 * state. When the main hand does not hold a gun, or when the gun carries no
 * plugins, a notice is sent and the command exits cleanly without crashing
 * (设计文档 §调试命令).</p>
 */
public final class PluginsSubcommand {

    private PluginsSubcommand() {
    }

    /**
     * Builds the {@code plugins} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code plugins}
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("plugins").executes(PluginsSubcommand::execute);
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ItemStack gun = player.getMainHandItem();
        if (!ModularShootAPI.isGun(gun)) {
            source.sendFailure(Component.translatable("modularshoot.command.no_gun")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        source.sendSuccess(() -> buildPluginsComponent(gun), false);
        return 1;
    }

    /**
     * Assembles the plugin listing: a header carrying the plugin count followed
     * by one formatted line per installed instance, or a no-plugins notice.
     */
    private static Component buildPluginsComponent(ItemStack gun) {
        List<PluginInstance> plugins = ModularShootAPI.getInstalledPlugins(gun);
        if (plugins.isEmpty()) {
            return Component.translatable("modularshoot.command.no_plugins").withStyle(ChatFormatting.YELLOW);
        }
        MutableComponent root = Component.translatable("modularshoot.command.plugins_header", plugins.size())
                .withStyle(ChatFormatting.AQUA);
        for (PluginInstance plugin : plugins) {
            root.append(formatPlugin(plugin));
        }
        return root;
    }

    /** Formats a single plugin instance as a coloured one-line entry. */
    private static Component formatPlugin(PluginInstance plugin) {
        String lockKey = plugin.locked()
                ? "modularshoot.command.plugin_locked"
                : "modularshoot.command.plugin_unlocked";
        ChatFormatting lockStyle = plugin.locked() ? ChatFormatting.RED : ChatFormatting.GREEN;
        return Component.literal("\n  • ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(plugin.pluginId().toString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  [type: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(plugin.installedTypeId().toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("]  [uuid: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(plugin.instanceUuid().toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("]  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable(lockKey).withStyle(lockStyle));
    }
}
