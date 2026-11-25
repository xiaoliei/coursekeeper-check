# C 成员：学校适配实现说明

更新日期：2026-11-17（含 D 复核修复与其他成员问题清单 C 部分）　｜　范围：基础版 T05（官方登录与数据适配）+ T06 适配器侧
首批学校：汕头大学（统一身份认证 SSO + 正方教务课表 + MYSTU 学习平台作业）

本文说明“怎么实现的”，交接/验收口径见同目录《C-学校登录与数据适配交接》。
**2026-11-15 下午第二轮真机检验**修复 4 个问题（会话过期误判离线、一格多条排课只解析第一条、幻影课程残留、登录弹层滑不到底），详见第 13 节；
**2026-11-16 按 D 复核意见**修复 3 个问题（启动崩溃、同步失败谎报完成、空课表清库），详见第 15 节；
**2026-11-17 按《其他成员待处理问题》**处理 C 的 5 项（CE-01 部分解析误删课程、C-01 取消残留、C-02 调试抓页、CD-01 提交状态、CBE-01 课程标识），详见第 18 节。

---

## 1. 交付范围

| 类别 | 文件 |
|---|---|
| 学校配置 | `school/SchoolCatalog.kt`（地址、`termTag`、明文白名单、两个解析器入口） |
| 会话与网络 | `school/SchoolSession.kt`（`SchoolRule` / `SchoolHttp` / `SchoolLogout`） |
| 课表解析 | `school/StuJwPageParser.kt` |
| 作业解析 | `school/MyStuTaskParser.kt` |
| 通用解析（参考） | `school/SchoolPageParser.kt`（`TableSchoolPageParser` / `DueDates` / `MiniHtml`） |
| 适配器 | `school/WebViewSchoolAdapter.kt`（实现 E 的 `SchoolAdapter`） |
| 登录状态机 | `school/SchoolLoginController.kt` |
| 登录 UI | `school/SchoolLoginSheet.kt`（WebView、两处登录、成功过渡、退出） |
| 接线 | `MainActivity.kt`（应用级单例、`syncCoordinator`、登录弹层） |
| 登录插槽 | `ui/AppShell.kt`（`loginSheet` 参数 + 顶栏「学校」入口，A 的文件） |
| 网络策略 | `AndroidManifest.xml` + `res/xml/network_security_config.xml` |
| 测试 | `app/src/test/java/com/coursekeeper/school/*`、`app/src/test/resources/school/*` |

跨模块改动（需对应成员复核）：

| 文件 | 原因 |
|---|---|
| `course/CourseProjector.kt`、`course/CourseViewModel.kt`（B） | 导入课程 `semesterId = null`，原投影只取绑定学期的课程，导致补录学期后课表仍为空 |
| `data/sync/SyncMerge.kt`、`data/sync/SchoolAdapter.kt`（E） | 同步镜像口径：远端已不存在的同步课程要删除（幻影课程根因，第 13.3 节）；`MergeCounts` 增加 `deleted` 计数；适配器增加 `debugDumpDir` 诊断参数 |
| `course/CourseScreen.kt`、`CourseViewModel.kt`、`CourseContract.kt`、`WeekText.kt`（B） | 课表页改为「今日 / 本周 / 上周 / 下周」页签 + 整周网格（C 的真机反馈落地，第 13.5 节）；`WeekText.parse` 兼容 `([周])` 双层括号 |

---

## 2. 架构与数据流

```
WebView 登录（sso.stu.edu.cn）
   │  教务登录成功 → 同一 WebView 再加载 MYSTU → 两处都回到业务页
   ▼
SchoolLoginController（Feedback 1.5s → Importing）
   │  afterLogin(): selectSchool + completeOnboarding + sync.synchronize(force = true)
   ▼
WebViewSchoolAdapter（实现 E 的 SchoolAdapter）
   ├─ checkSession(): HTTPS GET 门户，看最终 URL 主机 + 响应体
   ├─ fetchCourses(): GET 课表页 → StuJwPageParser
   └─ fetchTasks():  GET MYSTU 概览 → MyStuTaskParser
   ▼
SyncCoordinator（E，已实现）：30 分钟节流、30s 超时、最多重试 2 次、分模块事务、部分成功
   ▼
Repository / Room（E）
   ▼
课表页（B 的投影）、任务页（D）、首页/看板（A/F）
```

约束：适配器**不写库、不存凭据、不管理重试与节流**，只负责“拉页面 + 转结构化数据 + 报错误码”。

---

## 3. 登录实现

### 3.1 链路

| 步骤 | 实现 |
|---|---|
| 打开登录页 | WebView 加载 `SchoolInfo.loginUrl`（`https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F`） |
| 账号/密码/验证码 | 全部在学校官方页面内输入，应用不经手、不保存、不记录 |
| 教务登录成功判定 | `SchoolRule.isLoginComplete`：离开登录路径、回到门户主机（`jw.stu.edu.cn`），HTTPS 或白名单明文均放行 |
| 第二处登录（MYSTU） | 教务成功后，同一 WebView 加载 `taskListUrl`；`SchoolRule.isTaskSourceReady` 判定落在 `my.stu.edu.cn` 非登录页 |
| 成功过渡 | `SchoolLoginController`：`Feedback` 停留 1.5s → `Importing` → `Done`；后台没读完不提前显示成功 |
| 失败 | `Failure(原因)`，可重试；已有本地数据不清空 |
| 退出登录 | `SchoolLogout.clear()` 清 CookieManager + `selectSchool(null)`，不删本地课程任务 |

### 3.2 明文回跳（本校正方的特殊点）

SSO 的 `service` 参数是 `http://jw.stu.edu.cn/`，登录成功会被带回**明文**地址。Android 9+ 默认禁止明文，否则 WebView 白屏、登录永远检测不到成功。两层放行：

1. 代码层：`SchoolInfo.allowHttpHosts = setOf("jw.stu.edu.cn")`；WebView `shouldOverrideUrlLoading` 只对该域名放行 http；`isLoginComplete` 同样接受该域名的 http。
2. 系统层：`res/xml/network_security_config.xml` 仅对 `jw.stu.edu.cn` 开启 `cleartextTrafficPermitted`，其余域名仍强制 HTTPS。

### 3.3 登录入口

- `AppShell` 新增 `loginSheet` 插槽（非演示模式渲染 C 的弹层），顶栏保留「学校」常驻入口（登录后仍可重新登录/退出）。
- 过期场景：A 的 `StatusBanner`「重新登录」→ 弹层 `autoLogin = true` → 直接打开已记住学校的登录页。
- 演示工程不传 `loginSheet` 时仍用 A 的占位弹层，互不影响。

