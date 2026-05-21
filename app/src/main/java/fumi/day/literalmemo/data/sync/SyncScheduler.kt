package fumi.day.literalmemo.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncProcessor: SyncProcessor,
    private val userPreferences: UserPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    fun onResume() {
        if (_isSyncing.value) return
        scope.launch { doSync() }
    }

    fun onOperationEnqueued() {
        if (_isSyncing.value) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3000)
            doSync()
        }
    }

    fun syncNow() {
        if (_isSyncing.value) return
        debounceJob?.cancel()
        scope.launch { doSync() }
    }

    private suspend fun doSync() {
        val prefs = userPreferences.userPrefs.first()
        if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) return
        if (_isSyncing.value) return

        _isSyncing.value = true
        _syncError.value = null
        try {
            val result = syncProcessor.sync(prefs.gitHubToken, prefs.gitHubRepo)
            if (result.errors.isNotEmpty()) {
                _syncError.value = result.errors.first()
            }
        } catch (e: Exception) {
            _syncError.value = e.message
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun syncAndAwait(): SyncResult? {
        val prefs = userPreferences.userPrefs.first()
        if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) return null
        if (_isSyncing.value) return null

        debounceJob?.cancel()
        _isSyncing.value = true
        _syncError.value = null
        return try {
            val result = syncProcessor.sync(prefs.gitHubToken, prefs.gitHubRepo)
            if (result.errors.isNotEmpty()) {
                _syncError.value = result.errors.first()
            }
            result
        } catch (e: Exception) {
            _syncError.value = e.message
            SyncResult(errors = listOf(e.message ?: "Unknown error"))
        } finally {
            _isSyncing.value = false
        }
    }

    fun clearLocalData() {
        File(context.filesDir, "pile").listFiles()?.forEach { it.delete() }
    }
}
