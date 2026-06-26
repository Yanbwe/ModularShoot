package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;

/**
 * {@code /modularshoot stats} subcommand: reports the final, post-modifier
 * attribute values of the executor's main-hand gun.
 *
 * <p>Each value is read via {@link ServerPlayer#getAttributeValue(Holder)}, which
 * returns the fully stacked result of every attribute source (gun base,
 * installed plugins, traits, external modifiers). When the main hand does not
 * hold a gun a notice is sent and the command exits cleanly without crashing
 * (设计文档 §调试命令).</p>
 */
public final class StatsSubcommand {

    /** Framework attributes to report, in canonical display order. */
    private static final List<Holder<Attribute>> FRAMEWORK_ATTRIBUTES = List.of(
            ModularShootAttributes.HIT_DAMAGE,
            ModularShootAttributes.FIRE_RATE,
            ModularShootAttributes.RANGE,
            ModularShootAttributes.ACCURACY_YAW,
            ModularShootAttributes.ACCURACY_PITCH,
            ModularShootAttributes.ENTITY_PENETRATION,
            ModularShootAttributes.BULLET_SPEED,
            ModularShootAttributes.BULLET_SIZE,
            ModularShootAttributes.BLOCK_PENETRATION);

    private StatsSubcommand() {
    }

    /**
     * Builds the {@code stats} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code stats}
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("stats").executes(StatsSubcommand::execute);
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
        source.sendSuccess(() -> buildStatsComponent(player, gun), false);
        return 1;
    }

    /**
     * Assembles the multi-line stats report: a header carrying the gun display
     * name followed by one line per framework attribute with its stacked value.
     */
    private static Component buildStatsComponent(ServerPlayer player, ItemStack gun) {
        MutableComponent root = Component.empty()
                .append(Component.translatable("modularshoot.command.stats_header").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(gun.getHoverName().getString()).withStyle(ChatFormatting.WHITE));
        for (Holder<Attribute> holder : FRAMEWORK_ATTRIBUTES) {
            double value = player.getAttributeValue(holder);
            root.append(Component.literal("\n  ").withStyle(ChatFormatting.GRAY))
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
