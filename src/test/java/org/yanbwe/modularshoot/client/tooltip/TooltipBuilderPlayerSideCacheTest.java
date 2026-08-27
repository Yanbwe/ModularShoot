package org.yanbwe.modularshoot.client.tooltip;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.AttributeMount;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipBuilderPlayerSideCacheTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:tooltip_player_gun");
    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.parse("minecraft:generic.attack_damage");

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        bindGunDataHolder();
    }

    private static void bindGunDataHolder() {
        try {
            Field holderField = net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
        } catch (Exception e) {
            throw new IllegalStateException("GUN_DATA DeferredHolder bind failed", e);
        }
    }

    @Test
    void playerSideGunBypassesCache() {
        Registry<AttributeMeta> metas = registryWithMeta(HIT_DAMAGE, ATTACK_DAMAGE, 5.0);
        RegistryAccess access = accessWithGun(metas, gunDef(AttributeMount.PLAYER, Map.of(HIT_DAMAGE, 7.0)));
        ItemStack stack = gunStack();

        assertTrue(TooltipBuilder.shouldBypassCache(stack, access));
    }

    @Test
    void itemSideGunUsesCache() {
        Registry<AttributeMeta> metas = registryWithMeta(HIT_DAMAGE, ATTACK_DAMAGE, 5.0);
        RegistryAccess access = accessWithGun(metas, gunDef(AttributeMount.ITEM, Map.of(HIT_DAMAGE, 7.0)));
        ItemStack stack = gunStack();

        assertFalse(TooltipBuilder.shouldBypassCache(stack, access));
    }

    private static Registry<AttributeMeta> registryWithMeta(
            ResourceLocation logicalId, ResourceLocation binds, double defaultValue) {
        MappedRegistry<AttributeMeta> metas = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, logicalId, AttributeMeta.of(binds, defaultValue));
        return metas;
    }

    private static RegistryAccess accessWithGun(
            Registry<AttributeMeta> metas, GunDefinition gun) {
        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, GUN_ID, gun);
        return new RegistryAccess.ImmutableRegistryAccess(List.of(guns, metas));
    }

    private static GunDefinition gunDef(AttributeMount mount, Map<ResourceLocation, Double> stats) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                stats,
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                mount);
    }

    private static ItemStack gunStack() {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), new GunData(GUN_ID, UUID.randomUUID(), List.of(), 0, new net.minecraft.nbt.CompoundTag()));
        return stack;
    }
}