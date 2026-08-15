package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.render.DynamicOutlineTintRegistry.GunOutlineTintProvider;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DynamicOutlineTintRegistry} resolution semantics:
 * providers are keyed by plugin id and resolved in installed-plugin order.
 *
 * <p>The registry is a static singleton shared across tests, so
 * {@link #clearRegistry()} resets it before every case. Providers ignore the
 * {@code stack} parameter here (a {@code null} stack is fine), which keeps the
 * tests free of any Minecraft item state.</p>
 */
class DynamicOutlineTintRegistryTest {

    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("examplemod:plugin_a");
    private static final ResourceLocation PLUGIN_B = ResourceLocation.parse("examplemod:plugin_b");

    /** Builds a provider returning a constant red tint of the given intensity. */
    private static GunOutlineTintProvider provider(float red) {
        return (stack, partialTick) -> new Vector4f(red, 0.0F, 0.0F, 1.0F);
    }

    @BeforeEach
    void clearRegistry() {
        DynamicOutlineTintRegistry.clear();
        DynamicOutlineTintRegistry.resetCacheClearCount();
    }

    @Test
    void resolveReturnsFirstRegisteredInListOrder() {
        DynamicOutlineTintRegistry.register(PLUGIN_A, provider(0.5F));
        DynamicOutlineTintRegistry.register(PLUGIN_B, provider(0.9F));
        Optional<Vector4f> tint = DynamicOutlineTintRegistry.resolve(
                List.of(PLUGIN_B, PLUGIN_A), null, 0.5F);
        assertTrue(tint.isPresent(), "a registered plugin id yields a tint");
        assertEquals(0.9F, tint.get().x(), 1.0E-5F, "the first listed plugin with a provider wins");
    }

    @Test
    void resolveEmptyWhenNoneRegistered() {
        assertTrue(DynamicOutlineTintRegistry.resolve(List.of(PLUGIN_A), null, 0.0F).isEmpty(),
                "no registered providers yield no tint");
    }

    @Test
    void resolveSkipsUnregisteredIds() {
        DynamicOutlineTintRegistry.register(PLUGIN_A, provider(0.5F));
        Optional<Vector4f> tint = DynamicOutlineTintRegistry.resolve(
                List.of(ResourceLocation.parse("examplemod:other"), PLUGIN_A), null, 0.0F);
        assertTrue(tint.isPresent(), "unregistered ids are skipped until a registered one is found");
        assertEquals(0.5F, tint.get().x(), 1.0E-5F);
    }

    @Test
    void registerInvalidatesDynamicGunTextureCache() {
        // 阶段 7 / 任务 7.1 (审查 Medium 1): registering a provider must clear
        // the composited gun texture cache so already-cached entries that lack
        // the dynamic outline mask get rebuilt with one.
        assertEquals(0, DynamicOutlineTintRegistry.textureCacheClearCount,
                "cache clear counter starts at 0 after reset");

        DynamicOutlineTintRegistry.register(PLUGIN_A, provider(0.5F));

        assertEquals(1, DynamicOutlineTintRegistry.textureCacheClearCount,
                "registering a provider must trigger one texture-cache clear");
    }

    @Test
    void everyRegisterInvalidatesCacheExactlyOnce() {
        // Two registrations → two cache invalidations (each register clears).
        DynamicOutlineTintRegistry.register(PLUGIN_A, provider(0.5F));
        DynamicOutlineTintRegistry.register(PLUGIN_B, provider(0.9F));
        assertEquals(2, DynamicOutlineTintRegistry.textureCacheClearCount,
                "each register call invalidates the texture cache once");
    }
}
