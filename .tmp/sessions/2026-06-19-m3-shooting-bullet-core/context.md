# Task Context: M3 - 射击引擎 + 子弹系统（核心）

Session ID: 2026-06-19-m3-shooting-bullet-core
Created: 2026-06-19T00:00:00Z
Status: in_progress
Depends on: M1（属性系统）、M2（插件系统，因射击读取含插件修饰符的最终属性）

## Current Request

实施 M3 里程碑：射击引擎 + 子弹系统（核心）。M3 范围（设计文档行 2416-2430）：
- 左键攻击拦截（AttackEntityEvent / PlayerInteractEvent.LeftClickBlock）
- ShootC2SPacket 射击请求包 + 服务端射速控制（tick 计数）
- 射击时序步骤一至步骤九（含散布算法、属性快照、音效）
- BulletManager（每维度一个，chunk 分桶索引）
- 子弹飞行 + 碰撞检测（胶囊体连续碰撞 + 射线退化）
- 穿透逻辑（实体穿透、方块穿透、去重集合）
- 伤害应用（清零无敌帧 + 正常 hurt()）
- 伤害后处理器链
- 特性运行时钩子（onTick/onHit/onBlockHit/onExpire/onRemove）
- BulletManager.fireBullet() 独立发射
验证：手持枪械左键射击，子弹飞行命中实体造成伤害；穿透子弹命中多目标；方块穿透计数正确；独立发射（炮塔）可用。

## Context Files (Standards to Follow)

- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md

## Reference Files (Source Material to Look At)

- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（重点：系统六射击行为引擎 行707-866、系统七子弹系统 行868-1287、系统十二网络同步 行1936-2060）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md
- M1/M2 交付物（完成后存在）：ModularShootAttributes、GunData、GunDefinition、GunItem、AttributeModifierService、PluginDefinition、ModularShootAPI 等
- 外部源码：C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out、C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1

## External Docs Fetched

无需联网。本地源码可查阅 API。

## Components

M3 功能单元（供 TaskManager 细化）：
1. 左键攻击拦截（AttackEntityEvent + PlayerInteractEvent.LeftClickBlock，主手为枪械时取消原版攻击）
2. ShootC2SPacket 射击请求包（C→S，携带 modifierVersion，基于 CustomPacketPayload）
3. 服务端射速控制（MinecraftServer.getTickCount()，间隔=max(1,round(20/fire_rate))，fire_rate≤0 不射击）
4. 修饰符版本号反作弊（不一致计数器，≥3 判作弊）
5. 射击时序引擎（步骤一至九：拦截→射速→ShootPredicate→PreShootEvent→快照→散布→注册子弹→音效→PostShootEvent）
6. 散布算法（椭圆采样：r=sqrt(random)，θ∈[0,2π)，水平偏转=r×accuracy_yaw×cos(θ)，垂直偏转=r×accuracy_pitch×sin(θ)）
7. BulletSnapshot（stats/traits/damageType/shooter/gunId/gunInstanceUuid/state 映射，运行时可变）
8. BulletRecord（snapshot/shooter/position/direction/traveledDistance/age/bulletId，可修改 position/direction）
9. BulletManager（每维度一个，chunk 分桶索引，int ID 计数器从1单调递增不复用，接近INT_MAX回绕+WARN）
10. 子弹飞行推进（每 tick 按 bullet_speed 沿方向推进，无视重力）
11. 碰撞检测（bullet_size>0 胶囊体连续碰撞；bullet_size=0 射线退化；最近命中语义：方块vs实体取更近）
12. 穿透逻辑（entity_penetration/block_penetration 计数，已穿透实体集合 Set<UUID>、方块集合 Set<BlockPos> 去重）
13. 范围检查（超出 range 触发 onExpire）
14. 未加载区块处理（子弹进入未加载 chunk 直接 onExpire 移除，不强制加载）
15. 伤害应用（清零无敌帧 target.setInvulnerableTicks(0) + 正常 hurt()，保留护甲/抗性/事件）
16. DamageSource 构造（shooter 非 null 用其作 attacker，null 无 attacker）
17. 伤害后处理器链（DamageHandler register，按注册顺序链式执行）
18. 特性运行时钩子（onTick/onHit/onBlockHit/onExpire/onRemove + RemoveReason 枚举 + TraitHookType + registerTraitHook API）
19. BulletManager.fireBullet() 独立发射（炮塔/陷阱，shooter 可 null，gunId/gunInstanceUuid 始终 null）
20. ShootPredicate API（射击条件判断器，failure(reason) 显示原因）
21. PreShootEvent / PostShootEvent 事件
22. GunRightClickEvent（右键 API，未打开容器时触发）
23. 预置伤害类型 modularshoot:bullet_damage（数据驱动 DamageType）

## Constraints

- 子弹不由 Minecraft 实体实现，由 BulletManager 数据记录管理
- BulletManager 每维度（Level/ServerLevel）一个，子弹不跨维度
- 子弹 ID 每维度独立 int 空间，从1单调递增不复用
- chunk 分桶索引：仅对所在 chunk 及相邻 3×3 范围内实体做 raycast
- 射击方向由服务端 getLookAngle() + 散布计算，不信任客户端方向
- 射速控制基于服务端全局 tick 计数，TPS 掉落时实际射速同比例下降
- 伤害走正常 hurt() 流程（不绕过 actuallyHurt），清零无敌帧实现穿透
- 子弹默认不被原版盾牌格挡
- 事件挂在 NeoForge.EVENT_BUS（game bus）
- 网络包基于 RegisterPayloadHandlersEvent + PayloadRegistrar + CustomPacketPayload（非旧版 SimpleChannel）
- 代码风格：模块化、函数式、小函数（<50行）（见 code-quality.md）

## Exit Criteria

- [ ] 左键攻击拦截实现（主手枪械取消原版攻击）
- [ ] ShootC2SPacket 射击请求包实现
- [ ] 服务端射速控制（tick 计数）实现
- [ ] 修饰符版本号反作弊实现
- [ ] 射击时序引擎（步骤一至九）实现
- [ ] 散布算法（椭圆采样）实现
- [ ] BulletSnapshot + BulletRecord 定义
- [ ] BulletManager（每维度+chunk分桶）实现
- [ ] 子弹飞行 + 碰撞检测（胶囊体+射线退化）实现
- [ ] 穿透逻辑（实体/方块穿透+去重）实现
- [ ] 范围检查 + 未加载区块处理实现
- [ ] 伤害应用（清零无敌帧+正常hurt）实现
- [ ] DamageSource 构造实现
- [ ] 伤害后处理器链实现
- [ ] 特性运行时钩子（5个+RemoveReason）实现
- [ ] BulletManager.fireBullet() 独立发射实现
- [ ] ShootPredicate API 实现
- [ ] PreShootEvent/PostShootEvent/GunRightClickEvent 实现
- [ ] 预置伤害类型 modularshoot:bullet_damage 创建
- [ ] 项目编译通过
- [ ] 验证：左键射击命中伤害；穿透多目标；方块穿透计数；独立发射可用
