# PKotlinApp

一个「纯 Kotlin + 传统 View 体系」的 Android 参考工程：本地待办清单。数据用 Room 持久化，界面全部基于 XML 布局 + ViewBinding，同时包含自绘 View 与代码构建 View 的示例，**未引入 Jetpack Compose**。

## 功能范围

- 待办列表：按「未完成在前、最近更新在前」排序
- 新增 / 编辑待办（标题必填，备注可选）
- 勾选完成 / 取消完成，完成后标题置灰并加删除线
- 删除待办（二次确认）
- 顶部完成进度条（自定义 View 绘制）
- 空状态（完全由 Kotlin 代码构建，带新增入口）
- 所有数据写入 Room，杀进程或重启设备后仍在

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin 2.2.21（源码目录中无 `.java` 文件） |
| 构建 | Gradle Kotlin DSL + 版本目录 `gradle/libs.versions.toml` |
| AGP / Gradle | 8.13.1 / 8.14.3 |
| SDK | compileSdk 36、targetSdk 36、minSdk 24 |
| UI | XML 布局 + ViewBinding + Material Components 3（无 Compose） |
| 数据 | Room 2.8.5（KSP 生成代码）+ Coroutines / Flow |
| 架构 | MVVM + Repository + 手动依赖注入 |

## 目录结构

```text
PKotlinApp/
├── build.gradle.kts                 # 根构建脚本，只声明插件
├── settings.gradle.kts              # 仓库与模块声明
├── gradle/libs.versions.toml        # 所有版本与依赖坐标集中管理
├── gradle/wrapper/                  # Gradle Wrapper
└── app/
    ├── build.gradle.kts
    ├── schemas/                     # Room 导出的表结构（需提交，供迁移使用）
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/platojobs/pkotlinapp/
        │   ├── PKotlinApplication.kt        # Application，持有 AppContainer
        │   ├── di/AppContainer.kt           # 手动依赖注入容器
        │   ├── data/
        │   │   ├── local/                   # Room：Entity / Dao / Database
        │   │   └── repository/              # Repository 实现
        │   ├── domain/
        │   │   ├── model/Todo.kt            # 领域模型
        │   │   └── repository/TodoRepository.kt  # 仓库接口
        │   └── ui/
        │       ├── todo/                    # Activity / ViewModel / Adapter / Dialog
        │       └── widget/                  # TodoProgressView、EmptyStateView
        └── res/
            ├── layout/                      # activity_todo_list.xml、item_todo.xml、dialog_todo_edit.xml
            ├── values/                      # strings（中文）、colors、themes、attrs
            └── drawable/                    # 图标与启动图（全部为矢量图）
```

### 分层约定

- `ui` 只依赖 `domain`，不直接访问 Room / DAO。
- `domain` 只放领域模型与仓库接口，不依赖 Android 或 Room 类型。
- `data` 负责实现 `domain` 的仓库接口，完成 Entity ↔ Domain 的转换。
- Activity 只做视图装配与事件转发；业务状态在 ViewModel；数据访问在 Repository。
- 新增全局依赖时只需要改 `AppContainer`，避免在 Activity 里散落构造逻辑。

## XML 布局 vs 代码构建 View

项目刻意同时保留两种写法，并约定适用边界，避免后续维护时「同一类界面两种风格混用」。

| 场景 | 推荐方式 | 本项目示例 |
| --- | --- | --- |
| 结构相对静态的页面、列表项、表单 | XML + ViewBinding | `activity_todo_list.xml`、`item_todo.xml`、`dialog_todo_edit.xml` |
| 需要自绘、自定义测量、强视觉定制 | Kotlin 自定义 View | `TodoProgressView`（`onMeasure` / `onDraw` + 自定义属性） |
| 结构固定但需要运行时创建、注入回调的模块 | Kotlin 组装 ViewGroup | `EmptyStateView`（构造时组装图标、文案、按钮，无对应 XML） |

团队约定：

