package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.client.ClientGunDataStore;
import org.yanbwe.modularshoot.client.PlayerShootStateManager;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

/**
 * Custom item renderer for framework guns, integrating shoot-texture
 * switching and plugin overlay compositing with the vanilla flat-item look.
 *
 * <p>In 1.21.1 the {@code custom_renderer} model flag no longer exists. The
 * supported entry point is a {@code builtin/entity} parent model on the item
 * (baking into a {@code BuiltInModel} with {@code isCustomRenderer() == true}),
 * which routes the vanilla pipeline into
 * {@link IClientItemExtensions#getCustomRenderer()} → {@link #renderByItem}
 * (NeoForge patch on {@code ItemRenderer}). The gun and plugin items ship
 * with such models, and this renderer is registered for {@code modularshoot:gun}
 * on the mod event bus (see
 * {@link org.yanbwe.modularshoot.client.ModularShootClientExtensions}).</p>
 *
 * <h2>Render flow</h2>
 * <ol>
 *   <li>The vanilla pipeline calls {@link #renderByItem} for every gun stack
 *       (the item model reports {@code isCustomRenderer() == true}).</li>
 *   <li>This method reads the {@link GunDefinition} from the stack's
 *       {@code gun_data} component and the holding player's uuid.</li>
 *   <li>{@link ShootTextureResolver#resolveTexture} selects the base or
 *       shoot texture based on the gun's {@code shoot_texture_mode} and the
 *       player's shoot state.</li>
 *   <li>Installed plugin {@code texture_overlay} layers are collected via
 *       {@link PluginOverlayCompositor} — preferring the server-pushed
 *       snapshot in {@link ClientGunDataStore}, falling back to the local
 *       {@code gun_data} component.</li>
 *   <li>{@link DynamicGunTextureCache} loads the base PNG, composites the
 *       overlays on top (and, for outline-carrying guns, registers a white
 *       outline-mask texture alongside) and uploads the result as a dynamic
 *       texture (cached per texture+overlays+gunOutlines+modifierVersion).</li>
 *   <li>{@link DynamicItemModelRenderer} draws the texture as an extruded
 *       flat item, visually equivalent to a vanilla {@code item/generated}
 *       render. When an installed plugin has a registered dynamic tint
 *       provider ({@link DynamicOutlineTintRegistry}), the outline mask is
 *       drawn on top tinted with the provider's per-frame colour.</li>
 * </ol>
 *
 * <h2>Why not a baked model + ItemOverrideList</h2>
 * <p>Shoot-texture switching is driven by per-player client state
 * ({@link PlayerShootStateManager}) that changes every tick, and the plugin
 * overlay result is composited at runtime from datapack data — neither can
 * be represented in a resource-pack baked model. {@code ItemOverrideList}
 * is designed for model switching driven by the item's own data (damage,
 * Data Components), not external dynamic state; rewriting to it would also
 * require injecting overrides into the model baking pipeline. The BEWR
 * entry point plus the {@link #RENDERING_PLAYER_UUID} ThreadLocal provides
 * clean access to the external state for both first-person (local player)
 * and third-person (remote player) rendering (设计文档 §渲染器).</p>
 *
 * <p>Content authors therefore only need to supply PNG textures — no model
 * JSONs, no baked-model conventions. When a texture is missing, the
 * placeholder in {@link DynamicGunTextureCache} keeps the pipeline visible
 * and diagnosable.</p>
 *
 * <h2>First-person vs third-person</h2>
 * <p>The vanilla pipeline calls {@code renderByItem} for both first-person
 * (held in hand) and third-person (on the player model) contexts. Both paths
 * use the same texture-resolution logic. In first person the held gun
 * additionally plays a short per-shot <em>recoil kick</em> (设计文档 §第一人称
 * 射击后坐): at the moment a shot fires, the pose is displaced by
 * {@link FirstPersonRecoilKick} — pushed back into the screen with the
 * muzzle rising — driven by the same per-shot animation timer as the
 * third-person arm pose and the {@code per_shot} shoot-texture mode, so all
 * feedback channels pulse with the same cadence.</p>
 *
 * <h2>Player context</h2>
 * <p>{@code renderByItem} does not receive the holding {@code LivingEntity}
 * as a parameter (a vanilla API limitation). For the local player
 * (first-person) we fall back to {@code Minecraft.getInstance().player}.
 * For remote players (third-person) a {@link ThreadLocal} context is
 * provided: a Mixin on {@code ItemRenderer.renderStatic} sets the rendering
 * player's uuid before the call and clears it afterwards
 * (see {@link #setRenderingPlayer} / {@link #clearRenderingPlayer}).</p>
 *
 * <p><strong>Client-only class.</strong> References to {@link Minecraft},
 * {@link net.minecraft.client.renderer.entity.ItemRenderer} and other client
 * types restrict this class to the physical client. It must only be
 * instantiated and registered via {@code RegisterClientExtensionsEvent} on
 * the client mod event bus.</p>
 *
 * @see ShootTextureResolver
 * @see DynamicGunTextureCache
 * @see DynamicItemModelRenderer
 * @see PluginOverlayCompositor
 * @see PlayerShootStateManager
 */
