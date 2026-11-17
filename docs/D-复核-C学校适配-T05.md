# D 复核 C 学校适配（T05）· 双路质检记录

- 日期：2026-11-16　|　复核人：成员 D（主项目会话，双路子智能体 + 主会话亲核）
- 范围：`3ddbdf7..591e752`（C 的 9 个提交，36 文件 +3294/−76）
- 构建：`gradlew assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL**，**240 项测试 / 0 失败**（207→240，C 增 33）
- **总结论：待返工**——P0 未修前不装包、不验收。本文只记录结论与证据，不改 C 的代码。

## 一、必须修（P0）

**P0-1 启动崩溃：SchoolAdapter 违反应用级单例契约**
- `MainActivity.kt:70-79`：`HomeDataOwner`（Activity 级 ViewModel）每次构造都 new 一个 `WebViewSchoolAdapter` 再传 `data.syncCoordinator(adapter)`；而 `data/CourseKeeperData.kt:19` 有 `require(boundAdapter == null || boundAdapter === adapter)`（E 的契约：同一实例交给全应用共用协调器，见 `docs/E成员数据与同步交接.md`）。
- 触发路径（高频）：返回键退出 App（进程存活、ViewModel 已清）→ 再点开 → 新 `HomeDataOwner` → 新 adapter 实例 → `require` 抛 `IllegalArgumentException` → 启动即崩。旋转屏幕不触发（VM 存活）；进程被杀后冷启动不触发。**主会话已亲核 require 原文与构造处。**
- 修法（任选其一，C/E 商定）：adapter 提为应用级单例（CourseKeeperData 持有或顶层 object）；或与 E 商量放宽 require 为按 schoolId 比较。

## 二、建议随本轮修（P1）

| # | 问题 | 证据 | 说明 |
|---|---|---|---|
| P1-1 | 登录完成页"假完成" | `MainActivity.kt:80-84`（afterLogin 忽略 synchronize 返回值）+ `school/SchoolLoginController.kt:41-53` + `data/sync/SyncCoordinator.kt:88-91,140` | 模块失败（EXPIRED/OFFLINE/ERROR）不抛异常，控制器必然走到 Done「学校数据已导入本机」；过期会话经壳页误判进登录成功时，弹层显示成功、首页横幅显示过期，自相矛盾。修法：afterLogin 检查 summary 有失败即置 Failure |
| P1-2 | 空导入全清镜像 | `data/sync/SyncMerge.kt:53-58` + `school/WebViewSchoolAdapter.kt:59-63` | 解析出「0 门课 + 0 警告」→ Success(empty) → 清空全部非 manualDirty 同步课程（现有测试还固化为预期行为）。自愈但一个同步周期内课表/冲突/统计被清空。**该语义在 E 的模块里，需 E 拍板**：imports 为空且镜像非空时跳过清理，或按 PARSE_ERROR 报失败 |

## 三、记档项（P2，修或写已知限制）

| # | 问题 | 证据 |
|---|---|---|
| P2-1 | 导入协程跑在弹层 UI 作用域，Importing 期间点「关闭」或旋转即取消 afterLogin（可自愈但有中间态不一致；旋转停在"正在读取"） | `school/SchoolLoginSheet.kt:58,81-86,224` |
| P2-2 | WebView 从不 destroy()，每次登录泄漏一个实例 | `SchoolLoginSheet.kt:198-200`（AndroidView 无 onRelease） |
| P2-3 | debugDumpDir 未按 BuildConfig.DEBUG 门控，release 包也会把真实页面（含个人课表）落盘 | `MainActivity.kt:70-78`、`WebViewSchoolAdapter.kt:105-114` |
| P2-4 | 镜像清理测试缺口：清理路径下"MANUAL 课程不受影响"、"删除课程后关联任务悬挂保留"、既有墓碑交互均无用例（清理本身不写墓碑已有用例覆盖） | `app/src/test/.../SyncCoordinatorTest.kt:188-228` |
| P2-5 | 会话 Cookie 随明文 http 同主机跳转发送（过期 302 到 `http://jw.stu.edu.cn/` 时走明文） | `school/SchoolSession.kt:106-108` |
| P2-6 | termTag 将来补填 → 全部课程 externalId 变更 → 整表删建；任务关联落地后会悬挂（填 tag 前需迁移方案） | `school/SchoolCatalog.kt:61`、`StuJwPageParser.kt:122` |
| P2-7 | JS 壳页正则只认 `window.location`，`location.replace()` 变体漏判（降级 PARSE_ERROR，fail-safe 但横幅口径错） | `school/SchoolSession.kt:27,75-79` |

