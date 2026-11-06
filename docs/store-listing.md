# Google Play 商店上架材料（v0.31.0）

> 状态：**材料已补齐，但尚未上传 Play Console、尚未提交审核、未上架**。
> 本仓库的 release 签名使用**本地自签名测试证书**（`scripts/make-upload-keystore.sh` 生成），
> **不能用于 Play 上传**；正式上架必须换成 Play App Signing（或团队持有的正式上传密钥）。
>
> 本文只描述仓库中**已实现并有代码证据**的能力；未实现的能力（账号密码、云备份、OTA、
> 购物/支付、广告）一律不写入商店文案。

应用信息：

| 项 | 值 |
| --- | --- |
| 应用名称 | `BLE 设备数据面板`（9 个字符，Play 上限 30） |
| 包名 | `com.platojobs.pkotlinapp` |
| 版本 | `versionCode = 40`、`versionName = 0.31.0` |
| 默认语言 | 中文（简体），另含英文 `values-en` |
| 分类 | 工具（Tools）；次要分类建议「健康与健身」，因部分设备上报心率 |
| 联系方式 | https://github.com/platojobs/PKotlinApp/issues |
| 隐私政策 | `docs/privacy-policy.md`（上架前需发布为公网可访问 URL） |

---

## 1. 简短说明（≤80 字符）

```
扫描连接 BLE 设备，查看实时读数、历史曲线与本地告警场景。
```

共 31 个字符（含标点），远低于 80 字符上限。

## 2. 完整说明（≤4000 字符）

```
BLE 设备数据面板是一款本机优先的蓝牙低功耗（BLE）设备工具，用于查看传感器读数、
信号质量、历史曲线与本地告警。不依赖账号密码，不包含广告与任何交易功能。

【扫描与连接】
· 使用系统 BLE 扫描发现附近设备，展示广播名、MAC、信号强度（RSSI）与信号条。
· 连接后按 GATT 特征读取设备实际上报的温度、湿度、电量、心率、二氧化碳、气压等读数；
  设备不上报的指标不会出现在列表中。
· Android 12 及以上使用「附近的设备」权限；Android 11 及以下按系统限制使用位置权限完成扫描。
  应用不读取、不使用、也不上传位置信息。

【读数与历史】
· 实时读数、最近记录与 24 小时汇总（最小 / 最大 / 平均 / 最新）。
· 历史曲线支持 1 小时、24 小时、7 天、30 天区间；离线时回退本机 7 天数据并明确标注。
· 读数与设备信息保存在本机 Room 数据库；可按设备、指标、区间导出 CSV，
  或从账号导出 JSON，文件由用户主动通过系统分享发出。

【告警与自动化】
· 本地规则：信号低于阈值、设备断开、绑定丢失，以及按指标阈值（如温度 / 湿度 / 电量 /
  心率 / 二氧化碳）触发的规则，支持连续 N 条防抖与冷却节流。
· 命中后发送本地通知，可设置 1 / 5 / 30 分钟节流；可一键静音告警 10 / 30 / 60 分钟。
· 场景：把「连接 / 断开指定设备、开始 / 停止后台采集、静音告警、刷新同步」组合成本机
  有序动作，一键执行并逐条反馈成功或失败原因。场景仅保存在本机，不参与云端同步。

【多家庭与房间】
· 首页按房间分段展示设备卡片，房间头可折叠，「未分配」固定最后。
· 支持创建多个家庭与房间、设备长按移动房间；服务端房间为权威来源，
  离线编辑会明确标注「未同步」。

【设备与型号库】
· 设备卡片网格：连接状态、最近一条读数、真实 RSSI 信号条，支持搜索、排序与分组筛选。
· 产品页展示型号库的厂商、型号、固件版本与 GATT 服务元数据；市场页展示配件目录的
  厂商、SKU 与兼容性。市场仅作信息展示，不包含购物车、下单、支付或任何交易功能。

【同步与匿名账号】
· 使用匿名账号同步设备与读数，无需邮箱、手机号或密码；token 通过 Android Keystore 加密保存。
· 支持用 8 位配对码在旧设备生成、在新设备接管同一匿名账号；支持导出账号 JSON
  与在应用内注销账号。
· 服务端读数保留 90 天；本机已同步读数保留 7 天，未同步读数保留到同步成功为止。
· 生产环境使用 HTTPS；断网或后端不可用时自动降级为纯本地模式，不影响查看本机数据。

【隐私与数据安全】
· 不含广告 SDK、不含统计分析 SDK、不接入第三方社交 SDK。
· 不收集位置、通讯录、短信、照片、文件、日历、广告 ID；崩溃与错误只写本地日志，不上传。
· 详见 docs/privacy-policy.md 与 docs/data-safety.md。

【已知限制】
· 需要设备本身支持标准 GATT 服务或 NUS 透传；未实现 OTA 固件升级与 GATT 写入。
· 后台采集依赖前台服务与通知权限；Android 13 及以上拒绝通知权限时仍可采集，但通知不可见。
· 当前未接入 FCM，推送令牌注册流程处于「未配置」状态，告警只在本机提醒。
```

