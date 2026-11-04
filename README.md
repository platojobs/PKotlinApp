# BLE 设备数据面板（PKotlinApp）

[![Android CI](https://github.com/platojobs/PKotlinApp/actions/workflows/android.yml/badge.svg)](https://github.com/platojobs/PKotlinApp/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/tag/platojobs/PKotlinApp?label=release)](https://github.com/platojobs/PKotlinApp/tags)

纯 **Kotlin + XML/View** 体系的 BLE 设备数据面板示例工程：BLE 扫描/连接、Room 本地缓存、Retrofit 网络层、本地规则引擎、权限自检，以及类似米家的五模块底部导航。

**不包含 Jetpack Compose**。界面主线是 XML 布局 + ViewBinding，同时按约定保留「Kotlin 代码构建 View」能力（自绘 View 与代码组装 UI）。

---

## 1. 版本目标与技术栈

| 项目 | 取值 |
| --- | --- |
| 语言 | Kotlin 2.2.21 |
| 构建 | Gradle 8.14.3 + AGP 8.13.1 + Kotlin DSL + Version Catalog |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 24（Android Studio 模板默认值） |
| JDK | 21（编译与 CI 均使用 JDK 21） |
| UI | XML + ViewBinding + Material 3，禁止 Compose |
| 本地存储 | Room 2.8.5（KSP） |
| 网络 | Retrofit 2.11.0 + OkHttp 4.12.0 + Moshi 1.15.2 |
| 架构 | MVVM + Repository + 手动 DI（`AppContainer`）+ StateFlow/SharedFlow |

---

## 2. 五模块能力

底部 `BottomNavigationView` 承载五个 Fragment，各自独立包，共用 `ui/common`。

### 首页 Home（`ui/home`）
- BLE 实时扫描主入口：`BluetoothLeScanner` → `ScanCallback` → `callbackFlow`。
- 列表展示名称 / MAC / RSSI，并用自绘 `RssiSignalView` 展示真实信号格数。
- 扫描结果按 MAC 合并排序，节流写入 Room；下方「最近设备」来自 Room，点击进入详情。
- 无数据时插入代码构建的 `EmptyStateView`（带「开始扫描」动作）。
- 运行时权限：Android 12+ 申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`；SDK < 31 追加 `ACCESS_FINE_LOCATION`。

### 设备 Devices（`ui/devices`）
- 已保存设备管理：连接状态、上次出现时间、RSSI 趋势（与上一记录比较）、删除。
- 本地规则：低 RSSI、断开、绑定丢失三类；规则存 Room，可新增 / 启停 / 删除，触发写规则事件。
- 最近触发事件列表；无数据使用真实空状态。
- 点击设备可发起连接并进入详情页。

### 产品 Products（`ui/products`）
- 型号库：厂商、型号、固件版本、GATT 服务与特征表。
- 数据来自 Retrofit（`GET api/products`），失败时显示错误空状态并提供重试。
- GATT 服务/特征行由 Kotlin 代码动态组装（非 XML）。

### 市场 Market（`ui/market`）
- 配件与固件版本信息列表 + 兼容性说明。
- 数据经 `MarketDataSource` 接口抽象，`RemoteMarketDataSource` 走 Retrofit mock；未来接真实商品后端只需新增实现并替换注入，不改 UI/ViewModel。
- **不包含购物车、订单、支付等任何假交易能力**，页面有明确免责声明。

### 我的 Profile（`ui/profile`）
- 权限自检：读取真实的蓝牙扫描/连接/定位授权状态，并跳转系统应用设置。
- 扫描日志：Room 本地记录（扫描会话中每个设备首次出现写一条），可清空。
- 版本与关于：读取 `BuildConfig` 的真实版本号与构建类型。
- 简单设置：主题（跟随系统 / 浅色 / 深色），写入 SharedPreferences 并即时生效。

---

## 3. 工程结构

```
app/src/main/java/com/platojobs/pkotlinapp/
├── PKotlinApplication.kt          # 创建 AppContainer、应用主题
├── di/AppContainer.kt             # 手动 DI：DB、仓库、网络、BLE、规则引擎
├── domain/
│   ├── model/Models.kt            # 领域模型
│   ├── repository/Repositories.kt # 仓库接口
│   └── rule/                      # RuleEngine + 文案接口
├── data/
│   ├── ble/                       # BleScanner / BleConnectionManager
│   ├── local/                     # Room 实体、DAO、数据库
│   ├── repository/                # Room 仓库实现
│   ├── remote/                    # DTO、ApiService、ApiClient、Retrofit 仓库
│   ├── prefs/AppSettings.kt       # 主题等设置
│   └── rule/AndroidRuleTextProvider.kt
└── ui/
    ├── MainActivity.kt            # 五模块底部导航 + 预测性返回
    ├── common/                    # 权限、Insets、RSSI、空状态、时间格式化
    ├── home/                      # 首页
    ├── devices/                   # 设备与规则
    ├── products/                  # 型号库
    ├── market/                    # 市场
    ├── profile/                   # 我的
    └── detail/                    # 设备详情（独立 Activity）
```

### 如何新增一个模块

1. 在 `ui/<feature>/` 新建 `XxxFragment`、`XxxViewModel`（factory 从 `PKotlinApplication.container` 取依赖）、适配器与 XML 布局。
2. 在 `menu/bottom_nav_menu.xml` 增加 item，在 `MainActivity` 的标签映射中登记标题与 Fragment 创建逻辑。
3. 数据访问先定义 `domain/repository` 接口，再在 `data/` 写实现，并注入 `AppContainer`。
4. 空状态一律使用代码构建的 `EmptyStateView`，列表使用 ViewBinding + RecyclerView。
5. 若需要新表：在 `data/local/Entities.kt` 加实体、`Daos.kt` 加 DAO、`AppDatabase` 注册并升级版本；schema 输出到 `app/schemas/`。

---

## 4. 双 View 构建约定（分模块口径）

工程同时保留两条 View 构建路径，按场景选用：

| 方式 | 适用场景 | 本工程代表 |
| --- | --- | --- |
| XML 布局 + ViewBinding | 页面骨架、列表项、表单等可静态描述的结构；类型安全、易预览、易改版 | 五个 Fragment、详情页、全部 `item_*.xml` |
| Kotlin 代码构建 View | 需要按状态/数据动态生成，或需要自定义绘制与自定义属性 | `ui/common/EmptyStateView.kt`、`ui/common/RssiSignalView.kt` |

- `EmptyStateView`：完全由 Kotlin 组装 `LinearLayout + ImageView + TextView + MaterialButton`，提供 `setIcon/setTitle/setHint/setAction`，被首页、设备、产品、市场复用。这是「代码建 View」的统一入口。
- `RssiSignalView`：自绘 View，完整实现 `onMeasure` 与 `onDraw`，支持自定义属性 `rsv_activeColor`、`rsv_inactiveColor`、`rsv_barCount`，直接接收真实扫描 RSSI 并在列表/详情中绘制信号格。

约定：**能用 XML 静态描述的上层结构优先 XML；可复用、随数据变化、需要自定义绘制的控件用 Kotlin 构建**。新增 feature 模块时同样遵守，不允许只在一处堆砌两种写法。

---

## 5. BLE 与 Android 16 适配

### 权限模型

| 权限 | 声明 | 运行时申请 |
| --- | --- | --- |
| `BLUETOOTH_SCAN` | `usesPermissionFlags="neverForLocation"` | Android 12+ |
| `BLUETOOTH_CONNECT` | 普通 | Android 12+ |
| `ACCESS_FINE_LOCATION` | `maxSdkVersion="30"` | SDK_INT < 31 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | `maxSdkVersion="30"` | 安装期授予 |

所有蓝牙调用集中在 `data/ble/`，因权限由 UI 层保证，调用处使用 `@SuppressLint("MissingPermission")`。

### Android 16 广播（已核实常量存在）

在 **compileSdk 36** 的 `android.jar` / sources 中核实：

- `BluetoothDevice.ACTION_KEY_MISSING` = `"android.bluetooth.device.action.KEY_MISSING"`，标注 `@FlaggedApi`、protected broadcast，条件权限 `BLUETOOTH_CONNECT`（部分场景还要求 `BLUETOOTH_PRIVILEGED`）。
- `BluetoothDevice.ACTION_ENCRYPTION_CHANGE` = `"android.bluetooth.device.action.ENCRYPTION_CHANGE"`，同样为 `@FlaggedApi`，需要 `BLUETOOTH_CONNECT`。

因此代码直接引用系统常量，并通过 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 动态注册（系统广播仍可投递）。收到 `ACTION_KEY_MISSING` 时：

1. 界面提示「设备绑定信息丢失，请重新配对」；
2. 将该 MAC 加入 `blockedMacs`，主动断开并**禁止自动重连**；
3. 用户重新配对后可手动点「已重新配对，重试连接」。

> 运行时是否真正投递取决于系统 flag 与权限，**本机无 BLE 真机，未做运行时验证**，详见「已知限制」。

### 其他 Android 16 行为

- **Edge-to-edge**：两个 Activity 调用 `enableEdgeToEdge()`，布局通过 `ui/common/Insets.kt`（`ViewCompat.setOnApplyWindowInsetsListener`）处理状态栏/导航栏/IME 遮挡；工程中不存在 `windowOptOutEdgeToEdgeEnforcement`。
- **预测性返回**：`MainActivity` 与 `DeviceDetailActivity` 均使用 `OnBackPressedCallback`；未重写 `onBackPressed()`，Manifest 也未设置 `enableOnBackInvokedCallback=false`。
- **大屏/横屏**：Manifest 未锁定 `screenOrientation`；列表使用自适应宽度与 RecyclerView，横屏/大屏不会因固定高度崩溃。

---

## 6. 数据层

### Room

数据库 `device_dashboard.db`，4 张表：

| 表 | 关键字段 |
| --- | --- |
| `devices` | `mac` 主键、`name`、`lastRssi`、`lastSeenTimestamp` |
| `rules` | 自增 id、`type`、`threshold`、`enabled`、`createdAt`、`lastTriggeredTimestamp` |
| `rule_events` | 规则触发记录 |
| `scan_logs` | 扫描日志 |

`DeviceDao` 使用 `@Upsert insertOrUpdate`、`suspend getAllDevices`，同时提供 `Flow` 观察。扫描时在协程内节流写入，App 启动即读取历史列表。

### Retrofit / Mock 数据源

网络层位于 `data/remote/`，Moshi 使用 `KotlinJsonAdapterFactory`，仅在 debug 构建开启 BASIC 日志。

当前 BASE_URL（真实可访问，已用 `curl` 验证返回 200 JSON）：

```
https://quickmock.dev/m/ecHT7ex3yQPA/
```

- 该 mock 服务由 Quickmock.dev 提供，任意路径后缀均返回同一份组合 JSON，因此同时支撑 `GET api/device/{mac}`、`GET api/products`、`GET api/market`。
- 有效期约 30 天（创建于 2026-11-04，**预计 2026-12-04 到期**）。到期后需重新生成 mock 并修改 `app/build.gradle.kts` 中的 `API_BASE_URL`。
- 为什么不用 Mocky：实测 Mocky 域名证书校验失败、`api.mocky.io` 返回 404，无法稳定提供可直接访问的 JSON。
- 地址写在 `buildConfigField("String", "API_BASE_URL", ...)`，运行时通过 `BuildConfig.API_BASE_URL` 读取。

样例返回（节选）：

```json
{
  "mac": "AA:BB:CC:DD:EE:FF",
  "vendor": "Nordic Semiconductor",
  "deviceType": "BLE Tracker",
  "firmwareVersion": "1.2.3",
  "products": [
    {
      "id": "nrf52840",
      "vendor": "Nordic Semiconductor",
      "model": "nRF52840 Dongle",
      "firmwareVersion": "2.4.1",
      "note": "支持 BLE 5.0、Thread 与 USB DFU",
      "services": [
        {
          "uuid": "0000180F-0000-1000-8000-00805F9B34FB",
          "name": "Battery Service",
          "characteristics": ["2A19 Battery Level (Read/Notify)"]
        }
      ]
    }
  ],
  "marketItems": [
    {
      "id": "fw-gateway",
      "title": "网关固件",
      "category": "固件",
      "version": "1.4.0",
      "compatibility": "nRF52840 / ESP32-C3",
      "description": "支持多设备转发与 OTA 升级"
    }
  ]
}
```

### 断网降级

- 产品 / 市场：请求失败显示错误文案 + `EmptyStateView` 重试，不展示假数据。
- 设备详情：设备信息失败显示错误提示与「重试读取设备信息」，连接状态与 RSSI 仍来自真实 BLE。
- 首页 / 设备 / 我的：本地 Room 数据不依赖网络，断网仍可用。

---

## 7. 构建与运行

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

./gradlew assembleDebug        # Debug APK
./gradlew lintDebug            # Lint
./gradlew testDebugUnitTest    # 单元测试（RssiLevel 纯函数）
./gradlew assembleRelease      # 签名 Release APK
./gradlew bundleRelease        # 签名 Release AAB
```

Debug 包路径：`app/build/outputs/apk/debug/app-debug.apk`

---

## 8. Release 签名与混淆

### 签名配置（密码不入库）

`app/build.gradle.kts` 从**仓库根目录的 `keystore.properties`** 读取签名信息；该文件与密钥库均被 `.gitignore` 覆盖，不会提交。

`keystore.properties` 示例（放在仓库根目录，**不要提交**）：

```properties
storeFile=../.pkotlinapp-secrets/pkotlinapp-release.jks
storePassword=<你的密码>
keyAlias=pkotlinapp
keyPassword=<你的密码>
```

`storeFile` 相对仓库根目录解析；密钥库放在仓库外（如 `../.pkotlinapp-secrets/`）。

生成密钥库：

```bash
mkdir -p ../.pkotlinapp-secrets
keytool -genkeypair -v \
  -keystore ../.pkotlinapp-secrets/pkotlinapp-release.jks \
  -alias pkotlinapp -keyalg RSA -keysize 2048 -validity 9125
```

若 `keystore.properties` 不存在（例如 CI 环境），`signingConfig` 不会被创建，`assembleRelease` 仍可产出未签名 APK，适合做编译验证。

### Release 三件套

```kotlin
release {
    isDebuggable = false
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

### ProGuard / R8 规则覆盖

`app/proguard-rules.pro` 已覆盖：

- 泛型签名、内部类、运行时注解（Retrofit / Moshi 依赖）；
- Retrofit `ApiService`、`Response`、`Call`、`Continuation`；
- `data.remote.**` DTO 与 Moshi 注解方法、`@JsonQualifier`；
- `kotlin.Metadata`、`kotlin.reflect.**`、`kotlin.jvm.internal.**`；
- Room `data.local.**` 实体与列名；
- OkHttp / Okio 的 `-dontwarn`。

验证命令：

```bash
/Users/<user>/Library/Android/sdk/build-tools/36.0.0/apksigner \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 上架说明

- 版本目标：`compileSdk/targetSdk = 36`，满足 Google Play 新应用的 targetSdk 要求；`minSdk = 24`。
- 权限模型：`BLUETOOTH_SCAN` 标注 `neverForLocation`；低版本才声明定位权限，避免新版过度申请。
- Android 16 适配：edge-to-edge、预测性返回、大屏方向、BLE 新广播监听均已按上文处理。
- 发布前请替换为自己的正式 keystore，并在 Play Console 配置 App Signing；密码与密钥文件不得入库。

---

## 9. 持续集成

`.github/workflows/android.yml`：

- **verify job**（push main / pull_request）：JDK 21（Temurin）、`./gradlew assembleDebug lintDebug testDebugUnitTest`，上传 Lint 与单测报告。
- **release job**（仅 `v*` tag 或手动 `workflow_dispatch`）：执行 `assembleRelease`，**不注入签名密钥**，产出未签名 APK 作为构建验证；如需正式签名请在 GitHub Secrets 配置后自行补充注入步骤，仓库内不写任何密码。

---

## 10. 已知限制与真机自测项

以下内容仅完成**编译与静态验证**，需要在真实 Android 设备上自测：

- [ ] BLE 扫描能否发现真实设备、RSSI 是否随距离变化。
- [ ] 点击设备 `connectGatt()` 后的连接状态流转与 `readRemoteRssi` 轮询。
- [ ] Android 16 `ACTION_KEY_MISSING` / `ACTION_ENCRYPTION_CHANGE` 是否实际投递（受系统 flag 与权限影响）。
- [ ] 绑定丢失后禁止自动重连、重新配对后手动重试的完整流程。
- [ ] 各机型上的权限弹窗、拒绝后跳系统设置的行为。
- [ ] 横屏、大屏、折叠屏的 Edge-to-edge 与列表布局表现。

其他限制：

- Mock 服务为公开临时服务（Quickmock.dev），**预计 2026-12-04 到期**；到期后产品/市场/详情信息接口会失败（界面会显示错误与重试，不会崩溃），需更换 BASE_URL。
- 未实现真实 OTA、GATT 服务发现与写入，仅展示型号库中的 GATT 元数据。

---

## 11. 版本记录

- `v0.2.0`：项目定位改为 BLE 设备数据面板；五模块底部导航、BLE 扫描/连接、Room、Retrofit、规则引擎、Android 16 适配、签名 Release、GitHub Actions CI。
- `v0.1.0`：初始纯 Kotlin + View 待办 Demo（已被本版本覆盖）。
