# 中国风修仙题材模组开发项目 — 立项说明

> **目标平台**: Minecraft `1.21.1` · NeoForge `21.1.267` · Java `21`（ModDevGradle `2.0.148`，Parchment `2024.11.17`，Gradle `9.2.1` + Foojay `1.0.0`）

## 1. 项目定位与立项背景

本项目是一个面向个人开发与学习探索的 **Minecraft NeoForge 1.21.1 中国风修仙题材（Xianxia Cultivation）模组**。
项目采用清晰、分层的模块化工程结构，先完成标准开发环境搭建与最小验证原型，再逐步迭代灵根、灵气、境界突破、功法与法宝系统，确保每一阶段都可编译、可自动化测试、可进游戏验证。

当前处于 **Milestone 4（E2E 合规验证与 Tier-5 对抗性覆盖加固）**，已完成构建系统、占位符元数据契约、青锋御剑物品与脚部装备/御剑飞行机制、16 部件中国风 3D 剑模与 64×64 像素贴图、玩家足底御剑 3D 渲染层、JUnit 5 单元测试、NeoForge GameTest、100 项 `-Pe2e` 端到端合规验证、Tier-5 白盒对抗测试加固及立项/开发文档。

## 2. 最小验证原型：御剑原型

为验证中国风修仙核心体验与 NeoForge 1.21.1 关键技术链路，本项目规划了“青锋御剑”最小验证原型：

- **物品定位**：青锋御剑（`xianxia_placeholder:qingfeng_flying_sword`）—— 已完成注册表绑定、脚部装备与飞行能力赋予、16 部件 3D 古剑建模及足底御剑渲染实现。
- **脚部槽位装备（`脚部槽位装备`）**：仅支持装备在玩家盔甲栏的脚部（`EquipmentSlot.FEET`）槽位，支持右键快速替换穿戴与防错位校验。
- **御剑飞行（`御剑飞行`）**：生存/冒险模式玩家将飞剑装备于脚部槽位后获得飞行能力，卸下飞剑后立即清除飞行状态且不留残余；创造/旁观模式原有飞行状态不受影响。
- **精致 3D 展示建模（`精致 3D 展示建模`）**：多部件中国风古剑 3D JSON 模型（剑身/剑脊、如意云纹剑格与玉石镶嵌、缠绳剑柄、剑首与红绸剑穗）配合 64×64 像素风贴图，支持物品栏/手持展示以及脚部装备时水平悬停于玩家足底的御剑展示渲染。

## 3. 占位符说明与替换清单

由于模组名称、Mod ID、基础 Java 包名、作者与开源协议尚处于立项初期占位阶段，当前全部统一使用下表中的占位符。后续正式定名时，请按顺序同步替换。

### 3.1 占位符元数据对照表