## 四、打磨建议（P3，不阻塞）

MergeCounts.deleted 未入同步记录表；MYSTU 合法"0 作业"学期被补警告假失败；`WEEKS_LINE` 课程名以数字开头含"周"会误判；`periodsOf` 非连续节次展开成区间；`locationOf` 缺教室行时取到通知单编号行；WeekText 剥括号使手输 `1-3[5]` 误解析为 1-35；登录弹层 awaitingTaskSource 无超时；WebView 未关第三方 Cookie；页签"下周"超学期静默钳制、‹ › 无 contentDescription、页签无 Role.Selected；appSettings 初始值致 Expired 直达前闪一帧；StepConfirm 死参数 scope；「以后再说」与关闭按钮语义不一致。

## 五、合格项（复核通过）

1. **安全红线全过**：密码只在 WebView 官方页输入；`school/` 与 `data/sync/` 包 grep `Log/println/printStackTrace` 零命中，`password` 仅出现在登录页特征检测（`SchoolSession.kt:71,78`）与注释里——无密码值经手、无落盘、无日志；唯一 JS 是读页面 CSS 高度，无凭据注入；不绕验证码；Cookie 不落业务表不进日志、跨主机跳不携带；明文放行仅 `jw.stu.edu.cn`（`res/xml/network_security_config.xml`）；WebView 内 http 跳转仅白名单域名。
2. **D 的任务页零破坏**：taskScreen 18 参数、bootReminderHint、批量选择回调、selectedIds 收提示的 LaunchedEffect 全部原样；底栏几何（跳动修复前提）本范围内零改动；「学校」入口在顶栏不影响底栏高度。
3. **AppShell 旁路保留**：loginSheet/taskScreen/statisticsScreen 为 null 时演示路径行为不变（文案三分支真值表等价）；横幅与「学校」按钮无"既无横幅又无按钮"死区。
4. **镜像清理本体口径正确**：只删 `source==SYNC`（`SyncMerge.kt:16`）、manualDirty 保护、墓碑挡复活、清理不写墓碑、事务原子、任务悬挂保留（tasks 无课程外键，v1.0 口径）。
5. **coursesOf 收养与 F 统计同源无漂移**：统计页经 `RepositoryCourseService.summary → CourseProjector.project` 用同一 `coursesOf`，手动课程两侧同排除。
6. **解析降级 fail-safe**：两处 fetch 均有「0 条 + 警告 → 报错」守卫；无硬编码日期，学期起始日由用户手填。
7. **并发防重入**：`SyncCoordinator.synchronize` tryLock；`LaunchedEffect(data)` 永久挂起等待设置完成无资源泄漏；onLogout 的 `rememberCoroutineScope` 位于根组合不随弹层销毁（疑点已证伪）。

## 六、处置

- 2026-11-16：拉取合并（本地 main=591e752）→ 构建 240/0 绿 → 双路质检（路线一"修复后可提交"、路线二"可提交"）→ 汇总为本文。**未改任何代码，未推送，未装包**——P0 待 C 修复后再走拉取→复检→真机安装。

## 七、修复复核（2026-11-17）：P0/P1 全部闭环

C 在分支 `fix/school-adapter-review` 提交 4 个修复（基于 591e752），已 rebase 到本地 main 复核。

