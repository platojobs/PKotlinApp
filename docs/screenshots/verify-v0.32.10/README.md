# v0.32.10 底部导航复核记录

日期：2026-11-08

本轮只改一处：底部导航 item 内「图标 + 文字」整体的垂直定位。上一轮 v0.32.9 把导航条从 56dp 改到 80dp 后文字已经出现，但内容框仍按 Material 默认的「顶部对齐 + 底部 16dp 内边距」摆放，视觉上整体偏上约 5dp。

## 设备与构建

| 项 | 值 |
| --- | --- |
| 安装包 | 本仓库 `assembleDebug` 构建，`versionCode=54` / `versionName=0.32.10`（`adb shell dumpsys package com.platojobs.pkotlinapp` 实测） |
| Activity | `com.platojobs.pkotlinapp/.ui.MainActivity` |
| 模拟器 | AVD `Pixel_10a`，1080×2424 / 420dpi / API 37 arm64，headless |
| 字号 | 系统 `font_scale` 1.0 与 2.0 |
| 取景 | 整屏截图，导航条完整入镜；`uiautomator dump` 同步取 bounds |

## 取证方法更正

上一轮把 `content-desc`（首页/智能/产品/市场/我的）当成「文字标签存在」的证据，这是错的：`content-desc` 是 TalkBack 描述，画面上可以为空。

本轮改用两条独立证据：

1. `uiautomator dump` 中 label 节点自身：`navigation_bar_item_small_label_view` / `navigation_bar_item_large_label_view` 的 `text` 属性；
2. 截图目视核对文字是否真的被绘制出来。

## 五个 label 的实际 text（修复前后一致，均非空）

| item | text |
| --- | --- |
| `nav_home` | 首页 |
| `nav_intelligence` | 智能 |
| `nav_products` | 产品 |
| `nav_market` | 市场 |
| `nav_profile` | 我的 |

## 数值证据

同一台模拟器、同一分辨率，单位换算 420dpi（1dp = 2.625px）。

| 指标 | 修复前（v0.32.9 构建） | 修复后（v0.32.10 构建） |
| --- | --- | --- |
| item bounds | `[0,2151][216,2361]`，高 210px = 80dp | 同左 |
| icon_container bounds | `[76,2183][139,2246]`，24dp | `[76,2196][139,2259]`，24dp |
| label text bounds | `[76,2257][140,2303]` | `[76,2270][140,2316]` |
| 图标与标签间距 | 11px = 4.19dp | 11px = 4.19dp |
| 视觉整体中心相对 item 中心 | **-13px = -4.95dp（偏上）** | **0px = 0.00dp** |

修复后五个 item 数值完全一致（逐项实测）：

| item | text | item 高 | 图标 y | 标签 y | 中心偏差 | 图标-标签间距 |
| --- | --- | --- | --- | --- | --- | --- |
| `nav_home` | 首页 | 210px / 80.0dp | `[2196,2259]` | `[2270,2316]` | +0.00px / +0.00dp | 11px / 4.19dp |
| `nav_intelligence` | 智能 | 210px / 80.0dp | `[2196,2259]` | `[2270,2316]` | +0.00px / +0.00dp | 11px / 4.19dp |
| `nav_products` | 产品 | 210px / 80.0dp | `[2196,2259]` | `[2270,2316]` | +0.00px / +0.00dp | 11px / 4.19dp |
| `nav_market` | 市场 | 210px / 80.0dp | `[2196,2259]` | `[2270,2316]` | +0.00px / +0.00dp | 11px / 4.19dp |
| `nav_profile` | 我的 | 210px / 80.0dp | `[2196,2259]` | `[2270,2316]` | +0.00px / +0.00dp | 11px / 4.19dp |

「视觉整体」= 图标上沿到标签文字下沿；中心偏差 = 该整体中心 − item 中心。200% 字号实测数值与 100% 完全相同。

## 根因与改法

Material 3 的 `NavigationBarItemView` 默认 `itemPaddingTop = 12dp`、`itemPaddingBottom = 16dp`，且 `contentContainer` 是 `top|center_horizontal`：内容框从 12dp 处开始，底部再留 16dp 死区，图标 + 文字的可见整体因此被顶高。80dp item 下理论偏差 = 12 + 45.5/2 − 40 ≈ −5.25dp，与实测 −4.95dp 吻合。

改法（`app/src/main/res/layout/activity_main.xml`）：

- `app:itemGravity="center"`：内容框在 item 内垂直居中，而不是顶部对齐；
- `app:itemPaddingTop="0dp"` / `app:itemPaddingBottom="0dp"`：去掉顶部 12dp 与底部 16dp 的默认偏移，让参与居中的就是「图标 + 文字」本身。

导航条高度仍为 `minHeight=80dp`，底部手势区仍由 `applyBottomInsetPadding()` 只加 padding 处理，item 内容区不被 inset 挤占。

## 截图与断言

| 文件 | 场景 | 断言结论 |
| --- | --- | --- |
| `nav-100-home.png` | 100%，默认选中「首页」 | 五个 Tab 图标在上、文字在下；文字清晰可读，选中态为主色 + medium 字重；图标 + 文字整体在 item 内居中（实测 0.00dp）；底部手势条在导航条之下，未遮挡。 |
| `nav-100-products.png` | 100%，点击「产品」 | 选中态随点击切换；五个标签无裁切、无半行文字；数值与首页一致。 |
| `nav-200-home.png` | 200%，默认选中「首页」 | 五个标签仍完整可见、无重叠、无裁切；几何数值与 100% 完全相同。 |
| `nav-200-products.png` | 200%，点击「产品」 | 选中态正确；导航条完整位于手势条之上。 |

> 200% 下 nav label 视觉仍约 12sp，是 Material 规范限制（`NavigationBarView` 的 label 不随系统字号放大），不是被裁，也不是本轮引入的问题。

## 关于「v0.32.9 仍缺标题」

在本机上无法复现「label 无文字」：

- 重新构建并安装 `versionCode=54`（改动前同源构建为 `versionCode=53`）后，五个 label 的 `text` 均非空，截图肉眼可见；
- 仓库内 v0.32.9 的归档截图 `docs/screenshots/verify-v0.32.9/nav-100-home.png` 同样能看到五个文字标签；
- 反馈中贴出的 dump（`navigation_bar_item_labels_group` bounds `[294,2289][352,2330]`）与本机当前 item bounds `[2151,2361]` 不在同一坐标系，对应的是 v0.32.9 之前 56dp 导航条的构建；56dp 装不下「图标 + 文字」，label 被排出 item 可见范围，dump 里就会表现为 label group 没有子节点。

因此判断「缺标题」来自旧包。复验前请先执行：

```
adb shell dumpsys package com.platojobs.pkotlinapp | grep versionCode
```

确认 `versionCode >= 54`（v0.32.10）后再看底部导航。本轮真正修掉的是仍然存在的「图标整体偏上约 5dp」。
