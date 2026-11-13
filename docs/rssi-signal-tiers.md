# RSSI 信号强度分档规范（跨端唯一口径）

状态：已定稿，Android 为实现参考；iOS 按第 11 节逐条对齐。
真源（唯一可改的地方）：`app/src/main/java/com/platojobs/pkotlinapp/ui/common/RssiLevel.kt`
测试：`app/src/test/java/com/platojobs/pkotlinapp/ui/common/RssiLevelTest.kt`

## 1. 结论

- 分 5 个有效档 + 1 个未知档。判定按 dBm 从高到低逐级比较，**每个合法整数 dBm 恰好命中一档**。
- 边界全部采用「档位下限含、上限不含」的降序包含判定；相邻档位之间没有缝隙，也没有重叠。
- 「未知」与「极弱」是两档：两者都是 0 格，但文案不同。**不得把未知渲染成「信号极弱」。**
- 合法 RSSI 恒为负数。`0`、`127` 等非负值不是真实读数，**一律归入未知档**，不得显示成满格。
- Android 现有阈值（-55 / -66 / -75 / -85）经复核站得住，**本轮不改数值**，只把口径写死并消除实现里的歧义。
- 告警规则阈值（场景 `-85`、规则默认 `-70`）是用户可配置的触发条件，**不是显示档位**；其比较符统一为 `<=`（见第 6 节）。

## 2. 阈值表（规范性）

| 档位 | 内部名 | dBm 区间（整数，闭区间） | 4 格制格数 | 中文文案 | 英文文案 |
| --- | --- | --- | --- | --- | --- |
| 很强 | `STRONG` | `rssi >= -55` | 4 | 信号很强 | Strong signal |
| 良好 | `GOOD` | `-66 <= rssi <= -56` | 3 | 信号良好 | Good signal |
| 一般 | `FAIR` | `-75 <= rssi <= -67` | 2 | 信号一般 | Fair signal |
| 较弱 | `WEAK` | `-85 <= rssi <= -76` | 1 | 信号较弱 | Weak signal |
| 极弱 | `VERY_WEAK` | `rssi <= -86` | 0 | 信号极弱 | Very weak signal |
| 未知 | `UNKNOWN` | `null`、哨兵 `Int.MIN_VALUE`，或任何非负值（`rssi >= 0`） | 0 | 无信号数据 | No signal data |

图标：4 根等高递增竖条（第 N 根最高），从左往右点亮前 N 根；0 格表示 4 根全部为未点亮色。

### 判定伪代码（必须按此顺序）

```
if (rssi == null || rssi == Int.MIN_VALUE) return UNKNOWN   // 0 格
if (rssi >= 0) return UNKNOWN                              // 非负值不是真实读数
if (rssi >= -55) return STRONG                             // 4 格
if (rssi >= -66) return GOOD                               // 3 格
if (rssi >= -75) return FAIR                               // 2 格
if (rssi >= -85) return WEAK                               // 1 格
return VERY_WEAK                                           // 0 格
```

## 3. 边界与未知值规则

1. `-55` 属于 4 格；`-56` 属于 3 格。
2. `-66` 属于 3 格；`-67` 属于 2 格。
3. `-75` 属于 2 格；`-76` 属于 1 格。
4. `-85` 属于 1 格；`-86` 属于 0 格（极弱）。
5. 不存在「两档都覆盖」或「两档都不覆盖」的合法整数：上表并集覆盖全部负整数，交集为空。
6. 越界负数（如 `-127`、`-200`）仍是合法读数，只是低于下限，归入 `VERY_WEAK`。
7. **任何非负值（`0`、`127` 等）都不是真实读数，一律归入 `UNKNOWN`（0 格）**：合法 RSSI 恒为负数；`0` 是云端同步的占位值，`127` 是部分协议的最大值/占位。展示层必须先用 `RssiLevel.isValid` 过滤，**不得出现「0 dBm 满格」**。
8. 未知只有三种等价输入：`null`；跨组件无法传 `null` 时使用的哨兵 `Int.MIN_VALUE`（Android 常量 `UNKNOWN_RSSI`，`Models.kt:195`）；以及任意非负值。三者必须映射到同一档、同一文案、0 格。
9. iOS 没有 `Int.MIN_VALUE` 约定，未知请直接用 `nil`，并把 `rssi >= 0` 一并判为未知；若历史代码用 `-100` 之类的「魔法值」表示未知，**必须改为可选值**，因为 `-100` 在本表中是合法的极弱读数。

## 4. 非 4 格控件

所有现行布局都是 4 格（`item_device_card.xml:74`、`item_live_device.xml:43`、`activity_device_detail.xml:77`）。若将来改用其它格数 `N`：

