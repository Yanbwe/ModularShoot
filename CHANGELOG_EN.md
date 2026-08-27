# Changelog

## [0.3.1] - 2026-08-27

### Added

- **Gun definitions can declare an attribute mount**: `attribute_mount` accepts `item` / `player`; player-side guns no longer carry item attribute modifier components, and attribute mounting is delegated to the declaring side.
- **Player-side tooltip attribute bar**: final values are read from the attribute holder; when no holder is available, base values are shown with a "Values based on holder" note.

## [0.3.0] - 2026-08-26

### Breaking Changes

- **Network protocol bumped to 4**: delta positions now travel at double precision, per-bullet stat/trait snapshots ride inline with each bullet (content addressing keeps pure visuals only), and bullet entries gained a third-party extension field. 0.3.0 is not interoperable with older clients/servers; both sides must update together.
- **Unified query return types**: `getGunId` and `getState` now return `Optional` (consistent with the other queries) instead of `null`.
- **Pre-install event enhancement**: the plugin install pre-event now carries the framework-selected slot type and supports listener-supplied custom cancellation reasons (constructor change).

### Added

- **Plugin Java registration and dynamic providers**: symmetric to the gun side, plugin definitions can now be registered programmatically (random loot affixes and similar gameplay are no longer a dead end).
- **Bullet sync extension channel**: third parties can attach custom bytes to every bullet and read them from the client render object (custom rotation/orientation fields no longer need bespoke networking).
- **Tunable bullet sync**: distance bands, per-band update frequency and the full-sync interval moved into `modularshoot-common.toml` so servers can trade bandwidth to taste.
- **Dedicated "plugin definition missing" install error**: a missing datapack entry after reload no longer misreports as "no slot".
- **Extension-point catalogue in the facade javadoc**: every registration/event/hook entry listed by subsystem for one-stop discovery.

### Changed

- **Trait hooks dispatch only for bullets carrying their trait**: removes the misleading "register once, self-filter per bullet" semantics and cuts dispatch overhead under heavy bullet counts.
- **Idempotent predicate/effect registration**: re-registering the same instance no longer stacks it.
- **Outline tint registration is thread-safe**: GPU texture release is deferred to the render thread when registered elsewhere.
- **Batch-uninstall event docs corrected**: attribute refresh is deferred to the end of a batch.
- **Binding index re-registration repairs stale entries**: moving a key to a different item cleans the old reverse index.
- **Facade class javadoc corrected**: it describes the full framework surface (previously claimed plugins only).
- **Two duplicated item-binding indexes collapsed into one generic implementation**.
- **Render texture cache keys reused across frames** instead of rebuilding and re-hashing per draw.

### Fixed

- **Ammo damage-type presets no longer silently ignored**: presets written through the state system are now read by the shooting pipeline.
- **Corrupted/malicious bullet sync packets can no longer trigger giant allocations**: entry counts and extension lengths are bounded on decode.
- **Far-coordinate bullet precision**: incremental positions no longer degrade to float, removing drift beyond |16M| and the first-delta position step.
- **Cleared state no longer shows stale values on the client**: clears now flag the throttled sync too.
- **Corrupted/retyped state data no longer crashes tooltip and render reads**: decode failures degrade to zero values with rate-limited warnings.
- **State default-value type drift**: a JSON default for `value_type: float` is no longer decoded as a double and read back as zero.
- **Datapack error-handling docs corrected**: they now state that a single bad JSON aborts the whole registry load (vanilla pipeline behaviour).

### Testing

- Full suite: **694** tests pass (21 new: sync extension channel, plugin Java registration, damage preset reads, decode defence, binding index repair, default coercion, hook filtering).

## [0.2.0] - 2026-08-14

### Breaking Changes

- **Removed 5 deprecated `ModularShootAPI` overloads**: the explicit `RegistryAccess` uninstall overloads and the non-refreshing `setPluginLocked` have been removed. Use the recommended player-derived overloads, or call `PluginUninstallService` / `PluginLockService` directly.
- **Removed `BulletManager#fireBullet`**: use `ModularShootAPI.fireBullet` or the new `CreationCoordinator` for standalone firing.
- **Network protocol bumped to 3**: 0.2.0 is not interoperable with older clients/servers; both sides must update together.

### Performance

- **Cached registry lookups everywhere**: hot paths no longer re-resolve gun/plugin/state definitions repeatedly, speeding up shooting, install/uninstall and tooltips.
- **Much lighter bullet sync**: spatial indexing, position quantization and distance-based downsampling, plus content-addressed full sync and gun-state diffs, cut bandwidth and CPU on populated servers.
- **Faster state handling**: same-value writes short-circuit, fast NBT codec paths, and player-state throttling reduce copies and sync traffic for high-frequency states.
- **Leaner rendering and tooltips**: per-frame tooltip caching, reverse indexes, cached texture-key hashes, lazy outline masks and render-object reuse lower per-frame cost.
- **Faster reloads**: shared registry snapshots and cached modifier results prevent repeated full-table recomputation on large packs.
- **Batched broadcasts**: hit and firing-animation updates are merged per tick, reducing packet counts and player scans.

