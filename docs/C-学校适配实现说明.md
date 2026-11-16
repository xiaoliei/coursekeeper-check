# C 成员：学校适配实现说明

更新日期：2026-11-16（含 D 复核修复）　｜　范围：基础版 T05（官方登录与数据适配）+ T06 适配器侧
首批学校：汕头大学（统一身份认证 SSO + 正方教务课表 + MYSTU 学习平台作业）

本文说明“怎么实现的”，交接/验收口径见同目录《C-学校登录与数据适配交接》。
**2026-11-15 下午第二轮真机检验**修复 4 个问题（会话过期误判离线、一格多条排课只解析第一条、幻影课程残留、登录弹层滑不到底），详见第 13 节；
**2026-11-16 按 D 复核意见**修复 3 个问题（启动崩溃、同步失败谎报完成、空课表清库），详见第 15 节。

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
5. **去重**：跨大节的课在多行重复出现，按“天 + 起止节 + 周次 + 地点”去重（教师不同但同槽位的合讲条目只保留一条）。
6. **教师名含“周”**：周次行用“数字开头 + 周”正则识别，不会被“周艳红”误判。
7. **学期起始日**：页面不提供 → 返回 null，由用户在课表页“学期设置”补录，不猜日期。
8. **一格多条排课**（2026-11-15 真机发现）：同一 `div.kbcontent` 里用一整行短横线分隔多条排课（大学物理实验一格 3 段周次、潮汕非遗一格 8 段），`entryGroups()` 按分隔行拆开逐条解析；不拆就只会解析每格第一条，后面的周次全部丢失（这就是“下周该上的课不显示”的根因之一）。
9. **双层括号周次**：导出页存在 `9,11-13([周])[06-07节]` 写法，`WEEKS_LINE` 正则兼容 `([周])`，`WeekText.parse` 剥离 `[ ]`。

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
5. **课程关联**：首版留空。MYSTU 课程 ID 与教务课程编号是两套体系，按名称模糊匹配会错关联，待与 E/B 约定。
6. **提交状态**：不导入，完成与否以用户本机记录为准（避免同步覆盖用户操作）。

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
| 全仓单元测试 | **244 项 / 0 失败 / 0 跳过**（C 32 项：通用解析 7、汕头课表 8、MYSTU 作业 3、会话规则 7、登录状态机 4、退出登录 1、适配器单例 1、同步失败文案 3） |
| 构建 | `:app:assembleDebug` 通过 |
| 课表离线校验 | 真实抓取页：10 门课 / 24 条排课、0 跳过、0 警告 |
| 作业离线校验 | 真实保存页：8 条、0 缺 ID |
| 真机第一轮（2026-11-15 上午） | SSO + MYSTU 两处登录成功；课表 inserted 9、作业 inserted 8；设学期后课表与教务一致 |
| 真机第二轮（2026-11-15 下午） | 覆盖安装复验：冲突横幅消失、残留课程清零；第 10 周 9 条 / 第 11 周 11 条排课，潮汕非遗 9,11-13 周段正常出现；登录弹层整页可见 |
| 单元测试（D 复核后） | 全仓 **244 项 / 0 失败 / 0 跳过**（新增：适配器进程级单例、同步失败提示文案） |

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
| 作业→课程关联 | 首版留空，显示“未关联课程” | 产品 + E/B 定规则 |
| `termTag` | 未填，跨学期重修有撞键风险 | 确定学期后补 |
| 验证码/SSO 细节 | 本次登录未触发验证码；**过期自动重登已真机验证**（会话有效时“重新登录”自动跳回门户并强制同步，无需输密码）；单点互踢仍未验证 | C 真机补测 |
| MYSTU 大数据量 | 作业很多时概览是否只渲染部分条目 | 真机补测 |
| 诊断抓页开关 | `WebViewSchoolAdapter(debugDumpDir=…)` 会把每次拉到的页面存到应用外部目录（仅本机），正式发布前摘除 | C |
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

测试：全仓 244 项 / 0 失败 / 0 跳过。真机崩溃路径（返回键退出 → 重进）复验见第 16 节：已闭环。

## 16. 第三轮真机检验（2026-11-17 凌晨）：崩溃路径闭环 + 学校改课表的实证

覆盖安装 `app-debug.apk`（51c8a19 构建，含 D 复核三项修复）到 Redmi 24117RK2CC（`adb install -r`，保留数据）。

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
- **镜象清理（E 复核修复）实证生效**：远端已删除的 5 门旧课自动从本地清掉，手动任务 protectedCount=7 未受影响；
- 今天启动的重试按 OFFLINE 记录，首页横幅如实显示「当前离线 · 已有数据可继续使用」，与 D 复核第 2 条修复后的语义一致。

### 16.3 对团队的两点提示

1. **「大学生人生哲学八讲」现在是学校页面上的真实课程**（周四 6-7 节 3-18 周）：当初 T05 验收时的“幻影课程”经学校改课后已坐实。镜像口径下它正确入库，无需处理；若组内同学还在引用 11-15 的旧课表截图，注意已过时。
2. 学期中学校侧改课表是常态：同步 + 镜像清理会自动跟随，**手动添加/手动改过的课程与任务不受影响**（manualDirty 与 protectedCount 保护，已在真实数据上验证）。

