# C 成员：学校登录与数据适配交接

更新日期：2026-11-14（T05 首校实测）。对应计划书 T05（官方登录与数据适配）与 T06 的适配器侧配合，实现 E 的 `SchoolAdapter` 契约（见《E 成员数据与同步交接》）。首批学校为**汕头大学**：登录走统一身份认证（SSO），课表取正方教务「学期理论课表」，作业取 MYSTU 学习平台（Moodle）「课程概览」。同日修复见「本轮修复」。

## 交付范围

新增 `com.coursekeeper.school` 包：

| 文件 | 内容 |
|---|---|
| `SchoolCatalog.kt` | 学校接入信息：`id/name/loginUrl/portalUrl/scheduleUrl/taskListUrl/termTag/allowHttpHosts`，以及 `courseParser` / `taskParser` 两个解析器入口 |
| `SchoolSession.kt` | 会话规则纯函数（`SchoolRule`：会话检测、登录完成、MYSTU 登录完成）、轻量 HTTPS GET（`SchoolHttp`，带最终 URL）、退出登录（`SchoolLogout`，只清会话 Cookie） |
| `StuJwPageParser.kt` | **课表解析**：汕头大学正方教务（网格 + “有课表课程”编号表） |
| `MyStuTaskParser.kt` | **作业解析**：MYSTU（Moodle）课程概览页（标题 / 截止 / 类型 / 作业 ID） |
| `SchoolPageParser.kt` | 通用宽表解析器（`TableSchoolPageParser`）、截止时间解析（`DueDates`）、容错 HTML 提取（`MiniHtml`），保留为参考实现 |
| `WebViewSchoolAdapter.kt` | `SchoolAdapter` 实现：会话检测、课表/作业分别拉取解析，失败映射为 `SyncFailure` |
| `SchoolLoginController.kt` | 登录流程状态机：选校 → WebView 登录 → 1.5 秒反馈过渡 → 导入 → 完成/失败 |
| `SchoolLoginSheet.kt` | 登录弹层 UI：选校、已连接状态、WebView 官方登录页、成功过渡、完成提示、退出登录 |

修改的共享文件（均为预留插槽的最小改动）：

- `ui/AppShell.kt`：新增 `loginSheet` 可选参数（同 D/F 的接入方式）；非演示模式且传入时渲染 C 的登录弹层，并在顶栏保留「学校」常驻入口（登录后仍能做重新登录/退出），否则保留 A 的占位弹层，独立演示工程不受影响。
- `MainActivity.kt`：`HomeDataOwner` 构建应用级适配器与 `data.syncCoordinator`（E 约定同一实例），解析器取 `SchoolCatalog.parser`；引导完成且已选校后调用 `sync.onForeground()`；登录成功回调里 `selectSchool` + `completeOnboarding` + 强制同步；退出登录清会话并 `selectSchool(null)`。
- `AndroidManifest.xml`：新增 `INTERNET` 权限（此前缺失，WebView 与同步均依赖）。
- `res/xml/network_security_config.xml` + Manifest `android:networkSecurityConfig`：只对 `jw.stu.edu.cn` 放行明文 HTTP（SSO 回跳需要），其余域名仍按默认仅 HTTPS。

## T05 实测结论（汕头大学，2026-11-14）

