package org.yanbwe.modularshoot.bullet;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless unit tests for the {@link CreationCoordinator} network-marking
 * behaviour (阶段 6 / 任务 6.3 复审: Medium — CreationCoordinator 的网络标记行为无
 * 测试).
 *
 * <p>The coordinator is exercised with a real {@link BulletFactory} whose
 * {@link BulletFactory.Composer} is stubbed to record the {@code gunData} it is
 * handed (and to avoid a live registry), plus a counting
 * {@link CreationCoordinator.Marker} seam. This makes the short-life marking
 * side effect (D-03) observable and assertable exactly-once without touching
 * the {@code BulletSyncService} static created-this-tick state.</p>
 *
 * <ul>
 *   <li>{@link #fireOnServerMarksOncePerCreatedBullet} — creation on an
 *       authoritative (non-client) level marks the bullet exactly once.</li>
 *   <li>{@link #fireOnClientLevelSkipsMarking} — client-side creation performs
 *       no marking (server-only guard).</li>
 *   <li>{@link #passesSuppliedGunDataThrough} / {@link #forwardsNullGunDataForFallback}
 *       — the supplied {@code gunData} is passed through verbatim, and a
 *       {@code null} {@code gunData} is forwarded for the reverse-inventory
 *       back-track fallback (which degrades to {@code null} on an ownerless,
 *       gun-less snapshot).</li>
 *   <li>{@link #singleRegistrationSingleMarkNoDuplication} — firing twice yields
 *       one registration + one mark per bullet; every firing path funnels here
 *       and never marks twice.</li>
 * </ul>
 *
 * <p>Env bootstrap mirrors the probe-verified recipe used by
 * {@code PluginInstallServiceTest} / {@code StateTooltipBuildTest} so a real
 * (non-mock) {@link Level} can be constructed headlessly.</p>
 */
class CreationCoordinatorTest {

    static {
        // FML shim: FeatureFlags.<clinit> -> FeatureFlagLoader needs a
        // non-null LoadingModList; of() installs an empty instance.
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        // DataFixers.<clinit> requires a current game version.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        // Full vanilla registry bootstrap (also freezes vanilla registries via
        // the NeoForge vanillaSnapshot patch).
        net.minecraft.server.Bootstrap.bootStrap();
        // Reopen the registries for test-side registration.
        net.neoforged.neoforge.registries.GameData.unfreezeData();
    }

    private RegistryAccess access;

    @BeforeEach
    void setUp() {
        TraitHookRegistry.clear();
        List<Registry<?>> all = new ArrayList<>();
        BuiltInRegistries.REGISTRY.stream().forEach(all::add);
        all.add(damageTypes());
        access = new RegistryAccess.ImmutableRegistryAccess(all);
    }

    // ---- Env helpers ----------------------------------------------------

    private static BulletSnapshot emptySnapshot() {
        return new BulletSnapshot(
                new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>());
    }

    /**
     * A recording composer: captures the {@code gunData} each creation is handed
     * and counts invocations (one per {@code BulletFactory.createAndRegister}).
     * Creation never touches a live registry.
     */
    private static final class RecordingComposer implements BulletFactory.Composer {
        int calls;
        @Nullable GunData lastGunData;

        @Override
        public ComposedBulletStyle compose(
                @Nullable RegistryAccess ra,
                @Nullable BulletSnapshot snapshot,
                @Nullable GunData gunData) {
            calls++;
            lastGunData = gunData;
            return ComposedBulletStyle.DEFAULT;
        }
    }

    /** A counting marker seam observing the short-life marking side effect. */
    private static final class CountingMarker implements CreationCoordinator.Marker {
        int marks;

        @Override
        public void mark(Level level, BulletRecord bullet) {
            marks++;
        }
    }

    /** Builds a coordinator wired to the given recording composer + counting marker. */
    private static CreationCoordinator coordinator(
            RecordingComposer composer, CountingMarker marker) {
        return new CreationCoordinator(new BulletFactory(composer), marker);
    }

    // ---- Tests ----------------------------------------------------------

    @Test
    void fireOnServerMarksOncePerCreatedBullet() {
        RecordingComposer composer = new RecordingComposer();
        CountingMarker marker = new CountingMarker();
        CreationCoordinator c = coordinator(composer, marker);
        Level server = new StubLevel(access, false);

        BulletRecord bullet = c.fireBullet(
                server, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, null);

        assertNotNull(bullet);
        assertEquals(1, composer.calls, "creation must happen exactly once per fireBullet");
        assertEquals(1, marker.marks,
                "server-side markBulletCreated must be called exactly once after creation (D-03 exactly-once guarantee)");
    }

    @Test
    void fireOnClientLevelSkipsMarking() {
        RecordingComposer composer = new RecordingComposer();
        CountingMarker marker = new CountingMarker();
        CreationCoordinator c = coordinator(composer, marker);
        Level client = new StubLevel(access, true);

        c.fireBullet(client, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, null);

        assertEquals(1, composer.calls, "creation still happens on the client level");
        assertEquals(0, marker.marks,
                "client-side creation must NOT trigger the server-only network marking");
    }

    @Test
    void passesSuppliedGunDataThrough() {
        RecordingComposer composer = new RecordingComposer();
        CountingMarker marker = new CountingMarker();
        CreationCoordinator c = coordinator(composer, marker);
        Level server = new StubLevel(access, false);
        GunData supplied = GunData.create(
                ResourceLocation.parse("modularshoot:test_gun"), UUID.randomUUID());

        c.fireBullet(server, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, supplied);

        assertSame(supplied, composer.lastGunData,
                "the supplied gunData must be passed through to creation verbatim (no re-resolution)");
        assertEquals(1, marker.marks, "server-side creation still marks the bullet once");
    }

    @Test
    void forwardsNullGunDataForFallback() {
        RecordingComposer composer = new RecordingComposer();
        CountingMarker marker = new CountingMarker();
        CreationCoordinator c = coordinator(composer, marker);
        Level server = new StubLevel(access, false);

        // An ownerless / gun-less snapshot back-tracks to no gun: the composer
        // must observe the (degraded) null gunData supplied by the caller.
        c.fireBullet(server, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, null);

        assertNull(composer.lastGunData,
                "a null gunData must be forwarded for the back-track fallback, which degrades to null on a gun-less snapshot");
    }

    @Test
    void singleRegistrationSingleMarkNoDuplication() {
        RecordingComposer composer = new RecordingComposer();
        CountingMarker marker = new CountingMarker();
        CreationCoordinator c = coordinator(composer, marker);
        Level server = new StubLevel(access, false);
        BulletManager manager = BulletManager.get(server);

        BulletRecord a = c.fireBullet(server, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, null);
        BulletRecord b = c.fireBullet(server, new Vec3(0, 0, 0), new Vec3(1, 0, 0), emptySnapshot(), null, null);

        // Each bullet is registered exactly once into the per-dimension manager…
        assertSame(a, manager.getBulletById(a.getBulletId()));
        assertSame(b, manager.getBulletById(b.getBulletId()));
        assertNotEquals(a.getBulletId(), b.getBulletId());
        // …with one creation and one mark per registration (no duplicate marking).
        assertEquals(2, composer.calls);
        assertEquals(2, marker.marks);
    }

    // ---- Level stub (probe-verified recipe from PluginInstallServiceTest) --

    private static final class StubLevelData implements WritableLevelData {
        @Override public BlockPos getSpawnPos() { return BlockPos.ZERO; }
        @Override public float getSpawnAngle() { return 0.0F; }
        @Override public long getGameTime() { return 0L; }
        @Override public long getDayTime() { return 0L; }
        @Override public boolean isThundering() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public void setRaining(boolean raining) { }
        @Override public boolean isHardcore() { return false; }
        @Override public GameRules getGameRules() { return new GameRules(); }
        @Override public Difficulty getDifficulty() { return Difficulty.PEACEFUL; }
        @Override public boolean isDifficultyLocked() { return false; }
        @Override public void setSpawn(BlockPos pos, float angle) { }
    }

    private static final class StubLevel extends Level {
        StubLevel(RegistryAccess registryAccess, boolean isClientSide) {
            super(new StubLevelData(), Level.OVERWORLD, registryAccess,
                    Holder.direct(bareDimensionType()),
                    () -> new ActiveProfiler(() -> 0L, () -> 0, false),
                    isClientSide, false, 0L, 0);
        }

        @Override public ChunkSource getChunkSource() { return null; }
        @Override public void setDayTimeFraction(float dayTimeFraction) { }
        @Override public float getDayTimeFraction() { return 0.0F; }
        @Override public float getDayTimePerTick() { return 0.0F; }
        @Override public void gameEvent(Holder<GameEvent> holder, Vec3 vec3, GameEvent.Context context) { }
        @Override public LevelTickAccess<Block> getBlockTicks() { return null; }
        @Override public LevelTickAccess<Fluid> getFluidTicks() { return null; }
        @Override public void levelEvent(@Nullable Player player, int event, BlockPos pos, int data) { }
        @Override public List<? extends Player> players() { return List.of(); }
        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return null; }
        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
        @Override public float getShade(Direction direction, boolean bl) { return 1.0F; }
        @Override public void setDayTimePerTick(float dayTimePerTick) { }
        @Override public void sendBlockUpdated(BlockPos pos, BlockState from, BlockState to, int flags) { }
        @Override public void playSeededSound(@Nullable Player player, double x, double y, double z,
                Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { }
        @Override public void playSeededSound(@Nullable Player player, Entity entity,
                Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { }
        @Override public String gatherChunkSourceStats() { return ""; }
        @Override public Entity getEntity(int id) { return null; }
        @Override public TickRateManager tickRateManager() { return null; }
        @Override public MapItemSavedData getMapData(MapId mapId) { return null; }
        @Override public void setMapData(MapId mapId, MapItemSavedData data) { }
        @Override public MapId getFreeMapId() { return null; }
        @Override public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) { }
        @Override public Scoreboard getScoreboard() { return null; }
        @Override public RecipeManager getRecipeManager() { return null; }
        @Override protected LevelEntityGetter<Entity> getEntities() { return null; }
        @Override public PotionBrewing potionBrewing() { return null; }
    }

    /** A bare {@link DimensionType} — only {@code coordinateScale} is read by {@link Level}. */
    private static DimensionType bareDimensionType() {
        return new DimensionType(
                OptionalLong.empty(), false, true, false, false, 1.0, false, false,
                -64, 384, 384,
                BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld_effects"),
                0.0F,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
    }

    /**
     * Builds a {@code minecraft:damage_type} registry carrying every vanilla
     * damage type, required by {@link Level}'s constructor ({@code DamageSources}
     * resolves all types eagerly).
     */
    private static Registry<DamageType> damageTypes() {
        MappedRegistry<DamageType> registry = new MappedRegistry<>(
                Registries.DAMAGE_TYPE, Lifecycle.stable());
        for (Field field : DamageTypes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && ResourceKey.class.isAssignableFrom(field.getType())) {
                try {
                    ResourceKey<DamageType> key = (ResourceKey<DamageType>) field.get(null);
                    Registry.register(registry, key, new DamageType(
                            key.location().getPath(), DamageScaling.NEVER, 0.0F));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return registry;
    }
}
