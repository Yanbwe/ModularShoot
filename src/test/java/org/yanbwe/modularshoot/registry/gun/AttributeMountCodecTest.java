package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttributeMountCodecTest {

    private static AttributeMount decode(String value) {
        return AttributeMount.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString("\"" + value + "\""))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void parsesItem() {
        assertEquals(AttributeMount.ITEM, decode("item"));
    }

    @Test
    void parsesPlayer() {
        assertEquals(AttributeMount.PLAYER, decode("player"));
    }

    @Test
    void rejectsUnknownValue() {
        var result = AttributeMount.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString("\"chest\""));
        assertTrue(result.error().isPresent(), "unknown enum value should yield a decode error");
    }

    @Test
    void roundtripsBothValues() {
        for (AttributeMount mount : AttributeMount.values()) {
            JsonElement encoded = AttributeMount.CODEC.encodeStart(JsonOps.INSTANCE, mount)
                    .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
            AttributeMount decoded = AttributeMount.CODEC.decode(JsonOps.INSTANCE, encoded)
                    .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                    .getFirst();
            assertEquals(mount, decoded);
        }
    }

    @Test
    void gunDefaultsToItemWhenOmitted() {
        GunDefinition gun = GunDefinition.CODEC.decode(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"texture\":\"m:tex\"}"))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
        assertEquals(AttributeMount.ITEM, gun.attributeMount());
    }

    @Test
    void gunParsesExplicitPlayer() {
        GunDefinition gun = GunDefinition.CODEC.decode(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"texture\":\"m:tex\",\"attribute_mount\":\"player\"}"))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
        assertEquals(AttributeMount.PLAYER, gun.attributeMount());
    }

    @Test
    void gunRejectsInvalidMountValue() {
        var result = GunDefinition.CODEC.decode(JsonOps.INSTANCE,
                JsonParser.parseString("{\"texture\":\"m:tex\",\"attribute_mount\":\"chest\"}"));
        assertTrue(result.error().isPresent(), "invalid attribute_mount should fail decode");
    }
}