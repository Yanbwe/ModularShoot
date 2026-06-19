package org.yanbwe.modularshoot.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable record of a single plugin installed on a gun.
 *
 * <p>One plugin definition can be installed multiple times on the same gun;
 * the {@code instanceUuid} distinguishes those copies and is used as the
 * stable identifier for all per-instance operations (uninstall, lock, modifier
 * id). The {@code installedTypeId} records which plugin category the instance
 * was placed into at install time and is persisted with the item NBT so that
 * later tag changes do not retroactively evict the plugin.</p>
 *
 * @param pluginId       the plugin definition id in the {@code modularshoot:plugins} registry
 * @param instanceUuid   unique identifier generated at install time; stable for the
 *                       lifetime of this instance and used to locate it for uninstall/lock
 * @param installedTypeId the category id the instance was installed into; persisted,
 *                        not affected by later tag/reload changes
 * @param locked         lock flag; {@code false} by default, mutable via the lock API.
 *                        Locked plugins are skipped by non-forced uninstall operations
 */
public record PluginInstance(
        ResourceLocation pluginId,
        UUID instanceUuid,
        ResourceLocation installedTypeId,
        boolean locked
) {
    public static final Codec<PluginInstance> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("plugin_id").forGetter(PluginInstance::pluginId),
                    UUIDUtil.CODEC.fieldOf("instance_uuid").forGetter(PluginInstance::instanceUuid),
                    ResourceLocation.CODEC.fieldOf("installed_type_id").forGetter(PluginInstance::installedTypeId),
                    Codec.BOOL.optionalFieldOf("locked", false).forGetter(PluginInstance::locked)
            ).apply(instance, PluginInstance::new)
    );

    /**
     * Factory for a freshly installed plugin instance: locked defaults to {@code false}.
     *
     * @param pluginId       the plugin definition id
     * @param instanceUuid   the newly generated instance uuid
     * @param installedTypeId the category id chosen by the install algorithm
     * @return a new immutable {@link PluginInstance} with {@code locked = false}
     */
    public static PluginInstance create(ResourceLocation pluginId, UUID instanceUuid, ResourceLocation installedTypeId) {
        return new PluginInstance(pluginId, instanceUuid, installedTypeId, false);
    }
}
