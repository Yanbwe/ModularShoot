package org.yanbwe.modularshoot.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import org.yanbwe.modularshoot.registry.gun.Modifier;

/**
 * Immutable definition of a boolean trait entry in the
 * {@code modularshoot:traits} datapack registry.
 *
 * <p>The registry key (the trait id, e.g. {@code examplemod:ramping_damage})
 * is supplied by the registry itself and is therefore <strong>not</strong> a
 * field of this record. Callers obtain it from the registry holder/key.</p>
 *
 * <p>JSON keys (see 设计文档 §自定义特性数据包JSON格式, lines 2232-2255):
 * <ul>
 *   <li>{@code default_value} — required, the boolean default value for the
 *       trait when a gun definition does not explicitly set it.</li>
 *   <li>{@code description} — optional human-readable description text;
 *       defaults to {@code ""}.</li>
 *   <li>{@code name} — optional display name; absent means the caller falls
 *       back to the trait id path.</li>
 *   <li>{@code color} — optional hex colour code (e.g. {@code "#FF4444"}) for
 *       the trait name display; absent means the default colour is used.</li>
 *   <li>{@code brief} — optional one-line short description; absent means no
 *       brief tooltip line is shown.</li>
 *   <li>{@code force_show} — optional flag; when {@code true} the trait is
 *       shown in the tooltip even if its value equals the default. Defaults
 *       to {@code false}.</li>
*   <li>{@code priority} — optional display priority; higher values appear
*       earlier in the tooltip. Defaults to {@code 0}.</li>
*   <li>{@code visual_modifiers} — optional list of visual modifiers that
*       stack into a bullet's composed style whenever this trait is active
*       on the firing gun (设计规格 §3 Trait.visualModifiers). Default empty;
*       uses {@link Modifier#LIST_CODEC} so a single unrecognised
*       {@code "type"} decodes to an {@link
*       org.yanbwe.modularshoot.registry.gun.UnsupportedModifier} sentinel
*       rather than failing the whole list (spec §5).</li>
* </ul>
*
* <p>Runtime behaviour (hook callbacks) is not stored in this record. Traits
* attach runtime logic through
* {@link org.yanbwe.modularshoot.trait.TraitHookRegistry} keyed by the trait
* id. This record only carries the static, hot-reloadable definition data
* (设计文档 §特性运行时钩子).</p>
*
* @param defaultValue    required boolean default value
* @param description     human-readable description text; empty when absent
* @param name            optional display name; empty when the caller should
*                        fall back to the trait id path
* @param color           optional hex colour code for the name display; empty
*                        when the default colour is used
* @param brief           optional one-line short description; empty when no
*                        brief tooltip line is shown
* @param forceShow       whether to show the trait even when its value equals
*                        the default; defaults to {@code false}
* @param priority        display priority; higher values appear earlier;
*                        defaults to {@code 0}
* @param visualModifiers stacking visual modifiers contributed by this trait
*                        when active; empty when none. The compact
*                        constructor substitutes empty for {@code null} and
*                        copies the list into an immutable snapshot.
* @see ModularShootRegistries#TRAITS_KEY
* @see org.yanbwe.modularshoot.trait.TraitHookRegistry
*/
public record Trait(
        boolean defaultValue,
        String description,
        Optional<String> name,
        Optional<String> color,
        Optional<String> brief,
        boolean forceShow,
        int priority,
        List<Modifier> visualModifiers
) {
    /**
     * Compact constructor: substitute an empty list for a {@code null}
     * {@code visualModifiers} and copy the list into an immutable snapshot
     * so callers cannot mutate the record's internal state after
     * construction.
     */
    public Trait {
        visualModifiers = visualModifiers == null ? List.of() : List.copyOf(visualModifiers);
    }

    public static final Codec<Trait> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.BOOL.fieldOf("default_value").forGetter(Trait::defaultValue),
                    Codec.STRING.optionalFieldOf("description", "").forGetter(Trait::description),
                    Codec.STRING.optionalFieldOf("name").forGetter(Trait::name),
                    Codec.STRING.optionalFieldOf("color").forGetter(Trait::color),
                    Codec.STRING.optionalFieldOf("brief").forGetter(Trait::brief),
                    Codec.BOOL.optionalFieldOf("force_show", false).forGetter(Trait::forceShow),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(Trait::priority),
                    Modifier.LIST_CODEC.optionalFieldOf("visual_modifiers", List.of())
                            .forGetter(Trait::visualModifiers)
            ).apply(instance, Trait::new)
    );

    /**
     * Convenience factory for creating a {@link Trait} with only the required
     * field. Optional fields are filled with their defaults (empty
     * description, no name/colour/brief, forceShow {@code false}, priority
     * {@code 0}).
     *
     * @param defaultValue the boolean default value for the trait
     * @return a new immutable {@link Trait} instance
     */
    public static Trait of(boolean defaultValue) {
        return new Trait(
                defaultValue,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                0,
                List.of());
    }
}