| 项 | 实测结果 |
|---|---|
| 登录入口 | `https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F`（统一身份认证） |
| 登录后回跳 | SSO 的 `service` 是**明文** `http://jw.stu.edu.cn/`，登录成功会回到 http 门户；WebView 与登录判定都要对 `jw.stu.edu.cn` 单独放行 |
| 教务门户 | `https://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx`（正方 jsxsd） |
| 课表来源 | `https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do`「学期理论课表」 |
| 课表结构 | 表头 `星期一…星期日`；行首「第一大节 (01,02小节)」「第二大节 (03,04,05小节)」…；每个条目一个 `div.kbcontent`，按 `<br>` 分行：`课程名 / 教师 / 周次(周)[节次] / 【校区】 / 教室 / 通知单编号 / 班级：…` |
| 周次写法 | `3-18(周)`、`4,6,8,10,12,14,16,18(周)`、`15(周)`；直接交给 B 的 `WeekText.parse` |
| 节次 | 条目内 `[03-04节]` 优先（同一门课可跨多个大节），缺失时退回行首大节标签 |
| 课程编号 | 网格里**没有**课程编号；在「有课表课程」表 `table#dataTables2` 中，列序为 `序号? | 上课班级 | 课程编号 | 课程名称 | 授课教师`，用单元格里的“班级：202611595”反查（实测 9 门课，全部命中） |
| 学期起始日 | 页面不提供；返回 null，由用户在课表页手填，不猜日期 |
| 作业来源 | MYSTU 学习平台 `https://my.stu.edu.cn/courses/campus/my/`（Moodle），与教务系统**不同源、不同会话** |
| 作业结构 | 每门课一个 `div.coursebox#course-<id>` → 作业块（**两套 Moodle 模块并存**，D 2026-11-21）：老 `div.assignment.overview`（`mod/assignment/view.php?id=`、「截止时间: …」）与新 `div.assign.overview`（`mod/assign/view.php?id=`、「到期日期: …」、状态带「我的作业：」前缀与「未评分」尾缀）；块内同为 `div.name` / `div.info` / `div.details`（状态归一为 `schoolSubmission` 透传，暂不落库） |
| 作业稳定 ID | 作业链接里的 `view.php?id=`（如 307183） |
| 第二处登录 | 教务登录成功后，在同一 WebView 里再加载一次 MYSTU，复用 SSO 会话；落在 `my.stu.edu.cn` 的非登录页才算完成 |
| 验证码 / SSO 细节 | 登录页内完成，类型与是否二次验证尚未记录（见待办） |

隐私：实测页与导出文件只在本机解析，仓库里的样例已脱敏（课程名/教师/班级号/教室均为虚构），未提交任何真实学号、姓名或 Cookie。

## 与 E 的契约对齐

- `checkSession()` 返回 `VALID/EXPIRED/OFFLINE`：门户页 2xx 且非登录表单为有效；401/403 为过期；**被重定向到 SSO（`HttpReply.finalUrl` 主机不再是门户）也判过期**；5xx 按离线上报，不误报“登录过期”。
- 稳定标识（E 交接 §C 的同步输入）：
  - 课程 `externalId` 取页面课程编号（如 `CST3254A`）。`(source, externalId)` 是全局唯一键，跨学期重修会撞（B 的开放问题 6），因此 `SchoolInfo.termTag`（如 `2026AUT`）会作为前缀；课程与作业的课程关联两侧同时加前缀。未配置时保持原样。
  - 缺课程编号的课表条目**不导入**，计入 `skippedCourses`；节次或周次无法确定的条目同样跳过，不默认到第 1 节。**空表、有跳过条目或带解析警告的课表一律按 `PARSE_ERROR` 上报**，只有干净的非空快照才以 `Success(records, completeSnapshot = true)` 交给合并层清理缺席课程（CE-01，E 于 `75ce17a` 定稿；C 的解析器负责把“周次部分无法解析”等缺口记成警告）。
  - 作业 `externalId` 取 MYSTU 作业链接的 `view.php?id=`；缺 ID 的条目以 `externalId = null` 上交，由 E 计数。课程关联仍留空（`courseExternalId = null`）：MYSTU 课程是另一套 ID 体系，按名称模糊匹配会引入错误关联。C 透传来源信息（`sourceCourseId` / `sourceCourseName`）与学校提交状态（`schoolSubmission`：`SUBMITTED`/`NOT_SUBMITTED`/`DRAFT`/`REOPENED`/`UNKNOWN`）；**E 已在 Room v4 落库**，并按 D v1.1 对 `SUBMITTED` 自动填入未保护任务的完成时间（2026-11-21）；课程 ID ↔ 教务编号映射仍待 B/E。
- 周次经 B 的 `WeekText.parse` 转位图后交给 `ScheduleDraft`，不自建口径。
- 节流、重试、部分成功、墓碑合并全部由 E 的 `SyncCoordinator` 负责，适配器只做拉取与解析；`PARSE_ERROR/OFFLINE/EXPIRED/REMOTE_ERROR` 按 `ModuleResult.Failure` 上报。
- 作业模块：`taskListUrl` 为 null 或页面解析不出结构时返回 `Failure(PARSE_ERROR)`，整体呈现 PARTIAL，不把“没解析”伪装成“没有作业”；课表模块不受影响。