以 `scripts` 之外的字符统计方式核对时，请包含换行与标点；上架前可用：

```bash
python3 - <<'PY'
import re, pathlib
text = pathlib.Path("docs/store-listing.md").read_text(encoding="utf-8")
# 取「## 2. 完整说明」到「## 3.」之间的代码块
block = re.split(r"^## 3\.", text, flags=re.M)[0].split("```")[1]
print(len(block.strip()))
PY
```

## 3. 商店截图（Google Play 规格）

目录：[`docs/screenshots/play/`](screenshots/play/)，共 **6 张**，均为 **1080×1920（16:9）**，
符合 Play「手机截图 2–8 张、16:9 或 9:16、最短边 ≥320px、最长边 ≤3840px」要求。

截图由 `scripts/make-store-screenshots.py` 基于 `docs/screenshots/` 中的**真实模拟器截图**
排版生成；不使用设计稿、不合成界面、不虚构功能。每张图的文案只描述该图中实际可见的内容：

| 文件 | 源截图 | 文案 |
| --- | --- | --- |
| `01-home.png` | `home.png` | 首页 · 今日概览与设备状态 |
| `02-device-detail.png` | `device_detail.png` | 设备详情 · 历史曲线与 24 小时汇总 |
| `03-scenes.png` | `scenes.png` | 智能 · 本机场景一键执行 |
| `04-products.png` | `products.png` | 产品 · 设备类型目录 |
| `05-household.png` | `profile_household.png` | 我的 · 家庭与成员 |
| `06-scan-logs.png` | `profile_scan_logs.png` | 我的 · 扫描日志 |

标题只写图中真实可见的元素：`home.png` 没有设备卡片，故不写「设备卡片」；`device_detail.png`
没有阈值告警，故不写「阈值告警」；`products.png` 只有设备类型目录，没有 GATT 服务表；
`profile_household.png` 只有家庭与成员行，没有房间行。

### Feature Graphic

`docs/screenshots/play/feature-graphic.png`，**1024×500**，用于 Play 商店列表页顶部的
Feature Graphic 位（必填素材，展示在截图上方）。

- 文案：应用名「BLE 设备数据面板」+ 真实卖点「扫描连接 BLE 设备 / 实时读数与本地告警」+
  「无需账号密码 · 数据保存在本机」。
- 视觉：只使用 `app/src/main/res/values/colors.xml` 的设计令牌（`primary #175CD3`、
  `chart_band #E8EFFB`、`chart_line_2 #7A5AF8`、`text_primary #101828` 等），右侧为抽象信号柱，
  **不含任何界面截图或虚构 UI**。
- 格式：PNG（Play 接受 PNG/JPEG，1024×500，无透明）。

重新生成：

```bash
python3 scripts/make-store-screenshots.py
```

**未生成**：平板截图、宣传视频。平板截图可选，宣传视频可选。

## 4. 分类与标签

- 应用分类：**工具（Tools）**
- 次要分类（可选）：健康与健身（部分设备上报心率）
- 标签建议：蓝牙、BLE、传感器、设备管理、数据导出、本地自动化
- 不包含：购物、社交、游戏、新闻、金融等类别能力

## 5. 内容分级要点（IARC 问卷）

