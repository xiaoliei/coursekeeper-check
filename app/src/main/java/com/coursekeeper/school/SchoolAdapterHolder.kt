package com.coursekeeper.school

import android.app.Application
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * 应用级唯一的学校适配器实例（D 复核 2026-11-15：启动崩溃修复）。
 *
 * `CourseKeeperData.syncCoordinator` 要求传入的适配器实例在整个应用生命周期内唯一；
 * HomeDataOwner（ViewModel）随 Activity 销毁重建，返回键退出后进程仍缓存着数据层单例，
 * 此时再进 App 若每次新建适配器就会撞上 `require(boundAdapter === adapter)` 直接崩溃。
 * 因此在这里按进程持有单例：拿到的永远是同一个实例，重复进入 App 不再冲突。
 */
object SchoolAdapterHolder {
    @Volatile private var instance: WebViewSchoolAdapter? = null

    fun get(application: Application): WebViewSchoolAdapter =
        instance ?: synchronized(this) {
            instance ?: WebViewSchoolAdapter(
                schoolProvider = { SchoolCatalog.default },
                courseParser = SchoolCatalog.courseParser,
                taskParser = SchoolCatalog.taskParser,
                debugDumpDir = debugDumpDirFor(application),
            ).also { instance = it }
        }
}

/**
 * 原始学校页面落盘目录（C-02，2026-11-17）。
 *
 * 抓取页含个人课表、作业和学号，**只能在可调试构建里落盘**；正式发布包默认不保存。
 * 目录与基础路径分开传参，便于单测覆盖两种构建形态，不依赖 Robolectric 的 debuggable 默认值。
 */
internal fun debugDumpDirFor(application: Application): File? {
    val debuggable =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val base = application.getExternalFilesDir(null) ?: application.filesDir
    return debugDumpDir(base, debuggable)
}

/** 纯函数：可调试且拿到基础目录时才启用；release 包恒为 null。 */
internal fun debugDumpDir(base: File?, debuggable: Boolean): File? =
    if (debuggable && base != null) File(base, "sync-debug") else null
