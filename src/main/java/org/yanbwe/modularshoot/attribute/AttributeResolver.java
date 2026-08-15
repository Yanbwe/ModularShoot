package org.yanbwe.modularshoot.attribute;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;

/**
 * Central resolution service mapping a logical attribute id to an entity's
 * final attribute value.
 *
 * <p>Resolution chain: 逻辑属性 id → {@code modularshoot:attribute_meta}
 * 条目 → {@code binds} 目标原版属性 → 实体最终值. The
 * {@code attribute_meta} datapack registry (keyed by logical attribute id,
 * value {@link AttributeMeta}) carries the hot-reloadable metadata; each
 * entry's {@code binds} field points at an already-registered vanilla
 * {@link Attribute} id, which may belong to the framework
 * ({@code modularshoot:hit_damage}) or to any third-party mod.</p>
 *
 * <p>The service is consumed by three paths:
 * <ul>
 *   <li><b>挂载层</b> &mdash; {@link #resolveBoundHolder} resolves the bound
 *       vanilla attribute holder before mounting modifiers or building
 *       tooltip rows ({@link #metaFor} and {@link #readFinalValue} resolve
 *       the metadata entry and bound holder through the shared per-registry
 *       cache).</li>
 *   <li><b>结算/射速门禁/客户端预测/调试命令</b> &mdash;
 *       {@link #readFinalValue} reads the entity's final computed value in a
 *       single call, following the full chain internally.</li>
 * </ul>
 *
 * <p><strong>Degradation semantics.</strong> Every missing link in the chain
 * (registry absent, entry absent, {@code binds} target unregistered,
 * attribute not mounted on the entity) yields {@code null} or {@code 0.0}
 * and never throws an exception, matching the degradation contract of
 * {@link AttributeBindsDegradationHandler} (设计文档 §属性元数据 binds 失效
 * 降级).</p>
 *
 * <p>The class is not instantiable; all methods are static and each is
 * under 50 lines (设计文档 §函数&lt;50行).</p>
 *
 * @see AttributeMeta
 * @see AttributeBindsDegradationHandler
 * @see AttributeModifierService
 */
public final class AttributeResolver {

    private AttributeResolver() {
    }

    /**
     * Per-{@link Registry} weak-reference cache of the bound attribute holder
     * (审查优化, 任务 1.2): {@link #readFinalValue} resolves the same
     * {@code (RegistryAccess, logicalId)} pair — metadata entry + {@code binds}
     * target holder — on every stat read of the shooting hot path. Results are
     * keyed by the actual {@link Registry} instance (not the
     * {@link RegistryAccess} wrapper), so a {@code /reload} that swaps in a new
     * {@code attribute_meta} registry is a cache miss and never serves the old
     * instance's bind. Misses (a logical id with no metadata entry, or a
     * {@code binds} target that is unregistered) are cached too via
     * {@link Optional} sentinels.
     */
    private static final Map<Registry<AttributeMeta>, Map<ResourceLocation, Optional<BoundAttribute>>> BOUND_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Immutable holder of one cached resolution: the metadata entry (never
     * {@code null} when present) and its resolved vanilla attribute holder,
     * which may be {@code null} when the {@code binds} target is unregistered.
     */
    private record BoundAttribute(AttributeMeta meta, @Nullable Holder<Attribute> holder) {
    }

