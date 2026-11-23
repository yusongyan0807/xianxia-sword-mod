# 开发规范与目录结构说明（Development Guide）

本文档说明本项目的实际目录结构、包命名约定、NeoForge 1.21.1 编码与资源规范，以及后续中国风修仙体系的演进路线。

## 1. 项目目录结构

以下结构严格反映当前 **Milestone 4（E2E 合规验证与 Tier-5 对抗性覆盖加固）** 的实际文件布局：

```text
d:\AGYmcmod\
├── .gitattributes
├── .gitignore
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── LICENSE
├── README.md
├── docs/
│   └── DEVELOPMENT_GUIDE.md
├── src/main/templates/META-INF/
│   └── neoforge.mods.toml
├── src/main/java/com/example/xianxia/
│   ├── ModConstants.java                            # 全局占位符常量与 ResourceLocation 辅助
│   ├── XianxiaPlaceholderMod.java                   # @Mod 入口，挂载注册表总线
│   ├── registry/
│   │   ├── ModItems.java                            # 注册青锋御剑（qingfeng_flying_sword）
│   │   └── ModCreativeTabs.java                     # 注册修仙物品栏（xianxia_tab）
│   ├── item/FlyingSwordItem.java                    # 脚部槽位 Equipable 飞剑物品与 CREATIVE_FLIGHT 属性
│   ├── event/FlyingSwordEvents.java                 # 服务端双层飞行清理守卫（卸下立即清除非创造/旁观飞行）
│   ├── gametest/FlyingSwordGameTests.java           # NeoForge @GameTest 自动化与 Tier-5 对抗验证套件
│   └── client/
│       ├── ClientSetup.java                         # 客户端 Dist.CLIENT 渲染层注册入口
│       └── render/FlyingSwordRenderLayer.java       # 足底御剑 3D RenderLayer 渲染实现
├── src/main/resources/
│   ├── assets/xianxia_placeholder/
│   │   ├── lang/en_us.json & zh_cn.json             # 中英双语本地化资源
│   │   ├── models/item/qingfeng_flying_sword.json   # 16 部件中国风古剑 3D JSON 模型与 display 变换（含 head）
│   │   └── textures/item/qingfeng_flying_sword.png  # 64x64 RGBA 中国风修仙像素贴图
│   └── data/xianxia_placeholder/
│       └── structure/empty3x3x3.nbt                 # GameTest 3x3x3 空结构模板（DataVersion 3955）
├── src/test/java/com/example/xianxia/unit/
│   └── ProjectUnitTests.java                        # JUnit 5 契约、注册、属性、资源与 Tier-5 对抗测试
└── e2e/                                             # [E2E Track - enabled via -Pe2e]
    ├── main/java/com/example/xianxia/e2e/
    │   └── E2EFlyingSwordGameTests.java             # 47 项端到端运行时 GameTest
    └── test/java/com/example/xianxia/e2e/
        ├── E2EComplianceTests.java                  # 54 项静态/资产/字节码/JAR 合规测试
        ├── E2EClassfileInspector.java               # 类文件解析辅助
        └── E2EComplianceValidators.java             # 文档/模型/贴图校验辅助
```

## 2. 包路径与命名约定

- **基础 Java 包名**：`com.example.xianxia`（占位符，后续随 `gradle.properties` 中的 `mod_group_id` 同步替换）。
- **子包职责划分**：
  - `registry`：所有 `DeferredRegister` 声明（`ModItems`、`ModCreativeTabs` 等）。
  - `item`：自定义物品实现（如后续飞剑、法宝、丹药）。
  - `event`：服务端/通用游戏事件监听（`NeoForge.EVENT_BUS`）。
  - `client` 与 `client.render`：仅在物理客户端加载的渲染层、模型绑定与粒子效果。
  - `gametest`：NeoForge `@GameTest` 自动化行为测试。
  - `unit`（位于 `src/test/java`）：JUnit 5 单元测试与工程契约校验。
