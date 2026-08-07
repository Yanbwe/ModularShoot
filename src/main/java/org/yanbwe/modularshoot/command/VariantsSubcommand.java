package org.yanbwe.modularshoot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.variant.VariantPoolService;

/**
 * {@code /modularshoot variants} subcommand: previews the per-shot variant
 * pool of the executor's main-hand gun (机制四, 规格 §6).
 *
 * <p>Reports every candidate with its final weight and derived percentage —
 * the gun's declared {@code variants}, each valid installed plugin's
 * {@code adds_variants} (summed), every registered contributor's weight
 * modifiers (三阶段计算), and the implicit normal-bullet fallback (weight
 * {@code 1.0}) when the gun declares no {@code variants} (规格 §6.4).
 * Probabilities are computed server-side via
 * {@link VariantPoolService#previewPool}, so they reflect the exact pool a
 * roll would sample — including Java-API contributor modifiers the client
 * cannot see.</p>
 */
public final class VariantsSubcommand {

    private VariantsSubcommand() {
    }

    /**
     * Builds the {@code variants} subcommand node for registration by
     * {@link ModularShootCommand}.
     *
     * @return a literal-argument builder for {@code variants}
     */
    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("variants").executes(VariantsSubcommand::execute);
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ItemStack gun = player.getMainHandItem();
        if (!ModularShootAPI.isGun(gun, player.registryAccess())) {
            source.sendFailure(Component.translatable("modularshoot.command.no_gun")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        Optional<GunData> gunData = ModularShootAPI.getGunData(gun);
        Optional<GunDefinition> gunDef = gunData.flatMap(data ->
                ModularShootAPI.getGunDefinition(player.registryAccess(), data.gunId()));
        if (gunData.isEmpty() || gunDef.isEmpty()) {
            source.sendFailure(Component.translatable("modularshoot.command.no_gun")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        List<VariantPoolService.PoolEntry> pool =
                VariantPoolService.previewPool(player.registryAccess(), gunDef.get(), gunData.get());
        source.sendSuccess(() -> buildPoolComponent(gun, pool), false);
        return 1;
    }

    /**
     * Assembles the pool report: a header carrying the gun display name, then
     * one line per candidate — variant id (or the localised normal-bullet
     * fallback label), final weight and derived percentage.
     */
    private static Component buildPoolComponent(ItemStack gun, List<VariantPoolService.PoolEntry> pool) {
        double total = 0.0;
        for (VariantPoolService.PoolEntry entry : pool) {
            total += entry.finalWeight();
        }
        MutableComponent root = Component.empty()
                .append(Component.translatable("modularshoot.command.variants_header").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(gun.getHoverName().getString()).withStyle(ChatFormatting.WHITE));
        if (pool.isEmpty()) {
            root.append(Component.literal("\n  ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("modularshoot.command.variants_empty").withStyle(ChatFormatting.GRAY));
            return root;
        }
        for (VariantPoolService.PoolEntry entry : pool) {
            double percent = total > 0.0 ? entry.finalWeight() / total * 100.0 : 0.0;
            root.append(Component.literal("\n  ").withStyle(ChatFormatting.GRAY))
                    .append(entry.normalFallback()
                            ? Component.translatable("modularshoot.command.variant_normal")
                            : Component.literal(entry.variantId().toString()))
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.translatable("modularshoot.command.variant_weight",
                            formatWeight(entry.finalWeight()), formatPercent(percent))
                            .withStyle(ChatFormatting.GREEN));
        }
        return root;
    }

    /** Formats a weight without a trailing {@code .0} for whole numbers. */
    private static String formatWeight(double weight) {
        return weight == Math.floor(weight) ? Long.toString((long) weight) : Double.toString(weight);
    }

    /** Formats a percentage with one decimal place. */
    private static String formatPercent(double percent) {
        double rounded = Math.round(percent * 10.0) / 10.0;
        return rounded == Math.floor(rounded)
                ? Long.toString((long) rounded) + "%"
                : Double.toString(rounded) + "%";
    }
}
