# Task Context: M1 - 属性系统 + 枪械注册 + 基础物品

Session ID: 2026-06-19-m1-attributes-gun-basics
Created: 2026-06-19T00:00:00Z
Status: in_progress

## Current Request

用户要求实施 ModularShoot 模块化射击框架，从 M1 里程碑开始。用户先要求做任务分解。
M1 范围（来自设计文档"实施里程碑"章节）：
- 9 个预置属性的原版 DeferredRegister 注册 + attribute_meta 元数据表 + DataPackRegistry 注册表搭建
- modularshoot:gun 物品 + GunData Data Component
- 枪械定义的 Java API 注册与数据包 JSON 加载
- ATTRIBUTE_MODIFIERS 组件计算与写入（无插件场景，仅枪械基础值）
- 创造模式标签页 modularshoot
- 副手限制（放入副手时强制丢出）
验证：注册一把测试枪械，手持后 /attribute 命令可查到框架属性值；创造标签页可取出枪械；放入副手被丢出。

## Context Files (Standards to Follow)

以下为 ContextScout 发现的标准文件，所有下游代理必须遵循：
- C:\Users\GGxia\.config\opencode\context\core\standards\code-quality.md（代码质量标准，强制）
- C:\Users\GGxia\.config\opencode\context\core\workflows\component-planning.md（组件规划工作流）
- C:\Users\GGxia\.config\opencode\context\core\workflows\feature-breakdown.md（任务分解指南）
- C:\Users\GGxia\.config\opencode\context\core\workflows\external-context-integration.md（外部上下文集成）
- C:\Users\GGxia\.config\opencode\context\development\principles\clean-code.md（整洁代码原则）
- C:\Users\GGxia\.config\opencode\context\development\principles\api-design.md（API 设计原则）

## Reference Files (Source Material to Look At)

项目文件（非标准，是参考材料）：
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\设计文档.md（完整设计文档，2525 行，权威需求来源）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\AGENTS.md（项目约定：MC 1.21.1 + NeoForge）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\build.gradle（Gradle 构建配置，NeoForge moddev 插件）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\gradle.properties（mod_id=modularshoot, group=org.yanbwe.modularshoot, neo_version=21.1.233, mc=1.21.1, Java 21）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\src\main\java\org\yanbwe\modularshoot\ModularShoot.java（主类骨架）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\src\main\java\org\yanbwe\modularshoot\ModularShootClient.java（客户端类骨架）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\src\main\templates\META-INF\neoforge.mods.toml（mod 元数据）
- C:\Users\GGxia\Desktop\YanbweMod\ModularShoot\src\main\resources\modularshoot.mixins.json（mixin 配置，当前无 mixin 类）

## External Docs Fetched

无需联网获取。根据 AGENTS.md，本地有权威源码可查阅 API：
- Minecraft 1.21.1 源码：C:\Users\GGxia\Desktop\YanbweMod\1.21.1\out
- NeoForge 1.21.1 源码：C:\Users\GGxia\Desktop\YanbweMod\MCforge\NeoForge-1.21.1
下游代理在不确定 API 时应查阅这些源码路径。

## Components

M1 拆分为以下功能单元（供 TaskManager 进一步细化为原子子任务）：

1. **属性本体注册**：9 个预置属性通过 DeferredRegister 注册到 BuiltInRegistries.ATTRIBUTE（base=0），ModularShootAttributes 常量类
2. **属性元数据表**：modularshoot:attribute_meta 动态注册表 + 9 个预置属性元数据条目（默认值/显示信息/binds 指向自身）
3. **DataPackRegistry 搭建**：5 张框架动态注册表（guns/plugins/plugin_types/traits/states）+ attribute_meta，通过 DataPackRegistryEvent.NewRegistry 注册
4. **GunData Data Component**：定义 GunData record（gunId/gunInstanceUuid/installedPlugins/modifierVersion/state）+ PluginInstance record + Codec 序列化 + 注册到 DataComponents
5. **modularshoot:gun 物品**：GunItem 类（最大堆叠 1、耐火、不可附魔、副手限制）+ 注册到 ITEMS
6. **枪械定义数据结构**：GunDefinition record（名称/纹理/射击纹理/切换模式/音效/基础属性/固有特性/插件插槽/子弹样式）+ Codec
7. **枪械注册表 API**：Java API 注册枪械定义 + 数据包 JSON 加载到 modularshoot:guns 注册表
8. **ATTRIBUTE_MODIFIERS 计算**：根据枪械基础属性计算修饰符集合（ADD_VALUE，ID=modularshoot:gun_base）写入 DataComponents.ATTRIBUTE_MODIFIERS（slot=MAINHAND, showInTooltip=false）
9. **创造模式标签页**：注册 modularshoot 创造标签页 + BuildCreativeModeTabContentsEvent 将枪械变体作为独立 ItemStack 添加
10. **副手限制**：检测枪械放入副手槽时强制丢出（监听容器事件或 tick 检测）
11. **测试枪械数据包**：创建一把测试枪械 JSON 用于验证

## Constraints

技术约束：
- 平台：NeoForge 1.21.1（neo_version=21.1.233），Minecraft 1.21.1
- Java 21（toolchain）
- mod_id = modularshoot，包名 org.yanbwe.modularshoot
- 构建工具：Gradle + net.neoforged.moddev 插件 2.0.141
- 无外部依赖（纯原版 + NeoForge API）
- 属性本体走 DeferredRegister（不可热重载），元数据走 DataPackRegistry（可热重载）
- 属性 base 值一律为 0，默认值存于 attribute_meta 元数据表
- 枪械物品最大堆叠 1，耐火，不可附魔，不可放入副手
- ATTRIBUTE_MODIFIERS 的 slot=MAINHAND，showInTooltip=false
- 修饰符 ID 稳定：枪械基础值用固定 ID modularshoot:gun_base
- 代码风格：模块化、函数式、小函数（<50行）、纯函数优先、显式依赖（见 code-quality.md）
- 命名：Java 驼峰，常量 UPPER_SNAKE_CASE，包名小写

## Exit Criteria

- [ ] 9 个预置属性注册到原版 ATTRIBUTE 注册表，ModularShootAttributes 常量类可用
- [ ] attribute_meta 元数据表建立，9 个预置属性元数据条目登记（默认值/显示/binds）
- [ ] 5 张框架动态注册表 + attribute_meta 通过 DataPackRegistry 注册
- [ ] GunData Data Component 定义并注册，含 Codec 序列化
- [ ] modularshoot:gun 物品注册，最大堆叠 1、耐火、不可附魔
- [ ] 枪械定义数据结构（GunDefinition）+ Codec 完成
- [ ] 枪械注册表 API（Java + 数据包 JSON 加载）可用
- [ ] ATTRIBUTE_MODIFIERS 组件计算与写入逻辑完成（仅枪械基础值）
- [ ] 创造模式标签页 modularshoot 注册，枪械变体可取出
- [ ] 副手限制生效（放入副手被强制丢出）
- [ ] 测试枪械 JSON 数据包创建
- [ ] 项目可编译通过（gradle build / compileJava 无错误）
- [ ] 验证：手持测试枪械 /attribute 可查到框架属性值；创造标签页可取出；副手被丢出
