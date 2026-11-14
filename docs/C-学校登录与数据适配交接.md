# C 成员：学校登录与数据适配交接

更新日期：2026-11-13。对应计划书 T05（官方登录与数据适配）与 T06 的适配器侧配合；实现 E 的 `SchoolAdapter` 契约（见《E 成员数据与同步交接》）。本轮在 T05 第 1 周实测结论缺席的情况下，先交付可运行的参考实现与脱敏样例，真实学校字段以实测为准替换。同日修订：补齐退出登录、缺失标识透传、学期前缀与节次不猜，见「本轮修复」。

## 交付范围

新增 `com.coursekeeper.school` 包：

| 文件 | 内容 |
|---|---|
| `SchoolCatalog.kt` | 学校接入信息与目录。首批 1 所学校（计划书 §一）；`SchoolInfo` 目前登记占位条目，T05 实测后只替换 URL、名称与学期标签 `termTag` |
| `SchoolSession.kt` | 会话规则纯函数（`SchoolRule`）、轻量 HTTPS GET（`SchoolHttp`，不引入第三方网络库）与退出登录（`SchoolLogout`，只清会话 Cookie） |
| `SchoolPageParser.kt` | 课表/作业页解析（`TableSchoolPageParser`，可带学期标签 `termTag`）、截止时间解析（`DueDates`）、容错 HTML 提取（`MiniHtml`） |
| `WebViewSchoolAdapter.kt` | `SchoolAdapter` 实现：会话检测、课表/作业分别拉取解析，失败映射为 `SyncFailure` |
| `SchoolLoginController.kt` | 登录流程状态机：选校 → WebView 登录 → 1.5 秒反馈过渡 → 导入 → 完成/失败 |
| `SchoolLoginSheet.kt` | 登录弹层 UI：选校、已连接状态、WebView 官方登录页、成功过渡、完成提示、退出登录 |

修改的共享文件（均为预留插槽的最小改动）：

- `ui/AppShell.kt`：新增 `loginSheet` 可选参数（同 D/F 的接入方式）；非演示模式且传入时渲染 C 的登录弹层，并在顶栏保留「学校」常驻入口（登录后仍能做重新登录/退出），否则保留 A 的占位弹层，独立演示工程不受影响。
- `MainActivity.kt`：`HomeDataOwner` 构建应用级适配器与 `data.syncCoordinator`（E 约定同一实例），解析器按 `SchoolCatalog.termTag` 注入学期前缀；引导完成且已选校后调用 `sync.onForeground()`；登录成功回调里 `selectSchool` + `completeOnboarding` + 强制同步；退出登录清会话并 `selectSchool(null)`。
- `AndroidManifest.xml`：新增 `INTERNET` 权限（此前缺失，WebView 与同步均依赖）。

## 与 E 的契约对齐

- `checkSession()` 返回 `VALID/EXPIRED/OFFLINE`：门户页 2xx 且非登录表单为有效；401/403、被重定向回登录页为过期；5xx 按离线上报，不误报"登录过期"。
- 稳定标识（E 交接 §C 的同步输入）：
  - 课程 `externalId` 取页面上的课程代码（如 `高等数学(A01MATH)` → `A01MATH`）。`courses` 的 `(source, externalId)` 是**全局唯一键**，跨学期重修会撞（B 的开放问题 6），因此 `SchoolInfo.termTag`（如 `2026AUT`）会作为前缀注入解析器，课程与作业的课程关联两侧同时加前缀；未配置时保持原样。
  - 任务 `externalId` 取链接 `id=` 参数或 `data-id`。**缺 ID 的任务不再由适配器丢弃**，而是以 `externalId = null` 上交，由 E 计入 `missingIdentity` 并暂缓入库——否则同步记录里的缺失计数永远是 0。
  - 缺课程代码的课程条目不导入并计入 `skippedCourses`；节次无法确定时整条跳过，不默认到第 1 节。均不猜测去重键。
- 周次经 B 的 `WeekText.parse` 转位图后交给 `ScheduleDraft`，不自建口径；学期起始日仅识别页面明示的"学期开始/起始日"日期，解析不出返回 null，由用户在课表页补充，不猜日期。
- 节流、重试、部分成功、墓碑合并全部由 E 的 `SyncCoordinator` 负责，适配器只做拉取与解析；`PARSE_ERROR/OFFLINE/EXPIRED/REMOTE_ERROR` 按 `ModuleResult.Failure` 上报。

## 隐私与安全边界（计划书 §五）

- 账号、密码、验证码只在学校官方登录页（WebView）内输入，应用不经手、不保存；日志不记录任何凭据。
- 会话 Cookie 由 `android.webkit.CookieManager` 持有，仅用于本机同步，不进 Room/DataStore/备份（E 交接 §表结构约定）。
- WebView 只放行 HTTPS 跳转；弹层展示数据接收域名。

## 退出登录与换校

