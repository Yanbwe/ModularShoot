# Task Context: M4 - 网络同步 + 渲染

Session ID: 2026-06-19-m4-network-rendering
Created: 2026-06-19T00:00:00Z
Status: in_progress
Depends on: M3（射击引擎+子弹系统，需同步子弹数据）

## Current Request

实施 M4 里程碑：网络同步 + 渲染。M4 范围（设计文档行 2432-2445）：
- BulletS2CPacket（批量子弹同步）+ BulletHitS2CPacket（命中特效）
- GunSyncS2CPacket（插件列表 + modifierVersion + state）
- 客户端 BulletRenderManager + BulletRenderObject
- RenderLevelStageEvent 渲染（billboard + 3d 模式）
- 位置插值平滑
- 枪械纹理立体化 + 射击纹理切换（per_shot / while_firing + isFiring 标记）
- 插件纹理叠加合成
- 第三人称射击动画 Mixin + 动画计时器
- 修饰符版本号反作弊（若 M3 未完成部分）
验证：射击后客户端可见子弹飞行；命中播放特效；第三人称可见射击动作；while_firing 模式低射速不闪烁；反作弊触发日志。

## Context Files (Standards to Follow)

- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md

## Reference Files (Source Material to Look At)

- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（重点：系统七子弹视觉样式 行1189-1287、系统八纹理与渲染 行1289-1363、系统十二网络同步 行1936-2060）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md
- M1/M2/M3 交付物：BulletManager、BulletRecord、BulletSnapshot、GunItem、GunDefinition、PluginDefinition 等
- src/main/resources/modularshoot.mixins.json（mixin 配置，M4 需添加第三人称动画 mixin）
- 外部源码：C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out、C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1

## External Docs Fetched

无需联网。本地源码可查阅 API（RenderLevelStageEvent、HumanoidModel、LivingEntityRenderer、CustomPacketPayload 等）。

## Components

M4 功能单元（供 TaskManager 细化）：
1. BulletS2CPacket（S→C，子弹ID列表/位置/方向/速度/视觉样式ID，每 tick 批量，按渲染距离裁剪）
2. BulletHitS2CPacket（S→C，子弹ID/命中位置/命中类型/命中实体ID，命中时触发）
3. GunSyncS2CPacket（S→C，installedPlugins列表/modifierVersion/state映射，切换主手/安装拆卸/加入世界/状态修改后触发）
4. 数据包注册（RegisterPayloadHandlersEvent + PayloadRegistrar + CustomPacketPayload 体系）
5. 客户端 BulletRenderManager（单例，通过 BulletManager.getClientLevel(level) 获取，管理 BulletRenderObject）
6. BulletRenderObject（bulletId/position/prevPosition/direction/视觉样式/scale，非 Entity）
7. RenderLevelStageEvent 渲染（AFTER_PARTICLES/AFTER_WEATHER 阶段，遍历 BulletRenderObject 绘制）
8. billboard 渲染模式（始终面向相机的 quad，自定义 RenderType，深度测试/透明混合/无光照）
9. 3d 渲染模式（原版静态 JSON 模型，BlockModel/ModelResource，按 direction 旋转）
10. 位置插值平滑（prevPosition 与 position 按 partialTick 插值）
11. 短寿命子弹保证（创建 tick 末强制同步一次完整包）
12. 子弹视觉样式优先级（枪械定义→插件覆盖→onVisualTick 钩子，整体覆盖语义）
13. 特性视觉钩子 onVisualTick（仅客户端，每帧渲染前，含 BulletRenderObject 参数，TraitHookType.ON_VISUAL_TICK）
14. 枪械纹理立体化（原版物品渲染管线，自动处理）
15. 射击纹理切换 per_shot 模式（每次射击切换，单发后切回）
16. 射击纹理切换 while_firing 模式（按住左键持续显示，松开切回，依赖 isFiring 标记）
17. isFiring 标记维护（本地玩家：客户端检测左键+主手枪械；其他玩家：服务端同步）
18. shootAnimTimer 动画计时器（本地玩家零延迟置峰值，其他玩家服务端同步，每tick衰减）
19. 插件纹理叠加合成（按层级从低到高排序，同层级按安装顺序，2D 图层合成）
20. 第三人称射击动画 Mixin（注入 HumanoidModel/LivingEntityRenderer，timer>0 时强制双臂弩拉弦姿态，复用原版弩关键帧角度插值）
21. 客户端射击请求发送（检测左键按住+主手枪械，每 tick 发 ShootC2SPacket，松开停止）
22. 第三人称玩家射击动作（原版弩射击动作，第一人称无手臂动作）

## Constraints

- 纯服务端权威，客户端仅表现层（不做客户端预测）
- 客户端不创建 Minecraft 实体，用 BulletRenderObject（非 Entity，不进 level.getEntities()）
- 渲染对象不走 EntityType 注册流程
- 同步范围：仅玩家渲染距离内的子弹
- 同步频率：每 tick 一次
- 网络包基于 RegisterPayloadHandlersEvent + PayloadRegistrar + CustomPacketPayload
- Mixin 配置在 modularshoot.mixins.json，package=org.yanbwe.modularshoot.mixin
- 第三人称动画 Mixin 降级：加载失败则动画不播放，不影响射击逻辑
- isFiring 标记独立于动画计时器（不复用，避免低射速闪烁）
- 代码风格：模块化、函数式、小函数（<50行）（见 code-quality.md）

## Exit Criteria

- [ ] BulletS2CPacket 批量同步实现
- [ ] BulletHitS2CPacket 命中特效实现
- [ ] GunSyncS2CPacket 枪械数据同步实现
- [ ] 数据包注册（PayloadRegistrar 体系）实现
- [ ] 客户端 BulletRenderManager + BulletRenderObject 实现
- [ ] RenderLevelStageEvent 渲染（billboard+3d）实现
- [ ] 位置插值平滑实现
- [ ] 短寿命子弹强制同步实现
- [ ] 子弹视觉样式优先级实现
- [ ] onVisualTick 视觉钩子实现
- [ ] 射击纹理切换（per_shot + while_firing）实现
- [ ] isFiring 标记维护实现
- [ ] shootAnimTimer 动画计时器实现
- [ ] 插件纹理叠加合成实现
- [ ] 第三人称射击动画 Mixin 实现
- [ ] 客户端射击请求发送实现
- [ ] 项目编译通过
- [ ] 验证：射击后客户端可见子弹；命中特效；第三人称射击动作；while_firing 不闪烁
