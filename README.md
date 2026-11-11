# 课程任务管家

本目录是课程任务管家的 Android 项目仓库，由 A 模块演示工程迁移而来。当前首页与任务页已接入同一份真实任务数据；范围、接口和验证结果见 [A 模块接入与验收说明](docs/A-首页真实任务接入-本地说明.md)。原始交付快照保留在团队仓库的 `archive/coursekeeper-a`；统计草案见 [docs/统计口径与规则草案.md](docs/统计口径与规则草案.md)。

## 当前状态

- 版本 0.2.0，包名统一为 `com.coursekeeper`（原 A 演示为 `com.coursekeeper.a.demo`）。
- 首页和 D 的任务页共享 E 的真实 Room 数据；新增、完成、撤销和日期筛选联动，跳过引导的选择会保存。
- B 的课程服务及 F 的压力服务尚未接入，真实模式显示明确的缺失状态。独立演示通过 DemoHomeGateway、Preview 和默认空插槽继续可用。
- 正式口径（统计、压力指数、规则阈值）为草案，需小组确认后生效，见 docs 目录。
- E 的数据层已升级 Room v3，支持非连续周次、排课独立教师及 v1/v2 无损迁移；接入方式见 [E 成员数据与同步交接](docs/E成员数据与同步交接.md)，本轮验证见 [E v3 迁移与联调验收](docs/E-v3迁移与联调验收.md)。首页、任务、提醒和看板共用真实快照，学校同步等待 C 的适配器。

## 环境与版本锁定

| 组件 | 版本／位置 |
|---|---|
| Gradle | 9.6.0（wrapper 锁定，含校验和） |
| Android Gradle Plugin | 9.4.0 |
| Compose Compiler | 2.2.10（AGP 内置 Kotlin，勿额外加 `org.jetbrains.kotlin.android`） |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26；当前 AGP 默认 Build Tools 36.0.0；Platform 37 |
| JDK | 仓库 daemon-JVM 配置锁定 JDK 21；其他 JBR 启动时可能触发 JDK 21 下载 |
| SDK | 本地安装的 Android SDK，由 Android Studio 生成 `local.properties` 指向（已被 gitignore 排除） |

首次构建若缺少 `platforms;android-37`，AGP 会在许可已接受时自动下载；如失败，用 sdkmanager 安装后重跑。

## 构建与测试

在工程目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest   # 先跑单元测试（门禁）
.\gradlew.bat :app:assembleDebug       # 通过后再打调试包
```

- 调试 APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。
- JVM 测试覆盖 A 的投影、演示与接入、D 的任务交互以及 E 的数据和同步；本轮验证记录见 [A 本地联调说明](docs/A-首页真实任务接入-本地说明.md)。
- 统一“边界假数据”素材（跨节次、单双周、非连续周、本周/下周区间两侧、截止待定等）在 `app/src/test/java/com/coursekeeper/fakedata/`，供 F 的统计/看板测试与首页投影回归复用；非连续周素材已随 E v3 启用。
- 已在 Temurin JDK 21.0.13+9 下完成应用／测试 APK 构建和 65 项单元测试，0 失败、0 跳过。
- E v3 最新联调在 JDK 21 下通过全仓 126 项单元测试（含 E 44 项），0 失败、0 跳过；应用／测试 APK 均已构建，见 [v3 验收记录](docs/E-v3迁移与联调验收.md)。
- 设备测试：`.\gradlew.bat :app:connectedDebugAndroidTest`（需模拟器或真机）。
- GitHub Actions 自动构建（`.github/workflows/build.yml`）：`push 到 main` 与任意 PR 都会先跑 `:app:testDebugUnitTest`、通过后再 `:app:assembleDebug`，作为合并门禁。

注意：Windows 下 AGP 可能对含中文的工程路径告警或报错。若遇到，把工程复制到英文路径再构建即可；路径不强制英文，中文路径能正常构建时可直接使用。

## 日常开发流程（编译与测试）

> 通用流程，所有成员按此执行。两条硬性约定：**改完先本地编译并跑测试**、**推送前先拉取远程最新代码并 rebase 解决冲突**。工程路径不强制英文，仅在 Windows 构建报中文路径错误时建议改用英文路径。

### 1. 首次准备

```powershell
# 克隆团队仓库到本地工程目录（路径可含中文；若构建报错再换英文路径）
git clone <团队仓库地址> <本地工程目录>
cd <本地工程目录>
```

- Windows 下若 AGP 报“项目路径包含非 ASCII 字符”，把工程复制到英文路径后构建即可；路径不是硬性要求，能正常构建时用中文路径也可以。
- 用 Android Studio 打开克隆后的目录（不要只打开 `app` 子目录），配置 JDK 21 和本地 Android SDK。仓库的 daemon-JVM 配置会优先选择 JDK 21；如启动用其他 JBR，Gradle 可能需要联网下载 21。
- `local.properties` 由 Android Studio 自动生成，已被 gitignore 排除，不要提交。
- 首次构建会自动下载 Gradle 9.6.0、依赖和缺失的 SDK 组件（需联网；网络慢可先配置镜像）。

### 2. 编译与单元测试

```powershell
cd <你的工程目录>
# Windows PowerShell：JAVA_HOME 指向 Android Studio 自带的 jbr
$env:JAVA_HOME = "<Android Studio 安装目录>\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

