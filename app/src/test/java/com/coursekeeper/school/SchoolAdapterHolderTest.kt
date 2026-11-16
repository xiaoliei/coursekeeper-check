package com.coursekeeper.school

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
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
}
