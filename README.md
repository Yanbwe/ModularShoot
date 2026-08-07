# ModularShoot（模块化射击 / Modular Shooting）

一个基于 **NeoForge 1.21.1** 的属性驱动、模块化组装的枪械系统**框架模组**。

A **framework mod** for **NeoForge 1.21.1** — an attribute-driven, modularly-assembled gun system.

其目标是为了提供一个易于修改数值、拓展特性的射击系统。

Its goal: a shooting system where numbers are easy to tweak and features are easy to extend.

# 特色 / Features

## 属性驱动 / Attribute-Driven

枪械属性要求绑定到原版的属性系统里的属性，这使玩家/装备的属性修饰符可以影响到枪械。  
这意味着你只需要给玩家打上原版的属性修饰符，就可以让枪械数值增加，或是增添特性，这使增强枪械的饰品和装备的开发变得非常简单。

Gun stats must be bound to vanilla attributes, so player/equipment attribute modifiers affect guns directly.  
Just apply a vanilla attribute modifier to a player and their gun gets stronger — or gains traits. Building gun-boosting trinkets and gear becomes trivial.

## 数据驱动 / Data-Driven

所有枪械和插件分别共用一个物品 ID，数据都存在物品组件里。

All guns share a single item ID, and so do all plugins — the actual data lives in item components.

## 模块化插件系统 / Modular Plugin System

你玩过神化或者挖矿与砍杀这两个模组吗？他们都有把宝珠嵌到装备里的玩法。我们也做了，把插件嵌到枪械里。  
不同的是，我们的孔分类型，而且想注册多少个就有多少个，插件可以同时支持多种孔——说白了就是基于tag匹配的插孔。  
并且我们还提供了互斥和优先级等功能。

Ever played Apotheosis or Mine & Slash? They both let you socket orbs into gear. We did the same — socket plugins into guns.  
The twist: our sockets are typed and infinitely registrable, and a plugin can fit multiple socket types — tag-matching sockets, basically.  
We also ship mutual-exclusion groups and priorities.

## 动态渲染 / Dynamic Rendering

我们抄袭了以撒的结合，枪械纹理、子弹支持动态调色、纹理的叠加以及其它的视觉效果，叠加源可以是插件、也可以是饰品。

We shamelessly copied The Binding of Isaac — gun textures and bullets support dynamic tinting, texture layering and other visual effects, stacking from plugins or even trinkets.

## 开放的拓展点 / Open Extension Points

我们添加了很多钩子、很多事件。

We added a ton of hooks and a ton of events.

## 一些开发便利和约定 / Dev Conveniences & Conventions

对于一些常见的拓展玩法，我们提供了统一的接口和系统。  
例如枪械动作键 ，子弹变体权重池（为了做概率发射子弹变体），以及更多。   
_（但没有换弹系统）_

For common extension patterns, we provide unified interfaces and systems.  
E.g. a gun action key, a weighted bullet-variant pool (for random variant fire), and more.  
_(But no reload system.)_

## 有缺点 / The Downsides

枪械不支持3D模型，如果Minecraft是个2D游戏就没有这个缺点了；（不过子弹是支持3D模型的！）    
视觉方面难以惊艳所有人，实际上我只关心好不好玩，哈哈。

Guns don't support 3D models — if Minecraft were a 2D game, this downside wouldn't exist at all. (Bullets, though, do support 3D models!)  
The visuals won't wow everyone; honestly, I only care whether it's fun. Haha.

# 文档库 / Documentation

[Yanbwe's WIKI](https://yanbwe.github.io/Yanbwe-Wiki/modularshoot/)

# 常见问题 / FAQ

**Q1**：你为什么要做这个模组？  
**A1**：本来只是Yanbwe给自己的模组包用的，但是考虑到设计很有意思，就做成人人都可以用的模组。  
希望开发者都能在开发数值膨胀的射击内容时保持幸福。

**Q1**: Why did you make this mod?  
**A1**: It started as a private mod for Yanbwe's own modpack, but the design turned out interesting enough to release as a framework anyone can use.  
May every developer stay happy while crafting their number-bloated shooting content.

**Q2**：会支持更多Minecraft版本吗？例如1.20.1？  
**A2**：无计划，我在等Minecraft的最新版本出现质变的那一天。

**Q2**: Will you support more Minecraft versions, like 1.20.1?  
**A2**: No plans. I'm waiting for the day the latest Minecraft version has a qualitative breakthrough.

**Q3**：模组的封面跟模组有什么关系？  
**A3**：这是我梦到的。

**Q3**: What does the mod's cover art have to do with the mod?  
**A3**: I dreamed it.

**Q4**：等等，我只是个玩家，我以为这个模组有可以玩的东西。
**A4**：请期待Yanbwe的拓展模组喵。

**Q4**: Wait, I'm just a player — I thought this mod had something to play with.  
**A4**: Stay tuned for Yanbwe's expansion mods, meow。