**结论：P0/P1 全部修好，可提交。**

| 项 | 原问题 | 修法核实 | 结论 |
| --- | --- | --- | --- |
| P0 | 启动崩溃 | 新增 `school/SchoolAdapterHolder`（`@Volatile` + 双重检查的进程级单例），`MainActivity` 改为 `SchoolAdapterHolder.get(application)`；**全仓仅此一处 `new WebViewSchoolAdapter`**，不变式成立；Robolectric 用例断言重复获取同一实例 | ✅ 闭环 |
| P1-1 | 登录假完成 | `afterLogin` 检查 `SyncRunResult`：`Finished` 里有 OFFLINE/EXPIRED/ERROR 模块即抛；`InProgress`/`SchoolMismatch` 也抛（C 补的额外加固）；人话文案 `syncFailureText` 有 3 条单测；控制器 catch 后进 `Failure` 并保留重试入口 | ✅ 闭环 |
| P1-2 | 空导入清库 | `fetchCourses` 对「0 门课」一律报 `Failure(PARSE_ERROR)`，不进镜像合并；C 注明「确属无课学期需放行时，等 E 拍板再改守卫」 | ✅ 闭环（语义待 E） |

**新发现（不阻塞，记档）**

| # | 级别 | 说明 |
| --- | --- | --- |
| 1 | P2 | **"确实无课"的学期会被判成同步失败**（交换/休学/毕业学期）：每次打开都提示失败。等 E 给语义——如页面自带"学期/无课"标记，或允许用户确认"本学期无课"后放行空导入 |
| 2 | P2 | **登录成功但同步失败时 schoolId/onboarding 已落库**：用户看到 Failure，但下次启动会自动重试同步；想放弃登录得走"退出登录"。可辩护（失败可重试），建议下轮考虑"确认同步成功后再落库"或失败回滚 |
| 3 | P3 | `InProgress` 按失败提示有理论竞态：`afterLogin` 写 settings 会唤醒 `LaunchedEffect` 里的 `onForeground()`（含 400ms 延迟），若它先拿到锁，force 同步得到 `InProgress` → 登录页显示"上一次同步还在进行中"。延迟使实际很难触发，重试即可 |
| 4 | P3 | `syncFailureText` 放在共享文件 `MainActivity.kt`，更合适的位置是 sync/school 模块 |
| 5 | P3 | 原复核 P2-3（`debugDumpDir` 未按 `BuildConfig.DEBUG` 门控）仍未处理，C 记为"发布前摘除" |

**构建/测试（合并后本地 main）**：`assembleDebug` 通过，全仓 **269 项 / 0 失败**（244 + 25）。

**处置**：已 rebase 合入本地 main（线性历史，C 的 4 个提交保留作者），未推送——按流程等双路自检与用户授权。

## 八、双路自检记录（2026-11-17，用户同意后送检；范围 origin/main..HEAD 六个提交）

**路线一（业务逻辑/并发/口径）：可提交。路线二（Compose/UI 与集成）：修复后可提交。两路均无 P0/P1，代码零改动。**

### 8.1 复核通过的关键点

- **P0 单例不变式成立**：`@Volatile` + 双重检查写法正确；Holder 不持有 Context（不泄漏）；全仓仅一处 `new WebViewSchoolAdapter`；`require` 的触发面已穷举（无 Preview/其他 ViewModel 路径）；进程被杀时 Holder 与数据层同时重置，冷启动安全。
- **P1-1 失败传播正确**：`SyncStatus` 枚举全量对照（模块记录不含 PARTIAL）；`force=true` 下 `throttled` 恒空不会误报；`SyncFailure` 8 个错误码全部有映射，不漏；控制器 catch 后进 `Failure` 且保留 school 可重试。
- **P1-2 守卫有效**：只作用于 `fetchCourses`（`fetchTasks` 未受影响）；最终映射为 `PARSE_ERROR`，与文案对得上；因 `completeOnboarding` 在同步前已写，**不会把用户锁死**在弹层。
- **D 的任务页接线逐项等价**：`AppShell(...)` 调用区零 hunk；18 个参数、`createRequested`、提醒接线、自启引导、`selectedIds` 收提示全部原样；`AppShell.kt` 未被触碰（底栏几何不变）；演示旁路与课表/统计页无牵连。
- **退出登录不锁死**：Failure 页有「关闭」，点外部/返回也能退出；顶栏「学校」重进有「退出登录」。
- **提交卫生干净**：6 个提交各自只动 D/C 自己的文件，无测试删除、作者保留、线性历史。

