# D · 提醒与通知交接说明（第 3 周）

- 日期：2026-11-11　|　提供：成员 D　|　状态：代码完成，自检通过，真机验收见第七节
- 依据：《任务字段与通知规则 v1.0》§4（提醒口径）、《E 成员数据与同步交接》（回执语义）、AGENTS.md 提交前自检规矩

## 一、交付了什么

| 文件 | 作用 |
| --- | --- |
| `reminder/ReminderContract.kt` | 纯逻辑：截止前 24h/1h 槽位、自续链下一槽位、通知放行条件、文案（无 Android 依赖） |
| `reminder/ReminderScheduler.kt` | AlarmManager 调度：`reconcile` 状态对账（幂等）、登记表补撤已删任务、精确闹钟降级窗口 |
| `reminder/ReminderReceiver.kt` | 到点 Receiver（核对→发通知→自续）+ BootReceiver（开机/应用更新重建）+ ReminderCoordinator（attach 对账接驳点） |
| `reminder/Notifications.kt` | 通知渠道 + 到点通知构建（点按打开 App、autoCancel、权限被拒静默放弃） |
| `MainActivity.kt` | 接线：`ReminderCoordinator.attach` 随数据快照对账；通知权限申请时机 |
| `AndroidManifest.xml` | POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED + 两个 receiver（均 exported=false） |
| `app/src/test/.../reminder/` | 21 项单测：槽位计算、调度对账、删除补撤、幂等、回调时机、通知放行 |

全仓测试 **86 项，失败 0**（65 原有 + 21 新增）。<br>
（rebase 到 F 的 CI 提交后全仓为 **93 项**：本模块 86 + F 新增的 `fakedata/BoundaryFixtureTest` 7 项，
同样全绿。）

## 二、架构：状态对账 + 自续链

```
Room 快照（任何来源的写入都会刷新）
   ↓ attach() 逐条快照
ReminderScheduler.reconcile(tasks, now)   ← 幂等：需要的排上，不需要的撤掉
   ↓ 每个任务一条 PendingIntent（requestCode=taskId.hashCode()，data=coursekeeper://reminder/<taskId>）
AlarmManager.setExactAndAllowWhileIdle（API 31+ 不可用则 setWindow 10min）
   ↓ 到点
ReminderReceiver → 核对库内状态（未删/未完成/截止未改）→ 发通知 → 排下一槽位 → 1h 槽到点链条自然结束
```

- **为什么对账而不是事件驱动**：重放/重复快照天然不重复建提醒（满足 E 的 changed && !replayed 语义）；
  首页联动、同步、Agent 写入等任何来源都不需要逐个挂钩；崩溃后重开自动补齐。
- **为什么自续链**：每任务只占一个 PendingIntent，取消重建 = 同 id 覆盖；data URI 参与
  PendingIntent 匹配，taskId.hashCode 相撞也不会互串。
- **重建时机**：App 内存活期随快照对账；开机（BOOT_COMPLETED）与应用更新（MY_PACKAGE_REPLACED）
  由 BootReceiver 整体重建——这两个事件系统都会清空全部闹钟。

## 三、v1.0 §4 口径落地对照

| v1.0 条目 | 落地位置 |
| --- | --- |
| 截止前 24h / 1h 各提醒一次 | `reminderSlots()`，已过时刻不追发 |
| "截止待定"任务不提醒 | `needsReminder()`：dueAt null → cancel |
| 任意字段编辑重建提醒 | 快照驱动对账，标题到点时从库重读 |
| 删除同时取消提醒、不残留 | 调度器登记表：快照中消失即补撤 + 到点时 `shouldFireNotification` 兜底 |
| 完成即撤销提醒 | `needsReminder()` completedAt 判定 → cancel |
| 重放不重复提醒 | 对账幂等（同 id 覆盖），重放不 bump revision 不触发快照 |
| 首个带截止时间任务保存成功后申请通知权限 | `attach` 回调：数据层首次出现 dueAt≠null 任务时触发一次（**入口无关**，含首页快速添加） |
| 拒绝权限仍可用 | 提醒照常排，`notifyDue` 权限检查静默放弃（minSdk 26 下该权限不存在） |