| Placeholder Key | Current Value | Authoritative Locations |
|---|---|---|
| `mod_id` | `xianxia_placeholder` | `gradle.properties`, `ModConstants.MOD_ID`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_id}`), `build.gradle` (`mods`, `gameTestServer`), `src/main/resources/assets/<mod_id>/`, `src/main/resources/data/<mod_id>/`, `models/item/qingfeng_flying_sword.json`（`"sword"`/`"particle"` 贴图命名空间前缀）, `lang/*.json`（`itemGroup.<mod_id>.xianxia_tab`, `item.<mod_id>.*`, `tooltip.<mod_id>.*` 键前缀）, `src/test/java/<group_path>/unit/ProjectUnitTests.java`（启用 `-Pe2e` 时同步更新 `e2e/`） |
| `mod_name` | `Xianxia Cultivation Mod (Placeholder)` | `gradle.properties`, `ModConstants.MOD_NAME`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_name}`), `README.md`, `src/test/java/<group_path>/unit/ProjectUnitTests.java`（启用 `-Pe2e` 时含 `e2e/`）；另注：语言文件 `lang/*.json` 中的物品栏名称使用 `"Xianxia Cultivation (Placeholder)"`（`itemGroup.<mod_id>.xianxia_tab`） |
| `mod_group_id` | `com.example.xianxia` | `gradle.properties`, `src/main/java/<group_path>/`（当前 `com/example/xianxia/`）、`src/test/java/<group_path>/`（当前 `com/example/xianxia/`，含 `ProjectUnitTests.java`）与 `e2e/`（启用 `-Pe2e` 时）包目录，以及所有 Java 文件的 `package` / `import` 声明 |
| `mod_version` | `0.1.0-alpha` | `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_version}`), `src/test/java/<group_path>/unit/ProjectUnitTests.java`（启用 `-Pe2e` 时含 `e2e/`） |
| `mod_authors` | `Author Placeholder` | `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_authors}`), `LICENSE`, `src/test/java/<group_path>/unit/ProjectUnitTests.java` |
| `mod_license` | `MIT` | `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_license}`), `LICENSE`, `src/test/java/<group_path>/unit/ProjectUnitTests.java` |
| `mod_description` | `A Chinese Xianxia cultivation mod prototype for Minecraft NeoForge 1.21.1 (placeholder metadata).` | `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml` (`${mod_description}`) |
| `rootProject.name` | `xianxia-neoforge-1.21.1` | `settings.gradle` |

### 3.2 占位符替换步骤（1–6）

1. 修改 `gradle.properties` 中的 `mod_id`（必须符合 `^[a-z][a-z0-9_]{1,63}$`）、`mod_name`、`mod_group_id`、`mod_version`、`mod_authors`、`mod_license`、`mod_description`。
2. 在 `settings.gradle` 中更新 `rootProject.name`。
3. 将 `src/main/java/` 与 `src/test/java/` 下的 `com/example/xianxia` 目录重命名为新的 `<group_path>` 对应包路径（包含 `src/test/java/<group_path>/unit/ProjectUnitTests.java`，若使用 `-Pe2e` 则同步更新 `e2e/`），并同步更新所有 Java 源码的 `package`、`import` 与测试中的占位符断言。
4. 更新 `ModConstants.java` 中的 `MOD_ID` 与 `MOD_NAME`（如需要也可同步重命名主类 `XianxiaPlaceholderMod.java`）。
5. 同步重命名 `src/main/resources/assets/<mod_id>/` 与 `src/main/resources/data/<mod_id>/`，并更新 `models/item/qingfeng_flying_sword.json`（`"sword"` 与 `"particle"` 贴图引用前缀）、`lang/*.json`（`itemGroup.<mod_id>.xianxia_tab`、`item.<mod_id>.*`、`tooltip.<mod_id>.*` 键前缀及 `"Xianxia Cultivation (Placeholder)"` 等物品栏显示名）和 NBT 结构文件引用中的命名空间前缀。
6. 更新 `LICENSE`、`README.md`、`docs/DEVELOPMENT_GUIDE.md` 与 `src/test/java/<group_path>/unit/ProjectUnitTests.java`（启用 `-Pe2e` 时同步更新 `e2e/`），随后运行 `.\gradlew.bat test --no-daemon`、`.\gradlew.bat build --no-daemon` 与 `.\gradlew.bat runGameTestServer --no-daemon` 确认元数据、测试与打包全部通过。

## 4. 构建、测试与运行命令

在工作目录 `d:\AGYmcmod` 中执行以下命令（首次运行将通过 Foojay 自动配置 Java 21 工具链）：

- `.\gradlew.bat test --no-daemon` — 运行 JUnit 5 单元测试、ASM 隔离检查与占位符/元数据契约验证（`neoForge.unitTest`）。
- `.\gradlew.bat build --no-daemon` — 编译源码、执行测试并生成模组构建产物 `build/libs/xianxia_placeholder-0.1.0-alpha.jar`（不含 `e2e/` 或测试类）。
- `.\gradlew.bat runGameTestServer --no-daemon` — 启动无头 NeoForge GameTest 服务端，运行 `FlyingSwordGameTests` 中的游戏内自动化与 Tier-5 对抗行为测试。
- `.\gradlew.bat test runGameTestServer -Pe2e --no-daemon` — 启用 `-Pe2e` 独立验证轨道，同时运行全部单元测试、`FlyingSwordGameTests` 以及 `e2e/` 100 项合规/端到端测试。
- `.\gradlew.bat runClient` — 启动 Minecraft `1.21.1` 开发客户端进行交互式调试与视觉检查。

更多架构约定、目录说明与后续修仙体系演进规划请参阅以下工程治理与规范档案：

- [开发规范与目录结构说明](docs/DEVELOPMENT_GUIDE.md)
- [国风剑修美术与 3D 建模统一规范](docs/ART_STYLE_GUIDE.md)
- [核心架构决策记录（ADR-001）](docs/architecture/ADR-001-neoforge-1211-core-architecture.md)
- [差异化演进总蓝图与版本路线图](docs/ROADMAP_AND_DESIGN.md)
- [需求—源码—测试 双向可追溯矩阵](docs/traceability/TRACEABILITY_MATRIX.md)
- [v0.1.0 独立代码监察放行单](docs/audits/AUDIT-v0.1.0.md)
- [版本变更日志](CHANGELOG.md)
