package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the refactored {@link BulletStyle#CODEC}:
 * base + modifiers (scale / tint / attach_layer) dispatch.
 */
class BulletStyleCodecTest {

    private static JsonObject encode(BulletStyle style) {
        return BulletStyle.CODEC.encodeStart(JsonOps.INSTANCE, style)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg))
                .getAsJsonObject();
    }

    private static BulletStyle decode(JsonObject json) {
        return BulletStyle.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static BulletStyle roundtrip(BulletStyle style) {
        return decode(encode(style));
    }

    @Test
    void emptyStyleEncodesOptionalAbsent() {
        BulletStyle style = new BulletStyle(Optional.empty(), List.of());
        BulletStyle decoded = roundtrip(style);
        assertTrue(decoded.base().isEmpty());
        assertTrue(decoded.modifiers().isEmpty());
    }

    @Test
    void baseBillboardRoundtrip() {
        BulletStyle.Base base = new BulletStyle.Base(
                BulletStyle.RenderMode.BILLBOARD,
                ResourceLocation.fromNamespaceAndPath("m", "textures/bullet/default.png"),
                null);
        BulletStyle style = new BulletStyle(Optional.of(base), List.of());
        BulletStyle decoded = roundtrip(style);
        assertTrue(decoded.base().isPresent());
        assertEquals(BulletStyle.RenderMode.BILLBOARD, decoded.base().get().renderMode());
        assertEquals(ResourceLocation.fromNamespaceAndPath("m", "textures/bullet/default.png"),
                decoded.base().get().texture());
        assertNull(decoded.base().get().model());
    }

    @Test
    void scaleModifierRoundtrip() {
        ScaleModifier scale = new ScaleModifier(1.5f);
        BulletStyle style = new BulletStyle(Optional.empty(), List.of(scale));
        BulletStyle decoded = roundtrip(style);
        assertEquals(1, decoded.modifiers().size());
        assertInstanceOf(ScaleModifier.class, decoded.modifiers().get(0));
        assertEquals(1.5f, ((ScaleModifier) decoded.modifiers().get(0)).value(), 1e-6);
    }

    @Test
    void tintModifierRoundtrip() {
        TintModifier tint = new TintModifier(new Vector4f(1.0f, 0.3f, 0.2f, 1.0f));
        BulletStyle style = new BulletStyle(Optional.empty(), List.of(tint));
        BulletStyle decoded = roundtrip(style);
        assertInstanceOf(TintModifier.class, decoded.modifiers().get(0));
        Vector4f c = ((TintModifier) decoded.modifiers().get(0)).color();
        assertEquals(1.0f, c.x, 1e-6);
        assertEquals(0.3f, c.y, 1e-6);
        assertEquals(0.2f, c.z, 1e-6);
        assertEquals(1.0f, c.w, 1e-6);
    }

    @Test
    void tintAlphaDefaultsToOneWhenOmitted() {
        // [r,g,b] decodes with alpha = 1.0 default (spec §3.2).
        JsonObject json = new JsonObject();
        JsonObject tintObj = new JsonObject();
        tintObj.addProperty("type", "tint");
        tintObj.add("color", new com.google.gson.JsonArray());
        tintObj.get("color").getAsJsonArray().add(1.0);
        tintObj.get("color").getAsJsonArray().add(0.3);
        tintObj.get("color").getAsJsonArray().add(0.2);
        json.add("modifiers", new com.google.gson.JsonArray());
        json.get("modifiers").getAsJsonArray().add(tintObj);
        BulletStyle decoded = decode(json);
        assertInstanceOf(TintModifier.class, decoded.modifiers().get(0));
        assertEquals(1.0f, ((TintModifier) decoded.modifiers().get(0)).color().w, 1e-6);
    }

    @Test
    void attachLayerModifierRoundtrip() {
        AttachLayerModifier layer = new AttachLayerModifier(
                BulletStyle.RenderMode.BILLBOARD,
                ResourceLocation.fromNamespaceAndPath("m", "textures/bullet/flame.png"),
                null,
                false, true,
                new Vec3(0.0, 0.1, -0.3),
                0.8f,
                new Vector4f(1.0f, 0.5f, 0.2f, 1.0f));
        BulletStyle style = new BulletStyle(Optional.empty(), List.of(layer));
        BulletStyle decoded = roundtrip(style);
        assertInstanceOf(AttachLayerModifier.class, decoded.modifiers().get(0));
        AttachLayerModifier d = (AttachLayerModifier) decoded.modifiers().get(0);
        assertFalse(d.followRotation());
        assertTrue(d.followScale());
        assertEquals(0.8f, d.scale(), 1e-6);
    }

    @Test
    void unsupportedModifierDecodesToSentinelAndDoesNotFailList() {
        // spec §5: 看到未知 type → 该条 skip + WARN，其余继续组合。
        // Codec 层实现：未知 type 解码为 UnsupportedModifier 哨兵，整个 list 不失败。
        JsonObject json = new JsonObject();
        JsonObject unknown = new JsonObject();
        unknown.addProperty("type", "definitely_not_a_real_type");
        JsonObject valid = new JsonObject();
        valid.addProperty("type", "scale");
        valid.addProperty("value", 2.0);
        json.add("modifiers", new com.google.gson.JsonArray());
        json.get("modifiers").getAsJsonArray().add(unknown);
        json.get("modifiers").getAsJsonArray().add(valid);
        BulletStyle decoded = decode(json);
        assertEquals(2, decoded.modifiers().size());
        assertInstanceOf(UnsupportedModifier.class, decoded.modifiers().get(0));
        assertEquals("definitely_not_a_real_type",
                ((UnsupportedModifier) decoded.modifiers().get(0)).unknownType());
        assertInstanceOf(ScaleModifier.class, decoded.modifiers().get(1));
    }

    @Test
    void modifiersFieldDefaultsToEmptyWhenAbsent() {
        JsonObject json = new JsonObject();
        BulletStyle decoded = decode(json);
        assertTrue(decoded.base().isEmpty());
        assertTrue(decoded.modifiers().isEmpty());
    }
}