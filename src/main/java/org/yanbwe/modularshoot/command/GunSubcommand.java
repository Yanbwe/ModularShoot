package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * {@code /modularshoot gun <命名空间:枪械ID>} subcommand: gives the executor
 * a gun item stack bound to the specified gun definition
 * (设计文档 §调试命令).
 *
 * <p>The gun definition id is validated against the
 * {@code modularshoot:guns} dynamic registry of the executor's current world.
 * When the id does not exist a red failure notice is sent and the command
 * exits cleanly without crashing. When the id exists a fresh gun stack
 * (with a random instance uuid) is created via
 * {@link GunRegistry#createGunStack(ResourceLocation)} and added to the
 * executor's inventory; any overflow is dropped at the player's feet.</p>
 */
public final class GunSubcommand {

    private GunSubcommand() {
    }

    /**
     * Builds the {@code gun} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code gun} with a
     *         {@code gun_id} resource-location argument
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("gun")
                .then(Commands.argument("gun_id", ResourceLocationArgument.id())
                        .executes(GunSubcommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ResourceLocation gunId = ResourceLocationArgument.getId(context, "gun_id");

        if (GunRegistry.getGun(source.getLevel(), gunId).isEmpty()) {
            source.sendFailure(Component.literal("枪械定义不存在: " + gunId)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        ItemStack stack = GunRegistry.createGunStack(gunId);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        source.sendSuccess(() -> Component.literal("已给予枪械: " + gunId)
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
