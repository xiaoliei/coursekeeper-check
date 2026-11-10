# A：首页真实任务接入（本地联调）

日期：2026-11-10。基线：`ccde71aed4135bcc0690120d17449820788a96d8`。本地分支：`feat/a-home-integration-local`。本轮按《阻塞优先级》处理 A 的 P1 首页数据和 P3 课表插槽，代码保存在本地，尚未推送 GitHub。

状态：**本轮 A 的 P1／P3 本地开发与验收完成，可进入组内交接。** 用户已确认手动测试没有问题；课程、统计、学校同步的后续接入仍按下述边界推进。

## 本轮完成

- `HomeDataOwner` 创建应用共用的 `CourseKeeperData` 和 D 的 `RepositoryTaskGateway`。首页与任务页读同一个 Room 数据库、经同一个任务写入口提交。
- 新增 `RepositoryHomeGateway`：消费 E 的一致性快照和持久化设置，生成首页任务、今日截止、今日完成及本周任务概览；按设备本地日期每分钟重算。
- 新增、完成、撤销会联动两页。首页完成操作提交界面所见版本；撤销提交完成回执里的版本，第三方已编辑时提示冲突。
- 首页及其他页面的全局新增入口会跳到 D 的任务页并打开 D 的表单，复用五类、可空截止、失败保留草稿和提交中禁用等行为。
- 首页“今日截止”对应全部状态中今日截止的任务，包含已完成；“今日完成”按 `completedAt` 的本地日期筛选。跳转会原子清除旧类型条件，避免列表被旧筛选缩窄。
- 增加 `coursesScreen` 可选插槽。默认 `null` 使用原课表占位页；`taskScreen = null` 仍支持原独立演示。全局新增仅在真实任务页选中时隐藏。
- 跳过引导写入 E 的 SettingsStore；重启保留。首页读取中、读取失败、重试与同步状态独立展示。
- 真实模式使用实际日期，隐藏演示菜单，不显示固定课程、固定时刻或压力样例。无学期显示“待设置学期”；B/F 服务未接入时显示缺失状态，不把它当作真实 0。

## 新增接入参数

`AppShell` 原有参数保留，末尾增加可选参数：

| 参数 | 用途 |
|---|---|
| `coursesScreen: (@Composable (Modifier) -> Unit)?` | B 在组合根提供正式课表页，页面消费传入的内容区 Modifier |
| `onTaskFilter: (TaskFilter) -> Unit` | A 导航将首页的待办／全部／今日截止／今日完成请求交给 D 的查询状态 |
| `onCreateTask: (() -> Unit)?` | 真实模式打开 D 的新增表单；没有回调时安全地进入真实任务页，不写演示数据 |
| `onRetryHome: () -> Unit` | 重新收集本地数据流 |

D 的 `TaskScreen` 只追加可选参数：`createRequested`、`onCreateRequestConsumed`、`onClearDateFilter`。新增请求被消费后清除，避免切页或旋转后重开旧表单。`TaskQuery` 把状态、类型和日期条件放在同一个状态对象中；原 `setStatusFilter`、`setTypeFilter` 继续可用。

`HomeGateway.setTaskCompleted` 新增 `expectedVersion` 并返回 E 的 `WriteReceipt`。默认空版本只兼容旧演示调用；真实实现拒绝缺少版本的写入，不用临时读取的新版本代替用户看到的版本。`TaskUi.version` 保留快照版本。`NewTask.deadline` 可空，正式课程关联使用 `courseId`，不按课程显示名猜测 ID。

## 口径和边界

遵循仓库《任务字段与通知规则 v1.0》：待定截止任务置前并显示“截止待定”；今日截止包含完成项；今日完成按完成时间；本周按周一至下周一开区间统计截止任务，待定不入分母。类型与错误文案复用 D/E 的定义。

本轮未改 E 的表结构、迁移或 B 的周次算法。课程列表／教学周／下一节课等待 B 的正式服务与 E v3；压力指数等待 F，未自行定义压力公式。现有本周任务摘要是已确认任务口径的投影，F 统计服务接入后可替换其来源。

