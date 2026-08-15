package org.yanbwe.modularshoot.shooting;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;
import org.yanbwe.modularshoot.variant.VariantPoolService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hot-path single-resolution tests for the shooting pipeline (计划 §阶段 1 /
 * 任务 1.2).
 *
 * <p>{@link ShootingEngine#fire} itself needs a live {@link
 * net.minecraft.server.level.ServerPlayer}, which a plain JUnit process cannot
 * construct, so these tests drive the <em>exact production seams</em> the
 * engine delegates to ({@code TraitMergeService.computeTraits} and {@code
 * VariantPoolService.buildPool}) plus the {@link
 * AttributeResolver} resolution chain, and observe the number of dynamic
 * registry lookups with counting registries / a counting {@link
 * RegistryAccess}. Between them these seams cover every duplicate query the
 * engine removed:
 *
 * <ol>
 *   <li>the gun definition is resolved once by the engine and passed onward —
 *       the pre-resolved overload never touches the {@code guns} registry;</li>
 *   <li>each installed plugin definition is resolved exactly once while
 *       assembling the per-shot variant pool;</li>
 *   <li>the {@code attribute_meta} holder resolution for a
 *       {@code (RegistryAccess, logicalId)} pair is cached per registry
 *       instance (survives {@code /reload} via a new instance).</li>
 * </ol>
 *
 * <h2>JUnit environment bridge</h2>
 * <p>The same probe-verified bridge as {@code TraitMergeServiceTest} is
 * applied ({@code LoadingModList} shim + {@code SharedConstants.setVersion} +
 * {@code Bootstrap.bootStrap()}) so {@link ItemStack}/{@link Items} are usable;
 * the {@code GUN_DATA} DeferredHolder is bound to a direct {@link Holder} via
 * reflection so {@code GUN_DATA.get()} resolves. When the bind fails the
 * component-dependent cases degrade to skipped.</p>
 */
class ShootingEngineHotPathTest {

    private static final ResourceLocation GUN_ID = ResourceLocation.parse("modularshoot:hotpath_gun");
    private static final ResourceLocation PLUGIN_A = ResourceLocation.parse("modularshoot:hotpath_plugin_a");
    private static final ResourceLocation PLUGIN_B = ResourceLocation.parse("modularshoot:hotpath_plugin_b");
    private static final ResourceLocation VARIANT_A = ResourceLocation.parse("modularshoot:variant_a");
    private static final ResourceLocation VARIANT_B = ResourceLocation.parse("modularshoot:variant_b");
    private static final ResourceLocation TRAIT_AUTO_FIRE = ResourceLocation.parse("modularshoot:auto_fire");
    private static final ResourceLocation TRAIT_SILENT = ResourceLocation.parse("modularshoot:silent");
    private static final ResourceLocation LOGICAL_ID = ResourceLocation.parse("modularshoot:hotpath_attr");

