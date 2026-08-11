# Changelog

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
