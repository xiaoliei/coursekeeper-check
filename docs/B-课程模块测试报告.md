# B 课程模块测试报告

- 日期：2026-11-12　|　成员：B（课程与周次）　|　分支：`feat/course-service`（本地，未推送）
- 代码提交：`54f3c81`　|　证据提交：`95f8b44`
- 对应口径：`docs/课程与周次规则-v0.2.md`

## 一、结论

| 项 | 结果 |
|---|---|
| 全量单元测试 | **176 项，0 失败，0 错误**（26 个套件） |
| B 模块单测 | **50 项，0 失败**（7 个套件） |
| 构建 | `:app:assembleDebug` BUILD SUCCESSFUL |
| 模拟器手工验证 | 设置学期 → 添加课程 → 冲突提示 → 详情 → 翻周 → 删除，全部走通 |
| 遗留 | 无阻塞项；4 条已知限制见第七节 |

## 二、环境

| 项 | 值 |
|---|---|
| 系统 | Windows 11（工程位于 `E:\code\coursekeeper-check`，纯英文路径） |
| JDK | 21.0.7（`E:\java\jdk21`） |
| Gradle / AGP | 9.6.0（wrapper 锁定）/ 9.4.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Android SDK | `E:\AndroidStudio\Sdk`（platform 37.0、build-tools 37.0.0/36.0.0、platform-tools 37.0.1） |
| 模拟器 | `Medium_Phone_API_37.0`（Android 37，x86_64，Play Store 镜像） |
| 加速 | Android Emulator Hypervisor Driver（aehd，RUNNING） |

> **环境注意**：Robolectric 首次运行需要从 Maven Central 下载 `android-all-instrumented`（约 101 MB）。本机连不上 `repo1.maven.org`，已从阿里云镜像预置到 `~/.m2/repository/org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i7/`。换机器跑全量测试时若出现 `MavenArtifactFetcher ... SocketException`，按第八节处理。

## 三、全量回归

命令：

