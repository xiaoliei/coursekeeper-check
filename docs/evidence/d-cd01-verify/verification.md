# D 真机验收：CD-01 学校已提交 → 同步自动完成（v1.1）

- 日期：2026-11-21　|　设备：荣耀 Magic5 Pro（API 36）　|　被验版本：main `d37ac0a`（E 的 v4 落库 3917623 + 660bc32 + 文档 d37ac0a）
- 口径依据：《任务字段与通知规则 v1.1》（a87f992）第五节「学校已提交 → 自动完成」
- 方法：保留数据覆盖安装 → 真实学校账号登录同步 → `run-as` 拉库逐字段核对 + 界面截图

## 一、验收四项全部通过

| # | 场景 | 库内证据 | 界面证据 |
|---|---|---|---|
| ① | v3→v4 迁移不丢数据 | `user_version=4`；10 课程/24 排课/4 任务原样保留，新增 schoolSubmission/sourceCourseId/sourceCourseName 三列 | 05-final-open.png（启动正常零 FATAL） |
| ② | 学校「已提交」→ 自动完成 | 「第一次作业--前端」`completedAt=2026-11-21T11:22:11.177578`（与 TASKS lastSuccessAt 03:22:11.177578Z 同一时刻，微秒级一致）、version 1→2、manualDirty=0、schoolSubmission=SUBMITTED | 02-done-view.png（已完成视图显示该项） |
| ③ | 手动「恢复待办」 | ⋯菜单恢复：completedAt 清空、manualDirty=1、version 2→3、schoolSubmission 保留作追溯 | 03-selection-not-restore.png（注意：已完成视图勾选框=批量选择，不是撤销） |
| ④ | 恢复后学校不覆盖 | 12:01 全新同步成功（TASKS IDLE 04:01:02Z），学校页面仍报 SUBMITTED，但任务保持 completedAt=None/manualDirty=1/version=3——合并层保护生效 | 08-final-open-tab.png（待办里该项仍显示已逾期） |

④ 是关键项：登录成功后 afterLogin 强制同步拉到全新学校数据，`SyncMerge.kt` 的保护检查（manualDirty → skip）在真实数据上拦住了自动完成。

## 二、顺带验证的边界

| 场景 | 结果 |
|---|---|
| 新作业入库 | 「web第二次作业--js」首次同步插入，schoolSubmission=NOT_SUBMITTED，不触发完成 |
| 页面无状态的旧任务 | 「实验2及实验3报告」页面有作业但无状态文本 → schoolSubmission=null，保持逾期不猜 |
| 学校 SSO 会话过期 | 11:56 重开 App 触发前台同步，checkSession=EXPIRED，**任务数据原样保留**，重新登录后恢复 |
| 手动完成受保护 | 用户 11:36 手动完成两条旧作业（manualDirty=1），12:01 同步后 version 不变、状态不变 |
| 课表渲染 | 第 11 周 11 条排课正常（B 课程服务 + C 同步链路） |

## 三、发现的覆盖边界（改进需求，交 C）

MYSTU 首页只显示「您有需要留意的作业」：已提交且截止较早的作业（如 9 月底）会从首页消失，自动完成够不着，只能用户手动标完成。首页每门课作业区带 `mod/assign/index.php?id=<课程ID>` 链接，**科目内可看本学期全部历史作业（含已提交）**——C 扩抓该索引页即可补全覆盖，E 的 schoolSubmission 契约不变。已按抓页实证：前端作业=已提交、web js=还未提交，解析与页面一致，无错位。

## 四、测试口径

- 本轮单元测试 326 项 / 0 失败（E 新增 MigrationV4Test + TaskSubmissionSyncTest 14 项，含自动完成撤提醒的快照对账用例）；GitHub Actions `d37ac0a` build success。
- 本表不重复记录单测结论，只记真机闭环。
