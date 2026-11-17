package com.coursekeeper.school

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.coursekeeper.data.CourseKeeperData
import org.junit.Assert.assertFalse
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
}