1. 静态结构优先 XML：可预览、样式集中、改版成本低，设计师也能直接协作。
2. 只有以下情况才用 Kotlin 建 View：自绘 / 自定义测量、构造时需要注入参数或回调、需要在运行时动态增删子 View。
3. 代码构建的 View 必须独立成类、有明确入口（构造函数参数或工厂方法），不允许把大段 `addView` 散落在 Activity 中。
4. 命名统一：自绘控件用 `XxxView`，代码组装的复合控件用 `XxxView` / `XxxWidget`，放在 `ui/widget` 下。
5. 两种写法共存而不是互相替代：XML 负责页面骨架与主题属性，代码负责动态/自绘模块；也不把纯静态页面整页写成代码。

本项目同时保留两种方式的原因：待办列表本身是标准静态结构，用 XML + ViewBinding 维护成本最低；完成进度需要精确绘制，空状态需要在运行时注入「新增」回调，这两处用 XML 反而会增加无意义的布局文件与查找成本。

## 如何新增一个页面

以新增「统计页」为例：

1. 如需新的业务模型，在 `domain/model` 增加数据类。
2. 在 `domain/repository` 增加接口方法，并在 `data/repository` 实现所需查询。
3. 在 `di/AppContainer` 暴露需要的仓库实例。
4. 在 `ui/` 下按业务新建包（如 `ui/stats`），创建 `XxxViewModel`（用 `StateFlow` 暴露 UI 状态）与 `XxxActivity` / `XxxFragment`。
5. 新建 XML 布局，通过 `ViewBinding` 访问控件，不要使用 `findViewById`。
6. 文案统一写入 `res/values/strings.xml`；新 Activity 记得在 `AndroidManifest.xml` 注册。
7. 执行 `./gradlew assembleDebug` 验证。

## 如何新增一张数据表

以在 `TodoDatabase` 中新增 `TagEntity` 为例：

1. 在 `data/local` 新建 `@Entity(tableName = "tags")` 的实体类。
2. 新建对应 `@Dao`，查询返回 `Flow` 或使用 `suspend` 方法。
3. 在 `TodoDatabase` 的 `entities` 中加入新实体，`version` 加 1，并编写 `Migration`：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
    }
}
```

4. 在 `Room.databaseBuilder(...)` 中注册该 `Migration`，不要在生产环境使用破坏性迁移。
5. 构建后 `app/schemas/<数据库类名>/2.json` 会生成，**随代码一起提交**，作为后续迁移的依据。
6. 如需在界面使用，按上面的步骤补 Repository 与 ViewModel。

## 构建与运行

前置条件：

- JDK 17 及以上（本项目使用 **JDK 21** 验证；AGP 8.13 不支持 JDK 25）
- Android SDK Platform 36 与对应 Build Tools
- 根目录的 `local.properties` 指向本机 SDK（该文件不入库）：

```properties
sdk.dir=/Users/platojobs/Library/Android/sdk
```

命令行构建：

```bash
# 指定 JDK 21，避免使用系统默认的 JDK 25
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

./gradlew assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug         # 静态检查
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开工程后 Run。

## 构建与验证记录

| 检查项 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| Debug 构建 | `./gradlew assembleDebug`（JDK 21） | BUILD SUCCESSFUL，生成 `app-debug.apk`（约 6.7 MB） |
| 静态检查 | `./gradlew lintDebug` | BUILD SUCCESSFUL，0 error |
| Room 表结构 | 构建后检查 `app/schemas` | 已生成 `TodoDatabase/1.json` |
| 纯 Kotlin | `find app/src -name "*.java"` | 0 个 `.java` 文件 |
| 无 Compose | 检查构建脚本与版本目录 | 未出现任何 Compose 插件或依赖 |

首次构建时 `compileDebugKotlin` 曾因 Fragment Result API 的 Kotlin 扩展未引入而失败，改为使用 `parentFragmentManager.setFragmentResult(...)` 后构建通过。

## 版本

当前版本 `0.1.0`（tag：`v0.1.0`）。
