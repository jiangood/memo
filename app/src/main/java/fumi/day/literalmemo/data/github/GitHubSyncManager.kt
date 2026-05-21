package fumi.day.literalmemo.data.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.git.GitForgeApi
import fumi.day.literalmemo.data.log.OperationLog
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
    val errors: List<String> = emptyList(),
    val remoteShas: Map<String, String> = emptyMap()
)

@Singleton
class GitHubSyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gitHubRepository: GitHubRepository,
    private val userPreferences: UserPreferences,
    private val operationLog: OperationLog
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

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

    private val pileDir: File by lazy {
        File(context.filesDir, "pile").also { it.mkdirs() }
    }

    fun clearLocalData() {
        pileDir.listFiles()?.forEach { it.delete() }
        appScope.launch { operationLog.clear() }
    }

    suspend fun syncIfEnabled(): SyncResult? = withContext(Dispatchers.IO) {
        val prefs = userPreferences.userPrefs.first()
        if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) {
            return@withContext null
        }

        val result = sync(gitHubRepository, prefs.gitHubToken, prefs.gitHubRepo)
        if (result.errors.isEmpty()) {
            userPreferences.setLastSyncedAt(System.currentTimeMillis())
            userPreferences.setLastSyncedShas(result.remoteShas)
        }
        result
    }

    suspend fun sync(api: GitForgeApi, token: String, repo: String): SyncResult = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val errors = mutableListOf<String>()
        val newRemoteShas = mutableMapOf<String, String>()

        try {
            val unsyncedOps = operationLog.unsynced()
            val handledFiles = mutableSetOf<String>()
            val succeededOpIds = mutableSetOf<String>()

            val remotePileResult = api.listPileFiles(token, repo)
            if (remotePileResult.isFailure) {
                return@withContext SyncResult(errors = listOf("Failed to connect"))
            }
            val remotePileMap = remotePileResult.getOrThrow().associateBy { it.path.substringAfterLast("/") }
            val remoteTrashMap = (api.listTrashFiles(token, repo).getOrNull() ?: emptyList())
                .associateBy { it.path.substringAfterLast("/") }

            for (op in unsyncedOps) {
                handledFiles.add(op.fileName)
                try {
                    when (op.op) {
                        "CREATE", "UPDATE" -> {
                            val content = op.content
                            if (content.isNullOrBlank()) continue
                            val remoteFile = remotePileMap[op.fileName]
                            val sha = remoteFile?.sha
                            val message = if (sha == null) "Create ${op.fileName}" else "Update ${op.fileName}"
                            val result = api.putFile(token, repo, "pile/${op.fileName}", content, sha, message)
                            result.onSuccess {
                                uploaded++
                                newRemoteShas[op.fileName] = it.sha
                                succeededOpIds.add(op.id)
                            }.onFailure {
                                errors.add("${op.op} ${op.fileName} failed: ${it.message}")
                            }
                        }
                        "DELETE" -> {
                            val remoteFile = remotePileMap[op.fileName]
                            if (remoteFile != null) {
                                val contentResult = api.getFile(token, repo, remoteFile.path)
                                if (contentResult.isSuccess) {
                                    val content = contentResult.getOrThrow().content
                                    api.moveToTrash(token, repo, op.fileName, remoteFile.sha, content)
                                        .onSuccess { succeededOpIds.add(op.id) }
                                        .onFailure { errors.add("Trash ${op.fileName} failed: ${it.message}") }
                                } else {
                                    contentResult.exceptionOrNull()?.let { errors.add("Get ${op.fileName} for trash failed: ${it.message}") }
                                }
                            } else {
                                succeededOpIds.add(op.id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Op ${op.id} ${op.op} ${op.fileName} failed: ${e.message}")
                }
            }

            for (fileName in remoteTrashMap.keys) {
                if (fileName in handledFiles) continue
                handledFiles.add(fileName)
                val localFile = File(pileDir, fileName)
                if (localFile.exists()) {
                    localFile.delete()
                }
            }

            for ((fileName, remoteFile) in remotePileMap) {
                if (fileName in handledFiles) continue
                handledFiles.add(fileName)

                val localFile = File(pileDir, fileName)
                when {
                    !localFile.exists() -> {
                        val contentResult = api.getFile(token, repo, remoteFile.path)
                        contentResult.onSuccess {
                            localFile.writeText(it.content, Charsets.UTF_8)
                            newRemoteShas[fileName] = remoteFile.sha
                            downloaded++
                        }.onFailure {
                            errors.add("Download $fileName failed: ${it.message}")
                        }
                    }
                    else -> {
                        val localContent = localFile.readText(Charsets.UTF_8)
                        val contentResult = api.getFile(token, repo, remoteFile.path)
                        if (contentResult.isSuccess) {
                            val remoteContent = contentResult.getOrThrow().content
                            if (localContent != remoteContent) {
                                localFile.writeText(remoteContent, Charsets.UTF_8)
                                downloaded++
                            }
                            newRemoteShas[fileName] = remoteFile.sha
                        }
                    }
                }
            }

            val localFiles = pileDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.associateBy { it.name } ?: emptyMap()

            for ((fileName, localFile) in localFiles) {
                if (fileName in handledFiles) continue
                val content = localFile.readText(Charsets.UTF_8)
                val result = api.putFile(token, repo, "pile/$fileName", content, message = "Add $fileName")
                result.onSuccess {
                    uploaded++
                    newRemoteShas[fileName] = it.sha
                }.onFailure {
                    errors.add("Upload $fileName failed: ${it.message}")
                }
            }

            operationLog.markSynced(succeededOpIds)

        } catch (e: Exception) {
            errors.add("Sync failed: ${e.message}")
        }

        SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            errors = errors,
            remoteShas = newRemoteShas
        )
    }
}