### 8.2 交 C 订正（文档/证据表述，不代改——一 md 一作者）

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `docs/C-学校适配实现说明.md`「C 32 项」 | 与该句枚举合计（34）不符；且「244 项」是分支口径，合并后实际 269，建议注明「C 分支当时 244；合并 main 后 269」 |
| 2 | `docs/evidence/c-t05/verification.md` 第 97 行 | 把「启动重试横幅」当作 D 复核第 2 条（登录假完成）的验证——该修复只作用于登录弹层 Done/Failure，首页横幅本来就没有「导入完成」字样，此观察不能证明它 |
| 3 | `docs/evidence/c-t05/verification.md` 第 99 行 | 「空课表守卫在真实数据上验证」不实：11-16 页面解析出 9 门课，`courses.isEmpty()` 分支从未进入；真机验证的是 E 的镜像清理。建议注明「守卫仅代码复核，无零课页面样本」 |
| 4 | C 文档/证据里的 `51c8a19` | rebase 前悬空提交，外部克隆无法复现（内容与 `5fc2522` 等价），建议改引 rebase 后提交 |
| 5 | 笔误「镜象」、文档 EOF 多一个空行 | 顺手清理 |

### 8.3 交 C 补测 / 记档（代码正确，防线不足）

| 级别 | 项 |
| --- | --- |
| P2 | **两项修复缺真正的回归测试**：`SchoolAdapterHolderTest` 只断言两次 `get` 相同（不能防止 `MainActivity` 改回 `new`，也没走 `require` 路径）；`afterLogin` 的失败模块过滤是内联 lambda，无纯函数可测——若删掉过滤，3 条文案用例仍全绿。建议抽出 `SyncRunResult → 失败文案?` 纯函数并补分支用例；holder 用例至少构造两次 `HomeDataOwner` 或两次 `syncCoordinator` |
| P2 | **Failure 态关闭后残留**：`onDismissRequest` 只在 Done 时 reset，Failure 经返回键/点外部关闭后下次打开先看到旧错误；建议 Failure 一并 `backToSelect()` |
| P2 | **`SchoolMismatch` 提示不可执行**：「退出登录后重新连接」，但退出登录不清 `sync_records.schoolId`，重新登录仍 mismatch。当前单校不可达；未来多校会永久卡死，需提示或重置入口 |
| P2 | **空结果清库的兜底仍在 E 的 `mergeCourses`**（对 `Success(empty)` 删全部非 manualDirty 同步课程），且与 E 文档「成功空结果不删除」冲突；C 的守卫只保护 `WebViewSchoolAdapter` 一条路径。需 E 在合并层加守卫或更新契约 |
| P3 | `InProgress` 按失败提示的理论竞态（弱网 + 启动 `onForeground` 重试期间可触发，重试可恢复）；Holder 无替换/重置路径（多校埋雷）；`syncFailureText` 位置与字符串匹配（新增 `SyncFailure` 值会静默落「未知原因」）；空守卫的具体警告被吞；两模块同因文案重复；启动路径忽略 `onForeground` 返回值 |

### 8.4 处置

- **代码零改动**（结论之后一行不动）；P2 中「无课学期语义」「设置先落库」已在 §七 记录，其余本轮新增记档。
- C 的文档订正与补测在群里点名交办；**不代改 C 的 md**。
- 自检通过后按流程等用户发话推送。
