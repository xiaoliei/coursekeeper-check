package com.coursekeeper.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.courseKeeperSettings by preferencesDataStore(name = "coursekeeper_settings")

data class AppSettings(val schoolId: String? = null, val onboardingComplete: Boolean = false)

/** Ordinary preferences only. No school credentials, API keys or session cookies. */
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.courseKeeperSettings)

    val settings = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { AppSettings(it[SCHOOL], it[ONBOARDING] ?: false) }

    suspend fun selectSchool(schoolId: String?) {
        require(schoolId == null || schoolId.isNotBlank())
        store.edit { if (schoolId == null) it.remove(SCHOOL) else it[SCHOOL] = schoolId }
    }

    suspend fun completeOnboarding() { store.edit { it[ONBOARDING] = true } }

    private companion object {
        val SCHOOL = stringPreferencesKey("school_id")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
    }
}
