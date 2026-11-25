# C 证据：CBE-01 自动课程映射与 P2 历史作业补抓（2026-11-25）

基线：main `7d04477`。运行环境：Windows，JDK 21（Zulu 21.0.38），Gradle 9.6.0，`:app:testDebugUnitTest --offline`。**本轮无真机、无模拟器、无学校账号会话**，全部证据为 JVM 单测（Robolectric SDK 28）。

## 1. 运行结果

| 项 | 结果 |
| --- | --- |
| 命令 | `.\gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug` |
| 结果 | `BUILD SUCCESSFUL` |
| 全仓 JVM 单测 | **441 项 / 0 失败 / 0 跳过**（54 个测试类；rebase 到 main `7d2d359` 后复跑刷新，证据 XML 同步更新） |
| 本轮新增用例 | 19 项（`CourseLinkerTest` 9、`MyStuTaskIndexParserTest` 4、`WebViewSchoolAdapterBackfillTest` 6），另有 `TaskSubmissionSyncTest` 由 13 项改为 17 项（净增 4） |
| 构建 | `:app:assembleDebug` 通过 |

本目录内的 XML 为本次运行的原始结果（由 Gradle 写入 `app/build/test-results/testDebugUnitTest/` 后复制）：

| 文件 | 用例数 | 失败 |
| --- | --- | --- |
| `TEST-com.coursekeeper.data.sync.CourseLinkerTest.xml` | 9 | 0 |
| `TEST-com.coursekeeper.data.TaskSubmissionSyncTest.xml` | 17 | 0 |
| `TEST-com.coursekeeper.school.MyStuTaskIndexParserTest.xml` | 4 | 0 |
| `TEST-com.coursekeeper.school.MyStuTaskParserTest.xml` | 10 | 0 |
| `TEST-com.coursekeeper.school.WebViewSchoolAdapterBackfillTest.xml` | 6 | 0 |

## 2. 用例清单

### 2.1 CBE-01 映射规则（`CourseLinkerTest`）

| 用例 | 断言 |
| --- | --- |
| `semesterTagAndTrailingTeacherAreStrippedBeforeMatching` | `[2026秋] 应用密码学 方若宇` → 应用密码学；`【2026秋】`、`（2026秋）` 同规则 |
| `fullNameWithoutTeacherStillMatches` | 无教师名的完整名可命中 |
| `whitespaceWidthAndPunctuationAreNormalized` | 全角方括号、全角空格、全/半角括号不影响命中 |
| `parenthesizedTeacherIsNotStrippedAndStaysUnlinked` | `应用密码学（方若宇）` 保持未关联（不剥括号） |
| `fuzzyNamesAreNeverMatched` | `高级算法导论` ≠ `高级算法`；`操作系统` ≠ `操作系统原理` |
| `sameTitleInTwoCoursesIsAmbiguousAndStaysUnlinked` | 同名不同课判歧义 |
| `candidatesContradictingEachOtherAreAmbiguous` | 两个候选名指向不同课程判歧义 |
| `unknownOrBlankInputStaysUnlinked` | null/空白/空候选/只有学期标签均不关联 |
| `candidatesExposeOnlyDeterministicVariants` | 候选名只有“完整名”和“去末尾教师名”两种 |

### 2.2 CBE-01 合并接入（`TaskSubmissionSyncTest` 新增）

| 用例 | 断言 |
| --- | --- |
| `exactCourseNameLinksSyncedCourseAndUnmatchedNameKeepsExistingLink` | 唯一同名自动关联；学校改名后保留原关联；显式 `courseExternalId` 仍优先 |
| `sameCourseNameInTwoSyncedCoursesIsAmbiguousAndStaysUnlinked` | 两门同名本地课程 → 不关联 |
| `numericSourceCourseIdIsNeverTreatedAsTeachingSystemCourseNumber` | `sourceCourseId` 与本地编号同形也不关联 |
| `autoLinkedCourseIsKeptWhenLocalCourseIsMissingFromThisSnapshot` | 只同步任务时关联稳定不抖动 |
| 既有 13 例 | `manualDirty` 保护、完成/恢复、墓碑、批次回滚、版本冲突等全部保持通过 |

### 2.3 P2 索引页解析（`MyStuTaskIndexParserTest`）

| 用例 | 断言 |
| --- | --- |
| `allIndexRowsWithStableViewIdsAreParsedWithDueDateAndStatus` | 新/老模块混排 3 条；`00:00` 不退化成 23:59；状态归一 `SUBMITTED`/`NOT_SUBMITTED` |
| `sourceCourseIdentityIsCarriedForLinkerAndDedup` | 来源课程透传、`courseExternalId` 仍为 null、`missingIdentity=0` |
| `pageWithoutAssignmentRowsYieldsWarningInsteadOfGuessing` | 结构不符 → 0 条 + 警告 |
| `loginPageIsNotMistakenForAnEmptyIndex` | 登录页不被当成空索引 |

### 2.4 P2 适配器编排（`WebViewSchoolAdapterBackfillTest`）

| 用例 | 断言 |
| --- | --- |
| `indexBackfillAddsAssignmentsThatHomepageNoLongerShows` | 首页作业保留 + 索引补回历史作业 |
| `cachedCourseMissingFromHomepageIsStillBackfilled` | 缓存里“整课移出首页”的课程可补回 |
| `failingIndexRequestDegradesToHomepageResult` | 单课失败只跳过该课 |
| `unrecognizedIndexStructureStopsAfterTwoMissesInsteadOfWastingRequests` | 连续 2 门课无作业行即停（恰好 2 次请求） |
| `indexRequestsAreCappedByCourseBudget` | 最多 12 门课（恰好 12 次请求） |
| `homepageWinsWhenIndexRepeatsTheSameAssignment` | 同 ID 保留首页内容 |

## 3. 未覆盖与限制

- **真机**：C-01（旋转/返回/关闭重开）、C-02（Release 包不落盘）、补抓的真实同步均未在手机上验证。
- **索引页结构**：真实页面未抓取，fixture 按 Moodle 4.x 标准表格渲染结构编写；真实结构不同时解析为 0 条并停止，不会误导入，但补抓无效。待 D 提供脱敏索引页后校准。
- **老模块索引页**：`mod/assignment/index.php` 路径未确认，当前只抓配置的新模块模板。
- **缓存范围**：`school_course_cache` 是历史发现记录，不代表当前学期名单，也不区分账号。

## 4. 复现

```powershell
cd <repo>
.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.coursekeeper.data.sync.CourseLinkerTest" `
  --tests "com.coursekeeper.school.MyStuTaskIndexParserTest" `
  --tests "com.coursekeeper.school.WebViewSchoolAdapterBackfillTest" `
  --tests "com.coursekeeper.data.TaskSubmissionSyncTest"
```
