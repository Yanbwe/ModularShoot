package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * {@code /modularshoot bullets} subcommand: reports the count and brief
 * status of all active bullets in the executor's current dimension
 * (设计文档 §调试命令).
 *
 * <p>Delegates to {@link BulletManager#get} to obtain the per-dimension
 * manager and {@link BulletManager#getAllBullets()} for a point-in-time
 * snapshot. The report shows the total count followed by up to
 * {@link #MAX_SHOWN} individual bullet entries (id, position, age); any
 * remaining bullets are summarised with an ellipsis line.</p>
 */
public final class BulletsSubcommand {

    /** Maximum number of individual bullet entries to display before summarising. */
    private static final int MAX_SHOWN = 10;

    private BulletsSubcommand() {
    }

    /**
     * Builds the {@code bullets} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code bullets}
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("bullets").executes(BulletsSubcommand::execute);
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BulletManager manager = BulletManager.get(source.getLevel());
        Collection<BulletRecord> bullets = manager.getAllBullets();
        ResourceLocation dimension = source.getLevel().dimension().location();
        source.sendSuccess(() -> buildBulletsComponent(dimension, bullets), false);
        return 1;
    }

    /**
     * Assembles the bullets report: a header carrying the dimension id and
     * total count, followed by up to {@link #MAX_SHOWN} per-bullet lines.
     */
    private static Component buildBulletsComponent(ResourceLocation dimension, Collection<BulletRecord> bullets) {
        MutableComponent root = Component.literal("当前世界 [" + dimension + "] 活跃子弹: " + bullets.size())
                .withStyle(ChatFormatting.AQUA);
        int shown = 0;
        for (BulletRecord bullet : bullets) {
            if (shown >= MAX_SHOWN) {
                root.append(Component.literal("\n  ...").withStyle(ChatFormatting.GRAY));
                break;
            }
            root.append(formatBullet(bullet));
            shown++;
        }
        return root;
    }

    /** Formats a single bullet as a one-line entry with id, position and age. */
    private static Component formatBullet(BulletRecord bullet) {
        Vec3 pos = bullet.getPosition();
        return Component.literal("\n  • #").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(bullet.getBulletId())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" pos: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(formatVec3(pos)).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" age: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(bullet.getAge())).withStyle(ChatFormatting.GRAY));
    }

    /** Formats a {@link Vec3} as {@code (x.x, y.y, z.z)} with one decimal place. */
    private static String formatVec3(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }
}
