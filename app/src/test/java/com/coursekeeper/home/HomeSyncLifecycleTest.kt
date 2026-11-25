package com.coursekeeper.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.coursekeeper.school.LoginStep
import com.coursekeeper.school.SchoolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSyncLifecycleTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun dismissedWebViewStepDoesNotDisableHomeRefresh() {
        val school = SchoolCatalog.default
        assertEquals(false, schoolImportInProgress(LoginStep.WebView(school)))
        assertEquals(false, schoolImportInProgress(LoginStep.SelectSchool))
        assertEquals(true, schoolImportInProgress(LoginStep.Feedback(school)))
        assertEquals(true, schoolImportInProgress(LoginStep.Importing(school)))
    }

    @Test fun eachResumeSyncsOnceAndPauseDoesNotStartNetworkWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val allowed = MutableStateFlow(true)
            var requests = 0
            backgroundScope.launch { observeHomeSync(owner.lifecycle, allowed) { requests++ } }
            runCurrent()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(0, requests)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, requests)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            allowed.value = false
            allowed.value = true
            runCurrent()
            assertEquals(1, requests)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(2, requests)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun connectionChangesWhileResumedStartSyncAndLogoutDisablesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val allowed = MutableStateFlow(false)
            var requests = 0
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            backgroundScope.launch { observeHomeSync(owner.lifecycle, allowed) { requests++ } }
            runCurrent()
            assertEquals(0, requests)
            allowed.value = true
            runCurrent()
            allowed.value = true
            runCurrent()
            assertEquals(1, requests)
            allowed.value = false
            runCurrent()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, requests)
            allowed.value = true
            runCurrent()
            assertEquals(2, requests)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
