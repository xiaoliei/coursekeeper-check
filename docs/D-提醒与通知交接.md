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

全仓测试 **86 项，失败 0**（65 原有 + 21 新增）。

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

每次修复后均重跑 `assembleDebug testDebugUnitTest`：**86 项，失败 0**。

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

