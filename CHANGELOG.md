# 更新记录 / Changelog

## [0.1.3] - 2026-08-08

### 新增 / Added

- **枪械定义支持 `extra_values` 字段**：`GunDefinition` 新增命名空间数值扩展字段（键须带完整命名空间，与插件侧一致）；`ModularShootAPI.getExtraValueSums` / `getExtraValue` 语义升级为"枪械定义基础值 + 已安装插件累计值"的一站式总和（枪械定义失效时基础值不参与，与插件降级口径一致）。示例：`blood_sword` 声明 `modularshoot:demo_rarity` 基础值 10。

### 修复 / Fixed

- **修复子弹飞行视觉回弹**：`BulletRenderDispatcher` 每帧统一读取一次时钟供全帧子弹共用（`TimeBasedInterpolationTest`）。

- **修复子弹方形纹理透明区域遮挡后方子弹**：billboard 渲染的 RenderType 写掩码由默认 `COLOR_DEPTH_WRITE` 改为 `COLOR_WRITE`（深度只读不写）。

## [0.1.2] - 2026-08-08

### 变更 / Changed

- **移除框架默认命中粒子**：`ClientHitEffectHandler` 不再生成任何默认粒子（伤害指示 `DAMAGE_INDICATOR` / 暴击 `CRIT` / 方块破坏），命中视觉特效完全交由 `ClientBulletHitEvent` 监听方自行实现；数据驱动命中音效（枪械 `sounds` 槽位）保留，取消事件仍可跳过默认音效。相关 javadoc（`ClientBulletHitEvent`、`ModularShootPayloads`）同步更新。

### 改进 / Improved

- 重绘子弹默认纹理（`textures/bullet/default.png`），观感大幅提升。

## [0.1.1] - 2026-08-08

### 新增 / Added

- **第一人称发射动画**：新增 `FirstPersonRecoilKick` 后坐曲线，开火瞬间枪身沿 +X 后收（上限 0.2 格）、枪口绕 +Z 上抬（上限 8°），随后快速回落；由 `shootAnimTimer` 驱动，与第三人称手臂后坐姿势、`per_shot` 射击纹理同节奏触发（`GunItemRenderer`、`FirstPersonRecoilKickTest`）。
- 射击谓词失败消息与 `StateDisplay.format` 支持 `lang:` 前缀键（本地化机制补强）。

### 改进 / Improved

- **全量本地化**：提示文本（tooltip 标题、快捷键提示、插件行/分隔符、降级显示等约 30 处）、5 个命令子命令反馈全部改为 lang 键（`modularshoot.command.*`）。
- 31 个示例数据包 JSON 的 `name` / `brief` / `description` / `display` 改为 `lang:` 键，`§` 颜色码移入 lang 值；`en_us` / `zh_cn` 各新增 95 键（55 → 150），示例内容英文全量翻译。
- `DatapackLoadSummary` / `DatapackReloadListener` / `ItemBindingValidator` 日志英文化。
