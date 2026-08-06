package org.yanbwe.modularshoot.variant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Registry and collection hub for {@link VariantContributor}s (机制四 §6.2
 * 来源 3).
 *
 * <p>Provides the extension point where third-party mods (e.g. trinkets)
 * register weight modifiers for the per-shot variant pool (规格 §6.2). The
 * framework itself registers zero contributors by default.</p>
 *
 * <p>The contributor list is a {@link CopyOnWriteArrayList} so that registration
 * from mod init threads and iteration from the server shoot path do not
 * require external synchronisation. Registration happens rarely (during mod
 * init) while {@link #collect()} runs on every shot; the copy-on-write cost is
 * therefore paid on the rare path and iteration is lock-free.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class VariantContributorRegistry {

    /**
     * Thread-safe list of contributors registered by third-party mods.
     */
    private static final List<VariantContributor> CONTRIBUTORS = new CopyOnWriteArrayList<>();

    private VariantContributorRegistry() {
    }

    /**
     * Registers a variant weight contributor.
     *
     * <p>Registered contributors are queried by {@link #collect()} every time
     * a shot's variant pool is assembled. Contributors run in registration
     * order; same-variant modifiers are merged in that order, earlier
     * contributors first.</p>
     *
     * <p>Safe to call during mod common-setup; the underlying list is
     * thread-safe.</p>
     *
     * @param contributor the contributor to register; must not be {@code null}
     */
    public static void register(VariantContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        CONTRIBUTORS.add(contributor);
    }

    /**
     * Collects every registered contributor's weight modifiers into a
     * per-shot map.
     *
     * <p>Contributors run in registration order; each declared
     * "variant id &rarr; modifier" pair is appended to that id's list, so a
     * variant declared by several contributors ends up with all its modifiers
     * in registration order (先注册的在前). The returned map is a
     * {@link LinkedHashMap} preserving first-declaration order of the variant
     * ids; lists are plain {@link ArrayList}s valid for the duration of the
     * pool assembly.</p>
     *
     * @return a map of variant id &rarr; weight modifiers; never {@code null},
     *         empty when no contributors are registered
     */
    public static Map<ResourceLocation, List<AttributeModifier>> collect() {
        Map<ResourceLocation, List<AttributeModifier>> out = new LinkedHashMap<>();
        for (VariantContributor contributor : CONTRIBUTORS) {
            contributor.contribute(
                    (id, modifier) -> out.computeIfAbsent(id, key -> new ArrayList<>()).add(modifier));
        }
        return out;
    }

    /**
     * Clears all registered contributors (test isolation only; not part of the
     * public extension contract).
     */
    static void clear() {
        CONTRIBUTORS.clear();
    }
}
