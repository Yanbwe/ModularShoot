# Task Context: M2 - 插件系统 + 安装/拆卸

Session ID: 2026-06-19-m2-plugin-install-uninstall
Created: 2026-06-19T00:00:00Z
Status: in_progress
Depends on: M1（属性系统 + 枪械注册 + 基础物品）

## Current Request

实施 M2 里程碑：插件系统 + 安装/拆卸。M2 范围（设计文档行 2403-2414）：
- modularshoot:plugin 物品 + PluginData Data Component
- 插件种类、插件定义的注册（Java API + 数据包 JSON）
- tag 匹配 + 自动种类选择算法
- 安装交互（容器 GUI 内右键）、拆卸 API（按 UUID）
- 互斥组校验、自定义校验器
- 插件安装/拆卸后 ATTRIBUTE_MODIFIERS 重新计算
- 安装/拆卸事件
验证：注册测试插件与种类，右键安装到枪械、拆卸返还；互斥组冲突被拒绝；安装后属性值变化正确。

## Context Files (Standards to Follow)

- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（代码质量标准，强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md

## Reference Files (Source Material to Look At)

- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（权威需求，重点：系统三插件系统 行357-462、系统四安装拆卸API 行466-572、系统五属性计算管线 行575-704）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md
- M1 交付物（M1 完成后存在）：
  - src/main/java/org/yanbwe/modularshoot/attribute/ModularShootAttributes.java
  - src/main/java/org/yanbwe/modularshoot/component/GunData.java
  - src/main/java/org/yanbwe/modularshoot/component/PluginInstance.java
  - src/main/java/org/yanbwe/modularshoot/component/ModularShootDataComponents.java
  - src/main/java/org/yanbwe/modularshoot/gun/GunDefinition.java
  - src/main/java/org/yanbwe/modularshoot/item/GunItem.java
  - src/main/java/org/yanbwe/modularshoot/registry/ModularShootRegistries.java
  - src/main/java/org/yanbwe/modularshoot/attribute/AttributeModifierService.java
- 外部源码（API 验证）：
  - C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out（MC 1.21.1 源码）
  - C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1（NeoForge 源码）

## External Docs Fetched

无需联网。本地源码可查阅 API（见 AGENTS.md）。

## Components

M2 功能单元（供 TaskManager 细化）：
1. PluginData Data Component（pluginId 字段 + Codec + 注册）
2. modularshoot:plugin 物品（PluginItem 类，最大堆叠 1、耐火、不可附魔）
3. 插件种类定义数据结构（PluginTypeDefinition record + Codec：tags/priority/显示信息）
4. 插件定义数据结构（PluginDefinition record + Codec：tags/priority/图标/修饰符/特性/互斥组/子弹样式覆盖/纹理叠加/显示信息）
5. 插件种类注册表 API（Java + 数据包 JSON 加载到 modularshoot:plugin_types）
6. 插件注册表 API（Java + 数据包 JSON 加载到 modularshoot:plugins）
7. tag 匹配 + 自动种类选择算法（tag 数量少者优先 → priority 大者优先 → 随机兜底）
8. 安装交互（容器 GUI 内右键，PlayerInteractEvent 拦截，校验流程）
9. 互斥组校验 + 自定义校验器 API（PluginValidator register）
10. 拆卸 API（按 UUID/随机/按种类/全部，UninstallResult，force/returnItems 参数）
11. 安装/拆卸后 ATTRIBUTE_MODIFIERS 重新计算（扩展 M1 的 AttributeModifierService，加入插件修饰符）
12. 安装/拆卸事件（PrePluginInstallEvent/PostPluginInstallEvent/PrePluginUninstallEvent/PostPluginUninstallEvent）
13. 运行时锁定 API（setPluginLocked/isPluginLocked）
14. ModularShootAPI 工具方法（isPlugin/getPluginId/getPluginData/getInstalledPlugins/uninstallPlugin 等）
15. 测试插件与种类数据包 JSON

## Constraints

- 平台：NeoForge 1.21.1，Java 21，mod_id=modularshoot，包名 org.yanbwe.modularshoot
- 插件物品 ID：modularshoot:plugin，最大堆叠 1，耐火，不可附魔
- 安装交互：容器 GUI 内右键（player.containerMenu != 默认 InventoryMenu）
- 拆卸按 instanceUuid 定位（不用列表索引，防索引漂移）
- 修饰符 ID 稳定：插件用 instanceUuid 转成的 ResourceLocation 作为修饰符 ID
- 安装后 installedTypeId 固定持久化，不受后续 tag 变化影响
- 事件挂在 NeoForge.EVENT_BUS（game bus）
- 代码风格：模块化、函数式、小函数（<50行）、纯函数优先（见 code-quality.md）

## Exit Criteria

- [ ] PluginData Data Component 定义并注册
- [ ] modularshoot:plugin 物品注册（堆叠1/耐火/不可附魔）
- [ ] 插件种类定义 + Codec 完成
- [ ] 插件定义 + Codec 完成
- [ ] 插件种类/插件注册表 API（Java + 数据包 JSON）可用
- [ ] tag 匹配 + 自动种类选择算法实现
- [ ] 安装交互（容器 GUI 右键）实现
- [ ] 互斥组校验 + 自定义校验器 API 实现
- [ ] 拆卸 API（4 种方式）实现
- [ ] 安装/拆卸后 ATTRIBUTE_MODIFIERS 重新计算（含插件修饰符）
- [ ] 安装/拆卸事件（4 个）实现
- [ ] 运行时锁定 API 实现
- [ ] ModularShootAPI 工具方法实现
- [ ] 测试插件与种类 JSON 创建
- [ ] 项目编译通过
- [ ] 验证：右键安装/拆卸返还；互斥组冲突被拒；安装后属性值变化正确
