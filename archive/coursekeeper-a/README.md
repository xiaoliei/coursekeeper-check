# 课程任务管家 A 模块

本目录统一包含可点击原型、设计稿、Kotlin／Jetpack Compose Android 源码及演示 APK。A 已完成的内容和其他成员接下来要做的事，见 [A 模块交接说明](A模块交接说明.md)。

已经生成可安装的演示 APK；9 项逻辑测试、3 项模拟器界面测试通过。详见 [验收记录](docs/验收记录.md) 和 [实际首页截图](docs/screenshots/home.png)。

查看交互原型可直接用浏览器打开 [prototype/index.html](prototype/index.html)；首页字段和状态约定见 [字段清单](prototype/首页数据与交接说明.md)。安卓设备可安装根目录的 [演示 APK](课程管家A演示.apk)。

## 先运行起来

1. 打开 Android Studio，选择 **Open**，选中本文件所在的 `coursekeeper-a` 文件夹；不要只打开 `app` 子目录。
2. 工程路径使用英文，例如 `E:\AndroidProjects\coursekeeper-a`。Windows 的 Android 构建工具会拒绝含中文的工程路径。
3. 等待 Gradle 同步。当前锁定 Gradle 9.6.0、Android Gradle Plugin 9.4.0、Compose Compiler 2.2.10、Compose BOM 2026.02.01。AGP 使用内置 Kotlin，勿额外添加 `org.jetbrains.kotlin.android` 插件。
4. 在 SDK Manager 准备 Android API 37（本机目录为 `android-37.0`）和 Build Tools 36.0.0。Gradle JDK 选 Android Studio 自带 JBR；本机验证使用 Android Studio 2026.2.1 的 JBR 25。
5. 选择 Android 8.0／API 26 或更新的模拟器或安卓手机，运行 `app`。本次实际验证范围请看 `docs/验收记录.md`。

当前电脑已检测到 Android Studio 在 `D:\Program Files\Android\Android Studio`，SDK 在 `D:\SDK`。本地工程的 `local.properties` 已指向它；压缩包不含这个机器专用文件，换电脑后让 Android Studio 重新生成。

首次运行需要联网下载依赖。若 Gradle 下载失败，可检查 Android Studio 的代理配置与本机网络；不要为解决下载问题改动界面代码。本次源码未写入本机代理地址。

## 打开后体验什么

- 底部切换首页、课表、任务和数据四页；系统返回键从其他页回首页。
- 首页显示今日课程、今日截止、今日完成、下一节课、近期截止与本周概览。
- 点击数字进入对应筛选；点击课程或任务查看详情。
- 勾选任务后数字和本周进度联动，底部提示中可撤销。
- 手动添加任务：填写名称、关联课程、类型和截止时间；校验空名称及无效日期。
- 点击右上角“演示设置”，体验正常、离线、登录过期、部分成功、空数据及首次启动。切换场景会重置示例数据。
- 首次启动可以“先手动使用”；登录演示不收集账号密码。

演示时钟固定为 **2026-11-09 09:50**，方便与你的第一版设计图对照。假数据只在内存中保存，旋转屏幕时由 ViewModel 保留；结束进程或重启应用会还原。本周 15 门课程和压力指数 40 是展示样例，正式口径由 B、F 提供。

## 你接下来从哪里修改

| 你要改什么 | 对应文件 |
|---|---|
| 首页布局、卡片位置、文案 | `app/src/main/java/com/coursekeeper/a/ui/HomeScreen.kt` |
| 底部导航、页面跳转、弹层入口 | `app/src/main/java/com/coursekeeper/a/ui/AppShell.kt` |
| 公共卡片、任务行、空状态、提示横幅 | `app/src/main/java/com/coursekeeper/a/ui/components/Common.kt` |
| 蓝色、背景、文字配色 | `app/src/main/java/com/coursekeeper/a/ui/theme/Theme.kt` |
| 假课程、假任务和六种场景 | `app/src/main/java/com/coursekeeper/a/demo/DemoHomeGateway.kt` |
| 页面需要什么数据、操作接口 | `app/src/main/java/com/coursekeeper/a/home/HomeContract.kt` |
| 操作结果、错误提示和撤销事件 | `app/src/main/java/com/coursekeeper/a/home/HomeViewModel.kt` |
| 把假数据换成团队接口 | `MainActivity.kt` 中创建 `HomeViewModel` 的位置 |

建议你先做一次小修改练习：在 `HomeScreen.kt` 修改问候语或卡片间距，运行确认；再到 `DemoHomeGateway.kt` 修改一条课程名称，观察页面更新。之后逐项完善首页，而不是一次改动所有文件。

## 与组员合并

主责和接入分工见 [A 模块交接说明](A模块交接说明.md)。A 的页面通过 `HomeGateway` 读取 `HomeUiState`，不访问数据库。F 提供公共工程后，迁入 `home`、`ui` 代码和必要资源，按团队包名和版本对齐依赖。`demo` 包只服务于演示和测试。

课表、任务和数据看板目前是导航用的演示页面，分别由 B、D、F 替换为正式模块；基础数据库和同步由 E 提供，学校登录由 C 提供。

正式写操作由 D、E 补齐权威业务校验、版本冲突处理、去重和事务；当前演示接口需要按正式模型扩展版本令牌。接入异步保存时还需增加提交中状态、重复提交控制、失败保留草稿及相应回归；目前演示表单提交后关闭，失败以 Snackbar 提示。

## 构建与检查

在工程目录用 PowerShell 执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

调试 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。连接用于测试的安卓设备后，可执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

已提供 9 个 JVM 测试，以及覆盖四页导航、首次启动跳过登录、离线添加的 3 个 Compose 设备测试。实际执行结果见 `docs/验收记录.md`。

## 技术依据

- [AGP 9.4 兼容版本](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose Compiler 配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)

本工程供开发演示。学校抓取、本地数据库、真实通知、模型调用和性能目标仍按六人分工逐步实现。
