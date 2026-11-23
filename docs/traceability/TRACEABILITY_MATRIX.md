# 需求—源码—测试 双向可追溯矩阵（Traceability Matrix）

> **当前基线版本**：`v0.1.0`（构建产物标识 `0.1.0-alpha`）  
> **维护与核查**：独立代码监察官（`xianxia-code-inspector`）

本矩阵建立从**立项需求（`REQ`）→ 源码与资产文件（`SRC/RES`）→ 自动化测试验证（`TEST`）→ 架构规范（`ADR/DOC`）**的双向索引。后续每次提交新特性（`FEAT-*`）或修复缺陷（`BUG-*`），必须在合入前同步登记本表。

---

## 1. `v0.1.0` 核心需求追溯总表

| 追溯编号 | 需求/功能描述 | 实现源码 / 资源路径 | 对应单元测试 / GameTest 验证 | 关联架构与规范文档 | 状态 |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **`REQ-R1.1`** | 中国风修仙项目立项说明与最小验证定义 | `README.md`<br>`LICENSE` | `ProjectUnitTests`（文档与元数据存在性及内容核验）<br>`E2EComplianceTests`（`F1-T1-*`、`F1-T2-*`） | `docs/ROADMAP_AND_DESIGN.md` | ✅ 已验收 |
| **`REQ-R1.2`** | 统一占位符元数据体系与 6 步替换指南 | `gradle.properties`<br>`settings.gradle`<br>`src/main/templates/META-INF/neoforge.mods.toml`<br>`src/main/java/com/example/xianxia/ModConstants.java` | `ProjectUnitTests`（占位符 1:1 映射与合法性断言）<br>`E2EComplianceTests`（`T3-07` 跨文件一致性校验） | `README.md` §3<br>`docs/DEVELOPMENT_GUIDE.md` §2, §4 | ✅ 已验收 |
| **`REQ-R2.1`** | NeoForge 1.21.1 标准 Gradle 9.2.1 + Java 21 构建环境 | `build.gradle`<br>`gradlew` / `gradlew.bat`<br>`gradle/wrapper/*` | 离线/在线 `./gradlew build` & `./gradlew runGameTestServer` 零错误退出码 `0` | `docs/architecture/ADR-001-neoforge-1211-core-architecture.md` §2.1 | ✅ 已验收 |
| **`REQ-R2.2`** | 模组主入口、物品与创造模式物品栏延迟注册 | `XianxiaPlaceholderMod.java`<br>`registry/ModItems.java`<br>`registry/ModCreativeTabs.java`<br>`assets/xianxia_placeholder/lang/zh_cn.json` & `en_us.json` | `ProjectUnitTests`（注册表键、稀有度、堆叠上限 `1`、中英翻译键校验）<br>`FlyingSwordGameTests` | `docs/DEVELOPMENT_GUIDE.md` §3 | ✅ 已验收 |
| **`REQ-R3.1`** | 16 部件中国风「青锋御剑」3D 模型与 `64×64` 贴图 | `assets/xianxia_placeholder/models/item/qingfeng_flying_sword.json`<br>`assets/xianxia_placeholder/textures/item/qingfeng_flying_sword.png` | `ProjectUnitTests`（16 部件几何结构、合法旋转角、UV 边界、`64×64` 像素尺寸与非空色彩校验）<br>`E2EComplianceTests` | `docs/ART_STYLE_GUIDE.md` §2–§4 | ✅ 已验收 |
| **`REQ-R3.2`** | 脚部（`EquipmentSlot.FEET`）专属穿戴与右键快捷换装 | `item/FlyingSwordItem.java`（`Equipable`、`canEquip`、`use`） | `FlyingSwordGameTests`（仅允许 `FEET` 槽位、禁止头/胸/腿槽误穿、右键穿脱交互） | `docs/architecture/ADR-001-neoforge-1211-core-architecture.md` §2.3 | ✅ 已验收 |
| **`REQ-R3.3`** | 御剑飞行能力授予与卸载无残留双层清理守卫 | `item/FlyingSwordItem.java`（`NeoForgeMod.CREATIVE_FLIGHT` 修饰符）<br>`event/FlyingSwordEvents.java`（`LivingEquipmentChangeEvent` + `PlayerTickEvent.Post`） | `FlyingSwordGameTests` + `-Pe2e` 共 **56 项实机 GameTest**（含生存/冒险穿脱闭环、创造/旁观不误清、多飞行源共存 Tier-5 对抗测试） | `docs/architecture/ADR-001-neoforge-1211-core-architecture.md` §2.3, §2.4 | ✅ 已验收 |
| **`REQ-R3.4`** | 客户端物理隔离与足底水平悬浮防穿模 3D 渲染 | `client/ClientSetup.java`（`Dist.CLIENT`）<br>`client/render/FlyingSwordRenderLayer.java` | `ProjectUnitTests` & `E2EClassfileInspector`（字节码常量池扫描验证服务端零客户端类泄露、潜行与悬浮波谷防穿模校验） | `docs/ART_STYLE_GUIDE.md` §4.3<br>`docs/architecture/ADR-001-neoforge-1211-core-architecture.md` §2.2 | ✅ 已验收 |

---

## 2. 追溯维护规程（适用于后续 `v0.2+` 迭代）

1. **立项登记**：每次开发新模块前分配唯一编号（如 `FEAT-0201` 独立飞剑实体、`FEAT-0202` 双人共乘缩放）。
2. **代码标记**：在核心新增类的类注释中标注所属编号，确保从代码可反查设计初衷。
3. **监察闭环**：独立代码监察官在签发 `docs/audits/AUDIT-vX.Y.Z.md` 前，逐行核对本表新增项对应的自动化测试均已实跑通过。