---

## 4. 同步适配（E 的契约）

```kotlin
interface SchoolAdapter {
    val schoolId: String
    suspend fun checkSession(): SessionState
    suspend fun fetchCourses(): ModuleResult<ImportedCourse>
    suspend fun fetchTasks(): ModuleResult<ImportedTask>
}
```

### 4.1 会话检测（廉价探测）

- 只 GET 门户页，不拉业务数据（节流窗口内协调器也会调用）。
- `SchoolHttp` 返回 `finalUrl`：被 302 到 SSO 登录页时主机不再是门户 → 直接 `EXPIRED`；否则按状态码/登录表单特征分类（401/403 → EXPIRED，5xx → OFFLINE）。
- 超时：连接 8s、读取 15s，留在协调器 30s `withTimeout` 以内（阻塞读不响应协程取消）。

### 4.2 错误映射

| 情况 | 返回 |
|---|---|
| 401/403、跳登录页 | `Failure(EXPIRED)` |
| 非 2xx | `Failure(REMOTE_ERROR)` |
| IOException / 超时 | `Failure(OFFLINE, retryable = true)` |
| 页面结构不对、解析异常 | `Failure(PARSE_ERROR)` |
| 解析结果“0 条 + 有警告” | 视为失败（防止把“地址失效/没登录”伪装成“同步成功、0 条”） |

重试、节流、部分成功、事务合并全部由 E 的 `SyncCoordinator` 负责。

---

## 5. 课表解析（正方教务 `xskb_list`）

页面：`https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do`「学期理论课表」。两张表配合：

| 表 | 内容 | 用途 |
|---|---|---|
| `table#dataTables2`「有课表课程」 | 上课班级、课程编号、课程名称、授课教师 | 网格里没有课程编号，用单元格“班级：202611595”反查 `CST3254A` |
| `table#timetable` | 表头星期一…星期日；行首「第一大节 (01,02小节)」… | 位置、时间 |

单元格结构：每个条目一个 `div.kbcontent`，`<br>` 分行：

```
课程名 / 教师 / 3-18(周)[03-04节] / 【东海岸校区-E栋教学楼】 / 东海岸-E教133-阶梯 / 通知单编号 / 班级：202611595
```

解析规则：

1. **课程标识**：课程编号（含字母，纯数字的班级号不会被误认）；`termTag` 非空时加前缀（`2026AUT-CST3254A`）。
2. **周次**：`3-18(周)`、`4,6,8,10,12,14,16,18(周)`、`15(周)` → B 的 `WeekText.parse` → 位图，**不自建解析**。
3. **节次**：条目内 `[03-04节]` 优先（同一门课可跨多个大节），缺失才用行首大节标签；都没有则跳过并计数，不默认第 1 节。
4. **地点**：`【校区】` 与教室是两行，只取教室。
5. **去重**：跨大节的课在多行重复出现，按“天 + 起止节 + 周次 + 地点”去重；同一槽位的合讲条目只保留一条，但教师合并为 `张三/李四`，不丢第二位教师（B《课程与周次规则 v0.3》§8 交由 C 决定，2026-11-18 起按合并处理）。
6. **教师名含“周”**：周次行用“数字开头 + 周”正则识别，不会被“周艳红”误判。
7. **学期起始日**：页面不提供 → 返回 null，由用户在课表页“学期设置”补录，不猜日期。
8. **一格多条排课**（2026-11-15 真机发现）：同一 `div.kbcontent` 里用一整行短横线分隔多条排课（大学物理实验一格 3 段周次、潮汕非遗一格 8 段），`entryGroups()` 按分隔行拆开逐条解析；不拆就只会解析每格第一条，后面的周次全部丢失（这就是“下周该上的课不显示”的根因之一）。
9. **双层括号周次**：导出页存在 `9,11-13([周])[06-07节]` 写法，`WEEKS_LINE` 正则兼容 `([周])`，`WeekText.parse` 剥离 `[ ]`。
10. **完整性守卫**（CE-01，2026-11-17）：只要有条目因缺编号/周次/节次被跳过（`skippedCourses > 0`），整份快照按 `PARSE_ERROR` 上报、保留本地缓存，不进入镜像合并。理由见第 18.1 节：镜像合并会删除“这份快照里没有”的课程，部分解析成功等同于误删。

实测（修复后）：真机抓取的真实页面解析出 **10 门课 / 24 条排课**（修复前 14 条），0 跳过、0 警告。

---

## 6. 作业解析（MYSTU / Moodle）

页面：`https://my.stu.edu.cn/courses/campus/my/`「课程概览」。结构：

```
div.coursebox#course-38486
  div.assignment.overview
    div.name  → <a href=".../mod/assignment/view.php?id=307183">提交-实验-01-截止时间 2026.11.15 23：59</a>
    div.info  → 截止时间: 2026年11月15日 星期日 23:59
    div.details → 未提交/已提交（暂不导入）
```

规则：

1. **稳定 ID**：`view.php?id=`（如 307183）；缺 ID 的条目以 `externalId = null` 上交，E 计入 `missingIdentity`。
2. **标题**：去掉尾部“-截止时间 …”，只留任务名。
3. **截止时间**：取 `div.info`；`DueDates` 支持“日期 + 星期X + 时刻”，不会把 14:32 退化成默认 23:59。
4. **类型**：含“实验/报告”→`LAB_REPORT`，社团→`CLUB`，实习/兼职→`WORK`，其余 `HOMEWORK`。
5. **课程关联**：`courseExternalId` 仍留空——MYSTU 课程 ID 与教务课程编号是两套体系，不能直接当已解析关联。但会把 `sourceCourseId`（`course-<id>`）与 `sourceCourseName` 作为来源信息透传，供 B/E 约定映射后消费（CBE-01）。
6. **提交状态**：解析 `div.details` 并归一为 `schoolSubmission`（`SUBMITTED`/`NOT_SUBMITTED`/`DRAFT`/`REOPENED`/`UNKNOWN`）透传，**不写本地完成状态**；“已提交是否算完成、是否从待办移出”由 D/E 约定（CD-01）。

实测：真实保存页解析出 **8 条作业**，0 缺 ID，截止时间与页面一致。

---

## 7. 稳定标识与学期

