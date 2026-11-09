package com.coursekeeper.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.coursekeeper.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsStoreTest : DataTestBase() {
    @Test fun schoolAndSkippedOnboardingSurviveStoreRecreation() = runTest {
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        try {
            val first = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob), produceFile = { file }))
            assertNull(first.settings.first().schoolId)
            first.selectSchool("test-school")
            first.completeOnboarding()
            firstJob.cancel()
            firstJob.join()
            val second = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob), produceFile = { file }))
            assertEquals("test-school", second.settings.first().schoolId)
            assertTrue(second.settings.first().onboardingComplete)
            second.selectSchool(null)
            assertNull(second.settings.first().schoolId)
        } finally {
            firstJob.cancel(); secondJob.cancel(); firstJob.join(); secondJob.join()
            file.delete()
        }
    }
}
