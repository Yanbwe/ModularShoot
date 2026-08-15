package org.yanbwe.modularshoot.datapack;

import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.datapack.ReloadBehaviorHandler;
import org.yanbwe.modularshoot.network.GunSyncService;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginModifier;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for 阶段 5 / 任务 5.2 — Reload 在线玩家刷新缓存.
 *
 * <p>Before the optimisation, {@code ReloadBehaviorHandler.refreshGunsInInventory}
 * recomputed {@link AttributeModifierService#computeAllModifiers} for every gun
 * stack, and each such call re-iterated the whole {@code attribute_meta} registry
 * (plus the plugins registry). Two online players holding two copies of the same
 * gun therefore caused the full table to be traversed twice. These tests pin
 * the behaviours introduced by task 5.2 plus the code-review fixes:</p>
 * <ol>
 *   <li><b>Full table computed once per gun config per registry instance.</b>
 *       {@code computeAllModifiers} caches the result keyed by
 *       {@code (attribute_meta 实例, GunDefinition, gunId, modifierVersion,
 *       有序 pluginId 列表)} and weak-keyed by the {@code plugins}
 *       {@link Registry} instance (reusing the {@link org.yanbwe.modularshoot.registry.RegistryKeyedCache}
 *       pattern), so a second refresh of an identical gun config hits the
 *       cache instead of re-iterating the table. The key does <em>not</em>
 *       contain the full {@code PluginInstance} (whose per-gun
 *       {@code instanceUuid}s would fragment the cache across otherwise
 *       identical guns). Because the key includes {@code modifierVersion}, a
 *       plugin install/uninstall/lock (which increments the version)
 *       recomputes.</li>
 *   <li><b>Reload never serves stale results.</b> A {@code /reload} swaps in
 *       brand-new registry instances, which are cache misses; the cached value
 *       from the old instance is never returned.</li>
 *   <li><b>Merged inventory-refresh path.</b> Both the reload path
 *       ({@link ReloadBehaviorHandler}) and the login path
 *       ({@link org.yanbwe.modularshoot.network.GunSyncService}) now share a
 *       single implementation,
 *       {@link AttributeModifierService#refreshGunModifiersInInventory}, which
 *       refreshes every gun stack in the main inventory and offhand and
 *       silently skips non-gun stacks — so the two refresh paths behave
 *       identically (合并后行为一致).</li>
 * </ol>
 *
 * <p>Registry traversal is observed through {@link CountingRegistry}, a thin
 * {@link MappedRegistry} subclass that counts {@code entrySet()} invocations
 * (the "full table" scan in {@code AttributeModifierService.addBaseModifiers}),
 * with no mocking. Same JUnit bridge as the other datapack/plugin tests:
 * FML shim + vanilla bootstrap + a reflection-bound {@code GUN_DATA} holder for
 * headless {@link ItemStack} reads.</p>
 */
class ReloadInventoryRefreshTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:test_gun");
    private static final ResourceLocation HIT_DAMAGE = ResourceLocation.parse("modularshoot:hit_damage");
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.parse("minecraft:generic.attack_damage");
    private static final ResourceLocation PLUGIN_ID = ResourceLocation.parse("modularshoot:test_plugin");

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to the other datapack/attribute tests' probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        bindGunDataHolder();
    }

    /** Binds the {@code GUN_DATA} DeferredHolder to a direct holder for headless stack reads. */
    private static void bindGunDataHolder() {
        try {
            Field holderField = net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
        } catch (Exception e) {
            throw new IllegalStateException("GUN_DATA DeferredHolder bind failed", e);
        }
    }

    // ──────────────── 1. Same gun config does not re-iterate the full table ────────────────

    /**
     * Two stacks carrying the same gun (same {@code gunId}, same installed
     * plugin list, same {@code modifierVersion}) refreshed under one registry
     * instance must cause the {@code attribute_meta} table to be traversed
     * exactly once — the second refresh is a cache hit and returns the same
     * cached {@link ItemAttributeModifiers} instance.
     */
    @Test
    void sameGunConfigAcrossStacksComputesFullTableOnlyOnce() {
        CountingRegistry<AttributeMeta> metas = new CountingRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        RegistryAccess access = access(metas, gunDef());

        PluginInstance plugin = new PluginInstance(PLUGIN_ID, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
        ItemStack stackA = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(plugin), 0, new CompoundTag()));
        ItemStack stackB = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(plugin), 0, new CompoundTag()));

        AttributeModifierService.refreshModifiers(stackA, access);
        int scansAfterFirst = metas.entrySetCalls();
        assertEquals(1, scansAfterFirst, "first refresh must traverse the attribute_meta table once");

        AttributeModifierService.refreshModifiers(stackB, access);

        assertEquals(scansAfterFirst, metas.entrySetCalls(),
                "refreshing the same gun config again must hit the cache, not re-iterate the full table");
        // Both stacks' refreshed components are the same cached instance.
        assertSame(currentModifiers(stackA), currentModifiers(stackB),
                "identical gun config must share the cached ItemAttributeModifiers instance");
        // Behaviour unchanged: base (5.0) + plugin (1.0) == 2 modifiers.
        assertModifierCount(stackA, 2);
    }

    /**
     * 审查 High-1：真实游戏里每把枪安装插件时都会生成独立的 {@code instanceUuid}，
     * 因此缓存键绝不能包含完整 {@link PluginInstance}（否则跨枪/跨玩家几乎永远 miss）。
     * 这里两把"插件配置相同但 instanceUuid 不同"的枪共享同一个 cache key，必须命中
     * 缓存：全表只遍历一次、两个组件的缓存在内存中共享、且结果仍正确（base + 插件）。
     */
    @Test
    void sameGunWithDifferentPluginInstanceUuidsSharesCache() {
        CountingRegistry<AttributeMeta> metas = new CountingRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        RegistryAccess access = access(metas, gunDef());

        // 两把相同枪、相同插件列表，但 instanceUuid 完全不同（如真实游戏逐把生成）。
        PluginInstance pluginA = new PluginInstance(PLUGIN_ID, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
        PluginInstance pluginB = new PluginInstance(PLUGIN_ID, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
        ItemStack stackA = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(pluginA), 0, new CompoundTag()));
        ItemStack stackB = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(pluginB), 0, new CompoundTag()));

        AttributeModifierService.refreshModifiers(stackA, access);
        int scansAfterFirst = metas.entrySetCalls();
        AttributeModifierService.refreshModifiers(stackB, access);

        assertEquals(scansAfterFirst, metas.entrySetCalls(),
                "不同的 instanceUuid 不得让缓存碎片化：全表只遍历一次");
        assertSame(currentModifiers(stackA), currentModifiers(stackB),
                "插件配置相同（仅 instanceUuid 不同）的枪必须共享同一份缓存修饰符");
        // 结果正确性：两把枪都应是 base(5.0) + 插件(1.0) == 2 条修饰符。
        assertModifierCount(stackA, 2);
        assertModifierCount(stackB, 2);
        // 修饰符 id 已改为稳定（pluginId + occurrence index），不再携带实例 uuid，
        // 因此共享的组件对两把枪都适用（若仍含实例标识，这里至少可断言结果正确）。
    }

    /**
     * {@code modifierVersion} increments on plugin install/uninstall/lock, so
     * two stacks with identical plugins but different versions must not share a
     * cached result — the cache key includes the version.
     */
    @Test
    void modifierVersionChangeInvalidatesModifierCache() {
        CountingRegistry<AttributeMeta> metas = new CountingRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        RegistryAccess access = access(metas, gunDef());

        PluginInstance plugin = new PluginInstance(PLUGIN_ID, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
        List<PluginInstance> installed = List.of(plugin);
        ItemStack v0 = gunStack(new GunData(GUN_ID, UUID.randomUUID(), installed, 0, new CompoundTag()));
        ItemStack v1 = gunStack(new GunData(GUN_ID, UUID.randomUUID(), installed, 1, new CompoundTag()));

        AttributeModifierService.refreshModifiers(v0, access);
        int scansAfterFirst = metas.entrySetCalls();
        AttributeModifierService.refreshModifiers(v1, access);

        assertEquals(scansAfterFirst + 1, metas.entrySetCalls(),
                "modifierVersion 改变（安装/卸载/锁定）后必须重新遍历 attribute_meta 表");
        assertEquals(currentModifiers(v0), currentModifiers(v1),
                "版本不同但插件列表相同时内容一致");
    }

    // ──────────────── 2. Reload (new registry instance) recomputes, never stale ────────────────

    /**
     * A {@code /reload} swaps in a brand-new {@code attribute_meta} registry
     * instance with an updated default value. The same gun must be recomputed
     * against the new instance (cache miss), never served the old instance's
     * cached result. The gun here declares no {@code hit_damage} stat, so the
     * base value is driven purely by the meta default (5.0 &rarr; 10.0).
     */
    @Test
    void reloadWithNewRegistryInstanceRecomputesAndNeverServesStaleResult() {
        // Old registry: hit_damage default 5.0
        CountingRegistry<AttributeMeta> oldMetas = new CountingRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(oldMetas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        RegistryAccess oldAccess = access(oldMetas, gunDefWithoutStats());

        // New registry (/reload: new instance): default changed to 10.0
        CountingRegistry<AttributeMeta> newMetas = new CountingRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(newMetas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 10.0));
        RegistryAccess newAccess = access(newMetas, gunDefWithoutStats());

        ItemStack stack = gunStack(new GunData(GUN_ID, UUID.randomUUID(),
                List.of(), 0, new CompoundTag()));

        AttributeModifierService.refreshModifiers(stack, oldAccess);
        ItemAttributeModifiers before = currentModifiers(stack);

        // Reload: recompute against the new registry instance.
        AttributeModifierService.refreshModifiers(stack, newAccess);
        ItemAttributeModifiers after = currentModifiers(stack);

        assertEquals(1, oldMetas.entrySetCalls(), "old registry traversed once");
        assertEquals(1, newMetas.entrySetCalls(), "new registry traversed exactly once on reload");
        assertNotSame(before, after, "reload must not serve the old registry's cached result");
        assertValue(after, 10.0, "reload 后必须用新属性元数据默认值 10.0");
    }

    // ──────────────── 3. Merged inventory-refresh path ────────────────

    /**
     * The single shared inventory-refresh implementation (used by both the
     * reload path and the login path) must refresh every gun stack in the main
     * inventory and the offhand slot, silently skip non-gun stacks, and return
     * the number of refreshed guns — one consistent behaviour for both refresh
     * paths (合并后行为一致).
     */
    @Test
    void sharedInventoryRefreshRefreshesEveryGunAndSkipsNonGuns() {
        MappedRegistry<AttributeMeta> metas = new MappedRegistry<>(
                ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        Registry.register(metas, HIT_DAMAGE, AttributeMeta.of(ATTACK_DAMAGE, 5.0));
        RegistryAccess access = access(metas, gunDef());

        ItemStack gun1 = gunStack(new GunData(GUN_ID, UUID.randomUUID(), List.of(), 0, new CompoundTag()));
        ItemStack gun2 = gunStack(new GunData(GUN_ID, UUID.randomUUID(), List.of(), 0, new CompoundTag()));
        ItemStack offhandGun = gunStack(new GunData(GUN_ID, UUID.randomUUID(), List.of(), 0, new CompoundTag()));
        ItemStack nonGun = new ItemStack(Items.STICK);
        List<ItemStack> inventory = new ArrayList<>(List.of(gun1, nonGun, gun2));

        int count = AttributeModifierService.refreshGunModifiersInInventory(
                inventory, offhandGun, access);

        assertEquals(3, count,
                "both in-inventory guns plus the offhand gun are refreshed; non-gun stacks are skipped");
        assertModifierCount(gun1, 1);
        assertModifierCount(gun2, 1);
        assertModifierCount(offhandGun, 1);
    }

    /**
     * 审查 Low：两个调用点必须真的委托共享方法
     * {@link AttributeModifierService#refreshGunModifiersInInventory}，而不是各维护
     * 一份扫描拷贝 —— reload 路径（{@link ReloadBehaviorHandler}）与 login 路径
     * （{@link GunSyncService}）都要引用它。无头 JUnit 无法构造真实
     * {@code ServerPlayer}，故本测试采用结构级断言：读取两个类的 class 文件，确认其
     * 常量池都引用了共享方法名 {@code refreshGunModifiersInInventory}（若某调用点停止
     * 委托共享方法而改为自行拷贝实现，该引用会消失，本测试即失败）。
     */
    @Test
    void reloadAndLoginCallersDelegateToSharedRefreshMethod() {
        String methodName = "refreshGunModifiersInInventory";
        assertTrue(classFileContainsAscii(ReloadBehaviorHandler.class, methodName),
                "ReloadBehaviorHandler 的 reload 路径必须委托共享刷新方法 " + methodName);
        assertTrue(classFileContainsAscii(GunSyncService.class, methodName),
                "GunSyncService 的 login 路径必须委托共享刷新方法 " + methodName);
    }

    // ──────────────── Test infrastructure ────────────────

    private static GunDefinition gunDef() {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(HIT_DAMAGE, 5.0),   // declared base: 5.0 (beats the meta default)
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Gun declaring no stats, so the {@code attribute_meta} default drives the base value. */
    private static GunDefinition gunDefWithoutStats() {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static PluginDefinition pluginDef() {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("modularshoot:textures/plugin/icon.png"),
                TextureScaleMode.AUTO,
                List.of(new PluginModifier(ATTACK_DAMAGE.toString(),
                        PluginModifier.Operation.ADD, 1.0)),
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

    /**
     * Full registry view: guns + plugins + the given attribute_meta registry,
     * with the gun registered under {@link #GUN_ID}.
     */
    private static RegistryAccess access(Registry<AttributeMeta> metas, GunDefinition gun) {
        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, GUN_ID, gun);
        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, PLUGIN_ID, pluginDef());
        return new RegistryAccess.ImmutableRegistryAccess(List.of(guns, plugins, metas));
    }

    private static ItemStack gunStack(GunData gunData) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), gunData);
        return stack;
    }

    private static ItemAttributeModifiers currentModifiers(ItemStack stack) {
        return stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    /** Asserts the stack's modifier component has exactly {@code expected} entries. */
    private static void assertModifierCount(ItemStack stack, int expected) {
        assertEquals(expected, currentModifiers(stack).modifiers().size(),
                "stack " + stack + " must carry " + expected + " modifier(s)");
    }

    /** Sums the {@code ADD_VALUE} amounts in an {@link ItemAttributeModifiers}. */
    private static void assertValue(ItemAttributeModifiers modifiers, double expected, String message) {
        double total = modifiers.modifiers().stream()
                .mapToDouble(m -> m.modifier().amount())
                .sum();
        assertEquals(expected, total, message);
    }

    /**
     * Returns whether a compiled class's constant pool references the given
     * ASCII method name. Method names are stored verbatim as
     * {@code CONSTANT_Utf8} strings in the class file, so a byte-substring scan
     * over the class file is a robust, dependency-free structural check that
     * the class references (and thus can delegate to) the named method.
     */
    private static boolean classFileContainsAscii(Class<?> clazz, String ascii) {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("class file not found: " + resource);
            }
            byte[] classBytes = in.readAllBytes();
            byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i <= classBytes.length - needle.length; i++) {
                boolean match = true;
                for (int j = 0; j < needle.length; j++) {
                    if (classBytes[i + j] != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read class bytes: " + clazz.getName(), e);
        }
    }

    /**
     * A {@link MappedRegistry} that counts how many times {@code entrySet()} is
     * invoked, so the full-table scan of {@code attribute_meta} is observable
     * without mocking.
     *
     * @param <T> the registry value type
     */
    private static final class CountingRegistry<T> extends MappedRegistry<T> {
        private int entrySetCalls;

        CountingRegistry(ResourceKey<? extends Registry<T>> key, Lifecycle lifecycle) {
            super(key, lifecycle);
        }

        @Override
        public Set<Map.Entry<ResourceKey<T>, T>> entrySet() {
            entrySetCalls++;
            return super.entrySet();
        }

        int entrySetCalls() {
            return entrySetCalls;
        }
    }
}
