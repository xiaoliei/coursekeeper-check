# B 成员课程与周次交接

- 日期：2026-11-12　|　分支：`feat/course-service`　|　对应口径：`docs/课程与周次规则-v0.2.md`
- 范围：课表页、课程维护、教学周换算、冲突检测、首页课程投影、统计侧课程数字
- 状态：代码与测试完成；**尚未推送到 GitHub**，等组内确认后走 PR

## 一、交付物

| 类别 | 路径 | 说明 |
|---|---|---|
| 契约 | `app/src/main/java/com/coursekeeper/course/CourseContract.kt` | `CourseService` 接口 + `CoursePhase/CourseEntry/CourseState/WeekTimetable/SemesterInfo` |
| 纯计算 | `course/WeekText.kt` | 周次文本 → 位图（`9,11-13` → 7424），格式化复用 E 的 `ScheduleWeeks` |
| 纯计算 | `course/WeekCalculator.kt` | `startsOn` 归一化周一、第 N 周换算、周区间、当前学期 |
| 纯计算 | `course/ConflictDetector.kt` | 星期 × 节次 × 周次三重叠；同课程重复条目去重 |
| 投影 | `course/CourseProjector.kt` | 快照 → 课程状态（四个数字的唯一计算处） |
| 读服务 | `course/RepositoryCourseService.kt` | 接 E 的 Repository，实现 `CourseStatsSource` |
| 写服务 | `course/CourseWriter.kt` | 新增/编辑/删除/学期配置的窄接口 |
| 首页 | `course/HomeCourseProjection.kt` | 首页六个字段一次算出（见第四节） |
| 页面 | `course/CourseViewModel.kt`、`course/CourseScreen.kt` | 五种状态、切周、课时增删改、冲突提示 |
| 组合根 | `MainActivity.kt` | 注入 `RepositoryCourseService` + `CourseViewModel`，挂到 `coursesScreen` 插槽 |
| 文档 | `docs/课程与周次规则-v0.2.md` | 口径与实现状态 |
| 证据 | `docs/evidence/member-b-tests.xml`、`member-b-checks.json` | 测试与构建记录 |

## 二、对外接口

### 读（A/F 用）

```kotlin
interface CourseService {
    val state: Flow<CourseState>                       // 数据变化即重算
    suspend fun stateNow(): CourseState
    fun project(snapshot: DataSnapshot, now: LocalDateTime = LocalDateTime.now()): CourseState
    fun timetable(snapshot: DataSnapshot, weekNo: Int): WeekTimetable
    fun conflicts(snapshot: DataSnapshot, weekNo: Int): List<CourseConflict>
    fun weekOf(snapshot: DataSnapshot, date: LocalDate): Int?
}
```

### 写（课表页用）

```kotlin
interface CourseWriter {
    suspend fun createCourse(draft: CourseDraft, requestId: String? = null): WriteReceipt
    suspend fun updateCourse(id: String, expectedVersion: Long, draft: CourseDraft, requestId: String? = null): WriteReceipt
    suspend fun deleteCourse(id: String, expectedVersion: Long, requestId: String? = null): WriteReceipt
    suspend fun configureSemester(semesterId: String, name: String, startsOn: LocalDate,
                                  weekCount: Int, periods: List<PeriodRecord>,
                                  expectedVersion: Long? = null): WriteReceipt
}
```

写操作全部委托 `CourseKeeperRepository`：版本校验、排课整组替换、墓碑、事务都由 E 负责，B 不重复实现。

**节次设置**：`periods` 是完整节次表（第 N 节 → 开始/结束时刻）。空表表示不设具体时刻，课表只显示“第 N 节”；填了就显示 `08:00 — 09:40`，首页“下一节课”也会给“N 分钟后 / 进行中”。学期与节次在 E 那边是同一个版本化聚合（`saveSemester`）。

### 统计（F 用）

`RepositoryCourseService` 实现 `com.coursekeeper.statistics.CourseStatsSource`：

```kotlin
override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats
```

F 把 `StatisticsViewModel` 里默认的 `IntervalCourseStatsAdapter()` 换成本服务即可，四个数字已做逐项对齐测试，切换不会让看板跳变。

## 三、口径与状态

### 四个数字（与 F v0.2 一致）

| 指标 | 单位 | 计算 |
|---|---|---|
| `todayLessonCount` | 条目 | 今天星期几 且 本周在周次集合内的条目数，跨节次算 1 条 |
| `todayPeriodCount` | 节 | 上述条目 `end-start+1` 之和（压力指数用） |
| `weekCourseCount` | 门 | 本周生效条目的不同 `courseId` 数 |
| `weekEntryCount` | 条目 | 本周生效条目总数 |

### 五种页面状态

| 状态 | 条件 | 界面 |
|---|---|---|
| `READY` | 学期已设且今天在教学周 | 周视图（有课/无课各自分支） |
| `NO_SEMESTER` | 没有学期或 `startsOn` 为空 | “待设置学期”+ 设置入口 |
| `NOT_IN_TERM` | 今天不在 `1..weekCount` | 横幅提示，仍可翻周 |
| 空课表 | `READY` 且本周 0 条 | “本周暂无课程”+ 添加按钮 |
| 读取失败 | 快照流异常 | “加载失败”卡片 |

### 周次

