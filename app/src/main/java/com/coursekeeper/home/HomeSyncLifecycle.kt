package com.coursekeeper.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.coursekeeper.school.LoginStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** WebView can remain the saved step after its dialog is dismissed. It does not own a sync job. */
internal fun schoolImportInProgress(step: LoginStep): Boolean =
    step is LoginStep.Feedback || step is LoginStep.Importing

/** Observe each foreground entry, and re-enable sync when a school connection becomes available. */
internal suspend fun observeHomeSync(
    lifecycle: Lifecycle,
    allowed: Flow<Boolean>,
    onForeground: () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        allowed.distinctUntilChanged().collect { enabled ->
            if (enabled) onForeground()
        }
    }
}