public final class GunItemRenderer extends BlockEntityWithoutLevelRenderer implements IClientItemExtensions {

    /** Fallback uuid used when no player context is available (e.g. menu). */
    private static final UUID NULL_UUID = new UUID(0L, 0L);

    /**
     * ThreadLocal tracking the uuid of the player whose held item is
     * currently being rendered.
     *
     * <p>Set by a Mixin/event hook before {@code ItemRenderer.render} calls
     * {@link #renderByItem} and cleared afterwards. This bridges the vanilla
     * API gap where {@code renderByItem} does not receive the holding
     * entity. When unset, the renderer falls back to the local player
     * (correct for first-person) or {@link #NULL_UUID} (yields base texture
     * for menu/creative-tab rendering).</p>
     */
    private static final ThreadLocal<UUID> RENDERING_PLAYER_UUID = new ThreadLocal<>();

    /**
     * Constructs the renderer with the vanilla dispatcher and model set.
     *
     * <p>Intended to be called from
     * {@code RegisterClientExtensionsEvent} on the client mod event bus. The
     * caller should pass the instances available from
     * {@code Minecraft.getInstance().getBlockEntityRenderDispatcher()} and
     * {@code Minecraft.getInstance().getEntityModels()}.</p>
     *
     * @param dispatcher the block-entity render dispatcher
     * @param modelSet   the entity model set
     */
    public GunItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    /**
     * Returns this renderer as the item's client extension, routing every
     * gun stack into {@link #renderByItem}.
     *
     * @return {@code this}
     */
    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return this;
    }

    /**
     * Clears the dynamic texture cache on resource reload (F3+T) so the
     * composited gun textures are re-read from the resource packs.
     *
     * @param resourceManager the reloaded resource manager
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        DynamicGunTextureCache.getInstance().clear();
    }

    /**
     * Sets the uuid of the player whose held item is about to be rendered.
     *
     * <p>Intended to be called by a Mixin on
     * {@code ItemRenderer.renderStatic} before the call to
     * {@code renderByItem}. This enables correct shoot-texture resolution
     * for remote players in third-person. When the render completes the
     * caller must invoke {@link #clearRenderingPlayer} to avoid leaking
     * context into subsequent renders.</p>
     *
     * @param uuid the rendering player's uuid, or {@code null} to clear
     */
    public static void setRenderingPlayer(@Nullable UUID uuid) {
        RENDERING_PLAYER_UUID.set(uuid);
    }

    /**
     * Clears the rendering-player context after a render completes.
     *
     * <p>Must be called in a {@code finally} block by the same Mixin that
     * called {@link #setRenderingPlayer} to prevent context leakage.</p>
     */
    public static void clearRenderingPlayer() {
        RENDERING_PLAYER_UUID.remove();
    }

    /**
     * Custom render entry point invoked by the vanilla pipeline when the
     * gun's baked model reports {@code isCustomRenderer() == true}.
     *
     * <p>Resolves the correct texture via {@link ShootTextureResolver},
     * composites the installed plugin overlays on top, and draws the result
     * via {@link DynamicItemModelRenderer}. This keeps the gun visually
     * consistent with vanilla flat items while adding shoot-texture
     * switching and plugin overlays as the dynamic elements.</p>
     *
     * @param stack        the gun item stack
     * @param context      the display context (first-person, third-person,
     *                     GUI, etc.)
     * @param poseStack    the pose stack
     * @param bufferSource the buffer source
     * @param light        the packed light value
     * @param overlay      the packed overlay value
     */
    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay) {

        ResourceLocation gunId = ModularShootAPI.getGunId(stack);
        if (gunId == null) {
            DynamicItemModelRenderer.renderMissing(stack, context, poseStack, bufferSource, light, overlay);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            DynamicItemModelRenderer.renderMissing(stack, context, poseStack, bufferSource, light, overlay);
            return;
        }

        GunDefinition gunDef = ModularShootAPI.getGunDefinition(minecraft.level.registryAccess(), gunId).orElse(null);
        if (gunDef == null) {
            DynamicItemModelRenderer.renderMissing(stack, context, poseStack, bufferSource, light, overlay);
            return;
        }

        UUID playerUuid = resolvePlayerUuid();
        ResourceLocation renderTexture = ShootTextureResolver.resolveTexture(gunDef, playerUuid);

        RegistryAccess registryAccess = minecraft.level.registryAccess();
        boolean useSync = isMainHandSyncMatch(stack);
        PluginOverlayCompositor.OverlayRenderData renderData = useSync
                ? PluginOverlayCompositor.collectRenderDataFromSync(
                        ClientGunDataStore.getInstance().getInstalledPlugins(), registryAccess)
                : PluginOverlayCompositor.collectRenderData(ModularShootAPI.getInstalledPlugins(stack), registryAccess);
        int modifierVersion;
        if (useSync) {
            modifierVersion = ClientGunDataStore.getInstance().getModifierVersion();
        } else {
            GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
            modifierVersion = gunData != null ? gunData.modifierVersion() : 0;
        }

        DynamicGunTextureCache.TextureHandle handle = DynamicGunTextureCache.getInstance().getOrCreate(
                new DynamicGunTextureCache.Key(
                        renderTexture, renderData.overlays(), renderData.gunOutlines(), modifierVersion));
        // Dynamic outline pass: when any installed outline-carrying plugin has
        // a registered per-frame tint provider, the white outline mask is
        // drawn over the composite tinted with the provider's colour. Without
        // a provider the mask pass is skipped and the baked static strokes
        // render exactly as before.
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        Optional<Vector4f> tint = DynamicOutlineTintRegistry.resolve(
                renderData.gunOutlinePluginIds(), stack, partialTick);

        // First-person recoil kick: at the shot tick the held gun visibly
        // kicks — pushed back into the screen with the muzzle rising — then
        // settles within a few ticks (设计文档 §第一人称射击后坐). Driven by the
        // same per-shot shootAnimTimer as the third-person arm pose and the
        // per_shot texture mode, so the kick pulses per accepted shot. Only
        // the local player's main-hand stack in a first-person context is
        // affected; the pose frame at this point is item-centred with +X
        // pointing into the screen and +Z along the screen's left-right axis
        // (see FirstPersonRecoilKick).
        if (context.firstPerson() && isLocalMainHandStack(stack)) {
            FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(
                    PlayerShootStateManager.getInstance().getAnimTimer(minecraft.player.getUUID()));
            if (kick.isActive()) {
                poseStack.translate(kick.pushBlocks(), 0.0F, 0.0F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(kick.riseDegrees()));
            }
        }

        DynamicItemModelRenderer.render(
                handle.location(),
                tint.isPresent() ? handle.maskLocation() : null,
                tint.orElse(null),
                handle.quads(),
                scaleFor(gunDef.textureScale(), handle),
                scaleForY(gunDef.textureScale(), handle),
                context, poseStack, bufferSource, light, overlay);
    }

    /**
     * Computes the horizontal geometry scale for the resolved texture.
     *
     * <p>{@code auto} mode sizes the quad from the texture resolution
     * (16 px = 1 grid cell); {@code fixed} keeps the 16×16 unit grid
     * regardless of texture size.</p>
     *
     * @param mode   the gun's {@code texture_scale} setting
     * @param handle the composited texture handle carrying the pixel size
     * @return the horizontal scale factor
     */
    private static float scaleFor(TextureScaleMode mode, DynamicGunTextureCache.TextureHandle handle) {
        return mode == TextureScaleMode.AUTO ? handle.width() / 16.0F : 1.0F;
    }

    /**
     * Computes the vertical geometry scale for the resolved texture, see
     * {@link #scaleFor}. Width and height scale independently so non-square
     * textures are never stretched.
     *
     * @param mode   the gun's {@code texture_scale} setting
     * @param handle the composited texture handle carrying the pixel size
     * @return the vertical scale factor
     */
    private static float scaleForY(TextureScaleMode mode, DynamicGunTextureCache.TextureHandle handle) {
        return mode == TextureScaleMode.AUTO ? handle.height() / 16.0F : 1.0F;
    }

    /**
     * Whether the rendered stack should read its render inputs from the
     * server-pushed sync snapshot.
     *
     * <p>Three conditions must hold: the store holds a snapshot, the rendered
     * stack is the local player's main-hand item, and the snapshot's
     * {@code gunInstanceUuid} and {@code hotbarSlot} both match the stack's
     * own — the slot check is what prevents the stale-snapshot
     * contamination: copied gun stacks share the same uuid, so right after a
     * hotbar switch the store may still hold the <em>previous</em> slot's
     * snapshot for a few ticks until the server's new sync arrives, and
     * without the slot match the new gun would render the previous gun's
     * plugin overlays and outlines (描边污染修复). When any condition fails
     * the caller falls back to the stack's own {@code gun_data} component —
     * the correct data source for inventory GUI slots, dropped items and
     * remote players' guns.</p>
     *
     * @param stack the stack being rendered
     * @return {@code true} when the sync snapshot is authoritative for this
     *         stack
     */
    private static boolean isMainHandSyncMatch(ItemStack stack) {
        ClientGunDataStore store = ClientGunDataStore.getInstance();
        if (!store.hasSyncData() || !isLocalMainHandStack(stack)) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || store.getHotbarSlot() != player.getInventory().selected) {
            return false;
        }
        GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData != null && Objects.equals(gunData.gunInstanceUuid(), store.getGunInstanceUuid());
    }

    /**
     * Whether the rendered stack is the local player's main-hand item.
     *
     * <p>The {@link ClientGunDataStore} snapshot is authoritative only for
     * the local player's main hand. Every other render site — other slots in
     * the inventory GUI, dropped item entities, remote players' third-person
     * models — must read the stack's own {@code gun_data} component instead;
     * otherwise all guns in the inventory would show the main-hand gun's
     * overlays and flicker as the sync data's lifecycle (main-hand switch)
     * clears and repopulates the store.</p>
     *
     * <p>Identity comparison: the vanilla pipeline hands the inventory-held
     * {@link ItemStack} instance to {@link #renderByItem} for both first
     * person (from {@code Player#getMainHandItem}) and GUI slots (from the
     * inventory list), so {@code ==} matches exactly the main-hand stack and
     * nothing else. This check only answers "is the main hand"; whether the
     * snapshot <em>belongs</em> to this stack is {@link #isMainHandSyncMatch}'s
     * job.</p>
     *
     * @param stack the stack being rendered
     * @return {@code true} when the stack is the local player's main-hand item
     */
    private static boolean isLocalMainHandStack(ItemStack stack) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getMainHandItem() == stack;
    }

    /**
     * Determines the uuid of the player whose held item is being rendered.
     *
     * <p>Checks the {@link #RENDERING_PLAYER_UUID} ThreadLocal first (set by
     * a Mixin for third-person remote-player rendering). When unset, falls
     * back to the local player (correct for first-person). When neither is
     * available (e.g. menu rendering), returns {@link #NULL_UUID} which
     * causes {@link ShootTextureResolver} to select the base texture.</p>
     *
     * @return the rendering player's uuid (never {@code null})
     */
    private UUID resolvePlayerUuid() {
        UUID tracked = RENDERING_PLAYER_UUID.get();
        if (tracked != null) {
            return tracked;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null ? minecraft.player.getUUID() : NULL_UUID;
    }
}