## 四、对其他成员的影响（均为新增，无侵入）

- **A**：MainActivity 仅新增接线块，HomeDataOwner / HomeEvent / 撤销链路原样；`TaskEvent.Saved`
  签名未变（曾短暂加过 dueAt 字段，权限时机下沉后回退，`git diff` 已确认 task/ 包无残留）。
- **E**：零改动，只读 `repository.snapshots`。
- **B / F**：零接触。

## 五、已知限制（有意为之，均经自检确认）

1. **通知不路由到具体任务**：点按通知只打开 App 首页，不定位任务详情（第 4 周联调时可加深链）。
2. **通知 ID 复用 taskId.hashCode()**：两任务哈希相撞时通知互相覆盖（约 2⁻³² 量级，当前任务量可忽略）。
3. **提醒文案无年份**：`MM-dd HH:mm`，跨年截止的时间显示不完整（学期场景跨年概率低）。
4. **权限弹窗只问一次**：rememberSaveable 跨进程死亡保留；用户拒绝后不再补问（可在系统设置里手动开）。
5. **权限申请在后台组合存活但 STOPPED 态的防御**：理论上存在该路径，当前所有写库都源于前台 UI，
   实际不可达；系统对后台权限弹窗也会静默抑制。
6. **设备关机跨过槽位时刻的提醒丢失**：闹钟不跨重启持久，重启后只挂仍未来的槽位（不补发过去的）。

## 五之二、已修的交互缺陷：带"撤销"的 Snackbar 常驻（2026-11-11）

**现象**（用户真机报告）：勾选完成后黑色提示「任务已完成 / 撤销」不消失，点四个底部导航都不消失，
只有点"撤销"才能关掉——但撤销会把任务恢复回待办，违背用户意图。

**根因**：Material3 `SnackbarHostState.showSnackbar(message, actionLabel)` 的默认参数是
`duration = if (actionLabel == null) Short else Indefinite`。**只要带操作按钮就默认常驻**，
而代码从未在切页时主动关闭，于是唯一的出口变成"点撤销"。

**修复**（四处）：
1. 两处带撤销的 `showSnackbar` 显式传 `duration = SnackbarDuration.Long`（约 10 秒；系统无障碍
   "操作时限"开启时会被自动延长）与 `withDismissAction = true`（右上角关闭按钮，文案由 Material3
   的中文资源提供，content-desc = "关闭"）。
2. `AppShell` 新增 `LaunchedEffect(selected) { snackbarHostState.currentSnackbarData?.dismiss() }`
   ——**切页即收起**。覆盖底部导航之外的选中变化：首页卡片跳转、课程详情"查看课表"、系统返回键。
3. `AppShell` 的 `NavigationBarItem.onClick` 里先 `dismiss()` 再导航——覆盖**点当前 tab**
   （`selected` 不变、上述 effect 不重启）的情形，这是"点任意导航都不消失"的直接修复。
4. 无 action 的提示（"任务已保存""任务已删除"）保持 `Short` 自动消失。

第 2、3 条都在 A 的 `AppShell.kt` 里，合计新增约 10 行（含注释），需 A 复核。

**真机验证**（华为 API29）：关闭按钮渲染并可点 ✅；切「首页」后立即消失 ✅；不操作等约 10 秒自动消失
且任务仍保持勾选后的状态、未被误撤销 ✅；在任务页原地再点「任务」tab 也能收起 ✅（instrumented 用例覆盖）。

### 后续：提示遮挡"＋ 新建任务"按钮（同日修复）

**现象**（用户真机报告）：连续新建任务时，提示会正好压住任务页底部的蓝色「＋ 新建任务」按钮几秒，
虽然会自动消失，但影响连续添加的手感；删除操作不受影响。

**根因**：任务页的按钮在**内容层**底部（`Column` 的最后一个子项），而 Snackbar 由 `Scaffold`
的 `snackbarHost` 渲染，它只按 `bottomBar` 定位。任务页的 `bottomBar` 只有底部导航栏
（"手动添加任务"按钮在这一页被隐藏），于是提示正好落在导航栏上方 —— 也就是那个按钮的位置。
首页没有这个问题，因为首页的添加按钮在 `bottomBar` 里，`Scaffold` 会自动让提示避开它。