## 隐私与安全边界（计划书 §五）

- 账号、密码、验证码只在学校官方登录页（WebView）内输入，应用不经手、不保存；日志不记录任何凭据。
- 会话 Cookie 由 `android.webkit.CookieManager` 持有，仅用于本机同步，不进 Room/DataStore/备份（E 交接 §表结构约定）。
- WebView 默认只放行 HTTPS；`allowHttpHosts` 是**逐域名白名单**（当前仅 `jw.stu.edu.cn`，因为该学校的 SSO `service` 是明文），其它明文与自定义 scheme 一律阻断。系统层再用 `network_security_config.xml` 只对该域名允许明文，避免“代码放行、系统拦截”的白屏。弹层同时展示登录页域名与数据接收域名。

## 退出登录与换校

- 入口：顶栏「学校」（`AppShell` 在非演示模式且传入 `loginSheet` 时显示）→ 弹层「已连接：…」→「退出登录」；过期场景直达登录页时，登录页下方也保留退出入口。
- 行为：`SchoolLogout.clear()` 清 `CookieManager` 会话 → `settings.selectSchool(null)` → 登录控制器复位；**不删本地课程任务**，也不动 E 的同步记录。
- 过期入口（`SyncStatus.Expired` → `autoLogin = true`）仍直达已记住学校的登录页，不会被退出入口挡住；用户主动点「学校」进来时先看到连接状态与两个动作。
- 换校：首期目录只有一所学校；换到未登记学校会被 `SyncCoordinator` 的 `SchoolMismatch` 拦下，不混写、不清库。

## 本轮修复（2026-11-13 / 11-14）

| 问题 | 修法 | 位置 |
|---|---|---|
| 目录是占位学校（`example.edu.cn`），点了必然超时 | 换成汕头大学真实地址；课程解析换成实测实现 | `SchoolCatalog`、`StuJwPageParser` |
| SSO 登录成功回跳是 **http**，被 WebView 与系统明文策略双重拦截，永远检测不到登录 | `allowHttpHosts` 逐域名白名单：WebView 放行 `http://jw.stu.edu.cn`、`isLoginComplete` 同步支持；`network_security_config` 只对该域名允许明文 | `SchoolCatalog`、`SchoolLoginSheet`、`SchoolRule`、`AndroidManifest.xml` |
| 会话过期只靠“响应体像登录页”判断，SSO 重定向时可能误判有效 | `HttpReply` 带最终 URL；主机名不再是门户即判 EXPIRED | `SchoolSession`、`WebViewSchoolAdapter` |
| 作业源（MYSTU）未接入 | 新增 `MyStuTaskParser`（课程概览 → 标题/截止/类型/作业 ID）；登录流程追加一次 MYSTU 登录 | `MyStuTaskParser`、`SchoolLoginSheet` |
| 页面解析不出结构时会把空列表当“同步成功、0 条” | 解析结果为空且有警告 → 模块 `Failure(PARSE_ERROR)`；课表同样处理 | `WebViewSchoolAdapter` |
| 课表部分解析失败会被镜像合并当成“学校删课”整门删除（CE-01） | 适配器空表/跳过/带警告一律 `Failure(PARSE_ERROR)`，只有干净非空快照才带 `completeSnapshot = true` 授权清理（E `75ce17a` 定稿，C 解析器补“周次部分无法解析”警告） | `WebViewSchoolAdapter.toCourseSyncResult` |
| 新 `assign` 模块作业 4 条全部漏抓（D 2026-11-21 P1） | `ASSIGNMENT`/`ASSIGNMENT_ID` 正则兼容两套模块，「到期日期:」标签、「我的作业：」前缀与「未评分」尾缀清洗；`index.php` 链接缺 ID 照常暂缓 | `MyStuTaskParser` |
| 首页快照随平台缓存波动，整课消失/重现（D 兜底） | 同一次同步内课程集合并集收敛：再抓一页取并集，仍有新增才继续（≤3 次封顶，单页 10s 上限），失败降级已有结果 | `WebViewSchoolAdapter.convergeTaskPages` |
| MYSTU 截止时间带“星期X”，旧解析器会把时刻当默认 23:59 | `DueDates` 允许日期与时刻之间夹星期 | `SchoolPageParser` |
| 课表网格无课程编号，旧解析器全部跳过 | 从「有课表课程」表按班级号反查课程编号；节次优先取条目内 `[xx-yy节]`；跨大节重复条目去重；修正教师名含“周”导致的周次行误判 | `StuJwPageParser` |
| 缺稳定 ID 的作业被适配器静默丢弃，E 的 `missingIdentity` 恒为 0 | 以 `externalId = null` 上交，由 E 计数并暂缓入库 | `SchoolPageParser` |
| 没有退出登录入口 | 新增 `SchoolLogout`、弹层「退出登录」、顶栏「学校」常驻入口 | `SchoolSession`、`SchoolLoginSheet`、`AppShell` |
| 课程 externalId 无学期前缀，跨学期重修撞 `(source, externalId)` | `termTag` 前缀，课程与任务关联同时加前缀 | `SchoolCatalog`、解析器 |
| 解析不出节次时默认第 1 节（属于猜测） | 整条跳过并计入 `skippedCourses` | `StuJwPageParser` |
| 连接 10s + 读取 30s 可能超出协调器的 30s 超时 | 收敛为 8s + 15s，让阻塞读在超时前返回 | `SchoolRule` |
| 登录完成页承诺“下次同步重试”，实际不会重试 | 文案改为“不会入库，会记入同步记录的缺失计数” | `SchoolLoginSheet` |

