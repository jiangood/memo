package fumi.day.literalmemo.data.github

import fumi.day.literalmemo.data.log.OpLog
import fumi.day.literalmemo.data.sync.SyncResult
import fumi.day.literalmemo.data.sync.SyncScheduler
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubSyncManager @Inject constructor(
    private val syncScheduler: SyncScheduler,
    private val opLog: OpLog
) {
    val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing
    val syncError: StateFlow<String?> = syncScheduler.syncError

    fun launchSync() {
        syncScheduler.syncNow()
    }

    suspend fun syncAndAwait(): SyncResult? {
        return syncScheduler.syncAndAwait()
    }

    fun clearLocalData() {
        syncScheduler.clearLocalData()
    }
}
