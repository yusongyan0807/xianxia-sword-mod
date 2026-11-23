# ADR-001：Minecraft NeoForge 1.21.1 中国风剑修模组核心架构与编码红线

- **状态**：已采纳（Accepted） · **日期**：2026-11-23
- **维护与监察**：常驻主程（`xianxia-core-architect`）& 独立代码监察官（`xianxia-code-inspector`）

## 1. 背景

本项目为双人异地协同开发的长期维护型 **Minecraft `1.21.1` + NeoForge `21.1.267`（Java `21`）** 中国风剑修模组。针对同类模组常见的“联机服务端加载客户端类崩溃”“每帧全图扫描拖垮 TPS”“手拼裸字符串 NBT 难以维护”三大痛点，在立项阶段确立强制架构红线。

## 2. 核心架构决策

1. **工具链与版本契约**：锁定 ModDevGradle `2.0.148` + Gradle `9.2.1` + Parchment `2024.11.17` + Foojay Java `21`，严格使用 `1.21.1` 原生 API（`Equipable`、`NeoForgeMod.CREATIVE_FLIGHT`、`RenderLayer`），严禁混入 `1.21.2+` API。
2. **强制包分层职责**：
   - `ModConstants`：集中管理命名空间与 `ResourceLocation.fromNamespaceAndPath` 入口。
   - `registry/`：唯一允许声明 `DeferredRegister` 的注册入口，统一在 `@Mod` 构造器挂载。
   - `item/`、`component/`、`attachment/`：物品行为及 1.21.1 类型安全 `DataComponentType` / `AttachmentType`，严禁裸写字符串 NBT。
   - `event/` & `network/`：服务端状态守卫与 `CustomPacketPayload` 类型安全网络协议（强制服务端合法性校验）。
   - `compat/`：第三方模组（如 `Curios API`）隔离适配层。
   - `client/`：**物理客户端唯一隔离区**（强制 `@EventBusSubscriber(modid = ..., value = Dist.CLIENT)`），严禁非 `client/` 包引用任何 `net.minecraft.client.*` 或 Blaze3D 类，确保异地联机专用服务端 100% 零崩溃。
3. **御剑飞行权限双保险闭环**：通过 `EquipmentSlotGroup.FEET` 的 `CREATIVE_FLIGHT` 属性修饰符赋予飞行；卸载时由 `FlyingSwordEvents` 校验非创造/旁观模式且无其他飞行来源后立即复位 `mayfly`/`flying` 并同步客户端。
4. **Tick 性能预算**：常态 `Tick` 监听保持 `O(1)` 布尔短路；后续飞剑索敌与劈箭强制限定局部 `AABB` 半径并按 `5~10 Tick` 节流更新缓存。

## 3. 监督执行

通过单元测试字节码常量池扫描自动拦截跨端违规引用，并在每次推送 GitHub 前由独立代码监察官核验双向追溯矩阵、签署 `docs/audits/AUDIT-vX.Y.Z.md` 放行单。
