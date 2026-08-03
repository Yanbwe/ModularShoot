package org.yanbwe.modularshoot.registry;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.gun.ScaleModifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the {@link Trait#CODEC} after the
 * {@code visualModifiers} field is appended (设计规格 §3 Trait.visualModifiers).
 * Guards backward compatibility (existing traits with no {@code visual_modifiers}
 * field decode to empty list) and round-trip of a {@link ScaleModifier} entry.
 */
class TraitCodecTest {

    private static Trait decode(JsonObject json) {
        return Trait.CODEC.decode(JsonOps.INSTANCE, json)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m))
                .getFirst();
    }

    private static JsonObject encode(Trait trait) {
        return Trait.CODEC.encodeStart(JsonOps.INSTANCE, trait)
                .getOrThrow(m -> new AssertionError("Encode failed: " + m))
                .getAsJsonObject();
    }

    @Test
    void traitWithoutVisualModifiersDefaultsToEmpty() {
        JsonObject json = new JsonObject();
        json.addProperty("default_value", false);
        Trait trait = decode(json);
        assertTrue(trait.visualModifiers().isEmpty());
    }

    @Test
    void existingTraitsUnaffectedByNewField() {
        // A minimal trait with only default_value round-trips identical
        Trait t = Trait.of(true);
        Trait decoded = decode(encode(t));
        assertTrue(decoded.defaultValue());
        assertTrue(decoded.visualModifiers().isEmpty());
    }

    @Test
    void visualModifiersRoundtrip() {
        Trait trait = new Trait(false, "desc",
                Optional.empty(), Optional.empty(), Optional.empty(),
                false, 0,
                List.of(new ScaleModifier(1.2f)));
        Trait decoded = decode(encode(trait));
        assertEquals(1, decoded.visualModifiers().size());
        assertInstanceOf(ScaleModifier.class, decoded.visualModifiers().get(0));
        assertEquals(1.2f, ((ScaleModifier) decoded.visualModifiers().get(0)).value(), 1e-6);
    }
}