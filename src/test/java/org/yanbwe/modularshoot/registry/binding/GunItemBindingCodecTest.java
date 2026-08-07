package org.yanbwe.modularshoot.registry.binding;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Codec tests for {@link GunItemBinding}: a required two-field binding of an
 * item id and a gun id (设计规格 物品绑定系统 §3.1). Covers happy-path
 * parsing, missing-field failures, and a full encode/decode round trip.
 */
class GunItemBindingCodecTest {

    private static GunItemBinding parse(String json) {
        return GunItemBinding.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void parsesItemAndGun() {
        GunItemBinding binding = parse("{\"item\": \"minecraft:diamond_sword\", \"gun\": \"mypack:sword_rifle\"}");
        assertEquals(ResourceLocation.parse("minecraft:diamond_sword"), binding.itemId());
        assertEquals(ResourceLocation.parse("mypack:sword_rifle"), binding.gunId());
    }

    @Test
    void missingGunFailsDecode() {
        assertThrows(AssertionError.class,
                () -> parse("{\"item\": \"minecraft:diamond_sword\"}"),
                "gun is a required field and must not default");
    }

    @Test
    void missingItemFailsDecode() {
        assertThrows(AssertionError.class,
                () -> parse("{\"gun\": \"mypack:sword_rifle\"}"),
                "item is a required field and must not default");
    }

    @Test
    void roundTripPreservesFields() {
        GunItemBinding binding = parse("{\"item\": \"minecraft:diamond_sword\", \"gun\": \"mypack:sword_rifle\"}");
        JsonElement encoded = GunItemBinding.CODEC.encodeStart(JsonOps.INSTANCE, binding)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
        GunItemBinding roundTripped = parse(encoded.toString());
        assertEquals(binding.itemId(), roundTripped.itemId());
        assertEquals(binding.gunId(), roundTripped.gunId());
    }
}