**修复**（职责分两处，紧凑判定在调用方）：
- `TaskScreen.kt` 提取两个常量：`TaskBottomButtonHeight = 68.dp`（= 48dp 最小点击区 + 上下各
  10dp 边距）与 `TaskCompactHeightDp = 480`（短屏阈值）；`compact` 判定改为引用后者，单一来源。
- `MainActivity` 按 `LocalConfiguration.screenHeightDp >= TaskCompactHeightDp` 决定传
  `TaskBottomButtonHeight` 还是 `0.dp`——紧凑模式下按钮并入头部行内，不需要留位。
- `AppShell` 新增可选参数 `snackbarBottomInset: Dp = 0.dp`，只在"任务页 + 传了 `taskScreen` +
  非引导/加载/读取失败态"时应用到 `SnackbarHost` 的 `padding(bottom=...)`。**AppShell 保持对
  `com.coursekeeper.task` 包的零编译期依赖**，A 的独立演示工程（不传 `taskScreen`）行为不变。

**A 需复核的是 `AppShell.kt` 的新参数 `snackbarBottomInset` 与它的门控条件**（该文件在本次提交里
合计 +24/−3，含切页收起与导航点击收起；`TaskScreen` +11/−1；`MainActivity` 单看"让位接线"这一处
是 +9/−0，整文件因另含 Snackbar 时长修复共 +24/−2）。

**真机验证**：提示底边 1878px、按钮顶边 2028px，间隔 150px 不重叠 ✅；
提示仍可见时立刻点「＋ 新建任务」，表单正常打开（点击未被浮层吞掉）✅。


## 六、提交前质检记录（2026-11-11）

按 AGENTS.md 三步走。首轮双路自检 + 修复增量复查，两轮结论如下。

**首轮路线 2（Compose/集成安全）**：P0=0 P1=0 P2=5，结论可提交。
采纳修复：P2-1 Receiver 协程加 try/catch 防进程崩溃。其余记录在已知限制。

**首轮路线 1（业务逻辑/口径）**：P0=0 **P1=2** P2=6，结论不可提交。两个 P1 均为真问题，已修并补测试：
1. **删除任务后旧闹钟残留**——reconcile 只见快照内任务，被删任务无法被撤。修复：调度器维护
   `registered` 并发登记表，快照中消失的登记任务补撤；新增测试锁定。
2. **首页快速添加入口不触发权限申请**——首页只发 HomeEvent.TaskAdded，原方案权限只挂 TaskEvent.Saved。
   修复：权限时机下沉到 `attach` 的快照检测（数据层首次出现带 dueAt 任务即触发，入口无关）；
   MainActivity 改回调式接线；TaskEvent.Saved 回退原签名。三个测试用例锁定。

**增量复查（修复后）**：P0=0 P1=0 P2=3，结论可提交。P2-1（登记表清理顺序）、P2-3（Receiver 静默吞异常
无日志）已修（登记表清理提前、catch 加 Log.w）；P2-2（后台 launch 防御）记为已知限制 5。

每次修复后均重跑 `assembleDebug testDebugUnitTest`：**86 项，失败 0**（rebase 到 F 的提交后为 93 项，见 §一）。

**Snackbar 常驻修复的复查（同日，第 4 轮）**：P0=0 P1=0 **P2=6**，结论可提交。按建议修了 4 项：
P2-2（AppShell 改 `snackbarBottomInset` 参数，去掉对任务包的编译期依赖）、P2-3（本文档补条目、
校正行数）、P2-4（测试改用 `Indefinite` 与生产时长解耦、`scope.cancel()` 进 finally）、
P2-5（内边距门控加引导/加载/错误态条件）。P2-1（大字号下 68dp 偏小）、P2-6（`screenHeightDp`
整窗启发式且 API 36 起弃用）记为已知限制。