| 问题 | 回答 | 依据 |
| --- | --- | --- |
| 暴力、血腥、恐怖内容 | 无 | 界面仅有设备/读数/规则/设置 |
| 性暗示、裸体、粗俗语言 | 无 | 无 UGC 展示 |
| 赌博、毒品、酒精、烟草 | 无 | 无相关功能与内容 |
| 用户生成内容 / 用户间互动 | 无 | 无评论、聊天、动态、社交入口 |
| 位置共享 | 无 | 不调用定位 API，不上传位置 |
| 广告 | 无 | 无广告 SDK、无广告位、无广告 ID |
| 应用内购买 | 无 | 无内购、订阅、购物车、订单、支付 |
| 数据收集与共享 | 有（仅健康信息、设备标识与传感器读数） | 见 §6 与 `docs/data-safety.md` |

建议分级结果：**全年龄（3+ / Everyone）**。最终以 Play Console 问卷结果为唯一准绳。

## 6. Data safety 表单逐项答案（与 `docs/data-safety.md` 对齐）

| Play 表单问题 | 答案 | 与 `data-safety.md` 对应 |
| --- | --- | --- |
| 应用是否收集或共享必需用户数据类型？ | **是** | §1 数据收集与共享总表 |
| 所有收集数据是否传输加密？ | **是（生产 HTTPS）** | §2 安全实践 |
| 是否提供数据删除方式？ | **是（应用内注销账号；另可移除设备/清空日志）** | §2 |
| Health and fitness → Health info | **收集（心率）**；不共享；用途「应用功能」；可选（设备支持才产生） | §1 第 1 行 |
| Device or other IDs | **收集**（蓝牙 MAC、广播名、安装 UUID、匿名 userId）；不共享；用途「应用功能」 | §1 第 2 行 |
| App activity → Other actions | **收集**（传感器读数、数值、单位、时间戳、RSSI 扫描记录）；不共享；用途「应用功能」 | §1 第 3 行 |
| Location（精确/粗略） | **不收集**；`ACCESS_FINE_LOCATION` 仅 `maxSdkVersion=30` 用于 BLE 扫描限制 | §1 第 5 行 |
| Personal info | **不收集**（匿名账号，无邮箱/手机号/姓名/密码） | §1 第 6 行 |
| Financial info | **不收集** | §1 第 7 行 |
| Messages / Contacts / Calendar / Photos / Files / Web browsing | **不收集** | §1 第 8 行 |
| Advertising ID | **不收集** | §1 第 9 行 |
| Crash logs / Diagnostics | **不收集**（无 Crashlytics/Sentry/Firebase；错误仅本地 Log 与 `SyncPrefs.lastError`） | §1 第 4 行 |
| 推送通知令牌 | **不收集**（本版本无 `google-services.json`，`UnavailablePushTokenProvider` 不产生 token，注册流程停在「未配置」） | §3 第 8 条 |
| 数据共享给第三方 | **无** | §1 共享结论 |
| 数据收集是否可选 | **同步需要匿名账号；不联网时以纯本地模式运行** | §4 |
| 是否独立安全审查 | **否** | §2 |
| 是否遵循 Families Policy | **否（不面向儿童）** | §2 |
| 是否包含广告 / 内购 | **均否** | §2 |

填写时以 Play Console 实际选项为准；若控制台提供更贴切的类别（例如把传感器读数归到
「App activity → Other in-app actions」之外的选项），按控制台口径选择，并同步更新
`docs/data-safety.md`。

## 7. 发布产物与签名（详细验证见 `docs/release-build.md`）

| 产物 | 路径 |
| --- | --- |
| release APK | `app/build/outputs/apk/release/app-release.apk` |
| release AAB | `app/build/outputs/bundle/release/app-release.aab` |
| R8 映射 | `app/build/outputs/mapping/release/mapping.txt` |

⚠️ 这些产物由**自签名测试证书**签署，仅用于验证签名构建链路与产物真实性，
**不能上传 Play Console**。正式上架必须使用 Play App Signing 或正式上传密钥。

## 8. 仍未验证的部分

- **真机安装/运行**：本轮产物只在模拟器与 JVM/CI 中验证，未在真机安装 release APK。
- **Play Console 上传**：未上传，未做内部测试轨道验证。
- **Play 审核**：未提交，未经过 Google 审核。
- **Feature Graphic（1024×500）**：已生成并随文档入库，但未随应用实际提交到 Play Console。
- **隐私政策公网 URL**：`docs/privacy-policy.md` 尚未发布为公网可访问地址。