```
bars = ceil(level / 4 * N)，并夹取到 [0, N]；level == 0 时恒为 0 格
```

例：`N = 5` 时，1 档 → 2 格，2 档 → 3 格，3 档 → 4 格，4 档 → 5 格。
**档位与文案不随格数变化**，只有点亮根数变化。

## 5. 取值来源与未知的处理

| 场景 | 来源 | 是否真实 RSSI |
| --- | --- | --- |
| BLE 扫描 | `ScanResult.rssi` → `BleScanner.kt:56` → `ScannedDevice.rssi` | 是 |
| 已连接轮询 | `BluetoothGatt.readRemoteRssi()` → `BleConnectionManager.kt:328`，每 2000ms（`DeviceDetailViewModel.kt:226-227`） | 是 |
| 云端同步来的设备 | `RemoteSyncDataSource.kt:398`、`HouseholdRepository.kt:626`、`DeviceDetailViewModel.kt:556` 写 `0` 占位 | **否** |
| 通知 / 深链 | `AlertNotificationFactory.kt:40`、`PushIntentRouter.kt:24`、`CollectionNotificationFactory.kt:54` 用 `UNKNOWN_RSSI` | 否 |

展示层必须经过 `Device.localRssi`（`Models.kt:210`）把占位 `0` 转成 `null`，再交给 `RssiLevel`；
`sentinel` 之外，`RssiLevel.isValid` 会对**任何来源**的非负值兜底判为未知，避免绕过 `localRssi` 的调用点出现满格。
已按此收敛的调用点：`HomeFragment.kt:768`、`HomeAdapter.kt:466`、`ProductDetailActivity.kt:254`、`HomeViewModel.kt:665`（按信号排序时未知排最后）、`DeviceDetailActivity.kt:363-372`。

## 6. 规则阈值比较符（告警，不属于分档）

- 旧 RSSI 规则（`RuleType.LOW_RSSI`）的判定为 **`scannedRssi <= rule.threshold`**，即**达到阈值即触发（含等号）**。
- 原因：RSSI 是整数 dBm，用户设 `-80`、设备停在 `-80` 是常见情况。原先 Android 用 `<`、iOS 用 `<=`，同一设备同一读数会出现「一端告警、一端不告警」的分歧。
- 恢复条件是严格反向：只有 `scannedRssi > rule.threshold` 才走恢复，避免在阈值上反复抖动。
- 指标阈值规则（`RuleType.METRIC_THRESHOLD`）**不受此条影响**：其比较符由用户显式选择的 `RuleComparator`（`<` / `<=` / `>` / `>=`）决定，不在这里统一。
- 代码：`RuleEngine.kt:56-63`；单测：`RuleEngineTest` 的「等值触发」「高 1 dBm 不触发」两条。
- 文案同步：事件消息写「已达到阈值」（`rule_event_low_rssi`），场景摘要写 `RSSI ≤ -75 dBm`（`scene_trigger_summary_low_rssi`）。

## 7. Android 使用位置

| 位置 | 显示内容 | 代码 |
| --- | --- | --- |
| 首页设备卡片 | 仅本机已连接时显示信号条；未连接不显示 | `HomeAdapter.kt:464-466` |
| 扫描结果行 | `-72 dBm` 文字 + 信号条 | `HomeAdapters.kt:45` |
| 设备详情 | `-72 dBm` 文字 + 信号条 + 读数时间（扫描初值 → 连接后每 2s 刷新） | `DeviceDetailActivity.kt:363-387` |
| 扫描历史 | 仅 `-72 dBm` 文字 | `ScanLogAdapter.kt:38` |
| 无障碍 | `信号强度：信号良好` | `RssiSignalView.kt:94-99` |

排序用原始 dBm 降序（`HomeViewModel.kt:665`），不按档位排序；未知值排在最后。

## 8. 为什么保留现有阈值

- **步长**：-55 → -66 → -75 → -85，级差 11 / 9 / 10 dB。室内 2.4 GHz 路径损耗指数约 3，10 dB 对应约 2 倍距离，即每降一档大致「距离翻倍」，符合用户对「掉一格」的直觉。
- **下限 -85**：BLE 1M PHY 典型灵敏度约 -95 dBm，保留 10 dB 余量——仍可稳定连接但已接近边缘。该值与产品既有的弱信号默认阈值（`SceneModels.kt:61` 的 `-85`）一致，不引入第二套语义。
- **上限 -55**：对应近场（约 0.3–1 m 视距，或 +4 dBm 发射功率），再强也不会更「有意义」，所以封顶为 4 格。
- **中间两档 -66 / -75**：分别约 1–3 m、3–10 m，覆盖家居场景的主要可用区间。

