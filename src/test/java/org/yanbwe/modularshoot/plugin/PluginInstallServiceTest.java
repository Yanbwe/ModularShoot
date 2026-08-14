package org.yanbwe.modularshoot.plugin;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.neoforge.common.BooleanAttribute;
import net.neoforged.neoforge.common.PercentageAttribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Install-pipeline conduction tests for the preferred-type hint
 * (设计草案 框架改进-插件安装优先种类-方案 §五): the hint must end up as the
 * persisted {@code installedTypeId} (and {@code null} must conduct through
 * unchanged). The full {@link PluginInstallService#installPlugin} pipeline is
 * exercised — matching, selection, exclusive group, validators, pre-install
 * event, write phase — against hand-built {@code modularshoot:guns} /
 * {@code modularshoot:plugins} / {@code modularshoot:plugin_types} stub
 * registries.
 *
 * <h2>JUnit environment bridge</h2>
 * <p>The probe-verified bridge of
 * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest} (vanilla
 * bootstrap + {@code GameData.unfreezeData()} + DeferredHolder reflection
 * bind) is required to construct {@link ItemStack}s with the framework data
 * components. The install pipeline additionally needs a {@link Player}
 * (random source, {@code level()}), so a minimal {@link Player} subclass over
 * a minimal {@link Level} subclass is used — both are real (non-mock)
 * objects, following the project's no-mock convention.</p>
 */
class PluginInstallServiceTest {

    private static final ResourceLocation COMBAT = ResourceLocation.parse("modularshoot:combat");
    private static final ResourceLocation BARREL = ResourceLocation.parse("modularshoot:barrel");
    private static final ResourceLocation ACCESSORY = ResourceLocation.parse("modularshoot:accessory");