### Architecture

- **One-way public API**: item recognition moved into an internal utility; internal services no longer depend on the facade.
- **Cleaner client/server split**: client payload handling moved into the client package; common code no longer imports Minecraft client classes.
- **Clearer responsibilities**: the bullet manager was split into storage, creation and network-marking layers; the fire-rate formula is now a single shared source of truth.

### Robustness

- Added empty-handler short-circuits, throttled error logging, defensive packet decoding, no-op state-clear short-circuits and cache lifecycle cleanup.

### Testing

- Full suite: **673** tests pass.

## [0.1.7] - 2026-08-14

### Added

- **Install API supports a preferred plugin category**: `installPlugin` gained an optional preferred-type parameter (e.g. a plugin panel dragging a plugin onto a category area). A matching hint installs directly into that category; a miss falls back to the original auto-selection. Existing callers are unaffected.
- **Effective-slot query facade**: new `getEffectiveSlots` API returns each category's effective capacity (including installed plugins' `adds_slots`), so UIs can render install areas without re-implementing the aggregation.

### Testing

- 14 new tests cover hint hit / miss / full-slot fallback and full-pipeline conduction (including facade forwarding); all 452 pass.

## [0.1.6] - 2026-08-13

### Performance

- **Faster item recognition and binding lookups**: hot paths like inventory scans no longer rebuild lookup tables on every call — a notable per-tick saving on populated servers.
- **Deduplicated state writes**: writing an unchanged value no longer triggers data copies or sync traffic, so high-frequency states (heat accumulation, etc.) stop generating pointless packets.
- **Smaller bullet sync packets**: incremental sync entries are about half the size; bandwidth drops noticeably when many bullets are in flight.
- **Cheaper shotgun / multi-pellet shots**: the variant pool is built once per shot; pellets only roll independently — no more repeated registry lookups per pellet (the bullet-visual registry scan is also cached per registry instance).
- **Batch uninstall recomputes attributes once**: removing several plugins at once recomputes attributes a single time instead of once per plugin.
- **More reliable texture caching**: dynamic texture cache keys now compare values, so plugin reloads or resource refreshes never trigger unnecessary texture recomposition.
- **Leaner rendering**: per-frame / per-tick temporary allocations (bullet sorting, snapshot reads) eliminated.

### Fixed

- **Bullets could briefly disappear after a dimension switch**: sync state is now reset on dimension change instead of relying on the periodic full sync seconds later.
- **Stale plugin visuals after a resource reload**: F3+T now also clears the overlay cache, keeping visuals consistent with the resource packs.
- **Deterministic binding resolution**: when several Java-API bindings register the same item, the lexicographically smallest entry key now wins — the same rule as the datapack channel — instead of an unspecified scan order.
- **Hit-packet encoding fix**: non-entity hits no longer write the -1 sentinel as a varint (a negative varint is actually larger); a presence flag plus conditional varint keeps both entity and non-entity hits at or below the old fixed-width size.
- **Shoot sounds fade out too early at high speed**: shoot and plugin-install sounds now follow the player, so fast movement no longer leaves them behind at the trigger point.

### Testing

- 18 new tests covering binding indexes, cache-key value semantics, wire-compression precision, LRU eviction policy and the single-refresh batch uninstall; all 438 pass.

## [0.1.5] - 2026-08-11

### Fixed

- **Dedicated server crash**: fixed a `NoClassDefFoundError` crash when resolving gun/plugin item display names on a dedicated server (paths like the `/modularshoot stats` command and death messages are protected).
- **Plugin bare-key namespace drift**: bare `traits` / `adds_variants` keys in plugin definitions now land in the `modularshoot` namespace, matching guns — same-name traits and variants between a gun and its plugins now merge correctly.
- **Zero-speed bullets never expire**: bullets with a speed of 0 are now removed immediately as expired instead of lingering forever.
- **Reload validation false positives**: a plugin whose tags only partially match is no longer warned as "cannot be installed on any gun"; valid bindings pointing at Java-API-registered guns are no longer reported as "not found".
- **First-person recoil direction fix**: the muzzle was pressed down — now corrected to a slight rise (kick back into the screen + muzzle rise toward the viewer).

### Improved

