package org.yanbwe.modularshoot.registry.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable binding of a vanilla item id to a {@code modularshoot:guns}
 * registry gun id (设计规格 物品绑定系统 §3.1).
 *
 * <p>The binding is <strong>item-id level</strong>: it applies to every
 * instance of the bound item, and the entry key under which the binding is
 * declared may be any file id (it does not have to match the item id). The
 * bound gun entry is identified by its registry key.</p>
 *
 * <p>JSON format:</p>
 * <pre>{@code
 * {
 *   "item": "minecraft:diamond_sword",
 *   "gun":  "mypack:sword_rifle"
 * }
 * }</pre>
 *
 * <p>Both fields are required; decoding an object missing either one fails.</p>
 *
 * @param itemId the bound item's registry id (e.g. {@code minecraft:diamond_sword})
 * @param gunId  the target gun entry's registry key (e.g. {@code mypack:sword_rifle})
 */
public record GunItemBinding(ResourceLocation itemId, ResourceLocation gunId) {

    public static final Codec<GunItemBinding> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("item").forGetter(GunItemBinding::itemId),
                    ResourceLocation.CODEC.fieldOf("gun").forGetter(GunItemBinding::gunId)
            ).apply(instance, GunItemBinding::new)
    );
}
