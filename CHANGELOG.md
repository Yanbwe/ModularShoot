# 更新记录 / Changelog

## [0.1.5] - 2026-08-11

### 修复 / Fixed

- **专用服务器崩溃**：修复专用服务器上枪械/插件物品名称解析抛 `NoClassDefFoundError` 崩服的问题（`/modularshoot stats` 等命令、死亡消息等路径均受保护）。
- **插件裸键命名空间漂移**：插件定义的 `traits`/`adds_variants` 裸键与枪械一致归入 `modularshoot` 命名空间，枪械与插件同名的特性/变体现在可以正常合并。
- **零速子弹永不消亡**：速度为 0 的子弹不再永久驻留，立即按过期移除。
- **reload 校验误报**：插件标签仅部分未匹配时不再误报"装不上任何枪"；指向 Java API 注册枪械的合法绑定不再误报"未找到"。
- **第一人称后坐方向修正**：枪口下压修正为微微上抬（后收进屏幕 + 枪口朝观察者上抬）。

### 改进 / Improved

- **卸载超编预检测试**：0.1.4 的超编预检与随机卸载候选过滤补 10 条自动化测试。
- **健壮性**：非法伤害类型、损坏状态数据、槽位数值溢出、共享可变引用等边界情况不再导致崩溃或异常。
- **一致性**：卸载流程尊重事件监听器的修改；tooltip 插件栏计数与超编判定口径统一；登出清理节流表；reload 校验补绑定表与 shooters 汇总。
- **渲染**：3D 子弹光照改用插值位置，高速移动时更准确。
- **清理**：删除 8 处死代码，修正 7 处过时 javadoc。
- **测试补强**：出厂数据包 JSON 全量解码断言（45 个文件）；`BulletS2CPacket` 编解码往返测试；全量 414 用例。
- **本地化补强**：tooltip 计数括号与锁定锚字符改为 lang 键，en_us 下不再显示 CJK 标点。

## [0.1.4] - 2026-08-10

### 新增 / Added

- **动态枪械定义提供者**：`GunRegistry` 新增 `registerGunDefinitionProvider` 通道，`getGun` 查询顺序扩展为 Java API 注册 → 提供者 → 数据包注册表；支持 per-player / 运行时生成的动态定义（"玩家即枪"类玩法），框架内所有定义查询点零改动受益。门面 `ModularShootAPI.registerGunDefinitionProvider` 对外暴露；未注册提供者时行为完全一致。
- **插件定义支持 `adds_slots` 字段**：插件安装后可增加指定槽位数量，甚至创造枪械原本没有的槽位类型（键须为完整命名空间或 `modularshoot:` 裸键，值为任意整数，负数表示占用槽位）。有效容量 = 枪械 slots + 已装插件 adds_slots 聚合；安装匹配、tooltip 插件栏均按有效槽位计算。
- **卸载超编预检**：非 force 卸载若会导致任意槽位类型在拆卸后仍超编（不限于加槽插件本身——卸载后残留超编即拒绝，返回 `WOULD_OVERFLOW`）；force 卸载可绕过，超编插件滞留保留但该槽位不可再装；随机卸载自动过滤会触发超编的候选。
- **reload 校验**：`adds_slots` 键引用 `plugin_types` 注册表存在性检查（WARN）。

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
