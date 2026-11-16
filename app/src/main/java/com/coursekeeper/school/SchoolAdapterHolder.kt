package com.coursekeeper.school

import android.app.Application
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
                debugDumpDir = File(application.getExternalFilesDir(null) ?: application.filesDir, "sync-debug"),
            ).also { instance = it }
        }
}
