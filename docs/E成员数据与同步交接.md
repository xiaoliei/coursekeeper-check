# E 成员：本地数据与同步交接

更新日期：2026-11-11。依据六人分工、项目计划书、已生效的《任务字段与通知规则 v1.0》，以及 B《课程与周次规则 v0.1》提出的数据结构需求。本轮补齐基础版 T03、T06、A00 的 v3 迁移和联调支持；不代替 B/F 完成尚未确认的业务规则定稿。

## 开发范围

E 提供 Room 实体、DAO、Repository、DataStore 设置、数据库迁移、SyncCoordinator、本人测试及接口说明。B 继续负责 CourseService、周次和冲突校验；C 负责官方登录、会话及学校解析；D 负责 TaskService、业务校验、任务页面及提醒；A 负责 HomeGateway 接入与状态展示；F 负责统计及交叉验收。

当前 A 首页、D 任务页/通知和 F 看板共用 E 的真实 Room 快照。B 的正式课程服务、C 的真实学校适配器尚未接入。本轮提供非连续周次、排课教师、旧库升级和统计读取兼容；数据库不灌入演示数据。

计划规定基础版验收后启动 A01，故 Keystore/CredentialStore、Agent 配置与 R10 留在后期阶段；本轮先实现 A00 所需版本、requestId 和事务能力，不引入模型请求。

## 统一入口

```kotlin
val data = CourseKeeperData.get(applicationContext)
val repository = data.repository
val settings = data.settings
// B/D 业务服务在校验后调用 repository；页面经业务服务消费数据。
repository.snapshots.collect { snapshot -> /* 更新共享业务状态 */ }

// C 持有应用级 SchoolAdapter 单例；同一实例交给全应用共用的协调器。
val sync = data.syncCoordinator(schoolAdapter)
sync.state.collect { summary -> /* A 显示非阻断状态 */ }
sync.onForeground()              // 缓存展示后调用；延迟 400 ms 检测会话
sync.synchronize(force = true)    // A 的下拉刷新回调
```

`CourseKeeperData.get` 不发起网络请求；Flow 初次收集直接读 SQLite。网络只在显式调用协调器时发生，且不持有数据库事务。前台入口及刷新手势由 A 接入；C 当前尚无实现，E 未伪造真实学校连接。调用方提供协程作用域并处理取消。应用级协调器拒绝重叠刷新，返回 `InProgress`。

## 表结构与存储约定

| 表 | 内容与约束 |
|---|---|
| semesters / periods | 学期和节次配置；起始日可空，E 不猜日期；节次随学期级版本一起提交 |
| courses / schedules | 课程与排课分开；排课存 weeks 位图、规范 weeksText、可空条目 teacher；删课级联删排课，课程版本覆盖整个排课集合 |
| tasks | UUID 主键；五类枚举、可空 dueAt/completedAt、courseId、source、externalId、version、manualDirty |
| sync_records | 课表、作业各一条；学校、状态、尝试/成功时间、错误码和合并计数 |
| tombstones | `(module, source, externalId)`；物理删除同步任务后阻止重建；课程同样保护 |
| write_requests | requestId 唯一；操作参数摘要与已提交回执，随业务写入一起提交 |
| data_revision | 统一变更通知；每次提交后发出完整、一致的 DataSnapshot |

课程/任务各自有 `(source, externalId)` 唯一索引。手动任务 externalId 为空，允许多条同标题任务。任务不对课程建级联外键：删除课程时保留任务及原 courseId；B/D 根据找不到课程显示“课程已删除”。

v3 的 `ScheduleDraft` / `ScheduleRecord` 使用 `weeks: Long`、`weeksText: String`、`teacher: String?`。第 n 周使用 bit(n-1)，支持 1—64 周；第 64 周是 Long 的符号位，负数不能直接视为非法。`ScheduleWeeks.format()` 输出规范文本，例如 7424 对应 `9,11-13`。条目 teacher 为空时由 B 的读侧使用课程默认教师。

新写入要求周次非空、文本与位图一致，已关联学期时不得超出其 weekCount；学期 weekCount 可为 1—64，不新增未经确认的默认值。B/C 负责输入文本解析和字段级容错，E 只接收已结构化数据并作持久化边界校验。保留旧区间参数的转换构造器供现有调用兼容；旧字段及单双周枚举不再存入排课表。

```kotlin
val weeks = (1L shl 8) or (1L shl 10) or (1L shl 11) or (1L shl 12)
val entry = ScheduleDraft(dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
    weeks = weeks, teacher = "条目教师") // weeksText 默认由位图生成：9,11-13
```

任务的日期按 D v1.0 保存本地 `LocalDateTime`，完成使用设备本地时钟；同步尝试和成功时间使用 `Instant`。任务状态、逾期、今日/本周、压力和统计均由 B/D/F 的业务代码计算，数据库不保存派生状态。