- **Uninstall overflow precheck tests**: added 10 automated tests covering the 0.1.4 overflow precheck and random-uninstall candidate filtering.
- **Robustness**: edge cases such as invalid damage types, corrupted state data, slot-count integer overflow and shared mutable references no longer cause crashes or lock-ups.
- **Consistency**: uninstall now respects listener mutations; tooltip plugin-bar counts match the overflow rules; rate-limit state is cleaned up on logout; reload validation now covers the binding tables and the shooters summary.
- **Rendering**: 3D bullet lighting now uses the interpolated position, more accurate at high speed.
- **Cleanup**: removed 8 pieces of dead code and fixed 7 outdated javadocs.
- **Tests**: every shipped datapack JSON is now decoded by its real codec (45 files); `BulletS2CPacket` codec round-trip tests added; 414 tests in total.
- **Localization**: tooltip count parentheses and the lock anchor character are now lang keys — no more CJK punctuation in en_us.

## [0.1.4] - 2026-08-10

### Added

- **Dynamic gun definition providers**: `GunRegistry` gains a `registerGunDefinitionProvider` channel, extending the `getGun` lookup order to Java API registration → provider → datapack registry. This enables per-player / runtime-generated dynamic definitions (e.g. "the player is the gun" gameplay), benefiting every in-framework definition lookup site with zero changes. The facade `ModularShootAPI.registerGunDefinitionProvider` exposes this externally; behavior is completely unchanged when no provider is registered.
- **Plugins support the `adds_slots` field**: after installation a plugin can add a specified number of slots, or even create slot types the gun did not originally have (keys must be fully namespaced or bare `modularshoot:` keys; values are arbitrary integers, negative ones occupying slots). Effective capacity = gun slots + aggregated `adds_slots` of installed plugins; install matching and the tooltip plugin bar are both computed with effective slots.
- **Uninstall overflow pre-check**: a non-force uninstall that would leave any slot type overflowing after removal is rejected with `WOULD_OVERFLOW` (not limited to the slot-adding plugin itself — residual overflow after uninstall is refused); force uninstall bypasses this, leaving the overflowing plugin in place but that slot can no longer accept plugins; random uninstall automatically filters out candidates that would trigger overflow.
- **reload validation**: `adds_slots` keys are checked (WARN) for existence against the `plugin_types` registry.

## [0.1.3] - 2026-08-08

### Added

- **Gun definitions support the `extra_values` field**: `GunDefinition` gains a namespaced numeric extension field (keys must be fully namespaced, consistent with the plugin side); `ModularShootAPI.getExtraValueSums` / `getExtraValue` semantics are upgraded to a one-stop sum of "gun definition base values + cumulative values of installed plugins" (when the gun definition is invalid its base values are excluded, consistent with the plugin degradation policy). Example: `blood_sword` declares a `modularshoot:demo_rarity` base value of 10.

### Fixed

- **Fixed bullet flight visual bounce-back**: `BulletRenderDispatcher` now reads the clock once per frame and shares it across all bullets rendered in that frame (`TimeBasedInterpolationTest`).

- **Fixed square bullet texture transparency blocking bullets behind**: the billboard RenderType write mask is changed from the default `COLOR_DEPTH_WRITE` to `COLOR_WRITE` (depth read-only, no write).

## [0.1.2] - 2026-08-08

### Changed

- **Removed framework default hit particles**: `ClientHitEffectHandler` no longer spawns any default particles (damage indicator `DAMAGE_INDICATOR` / crit `CRIT` / block breaking); hit visual effects are now entirely up to `ClientBulletHitEvent` listeners; data-driven hit sounds (gun `sounds` slots) are kept, and cancelling the event still skips the default sound. Related javadoc (`ClientBulletHitEvent`, `ModularShootPayloads`) updated accordingly.

### Improved

- Redrawn the default bullet texture (`textures/bullet/default.png`) for a much better look.

## [0.1.1] - 2026-08-08

### Added

- **First-person firing animation**: new `FirstPersonRecoilKick` recoil curve — at the moment of firing the gun body pulls back along +X (up to 0.2 blocks) and the muzzle rises around +Z (up to 8°), then quickly settles; driven by `shootAnimTimer`, triggered in sync with the third-person arm recoil pose and `per_shot` firing textures (`GunItemRenderer`, `FirstPersonRecoilKickTest`).
- Shooting predicate failure messages and `StateDisplay.format` now support `lang:` prefixed keys (localization mechanism enhancement).

### Improved

- **Full localization**: prompt texts (tooltip titles, key hints, plugin rows/separators, downgrade display, ~30 spots in total) and the feedback of all 5 command subcommands converted to lang keys (`modularshoot.command.*`).
- The `name` / `brief` / `description` / `display` fields in the 31 example datapack JSONs converted to `lang:` keys, with `§` color codes moved into lang values; `en_us` / `zh_cn` each gain 95 keys (55 → 150), and example content fully translated to English.
- `DatapackLoadSummary` / `DatapackReloadListener` / `ItemBindingValidator` logs switched to English.
