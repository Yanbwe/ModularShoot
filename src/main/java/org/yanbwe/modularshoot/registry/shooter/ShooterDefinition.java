package org.yanbwe.modularshoot.registry.shooter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.AttributeResolver;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.BulletSnapshotBuilder;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

/**
 * Immutable definition of an entry in the {@code modularshoot:shooters}
 * registry — the "independent firing configuration" carrier for
 * non-player-sourced bullets (设计文档 §独立发射).
 *
 * <p>A shooter definition is a self-contained template for building a
 * {@link BulletSnapshot} without a firing gun: a numeric {@code stats}
 * template, optional boolean {@code traits}, an optional independent visual
 * style ({@code bullet_style}), an optional shoot sound and an optional list
 * of {@code attribute_binds} that overlay live entity attribute values on top
 * of the template at snapshot time (turret, trap, boss attack, scripted
 * scenario, ...).</p>
 *
 * <p>The registry key (the shooter id, e.g.
 * {@code modularshoot:bone_shooter}) is supplied by the registry itself and
 * is therefore <strong>not</strong> a field of this record.</p>
 *
 * <p>JSON shape (see 设计文档 §独立发射配置 JSON):
 * <ul>
 *   <li>{@code stats} — <b>required, non-empty</b>. Logical attribute id →
 *       base value; the template the snapshot starts from.</li>
 *   <li>{@code traits} — optional trait id → flag, defaults to an empty
 *       map.</li>
 *   <li>{@code bullet_style} — optional independent-firing visual style
 *       (same structure as a gun's {@code bullet_style}); absent → the
 *       framework default bullet appearance applies at compose time.</li>
 *   <li>{@code shoot_sound} — optional {@link ShootSound} (id, volume,
 *       pitch) played at the firing position; absent → silent.</li>
 *   <li>{@code attribute_binds} — optional list of logical attribute ids.
 *       At snapshot time each bound id is read from the source entity (if
 *       any) via {@link AttributeResolver}; a present value overrides the
 *       template entry (introducing new keys is allowed, matching the
 *       {@code extra_values} convention), an empty read keeps the template
 *       value.</li>
 * </ul>
 *
 * @param stats          required base stat template (logical attribute id →
 *                       value); must not be empty
 * @param traits         optional inherent trait flags; empty when none
 * @param bulletStyle    optional independent-firing visual style; empty when
 *                       the default appearance should be used
 * @param shootSound     optional shoot sound; empty when the shooter is
 *                       silent
 * @param attributeBinds optional logical attribute ids overlaid from the
 *                       source entity at snapshot time; empty when none
 */