| 对象 | 规则 | 说明 |
|---|---|---|
| 课程 | 课程编号（可选学期前缀） | `(source, externalId)` 全局唯一；不填 `termTag` 时跨学期重修会撞键（B 的开放问题 6） |
| 作业 | `view.php?id=` | 平台内稳定 |
| 学期 | 用户手填起始日 + 周数 | 页面不提供，不猜 |
| 课程↔任务 | 暂不关联 | 见上 |

---

## 8. 配置（SchoolCatalog）

```kotlin
SchoolInfo(
    id = "stu", name = "汕头大学",
    loginUrl = "https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F",
    portalUrl = "https://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx",
    scheduleUrl = "https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do",
    taskListUrl = "https://my.stu.edu.cn/courses/campus/my/",
    termTag = null,                       // 确定学期后填 2026AUT 之类
    allowHttpHosts = setOf("jw.stu.edu.cn"),
)
```

`taskListUrl = null` 时适配器让作业模块报 `PARSE_ERROR`，整体呈现部分成功；不会用空结果冒充成功。

---

## 9. 隐私与安全

- 密码/验证码只在学校官方页面输入，应用不经手、不保存、不写日志。
- Cookie 由 `CookieManager` 持有，仅本机同步用；不进 Room/DataStore/备份。
- 日志只记枚举错误码，不记响应正文与凭据。
- 明文白名单是“域名级”的，不是全局放开 HTTP。
- 真实页面样例（含个人课程信息）只在本机校验，加入 `.gitignore`，不进仓库；仓库里是脱敏 fixture。

---

## 10. 测试与验证

| 项 | 结果 |
|---|---|
| 全仓单元测试 | **244 项 / 0 失败 / 0 跳过**（分支口径；合并 main 后 **269 项**）。C 共 **44 项**：通用解析 7、汕头课表 8、MYSTU 作业 3、会话规则 7、登录状态机 4、退出登录 1、适配器单例 3、同步失败文案 8、关闭复位 3 |
| 构建 | `:app:assembleDebug` 通过 |
| 课表离线校验 | 真实抓取页：10 门课 / 24 条排课、0 跳过、0 警告 |
| 作业离线校验 | 真实保存页：8 条、0 缺 ID |
| 真机第一轮（2026-11-15 上午） | SSO + MYSTU 两处登录成功；课表 inserted 9、作业 inserted 8；设学期后课表与教务一致 |
| 真机第二轮（2026-11-15 下午） | 覆盖安装复验：冲突横幅消失、残留课程清零；第 10 周 9 条 / 第 11 周 11 条排课，潮汕非遗 9,11-13 周段正常出现；登录弹层整页可见 |
| 单元测试（D 复核后） | 分支口径 **244 项**；合并 main 后 **269 项**（均为 0 失败 / 0 跳过）。新增：适配器进程级单例、同步失败提示文案 |

复现命令：

```powershell
cd C:\Users\yiluo\Desktop\coursekeeper
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb uninstall com.coursekeeper
.\gradlew.bat :app:installDebug
```

首次使用：顶栏「学校」→ 登录 → 课表页「学期设置」填开学日与周数（页面不提供，必须手填）。

---

## 11. 已知边界与待办

| 项 | 现状 | 归属 |
|---|---|---|
| 首页课程区仍显示“暂无课程信息/暂无数据” | A 的 `RepositoryHomeGateway` 未接 B 的 `HomeCourseProjection`（仍是 `Unavailable` 占位） | A |
| 作业→课程关联 | 首版留空，显示“未关联课程”；C 已透传 `sourceCourseId`/`sourceCourseName`（CBE-01），映射规则待定 | 产品 + E/B 定规则 |
| 学校提交状态 | C 已解析并透传 `schoolSubmission`（CD-01），E 尚未落库、D 尚未按约定展示 | C/D/E 约定口径 |
| `termTag` | 未填，跨学期重修有撞键风险 | 确定学期后补 |
| 验证码/SSO 细节 | 本次登录未触发验证码；**过期自动重登已真机验证**（会话有效时“重新登录”自动跳回门户并强制同步，无需输密码）；单点互踢仍未验证 | C 真机补测 |
| MYSTU 大数据量 | 作业很多时概览是否只渲染部分条目 | 真机补测 |
| 诊断抓页开关 | 自 2026-11-17 起**只在可调试构建**启用（`debugDumpDirFor` 读 `FLAG_DEBUGGABLE`），release 包恒为 null（C-02） | C 已处理 |
| 真实数据卫生 | 真机截图/抓取页含真实学号与课表（工程 `.shots/` 目录），**只留本机**，已加入 `.gitignore`；仓库里只有脱敏 fixture | C |
| 同设备多账号 | 一台设备切换多个学校账号会互踢会话并可能触发学校 WAF 风控（浏览器访问 stu.edu.cn 出现“网络或安全原因”拦截页，等待 10–30 分钟自行解除）；建议一人一设备一账号，应用内 30 分钟同步节流已是低频访问 | 全员约定 |

---

## 12. 对其他模块的影响

| 成员 | 影响 | 需要做什么 |
|---|---|---|
| **B** | 为让“未绑定学期的导入课程”显示，改了 `CourseProjector` / `CourseViewModel` | 复核改动；确认长期是否改为“学期创建时回填 `semesterId`” |
| **A** | `AppShell` 加了 `loginSheet` 插槽与顶栏「学校」入口 | 复核 UI；首页课程区接入 `HomeCourseProjection` 并改两条旧断言 |
| **D** | 任务页新增 8 条同步作业，`courseExternalId = null` | 确认“未关联课程”展示符合预期；按轮转复核 C 的代码 |
| **E** | 同步数据沿用既有契约；`missingIdentity` 语义未变 | 确认导入课程学期绑定的长期方案 |
| **F** | 统计随课程投影变化（已计入导入课程） | 回归统计口径，确认 CI 通过 |

---

## 13. 第二轮真机检验与修复（2026-11-15 下午）

覆盖安装新版（`adb install -r`，保留旧数据）后按用户反馈逐项复验。**旧版本装着旧数据**，本轮先修展示与解析，再通过「学校 → 重新登录」强制同步验证数据链路。

### 13.1 会话过期被误报成“当前离线”（同步一直失败的真凶）

