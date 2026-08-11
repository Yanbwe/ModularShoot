# 更新记录 / Changelog

## [0.1.5] - 2026-08-11

### 修复 / Fixed

- **专用服务器崩溃**：`GunItem`/`PluginItem` 的 `getName` 无条件调用客户端包 `ItemNameResolver`（引用 `Minecraft`），专用服务器上抛 `NoClassDefFoundError`（`catch(Exception)` 捕不住）直接崩服——入口改为按物理端守卫（`FMLEnvironment.dist.isDedicatedServer()` 直接回退），客户端类引用不再被触达（命令 `/modularshoot stats`/`variants`/`debug on`、死亡消息等路径均受保护）。
- **插件裸键命名空间漂移**：`PluginDefinition` 的 `traits`/`adds_variants` 裸键落 `minecraft:` 命名空间，与枪械侧 `modularshoot:`（`SharedKeyCodecs.MODULARSHOOT_KEY`）合并契约静默失效（枪械固有特性压过插件的语义被破坏、variants 权重不合并）——两处 codec 统一为 `MODULARSHOOT_KEY`。
- **零速子弹永不消亡**：冻结 `bullet_speed ≤ 0` 的子弹 `traveledDistance` 恒 0、范围检查永假、无 age 兜底，永久驻留 BulletManager 空跑每 tick hook/广播——管线新增静止过期检查（位置推进前立即按 EXPIRED 移除，onExpire 先触发）。
- **BulletSnapshot 死代码与文档失实**：`encodeState`/`decodeState` 声称"state 初始值经 BulletS2CPacket 传客户端"，实际 `ClientBulletSnapshot` 刻意不下发 state（设计文档 §子弹快照）、两方法无调用者——删除死代码，javadoc 明确 state 仅服务端可见。
- **reload 校验误报**：`CrossReferenceValidator` 插件 tag 只要有一个未匹配就 WARN 且断言"装不上任何枪"（部分匹配误报）——改为仅全部 tag 均无交集才 WARN；`ItemBindingValidator` 的 "Bound gun not found" 只对照 datapack 键集，指向 Java API 注册枪械的合法绑定每次 reload 误报——已知集合改为 datapack 键 ∪ Java API 注册键（provider 通道不可枚举、不含）。
- **第一人称后坐方向修正**：`renderByItem` 收到的是相机坐标系（+X=屏幕右、+Y=上、+Z=指向观察者），原 +X 平移 + 绕 +Z 旋转实测表现为**枪口下压**——改为沿 -Z 后收入屏 + 绕 +X（屏幕左右水平轴）正旋转（枪口顶端朝观察者抬起），javadoc/设计文档同步。

### 改进 / Improved

- **卸载超编预检测试补全**：`preflightReason`/`isRandomCandidate` 决策纯函数化并补 10 条测试（非 force 拒绝 WOULD_OVERFLOW / force 绕过 / 随机候选过滤 / `removalCausesOverflow` stub 注册表集成），0.1.4 三条最易错路径首次有自动化断言。
- **健壮性**：`resolveDamageType` 非法 state 串不再抛未检查异常（tryParse + 降级回退）；`decodeStateMap` 损坏 NBT 键跳过并 WARN（与 unregistered 降级哲学一致）；`EffectiveSlotService` 槽位聚合改饱和加法（防 int 回绕锁死槽位）；`AttachLayerModifier` 默认 tint 防御性拷贝（防共享可变引用全局污染）。
- **一致性**：卸载路径 pre-event 后重读组件（对齐安装路径，监听器改动不再被静默抹掉）；tooltip 插件栏已装计数与超编判定统一按 installedTypeId 全量计数；`GunDegradationHandler` WARN 节流表登出清理（防缓慢内存泄漏）；`checkRegistrationConflicts` 补 `gun_items`/`plugin_items`；reload 汇总补 shooters 行。
- **渲染**：3D 子弹光照改用插值位置（不再滞后一个同步段）。
- **清理**：删除死代码（`checkGunTextures`、`DegradationTextures`、空 `BLOCKS` DeferredRegister、`AntiCheatState.baselineVersion`、`InstallResult.failure(String)`、不可达 `definition_not_found` 分支、`clearCache()`）与 6 处失实 javadoc（注册表计数、命令线程模型、挥臂 Mixin、standalone 变体、nextLong 计数等）。
- **测试补强**：出厂数据包 JSON 全量真实 codec 解码断言（9 张表 45 个文件，codec 映射覆盖 10 张注册表）；`BulletS2CPacket` codec 往返测试（8 条：null 哨兵/枚举 ordinal/三桶/forceFullSync）；全量 414 用例。
- **本地化补强**：tooltip 插件栏的全角括号计数（`（x/y）`）与锁定锚字符 `⚓` 由硬编码改为 lang 键（`modularshoot.tooltip.slot_count` / `locked_anchor`），en_us 下不再显示 CJK 标点。

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