**第 5 轮聚焦复查（只审上述 4 项修复）**：P0=0 P1=0 P2=2，结论可提交。两项均已修：
P2-①（本文档对紧凑判定归属与行数的描述过时）按实际架构重写；P2-②（`!onboarding && !loading &&
readError==null` 在 Snackbar 门控与底栏可见性各写一份）提取为 `val contentReady` 两处共用，
行为等价。

验证口径：**93 项单测全绿**（本模块 86 + F 的边界假数据 7）；`NavigationTest` 5 条 instrumented
在**最终代码**上用 `adb shell am instrument -e class com.coursekeeper.NavigationTest` 跑出
**OK (5 tests)**，6.356 秒（真机华为 API 29）。未用 `connectedDebugAndroidTest`——它会反复装卸，
触发未备案 APK 的华为账号验证。推送到 main 后 GitHub Actions（F 配置）在该提交上 **跑绿**。

## 七、真机验收（2026-11-11，华为 TEL-AN00a / Android 10 API 29）

全程 ADB 无人值守驱动（`uiautomator dump` 取坐标 + `input tap/text`），设备时钟 09:02 起测。

| # | 场景 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 新 APK 装入（含两个 receiver） | ✅ | `dumpsys package` 可见 `reminder.ReminderReceiver` / `reminder.BootReceiver` |
| 2 | 建 10:06 截止任务（经 A 的首页「＋ 手动添加任务」入口） | ✅ | 表单正常打开，默认截止时间=当天 23:59 |
| 3 | 闹钟注册 | ✅ | `when=2026-11-11 09:06:00.000 type=0 wakeup=true window=0`（= 10:06−1h，精确闹钟） |
| 4 | 到点发通知 | ✅ | `dumpsys notification`：`channel=task_reminders`，`bigText=「ReminderTest01」将在 11-11 10:06 截止`；截图 `temp/verify/w3-notification.png` |
| 5 | 自续链终止 | ✅ | 1h 槽为最后一档，触发后待触发闹钟数 = 0，无重复通知 |
| 6 | 勾选完成 → 撤提醒 | ✅ | 完成后闹钟数 1 → 0，Snackbar 出现「任务已完成 / 撤销」 |
| 7 | 撤销 → 重建提醒 | ✅ | 撤销后闹钟回到 `when=11:00:00.000` |
| 8 | 删除任务 → 撤提醒（自检 P1-1 的回归） | ✅ | 删除前 1 条 → 删除后 0 条 |
| 9 | **重启恢复（BOOT_COMPLETED）** | ⚠️ **被 ROM 拦截** | 见下 |

**第 9 项细节（重要，非代码缺陷）**：重启并解锁后闹钟未重建。`dumpsys activity broadcasts` 显示系统把广播记录为
`Skipped #148 (manifest) name=com.coursekeeper.reminder.BootReceiver`，紧随 `ActivityManager: App 10302/com.coursekeeper targets O+, restricted`
——这是**华为 EMUI「应用启动管理」的自启动管控**拦下了 BOOT_COMPLETED 投递。接收器本身注册正确
（`enabled=true`、出现在投递列表、广播被"跳过"而非"未匹配"）。验证代码路径有效：打开 App 后
`attach` 对账立即把闹钟重建回 `11:00:00.000`。

**用户侧需要做的事（须写进发布说明）**：设置 → 应用 → 应用启动管理 → 课程管家 → 关闭"自动管理" →
手动管理里允许**自启动**（建议同时允许关联启动/后台活动）。其余 ROM（小米/OPPO/vivo）同理，
在各自的"自启动/后台管理"里放行。未放行时提醒仍可用，只是重启后要打开一次 App 才恢复。

验收环境备注：设备时钟与 PC 相差约 7 小时，一切时间以设备本地为准；EMUI 锁屏状态下安装要求
输入锁屏密码（用户手动提供，解锁后已设 `svc power stayon usb` 保持常亮）；解锁状态下安装
仍弹「风险提示→继续安装」，由 ADB 轮询屏幕坐标自动点击。另：`am force-stop` 会清掉 App 全部闹钟
（系统行为），故调试期间强制停止后需重开 App 才会重新挂提醒。