- **现象**：登录过期后首页横幅显示“当前离线”，永远等不到同步，也出不来“重新登录”。
- **根因**（两个叠加）：
  1. `HttpURLConnection` 的自动重定向**不跨协议**：过期请求被教务 302 到明文 `http://jw.stu.edu.cn/`（https→http），跟随停在这一跳，`finalUrl` 主机名没变，走不到“主机名变成 SSO → 过期”分支；
  2. 跟到底也只是 127 字节的 JS 壳页 `window.location.href='https://sso.stu.edu.cn/login…'`——无表单、无密码框，`looksLikeLoginPage` 认不出；最终 `classify(302)` 落进 else → `OFFLINE`。
- **修法**：`SchoolHttp.get` 改为**手动跟随重定向**（最多 6 跳、任意协议、Cookie 只发往与首请求同主机的跳）；`looksLikeLoginPage` 增加 JS 跳转壳页识别；重定向超出上限按 508 返回（按远端错误处理，不误判会话状态）。
- **真机验证**：横幅正确变为「登录已过期 · 现有数据已保留 → 重新登录」。

### 13.2 解析器一格多条排课只取第一条（“下周该上的课不显示”的根因）

- **现象**：本地课表里大学物理实验、潮汕非遗整门缺失；第 11 周看不到潮汕非遗（9,11-13 周）。
- **根因**：正方页面把同一单元格的多条排课塞进**同一个 `div.kbcontent`**，用一整行 `---------------------` 分隔（大学物理实验一格 3 段周次：4-7 / 8-16 / 17-18；潮汕非遗一格 8 段：2、3、6、7-8、9、9,11-13、14、15）。旧解析按“一格 = 一条”，只解析了每格第一条。
- **修法**：`entryGroups()` 按分隔行拆开逐条解析；同时兼容导出页 `([周])` 双层括号周次（`WEEKS_LINE` 正则 + `WeekText.parse` 剥离 `[ ]`）。
- **效果**：同一抓取页 14 条 → **24 条排课**（物理实验 4 段、潮汕非遗 11 段全部入库）。

### 13.3 幻影课程与假冲突（“大学生人生哲学八讲”问题）

- **现象**：课表显示与「大学生人生哲学八讲」的时间冲突，但该课不在用户教务课表里（对照官方个人课表 xls 确认）。
- **根因**：`SyncMerge.mergeCourses` 只增改不删——此前导入过的其他课表残留课程（大学生人生哲学八讲、软件质量与测试、计算方法、红动潮汕）在换账号/新课表同步后永久残留，并与新课表产生假冲突。
- **修法**（E 的 `SyncMerge`，需 E 复核）：合并后删除“远端导入集合中已不存在”的 SYNC 课程；`manualDirty`（用户改过）保留；**不写墓碑**（学校重新排课时下次同步照常导入）；`MergeCounts` 增加 `deleted` 计数（未入 SyncRecord 表，无需迁移）。
- **真机验证**：强制同步后三门残留课清零，冲突横幅消失。

### 13.4 登录弹层滑不到底（登录按钮被挡）

- **现象**：WebView 固定 380dp，网页自身可滚动，竖向手势全被 WebView 吞掉，外层弹层滑不到底，登录按钮在页面折叠区内。
- **修法**：WebView 高度按页面 `scrollHeight` 自适应（380–600dp，加载中/加载完各测一次并延迟复测）。**注意 `scrollHeight` 是 CSS 像素，与 dp 同标度，除以屏幕密度会被钳到下限**——这是第一版实现的错误。
- **真机验证**：整页（校徽 → 登录按钮 → 版权页脚）完整可见，无需任何滑动。

### 13.5 顺带落地：课表页页签与整周网格（B 的模块，C 提交需 B 复核）

真机反馈一并落地：课表页顶部改为「今日 / 本周 / 上周 / 下周」页签；今日保留原列表样式；其余三档为整周一屏网格（节次时间轴 + 七天列 + 按课程稳定着色的卡片，跨节次撑高、重叠排课并排显示 + 冲突红点、今天列高亮、`‹ ›` 可继续翻周）。周过滤沿用 B 的 `ScheduleWeeks.contains` 位图口径，未改计算规则。

### 13.6 真机验证记录（数据对照）

| 检查 | 结果 |
|---|---|
| 强制同步入口 | 「学校 → 重新登录」：会话有效时 SSO 自动跳回门户，无需输密码，`synchronize(force = true)` 全量重拉（普通前台同步受 30 分钟节流，本轮就是被它拦住才改走强制入口） |
| 幻影课程 | 大学生人生哲学八讲 / 软件质量与测试 / 计算方法 / 红动潮汕 全部清除；冲突横幅消失 |
| 第 10 周 | 9 条排课：羽毛球、AI时代的经典音乐聆听、应用密码学、编译原理、习概、软件工程、操作系统×2、WEB应用技术（+ 物理实验 4-7 周段），与官方个人课表 xls 一致 |
| 第 11 周 | 11 条排课：上述 3-18 周课程 + 物理实验 8-16 周段 + **潮汕非遗 9,11-13 周段（周三 6-10 节 外出实践）**——用户反馈的“下周不显示”就此闭环 |
| 作业 | MYSTU 8 条入库（首页“近期截止”可见） |
| 测试 | 全仓 240 项 / 0 失败 / 0 跳过；新增会话壳页、多段排课、双层括号周次、页签切换等用例 |

## 14. 变更清单（相对 origin/main）

分支 `feat/school-adapter`，全部已提交（共 9 个提交，2026-11-15）：

| 提交 | 内容 |
|---|---|
| `86ba250` feat(school) | 学校适配器、WebView 官方登录（T05 第一轮） |
| `2f2b085` test(school) | 解析、会话规则、登录状态机与退出登录用例 |
| `01c46b3` docs(school) | C 交接文档与 README 接入指引 |
| `a282ff9` feat(school) | 会话过期壳页识别、一格多段排课解析、登录弹层自适应高度（第二轮） |
| `ca35cb5` test(school) | 会话壳页、多段排课、双层括号周次用例 |
| `006ae74` fix(data-sync) | 同步镜像口径——清理远端已不存在的同步课程（E 复核） |
| `d7f1237` feat(course) | 课表页今日/本周/上周/下周页签与整周网格（B 复核） |
| `ca6f9d1` docs(school) | 第二轮真机检验记录与 T05 证据；`.shots/` 入 gitignore |
| 待推 | 补交课表/作业脱敏样例文件（解析测试的 classpath 依赖） |

跨模块需复核：E 复核 `data/sync/{SyncMerge,SchoolAdapter}.kt`；B 复核 `course/{CourseScreen,CourseViewModel,CourseContract,WeekText}.kt`。
**不入库**：`.shots/`（真机截图与抓取页，含真实学号/课表）、`local.properties`、`build/`。