public record ShooterDefinition(
        Map<ResourceLocation, Double> stats,
        Map<ResourceLocation, Boolean> traits,
        Optional<BulletStyle> bulletStyle,
        Optional<ShootSound> shootSound,
        List<ResourceLocation> attributeBinds
) {

    /**
     * {@code stats} codec with non-empty validation: an empty template
     * (or empty JSON object) is a datapack-author error and fails the whole
     * entry load with {@code "stats must not be empty"}.
     */
    private static final Codec<Map<ResourceLocation, Double>> NON_EMPTY_STATS =
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE).flatXmap(
                    ShooterDefinition::validateNonEmptyStats,
                    ShooterDefinition::validateNonEmptyStats);

    public static final Codec<ShooterDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    NON_EMPTY_STATS.fieldOf("stats").forGetter(ShooterDefinition::stats),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.BOOL)
                            .optionalFieldOf("traits", Map.of())
                            .forGetter(ShooterDefinition::traits),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style")
                            .forGetter(ShooterDefinition::bulletStyle),
                    ShootSound.CODEC.optionalFieldOf("shoot_sound")
                            .forGetter(ShooterDefinition::shootSound),
                    ResourceLocation.CODEC.listOf()
                            .optionalFieldOf("attribute_binds", List.of())
                            .forGetter(ShooterDefinition::attributeBinds)
            ).apply(instance, ShooterDefinition::new)
    );

    private static DataResult<Map<ResourceLocation, Double>> validateNonEmptyStats(
            Map<ResourceLocation, Double> map) {
        return map.isEmpty()
                ? DataResult.error(() -> "stats must not be empty")
                : DataResult.success(map);
    }

    /**
     * Builds the frozen {@link BulletSnapshot} for this shooter definition.
     *
     * <p>Starts from the template {@code stats} and overlays each
     * {@code attribute_binds} id by reading the source entity's live value
     * (when a non-null source is given and its entity type passes the
     * {@code attribute_meta} whitelist); an empty read keeps the template
     * value. Per the independent-firing snapshot convention the result
     * carries {@code gunId}/{@code gunInstanceUuid}/{@code shooter} all
     * {@code null}; the damage type is left {@code null} for the firing
     * boundary to patch in ({@code ModularShootAPI#fireBullet}).</p>
     *
     * @param source         the entity to read bound attribute values from,
     *                       or {@code null} to keep the template untouched
     * @param registryAccess the runtime registry view (for
     *                       {@code attribute_meta} resolution)
     * @return a new frozen snapshot ready for the independent-firing flow
     */
    public BulletSnapshot createSnapshot(
            @Nullable LivingEntity source, RegistryAccess registryAccess) {
        return buildSnapshot(stats, traits, bulletStyle.orElse(null), attributeBinds,
                id -> readBound(id, source, registryAccess), null);
    }

    /**
     * Plays this shooter's {@link ShootSound} at the given position.
     *
     * <p>Silently skips when no {@code shoot_sound} is declared. When the
     * sound event id is not registered in
     * {@link BuiltInRegistries#SOUND_EVENT} a {@code WARN} is logged and
     * playback is skipped (the same degradation contract as
     * {@code ShootingEngine#playShootSound}). The first argument to
     * {@code playSound} is {@code null} so every nearby entity hears the
     * shot. Uses {@link SoundSource#HOSTILE}: the shooter is a non-player
     * source (设计文档 §独立发射).</p>
     *
     * @param level    the level to play into; must not be {@code null}
     * @param position the firing position; must not be {@code null}
     */
    public void playShootSound(Level level, Vec3 position) {
        if (shootSound.isEmpty()) {
            return;
        }
        ShootSound sound = shootSound.get();
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(sound.id());
        if (event == null) {
            ModularShoot.LOGGER.warn(
                    "Shoot sound {} not found in SOUND_EVENT registry; skipping playback.",
                    sound.id());
            return;
        }
        level.playSound(null, position.x, position.y, position.z,
                event, SoundSource.HOSTILE, sound.volume(), sound.pitch());
    }

    /**
     * Pure-function core of {@link #createSnapshot}: merges the bind-reader
     * results onto the template stats and routes everything through
     * {@link BulletSnapshotBuilder}.
     *
     * <p>Semantics (package-private so the headless tests exercise it
     * without an entity or registry):</p>
     * <ul>
     *   <li>Start from a copy of the template {@code stats}; for each bind id
     *       call {@code bindReader}: a present value overwrites the template
     *       entry (introducing a new key is allowed, matching the
     *       {@code extra_values} convention), an empty value keeps the
     *       template entry.</li>
     *   <li>Feed the merged map through {@link BulletSnapshotBuilder#stat}
     *       entry by entry, the traits through {@link BulletSnapshotBuilder#trait},
     *       {@code style} through {@link BulletSnapshotBuilder#style} (when
     *       non-null) and {@code damageType} through
     *       {@link BulletSnapshotBuilder#damageType} (when non-null), then
     *       {@link BulletSnapshotBuilder#build()}.</li>
     * </ul>
     *
     * @param stats      the template stats map (copied, never mutated)
     * @param traits     the trait flags (copied by the builder)
     * @param style      the independent-firing visual style, or {@code null}
     * @param binds      the attribute bind ids, in declared order
     * @param bindReader reads the overlaid value for a bind id; empty keeps
     *                   the template value
     * @param damageType the damage type holder, or {@code null} (patched by
     *                   the firing boundary)
     * @return a new frozen snapshot
     */
    static BulletSnapshot buildSnapshot(
            Map<ResourceLocation, Double> stats,
            Map<ResourceLocation, Boolean> traits,
            @Nullable BulletStyle style,
            List<ResourceLocation> binds,
            Function<ResourceLocation, Optional<Double>> bindReader,
            @Nullable Holder<DamageType> damageType) {
        Map<ResourceLocation, Double> merged = new LinkedHashMap<>(stats);
        for (ResourceLocation bind : binds) {
            bindReader.apply(bind).ifPresent(value -> merged.put(bind, value));
        }
        BulletSnapshotBuilder builder = new BulletSnapshotBuilder();
        merged.forEach(builder::stat);
        traits.forEach(builder::trait);
        if (style != null) {
            builder.style(style);
        }
        if (damageType != null) {
            builder.damageType(damageType);
        }
        return builder.build();
    }

    /**
     * Reads the live value of a bound logical attribute from the source
     * entity, with full degradation: a {@code null} source, a missing
     * {@code attribute_meta} entry (with a {@code WARN}) or an entity type
     * outside the entry's whitelist all yield {@link Optional#empty()}, so
     * the template value is kept.
     *
     * @param id     the bound logical attribute id
     * @param source the entity to read from, or {@code null}
     * @param access the runtime registry view
     * @return the entity's final value, or empty when any link is missing
     */
    private static Optional<Double> readBound(
            ResourceLocation id, @Nullable LivingEntity source, RegistryAccess access) {
        if (source == null) {
            return Optional.empty();
        }
        AttributeMeta meta = AttributeResolver.metaFor(access, id);
        if (meta == null) {
            ModularShoot.LOGGER.warn(
                    "Shooter attribute_binds {} not registered; keeping template value", id);
            return Optional.empty();
        }
        if (!meta.allowsEntity(source.getType())) {
            return Optional.empty();
        }
        return Optional.of(AttributeResolver.readFinalValue(source, id, access));
    }

    /**
     * Shoot sound binding of a {@link ShooterDefinition}: a required sound
     * event id plus optional volume and pitch, both defaulting to
     * {@code 1.0f}.
     *
     * @param id     the registered sound event id; must not be {@code null}
     * @param volume the playback volume, default {@code 1.0f}
     * @param pitch  the playback pitch, default {@code 1.0f}
     */
    public record ShootSound(ResourceLocation id, float volume, float pitch) {

        public static final Codec<ShootSound> CODEC = RecordCodecBuilder.create(
                instance -> instance.group(
                        ResourceLocation.CODEC.fieldOf("id").forGetter(ShootSound::id),
                        Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(ShootSound::volume),
                        Codec.FLOAT.optionalFieldOf("pitch", 1.0f).forGetter(ShootSound::pitch)
                ).apply(instance, ShootSound::new)
        );
    }
}