DataStore 只保存选中学校、引导完成标记；`completeOnboarding()` 可用于跳过登录后记住选择。学期/节次需事务和版本管理，存 Room。学校密码、Cookie、验证码和 API Key 均不进入这些表或设置。

## Repository 读写接口

读接口：`snapshot()`、`snapshots`、`getTask(id)`、`getCourse(id)`。Snapshot 包含学期、节次、课程、排课、任务及同步记录。任务按 dueAt 升序、空值最前返回；D 在统一业务查询中执行类型/课程/状态/日期筛选。相同数据不会重复发出业务快照。

| 操作 | 参数与返回 |
|---|---|
| createTask | TaskDraft、source（MANUAL/AGENT，默认 MANUAL）、可选 requestId |
| updateTask | id、expectedVersion、完整 TaskDraft、可选 requestId；可显式清空截止/课程 |
| setTaskCompleted | id、completed、expectedVersion、可选 requestId |
| deleteTask | id、expectedVersion、可选 requestId |
| createCourse / updateCourse / deleteCourse | CourseDraft（含完整排课集合）；更新/删除必带 expectedVersion；可选 requestId |
| saveSemester | SemesterRecord、完整节次集合、expectedVersion（新建为 null） |

操作返回 `WriteReceipt(id, version, changed, replayed)`。新增版本 1，成功编辑/状态变化递增；完成切换到同一状态返回 `changed=false`，保持版本与完成时间。删除回执版本为旧版本 +1。新建关联需课程存在；已有课程删除后的原关联允许保留。

版本由 Repository 作为业务持久化边界统一递增，调用方不得自行增加或直接调用 DAO。事务内检查当前版本，旧版本返回 `VERSION_CONFLICT`，禁止静默覆盖。B/D 服务提交用户实际看见的 expectedVersion，不能为了避开冲突而先读最新版本代替它。同步使用网络请求前的基线版本，不能在网络返回后临时取最新版本覆盖。

相同 requestId 和相同参数返回原回执、不再次写入；同 ID 换参数返回 `REQUEST_ID_REUSED`。校验失败不会占用 requestId。回执持久化，可在进程重启后重放，重放不会复活已删除任务。D 的提醒应只由 `changed && !replayed` 的提交结果或记录版本变化驱动；重放不得再创建提醒。

任务基本校验与错误码遵守 D v1.0：`TASK_NOT_FOUND`、`EMPTY_TITLE`、`TITLE_TOO_LONG`、`VERSION_CONFLICT`、`COURSE_NOT_FOUND`，通过 `DataException.code` 和人话 `message` 返回。数据格式/非法来源使用 `INVALID_RECORD`；请求 ID 误用为 `REQUEST_ID_REUSED`。提醒创建/取消及权限申请仍归 D；E 不申请通知权限。

## C 的同步输入与合并规则

`SchoolAdapter` 提供 `schoolId`、`checkSession()`、`fetchCourses()`、`fetchTasks()`。会话返回 VALID/EXPIRED/OFFLINE；模块分别返回 `ModuleResult.Success(records)` 或 `Failure(reason, retryable)`。异常信息只记录枚举错误码，不存异常原文、响应正文或凭据。

`ImportedCourse(externalId, CourseDraft)` 要求稳定外部 ID。`ImportedTask(externalId?, TaskDraft, courseExternalId?)` 由 C 填已验证的作业稳定 ID；课程关联使用外部课程 ID，由 E 解析到本地 ID，未知关联可空。不接受 C 猜测的本地 UUID。

D v1.0 将作业 externalId 的具体取值交由 C 待补充。在 C 明确前，E 不用标题、截止或 URL 猜测去重键：缺少稳定 ID 的任务暂缓入库并计入 `missingIdentity`，手动任务正常使用。C 明确后，只需在适配器填入正式 externalId。必填标题无法解析仍须由 C/D 处理；可空的截止/关联缺失不是模块失败。

合并规则：

1. 每个模块独立事务；课程先提交，任务再解析关联。一模块失败不回滚另一模块成功结果。
2. 同来源同稳定 ID 更新已有记录；相同内容不改版本、不重建排课；完全相同的重复行折叠，同 ID 不同内容拒绝整个模块并回滚。v3 差异比对和条目 ID 覆盖 weeks、weeksText、teacher 以及原有星期/节次/地点；仅周次或教师不同的条目不会撞 ID。
3. manualDirty 或 completedAt 非空的任务受到保护；手动编辑/完成/撤销后置位任务级 manualDirty，不做字段级追踪。手动修改过的课程也保护。
4. 删除同步记录的同一事务写墓碑。后续拉取命中墓碑跳过，不复活；任务原课程已删时保留原 courseId。
5. 成功空结果或远端缺行均不删除本地记录；只有明确用户删除才物理删除。失败不会把缓存替换为空。
6. 单模块中任意非法行/版本冲突会回滚该模块全部写入，不发布半份业务结果，保留原最后成功时间。