## 15. D 复核修复（2026-11-16）

D 对 T05 的复核（`docs/D-复核-C学校适配-T05.md`）提出 1 必修 + 2 建议，全部核实属实并修复：

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1（必修） | **启动崩溃**：返回键退出后再打开直接闪退 | `HomeDataOwner`（ViewModel）随 Activity 重建，每次 `new WebViewSchoolAdapter(...)` 传给 `syncCoordinator`；而应用进程缓存期间 `CourseKeeperData` 单例里 `boundAdapter` 还是旧实例，撞上 `require(boundAdapter === adapter)` | 新增 `school/SchoolAdapterHolder`（进程级单例），`MainActivity` 改为 `SchoolAdapterHolder.get(application)`；Robolectric 用例断言重复获取同一实例 |
| 2 | **同步失败谎报「导入完成」**，与首页过期/错误横幅矛盾 | `afterLogin` 丢弃 `synchronize(force = true)` 的返回结果，模块失败不抛错，状态机照常进入 `Done` | `afterLogin` 检查各模块状态（OFFLINE/EXPIRED/ERROR），有失败即抛 `syncFailureText` 人话（如“同步未完成：课表：网络不可用”），状态机进入 `Failure` 并保留重试入口；文案有单测 |
| 3 | **空课表清空本地同步课程** | `fetchCourses` 只在“0 门课且有警告”时报失败；学校返回结构正常但空网格的页面（0 门 0 警告）会放行，交给镜像合并把本地课程全删 | 空结果**一律**报 `Failure(PARSE_ERROR)`，不进合并。语义留 E 拍板：若确属“无课学期”（交换/休学）需放行时再改守卫 |

测试：分支口径 244 项；合并 main 后 269 项（均为 0 失败 / 0 跳过）。真机崩溃路径（返回键退出 → 重进）复验见第 16 节：已闭环。

## 16. 第三轮真机检验（2026-11-17 凌晨）：崩溃路径闭环 + 学校改课表的实证

覆盖安装 `app-debug.apk`（合并提交 `5fc2522` 构建，含 D 复核三项修复；该提交是 `51c8a19` 经 rebase 入 main 后的等价版本）到 Redmi 24117RK2CC（`adb install -r`，保留数据）。

### 16.1 崩溃路径复验（D 复核第 1 条闭环）

| 步骤 | 结果 |
|---|---|
| 覆盖安装 | `Streamed Install: Success`，数据保留（同步记录、课表均在） |
| 冷启动 → 首页 | 正常，无 FATAL |
| 返回键退出 → 等 2s → 重启 | 正常进入，无闪退（修复前必崩路径） |
| 第二轮 返回退出 → 重启 → 进课表页 | 正常，网格渲染完整 |
| `logcat -d -s AndroidRuntime:E` | 全程 0 条 FATAL EXCEPTION |

### 16.2 学校于 11-16 白天改了课表（同步链路实证）

复验时发现周条数与 11-15 记录不一致，追查确认为**学校实时课表变更**，不是程序问题：

| 课程 | 11-15 抓页（179368 B） | 11-16 抓页（151818 B） |
|---|---|---|
| 潮汕非遗数字化传承服务实践 / 大学物理实验（1） / AI时代的经典音乐聆听 / WEB应用技术 / 习概 | 有 | 消失 |
| 红动潮汕 / 计算方法 / 软件质量与测试 / **大学生人生哲学八讲** | 无 | 新增 |
| 编译原理 | 周二 3-5 节 E229 | 改周二 3-4 + 周三双周 3-4 节 E109 |

用项目解析器离线复跑两天抓页（0 警告 0 跳过），再与设备 Room 库（`run-as` 拉库解码位图）逐位对照：

- 库内容 = 11-16 18:55 最后一次成功导入（`sync_records`：COURSES inserted=9），位图与解析器输出一致；
- 第 10 周 11 条 / 第 11 周 10 条，界面显示与库、与页面三方一致（编译原理周三段为双周，第 11 周是奇数周所以不在——手数时易错）；
- **镜像清理（E 复核修复）实证生效**：远端已删除的 5 门旧课自动从本地清掉，手动任务 protectedCount=7 未受影响；
- 今天启动的重试按 OFFLINE 记录，首页横幅如实显示「当前离线 · 已有数据可继续使用」，与 D 复核第 2 条修复后的语义一致。

### 16.3 对团队的两点提示

1. **「大学生人生哲学八讲」现在是学校页面上的真实课程**（周四 6-7 节 3-18 周）：当初 T05 验收时的“幻影课程”经学校改课后已坐实。镜像口径下它正确入库，无需处理；若组内同学还在引用 11-15 的旧课表截图，注意已过时。
2. 学期中学校侧改课表是常态：同步 + 镜像清理会自动跟随，**手动添加/手动改过的课程与任务不受影响**（manualDirty 与 protectedCount 保护，已在真实数据上验证）。

## 17. D 二轮复核：文档订正与两道防线（2026-11-17）

D 复核了 1 必修 + 2 建议的修复后，确认三项均真修好（单例不变式唯一构造入口、失败传播与控制器 Failure 贯通、空导入守卫不锁死用户），并指出文档不实与两处测试缺口。订正与补强如下。

### 17.1 文档订正（D 指出 5 处，全部属实）

| # | 原文 | 订正 |
|---|---|---|
| 1 | “C 32 项”，同句枚举合计 34 | 枚举本身没错，是总数写错：现为 **C 44 项**（本轮补测 +10 后的实得）；测试数同时注明**分支口径 244 / 合并 main 后 269 / 本轮补测后 279** |
| 2 | verification.md 用“启动重试横幅”证明登录假完成修复 | 该修复只在登录弹层；首页横幅没有“导入完成”字样，证明不了。改为引用 D 的真机结论（过渡阶段断网 → 弹层显示“同步未完成：课表/作业：网络不可用” + 重试，恢复网络后自愈） |
| 3 | “空课表守卫在真实数据上验证” | 不实：11-16 页面解析出 9 门课，空分支从未进入。真机验的是**镜像清理**；空守卫目前只有单测覆盖 |
| 4 | 引用 `51c8a19` | 该提交 rebase 后不在 main 历史，改引等价合并提交 `5fc2522` |
| 5 | “镜象”笔误、文档末尾多余空行 | 已改“镜像”、去空行 |

### 17.2 补的两道防线（D 建议，非阻塞）

