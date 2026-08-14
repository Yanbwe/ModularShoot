# Changelog

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
