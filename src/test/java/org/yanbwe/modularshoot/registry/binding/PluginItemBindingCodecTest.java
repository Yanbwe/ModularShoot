package org.yanbwe.modularshoot.registry.binding;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Codec tests for {@link PluginItemBinding}: a required two-field binding of
 * an item id and a plugin id (设计规格 物品绑定系统 §3.1). Covers happy-path
 * parsing, missing-field failures, and a full encode/decode round trip.
 */
class PluginItemBindingCodecTest {

    private static PluginItemBinding parse(String json) {
        return PluginItemBinding.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void parsesItemAndPlugin() {
        PluginItemBinding binding = parse("{\"item\": \"minecraft:stick\", \"plugin\": \"mypack:light_plugin\"}");
        assertEquals(ResourceLocation.parse("minecraft:stick"), binding.itemId());
        assertEquals(ResourceLocation.parse("mypack:light_plugin"), binding.pluginId());
    }

    @Test
    void missingPluginFailsDecode() {
        assertThrows(AssertionError.class,
                () -> parse("{\"item\": \"minecraft:stick\"}"),
                "plugin is a required field and must not default");
    }

    @Test
    void missingItemFailsDecode() {
        assertThrows(AssertionError.class,
                () -> parse("{\"plugin\": \"mypack:light_plugin\"}"),
                "item is a required field and must not default");
    }

    @Test
    void roundTripPreservesFields() {
        PluginItemBinding binding = parse("{\"item\": \"minecraft:stick\", \"plugin\": \"mypack:light_plugin\"}");
        JsonElement encoded = PluginItemBinding.CODEC.encodeStart(JsonOps.INSTANCE, binding)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));
        PluginItemBinding roundTripped = parse(encoded.toString());
        assertEquals(binding.itemId(), roundTripped.itemId());
        assertEquals(binding.pluginId(), roundTripped.pluginId());
    }
}