| # | 缺口 | 处理 |
|---|---|---|
| 1 | 两处修复缺真正回归测试：holder 用例只测两次 `get` 相同；afterLogin 的失败过滤是内联 lambda | ① `SchoolAdapterHolderTest` 增两组：ViewModel 重建后经 Holder 再绑定不触发 `require`；自建实例仍被数据层拒绝（证明 Holder 是唯一正确入口）。② 失败判定抽成纯函数 `afterLoginFailureMessage(SyncRunResult)`，`SyncFailureTextTest` 覆盖全成功/单模块失败/部分失败/InProgress/SchoolMismatch 五条分支 |
| 2 | Failure 页用返回键/点外部关闭后状态机停在 Failure，下次打开先看到旧错误 | 抽出 `resetsOnDismiss(step)`：**Done 与 Failure 都复位**，中间步骤保留；新增 `SchoolLoginSheetRuleTest` 覆盖 6 种状态（含 `school = null` 的加载失败） |

### 17.3 SchoolMismatch 提示（D 建议③，已改文案；重置入口留 E）

`SchoolLogout.clear()` 只清 Cookie，不清 `sync_records.schoolId`，所以“退出登录后重新连接”解除不了不一致，重新登录仍会 mismatch。当前单校不可达，把提示改为可执行口径：

> 本机同步记录属于其他学校。当前版本仅支持汕头大学，请清除本机应用数据后重新连接。

真正的“清除学校同步记录”入口涉及 E 的数据层（`sync_records` / 镜像课程），未擅自新增；多校支持落地前由 E 决定是加重置入口还是在 `syncCoordinator` 绑定时清理。

### 17.4 本轮验证范围（如实记录）

- 单测：基于最新 `origin/main`（已含 269 项）**279 项 / 0 失败 / 0 跳过**（本轮 +10：holder 2、失败判定 5、关闭复位 3）；
- 真机：**本轮未重跑**。崩溃路径与登录失败路径的真机证据分别来自第 16 节（本人）与 D 的本轮复核（过渡阶段断网 → 弹层提示 + 重试自愈）；关闭复位与 mismatch 文案只有代码与单测，真机待下一轮合并后顺带冒烟。

## 18. 其他成员待处理问题清单（2026-11-17）：C 部分处理

依据 `2026-11-17-其他成员待处理问题.md`（核对版本 main `6605351`），处理 C 负责的 5 项；2026-11-18 又按 main `5cb46e0`（E 已实现 CE-01）复核并对齐。本轮只改代码、测试与文档，**未推送 GitHub、未跑真机**。

### 18.1 CE-01 部分解析失败可能误删已有课程（优先修复，C＋E）—— 已由 E 在合并层修复，C 侧对齐

**根因**：解析器遇到缺课程编号 / 周次 / 节次的条目只做“跳过 + 警告”；旧适配器只拦截“解析出 0 门课”，剩余非空时仍返回成功，警告没有传给合并层。E 的 `mergeCourses` 按镜像口径删除“本快照里没有”的同步课程，于是某门课只是没解析出来，也会被当成学校删课整门清掉。

**最终实现（main `75ce17a`，C 撤掉自己的重复守卫）**：

- 契约：`ModuleResult.Success(records, completeSnapshot = false)` 显式授权镜像清理（E 的 `data/sync/SchoolAdapter.kt`）；
- 适配器：`ScheduleParse.toCourseSyncResult()`（`school/WebViewSchoolAdapter.kt`）——空表、`skippedCourses != 0` 或 `warnings` 非空一律 `Failure(PARSE_ERROR)`，只有干净的非空快照才带 `completeSnapshot = true`；
- 合并层：只有完整快照且 `semesterId` 唯一时才清理该学期缺席的同步课程，并校验请求前基线，避免迟到结果删掉后来同步的课；
- C 的解析器补记“周次部分无法解析”警告（`StuJwPageParser`），让“能解析但缺周”的页面也不会被当成完整快照。

**验证**：E 的 `CourseSyncResultTest` 3 例覆盖空表 / 跳过 / 警告 / 脱敏样例中的越界周次；合并层用例在 `SyncCoordinatorTest`。

**仍未完成（E）**：“真实无课学期 / 成功空结果 / 远端真删课”的语义仍需 E 拍板（当前一律失败、保留本地）。C 侧的 `requireCompleteSchedule` 与 `ScheduleCompletenessTest` 已在对齐时删除，避免两套守卫口径分叉。

### 18.2 C-01 导入协程取消后残留“正在读取”状态

**根因**：导入由弹层的 `rememberCoroutineScope` 启动，控制器存在 ViewModel 中；协程被取消（旋转、退出）时只重新抛出，状态停在 `Importing`，下次打开仍显示已停止的任务。

**修复**：`SchoolLoginController.onLoginSuccess` 捕获 `CancellationException` 后，若当前仍是 `Feedback` / `Importing`，先复位到 `SelectSchool` 再重新抛出；若用户已主动 `backToSelect()`（关闭按钮），保持其选择不覆盖。

**测试**：`SchoolLoginControllerTest` 新增 3 例——反馈阶段取消、导入阶段取消都回到选校页；主动关闭后取消不会把状态改回旧值。

**仍待**：清单要求的真机复验（反馈/导入两个阶段分别验证旋转、返回、关闭再打开）。本轮未上真机。

### 18.3 C-02 原始学校页面调试保存未限制构建类型

**根因**：`SchoolAdapterHolder` 无条件传入 `sync-debug` 目录，release 包也会把含学号、课表、作业的原始页面落盘。

**修复**：`SchoolAdapterHolder` 改为 `debugDumpDirFor(application)`，读取 `ApplicationInfo.FLAG_DEBUGGABLE`；不可调试构建恒为 null。落盘路径由纯函数 `debugDumpDir(base, debuggable)` 决定，便于单测覆盖。

**测试**：`SchoolAdapterHolderTest` 新增 2 例——release 形态返回 null、debug 形态返回 `sync-debug`；Robolectric 下切换 `FLAG_DEBUGGABLE` 验证 `debugDumpDirFor` 跟随应用标记。

**仍待**：清单建议打 Release 包做落盘复验（正式构建同步后不再生成 HTML）。本轮只有纯函数与 Robolectric 断言。

### 18.4 CD-01 学校已提交作业仍显示逾期（C 牵头，D／E 协作）—— C 侧已交数据

**C 侧处理**：`MyStuTaskParser` 解析 `div.details`，归一为 `schoolSubmission`：