## 解析格式与脱敏样例

- `app/src/test/resources/school/stu-timetable.html`：汕头大学课表结构（按实测页面脱敏后编写），覆盖“有课表课程”编号表、网格单元格两层 div、`3-18(周)` 与 `4,6,…,18(周)`、条目内节次、`【校区】+教室` 两行、跨大节重复条目。
- `app/src/test/resources/school/mystu-dashboard.html`：MYSTU 课程概览结构，覆盖 `coursebox → assignment overview`、`view.php?id=` 稳定标识、`截止时间: 2026年11月15日 星期日 23:59`、标题尾部“-截止时间 …”清理、缺 ID 计数。
- `app/src/test/resources/school/schedule-sample.html`、`homework-sample.html`：早期通用宽表/列表格式，保留给 `TableSchoolPageParser` 的回归测试；首批学校不再使用。
- 实测校验（本机，未提交真实页）：用保存的 `xskb_list.html` 跑 `StuJwPageParser`，得到 **9 门课、0 条跳过、0 条警告**，与页面人工核对一致。

## 登录成功判定

WebView 在 `doUpdateVisitedHistory` 中调用 `SchoolRule.isLoginComplete`：当前地址为 HTTPS（或为白名单内的学校明文域名）、已离开登录页、回到教务门户域名即视为成功，随后走“登录成功 → 读取课表 → 计算周次 → 完成”过渡（约 1.5 秒反馈，计划书 §四），再由 `afterLogin` 强制同步一次。失败给出原因并可重试；已有本地数据不受影响。

## 本地验证

`JAVA_HOME` 指向 JDK 21 后执行 `:app:testDebugUnitTest :app:assembleDebug`。本轮结果：全仓 **234 项单元测试，0 失败、0 跳过**，其中 C 的模块 **27 项**（通用解析 7、汕头课表 6、MYSTU 作业 3、会话规则 6、登录状态机 4、退出登录 1）；`:app:assembleDebug` 构建通过。真机端到端（真实账号登录）待补验收记录。

## 真机联调发现（2026-11-15）

首次真机登录后：作业 8 条全部出现在任务页，但**课表页一门课都没有**。拉取设备数据库核对：

| 表 | 实际数据 |
|---|---|
| `courses` | 9 条，`source = SYNC`，`semesterId = null` |
| `schedules` | 14 条 |
| `tasks` | 8 条 |
| `sync_records` | COURSES / TASKS 均 `IDLE` 成功（inserted 9 / 8） |
| `semesters` | **空** |

