package org.yanbwe.modularshoot.attribute;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.AttributeMount;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for the player-side {@code attribute_mount} guard and stale
 * component cleanup in {@link AttributeModifierService} (任务 2).
 *
 * <p>Same JUnit bridge as {@code AttributeModifierServiceIdTest}: FML shim +
 * vanilla bootstrap + a reflection-bound {@code GUN_DATA} holder for headless
 * {@link ItemStack} reads. Each test uses fresh {@link MappedRegistry}s so the
 * shared static caches cannot leak state across cases.</p>
 */
class AttributeModifierServiceMountTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:mount_test_gun");
    private static final ResourceLocation PLAYER_GUN_ID = ResourceLocation.parse("modularshoot:mount_test_player_gun");
    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.parse("minecraft:generic.attack_damage");

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to AttributeModifierServiceIdTest).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        bindGunDataHolder();
    }

    /** Binds the {@code GUN_DATA} DeferredHolder to a direct holder for headless stack reads. */
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
    void applyModifiersOnPlayerSideClearsComponent() {
        ItemStack stack = gunStack();
        AttributeModifierService.applyModifiers(stack, gunDef(AttributeMount.PLAYER), access());
        assertEquals(ItemAttributeModifiers.EMPTY, stack.get(DataComponents.ATTRIBUTE_MODIFIERS));
    }

    @Test
    void refreshModifiersOnPlayerSideClearsStaleComponent() {
        ItemStack stack = gunStack();
        // 先按物品侧写入，模拟旧版本/热切换残留
        AttributeModifierService.applyModifiers(stack, gunDef(AttributeMount.ITEM), access());
        assertFalse(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());

        // 定义切换为玩家侧后刷新必须清空
        AttributeModifierService.refreshModifiers(stack, accessWithGun(gunDef(AttributeMount.PLAYER)));
        assertEquals(ItemAttributeModifiers.EMPTY, stack.get(DataComponents.ATTRIBUTE_MODIFIERS));
    }

    @Test
    void refreshModifiersOnItemSideStillWritesModifiers() {
        ItemStack stack = gunStack();
        AttributeModifierService.refreshModifiers(stack, access());
        assertFalse(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());
    }

    @Test
    void inventoryRefreshMixedMountsClearsPlayerSideAndRefreshesItemSide() {
        ItemStack itemGun = gunStack();
        ItemStack playerGun = gunStack();
        RegistryAccess access = accessWithGuns(Map.of(GUN_ID, gunDef(AttributeMount.ITEM), PLAYER_GUN_ID, gunDef(AttributeMount.PLAYER)));

        // itemGun 仍用 GUN_ID；playerGun 需使用 PLAYER_GUN_ID 的 GunData
        ItemStack playerGunWithData = gunStackWithId(PLAYER_GUN_ID);
        List<ItemStack> inventory = new ArrayList<>(List.of(itemGun, new ItemStack(Items.STICK), playerGunWithData));
        int count = AttributeModifierService.refreshGunModifiersInInventory(inventory, ItemStack.EMPTY, access);

        assertEquals(2, count);
        assertFalse(itemGun.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty());
        assertEquals(ItemAttributeModifiers.EMPTY, playerGunWithData.get(DataComponents.ATTRIBUTE_MODIFIERS));
    }

    @Test
    void getAttributeMountReturnsDeclaredMount() {
        RegistryAccess access = accessWithGuns(Map.of(
                GUN_ID, gunDef(AttributeMount.ITEM),
                PLAYER_GUN_ID, gunDef(AttributeMount.PLAYER)));

        assertEquals(Optional.of(AttributeMount.ITEM),
                ModularShootAPI.getAttributeMount(gunStack(), access));
        assertEquals(Optional.of(AttributeMount.PLAYER),
                ModularShootAPI.getAttributeMount(gunStackWithId(PLAYER_GUN_ID), access));
    }

    @Test
    void getAttributeMountReturnsEmptyForNonGun() {
        assertTrue(ModularShootAPI.getAttributeMount(new ItemStack(Items.STICK), access()).isEmpty());
    }

    // ──────────────── Test data helpers ────────────────

    private static GunDefinition gunDef(AttributeMount mount) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(HIT_DAMAGE, 5.0),
                Map.of(), Map.of(), Map.of(),
                Optional.empty(), Map.of(), Map.of(),
                Optional.empty(), mount);
    }

    private static RegistryAccess access() {
        return accessWithGuns(Map.of(GUN_ID, gunDef(AttributeMount.ITEM)));
    }

    private static RegistryAccess accessWithGun(GunDefinition def) {
        return accessWithGuns(Map.of(GUN_ID, def));
    }

    private static RegistryAccess accessWithGuns(Map<ResourceLocation, GunDefinition> gunDefs) {
        MappedRegistry<AttributeMeta> metas = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        gunDefs.forEach((id, def) -> Registry.register(guns, id, def));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(guns, plugins, metas));
    }

    private static ItemStack gunStack() {
        return gunStackWithId(GUN_ID);
    }

    private static ItemStack gunStackWithId(ResourceLocation id) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                new GunData(id, UUID.randomUUID(), List.of(), 0, new CompoundTag()));
        return stack;
    }
}