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