- 位图 `Long`，第 n 周 → bit(n-1)，上限 64 周（第 64 周是 `Long` 符号位，**不能用正负判断有效性**）。
- 文本由 `ScheduleWeeks.format` 统一生成；解析只走 `WeekText.parse`，非法段丢弃并记警告，整条不算失败。
- `startsOn` 一律归一化到周一；缺起始日不猜，直接进入 `NO_SEMESTER`。

### 冲突

同一星期 + 节次区间相交 + 周次位图相交：不同课程算冲突；同课程但地点/教师不同按数据异常提示；完全相同的重复条目去重。**只提示不拦截保存**。

### 错误文案

| 错误码 | 文案 |
|---|---|
| `VERSION_CONFLICT` | 内容已被修改，请刷新后重试 |
| `COURSE_NOT_FOUND` | 课程不存在，请刷新后重试 |
| `INVALID_RECORD` | 数据格式不正确，请检查后重试 |
| 空标题 / 超长 | 请填写课程名称 / 课程名称过长（≤60 字） |
| 周数非法 | 学期周数需在 1—64 之间 |

## 四、各成员怎么接

### A：首页六个字段

`home/RepositoryHomeGateway.projectHome()` 现在写死：

```kotlin
courseAvailability = if (snapshot.semesters.none { it.startsOn != null }) NoSemester else Unavailable
```

替换为：

```kotlin
val course = HomeCourseProjection.project(snapshot, now)
// HomeUiState(...) 里
courseAvailability = course.availability,
todayCourseCount = course.todayCourseCount,
weekCourseCount = course.weekCourseCount,
courses = course.courses,
nextCourse = course.nextCourse,
nextCourseHint = course.nextCourseHint,
```

**注意**：这会把“已配学期但没排课”的语义从 `Unavailable` 改成 `Ready`（显示 0），需要同步改两条断言：

- `home/HomeProjectionTest.kt:61`
- `fakedata/BoundaryFixtureTest.kt:38`（F 的文件）

`coursesScreen` 插槽已经在 `MainActivity` 接好，A 不需要再动 `AppShell`。

### F：切换课程统计源

把 `StatisticsViewModel(repository, courseStatsSource = ...)` 的默认值换成 `RepositoryCourseService.repositoryCourseService` 实例（`MainActivity` 里的 `owner.courseService`），即可删除 `IntervalCourseStatsAdapter`。

### C：解析输出格式

- 课程用教务课程编号作 `externalId`；
- 周次输出规范化文本（`9,11-13`），单双周转成集合文本；
- 解析不了的字段留空，不算失败；
- 学期起始日读不到就置空，由用户在课表页补录。

### E：无需改动

v3 表结构、`ScheduleDraft.weeks/weeksText/teacher`、`fingerprint`、`validateCourse` 都已就绪；B 只按这些字段读写。

## 五、测试与验证

```powershell
cd E:\code\coursekeeper-check
$env:JAVA_HOME='E:\java\jdk21'
.\gradlew.bat :app:testDebugUnitTest --tests "com.coursekeeper.course.*" --console=plain
.\gradlew.bat :app:assembleDebug
```

覆盖范围：

| 测试类 | 覆盖 |
|---|---|
| `WeekTextTest` | 非连续周、分段、单双周、越界、非法段、位图文本往返 |
| `WeekCalculatorTest` | 周一起算、起始日非周一、学期边界、当前学期选择 |
| `ConflictDetectorTest` | 相交/不相交、同课程异地点、重复条目去重 |
| `CourseProjectionTest` | 四个数字与 F 的临时适配器逐项一致、跨节次、同名不同码、非连续周 |
| `CourseViewModelTest` | 初始周、翻周边界、未设学期、学期设置、节次时间保存与校验、保存/删除、版本冲突文案、结束节超限 |
| `HomeCourseProjectionTest` | 三种状态映射、下一节课与提示、当天课程结束 |

另外在模拟器（`Medium_Phone_API_37.0`）上手工走通：设置学期（含 2 节节次时间）→ 添加课程 → 冲突提示 → 详情 → 翻周 → 删除；设置节次后课表卡片显示 `08:00 — 09:40`。

## 六、已知限制

1. **没有官方节次时刻表**：现在由用户在学期设置里手填（`PeriodRecord`）；官方对照表到位后可做默认预填。未填时只显示“第 X-Y 节”，`nextCourse.time` 为空。
2. **停课/假期未支持**：校历第 13 周校运会停课不会自动跳过，需手动调整课程周次（见规则 v0.2 §10.1，本期明确不做）。
3. **调课覆盖层未做**：群通知里的调课不会改课表，只能手动编辑课程或建任务提醒（同上，本期明确不做）。
4. **跨学期重修**：`courses` 仍是 `(source, externalId)` 全局唯一，同一课程编号两个学期会冲突（E 的开放问题）。
5. **课表页无设备测试**：只有 JVM 单测 + 模拟器手工验证，没有 Compose UI Test。

## 七、提交记录（本地，未推送）

```
feat(course): 课程读侧投影与周次/冲突规则（第 1-2 步）
feat(course): 课表页与课程维护接入（第 3-4 步）
feat(course): 首页课程投影（第 5 步）
docs(course): 规则 v0.2、交接说明与验收证据（第 6 步）
```