| 页面文本 | 归一值 |
|---|---|
| 已提交 | `SUBMITTED` |
| 还未提交 / 未提交 | `NOT_SUBMITTED` |
| 草稿 | `DRAFT` |
| 需要重新提交 | `REOPENED` |
| 其他 | `UNKNOWN` |

通过 `ImportedTask.schoolSubmission` 透传，**不写本地完成状态、不改 D 的逾期判定**。脱敏样例 `mystu-dashboard.html` 同时含“已提交 / 还未提交”，测试断言两种状态与缺 ID 条目。

**待 D/E 定**：学校“已提交”在任务页如何展示、是否从本地待办移出、与手动完成／恢复待办的关系；如需持久化，E 加同步字段与合并规则（必须保护用户手动完成）。另按清单，R-01（组员无需本人提交的作业）不列为缺陷，C 核对 CD-01 时一并保留回归样例。

### 18.5 CBE-01 导入作业显示“未关联课程”（C 牵头，B／E 协作）—— C 侧已交数据

**C 侧处理**：透传 `sourceCourseId`（MYSTU `course-<id>`）与 `sourceCourseName`（如 `[2026秋] 应用密码学 方若宇`）；`courseExternalId` 仍为 null，不按名称模糊匹配、不把数字课程 ID 冒充教务课程编号。样例覆盖 `course-1001` / `course-1002`。

**契约变更**：`data/sync/SchoolAdapter.kt` 的 `ImportedTask` 新增 3 个可空字段（`sourceCourseId` / `sourceCourseName` / `schoolSubmission`，默认 null）。E 的 `mergeTasks` 目前不消费、行为不变；需要在 E 侧落库时再补字段与合并规则。

**待 B/E 定**：教务课程编号与 MYSTU 课程 ID 的映射方式（含同名不同课、用户手动修改关联的保护）；映射建立后由 D 回归任务页显示。

### 18.6 验证范围与交接

| 项 | 结果 |
|---|---|
| 基线 | main `5cb46e0`（2026-11-18 复核；E 已合入 CE-01 与重试修复） |
| 全仓单元测试 | **307 项 / 0 失败 / 0 跳过**（基线 main `5cb46e0`；C 的 `school` 包含 E 的 `CourseSyncResultTest` 共 56 项） |
| 构建 | `:app:assembleDebug` 通过 |
| 真机 | **本轮未跑**：C-01 旋转复验、C-02 Release 落盘复验均按清单列为待做 |
| 未处理 | 无课学期语义（E）；CD-01/CBE-01 的展示与落库口径（D/E、B/E）；R-01 作为正常行为回归保留 |

### 18.7 对照 main 最新改动与 B《课程与周次规则 v0.3》的再复核（2026-11-18）

拉取 main `5cb46e0` 后发现 E 已先行实现 CE-01，C 侧据此调整，并按 v0.3 补了一处实现细节：

| 事项 | 复核结论 | 处理 |
|---|---|---|
| CE-01 | E 在 `75ce17a` 引入 `ModuleResult.Success(completeSnapshot)`：适配器空表/跳过/警告一律 `PARSE_ERROR`，合并层只在完整快照且 `semesterId` 唯一时清理 | **撤掉 C 自己的 `requireCompleteSchedule` 与 `ScheduleCompletenessTest`**，避免两套守卫口径分叉；文档改述 E 的实现 |
| 周次部分缺失 | E 在 `StuJwPageParser` 补了“周次部分无法解析”警告，使“能解析但缺周”的页面不被当作完整快照 | 保留并纳入 C 的解析规则说明 |
| v0.3 §8 合讲教师 | 原文“教师只保留第一条…若要保全应合并成 `张三/李四`（由 C 决定）” | **C 决定保全**：`mergeTeachers()` 去重后按 `/` 合并；新增用例 `coTaughtEntriesInSameSlotKeepBothTeacherNames` |
| v0.3 §9 C ① 字段可空 | location/teacher 缺失不产生警告、不跳过条目 | 已满足，无需改动 |
| v0.3 §9 C ② 周次规范化 | 解析统一走 B 的 `WeekText.parse`，`ScheduleDraft` 默认生成规范化文本 | 已满足 |
| v0.3 §9 C ③ 课程编号 | `externalId` 取课程编号；`termTag` 仍为 null | 已满足；`termTag` 属 C/E 待定项——v0.3 §10.1 建议首个学期导入前定值，当前已有真实数据，**本轮不擅自启用**（启用后 externalId 会变，需 E 配迁移） |
| v0.3 §9 C ④ 学期起始日 | 页面读不到返回 null，不猜 | 已满足 |
| 手动刷新重试（`5fc59b4`） | 协调器层修复，不涉及适配器 | 无 C 改动 |

## 19. D《作业多状态问题分析与分工》（2026-11-21）：P1 双模块解析与并集收敛

依据 `docs/D-作业多状态问题分析与分工.md`（P1 必修 + 兜底"随 P1 顺带"；P2 三项为评估后回结论）。执行前已 `git pull` 到 main `6559f01`。

### 19.1 P1 解析器双模块支持（必修）

**根因**（D 量化：漏抓 4/6）——MYSTU 首页同时存在老 `assignment`（作业 2.2）与新 `assign` 两套 Moodle 模块，解析器只认老模块：

| 缺口 | 修复（`school/MyStuTaskParser.kt`） |
|---|---|
| `ASSIGNMENT` 只匹配 `assignment overview` | 正则改 `assign(?:ment)? overview`，两套模块并存（「assign overview」是「assignment overview」的前缀，可选组区分） |
| `ASSIGNMENT_ID` 只匹配 `mod/assignment/view\.php` | 改 `mod/assign(?:ment)?/view\.php\?id=`；`index.php` 链接无作业 ID，与老模块同规则按 `externalId = null` 上交 |
| 截止时间只认「截止时间:」 | **实为已兼容**：`DueDates` 正则只匹配日期模式、不管标签前缀，「到期日期: 2026年09月26日 星期六 00:00」可直接解析；新增用例锁定 00:00 不退化成默认 23:59 |
| 状态带「我的作业：」前缀与「未评分」尾缀 | `submissionOf` 归一前剥掉前缀与尾缀（尾缀只描述评分状态，与提交无关） |

**fixture**：`mystu-dashboard.html` 补入 course-3001「高级算法」4 条新模块作业（中期检查×2、项目最终汇报×2，ID 410001–410004，全部「我的作业：还未提交, 未评分」「到期日期: 2026年09月26日 星期六 00:00」），原始抓页仍只存本机 `.shots/`。现有断言同步更新（3→7 条）。