    // ---- Environment bootstrap (probe-verified recipe, identical to
    // ModularShootAPIItemBindingTest) --------------------------------------

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
        // NeoForge registers neoforge:fluid_type (with the minecraft:empty
        // default) during mod loading, which never happens in JUnit. Entity's
        // constructor resolves NeoForgeMod.EMPTY_TYPE.value() against
        // BuiltInRegistries.REGISTRY, so the registry must be provided here.
        registerFluidTypeRegistry();
        registerNeoForgeAttributes();
    }

    /** Guards the one-time attribute registration. */
    private static boolean neoforgeAttributesRegistered = false;

    /**
     * NeoForge registers {@code neoforge:swim_speed} /
     * {@code neoforge:nametag_distance} / {@code neoforge:creative_flight}
     * into {@code minecraft:attribute} during mod loading, which never
     * happens in JUnit. {@code LivingEntity.createLivingAttributes} (patched)
     * and {@code DefaultAttributes.<clinit>} resolve these holders eagerly, so
     * they must be provided here.
     */
    private static void registerNeoForgeAttributes() {
        if (neoforgeAttributesRegistered) {
            return;
        }
        Registry<Attribute> attributes = BuiltInRegistries.ATTRIBUTE;
        Registry.register(attributes,
                ResourceLocation.fromNamespaceAndPath("neoforge", "swim_speed"),
                new PercentageAttribute("neoforge.swim_speed", 1.0D, 0.0D, 1024.0D));
        Registry.register(attributes,
                ResourceLocation.fromNamespaceAndPath("neoforge", "nametag_distance"),
                new RangedAttribute("neoforge.name_tag_distance", 64.0D, 0.0D, 64.0D));
        Registry.register(attributes,
                ResourceLocation.fromNamespaceAndPath("neoforge", "creative_flight"),
                new BooleanAttribute("neoforge.creative_flight", false));
        neoforgeAttributesRegistered = true;
    }

    /** Guards the one-time registration into the shared BuiltInRegistries.REGISTRY. */
    private static boolean fluidTypeRegistryRegistered = false;

    private static void registerFluidTypeRegistry() {
        if (fluidTypeRegistryRegistered) {
            return;
        }
        // GameData.unfreezeData() 只解冻 REGISTRY 内的注册表，REGISTRY 自身
        // 仍是冻结的；向其中注册 fluid_type 前需单独解冻（unfreeze 为
        // protected，测试侧用反射调用）。
        try {
            java.lang.reflect.Method unfreeze =
                    BaseMappedRegistry.class.getDeclaredMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.REGISTRY);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        MappedRegistry<FluidType> fluidTypes = new MappedRegistry<>(
                NeoForgeRegistries.Keys.FLUID_TYPES, Lifecycle.stable());
        Registry.register(fluidTypes, ResourceLocation.withDefaultNamespace("empty"),
                new FluidType(FluidType.Properties.create().descriptionId("block.minecraft.air")));
        // 注册进 BuiltInRegistries.REGISTRY，使 DeferredHolder 能绑定
        // （unchecked 转换与 BuiltInRegistries 内部注册方式一致）。
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Registry<Registry<?>> registryOfRegistries = (Registry) BuiltInRegistries.REGISTRY;
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ResourceKey<Registry<?>> fluidTypesKey = (ResourceKey) fluidTypes.key();
        Registry.register(registryOfRegistries, fluidTypesKey, fluidTypes);
        fluidTypeRegistryRegistered = true;
    }

    /** Binds {@code GUN_DATA}/{@code PLUGIN_DATA} DeferredHolders to direct
     *  {@link Holder}s so their {@code get()} resolves in JUnit (see the
     *  reference test's probe verdict 4). */
    private static boolean bindComponentHolders() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            DataComponentType<PluginData> pluginType = new DataComponentType.Builder<PluginData>()
                    .persistent(PluginData.CODEC).build();
            holderField.set(ModularShootDataComponents.PLUGIN_DATA, Holder.direct(pluginType));
            return true;
        } catch (Exception e) {
            System.err.println("[PluginInstallServiceTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    /** Probe verdict: {@code true} when the component channel is usable in JUnit. */
    private static final boolean COMPONENT_HOLDERS_BOUND = bindComponentHolders();

    // ---- definition/instance builders (same shapes as
    // EffectiveSlotServiceTest / PluginUninstallServiceGateTest) ------------

    /** Builds a {@link GunDefinition} carrying only the given slot configuration. */
    private static GunDefinition gun(Map<ResourceLocation, Integer> slots) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("m:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                slots,
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Builds a {@link PluginDefinition} carrying the given tags (and no adds_slots). */
    private static PluginDefinition plugin(List<ResourceLocation> tags) {
        return new PluginDefinition(
                tags,
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Builds a {@link PluginTypeDefinition} carrying the given tags and priority. */
    private static PluginTypeDefinition type(List<ResourceLocation> tags, int priority) {
        return new PluginTypeDefinition(tags, priority, Optional.empty(), Optional.empty());
    }

    /** Registers a unique item id and returns the registered item. */
    private static Item newUniqueItem(String discriminator) {
        ResourceLocation id = ResourceLocation.parse(
                "minecraft:test_" + discriminator + "_" + UUID.randomUUID());
        Item item = new Item(new Item.Properties());
        Registry.register(BuiltInRegistries.ITEM, id, item);
        return item;
    }

    // ---- stub Level / Player (real subclasses, no mocks) ------------------

    /**
     * A bare {@link DimensionType} — only {@code coordinateScale} is read by
     * the {@link Level} constructor (world border), so a minimal record works.
     * Dimension types are a dynamic registry in 1.21.1, unavailable headless;
     * {@link Holder#direct} wraps the bare record without a registry.
     */
    private static DimensionType bareDimensionType() {
        return new DimensionType(
                OptionalLong.empty(),
                false,
                true,
                false,
                false,
                1.0,
                false,
                false,
                -64,
                384,
                384,
                BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld_effects"),
                0.0F,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
    }

    /**
     * Builds a {@code minecraft:damage_type} registry carrying every vanilla
     * damage type key. Damage types are a datapack-driven (dynamic) registry
     * in 1.21.1 — absent from the bootstrapped test environment — but the
     * {@link Level} constructor resolves all vanilla types eagerly via
     * {@link DamageSources}. Registering each {@link DamageTypes} key with a
     * minimal {@link DamageType} satisfies the lookup.
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

    /** Minimal {@link WritableLevelData} stub — none of the values are read. */
    private static final class StubLevelData implements WritableLevelData {
        @Override
        public BlockPos getSpawnPos() {
            return BlockPos.ZERO;
        }

        @Override
        public float getSpawnAngle() {
            return 0.0F;
        }

        @Override
        public long getGameTime() {
            return 0L;
        }

        @Override
        public long getDayTime() {
            return 0L;
        }

        @Override
        public boolean isThundering() {
            return false;
        }

        @Override
        public boolean isRaining() {
            return false;
        }

        @Override
        public void setRaining(boolean raining) {
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public GameRules getGameRules() {
            return new GameRules();
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.PEACEFUL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }

        @Override
        public void setSpawn(BlockPos pos, float angle) {
        }
    }

    /**
     * Minimal {@link Level} subclass: only the abstract members are stubbed
     * (none are read by the install pipeline). The registry access carries the
     * bootstrapped vanilla registries plus the framework stub registries, so
     * both {@code DamageSources} (vanilla) and the install pipeline
     * (framework) resolve.
     */
    private static final class StubLevel extends Level {
        StubLevel(RegistryAccess access) {
            super(new StubLevelData(), Level.OVERWORLD, access,
                    Holder.direct(bareDimensionType()),
                    () -> new ActiveProfiler(() -> 0L, () -> 0, false),
                    false, false, 0L, 0);
        }

        @Override
        public ChunkSource getChunkSource() {
            return null;
        }

        @Override
        public void setDayTimeFraction(float dayTimeFraction) {
        }

        @Override
        public float getDayTimeFraction() {
            return 0.0F;
        }

        @Override
        public float getDayTimePerTick() {
            return 0.0F;
        }

        @Override
        public void gameEvent(Holder<GameEvent> holder, Vec3 vec3, GameEvent.Context context) {
        }

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            return null;
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            return null;
        }

        @Override
        public void levelEvent(@Nullable Player player, int event, BlockPos pos, int data) {
        }

        @Override
        public List<? extends Player> players() {
            return List.of();
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return FeatureFlags.DEFAULT_FLAGS;
        }

        @Override
        public float getShade(Direction direction, boolean bl) {
            return 1.0F;
        }

        @Override
        public void setDayTimePerTick(float dayTimePerTick) {
        }

        @Override
        public void sendBlockUpdated(BlockPos pos, BlockState from, BlockState to, int flags) {
        }

        @Override
        public void playSeededSound(@Nullable Player player, double x, double y, double z,
                Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {
        }

        @Override
        public void playSeededSound(@Nullable Player player, Entity entity,
                Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {
        }

        @Override
        public String gatherChunkSourceStats() {
            return "";
        }

        @Override
        public Entity getEntity(int id) {
            return null;
        }

        @Override
        public TickRateManager tickRateManager() {
            return null;
        }

        @Override
        public MapItemSavedData getMapData(MapId mapId) {
            return null;
        }

        @Override
        public void setMapData(MapId mapId, MapItemSavedData data) {
        }

        @Override
        public MapId getFreeMapId() {
            return null;
        }

        @Override
        public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
        }

        @Override
        public Scoreboard getScoreboard() {
            return null;
        }

        @Override
        public RecipeManager getRecipeManager() {
            return null;
        }

        @Override
        protected LevelEntityGetter<Entity> getEntities() {
            return null;
        }

        @Override
        public PotionBrewing potionBrewing() {
            return null;
        }
    }

    /** Minimal {@link Player} subclass: only the two abstract members. */
    private static final class StubPlayer extends Player {
        StubPlayer(Level level) {
            super(level, BlockPos.ZERO, 0.0F, new GameProfile(UUID.randomUUID(), "stub_player"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }

    // ---- environment ------------------------------------------------------

    /**
     * Per-test environment: fresh stub registries (guns / plugins /
     * plugin_types) plus the bootstrapped vanilla registries, and a stub
     * player whose level exposes the same registry view — so both the service
     * overload and the facade overload resolve everything.
     *
     * <p>Scenario: plugin tags {@code [combat, barrel]} match both types; gun
     * slots {@code {combat: 2, barrel: 1}}; combat priority 10 vs barrel 5, so
     * auto-selection deterministically picks combat.</p>
     */
    private record Env(RegistryAccess access, Player player,
                       ResourceLocation gunId, ResourceLocation pluginId) {
    }

    private static Env env() {
        ResourceLocation gunId = ResourceLocation.parse(
                "modularshoot:test_hint_gun_" + UUID.randomUUID());
        ResourceLocation pluginId = ResourceLocation.parse(
                "modularshoot:test_hint_plugin_" + UUID.randomUUID());

        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, gunId, gun(Map.of(COMBAT, 2, BARREL, 1)));
        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, pluginId, plugin(List.of(COMBAT, BARREL)));
        MappedRegistry<PluginTypeDefinition> types = new MappedRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, Lifecycle.stable());
        Registry.register(types, COMBAT, type(List.of(COMBAT), 10));
        Registry.register(types, BARREL, type(List.of(BARREL), 5));

        List<Registry<?>> all = new ArrayList<>();
        BuiltInRegistries.REGISTRY.stream().forEach(all::add);
        all.add(damageTypes());
        all.add(guns);
        all.add(plugins);
        all.add(types);
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(all);

        Player player = new StubPlayer(new StubLevel(access));
        return new Env(access, player, gunId, pluginId);
    }

    /** Builds a gun stack carrying {@code gun_data} for the given gun id. */
    private static ItemStack gunStack(ResourceLocation gunId) {
        ItemStack stack = new ItemStack(newUniqueItem("gun"));
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                GunData.create(gunId, UUID.randomUUID()));
        return stack;
    }

    /** Builds a 2-count plugin stack carrying {@code plugin_data}. */
    private static ItemStack pluginStack(ResourceLocation pluginId) {
        ItemStack stack = new ItemStack(newUniqueItem("plugin"));
        stack.set(ModularShootDataComponents.PLUGIN_DATA.get(), new PluginData(pluginId));
        stack.setCount(2);
        return stack;
    }

    /** Reads the persisted {@code installedTypeId} from a successful result. */
    private static ResourceLocation installedType(PluginInstallService.InstallResult result) {
        return result.installedGun()
                .get(ModularShootDataComponents.GUN_DATA.get())
                .installedPlugins().get(0).installedTypeId();
    }

    // ---- conduction: hint → installedTypeId ------------------------------

    @Test
    void hintSelectsPreferredTypeWhenBothMatch() {
        Env env = env();
        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                gunStack(env.gunId()), pluginStack(env.pluginId()),
                env.player(), BARREL, env.access());
        assertTrue(result.success(), "install with a matching hint must succeed");
        assertEquals(BARREL, installedType(result),
                "the hinted category is persisted as installedTypeId");
    }

    @Test
    void nullHintConductsToAutoSelection() {
        Env env = env();
        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                gunStack(env.gunId()), pluginStack(env.pluginId()),
                env.player(), null, env.access());
        assertTrue(result.success(), "install without a hint must succeed");
        assertEquals(COMBAT, installedType(result),
                "null hint keeps the original auto-selection (higher priority wins)");
    }

    @Test
    void hintNotMatchingFallsBackToAutoSelection() {
        Env env = env();
        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                gunStack(env.gunId()), pluginStack(env.pluginId()),
                env.player(), ACCESSORY, env.access());
        assertTrue(result.success(), "a non-matching hint falls back and still installs");
        assertEquals(COMBAT, installedType(result),
                "an unmatched hint falls back to the three-level auto-selection");
    }

    @Test
    void hintWithFullSlotFallsBackToOtherType() {
        // 枪已装 1 个 barrel 插件（barrel 容量 1 已满）→ barrel 不在候选里 →
        // hint 落空 → 回退装 combat。
        Env env = env();
        ItemStack gun = gunStack(env.gunId());
        GunData oldData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        PluginInstance occupied = new PluginInstance(
                ResourceLocation.parse("modularshoot:test_hint_occupant_" + UUID.randomUUID()),
                UUID.randomUUID(), BARREL, false);
        gun.set(ModularShootDataComponents.GUN_DATA.get(),
                new GunData(oldData.gunId(), oldData.gunInstanceUuid(),
                        List.of(occupied), oldData.modifierVersion(), oldData.state()));

        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                gun, pluginStack(env.pluginId()), env.player(), BARREL, env.access());
        assertTrue(result.success(), "a full hinted slot falls back to a matching category");
        List<PluginInstance> installed = result.installedGun()
                .get(ModularShootDataComponents.GUN_DATA.get()).installedPlugins();
        assertEquals(2, installed.size(), "the occupant and the new plugin are both installed");
        assertEquals(BARREL, installed.get(0).installedTypeId(),
                "the pre-installed occupant keeps its slot type");
        assertEquals(COMBAT, installed.get(1).installedTypeId(),
                "a full hinted category is not a candidate, fallback wins");
    }

    @Test
    void facadeOverloadForwardsHint() {
        // 面板使用的入口：门面重载内部从 player.level() 取注册表视图并透传 hint。
        Env env = env();
        PluginInstallService.InstallResult result = ModularShootAPI.installPlugin(
                gunStack(env.gunId()), pluginStack(env.pluginId()),
                env.player(), BARREL);
        assertTrue(result.success(), "facade overload with a hint must succeed");
        assertEquals(BARREL, installedType(result),
                "the hint reaches the service through the facade");
    }

    // ---- side effects -----------------------------------------------------

    @Test
    void inputsAreNotMutatedAndPluginIsConsumed() {
        Env env = env();
        ItemStack gun = gunStack(env.gunId());
        ItemStack plugin = pluginStack(env.pluginId());

        PluginInstallService.InstallResult result = PluginInstallService.installPlugin(
                gun, plugin, env.player(), BARREL, env.access());

        assertTrue(result.success(), "install must succeed");
        assertEquals(0, gun.get(ModularShootDataComponents.GUN_DATA.get())
                        .installedPlugins().size(),
                "the original gun stack is never mutated");
        assertEquals(2, plugin.getCount(),
                "the original plugin stack is never mutated");
        assertEquals(1, result.consumedPlugin().getCount(),
                "the returned copy is shrunk by exactly one");
    }
}
