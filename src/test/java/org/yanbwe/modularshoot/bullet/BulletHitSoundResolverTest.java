package org.yanbwe.modularshoot.bullet;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BulletHitSoundResolver}: hit-type to sound-slot resolution.
 */
class BulletHitSoundResolverTest {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "textures/gun/test.png");
    private static final ResourceLocation HIT_ENTITY_SOUND =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "hit_entity");
    private static final ResourceLocation HIT_BLOCK_SOUND =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "hit_block");
    private static final ResourceLocation HIT_PIERCE_SOUND =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "hit_pierce");

    private static GunDefinition definitionWithSounds(Map<String, ResourceLocation> sounds) {
        return new GunDefinition(
                Optional.empty(),
                TEXTURE,
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                sounds,
                Optional.empty(),
                Map.of(),
                Optional.empty());
    }

    @Test
    void resolveMapsHitTypeToConfiguredSlot() {
        GunDefinition definition = definitionWithSounds(Map.of(
                "hit_entity", HIT_ENTITY_SOUND,
                "hit_block", HIT_BLOCK_SOUND,
                "hit_pierce", HIT_PIERCE_SOUND));
        assertEquals(Optional.of(HIT_ENTITY_SOUND), BulletHitSoundResolver.resolve(definition, HitType.ENTITY));
        assertEquals(Optional.of(HIT_BLOCK_SOUND), BulletHitSoundResolver.resolve(definition, HitType.BLOCK));
        assertEquals(Optional.of(HIT_PIERCE_SOUND), BulletHitSoundResolver.resolve(definition, HitType.PIERCE));
    }

    @Test
    void resolveReturnsEmptyWhenSoundsMapIsEmpty() {
        GunDefinition definition = definitionWithSounds(Map.of());
        assertEquals(Optional.empty(), BulletHitSoundResolver.resolve(definition, HitType.ENTITY));
        assertEquals(Optional.empty(), BulletHitSoundResolver.resolve(definition, HitType.BLOCK));
        assertEquals(Optional.empty(), BulletHitSoundResolver.resolve(definition, HitType.PIERCE));
    }
}
