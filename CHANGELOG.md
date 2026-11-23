# Changelog

本文件记录项目所有正式版本的变更历史，遵循语义化版本规范（Semantic Versioning）。

## [0.1.0] - 2026-11-23

### Added
- **项目立项与工程骨架**：搭建基于 Minecraft `1.21.1`、NeoForge `21.1.267`、ModDevGradle `2.0.148` 与 Java `21` 的标准模组开发环境，配置清晰的占位符元数据体系与替换指南（`README.md`、`docs/DEVELOPMENT_GUIDE.md`）。
- **最小验证原型「青锋御剑」**：
  - 注册飞剑物品 `qingfeng_flying_sword` 与专属创造模式物品栏 `xianxia_tab`，提供中英双语本地化（`zh_cn` / `en_us`）。
  - 实现脚部装备槽（`EquipmentSlot.FEET`）专属穿戴与手持右键快捷换装。
  - 基于 `NeoForgeMod.CREATIVE_FLIGHT` 与 `FlyingSwordEvents` 双层服务端守卫，实现穿戴赋予御剑飞行、卸下无残留即时移除（兼容创造/旁观模式及其他飞行来源）。
- **中国风 3D 建模与足底渲染**：
  - 制作 16 部件立体中国风古剑模型（四段寒铁剑刃、青玉云纹剑脊、上扬云翼剑格、翠玉剑心、菱纹握柄与朱红分叉剑穗）及 `64×64` 原生像素贴图。
  - 实现客户端物理隔离的 `FlyingSwordRenderLayer`，支持装备于脚部时在玩家足底水平悬浮展示与呼吸/俯仰动态微倾。
- **工程治理与可追溯体系**：
  - 沉淀国风剑修美术母版规范（`docs/ART_STYLE_GUIDE.md`）、核心架构决策记录（`docs/architecture/ADR-001-neoforge-1211-core-architecture.md`）、差异化演进总蓝图（`docs/ROADMAP_AND_DESIGN.md`）、双向追溯矩阵（`docs/traceability/TRACEABILITY_MATRIX.md`）及首版独立代码监察报告（`docs/audits/AUDIT-v0.1.0.md`）。
  - 配备全套 JUnit 5 单元测试、NeoForge 实机 GameTest 及 `-Pe2e` 端到端合规验证套件。
