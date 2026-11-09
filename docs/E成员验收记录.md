# E 成员基础版验收记录

日期：2026-11-09。范围为 T03、T06、A00；依据六人分工、项目计划书及《任务字段与通知规则 v1.0》。本次按用户明确要求保留分段提交，直接交付到 `main`，不创建 PR；交付后由 F 按计划交叉复核。

## 构建与测试

| 检查 | 实测结果 |
|---|---|
| `:app:assembleDebug` | 通过，调试 APK 已生成 |
| `:app:testDebugUnitTest` | **37 项通过，0 失败，0 错误**；其中 E 新增 28 项、A 原有 9 项 |
| 工作区与验证副本一致性 | 主源码、JVM 测试、Gradle 配置与 schema 共 32 个文件，逐文件 SHA-256 比对一致 |
| `git diff --check` | 通过 |
| Android lint | E 源码无错误；全仓检查仍有 A 首页预览的既有错误，详见后文 |

逐项测试证据：[member-e-tests.xml](evidence/member-e-tests.xml)。构建和 lint 摘要：[member-e-checks.json](evidence/member-e-checks.json)。

验证在英文副本 `C:\tmp\coursekeeper-e-build` 执行，避免仓库 README 已说明的 Windows 中文路径构建限制。使用仓库锁定的 Gradle 9.6.0、AGP 9.4.0、Compose 编译器 2.2.10，Gradle daemon 使用 JDK 21；compile/target SDK 37，Robolectric/SQLite 测试使用 API 28。SDK、代理、local.properties 与构建缓存均未加入仓库。

调试 APK：`C:\tmp\coursekeeper-e-build\app\build\outputs\apk\debug\app-debug.apk`。

SHA-256：`ac3b9a433bad7d96c5a843f42bef11a60e81f2ce9103e0772f1ac2b85f7ca564`。

推送前已执行 `git fetch origin` 和 `git rebase origin/main`（无冲突），随后以 `--rerun-tasks` 完整重跑编译和单元测试：48 个构建任务实际执行，37 项测试全部通过。

## E 用例分布

| 测试类 | 数量 | 验证内容 |
|---|---:|---|
| RepositoryTest | 9 | UUID、五类枚举、可空截止、设备本地完成时间、版本冲突、并发写入、requestId、错误码、删课程保留任务、学期/节次与快照 |
| SyncCoordinatorTest | 15 | 重复/差异/空导入、手动与完成保护、墓碑、模块回滚、部分成功、未知稳定 ID、30 分钟与强制刷新、有限重试、过期/断网、取消、并发旧响应、删课程后再同步、学校隔离及超时 |
| StorageRecoveryTest | 3 | 业务库关闭后复制恢复，完成/关联/回执/墓碑保留；v1 正常及边界数据、空库升级到 v2 |
| SettingsStoreTest | 1 | 选校与跳过引导标记持久化、重新创建设置存储后读取 |

迁移使用真实 Room 打开 SQLite v1 样例触发 `MIGRATION_1_2` 并校验 v2 结构。v1 是本轮定义的 E 初始 schema 基线，原 A 内存演示没有已发布的 Room 数据库。本轮不声称已完成真实历史安装包升级、Agent R01—R12 或真机性能验收。

## lint 现存问题

`app/src/main/java/com/coursekeeper/ui/HomeScreen.kt:100` 的 `HomePreview` 在组合中直接读取 `DemoHomeGateway().state.value`，触发 `StateFlowValueCalledInComposition`。已对照当前基线提交确认该代码原已存在，本轮对该文件无改动；交由 A/F 处理。没有添加 lint baseline、关闭检查或修改 A 页面来隐藏问题。

依赖版本更新与原 manifest 的 DataExtractionRules 提示属于 warning，不影响本轮编译和单元测试。验证环境一度出现 local.properties 路径转义错误，已修正本机配置后重新检查；该文件不属于仓库改动。

## 联调与阶段边界

- E 数据组件可直接实例化使用；当前应用仍使用 A 的内存演示入口，正式页面数据源切换由 A 结合 B/D/F 服务接入。
- C 的真实学校适配器、会话验证及作业稳定 externalId 尚未提供；同步行为用确定性脱敏适配器验证，不冒充真实学校联调。缺少稳定 ID 的作业不猜测去重键，暂缓入库并计数。
- 课程业务/周次计算归 B；任务业务、页面与通知归 D；统计和规则归 F。本轮保留他们的页面与草案，未提前定稿统计公式。
- 基础版验收后才启动 A01；本轮完成 A00 版本、requestId、事务与迁移预留，Keystore 和 Agent 凭据生命周期属于后续阶段。
- 未执行设备界面测试或真机性能测量；本轮没有 UI 改动。完整产品联调及 F 的交叉验收仍需按团队计划执行。

审核说明与接口样例见 [E 成员数据与同步交接](E成员数据与同步交接.md)。本轮已取得用户直接推送 `main` 的明确授权，沿用推送前同步远端、重跑构建与测试的要求；本次不走 PR，交叉复核按团队计划安排。