课程合并在事务内一次读取课程/排课并按课程分组，避免每导入一门课都扫描全表。整组更新时复用未变排课的原 ID，包括从 v2 迁移来的 ID；只为新增或实际改变的条目派生新 ID。

首期仅支持一所学校的本地数据集。已有同步记录与新适配器 schoolId 不同会返回 `SchoolMismatch`，不混写也不清库；多学校切换/账号数据隔离需 C/E 后续约定数据分区，不能只换 SettingsStore 的学校标记继续合并。

## 节流、重试与状态

自动同步先轻量检查会话，再按每模块 lastSuccessAt 判断：不足 30 分钟跳过拉取，恰好 30 分钟允许；强制刷新绕过节流。失败模块下次可立即重试，已成功模块继续节流；设备时间回拨时允许重新同步。

每次检查/拉取默认超时 30 秒。拉取的临时网络、超时或明确可重试远端错误，在初次之外最多重试 2 次，间隔 500/1000 ms；解析、数据、登录过期和版本冲突不自动重试。重试只发生在失败模块，不重复已成功模块。协程取消向上传播，并将尚在 SYNCING 的记录收敛为 ERROR/INTERRUPTED。

正式数据层命名为 `IDLE / SYNCING / OFFLINE / EXPIRED / PARTIAL / ERROR`（与 F 草案枚举语义一致）；这是 E 的交付契约，不修改 F 未生效的统计公式。模块记录只用 IDLE/SYNCING/OFFLINE/EXPIRED/ERROR；PARTIAL 由整体汇总派生。IDLE 的 lastSuccessAt 为空表示尚未成功同步，不得显示“同步成功”。

`SyncSummary.modules` 始终包含课表/作业两项及各自 lastSuccessAt。一个成功一个失败显示 PARTIAL；两者离线显示 OFFLINE；会话失效同时标 EXPIRED。断网、过期、部分成功时 Repository 手动操作完全可用。A 的旧演示 `home.SyncStatus.Ready` 不等于此正式枚举，A 接入时需映射并扩充同步中/错误展示。F 直接消费 E 提供的最后成功时间，不在统计层重算。

## 迁移、备份与后续阶段

当前 Room 版本为 3，schema 1/2/3 随源码提交。`MIGRATION_1_2` 增加墓碑与 requestId 回执表；`MIGRATION_2_3` 将旧区间及单双周准确转换为位图，生成规范文本，条目 teacher 置空以沿用课程默认教师。保留课程/任务/排课 ID、版本、完成时间、关联、墓碑、请求回执和同步时间，不使用破坏性 fallback。v1 是 E 定义的初始 schema 基线，原 A 内存演示没有已发布的 Room v1 数据库。

旧的单双周与区间交集为空时，迁移保留原排课及空位图，不虚构上课周；新建排课拒绝空周次。旧区间超出 1—64 周或枚举损坏时中止迁移、回滚并保留原库，不截断数据。旧库须核对修复后重新升级。

业务备份须在数据库关闭或取得一致 SQLite 快照后复制，不能单拷贝仍有未合并 WAL 的主文件。恢复测试核对课程任务与回执/墓碑；将来凭据独立存放并排除在业务备份之外。当前应用沿用原 `allowBackup=false`，没有增加系统云备份或用户导出 UI。

后续 A02/A03/A04：按计划添加 Keystore 密钥、独立配置/密文存储、清除及解密失败恢复，随后与 F 验证地址绑定和路由，与 C/D 验证取消/确认。不能在本轮基础数据交付中声称 R01—R12 的 Agent 整体验收已完成。

跨学期同编号课程的唯一键是 B v0.1 的开放问题，本轮未改变现有课程/任务去重规则；需与 C 明确学期标识及任务关联后再修订。v3 数据字段已经就绪，不表示 B v1.0 或 F 的全部规则已生效。

## 本地验证与审核

执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。E 测试在 `app/src/test/java/com/coursekeeper/data`，使用真实 Room/SQLite 的 Robolectric 测试，覆盖持久化、迁移、恢复、事务、版本、合并和调度；原 A 的测试保留。

本轮 v3 验证见 `docs/E-v3迁移与联调验收.md`；首次交付记录保留在 `docs/E成员验收记录.md`。F 的临时课程统计适配器只更新读取方式为位图，统计公式不变；非连续周边界素材已启用。发布继续遵循用户要求：审核后分段直接提交 main，不创建 PR。