macOS / Linux 使用 `./gradlew`，参数相同：

```bash
cd <你的工程目录>
export JAVA_HOME="<Android Studio 的 jbr 路径>"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

- 只编译可去掉 `:app:testDebugUnitTest`；单测报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。
- 只跑某个测试：`.\gradlew.bat :app:testDebugUnitTest --tests "com.coursekeeper.DemoHomeGatewayTest"`。
- 各成员的模块测试统一放在 `app/src/test`（JVM）与 `app/src/androidTest`（界面），按模块包名新增。

### 3. Android Studio 日常开发

- Open 选择工程目录，Gradle JDK 配置为 JDK 21，等待 Gradle Sync 完成。
- 点 Run 即编译并安装到当前模拟器或真机；提交前先完成第 2 节的编译与单元测试。

### 4. 界面（设备）测试

需要模拟器或真机；模拟器用 Android Studio 的 Device Manager 创建（API 26+ 即可，API 37 已验证）。

```powershell
cd <你的工程目录>
$env:JAVA_HOME = "<Android Studio 安装目录>\jbr"
.\gradlew.bat :app:connectedDebugAndroidTest
```

### 5. 提交与推送（先 rebase，后推送）

推荐每人使用本人分支开发（如 `feat/statistics`、`feat/task-reminder`），`main` 始终保持可构建。

```powershell
# 1) 提交本地改动
git add -A
git commit -m "feat: 简要说明改动"

# 2) 推送前必须先拉取远程最新代码并 rebase（重点；等价于 git pull --rebase origin main）
git fetch origin
git rebase origin/main

# 3) 有冲突时：逐个文件解决 -> 标记已解决 -> 继续 rebase
git add <冲突文件>
git rebase --continue
# 想放弃本次 rebase 可执行：git rebase --abort

# 4) rebase 完成后重新编译并跑测试，确认没有引入问题
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

# 5) 推送个人分支
git push -u origin <你的分支>
```

- **推送前必须 `fetch` + `rebase` 到远程 `main` 最新提交**，冲突在本地解决完再推送；未同步时不要直接 push。
- 每次开始改动前建议先执行 `git fetch origin && git rebase origin/main`，减少后续冲突。
- 不要提交 `build/`、`.gradle/`、`local.properties`、密钥或个人配置（`.gitignore` 已覆盖，提交前用 `git status` 自查）。
- 已推送过的个人分支在 rebase 后如需再次推送，使用 `git push --force-with-lease`；共享分支 `main` 禁止强制推送。
- 通过 PR/MR 合并到 `main`，合并前按分工计划由指定成员交叉评审。

### 6. 换机说明

新机器把仓库克隆/复制到本地工程目录（遇中文路径构建报错再换英文路径），安装 Android Studio 并配置 JDK 21 与 Android SDK；首次构建 Gradle 会按 `gradle-wrapper.properties` 和 `app/build.gradle.kts` 自动下载发行版、依赖与缺失的 SDK 组件（需网络）。

## 目录结构

```text
coursekeeper/
├── app/src/main/java/com/coursekeeper/
│   ├── MainActivity.kt        # 组合根，团队接口的接入点
│   ├── demo/                  # 公共假数据（六种场景）
│   ├── home/                  # 首页契约 HomeGateway / HomeUiState / ViewModel
│   ├── ui/                    # AppShell 导航、首页、公共组件、主题
│   └── (statistics/ 待 W2 由 F 新增)
├── app/src/test/              # JVM 逻辑测试
├── app/src/androidTest/       # Compose 设备测试
├── .github/workflows/         # GitHub Actions 自动构建（先测后构）
└── docs/                      # 团队口径与规则草案
```

## 各成员接入指引

| 成员 | 接入内容 | 入口 |
|---|---|---|
| A | 首页真实任务投影、公共导航与状态；消费 B/D/E/F 接口 | `home/RepositoryHomeGateway.kt`、`MainActivity.kt` |
| B | 替换课表占位页，提供 `CourseService`（周次、节次、冲突） | `AppShell.kt` 中 `DemoCourses` |
| C | 接入选校登录与 WebView，返回结构化数据给 E | 登录弹层与 `DemoScenario.Expired` |
| D | 维护已接入的真实任务页，继续实现通知 | `task/TaskScreen.kt`、`TaskViewModel.kt` 与共享 `TaskGateway` |
| E | 维护 Room、Repository 与同步状态及后续迁移 | `CourseKeeperData` 为首页与任务页提供同一个数据源 |
| F | 替换数据占位页，实现统计、规则、压力指数与构建检查 | `AppShell.kt` 中 `DemoStatistics` |

页面先使用统一假数据推进，接口确认后逐项联调；正式写操作由 D、E 补齐校验、版本冲突、去重与事务。

## 假数据说明

DemoHomeGateway 的时钟固定为 **2026-11-09 09:50**，用于 Preview、测试与独立演示，内存数据重启还原。公共工程的 MainActivity 使用真实 Repository、设备日期和持久化引导状态，不灌入这些演示记录，不显示“15 门课程／压力 40”等样例值。