- **注册 ID 命名**：一律采用小写 `snake_case`（满足 `^[a-z][a-z0-9_]{1,63}$`），并通过 `ModConstants.id("...")`（底层调用 `ResourceLocation.fromNamespaceAndPath`）生成资源路径。

## 3. NeoForge 1.21.1 注册与事件规范

1. **延迟注册（DeferredRegister）**：
   - 物品注册表必须保持 `DeferredRegister.Items` 具体类型（`DeferredRegister.createItems(ModConstants.MOD_ID)`），以便后续调用 `registerItem` 返回 `DeferredItem<T>`。
   - 创造模式物品栏使用 `DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModConstants.MOD_ID)`。
   - 所有注册表在 `@Mod(ModConstants.MOD_ID)` 构造函数中通过 `modEventBus` 注册。
2. **事件总线使用纪律**：
   - 不要在没有 `@SubscribeEvent` 实例方法的类上调用 `NeoForge.EVENT_BUS.register(this)`，否则 FML EventBus 8 会在模组初始化时抛出 `IllegalArgumentException`。
   - 客户端静态事件订阅使用 `@EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT)`，**不要**传入已弃用的 `bus =` 参数（1.21.1 FML 会自动识别 `IModBusEvent` 与游戏总线事件）。
3. **严格遵守 Minecraft 1.21.1 API 边界**：
   - 脚部装备与御剑飞行采用 1.21.1 标准的 `Equipable` 接口、`NeoForgeMod.CREATIVE_FLIGHT` 属性修饰符与 `LivingEquipmentChangeEvent` 清理机制。
   - 玩家脚踏渲染采用 `RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>` 与 `EntityRenderersEvent.AddLayers#getSkin(skin)`。
   - 禁止引入 1.21.2+ 才出现的 API（如 `DataComponents.EQUIPPABLE`、`EntityRenderState`、`getPlayerRenderer` 或 `EquipmentAssets`）。
4. **物理端隔离**：所有客户端渲染类和纹理/模型注册必须严格隔离在 `client/` 包及 `Dist.CLIENT` 作用域下，确保专用服务端启动不加载任何客户端类。

## 4. 资源、元数据与测试规范

- **模组元数据模板**：`src/main/templates/META-INF/neoforge.mods.toml` 由 Gradle 任务 `generateModMetadata` 展开生成到 `build/generated/sources/modMetadata/META-INF/neoforge.mods.toml`，`replaceProperties` 与 `gradle.properties` 保持 1:1 映射。
- **GameTest 结构文件**：Minecraft 1.21+ 使用单数路径 `data/<mod_id>/structure/<name>.nbt`（而非旧版 `structures/`）。
- **JUnit 5 单元测试**：通过 ModDevGradle `neoForge.unitTest` 运行（工作目录默认设置为 `build/minecraft-junit`），测试在 FML 转换类加载器内执行，可直接校验 `ResourceLocation`、`ModList` 与注册表命名空间。
- **编码规范**：全部源码、配置与文档均使用 **UTF-8（无 BOM）** 编码。

## 5. 修仙体系后续演进路线

1. **Stage 1（最小验证原型）**：完成青锋御剑（`qingfeng_flying_sword`）、脚部槽位装备、御剑飞行能力授予与干净卸载、以及多部件 3D 古剑建模与足底御剑展示。
2. **Stage 2（灵根与灵气）**：引入五行/变异灵根（Spirit Roots）、天地灵气感知与玩家灵力存储（基于 NeoForge `AttachmentType` 与客户端灵力条 HUD）。
3. **Stage 3（境界与功法）**：实现练气、筑基、金丹、元婴等境界突破体系与打坐吐纳功法，并将御剑飞行速度与持续时间接入灵力消耗与境界加成。
4. **Stage 4（法宝与御剑术）**：扩展多阶飞剑法宝、御剑术主动剑诀、剑阵协同及神识探查系统。
