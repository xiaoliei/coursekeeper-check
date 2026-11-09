# D · 任务页交接说明（第 2 周）

- 日期：2026-11-09　|　提供：成员 D　|　状态：**代码完成，待真机验证**
- 依据：`docs/任务字段与通知规则-v1.0.md`（口径）、`docs/E成员数据与同步交接.md`（数据层接口）

## 一、交付了什么

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/coursekeeper/task/TaskContract.kt` | 任务状态派生、排序、筛选、截止文案（纯函数，无 Android 依赖）|
| `app/src/main/java/com/coursekeeper/task/TaskViewModel.kt` | `TaskGateway` 窄接口 + `RepositoryTaskGateway` 适配 E 的数据层 + 界面状态与事件 |
| `app/src/main/java/com/coursekeeper/task/TaskScreen.kt` | 任务列表、状态/类型筛选、新建/编辑表单（含"截止待定"开关）、删除确认 |
| `app/src/main/java/com/coursekeeper/MainActivity.kt` | 接线：`CourseKeeperData.get(...).repository` → `TaskViewModel` → `AppShell` |
| `app/src/main/java/com/coursekeeper/ui/AppShell.kt` | **仅两处改动**，见第四节（A 的文件，请 A 复核）|
| `app/src/test/java/com/coursekeeper/task/*` | 13 项单测：状态派生、排序、筛选、文案、版本号传递、错误映射、撤销 |

## 二、数据流

```
TaskScreen（纯展示）
   ↑ StateFlow<TaskUiState>
TaskViewModel ── TaskGateway（窄接口，便于测试）
                   └ RepositoryTaskGateway
                        └ CourseKeeperRepository（E）
                             └ Room（真实数据库）
```

- 任务页**直接读 E 的真实数据层**，不再经过 A 的演示网关；`repository.snapshots` 变化会自动刷新页面。
- 业务校验一律由 Repository 权威执行；界面只做即时预校验，文案与 `DataError.userMessage` 保持一致。
- 写入全部带 `expectedVersion`；冲突返回 `VERSION_CONFLICT` 并原样提示，不静默覆盖。
- 撤销动作读取**当前最新版本号**再提交，避免用界面上已过期的版本。

## 三、v1.0 口径落地对照

| v1.0 条目 | 落地位置 |
| --- | --- |
| 状态不存储，代码派生（§1）| `TaskRecord.statusAt` |
| 日期按设备本地时区、00:00 日界（§1 补记）| `dueLabel` 用 `LocalDate` 比较 |
| 排序：待定最前 → dueAt 升序 → 已完成在后（§3）| `sortedForDisplay()` |
| 逾期保留并标"已逾期"（§3）| `dueLabel` 前缀 + 列表不过滤 |
| 类型五枚举（§2）| 复用 E 的 `TaskType`，`label` 扩展 |
| 首个带 dueAt 任务后才申请通知权限（§4）| **未做**（第 3 周与通知一起实现）|
| 课程删除不级联删任务（六-B）| `courseLabel` 显示"课程已删除" |
| 错误码 + 人话文案（§3）| `TaskEvent.Message` 直接取 `DataError.userMessage` |
| 重放不计（`changed/replayed`）| `toggle` 只在 `receipt.changed` 时提示 |

## 四、对 A 的 `AppShell.kt` 只做了两处改动

1. 新增可选参数 `taskScreen: (@Composable (Modifier) -> Unit)? = null`；
   为 null 时仍用原来的 `DemoTasks`，**A 的独立演示工程不受影响**。
2. 任务页自带新建入口，因此选中"任务"页时隐藏底部的"＋ 手动添加任务"按钮（避免两个入口写进不同数据源）。

请 A 复核这两处；首页与任务页的筛选语义（A 的 `TaskFilter.Today` → D 的状态筛选）**尚未对齐**，列入第 3 周。

## 五、已知边界（本版不做）

| 项 | 说明 |
| --- | --- |
| 通知与提醒 | 第 3 周；依赖 `changed && !replayed` 的写回执（已按此设计事件）|
| "今日截止 / 今日完成"筛选入口 | A 首页数字点击会打开任务页但不带筛选；需与 A 对齐 |
| 课程关联下拉 | 目前读 `snapshots.courses`；B 的课程模块接入后会自动出现 |
| 首页任务数据 | 首页仍用 A 的演示网关，任务页用真库——第 3 周统一 |
| 调课覆盖层 | E 尚未建表 |

## 六、真机验证记录（2026-11-10，华为 TEL-AN00a / Android 10）

ADB 驱动已完成 1/2/3/7/8 步并截图存证（`temp/verify/`，01–07）：
创建（"截止待定"默认开）、编辑保存（对话框自动关闭）、删除（确认框 + Snackbar + 计数同步）、
**持久化（force-stop + 重装 APK 后数据仍在）**——持久化一项因驱动过程中的重复保存获得意外强验证。
**遗留给成员 D 手动完成**：勾选完成与撤销、已完成/全部筛选、类型筛选、旋转屏幕。

真机过程发现并修复：`safely()` 成功路径不回调 `onResult(null)`，导致保存后对话框卡住
（任务实际已入库）；该缺陷未被 51 项单测覆盖，因测试只断言了失败回调。
修复提交 `0473f2a`，补成功回调断言。

安装备注：华为 EMUI 需在开发者选项打开"USB 安装"，否则每次 adb install 都会弹确认框且易被误关。

## 七、测试证据

`./gradlew :app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**

| 测试类 | 项数 |
| --- | --- |
| TaskContractTest（新增）| 6 |
| TaskViewModelTest（新增）| 7 |
| 原有（A 9 + E 28）| 37 |
| **合计** | **50 项，失败 0** |

一个测试环境注意点：`TaskViewModelTest` 用 `UnconfinedTestDispatcher`。用 `StandardTestDispatcher` 时，后台收集器与 `advanceUntilIdle()` 的推进顺序会造成"事件已发送但未被收集"的假阴性，排查耗时较长，记录在此避免重踩。

## 八、提交前质检与修复记录（2026-11-10）

提交前由两个独立审查（业务逻辑/并发、Compose/集成）提出问题，处置如下：

**已修复**
1. **撤销改用回执版本号**（原实现读 `data.value` 的"最新版本"，违反 E 的约定"提交用户实际看见的 expectedVersion"，且在数据流滞后时会弹假冲突、在第三方改过后会静默覆盖）。现在 `toggle` 把 `WriteReceipt.version` 放进事件，`undo(taskId, completed, expectedVersion)` 直接用它提交——第三方已改时会得到 VERSION_CONFLICT 而不是覆盖。
2. **表单默认截止时间不再写死** `2026-11-09 23:59`（会在日后变成静默脏数据），改为按打开当天的 23:59 计算。
3. **编辑/删除对话框冻结打开时的版本号**：提交按用户打开时看到的版本，第三方中途改动会得到冲突提示，不会被"静默新版本号"盖掉。
4. **create/update 失败不再丢草稿**：保存结果经 `onResult` 回传，失败时表单保持打开并显示错误文案（v1.0"失败时界面恢复原状态"）；成功才关闭。
5. **通用异常文案收敛**为"操作失败，请重试"，不再把 `error.message` 原样弹给用户。
6. **预校验文案改为引用 `DataError` 常量**，与权威校验同源，不再手工复制。
7. **列表文案随时间刷新**：每分钟重算一次"今天/已逾期"（原先只在数据变化时采样，跨零点会显示过期文案）。
8. **弹层状态自清理**：任务从列表消失（同步删除/筛选变化）时清掉编辑/删除弹层状态，防止同 id 任务重现时"复活"旧草稿。
9. **无障碍补齐**：任务行 Checkbox 补 `contentDescription`（与 A 的 TaskRow 同款）。
10. **内部黑话出用户文案**："等 B 的课程模块接入后可选"→"暂无课程可关联"；空态文案对齐 v1.0 排序口径。
11. `TaskEvent.Saved/Deleted` 携带 `WriteReceipt`，`create` 透出可选 `requestId`——第 3 周通知权限与提醒的钩子点。

**记录未修（已知限制，均有意为之）**
- 首页仍走 A 的演示网关、任务页走真实 Room：**集成包内两页任务数据暂不一致**（首页演示数据 / 任务页真库），属过渡态。A 的首页接入 Repository 后自然消除；在此之前首页数字不代表任务页内容。A 的演示对话框里也有一处写死的 `2026-11-09 23:59` 默认值（AppShell.kt 约 170 行），属 A 的文件，请 A 自行调整。
- 事件通道沿用 A 的 `Channel(BUFFERED)` 模式：旋转后未消费完的旧事件可能重放。与 A 的 HomeViewModel 行为一致，待团队统一决定是否换一次性语义。
- 两个 ViewModel 共用 SnackbarHostState，事件连发时前面的提示会被顶掉。现有事件频率下可接受。

**质检后的测试**：新增"撤销用回执版本而非 data 最新版本（含第三方改到 v5 的对抗用例）""create 失败经 onResult 回传且不发 Saved 事件"等用例，全仓 **51 项测试，失败 0**。
