# E v3 迁移与联调验收

日期：2026-11-11。开发基线：`a419f22`。本轮完成 E 的数据结构升级、迁移和同步兼容，解除 B/F 对非连续周次存储的依赖。用户已明确授权分段直接提交 main，不创建 PR；推送前同步远端并重新执行先测后构检查。

## 本轮结果

| 检查 | 结果 |
|---|---|
| 全仓 JVM 测试 | **126 项通过，0 失败、0 错误、0 跳过** |
| E 数据模块 | 44 项通过；本轮新增 16 项 |
| 原 F 非连续周占位 | 已替换为真实素材与断言，取消 Ignore |
| 应用调试包 / 设备测试包 | 均构建成功 |
| 工作区与构建副本 | 66 个源码、测试、schema 与构建配置文件逐字节一致 |
| lint | 既有首页 Preview 错误 1 处；E 源码错误 0 |
| 设备测试 | 当前 adb 无设备；已编译测试 APK，未执行设备测试 |

可复核证据：[逐项测试 XML](evidence/member-e-v3-tests.xml)、[构建/包校验和与 lint 摘要](evidence/member-e-v3-checks.json)。

推送前已完成 `git fetch origin` 和 `git rebase origin/main`（无冲突），以 `--rerun-tasks` 实际执行 31 个测试构建任务，126 项测试再次全部通过；随后应用和设备测试包构建成功。

## 根据最新进展完成的对接

- A 的首页已读取真实任务；D 的任务页和提醒已订阅同一份快照；F 的看板与 StatisticsService 已接入。本轮保留这些功能与业务规则，验证它们在 v3 数据库下继续工作。
- B 的《课程与周次规则 v0.1》要求保存 `9,11-13` 等非连续周次及排课独立教师。E 新增对应字段、迁移和统一编码；B 的正式 CourseService 与规则生效仍由 B 推进。
- F 的临时 `IntervalCourseStatsAdapter` 保留原入口名称，内部改为查询位图中的当前周，不再用连续区间补齐空缺。门数、条目数、节数和压力公式不变。
- C 的真实 SchoolAdapter/作业稳定 ID 尚未入库；本轮验证结构化导入、差异更新和失败保护，没有伪造学校请求。

## 数据结构与迁移

`schedules` 以 `weeks: Long`、`weeksText: String` 替换原 `startWeek/endWeek/weekPattern`，新增可空 `teacher`。`weeksText` 从位图统一生成；例如 7424 对应 `9,11-13`，第 10 周不会被误算为有课。

位图包含 1—64 周，第 64 周使用 Long 的符号位，不能用“正数”作为有效性判断。条目 teacher 为空时由 B 的读侧使用课程默认教师。没有引入未经确认的学期默认长度、停课规则或课表时间。

迁移链为 `1 → 2 → 3`，由 Room 的显式 Migration 执行，并提交 schema 3。v1/v2 schema 保留原文件。实现依据：[Android Room 数据库迁移指南](https://developer.android.com/training/data-storage/room/migrating-db-versions)。

迁移验证覆盖：

1. 连续周、单周、双周、第 64 周均按旧库真实集合转换；原排课 ID、课程 ID 和版本不变。
2. 课程、学期、节次、任务关联、完成时间、manualDirty、同步时间、请求回执及删除墓碑保留。
3. 删除课程仍级联删除排课；任务保留原 courseId。旧 requestId 任务回执继续重放，墓碑继续阻止任务复活。
4. 首次在 v3 编辑迁移课程时，未变条目沿用旧 ID，不因新指纹格式统一换号。
5. 旧库中单双周与区间交集为空的条目保留为空位图，不生成不存在的上课周；新写入拒绝空周次。
6. 旧区间超出 1—64 周时，中止并回滚迁移；测试确认数据库仍为 v2、原区间和任务完整，没有破坏性重建或静默截断。
7. 空 v2 库正常升级；原 v1 基线测试已覆盖完整迁移链。

## Repository 与同步

新写入校验位图非空、文本一致、星期/节次区间合法；已关联学期时，周次须位于该学期范围。B/C 负责输入字符串解析和解析容错，E 负责结构化数据的持久化检查。

排课指纹包含周次位图、规范文本、星期、起止节、地点和条目教师；两条只在周次或教师上不同的排课可以同时保存。课程仍按整体提交，版本增加一次；同步返回顺序改变不会产生新版本。

同步合并一次读取课程和排课并分组，只对实际变更的课程查询其条目，避免逐门重复扫描排课全表。事务、expectedVersion、手动保护、完成状态保护、墓碑及部分成功语义沿用原约定。

保留旧区间参数的转换构造器，现有 A/F 示例代码可继续编译；Room 只存新字段。新增代码应使用：

```kotlin
val weeks = (1L shl 8) or (1L shl 10) or (1L shl 11) or (1L shl 12)
val entry = ScheduleDraft(2, 1, 2, weeks = weeks, teacher = "教师甲")
// entry.weeksText == "9,11-13"
repository.createCourse(CourseDraft("课程", schedules = listOf(entry)))
```

## 验证环境与交付

遵循当前 README 的“先测后构”：先 `:app:testDebugUnitTest`，通过后构建 `:app:assembleDebug :app:assembleDebugAndroidTest`。JDK 21、SDK 37 与依赖版本沿用仓库；为避开 Windows 中文/SQLite 长路径，使用英文副本 `C:\tmp\coursekeeper-e-v3` 和短测试临时目录。SDK 路径、代理、临时目录与构建缓存未写入仓库。

应用包：`C:\tmp\coursekeeper-e-v3\app\build\outputs\apk\debug\app-debug.apk`。校验和见构建证据 JSON。

lint 的既有错误在 `ui/HomeScreen.kt:114`：Preview 直接读取 `StateFlow.value`。该文件、MainActivity、D 的任务/提醒实现和 F 的 StatisticsService 均无本轮修改；未关闭 lint 或添加 baseline。

## 后续交接

- B：数据字段与迁移已就绪，可接入 CourseService、教学周/冲突规则与课程维护；B v0.1 仍按全体确认流程生效。
- C：按新 ScheduleDraft 提供结构化位图和教师；学校解析与作业稳定 ID 继续由 C 实现。
- A/F：本轮已兼容现有真实任务/统计读取；B 正式服务到位后再替换临时课程统计来源。
- 跨学期同编号课程的唯一键仍是 B 的开放问题；本轮未擅自改变已用的去重和任务关联规则。
- Agent 密钥管理属于基础版验收后的阶段，本轮未提前启用。

本轮按用户明确授权分段直接提交 main，不创建 PR；推送前执行 fetch/rebase，先通过全仓单元测试，再构建应用与设备测试包。构建证据中的 prePushVerification 记录本次推送前复核结果。
