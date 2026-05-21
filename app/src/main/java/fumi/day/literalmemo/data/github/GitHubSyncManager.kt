package fumi.day.literalmemo.data.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.git.GitTransport
import fumi.day.literalmemo.data.git.TreeEntry
import fumi.day.literalmemo.data.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val trashed: Int = 0,
    val errors: List<String> = emptyList()
)

@Singleton
class GitHubSyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gitHubRepository: GitHubRepository,
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val pileDir: File by lazy {
        File(context.filesDir, "pile").also { it.mkdirs() }
    }

    private val trashDir: File by lazy {
        File(context.filesDir, "trash").also { it.mkdirs() }
    }

    fun launchSync() {
        if (_isSyncing.value) return
        appScope.launch { syncAndAwait() }
    }

    suspend fun syncAndAwait(): SyncResult? {
        if (_isSyncing.value) return null
        _isSyncing.value = true
        _syncError.value = null
        return try {
            val result = syncIfEnabled()
            if (result != null && result.errors.isNotEmpty()) {
                _syncError.value = result.errors.first()
            }
            result
        } finally {
            _isSyncing.value = false
        }
    }

    fun clearLocalData() {
        pileDir.listFiles()?.forEach { it.delete() }
        trashDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun syncIfEnabled(): SyncResult? = withContext(Dispatchers.IO) {
        val prefs = userPreferences.userPrefs.first()
        if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) {
            return@withContext null
        }
        val result = sync(prefs.gitHubToken, prefs.gitHubRepo)
        if (result.errors.isEmpty()) {
            userPreferences.setLastSyncedAt(System.currentTimeMillis())
        }
        result
    }

    private suspend fun sync(token: String, repo: String): SyncResult = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        var trashed = 0
        val errors = mutableListOf<String>()

        try {
            val remotePileResult = gitHubRepository.listPileFiles(token, repo)
            if (remotePileResult.isFailure) {
                return@withContext SyncResult(errors = listOf("Failed to list remote files"))
            }
            val remotePile = remotePileResult.getOrThrow().associateBy { it.path.substringAfterLast("/") }
            val remoteTrashNames = (gitHubRepository.listTrashFiles(token, repo).getOrNull() ?: emptyList())
                .map { it.path.substringAfterLast("/") }.toSet()

            for (name in remoteTrashNames) {
                File(pileDir, name).delete()
            }

            val treeEntries = mutableListOf<TreeEntry>()

            for (trashFile in trashDir.listFiles() ?: emptyList()) {
                if (!trashFile.name.endsWith(".md")) continue
                treeEntries.add(TreeEntry(path = "pile/${trashFile.name}", content = null, delete = true))
            }

            val localNames = pileDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.map { it.name }
                ?.toSet() ?: emptySet()

            val toUpload = localNames - remotePile.keys - remoteTrashNames
            for (name in toUpload) {
                val file = File(pileDir, name)
                treeEntries.add(TreeEntry(path = "pile/$name", content = file.readText(Charsets.UTF_8)))
            }

            if (treeEntries.isNotEmpty()) {
                val commitResult = gitTransport.batchCommit(token, repo, treeEntries)
                if (commitResult.isSuccess) {
                    val trashCount = trashDir.listFiles()?.count { it.name.endsWith(".md") } ?: 0
                    trashDir.listFiles()?.forEach { it.delete() }
                    uploaded = toUpload.size
                    trashed = trashCount
                } else {
                    errors.add("Upload failed: ${commitResult.exceptionOrNull()?.message}")
                }
            }

            for (name in remotePile.keys - localNames) {
                val remoteFile = remotePile[name] ?: continue
                val contentResult = gitHubRepository.getFile(token, repo, remoteFile.path)
                if (contentResult.isSuccess) {
                    File(pileDir, name).writeText(contentResult.getOrThrow().content, Charsets.UTF_8)
                    downloaded++
                } else {
                    errors.add("Download $name failed: ${contentResult.exceptionOrNull()?.message}")
                }
            }
        } catch (e: Exception) {
            errors.add("Sync failed: ${e.message}")
        }

        SyncResult(uploaded = uploaded, downloaded = downloaded, trashed = trashed, errors = errors)
    }
}
