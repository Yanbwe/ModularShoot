package org.yanbwe.modularshoot.attribute;

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
 *       tooltip rows ({@link #metaFor} is called internally by
 *       {@link #readFinalValue} to resolve the metadata entry).</li>
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
        Registry<AttributeMeta> registry =
                registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY).orElse(null);
        if (registry == null) {
            return null;
        }
        return registry.get(logicalId);
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
        AttributeMeta meta = metaFor(registryAccess, logicalId);
        if (meta == null) {
            return 0.0;
        }
        if (!meta.allowsEntity(entity.getType())) {
            return 0.0;
        }
        Holder<Attribute> holder = resolveBoundHolder(meta);
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