    /**
     * Resolves a logical attribute id to its metadata entry and bound vanilla
     * attribute holder, caching the result per {@link Registry} instance.
     *
     * @param registryAccess the runtime registry view
     * @param logicalId      the logical attribute id
     * @return the cached resolution, or empty when the registry is absent or
     *         the metadata entry is missing
     */
    private static Optional<BoundAttribute> resolveBound(RegistryAccess registryAccess, ResourceLocation logicalId) {
        Registry<AttributeMeta> registry =
                registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY).orElse(null);
        if (registry == null) {
            return Optional.empty();
        }
        Map<ResourceLocation, Optional<BoundAttribute>> byId;
        synchronized (BOUND_CACHE) {
            byId = BOUND_CACHE.computeIfAbsent(registry, r -> new ConcurrentHashMap<>());
        }
        return byId.computeIfAbsent(logicalId, id -> resolveBoundUncached(registry, id));
    }

    /**
     * Uncached resolution: metadata entry from the registry, then its
     * {@code binds} target holder from {@link BuiltInRegistries#ATTRIBUTE}.
     */
    private static Optional<BoundAttribute> resolveBoundUncached(
            Registry<AttributeMeta> registry, ResourceLocation logicalId) {
        AttributeMeta meta = registry.get(logicalId);
        if (meta == null) {
            return Optional.empty();
        }
        Holder<Attribute> holder = BuiltInRegistries.ATTRIBUTE.getHolder(meta.binds()).orElse(null);
        return Optional.of(new BoundAttribute(meta, holder));
    }

    /**
     * Looks up the {@link AttributeMeta} entry for a logical attribute id.
     *
     * <p>Returns the raw metadata entry without resolving its {@code binds}
     * target; callers needing the bound vanilla attribute use
     * {@link #resolveBoundHolder}. A missing registry or entry yields
     * {@code null} rather than an exception (降级语义，与
     * {@link AttributeBindsDegradationHandler} 的契约一致).
     *
     * @param registryAccess the runtime registry view (for
     *                       {@code attribute_meta})
     * @param logicalId      the logical attribute id to look up
     * @return the metadata entry, or {@code null} when the registry or the
     *         entry is absent
     */
    @Nullable
    public static AttributeMeta metaFor(RegistryAccess registryAccess, ResourceLocation logicalId) {
        return resolveBound(registryAccess, logicalId).map(BoundAttribute::meta).orElse(null);
    }

    /**
     * Resolves the vanilla {@link Attribute} holder bound by an entry's
     * {@code binds} field.
     *
     * <p>Consults {@link BuiltInRegistries#ATTRIBUTE}; when the bound id is
     * not registered (e.g. a third-party mod was uninstalled and its
     * attribute body disappeared) the method returns {@code null} so callers
     * can degrade gracefully without mounting modifiers or reading values
     * (设计文档 §属性元数据 binds 失效降级).
     *
     * @param meta the metadata entry whose {@code binds} target is resolved
     * @return the registered attribute holder, or {@code null} when the
     *         bound attribute is not registered
     */
    @Nullable
    public static Holder<Attribute> resolveBoundHolder(AttributeMeta meta) {
        return BuiltInRegistries.ATTRIBUTE.getHolder(meta.binds()).orElse(null);
    }

    /**
     * Reads the entity's final value for a logical attribute.
     *
     * <p>Follows the full chain 逻辑属性 id → {@code attribute_meta} 条目 →
     * {@code binds} 目标原版属性 → 实体属性实例 and returns
     * {@link AttributeInstance#getValue()} of the resolved instance. Every
     * missing link degrades to {@code 0.0} without throwing: the metadata
     * entry may be absent, the bound attribute may be unregistered, the
     * entity's type may be outside the entry's {@code entity_types}
     * read whitelist, or the attribute may not be mounted on the entity
     * (e.g. a non-player mob that never received the framework's attributes).</p>
     *
     * <p>Never uses {@link LivingEntity#getAttributeValue} because that
     * method throws {@link IllegalArgumentException} when the attribute is
     * missing on the entity; {@link LivingEntity#getAttribute} is null-checked
     * instead.
     *
     * @param entity         the entity to read the attribute value from
     * @param logicalId      the logical attribute id
     * @param registryAccess the runtime registry view (for
     *                       {@code attribute_meta})
     * @return the final attribute value, or {@code 0.0} when any link in the
     *         chain is missing or the entity type is not whitelisted
     */
    public static double readFinalValue(LivingEntity entity, ResourceLocation logicalId, RegistryAccess registryAccess) {
        Optional<BoundAttribute> bound = resolveBound(registryAccess, logicalId);
        if (bound.isEmpty()) {
            return 0.0;
        }
        BoundAttribute b = bound.get();
        if (!b.meta().allowsEntity(entity.getType())) {
            return 0.0;
        }
        Holder<Attribute> holder = b.holder();
        if (holder == null) {
            return 0.0;
        }
        AttributeInstance instance = entity.getAttribute(holder);
        if (instance == null) {
            return 0.0;
        }
        return instance.getValue();
    }
}
