# v0.32.11 UI 走查证据

本轮为 Android 端布局错位与 UI 细节自驱打磨，共定位并修复 6 类可测量缺陷。

## 取证环境与方法

- 设备：Pixel_10a（headless emulator），1080 × 2424 px，420 dpi，**1dp = 2.625px**，状态栏 inset 142px。
- 字号：`fontScale=1.0`（100%）与 `fontScale=2.0`（200%）两组；100% 与 200% 都覆盖。
- 取证方式：`adb shell uiautomator dump` 读取节点自身 `text` 与 `bounds`（不使用 `content-desc`／无障碍标签作为「画面有文字」的证据），`adb exec-out screencap -p` 截图。
- 数值口径：偏差均以 px 实测并换算 dp，四舍五入到 0.1dp。

## 一、首页搜索框与「扫描添加」按钮错位 + 200% 占位文字被裁

- 文件：`app/src/main/res/layout/fragment_home.xml`、`HomeFragment.kt`
- 场景：首页顶部「搜索框 + 扫描添加」同一行。
- 问题（修复前）：
  - 100%：输入文字中心比右侧按钮中心低 **7.5px（2.9dp）**。
  - 200%：输入文字中心比按钮中心低 **15px（5.7dp）**，且占位文字被 TextInputLayout 的 52dp 固定高度上下裁切。
- 根因：TIL 带浮动 hint 时自身高度随字号变化，但外层 `minHeight` 与按钮的 52dp 固定高度不一致；TIL 内容在框内垂直居中，按钮文字也在按钮内居中，两者基准不同。
- 修复：TIL 关闭 hint（`app:hintEnabled="false"`）、高度改 `wrap_content` + `minHeight=input_h`；EditText 直接设 `android:hint`；按钮高度 `wrap_content` + `minHeight=input_h` 并在 `doOnLayout` 的下一帧与 TIL 实际高度对齐。
- 断言（修复后）：TIL 与按钮 bounds 完全一致，中心偏差 **0px**。
  - 100%：搜索框 `[42,653][753,809]`（宽 711px，高 156px / 59.4dp）；按钮 `[774,653][1038,809]`（高 156px / 59.4dp）。二者 top=653、bottom=809，垂直中心同为 y=731。
  - 200%：搜索框 `[42,701][629,902]`（宽 587px，高 201px / 76.6dp）；按钮 `[650,701][1038,902]`（高 201px / 76.6dp）。二者 top=701、bottom=902，垂直中心同为 y=801.5；输入框上下不再裁切，但横向占位文字仍被裁（v0.32.12 已改为大字号纵向堆叠，见 `verify-v0.32.12/`）。
- 截图：`before-home-100.png` / `after-home-100.png`；`before-home-200.png` / `after-home-200.png`。

## 二、200% 下首页「设备状态」统计块标签被裁

- 文件：`app/src/main/res/layout/item_home_status.xml`
- 场景：首页「设备状态」四格（在线／离线／告警／未连接）。
- 问题（修复前）：单元格固定 `layout_height=48dp`（126px）；计数占 83px 后标签仅剩 **43px** 可见，而 200% 下标签需 168px（两行），约 **125px 被裁**。
- 修复：单元格改 `wrap_content` + `minHeight=touch_min` + `paddingVertical=space_4`，父行 `gravity=center_vertical`。
- 断言（修复后）：单元格高 **273px（104dp）**，计数 83px + 标签完整 168px（两行），无裁切。
- 截图：`before-status-200-crop.png`（放大裁切看被裁标签）/ `after-status-200.png`。

## 三、200% 下智能页分区标题被挤成逐字换行

