# v0.32.12 UI 走查证据

v0.32.11 复核截图时发现的新缺陷：200% 字号下首页搜索框占位文字被硬裁，`MAC` 完全不可见。

## 取证环境与方法

- 设备：Pixel_10a（headless emulator），1080 × 2424 px，420 dpi，**1dp = 2.625px**，状态栏 inset 142px。
- 字号：100% 与 200%。
- 取证：`adb shell uiautomator dump` 取节点 `text` 与 `bounds`；`adb exec-out screencap -p` 截图；占位文字的实际像素宽度用截图内暗色像素范围测得（`PIL`），不依赖无障碍标签。

## 问题：200% 下首页搜索框占位文字被裁

- 文件：`app/src/main/res/layout/fragment_home.xml`、`HomeFragment.kt`
- 场景：首页顶部「搜索框 + 扫描添加」同一行。
- 现象（v0.32.11，修复前）：
  - 搜索框 `searchLayout` bounds `[42,701][629,902]`，可用宽度 **587px（223.6dp）**。
  - 占位文字「搜索设备名称或 MAC」从 x=88 起绘制，完整像素宽度 **708px**；框内可见部分止于 x=622（**535px**），**173px（24.4%）被裁**，`MAC` 不可见。
  - 同字号下「扫描添加」按钮 `[650,701][1038,902]` 占掉 388px 行宽（36%），是搜索框变窄的直接原因。
- 修复：`fontScale >= 1.5` 时把「搜索框 + 按钮」这一行改为纵向堆叠——搜索框 `MATCH_PARENT` 独占整行，按钮换到下一行右对齐（`topMargin=space_8`）；正常字号保持同一行、按钮与输入框等高。
- 断言（修复后，200%）：
  - 搜索框 `searchRow` `[0,785][1080,1171]`；`searchInput` `[42,806][1038,1007]`，可用宽度 **996px（379.4dp）**，占位文字 708px 完整显示，右侧余量 288px。
  - 按钮 `scanAddButton` `[650,1028][1038,1171]`，右边界 x=1038 与页面 `page_margin` 对齐，与搜索框间距 21px（8dp）。
- 断言（修复后，100%，回归对照）：
  - `searchInput` `[42,653][753,809]`，按钮 `[774,653][1038,809]`，与 v0.32.11 完全一致，未回归。
- 截图：
  - `before-home-search-200.png`（v0.32.11，占位文字止于「搜索设备名称或」）
  - `after-home-search-200.png`（完整显示「搜索设备名称或 MAC」，按钮下一行右对齐）
  - `after-home-search-100.png`（100% 保持同一行等高）

## 设计取舍

- **100% 下仍保持同一行**：按钮与输入框等高、中心一致，无裁切；只有大字号才堆叠，避免改变常规字号下的首屏密度。
- **大字号按钮不再强制与输入框等高**：堆叠后两者上下排列，等高只会让按钮显得过高（200% 下输入框 201px）；按钮改为 `wrap_content` + `minHeight=input_h`，自然高度 143px（54.5dp）。

## 回归

- 单测：`./gradlew :app:testDebugUnitTest`（463 通过 / 0 失败）
- Lint：`:app:lintDebug`（0 error / 11 warning，均为既有 i18n PluralsCandidate）
- e2e（fixture / Postgres）：`./scripts/e2e-contract.sh`、`./scripts/e2e-postgres.sh` 通过
- Release：`:app:assembleRelease` 成功（混淆与资源收缩配置未改动）
