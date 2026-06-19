# Task Context: M6 - 数据包注册 + 降级处理 + 调试命令

Session ID: 2026-06-19-m6-datapack-degradation-debug
Created: 2026-06-19T00:00:00Z
Status: in_progress
Depends on: M1-M5（所有系统就绪后做数据包格式完善、降级、调试命令、扩展点）

## Current Request

实施 M6 里程碑：数据包注册 + 降级处理 + 调试命令。M6 范围（设计文档行 2461-2472）：
- 全部数据包 JSON 格式（枪械/插件/种类/特性/状态/属性元数据）
- 注册冲突与覆盖规则
- /reload 重载行为
- 定义丢失降级（枪械/插件/种类/属性/状态）
- 数据包 JSON 加载失败错误处理
- /modularshoot 调试命令（stats/plugins/gun/plugin/bullets/debug）
- ShootPredicate + ReloadEvent + GunRightClickEvent 扩展点
验证：数据包注册枪械/插件可用；/reload 后定义变更生效；卸载模组后旧物品降级不崩溃；调试命令输出正确。

## Context Files (Standards to Follow)

- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md

## Reference Files (Source Material to Look At)

- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（重点：数据包注册章节 行2078-2384、调试命令 行2063-2075、系统十弹药系统API 行1600-1686）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md
- M1-M5 交付物：所有注册表 API、GunDefinition、PluginDefinition、PluginTypeDefinition、AttributeMeta、StateDefinition、TraitDefinition、ModularShootAPI、ShootPredicate、PreShootEvent/PostShootEvent、GunRightClickEvent 等
- 外部源码：C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out、C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1（Command API、ArgumentTypes 等）

## External Docs Fetched

无需联网。本地源码可查阅 API。

## Components

M6 功能单元（供 TaskManager 细化）：
1. 枪械数据包 JSON 格式完善（name/texture/shoot_texture/shoot_texture_mode/stats/traits/slots/sounds/bullet_style，路径 data/<ns>/modularshoot/guns/<id>.json）
2. 插件数据包 JSON 格式完善（tags/priority/item_icon/modifiers/traits/exclusive_group/bullet_style/texture_overlay/name/brief/description/color，路径 data/<ns>/modularshoot/plugins/<id>.json）
3. 插件种类数据包 JSON 格式完善（tags/priority/name/color，路径 data/<ns>/modularshoot/plugin_types/<id>.json）
4. 属性元数据数据包 JSON 格式完善（binds/default_value/description/color/priority/force_show，路径 data/<ns>/modularshoot/attribute_meta/<id>.json）
5. 自定义特性数据包 JSON 格式完善（default_value/description/name/color/brief/force_show/priority，路径 data/<ns>/modularshoot/traits/<id>.json）
6. 状态数据包 JSON 格式完善（domain/value_type/default_value/display，路径 data/<ns>/modularshoot/states/<id>.json）
7. 注册冲突与覆盖规则（Java API 优先 > 数据包按 pack.mcmeta 优先级 > 不支持数据包覆盖 Java API）
8. 加载顺序（框架预置→Java API→数据包 JSON）
9. /reload 重载行为（新数据包覆盖同 ID；物品实例 Data Component 字段不变；行为定义始终读当前注册表；创造标签页用新定义）
10. 枪械 gunId 失效降级（不崩溃/灰色未知枪械名/兜底纹理/射击静默失败+WARN/tooltip降级/修饰符不挂载）
11. 插件 pluginId 失效降级（修饰符忽略/特性忽略/tooltip灰色失效插件/可拆卸不返还）
12. 种类定义丢失降级（installedTypeId 失效插件仍保留/修饰符仍生效/tooltip未知种类分组/定义恢复后自动归位）
13. 属性元数据 binds 失效降级（条目保留/修饰符不挂载/读取兜底0/tooltip不显示/定义恢复后自动恢复）
14. 状态 ID 失效降级（值保留但不可读/读取返回零值+WARN/tooltip不显示/定义恢复后可读写）
15. 数据包 JSON 加载失败错误处理（单条解析失败跳过+ERROR日志+不影响其他；引用失效+WARN；资源缺失兜底+WARN；汇总日志）
16. /modularshoot 调试命令（stats/plugins/gun/plugin/bullets/debug 子命令，op 权限 modularshoot.command）
17. ShootPredicate 扩展点完善（M3 已基础实现，M6 完善 failure(reason) 动作栏显示）
18. ReloadEvent 事件（R 键触发，框架仅触发不处理换弹逻辑）
19. GunRightClickEvent 完善（M3 已基础实现，M6 确认未打开容器时触发）
20. key.modularshoot.reload 键位绑定（默认 R 键，独立 modularshoot 分类标签）
21. 数据包 JSON 示例（完整可用的示例数据包供验证）

## Constraints

- 数据包 JSON 路径遵循 data/<命名空间>/modularshoot/<类型>/<id>.json
- 注册冲突：Java API 优先，数据包按 pack.mcmeta 优先级，不支持数据包覆盖 Java API
- 降级机制：所有失效场景不崩溃，定义恢复后自动恢复正常
- 单条 JSON 解析失败隔离（try-catch），不影响其他条目
- 调试命令需 op 权限（modularshoot.command 权限节点）
- ReloadEvent 框架仅触发不处理换弹逻辑（上层模组监听实现）
- 键位绑定默认 R 键，独立分类标签，玩家可自行调整冲突
- 代码风格：模块化、函数式、小函数（<50行）（见 code-quality.md）

## Exit Criteria

- [ ] 6 种数据包 JSON 格式（枪械/插件/种类/属性元数据/特性/状态）完善
- [ ] 注册冲突与覆盖规则实现
- [ ] 加载顺序实现
- [ ] /reload 重载行为实现
- [ ] 5 种定义丢失降级（枪械/插件/种类/属性/状态）实现
- [ ] 数据包 JSON 加载失败错误处理实现
- [ ] /modularshoot 调试命令（6 个子命令）实现
- [ ] ShootPredicate 扩展点完善
- [ ] ReloadEvent 事件实现
- [ ] GunRightClickEvent 完善
- [ ] key.modularshoot.reload 键位绑定实现
- [ ] 数据包 JSON 示例创建
- [ ] 项目编译通过
- [ ] 验证：数据包注册可用；/reload 后定义变更生效；卸载模组后旧物品降级不崩溃；调试命令输出正确
