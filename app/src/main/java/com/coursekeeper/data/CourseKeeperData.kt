package com.coursekeeper.data

import android.content.Context
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.settings.SettingsStore
import com.coursekeeper.data.sync.SchoolAdapter
import com.coursekeeper.data.sync.SyncCoordinator

/** Shared integration entry for A/B/C/D/F; constructed without network requests or model setup. */
class CourseKeeperData private constructor(context: Context) {
    private val database = CourseKeeperDatabase.open(context)
    val repository = CourseKeeperRepository(database)
    val settings = SettingsStore(context)
    private var sync: SyncCoordinator? = null
    private var boundAdapter: SchoolAdapter? = null

    @Synchronized
    fun syncCoordinator(adapter: SchoolAdapter): SyncCoordinator {
        require(boundAdapter == null || boundAdapter === adapter) { "Reuse the application-scoped SchoolAdapter" }
        return sync ?: SyncCoordinator(repository, adapter).also { sync = it; boundAdapter = adapter }
    }

    companion object {
        @Volatile private var instance: CourseKeeperData? = null

        fun get(context: Context): CourseKeeperData = instance ?: synchronized(this) {
            instance ?: CourseKeeperData(context.applicationContext).also { instance = it }
        }
    }
}