- 入口：顶栏「学校」（`AppShell` 在非演示模式且传入 `loginSheet` 时显示）→ 弹层「已连接：…」→「退出登录」；过期场景直达登录页时，登录页下方也保留退出入口。
- 行为：`SchoolLogout.clear()` 清 `CookieManager` 会话 → `settings.selectSchool(null)` → 登录控制器复位；**不删本地课程任务**，也不动 E 的同步记录。
- 过期入口（`SyncStatus.Expired` → `autoLogin = true`）仍直达已记住学校的登录页，不会被退出入口挡住；用户主动点「学校」进来时先看到连接状态与两个动作。
- 换校：首期目录只有一所学校；换到未登记学校会被 `SyncCoordinator` 的 `SchoolMismatch` 拦下，不混写、不清库。

## 本轮修复（2026-11-13）

| 问题 | 修法 | 位置 |
|---|---|---|
| 缺稳定 ID 的作业被适配器静默丢弃，E 的 `missingIdentity` 恒为 0 | 以 `externalId = null` 上交，由 E 计数并暂缓入库 | `SchoolPageParser.parseTasks` |
| 没有退出登录入口 | 新增 `SchoolLogout`、弹层「退出登录」、顶栏「学校」常驻入口 | `SchoolSession`、`SchoolLoginSheet`、`AppShell` |
| 课程 externalId 无学期前缀，跨学期重修撞 `(source, externalId)` | `SchoolInfo.termTag` 注入 `TableSchoolPageParser(termTag)`，课程与任务关联同时加前缀 | `SchoolCatalog`、`SchoolPageParser`、`MainActivity` |
| 解析不出节次时默认第 1 节（属于猜测） | 整条跳过并计入 `skippedCourses` | `SchoolPageParser` |
| 连接 10s + 读取 30s 可能超出协调器的 30s 超时 | 收敛为 8s + 15s，让阻塞读在超时前返回 | `SchoolRule` |
| 登录完成页承诺“下次同步重试”，实际不会重试 | 文案改为“不会入库，会记入同步记录的缺失计数” | `SchoolLoginSheet` |

## 解析格式与脱敏样例

`app/src/test/resources/school/` 下的 `schedule-sample.html`、`homework-sample.html` 为参考格式的脱敏样例（虚构课程代码与人名）：

- 课表：宽表表格，表头含"星期一…星期日"；单元格条目为 `课程名(代码)` + 周次行 + 地点行 + 教师行（`<br>` 分隔，顺序容错）；行首节次标签支持 `第1-2节`。
- 作业：`<li>` 列表项，链接文本为标题，`id=` 为稳定标识，文本内日期为截止时间（`2026-11-20 23:59` / `2026年11月20日` 等，缺时刻默认 23:59）；标题含"实验"归 LAB_REPORT，其余默认 HOMEWORK。

T05 实测确定学校后，按真实页面调整 `TableSchoolPageParser` 的字段提取规则或替换实现，契约与合并规则不动。

## 登录成功判定

WebView 在 `doUpdateVisitedHistory` 中调用 `SchoolRule.isLoginComplete`：当前地址为 HTTPS、已离开登录页、回到教务门户域名即视为成功，随后走"登录成功 → 读取课表 → 计算周次 → 完成"过渡（约 1.5 秒反馈，计划书 §四），再由 afterLogin 强制同步一次。失败给出原因并可重试；已有本地数据不受影响。

## 本地验证

`JAVA_HOME` 指向 JDK 21 后执行 `:app:testDebugUnitTest :app:assembleDebug`。本轮结果：全仓 **200 项单元测试，0 失败、0 跳过**，其中 C 的模块 **16 项**（解析 7、会话规则 4、登录状态机 4、退出登录 1）；`:app:assembleDebug` 构建通过。真机/模拟器端到端（真实学校登录）待 T05 实测后补验收记录。

## 边界与待办

- T05 第 1 周可行性结论（登录链路、可获取字段、脱敏样例）仍未定版，目录里的学校条目是占位：确定学校后先替换 `SchoolCatalog` 再实测登录。
- 确定学期后需填 `SchoolInfo.termTag`（如 `2026AUT`），否则课程稳定标识仍只有课程代码，跨学期重修会撞 `(source, externalId)` 唯一键。
- `SchoolRule.isLoginComplete` 目前要求成功页与门户**同 host**。若目标学校走跨域 CAS/SSO，需要在实测时补成功标识（特定元素或接口返回），否则登录成功检测不到——这是实测要专门验的一条。
- 真机/模拟器端到端（真实学校登录）与 C 的验收证据（截图、测试 XML、脱敏样例）尚未归档到 `docs/evidence/c-*`。
- 下拉强制刷新手势属 A 的接入点（`sync.synchronize(force = true)` 已可用）；`DemoScenario.Expired` 演示场景保持 A 原有行为，未混入真实链路。
- 多学校切换、账号数据分区按 E 交接待后续约定；首期仅一所学校。