`CourseAvailability` 为首页预留 `Ready / NoSemester / Unavailable / NotInTerm`。当前真实模式只根据已保存的学期配置区分 NoSemester 与 Unavailable；不自行计算是否处于教学周。课程页未注入 B 的实现前是只读占位，尚不能完成学期设置。

C 的真实登录／SchoolAdapter 尚未提供，当前只展示 E 已保存的模块同步状态，不伪造登录成功或主动发学校请求。通知仍由 D 后续实现；共享任务网关为后续业务副作用保留统一接入点。

## 验证记录

2026-11-10 完成以下验证：

| 检查 | 结果 |
|---|---|
| Debug 应用与 AndroidTest APK 构建 | **已用 JDK 21 补验通过**，交付 APK 已更新 |
| JVM／Robolectric 单元测试 | **JDK 21 下 65 项通过，0 失败、0 跳过**（含原有 53 项与本轮新增 12 项） |
| API 37 模拟器界面测试 | **此前 6 项通过**（4 项导航／插槽，2 项真实 Activity + D 表单 + E 文件数据库联调）；本次仅切换 JDK，未重复执行设备测试 |
| 手动强制停止应用后重开 | 首页已完成数和任务完成状态保留，不再出现首次引导 |
| 首页“今日完成”跳转 | 显示同一任务和“今日完成 · 1 项”，没有重复的底部新增按钮 |
| APK 签名校验、git diff 空白检查 | 通过 |
| Android Studio 实际同步与启动 | 日志确认 21:54 同步成功、22:05 在 Medium Phone API 37.0 模拟器安装并启动 `com.coursekeeper.MainActivity` |
| 用户手动验收 | **用户确认“测试没有问题”**；验收流程为新增今日截止任务、首页计数、完成／撤销、重启后数据保留，见 [用户验收记录](evidence/a-home-integration/user-acceptance.md) |

新增测试覆盖：D 新建／A 完成／D 撤销双向联动、空截止和五种类型、显示版本冲突、持久化引导、今日两项计数的精确筛选、周一边界与日界更新、同步失败／缺失课程状态，以及原独立演示的默认行为。

证据见 [测试汇总](evidence/a-home-integration/summary.json)、[设备测试输出](evidence/a-home-integration/device-tests.txt) 及同目录下的单元测试 XML。界面截图：[重启后的首页](evidence/a-home-integration/home-persisted.png)、[今日完成任务页](evidence/a-home-integration/completed-tasks.png)。测试在专用模拟器执行，没有改动真实用户的任务。

最新验证环境：Windows、**Temurin JDK 21.0.13+9**、Gradle 9.6.0、Android SDK 37／Build Tools 36.0.0。用户手动安装 JDK 后，已在本地源码工程直接运行 `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`，保留团队原有 `gradle-daemon-jvm.properties` 的 JDK 21 要求，确认 Gradle 与单测均使用 21.0.13。全部构建通过、65 项单测通过，详见 [JDK 21 补验记录](evidence/a-home-integration/jdk21-build.txt)。此次使用本地缓存离线构建，没有另行下载 JDK。

此前 6 项自动化设备测试和专用模拟器上的手动重启检查使用 JBR 25 构建的同一份应用／测试源码。之后补验了 JDK 21，并完成 Android Studio 实际同步、运行及用户手动验收。业务代码未因环境配置而改动，未重复自动化设备测试。后续合并与其他模块联调仍需按团队流程执行 CI。

为避开 Windows 原生 SQLite 的长路径限制，本地验证把测试临时目录设为短路径；Robolectric SDK 依赖经过官方校验值验证后预下载。测试环境设置位于工作目录，IDE 的 JDK 选择和网络配置位于本机忽略文件或用户 Gradle 目录，不进入团队提交和源码压缩包。

## 仍需组内协调

按阻塞通报，A/F 需核对 C 的 GitHub 用户名、邀请是否接受，以及 T05 结论的预计提交时间；E 需给出 v3 迁移排期，F 需确认条目／节的统计语义。这些沟通未通过工具发送给任何组员，也不阻塞本轮真实任务联动。