```powershell
cd E:\code\coursekeeper-check
$env:JAVA_HOME='E:\java\jdk21'
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

| 模块 | 套件 | 用例 | 失败 |
|---|---|---|---|
| **B 课程（本次交付）** | 7 | **50** | 0 |
| E 数据/同步 | 7 | 44 | 0 |
| A 首页/公共 | 5 | 28 | 0 |
| D 提醒 | 3 | 21 | 0 |
| F 统计 | 2 | 17 | 0 |
| D 任务 | 2 | 16 | 0 |
| **合计** | **26** | **176** | **0** |

## 四、B 模块用例明细（50 项）

### 4.1 `WeekTextTest`（10）

| 用例 | 验证点 |
|---|---|
| 非连续周 9,11-13 解析为位图 7424 | 核心：`9,11-13` → bit8/10/11/12 |
| 连续区间 1-16 解析为低 16 位全 1 | 65535 |
| 相邻分段会合并成一段 4-7 加 8-16 | 规范化输出 `4-16` |
| 单双周是位图子集 | `1-16(单)`/`1-16双周` |
| 兼容导出里的 周 后缀与全角符号 | `9，11-13（周）` |
| 超出本学期周数只丢弃该段并记警告 | `weekCount=10` 截断 |
| 非法段不影响整条 其他段仍然生效 | `abc,3-4` |
| 第 0 周被忽略并记警告 | 越界丢弃 |
| 全部非法时返回空位图而不是抛异常 | 容错边界 |
| 位图与文本往返一致 | 同源校验 |

### 4.2 `WeekCalculatorTest`（7）

任意日期归一化周一；起始日非周一仍按该周周一起算；第 10 周对应 11-09～11-15；学期前后返回 null；起始日缺失不猜；当前学期取包含今天的；最近配置学期用于非教学周浏览。

### 4.3 `ConflictDetectorTest`（7）

不同课程同天节次相交为冲突；周次不相交（4-7 与 8-16）不算；部分周重叠只报交集；同课程异地点按数据异常；完全重复条目去重；不同星期/节次不相交；课程已删除的排课不参与。

### 4.4 `CourseProjectionTest`（10）

**四个课程数字与 F 的 `IntervalCourseStatsAdapter` 逐项一致**（7 组边界夹具）；跨节次按条计/按节累加；同课程多条目按门去重；同名不同编号算两门；非连续周第 10 周没课、第 11 周有课；未设学期 `NO_SEMESTER`；非教学周 `NOT_IN_TERM`；下一节课取当前时刻之后最近一条；按天分组与空周；冲突按周返回。

### 4.5 `CourseViewModelTest`（9）

初始显示当前周；翻到下周再回本周；边界周不能继续翻（1/20）；未设学期；设置学期后进入 READY；保存课程把草稿交给写侧；空标题被拦下且不调用写侧；版本冲突转人话；删除课程并移除。

### 4.6 `HomeCourseProjectionTest`（6）

未设学期 → `NoSemester`；非教学周 → `NotInTerm`；教学周内无课 → `Ready` 且计数 0；跨节次按条目计数并给节次文案；有节次时刻表时算下一节课与“10 分钟后”；当天课全部结束给“今日课程已结束”。

### 4.7 `CourseSelfCheckTest`（1）

手动自查报告：打印周次解析、教学周换算、冲突判定、与 F 适配器对比、非连续周三行对照，任一不一致即失败。用于人工验收，也可作为回归兜底。

## 五、构建产物

| 项 | 值 |
|---|---|
| 任务 | `:app:assembleDebug` |
| 结果 | BUILD SUCCESSFUL |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 13,547,427 字节（12.9 MB） |
| SHA256 | `d28e74d7de971d0ad67b68527caa34920d88566af9d5ec46b597075c756fe3a4` |

## 六、模拟器手工验证（Android 37）

安装 `app-debug.apk` 后按真实用户路径操作：

| # | 操作 | 期望 | 实际 |
|---|---|---|---|
| 1 | 启动 App，跳过引导 | 进入首页 | ✅ |
| 2 | 点「课表」tab | 未设学期显示“待设置学期”，不显示 0 | ✅ |
| 3 | 点「设置学期起始日」，起始日填 2026-11-12、20 周 | 归一化到 11-09 周一，显示“第 1 周 · 11 月 9 日 - 11 月 15 日” | ✅ |
| 4 | 添加课程 Data Structure（周一 1-2 节，1-16 周） | 出现在周一，显示“1-2 节” | ✅ |
| 5 | 再加一门 OS，同一时段 | 顶部冲突横幅 + 两张卡片标“冲突” | ✅ |
| 6 | 点「详情」 | 显示教师/节次/周次/地点，含编辑、删除 | ✅ |
| 7 | 点「下一周」 | 第 2 周 · 11 月 16 日 - 11 月 22 日 | ✅ |
| 8 | 点「本周」 | 回到第 1 周 | ✅ |
| 9 | 删除 OS 并确认 | 回到 1 条排课，冲突横幅消失 | ✅ |
| 10 | 观察布局 | 周视图占满剩余高度，底部按钮不被挤出 | ✅ |

过程中发现并修复 2 个只有真跑才暴露的问题：

1. 星期标题统计的是条目数却标“节”（“周一 · 1 节”实际 2 节）→ 改为按 `periodCount` 汇总。
2. `LazyColumn` 未加权重，会把底部按钮挤出屏幕 → 加 `Modifier.weight(1f)`。

## 七、未覆盖与已知限制

| # | 项 | 说明 |
|---|---|---|
| 1 | Compose UI 自动化测试 | 只有 JVM 单测 + 模拟器手工验证，没有 `androidTest` 用例 |
| 2 | 节次时刻表 | 无官方《上课时间对照表》，界面只显示“第 X-Y 节”，`nextCourse.time` 为空 |
| 3 | 停课/假期 | 校历第 13 周校运会停课不会自动跳过 |
| 4 | 调课覆盖 | 群通知调课不改课表，只能手动编辑课程或建任务提醒 |
| 5 | 跨学期重修 | `courses` 的 `(source, externalId)` 全局唯一会挡住同编号两个学期（E 的开放问题 6） |
| 6 | 真机 | 只在模拟器验证，未上真机（D 有 EMUI 安装备注可参考） |

## 八、复现步骤

```powershell
# 1) 全量回归
cd E:\code\coursekeeper-check
$env:JAVA_HOME='E:\java\jdk21'
.\gradlew.bat :app:testDebugUnitTest --console=plain

# 2) 只跑 B 的 50 项
.\gradlew.bat :app:testDebugUnitTest --tests "com.coursekeeper.course.*" --console=plain

# 3) 构建
.\gradlew.bat :app:assembleDebug

# 4) 装到模拟器（先确认 adb devices 有设备）
& 'E:\AndroidStudio\Sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

若全量测试报 `MavenArtifactFetcher.java ... SocketException`：

```powershell
$v='9-robolectric-4913185-2-i7'
$dir="$env:USERPROFILE\.m2\repository\org\robolectric\android-all-instrumented\$v"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
curl.exe -L -o "$dir\android-all-instrumented-$v.jar" `
  "https://maven.aliyun.com/repository/public/org/robolectric/android-all-instrumented/$v/android-all-instrumented-$v.jar"
```

## 九、证据文件

| 文件 | 内容 |
|---|---|
| `docs/evidence/member-b-tests.xml` | B 的 7 个套件 / 50 项测试 JUnit XML |
| `docs/evidence/member-b-checks.json` | 分支、提交、JDK、任务、用例数、APK 大小与 SHA256 |
| `docs/课程与周次规则-v0.2.md` | 口径与实现状态 |
| `docs/B-课程与周次交接.md` | 接口、状态、各成员接入方式、已知限制 |
| 本文件 | 全量回归与手工验证记录 |