即导入是成功的，问题在展示层：

1. 用户还没设置学期 → `CourseProjector` 返回 `NO_SEMESTER`，课表页显示“待设置学期”。
2. 更关键：`CourseProjector` 原来只取 `semesterId == 学期.id` 的课程。C 的导入发生在“用户尚未创建学期”之前，9 门课全带 `semesterId = null`，**即使补录学期也永远不显示**。

修复：`CourseProjector` 增加 `coursesOf(semesterId)`，放行“显式绑定该学期”或“`source == SYNC` 且尚未绑定学期”的课程；`CourseViewModel` 的课程清单复用同一规则。手动新建但未关联学期的课程仍按原口径忽略（F 的统计用例依赖该语义）。改动跨到 B 的模块，需 B 复核。

验证（真机，设学期 2026-09-07 / 20 周后）：课表页显示“第 10 周 · 10 条排课”，周一/周二课程、节次、教室、教师与教务页面一致；作业 8 条正常。

遗留：首页「今日课程/下一节课」仍显示“暂无课程信息”——A 的 `RepositoryHomeGateway` 尚未接入 B 的 `HomeCourseProjection`（第 102 行仍是 `Unavailable` 占位），属 A 的待办，不在 C 的适配范围内。

## 第二轮真机检验（2026-11-15 下午）

覆盖安装复验用户反馈的 4 个问题并全部修复：会话过期误报“离线”（重定向不跨协议 + JS 壳页）、解析器一格多条排课只取第一条（潮汕非遗 9,11-13 周段缺失 → “下周不显示”）、幻影课程残留（`SyncMerge` 镜像清理，E 复核）、登录弹层 WebView 自适应高度。同步镜像口径与课表页「今日/本周/上周/下周」页签 + 整周网格一并落地。过程、根因与真机数据对照详见《C-学校适配实现说明》第 13 节。

## 边界与待办

- **课表地址已在真机验证**：`https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do` 能取到「学期理论课表」；若以后学校加查询参数导致空表，按 F12 复制真实地址替换 `scheduleUrl`。
- **MYSTU 已在真机验证**：登录后第二处 SSO 生效，8 条作业入库；仍需补充“显示/折叠”边界（作业很多时页面是否只渲染部分）。
- **作业与课程关联待定**：MYSTU 课程 ID（`course/view.php?id=`）与教务课程编号是两套体系，当前 `courseExternalId = null`；C 已透传 `sourceCourseId` / `sourceCourseName`，需与 E、B 约定按规范化课程名匹配还是维护映射表。
- **学校提交状态已闭环**：C 解析 `div.details` 透传 `schoolSubmission`；D v1.1 定口径（SUBMITTED → 自动完成未保护任务），E Room v4 落库，D 已真机验收（`docs/evidence/d-cd01-verify/`）。小组作业/补交通道照实记录未提交→逾期，用户手动标完成（已知限制）。
- **验证码与 SSO 细节未记录**：登录页验证码类型、是否需要二次验证、单点登录是否互踢，都要在真机走一遍后写回本文。
- 确定学期后需填 `SchoolInfo.termTag`（如 `2026AUT`），否则课程稳定标识仍只有课程编号，跨学期重修会撞 `(source, externalId)` 唯一键。
- 学期起始日页面不提供，必须由用户在课表页补录；周次显示依赖它（真机验证用 2026-09-07 / 20 周，实际值以校历为准）。
- B 的 `coursesOf` 改动与首页课程区（A 未接 `HomeCourseProjection`）需在各自 PR 中复核；C 侧已提供可复现数据。
- 真机/模拟器端到端（真实账号登录、课表导入、过期重登、退出登录）与 C 的验收证据（截图、测试 XML）尚未归档到 `docs/evidence/c-*`。
- 下拉强制刷新手势属 A 的接入点（`sync.synchronize(force = true)` 已可用）；`DemoScenario.Expired` 演示场景保持 A 原有行为，未混入真实链路。
- 多学校切换、账号数据分区按 E 交接待后续约定；首期仅一所学校。
