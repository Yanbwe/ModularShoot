package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.plugin.PluginRegistry;

/**
 * {@code /modularshoot plugin <命名空间:插件ID> [数量]} subcommand: gives the
 * executor one or more plugin item stacks bound to the specified plugin
 * definition (设计文档 §调试命令).
 *
 * <p>The plugin definition id is validated against the
 * {@code modularshoot:plugins} dynamic registry of the executor's current
 * world. When the id does not exist a red failure notice is sent and the
 * command exits cleanly without crashing. When the id exists the requested
 * number of fresh plugin stacks are created via
 * {@link PluginRegistry#createPluginStack(ResourceLocation)} and added to the
 * executor's inventory; any overflow is dropped at the player's feet.</p>
 *
 * <p>The optional {@code count} argument defaults to {@code 1} and is clamped
 * to {@code [1, 64]}. Because the framework plugin item has a max stack size
 * of {@code 1}, each plugin is a separate single-item stack.</p>
 */
public final class PluginSubcommand {

    /** Default plugin count when the optional {@code count} argument is absent. */
    private static final int DEFAULT_COUNT = 1;

    private PluginSubcommand() {
    }

    /**
     * Builds the {@code plugin} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code plugin} with a
     *         {@code plugin_id} resource-location argument and an optional
     *         {@code count} integer argument
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("plugin")
                .then(Commands.argument("plugin_id", ResourceLocationArgument.id())
                        .executes(PluginSubcommand::execute)
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(PluginSubcommand::executeWithCount)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return givePlugins(context, DEFAULT_COUNT);
    }

    private static int executeWithCount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int count = IntegerArgumentType.getInteger(context, "count");
        return givePlugins(context, count);
    }

    private static int givePlugins(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ResourceLocation pluginId = ResourceLocationArgument.getId(context, "plugin_id");

        if (PluginRegistry.getPlugin(source.getLevel(), pluginId).isEmpty()) {
            source.sendFailure(Component.literal("插件定义不存在: " + pluginId)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        for (int i = 0; i < count; i++) {
            ItemStack stack = PluginRegistry.createPluginStack(pluginId);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        final int given = count;
        source.sendSuccess(() -> Component.literal("已给予插件: " + pluginId + " x" + given)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
