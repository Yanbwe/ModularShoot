# Task Context: M5 - 状态存储系统

Session ID: 2026-06-19-m5-state-storage
Created: 2026-06-19T00:00:00Z
Status: in_progress
Depends on: M1（GunData.state 字段）、M3（BulletSnapshot.state）、M4（GunSyncS2CPacket 同步 state）

## Current Request

实施 M5 里程碑：状态存储系统。M5 范围（设计文档行 2447-2459）：
- modularshoot:states 注册表
- per-gun / per-player / per-bullet 三层状态存储
- GunState / PlayerState 视图 API
- per-gun state 随 GunSyncS2CPacket 同步（含节流策略）
- per-player AttachmentType（含 .sync() 配置）
- per-bullet state 随 BulletS2CPacket 携带初值
- tooltip 状态栏集成
- resolveGunFromSnapshot 回溯工具方法
验证：注册击杀叠层状态，命中致死时计数递增并同步到客户端 tooltip；per-player 状态跨武器保留。

## Context Files (Standards to Follow)

- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md

## Reference Files (Source Material to Look At)

- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（重点：系统十一状态存储系统 行1690-1933）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md
- M1/M2/M3/M4 交付物：GunData（state 字段）、BulletSnapshot（state 字段）、GunSyncS2CPacket、ModularShootRegistries（states 注册表）、ModularShootAPI、tooltip 系统
- 外部源码：C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out、C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1（AttachmentType、IAttachmentSerializer 等）

## External Docs Fetched

无需联网。本地源码可查阅 API。

## Components

M5 功能单元（供 TaskManager 细化）：
1. 状态定义数据结构（StateDefinition record + Codec：domain/value_type/default_value/display）
2. modularshoot:states 注册表 API（Java + 数据包 JSON 加载，M1 已搭建注册表骨架，M5 填充内容）
3. 值类型支持（int/long/double/float/boolean/string/uuid 七种，Codec dispatch）
4. per-gun 状态存储（GunData.state Map<ResourceLocation, Object>，随 NBT 持久化）
5. per-player 状态存储（NeoForge AttachmentType，存玩家 NBT，含 .sync() 同步配置）
6. per-bullet 状态存储（BulletSnapshot.state，随 BulletS2CPacket 携带初值）
7. GunState 视图 API（getInt/setInt/getLong/setLong/getDouble/setDouble/getFloat/setFloat/getBoolean/setBoolean/getString/setString/getUuid/setUuid/hasState/clearState）
8. PlayerState 视图 API（签名与 GunState 一致，归属玩家）
9. BulletSnapshot state 读写 API（getState/setState 泛型方法）
10. per-gun state 同步（随 GunSyncS2CPacket 携带 state 映射）
11. per-gun state 同步节流策略（stateDirty 脏标记 + lastStateSyncTick，节流间隔 2 tick，关键时刻立即同步）
12. per-player AttachmentType 同步（.sync() 配置 IAttachmentSerializer）
13. 未注册/类型不匹配处理（返回零值 + WARN 日志，每状态每分钟一次）
14. resolveGunFromSnapshot 回溯工具方法（gunInstanceUuid→shooter→背包查找）
15. tooltip 状态栏集成（display.priority 排序，hide_default 过滤，per-player 状态混合显示）
16. 保留状态 modularshoot:ammo_damage_type 约定（框架不预置注册，上层模组按约定自行注册）
17. ModularShootAPI.getState(gun)/getState(player) 工具方法

## Constraints

- 框架预置 0 个状态（所有状态由上层模组注册）
- 状态 ID 由上层模组命名（建议自身命名空间，如 examplemod:kill_count）
- per-gun state 高频变化会触发 ItemStack.matches 物品变化检测（安全但开销），建议高频用 per-player
- per-gun 同步节流间隔 2 tick（100ms），关键时刻（安装拆卸/切换主手/加入世界）立即同步
- per-player AttachmentType 必须配置 .sync() 否则不同步
- per-bullet state 不持久化，仅子弹生命周期内
- 读取未注册/类型不匹配返回零值 + WARN（不抛异常）
- 代码风格：模块化、函数式、小函数（<50行）（见 code-quality.md）

## Exit Criteria

- [ ] 状态定义数据结构 + Codec 完成
- [ ] modularshoot:states 注册表 API（Java + 数据包）实现
- [ ] 7 种值类型支持实现
- [ ] per-gun/per-player/per-bullet 三层存储实现
- [ ] GunState/PlayerState 视图 API（类型安全 get/set）实现
- [ ] BulletSnapshot state 读写 API 实现
- [ ] per-gun state 同步（含节流策略）实现
- [ ] per-player AttachmentType 同步实现
- [ ] 未注册/类型不匹配处理实现
- [ ] resolveGunFromSnapshot 回溯工具方法实现
- [ ] tooltip 状态栏集成实现
- [ ] ModularShootAPI.getState 工具方法实现
- [ ] 项目编译通过
- [ ] 验证：击杀叠层计数递增并同步 tooltip；per-player 状态跨武器保留
