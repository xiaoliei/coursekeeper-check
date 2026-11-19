package com.coursekeeper.school

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.coursekeeper.data.CourseKeeperData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 学校适配器进程级单例（D 复核 2026-11-15：启动崩溃修复）。
 * HomeDataOwner 随 Activity 重建，但传给 syncCoordinator 的实例必须始终相同，
 * 否则撞上 CourseKeeperData 的 require 直接崩溃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SchoolAdapterHolderTest {

    @Test
    fun returnsSameInstanceAcrossRecreations() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertSame(SchoolAdapterHolder.get(application), SchoolAdapterHolder.get(application))
    }

    /**
     * 真实崩溃路径的回归：数据层先绑定一个适配器，ViewModel 重建后再通过 Holder
     * 取一次并交给 syncCoordinator——修复前这里拿到的是新实例，require 直接抛。
     */
    @Test
    fun recreatedOwnerCanRebindCoordinatorsWithHolderInstance() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val data = CourseKeeperData.get(application)

        val firstOwnerAdapter = SchoolAdapterHolder.get(application)
        data.syncCoordinator(firstOwnerAdapter)

        val recreatedOwnerAdapter = SchoolAdapterHolder.get(application)
        assertSame(firstOwnerAdapter, recreatedOwnerAdapter)
        assertSame(
            data.syncCoordinator(firstOwnerAdapter),
            data.syncCoordinator(recreatedOwnerAdapter),
        )
    }

    /** 反向保障：绕过 Holder 自建实例仍会被数据层拒绝，说明 Holder 是唯一正确入口。 */
    @Test
    fun bypassingHolderWouldViolateTheInvariant() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val data = CourseKeeperData.get(application)
        data.syncCoordinator(SchoolAdapterHolder.get(application))

        val foreign = WebViewSchoolAdapter(
            schoolProvider = { SchoolCatalog.default },
            courseParser = SchoolCatalog.courseParser,
            taskParser = SchoolCatalog.taskParser,
        )
        val rejected = runCatching { data.syncCoordinator(foreign) }.exceptionOrNull()
        assertTrue("自建实例必须被数据层拒绝", rejected is IllegalArgumentException)
        assertFalse("拒绝信息不应为空", rejected?.message.isNullOrBlank())
    }

    // ---- C-02（2026-11-17）：原始学校页面只允许在可调试构建落盘 ----

    @Test
    fun debugDumpDirIsOnlyCreatedForDebuggableBuilds() {
        val base = java.io.File("build/tmp/debug-base")

        assertEquals("sync-debug", debugDumpDir(base, debuggable = true)?.name)
        assertNull("release 包不得保存含学号的原始页面", debugDumpDir(base, debuggable = false))
        assertNull("拿不到基础目录时不落盘", debugDumpDir(null, debuggable = true))
    }

    @Test
    fun debugDumpDirForFollowsApplicationDebugFlag() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val info = application.applicationInfo
        val original = info.flags
        try {
            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            assertNotNull(debugDumpDirFor(application))

            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertNull(debugDumpDirFor(application))
        } finally {
            info.flags = original
        }
    }
}
