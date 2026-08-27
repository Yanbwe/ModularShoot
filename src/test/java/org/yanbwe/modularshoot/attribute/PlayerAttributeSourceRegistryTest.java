package org.yanbwe.modularshoot.attribute;

import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.ModularShootAPI;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAttributeSourceRegistryTest {

    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");

    static {
        // FML shim + game-version shim, then vanilla bootstrap so ItemStack/Items
        // are usable in the headless JUnit environment (project test bridge).
        net.neoforged.fml.loading.LoadingModList.of(
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void emptyRegistryResolvesEmpty() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertTrue(PlayerAttributeSourceRegistry.resolve(stack, null).isEmpty());
    }

    @Test
    void firstNonEmptyProviderWins() {
        ItemStack stack = new ItemStack(Items.STICK);
        PlayerAttributeValueReader first = (id, access) -> 1.0;
        PlayerAttributeValueReader second = (id, access) -> 2.0;
        PlayerAttributeSourceRegistry.register((s, viewer) ->
                s == stack ? Optional.of(first) : Optional.empty());
        PlayerAttributeSourceRegistry.register((s, viewer) ->
                s == stack ? Optional.of(second) : Optional.empty());

        Optional<PlayerAttributeValueReader> result =
                PlayerAttributeSourceRegistry.resolve(stack, null);

        assertTrue(result.isPresent());
        assertSame(first, result.get(), "first non-empty provider must win");
    }

    @Test
    void emptyProvidersFallThrough() {
        ItemStack stack = new ItemStack(Items.STICK);
        PlayerAttributeValueReader reader = (id, access) -> 42.0;
        PlayerAttributeSourceRegistry.register((s, viewer) -> Optional.empty());
        PlayerAttributeSourceRegistry.register((s, viewer) ->
                s == stack ? Optional.of(reader) : Optional.empty());

        Optional<PlayerAttributeValueReader> result =
                PlayerAttributeSourceRegistry.resolve(stack, null);

        assertTrue(result.isPresent());
        assertSame(reader, result.get());
    }

    @Test
    void facadeRegistersProvider() {
        ItemStack stack = new ItemStack(Items.STICK);
        PlayerAttributeValueReader reader = (id, access) -> 7.0;
        ModularShootAPI.registerPlayerAttributeSourceProvider((s, viewer) ->
                s == stack ? Optional.of(reader) : Optional.empty());

        Optional<PlayerAttributeValueReader> result =
                PlayerAttributeSourceRegistry.resolve(stack, null);

        assertTrue(result.isPresent());
        assertSame(reader, result.get());
    }

    @Test
    void readerReceivesLogicalIdAndRegistryAccess() {
        ItemStack stack = new ItemStack(Items.STICK);
        RegistryAccess access = RegistryAccess.EMPTY;
        ResourceLocation expectedId = HIT_DAMAGE;
        PlayerAttributeSourceRegistry.register((s, viewer) ->
                s == stack ? Optional.of(
                        (id, acc) -> id.equals(expectedId) && acc == access ? 9.0 : 0.0)
                        : Optional.empty());

        Optional<PlayerAttributeValueReader> result =
                PlayerAttributeSourceRegistry.resolve(stack, null);

        assertTrue(result.isPresent());
        assertEquals(9.0, result.get().read(expectedId, access), 1.0E-9);
    }
}