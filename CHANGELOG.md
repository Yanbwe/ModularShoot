# 更新记录 / Changelog

## [0.1.1] - 2026-08-08

### 新增 / Added

- **第一人称发射动画**：新增 `FirstPersonRecoilKick` 后坐曲线，开火瞬间枪身沿 +X 后收（上限 0.2 格）、枪口绕 +Z 上抬（上限 8°），随后快速回落；由 `shootAnimTimer` 驱动，与第三人称手臂后坐姿势、`per_shot` 射击纹理同节奏触发（`GunItemRenderer`、`FirstPersonRecoilKickTest`）。
- 射击谓词失败消息与 `StateDisplay.format` 支持 `lang:` 前缀键（本地化机制补强）。

### 改进 / Improved

- **全量本地化**：提示文本（tooltip 标题、快捷键提示、插件行/分隔符、降级显示等约 30 处）、5 个命令子命令反馈全部改为 lang 键（`modularshoot.command.*`）。
- 31 个示例数据包 JSON 的 `name` / `brief` / `description` / `display` 改为 `lang:` 键，`§` 颜色码移入 lang 值；`en_us` / `zh_cn` 各新增 95 键（55 → 150），示例内容英文全量翻译。
- `DatapackLoadSummary` / `DatapackReloadListener` / `ItemBindingValidator` 日志英文化。
