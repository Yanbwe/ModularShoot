package org.yanbwe.modularshoot.client.tooltip;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.BooleanAttribute;
import net.neoforged.neoforge.common.PercentageAttribute;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.state.ModularShootAttachmentTypes;
import org.yanbwe.modularshoot.state.PlayerState;
import org.yanbwe.modularshoot.state.PlayerStateData;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDisplay;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateValueType;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试（审查 High）：修改 per-player 状态后，{@link StateTooltipBuilder#buildStateBar}
 * 必须重建而不是命中旧缓存结果。
 *
 * <p>真实性：走真实的 {@code buildStateBar} → {@code TooltipCacheKey.of(...)} 路径，
 * 其中 mutable-data 版本由 {@link TooltipVersion#mutableDataVersion} 派生，并把
 * PLAYER_STATE attachment 的 identity-version 纳入 key。第一次构建缓存后，通过
 * {@link PlayerState#setInt} 真实地写入新值（每次写都会安装一个新的
 * {@link PlayerStateData} 实例 → 版本 bump），第二次调用必须得到不同（更新）的
 * 状态栏。</p>
 *
 * <p>为在无头 JUnit 中构造真实 {@link Player} 与 attachment，复用
 * {@code PluginInstallServiceTest} 的 probe-verified 启动桥（FML shim +
 * bootstrap + unfreeze + 注册 fluid_type / neoforge attributes + DeferredHolder
 * 反射绑定），并额外把 {@code PLAYER_STATE} attachment 注册进
 * {@code NeoForgeRegistries.ATTACHMENT_TYPES}（NeoForge 的
 * {@code AttachmentHolder.validateAttachmentType} 在 dev 模式下要求已注册）。</p>
 */
class StateTooltipBuildTest {

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        net.neoforged.neoforge.registries.GameData.unfreezeData();
        registerFluidTypeRegistry();
        registerNeoForgeAttributes();
        bindComponentHolders();
        bindAndRegisterPlayerStateAttachment();
    }

    private static ResourceLocation stateId() {
        return ResourceLocation.parse(
                "modularshoot:test_player_state_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @BeforeEach
    @AfterEach
    void resetStore() {
        org.yanbwe.modularshoot.client.ClientGunDataStore.getInstance().clear();
    }

    @Test
    void buildStateBarRebuildsAfterPlayerStateChange() {
        Env env = env();
        ItemStack gun = gunStack();

        // 让 StubPlayer 主手持枪（携带 gun_data 组件），触发 per-player 状态可见。
        env.player().getInventory().setItem(
                env.player().getInventory().selected,
                gun);

        // 首次构建 → 值 5。
        List<Component> first = StateTooltipBuilder.buildStateBar(gun, env.player(), env.access());
        assertTrue(containsValue(first, "5"), "首次构建应显示默认预写值 5: " + render(first));

        // 改变 per-player 状态（真实写路径 → 新 PlayerStateData 实例 → 版本 bump）。
        PlayerState.of(env.player()).setInt(env.stateId(), 9);

        // 第二次构建：缓存键因 per-player 状态版本变化而不同，必须重建而非命中旧值。
        List<Component> second = StateTooltipBuilder.buildStateBar(gun, env.player(), env.access());
        assertNotEquals(first, second, "per-player 状态变化后 tooltip 必须重建（不得返回陈旧结果）");
        assertTrue(containsValue(second, "9"), "重建后的状态栏应显示新值 9: " + render(second));
        assertTrue(!containsValue(second, "5"), "重建后的状态栏不应再显示旧值 5: " + render(second));
    }

    @Test
    void buildStateBarDropsPerPlayerRowsWhenMainHandSwitched() {
        // 审查 High：缓存键必须纳入「被悬停枪是否为主手/是否持枪」布尔项。
        // 主手持枪构建后清除主手再构建 → per-player 行必须被移除；
        // 反向恢复主手后必须能再次显示（不再命中旧缓存返回陈旧结果）。
        Env env = env();
        ItemStack gun = gunStack();

        // 主手持枪 → per-player 状态可见。
        env.player().getInventory().setItem(
                env.player().getInventory().selected, gun);
        List<Component> held = StateTooltipBuilder.buildStateBar(gun, env.player(), env.access());
        assertTrue(containsValue(held, "5"), "主手持枪时 per-player 行应显示: " + render(held));

        // 清除主手（空手）→ 栈身份/注册表/修饰键/PLAYER_STATE 内容版本均不变，
        // 但主手布尔项变化必须使缓存键失效 → per-player 行被移除。
        env.player().getInventory().setItem(
                env.player().getInventory().selected, ItemStack.EMPTY);
        List<Component> cleared = StateTooltipBuilder.buildStateBar(gun, env.player(), env.access());
        assertTrue(!containsValue(cleared, "5"),
                "清除主手后 per-player 行必须被移除（不得命中旧缓存）: " + render(cleared));

        // 反向恢复主手 → 再次显示 per-player 行。
        env.player().getInventory().setItem(
                env.player().getInventory().selected, gun);
        List<Component> restored = StateTooltipBuilder.buildStateBar(gun, env.player(), env.access());
        assertTrue(containsValue(restored, "5"),
                "恢复主手后 per-player 行必须再次显示（缓存键已区分主手布尔项）: " + render(restored));
    }

    @Test
    void differingPlayersProduceDifferingDataVersions() {
        // 审查 Medium：per-player 版本计数不携带玩家身份，不同玩家计数相同会碰撞。
        // 这里两个不同 UUID 的玩家（各自首个 per-player 版本计数均为 1）必须产生
        // 不同的 dataVersion（通过折叠观看者 UUID 区分）。
        Env env = env();
        ItemStack gun = gunStack();
        RegistryAccess access = env.access();

        Player playerA = new StubPlayer(new StubLevel(access));
        Player playerB = new StubPlayer(new StubLevel(access));

        int vA = TooltipVersion.mutableDataVersion(gun, playerA);
        int vB = TooltipVersion.mutableDataVersion(gun, playerB);

        assertNotEquals(vA, vB,
                "不同玩家（UUID 不同）即使 per-player 版本计数相同也必须产生不同 dataVersion");
    }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    private static String render(List<Component> lines) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(lines.get(i).getString()).append('"');
        }
        return sb.append(']').toString();
    }

    /** Whether any rendered line contains the exact formatted value. */
    private static boolean containsValue(List<Component> lines, String value) {
        for (Component line : lines) {
            if (line.getString().endsWith(": " + value)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    private record Env(RegistryAccess access, Player player, ResourceLocation stateId) {
    }

    private static Env env() {
        ResourceLocation stateId = stateId();
        MappedRegistry<StateDefinition> states = new MappedRegistry<>(
                ModularShootRegistries.STATES_KEY, Lifecycle.stable());
        Registry.register(states, stateId, new StateDefinition(
                StateDomain.PLAYER, StateValueType.INT, 5,
                StateDisplay.of("test_state", Optional.empty()), List.of()));

        List<Registry<?>> all = new ArrayList<>();
        BuiltInRegistries.REGISTRY.stream().forEach(all::add);
        all.add(damageTypes());
        all.add(states);
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(all);

        Player player = new StubPlayer(new StubLevel(access));
        return new Env(access, player, stateId);
    }

    private static ItemStack gunStack() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                GunData.create(ResourceLocation.parse("modularshoot:test_gun"), UUID.randomUUID()));
        return stack;
    }

    // ------------------------------------------------------------------
    // NeoForge environment bridge (mirrors PluginInstallServiceTest)
    // ------------------------------------------------------------------

    private static boolean neoforgeAttributesRegistered = false;

    private static void registerNeoForgeAttributes() {
        if (neoforgeAttributesRegistered) {
            return;
        }
        Registry<Attribute> attributes = BuiltInRegistries.ATTRIBUTE;
        // 若同 JVM 内其它测试已注册过这些 neoforge attribute，跳过避免重复注册冲突。
        if (attributes.getOptional(ResourceLocation.fromNamespaceAndPath("neoforge", "swim_speed")).isPresent()) {
            neoforgeAttributesRegistered = true;
            return;
        }
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

    private static boolean fluidTypeRegistryRegistered = false;

    private static void registerFluidTypeRegistry() {
        if (fluidTypeRegistryRegistered) {
            return;
        }
        // 若同一 JVM 内其它测试（如 PluginInstallServiceTest）已注册过
        // neoforge:fluid_type，跳过，避免 "Duplicate key" 冲突。
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ResourceKey<Registry<?>> fluidTypesKey =
                (ResourceKey) NeoForgeRegistries.Keys.FLUID_TYPES;
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Registry<Registry<?>> registryOfRegistries = (Registry) BuiltInRegistries.REGISTRY;
        if (registryOfRegistries.getOptional(fluidTypesKey).isPresent()) {
            fluidTypeRegistryRegistered = true;
            return;
        }
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
        Registry.register(registryOfRegistries, fluidTypesKey, fluidTypes);
        fluidTypeRegistryRegistered = true;
    }

    private static boolean componentHoldersBound = false;

    private static void bindComponentHolders() {
        if (componentHoldersBound) {
            return;
        }
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            componentHoldersBound = true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean playerStateAttachmentBound = false;

    private static void bindAndRegisterPlayerStateAttachment() {
        if (playerStateAttachmentBound) {
            return;
        }
        // 构建并注册 attachment type：NeoForge 的 validateAttachmentType 在 dev
        // 模式要求 AttachmentType 已注册进 ATTACHMENT_TYPES registry。
        AttachmentType<PlayerStateData> type = AttachmentType.<PlayerStateData>builder(
                        () -> new PlayerStateData())
                .serialize(PlayerStateData.CODEC)
                .copyOnDeath()
                .build();
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("modularshoot", "player_state");
        Registry.register(NeoForgeRegistries.ATTACHMENT_TYPES, key, type);
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            holderField.set(ModularShootAttachmentTypes.PLAYER_STATE, Holder.direct(type));
            playerStateAttachmentBound = true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    // ------------------------------------------------------------------
    // Stub level / player (real subclasses, no mocks)
    // ------------------------------------------------------------------

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
        public void setDayTimePerTick(float dayTimePerTick) {
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
                net.minecraft.tags.BlockTags.INFINIBURN_OVERWORLD,
                ResourceLocation.withDefaultNamespace("overworld_effects"),
                0.0F,
                new DimensionType.MonsterSettings(
                        false, false, net.minecraft.util.valueproviders.ConstantInt.of(0), 0));
    }
}