因此本轮**不改阈值数值**；改动集中在「未知 vs 极弱」的歧义、非负非法值、规则比较符，以及文档化。

## 9. iOS 现状与差异（需修正）

iOS 实现：`codemagic/DesignSystem/RSISignalView.swift`。

- 阈值（`RSISignalView.swift:81-84`）：`>= -55` → 4，`-67...-56` → 3，`-77...-68` → 2，`-83...-78` → 1，其余 0。
- 文件头注释（`RSISignalView.swift:4`）声称与 Android 一致，并写作 `≥ -67 / -78 / -90`——与它自己的代码（-67 / -77 / -83）也不一致，属于过期注释，应删除或改为引用本文档。

逐值差异（Android 为准）：

| dBm | Android | iOS | 是否一致 |
| --- | --- | --- | --- |
| -55 … -66 | 3 或 4（-55 为 4） | 同 | 一致 |
| **-67** | 2 | 3 | 不一致 |
| -68 … -75 | 2 | 2 | 一致 |
| **-76** | 1 | 2 | 不一致 |
| **-77** | 1 | 2 | 不一致 |
| -78 … -83 | 1 | 1 | 一致 |
| **-84** | 1 | 0 | 不一致 |
| **-85** | 1 | 0 | 不一致 |
| ≤ -86 | 0（极弱） | 0（只有「未知」文案） | 格数一致、文案缺失 |

## 10. 两端剩余差异与决策

档位阈值、边界归属、未知判定与文案统一之后，两端仍保留以下差异，均已确认为**有意为之**：

| 项目 | iOS | Android | 决策 |
| --- | --- | --- | --- |
| 颜色 | 按档着色：4 / 3 档绿、2 档橙、1 档红（`RSISignalView.swift:91-93`） | 全档统一 `success` 绿（`RssiSignalView.kt:34-37`） | **允许不同**。颜色不影响档位语义；格数、文案与边界必须与本文档一致，不得据颜色反推档位 |
| 未连接设备卡上的信号格 | 不显示 | 不显示（仅 `LOCAL_CONNECTED` 时可见，`HomeAdapter.kt:464-465`） | **两端一致**，维持 |
| 详情页信息组合 | dBm + 格数 + 读数时间 | dBm + 格数 + 读数时间（`DeviceDetailActivity.kt:363-387`，读数时间随每次轮询刷新） | **两端都必须显示三项**，缺一不可 |
| 未知的内部表示 | `isSignalUnavailable` 布尔（`RSISignalView.swift:17`） | `null` / `UNKNOWN_RSSI` | 语义等价，不强求实现方式统一；但对外表现（0 格 + 「无信号数据」）必须一致 |

除上表外没有其它允许的差异：任何 dBm 在两端的档位、格数、文案必须逐值相同。

## 11. iOS 实现清单

1. 把分档函数改为第 2 节的降序 `if` 判定，阈值常量固定为 `-55 / -66 / -75 / -85`，并在 `nil` 之后、`-55` 之前加入 `rssi >= 0 → UNKNOWN`。
2. 删除 `RSISignalView.swift:4` 的过期「与 Android 一致」注释，改为引用本文档。
3. 增加「极弱」文案（0 格但非未知），与「未知」区分；`nil` 与非负值只对应「无信号数据」。
4. 旧 RSSI 规则判定改为 `rssi <= threshold`（达到即触发），恢复条件为 `>`。
5. 边界自测：`-55→4`、`-56→3`、`-66→3`、`-67→2`、`-75→2`、`-76→1`、`-85→1`、`-86→0`；非法值自测：`0→未知`、`127→未知`。
6. 详情页保持 dBm + 格数 + 读数时间三要素；未连接设备卡不显示信号格。
7. 保留按档着色可以，但不要据此改变格数或文案。

## 12. 一致性校验

Android 侧由 `RssiLevelTest` 与 `RuleEngineTest` 保证，包含：

- 每个边界两侧的档位断言；
- `-150 … -1` 全量扫描：不存在未知档、档位单调不减；
- `0` 与 `127` 归未知且 0 格、文案为「无信号数据」，`-55` 仍为 4 格；
- 相邻档位差恒为 1（无缝隙）；
- `null` 与 `Int.MIN_VALUE` 同档且文案不同于极弱；
- 2 / 4 / 5 格换算与夹取；
- 云端占位 `0` 经 `Device.localRssi` 后为未知、而非满格；
- 旧 RSSI 规则在 `rssi == threshold` 时触发，在 `rssi == threshold + 1` 时不触发。

执行：`./gradlew :app:testDebugUnitTest --tests '*RssiLevelTest*' --tests '*RuleEngineTest*'`