    // ---- Environment bootstrap (probe-verified bridge, see class javadoc) -

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static boolean bindGunDataHolder() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            return true;
        } catch (Exception e) {
            System.err.println("[ShootingEngineHotPathTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    private static final boolean GUN_DATA_BOUND = bindGunDataHolder();

    // ---- Counting infrastructure ------------------------------------------

    /** A {@link RegistryAccess} that counts per-key {@link #registry} lookups. */
    private static final class CountingRegistryAccess implements RegistryAccess {
        private final RegistryAccess delegate;
        final AtomicInteger gunRegistryCalls = new AtomicInteger();
        final AtomicInteger pluginRegistryCalls = new AtomicInteger();

        CountingRegistryAccess(RegistryAccess delegate) {
            this.delegate = delegate;
        }

        @Override
        public <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key) {
            if (ModularShootRegistries.GUNS_KEY.equals(key)) {
                gunRegistryCalls.incrementAndGet();
            } else if (ModularShootRegistries.PLUGINS_KEY.equals(key)) {
                pluginRegistryCalls.incrementAndGet();
            }
            return delegate.registry(key);
        }

        @Override
        public Stream<RegistryAccess.RegistryEntry<?>> registries() {
            return delegate.registries();
        }
    }

    /** A {@link MappedRegistry} counting {@link #get} invocations. */
    private static final class CountingAttributeMetaRegistry extends MappedRegistry<AttributeMeta> {
        final AtomicInteger getCalls = new AtomicInteger();

        CountingAttributeMetaRegistry() {
            super(ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable());
        }

        @Override
        public AttributeMeta get(ResourceLocation id) {
            getCalls.incrementAndGet();
            return super.get(id);
        }
    }

    // ---- Definition builders ----------------------------------------------

    private static GunDefinition gunWithTraits(Map<ResourceLocation, Boolean> traits) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                traits,
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static GunDefinition gunWithNoVariants() {
        return gunWithTraits(Map.of());
    }

    private static PluginDefinition parsePlugin(String json) {
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    private static PluginInstance instance(ResourceLocation pluginId) {
        return new PluginInstance(pluginId, UUID.randomUUID(),
                ResourceLocation.parse("modularshoot:type"), false);
    }

    private static GunData gunDataWithPlugins(List<ResourceLocation> pluginIds) {
        return new GunData(GUN_ID, UUID.randomUUID(),
                pluginIds.stream().map(ShootingEngineHotPathTest::instance).toList(),
                0, new CompoundTag());
    }

    private static ItemStack gunStackWith(GunData gunData) {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(ModularShootDataComponents.GUN_DATA.get(), gunData);
        return stack;
    }

    // ---- Test 1: pre-resolved gun definition is not re-looked up ----------

    @Test
    void computeTraitsWithResolvedGunDefinitionSkipsGunRegistryLookup() {
        assumeTrue(GUN_DATA_BOUND, "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        // 枪械固有特性 auto_fire=true；插件 A 追加 silent=true 与 trait 合并。
        MappedRegistry<GunDefinition> gunRegistry = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(gunRegistry, GUN_ID, gunWithTraits(Map.of(TRAIT_AUTO_FIRE, true)));
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"traits\":{\"modularshoot:silent\":true}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(
                List.of(gunRegistry, pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        GunDefinition resolved = gunRegistry.getOptional(GUN_ID).orElseThrow();
        Map<ResourceLocation, Boolean> traits =
                org.yanbwe.modularshoot.plugin.TraitMergeService.computeTraits(
                        gunStackWith(gunDataWithPlugins(List.of(PLUGIN_A))), access, resolved);

        assertEquals(0, access.gunRegistryCalls.get(),
                "已解析的 GunDefinition 传入后不得再次查询 guns 注册表（重复解析被消除）");
        assertEquals(Boolean.TRUE, traits.get(TRAIT_AUTO_FIRE),
                "枪械固有特性仍合并入结果（行为不变）");
        assertEquals(Boolean.TRUE, traits.get(TRAIT_SILENT),
                "插件特性仍合并入结果（行为不变）");
    }

    @Test
    void computeTraitsWithoutPreResolvedDefinitionResolvesGunExactlyOnce() {
        assumeTrue(GUN_DATA_BOUND, "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        MappedRegistry<GunDefinition> gunRegistry = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(gunRegistry, GUN_ID, gunWithTraits(Map.of(TRAIT_AUTO_FIRE, true)));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(
                List.of(gunRegistry, pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        org.yanbwe.modularshoot.plugin.TraitMergeService.computeTraits(
                gunStackWith(gunDataWithPlugins(List.of())), access);

        assertEquals(1, access.gunRegistryCalls.get(),
                "未预解析的 2 参重载应恰好解析一次枪械定义");
    }

    // ---- Test 2: each plugin resolved once per pool build -----------------

    @Test
    void variantPoolResolvesEachInstalledPluginDefinitionExactlyOnce() {
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(pluginRegistry, PLUGIN_A,
                parsePlugin("{\"item_icon\":\"m:icon\",\"adds_variants\":{\"modularshoot:variant_a\":2.0}}"));
        Registry.register(pluginRegistry, PLUGIN_B,
                parsePlugin("{\"item_icon\":\"m:icon\",\"adds_variants\":{\"modularshoot:variant_b\":3.0}}"));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(pluginRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        GunData gunData = gunDataWithPlugins(List.of(PLUGIN_A, PLUGIN_B));
        // 一次池构建：每个已安装插件只解析一次 PluginDefinition。任务 1.3 已移除
        // 跨发缓存（buildPool 每次射击都重新组装），因此 plugins 注册表视图只会为
        // 每个已安装插件各获取一次（getPlugin 内部），没有额外的"缓存弱键 token"
        // 获取。计数与真实行为一致：无额外硬编码。
        VariantPoolService.buildPool(access, gunWithNoVariants(), gunData);

        assertEquals(gunData.installedPlugins().size(), access.pluginRegistryCalls.get(),
                "组装变体池时每个已安装插件只应解析一次 PluginDefinition，且无多余的注册表视图获取");

        // 行为不变：两个插件的 adds_variants 都按声明合并进池（预览走同一 assemble 路径）。
        CountingRegistryAccess previewAccess = new CountingRegistryAccess(raw);
        List<VariantPoolService.PoolEntry> entries =
                VariantPoolService.previewPool(previewAccess, gunWithNoVariants(), gunData);
        assertEquals(2, entries.stream().filter(e -> !e.normalFallback()).count(),
                "两个插件的变体都应贡献候选");
        assertEquals(2.0, entries.stream().filter(e -> VARIANT_A.equals(e.variantId()))
                        .findFirst().orElseThrow().finalWeight(), 1e-9, "variant_a 权重来自插件 A");
        assertEquals(3.0, entries.stream().filter(e -> VARIANT_B.equals(e.variantId()))
                        .findFirst().orElseThrow().finalWeight(), 1e-9, "variant_b 权重来自插件 B");
    }

    // ---- Test 3: attribute_meta holder resolution cached per registry ----

    @Test
    void attributeMetaResolutionCachedPerRegistryInstance() {
        CountingAttributeMetaRegistry first = new CountingAttributeMetaRegistry();
        Registry.register(first, LOGICAL_ID, AttributeMeta.of(
                ResourceLocation.parse("minecraft:generic.attack_speed"), 2.0));
        RegistryAccess firstAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(first));

        org.yanbwe.modularshoot.attribute.AttributeResolver.metaFor(firstAccess, LOGICAL_ID);
        org.yanbwe.modularshoot.attribute.AttributeResolver.metaFor(firstAccess, LOGICAL_ID);

        assertEquals(1, first.getCalls.get(),
                "相同 (RegistryAccess, logicalId) 的 holder 解析结果必须缓存，只访问底层注册表一次");

        // reload：新 Registry 实例须重新解析（弱引用键，不返回旧实例的陈旧结果）。
        CountingAttributeMetaRegistry second = new CountingAttributeMetaRegistry();
        AttributeMeta newMeta = AttributeMeta.of(
                ResourceLocation.parse("minecraft:generic.attack_speed"), 5.0);
        Registry.register(second, LOGICAL_ID, newMeta);
        RegistryAccess secondAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(second));

        AttributeMeta resolved = org.yanbwe.modularshoot.attribute.AttributeResolver.metaFor(
                secondAccess, LOGICAL_ID);
        assertSame(newMeta, resolved, "reload 后必须返回新 Registry 实例解析出的元数据");
        assertEquals(1, second.getCalls.get(), "新 Registry 实例上第一次查询才会访问其底层注册表");
    }

    // ---- Test 4: ShootingEngine.fire gun-registry single-resolution seam ----

    @Test
    void gunDefinitionLookupSeamResolvesGunThroughRegistryExactlyOnce() {
        MappedRegistry<GunDefinition> gunRegistry = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(gunRegistry, GUN_ID, gunWithTraits(Map.of()));
        RegistryAccess raw = new RegistryAccess.ImmutableRegistryAccess(List.of(gunRegistry));
        CountingRegistryAccess access = new CountingRegistryAccess(raw);

        Optional<GunDefinition> resolved =
                ShootingEngine.lookupGunDefinition(access, GUN_ID);

        assertEquals(Optional.of(gunRegistry.getOptional(GUN_ID).orElseThrow()), resolved,
                "seam 应解析出枪械定义");
        assertEquals(1, access.gunRegistryCalls.get(),
                "lookupGunDefinition 一次调用必须只查询一次 guns 注册表（fire 的唯一 gun 解析入口）");
    }

    /**
     * 结构性回归测试：解析 {@code ShootingEngine.class} 的字节码，断言以下不变量——
     * <ol>
     *   <li>{@code fire(ServerPlayer, GunData, double)} 恰好通过
     *       {@code lookupGunDefinition} seam 解析枪械定义一次，不直接调用
     *       {@code GunRegistry.getGun}，也不调用 2 参
     *       {@code TraitMergeService.computeTraits}（后者会重新 getGun，即任务 1.2
     *       要防止的回归）；</li>
     *   <li>{@code lookupGunDefinition} 是 {@code ShootingEngine} 内唯一对
     *       {@code GunRegistry.getGun} 的调用点，且恰好一次。</li>
     * </ol>
     */
    @Test
    void fireLooksUpGunDefinitionExactlyOnceStructurally() {
        ParsedClass parsed = ParsedClass.read(ShootingEngine.class);

        String fireDesc = "(Lnet/minecraft/server/level/ServerPlayer;"
                + "Lorg/yanbwe/modularshoot/component/GunData;D)V";
        ParsedClass.MethodInfo fire = parsed.methods.stream()
                .filter(m -> "fire".equals(m.name()) && fireDesc.equals(m.descriptor()))
                .findFirst().orElseGet(() -> org.junit.jupiter.api.Assertions.fail(
                        "未在 ShootingEngine 中找到 fire(ServerPlayer, GunData, double)"));

        String seamDesc = "(Lnet/minecraft/core/RegistryAccess;"
                + "Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;";
        String seamOwner = "org/yanbwe/modularshoot/shooting/ShootingEngine";
        String gunOwner = "org/yanbwe/modularshoot/registry/gun/GunRegistry";
        String mergeOwner = "org/yanbwe/modularshoot/plugin/TraitMergeService";
        String merge2Desc = "(Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/core/RegistryAccess;)Ljava/util/Map;";
        String getGunDesc = "(Lnet/minecraft/core/RegistryAccess;"
                + "Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;";

        long seamCallCount = fire.calls().stream()
                .filter(c -> seamOwner.equals(c.owner()) && "lookupGunDefinition".equals(c.name())
                        && seamDesc.equals(c.descriptor()))
                .count();
        long directGetGunCount = fire.calls().stream()
                .filter(c -> gunOwner.equals(c.owner()) && "getGun".equals(c.name())
                        && getGunDesc.equals(c.descriptor()))
                .count();
        long twoArgMergeCount = fire.calls().stream()
                .filter(c -> mergeOwner.equals(c.owner()) && "computeTraits".equals(c.name())
                        && merge2Desc.equals(c.descriptor()))
                .count();

        assertEquals(1, seamCallCount,
                "fire 必须恰好一次通过 lookupGunDefinition seam 解析枪械定义（回归：再次重复解析）");
        assertEquals(0, directGetGunCount,
                "fire 不得直接调用 GunRegistry.getGun——所有解析必须经 seam，一次射击一次查询");
        assertEquals(0, twoArgMergeCount,
                "fire 不得再调用 2 参 TraitMergeService.computeTraits（该重载会重新 getGun）");

        ParsedClass.MethodInfo seam = parsed.methods.stream()
                .filter(m -> "lookupGunDefinition".equals(m.name()) && seamDesc.equals(m.descriptor()))
                .findFirst().orElseGet(() -> org.junit.jupiter.api.Assertions.fail(
                        "ShootingEngine 中缺少 lookupGunDefinition seam"));
        long seamGetGunCount = seam.calls().stream()
                .filter(c -> gunOwner.equals(c.owner()) && "getGun".equals(c.name())
                        && getGunDesc.equals(c.descriptor()))
                .count();
        assertEquals(1, seamGetGunCount,
                "seam 必须且只能包含一次 GunRegistry.getGun 调用");
    }

    // ---- Minimal class-file reader for bytecode-level invariants --------

    /**
     * 只解析断言所需的常量池（Utf8/Class/NameAndType/Methodref）与每个方法的
     * Code 属性中的 {@code invokestatic} 指令。
     */
    private static final class ParsedClass {
        record InvokeCall(String owner, String name, String descriptor) {
        }

        record MethodInfo(String name, String descriptor, List<InvokeCall> calls) {
        }

        final List<MethodInfo> methods;

        private ParsedClass(List<MethodInfo> methods) {
            this.methods = methods;
        }

        static ParsedClass read(Class<?> type) {
            String resource = type.getName().replace('.', '/') + ".class";
            byte[] bytes;
            try (var in = type.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    org.junit.jupiter.api.Assertions.fail("找不到类文件字节码: " + resource);
                }
                bytes = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new AssertionError("读取类文件失败: " + resource, e);
            }
            return parse(bytes);
        }

        static ParsedClass parse(byte[] b) {
            Cursor c = new Cursor(b);
            assertEquals(0xCAFEBABE, (int) c.u4(), "非法 class 文件魔数");
            c.u2(); // minor
            c.u2(); // major
            int cpCount = c.u2();
            String[] utf8 = new String[cpCount];
            int[] classIndex = new int[cpCount];              // CONSTANT_Class -> name_index
            int[][] methodRef = new int[cpCount][];           // {class_index, name_and_type_index}
            int[] natNameIndex = new int[cpCount];            // NameAndType -> name_index
            int[] natDescIndex = new int[cpCount];            // NameAndType -> descriptor_index
            for (int i = 1; i < cpCount; i++) {
                int tag = c.u1();
                switch (tag) {
                    case 1 -> utf8[i] = c.utf8(c.u2());              // Utf8
                    case 3, 4 -> c.skip(4);                          // int / float
                    case 5, 6 -> { c.skip(8); i++; }                 // long / double（占两个槽）
                    case 7 -> classIndex[i] = c.u2();                // Class
                    case 8 -> c.skip(2);                             // String
                    case 9, 10, 11 -> methodRef[i] = new int[]{c.u2(), c.u2()};
                    case 12 -> {                                      // NameAndType
                        natNameIndex[i] = c.u2();
                        natDescIndex[i] = c.u2();
                    }
                    case 15 -> c.skip(3);                            // MethodHandle
                    case 16 -> c.skip(2);                            // MethodType
                    case 17, 18 -> c.skip(4);                        // Dynamic / InvokeDynamic
                    case 19, 20 -> c.skip(2);                        // Module / Package
                    default -> throw new AssertionError("不支持的常量池 tag: " + tag);
                }
            }

            c.skip(6); // access_flags, this_class, super_class
            int interfaceCount = c.u2();
            c.skip(interfaceCount * 2);
            int fieldCount = c.u2();
            for (int i = 0; i < fieldCount; i++) {
                c.skip(6); // access, name, descriptor
                skipAttributes(c);
            }

            List<MethodInfo> methods = new java.util.ArrayList<>();
            int methodCount = c.u2();
            for (int i = 0; i < methodCount; i++) {
                c.skip(2); // access_flags
                String name = utf8[c.u2()];
                String descriptor = utf8[c.u2()];
                byte[] codeAttr = null;
                int attrCount = c.u2();
                for (int a = 0; a < attrCount; a++) {
                    String attrName = utf8[c.u2()];
                    int attrLen = (int) c.u4();
                    if ("Code".equals(attrName)) {
                        codeAttr = c.bytes(attrLen);
                    } else {
                        c.skip(attrLen);
                    }
                }
                methods.add(new MethodInfo(name, descriptor,
                        codeAttr == null ? List.of()
                                : decodeInvokeStatics(codeAttr, utf8, classIndex, methodRef,
                                        natNameIndex, natDescIndex)));
            }
            return new ParsedClass(methods);
        }

        static List<InvokeCall> decodeInvokeStatics(
                byte[] codeAttr, String[] utf8, int[] classIndex, int[][] methodRef,
                int[] natNameIndex, int[] natDescIndex) {
            Cursor c = new Cursor(codeAttr);
            c.skip(4); // max_stack + max_locals
            int codeLen = (int) c.u4();
            byte[] code = c.bytes(codeLen);
            List<InvokeCall> calls = new java.util.ArrayList<>();
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                if (op == 0xB8) { // invokestatic
                    int refIdx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    int[] ref = methodRef[refIdx];
                    int natIdx = ref[1];
                    String owner = utf8[classIndex[ref[0]]];
                    String name = utf8[natNameIndex[natIdx]];
                    String descriptor = utf8[natDescIndex[natIdx]];
                    calls.add(new InvokeCall(owner, name, descriptor));
                    pc += 3;
                } else {
                    pc += instructionLength(op, code, pc);
                }
            }
            return calls;
        }

        static void skipAttributes(Cursor c) {
            int count = c.u2();
            for (int i = 0; i < count; i++) {
                c.skip(2); // attribute_name_index
                c.skip((int) c.u4());
            }
        }

        static int instructionLength(int op, byte[] code, int pc) {
            return switch (op) {
                case 0x10, 0x3C, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9 -> 2;
                case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0,
                     0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5,
                     0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> 3;
                case 0xC5 -> 4;
                case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
                case 0xAA -> tableswitchLength(code, pc);
                case 0xAB -> lookupswitchLength(code, pc);
                case 0xC4 -> wideLength(code, pc);
                default -> 1;
            };
        }

        static int tableswitchLength(byte[] code, int pc) {
            int pad = (4 - ((pc + 1) & 3)) & 3;
            int base = pc + 1 + pad;
            int low = i32(code, base + 4);
            int high = i32(code, base + 8);
            return (1 + pad + 12) + (high - low + 1) * 4;
        }

        static int lookupswitchLength(byte[] code, int pc) {
            int pad = (4 - ((pc + 1) & 3)) & 3;
            int base = pc + 1 + pad;
            int npairs = i32(code, base + 4);
            return (1 + pad + 8) + npairs * 8;
        }

        static int wideLength(byte[] code, int pc) {
            return code[pc + 1] == 0x84 ? 6 : 4;
        }

        static int i32(byte[] code, int at) {
            return ((code[at] & 0xFF) << 24) | ((code[at + 1] & 0xFF) << 16)
                    | ((code[at + 2] & 0xFF) << 8) | (code[at + 3] & 0xFF);
        }
    }

    /** 有界 little-endian 读取器：只实现 class 文件解析所需的原语。 */
    private static final class Cursor {
        private final byte[] bytes;
        private int pos;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int u1() {
            return bytes[pos++] & 0xFF;
        }

        int u2() {
            int r = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
            pos += 2;
            return r;
        }

        long u4() {
            long r = (((long) u1()) << 24) | (((long) u1()) << 16)
                    | (((long) u1()) << 8) | (long) u1();
            return r;
        }

        String utf8(int len) {
            String s = new String(bytes, pos, len, java.nio.charset.StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        byte[] bytes(int len) {
            byte[] out = java.util.Arrays.copyOfRange(bytes, pos, pos + len);
            pos += len;
            return out;
        }

        void skip(int n) {
            pos += n;
        }
    }
}
