package org.yanbwe.modularshoot.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable data component stored on a {@code modularshoot:gun} item stack.
 *
 * <p>Holds the runtime per-gun state: which gun definition the stack refers to,
 * a stable instance uuid for bullet-snapshot backtracking, the ordered list of
 * installed plugins, a modifier version counter for anti-cheat rate-control,
 * and the per-gun state map used by the state storage system.</p>
 *
 * <p>The component is persisted with the item NBT so it survives drops, chests,
 * relogs and dimension changes. The framework recomputes the vanilla
 * {@code ATTRIBUTE_MODIFIERS} component from the gun definition plus the
 * installed plugins whenever this data changes.</p>
 *
 * @param gunId           the gun definition id in the {@code modularshoot:guns} registry
 * @param gunInstanceUuid stable per-stack identifier generated at creation time;
 *                        used by {@code resolveGunFromSnapshot} to locate the firing
 *                        gun even if the player has switched main hand
 * @param installedPlugins ordered list of installed plugin instances; order reflects
 *                        install sequence and influences same-priority trait conflicts
 * @param modifierVersion anti-cheat version counter; incremented on every
 *                        install/uninstall/lock change. Sent with shoot packets
 *                        so the server can reject stale-modifier exploits
 * @param state           per-gun state payload (kill stacks, heat, etc.) stored as a
 *                        compound tag. The typed dispatch (int/double/boolean/etc.) is
 *                        finalised by the state storage system (M5); M1 uses a raw
 *                        compound tag so the field serialises correctly out of the box
 */
public record GunData(
        ResourceLocation gunId,
        UUID gunInstanceUuid,
        List<PluginInstance> installedPlugins,
        int modifierVersion,
        CompoundTag state
) {
    public static final Codec<GunData> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("gun_id").forGetter(GunData::gunId),
                    UUIDUtil.CODEC.fieldOf("gun_instance_uuid").forGetter(GunData::gunInstanceUuid),
                    PluginInstance.CODEC.listOf().optionalFieldOf("installed_plugins", List.of()).forGetter(GunData::installedPlugins),
                    Codec.INT.optionalFieldOf("modifier_version", 0).forGetter(GunData::modifierVersion),
                    CompoundTag.CODEC.optionalFieldOf("state", new CompoundTag()).forGetter(GunData::state)
            ).apply(instance, GunData::new)
    );

    /**
     * Factory for a freshly created gun stack: empty plugin list, version 0, empty state.
     *
     * @param gunId           the gun definition id
     * @param gunInstanceUuid the newly generated instance uuid
     * @return a new immutable {@link GunData} with empty plugins, version 0 and empty state
     */
    public static GunData create(ResourceLocation gunId, UUID gunInstanceUuid) {
        return new GunData(gunId, gunInstanceUuid, List.of(), 0, new CompoundTag());
    }
}
