package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Pure diff/merge operations for the per-gun state payload (阶段 2 /
 * 任务 2.3 §GunSync 状态 diff).
 *
 * <p>Given the previously-synced state and the current state of a gun,
 * {@link #diff} produces a minimal {@link CompoundTag} patch containing only
 * the keys whose values actually changed, and {@link #removedKeys} reports the
 * keys that disappeared. The client applies a patch with {@link #merge}: it
 * copies the existing state, overwrites the changed keys and removes the
 * listed removed keys, leaving everything else untouched. This lets the server
 * send only the changed state keys instead of re-transmitting the full NBT
 * state map on every state write (e.g. ammo decrement, kill stacks).</p>
 *
 * <p><b>Key space.</b> State keys are the raw NBT string keys of the
 * {@link CompoundTag} (i.e. the string form of each state id). The diff/merge
 * operates purely on these keys and never consults the registry, so it works
 * identically on the server and the client without needing a
 * {@link net.minecraft.core.RegistryAccess}.</p>
 */
public final class GunStateDiff {

    private GunStateDiff() {
    }

    /**
     * Produces a patch {@link CompoundTag} containing only the keys whose
     * values differ between {@code base} and {@code target}. Keys present in
     * both with equal values are omitted; keys present in {@code target} but
     * absent or different in {@code base} are included with their new value.
     *
     * @param base   the previously-synced state (not mutated)
     * @param target the current authoritative state (not mutated)
     * @return a compact patch of only the changed keys
     */
    public static CompoundTag diff(CompoundTag base, CompoundTag target) {
        CompoundTag patch = new CompoundTag();
        for (String key : target.getAllKeys()) {
            Tag targetValue = target.get(key);
            if (!Objects.equals(targetValue, base.get(key))) {
                patch.put(key, targetValue.copy());
            }
        }
        return patch;
    }

    /**
     * Returns the state keys present in {@code base} but absent in
     * {@code target} — i.e. keys the client must remove to match the server.
     *
     * @param base   the previously-synced state (not mutated)
     * @param target the current authoritative state (not mutated)
     * @return the list of removed keys, in deterministic order
     */
    public static List<String> removedKeys(CompoundTag base, CompoundTag target) {
        List<String> removed = new ArrayList<>();
        for (String key : base.getAllKeys()) {
            if (!target.contains(key)) {
                removed.add(key);
            }
        }
        return removed;
    }

    /**
     * Merges a patch onto an existing state, returning a new
     * {@link CompoundTag}: copies {@code base}, applies every key of
     * {@code patch} (overwriting same-named keys) and removes every key in
     * {@code removedKeys}. Untouched keys are preserved. Purely functional —
     * neither input is mutated.
     *
     * @param base        the existing client state (not mutated)
     * @param patch       the changed-key patch from {@link #diff} (not mutated)
     * @param removedKeys the keys to remove (from {@link #removedKeys})
     * @return a new state tag reflecting the merge
     */
    public static CompoundTag merge(CompoundTag base, CompoundTag patch, List<String> removedKeys) {
        CompoundTag result = base.copy();
        for (String key : patch.getAllKeys()) {
            result.put(key, patch.get(key).copy());
        }
        for (String key : removedKeys) {
            result.remove(key);
        }
        return result;
    }
}