**测试**（`MyStuTaskParserTest` +4、`MyStuPageMergeTest` 新建 +5）：新模块 4 条 externalId/状态/到期时间三要素正确；`sourceCourseIds` 透出；前缀/尾缀清洗各态；`index.php` 缺 ID 暂缓；多页合并去重与计数（见 19.2）。

### 19.2 兜底：课程集合并集收敛（随 P1 顺带）

**实现**（`school/WebViewSchoolAdapter.kt`）：

- `TaskParse` 新增 `sourceCourseIds`（本页课程块 ID 集合，C 解析层输出，不进合并层）；
- `fetchTasks` 同一次同步内再抓一页与首页课程集合取并集，**仍有新增才继续抓**（≤3 次封顶），集合不再新增即停；附加抓取失败/超时（单页 10s 上限）降级为已有结果，不影响主流程；
- 多页合并纯函数 `mergeTaskPages`：按 `externalId` 去重（同 ID 保留首页快照），缺 ID 条目按"标题+截止+课程"折叠并去重计数，警告与课程集合并集。

**约束核对**：一次同步 = 打开时全量拉取、≤3 次请求，不引入轮询；`manualDirty`/`completedAt` 保护与 `schoolSubmission` 契约均未触碰。

### 19.3 P2 三项评估结论（回结论，未实现）

| 项 | 结论 | 理由 |
|---|---|---|
| 扩抓每课索引页 `mod/assign/index.php?id=` | **暂缓**，待 D 提供脱敏 index 页后评估 | 真实结构未抓取（老模块索引页路径可能不同，D 亦标注）；首页波动已由并集收敛兜底；每课 1 请求与 E 的 30s 模块超时的交互需先量化（课程多时 N 次请求） |
| 课程发现源（「显示所有课程」列表页） | **暂不接入** | D 实测：隐藏 36 门=历史学期，首页课程块齐全时即当前学期全量；并集收敛已兜底整课消失。若确认课程块本身丢失频发（非缓存波动），再抓列表页评估按学期过滤 |
| 课程 ID 缓存（C＋E 商定） | **方案已提，待 E 定表** | 任务侧 `sourceCourseId` E v4 已落库；建议 E 增存"上次作业快照的 `sourceCourseIds`"，适配器发现首页课程块少于缓存时按缓存 ID 扩抓（依赖索引页项）。C 侧 `TaskParse.sourceCourseIds` 已就绪 |

### 19.4 验收对照（D 第七节）

| 口径 | 结果 |
|---|---|
| 1. 单测：新模块 4 条三要素 | ✅ `newAssignModuleEntriesAreParsedWithIdDueAndSubmission` |
| 2. 真机（D 手机对照组） | 待 D：一次同步后高级算法 4 条出现、既有条目 manualDirty 不被覆盖（合并层保护未动）、无重复 |
| 3. P2 历史作业 | 本轮 P2 未实现，不做声明 |

全仓单元测试 **319 项 / 0 失败 / 0 跳过**；`:app:assembleDebug` 通过。**本轮未跑真机**。

## 20. CBE-01 自动课程映射与 P2 历史作业补抓（2026-11-25）

基线 main `7d04477`。§19.3 的“暂缓/待定”两项本轮落地；C-01／C-02／双模块解析未改动。

### 20.1 CBE-01：规范化课程名唯一命中

**新增** `data/sync/CourseLinker.kt`（纯对象，无 Android 依赖）：输入学校侧课程名与本地同步课程的 `(externalId, title)`，输出唯一命中的教务课程编号或 null。规则：

1. 去掉开头学期标签（`[2026秋]`、`［］`、`【】`、`（）`、`()`）；
2. 候选 = 完整名 + 去掉末尾空白分隔教师名；**括号内教师不剥离**；
3. 规范化（去空白与常见标点、统一小写）后**完全相等**才命中，不做模糊匹配；
4. 命中 0 条或多条不同课程 → null（歧义不猜）。

**接入**（`data/sync/SyncMerge.kt` 的 `mergeTasks`）：适配器仍只透传来源信息，不读库；映射在合并层执行，候选来自本次事务内的本地同步课程。显式 `courseExternalId` 优先；`manualDirty` / `completedAt` 保护覆盖“用户手动改关联”；改名校验不命中时保留原关联，不清空。

**测试**：`CourseLinkerTest` 9 例 + `TaskSubmissionSyncTest` 4 例（唯一同名关联、改名保留、同名不同课不关联、数字来源编号不冒充教务编号）。

### 20.2 P2：每课索引页补抓

**配置**：`SchoolInfo.taskIndexUrlTemplate`（汕头大学 `…/mod/assign/index.php?id={id}`）；null 的学校不补抓。

**编排**（`school/WebViewSchoolAdapter.kt`）：

- 补抓对象 = 本次首页课程 ∪ `knownSourceCourseIds(schoolId)`（E v5 缓存），首页本次没出现的课程优先；
- 预算：≤12 门课/次、总 6s、单请求连接 3s/读取 4s（`SchoolHttp.get` 新增超时参数）；仍是一次打开一次拉取，不轮询；
- 合并：`mergeTaskPages` 按 `externalId` 去重，首页优先，缺 ID 条目按内容折叠；
- 降级：单课失败跳过；连续 2 门课无作业行即停；首页结果照常成功。

**解析**（`MyStuTaskParser.parseTaskIndex`）：按行内 `mod/assign(ment)?/view.php?id=` 链接定位（与首页同规则），链接文本为标题、行内其余文本解析到期日期与提交状态；不依赖表格类名/列顺序。

**尚未校准**：真实索引页样例未取得（D 已真机确认索引页存在且含本学期全部历史作业）；结构不同则 0 条 + 停止，不误导入。拿到脱敏样例后补 fixture 并复核列语义；老模块 `mod/assignment/index.php` 路径待确认。

**测试**：`MyStuTaskIndexParserTest` 4 例、`WebViewSchoolAdapterBackfillTest` 6 例（含 12 门上限、连续空即停、单课失败降级、首页优先）。

### 20.3 本轮验证

全仓 JVM 单测 **441 项 / 0 失败 / 0 跳过**（rebase 到 main `7d2d359` 后复跑）；`:app:assembleDebug` 通过。**本轮未跑真机**（无设备/模拟器/学校会话），C-01、C-02 的手机复验仍待补。回报见《C-问题处理回报-2026-11-25.md》，证据见 `docs/evidence/c-cbe01-p2/`。