- 文件：`app/src/main/res/layout/fragment_intelligence.xml`、`IntelligenceFragment.kt`
- 场景：「自动化 / 场景 / 最近运行」三个 section header（左侧标题列 + 右侧动作按钮）。
- 问题（修复前）：标题列宽仅 **260px（99dp）**，标题「自动化」拆 2 行、说明文字 5 行逐字换行。
- 修复：`fontScale >= 1.5` 时 header 改纵向堆叠，标题列 `MATCH_PARENT` 且去掉 weight，动作按钮右对齐并加 `topMargin=space_4`。
- 断言（修复后）：标题「自动化」1 行 `[42,649][1038,782]`（宽 996px、高 133px），说明 1 行 `[42,793][1038,889]`（高 96px），按钮移到下一行右对齐（`推荐规则`[302,900][670,1043]、`新增规则`[670,900][1038,1043]）。
- 截图：`before-intel-200.png` / `after-intel-200.png`；100% 对照 `after-intel-100.png`。

## 四、200% 下「我的」页权限行标签被挤成竖排

- 文件：`app/src/main/res/layout/fragment_profile.xml`、`ProfileFragment.kt`
- 场景：「权限自检」卡片中的定位权限行「定位权限（Android 11 及以下）」。
- 问题（修复前）：左侧标签宽仅 **120px（45.7dp）**，高被挤到 **925px（352dp）** 逐字换行。
- 修复：三个权限行加 id，`fontScale >= 1.5` 时改纵向堆叠，标签 `MATCH_PARENT`，状态文案另起一行右对齐。
- 断言（修复后）：标签「定位权限（Android 11 及以下）」宽 **912px**、高 **220px**（2 行，`[84,1743][996,1963]`）；状态文案另起一行右对齐并结束于 x=996（`[204,1974][996,2071]`）；整行高 328px。
- 截图：`after-profile-permission-200.png`（已滚动到「权限自检」卡片，控件完整入镜）。

## 五、切回已创建的 Tab 后大标题不再随滚动收起

- 文件：`MainActivity.kt`
- 场景：首页 → 智能 → 切回首页后向下滚动。
- 问题（修复前）：`showTab()` 先 `attachHeaderScroll(null)` 解绑，但 Fragment 的 `onViewCreated` 只跑一次，切回后没有重新挂载滚动容器，滚动 4 次后 `headerLargeArea` 仍为 **252px（96dp）**。
- 修复：记录每个 Tab 的滚动容器，切回时 `post` 重新 `attachHeaderScroll()` 并按当前滚动偏移同步一次 `updateHeader()`。
- 断言（修复后）：滚动后 `headerLargeArea` 为 **147px（56dp）**；切回已滚动过的 Tab 也能立即同步收起。
- 截图：`after-home-scrolled-100.png`、`after-home-scrolled-200.png`。

## 六、「我的」页滚动从不联动大标题

- 文件：`ProfileFragment.kt`
- 场景：「我的」页向下滚动。
- 问题（修复前）：此前未调用 `attachHeaderScroll`，滚动 4 次后 `headerLargeArea` 仍为 **252px（96dp）**。
- 修复：`onViewCreated` 中挂载 `binding.profileScroll`。
- 断言（修复后）：滚动后 `headerLargeArea` 为 **147px（56dp）**。
- 截图：`before-profile-scrolled-200.png` / `after-profile-scrolled-200.png`。

## 设计取舍（保留现状，不作为缺陷）

- **200% 下底部导航 label 视觉约 12sp**：受 Material `BottomNavigationView` 规范限制，非裁切，v0.32.9 已单独立项确认，本轮不改。
- **搜索框高度由 Material 输入框自然高度决定**：100% 为 156px（59.4dp），200% 为 201px（76.6dp），长于 `input_h` 的 52dp；按钮跟随等高以达到「同一行垂直居中」。刻意不再把两者压回 52dp，否则会重新引入文字与按钮基准不一致。

## 回归

- 单测：`./gradlew :app:testDebugUnitTest`（463 通过 / 0 失败）
- Lint：`:app:lintDebug`（0 error / 11 warning，均为既有 i18n PluralsCandidate）
- e2e（fixture）：`./scripts/e2e-contract.sh` 通过（9 例，3 例 Postgres 专属跳过）
- e2e（Postgres）：`./scripts/e2e-postgres.sh` 通过（9 例，1 例限流专属跳过；presence 三态与 410 全量重拉均校验）
- Release：`:app:assembleRelease` 成功（混淆与资源收缩配置未改动）
