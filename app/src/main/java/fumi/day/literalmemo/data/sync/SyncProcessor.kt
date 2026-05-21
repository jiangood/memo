package fumi.day.literalmemo.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.git.GitTransport
import fumi.day.literalmemo.data.git.TreeEntry
import fumi.day.literalmemo.data.log.OpLog
import fumi.day.literalmemo.data.log.OpType
import fumi.day.literalmemo.data.log.Operation
import fumi.day.literalmemo.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val errors: List<String> = emptyList()
)

@Singleton
class SyncProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opLog: OpLog,
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences
) {
    private val pileDir: File by lazy {
        File(context.filesDir, "pile").also { it.mkdirs() }
    }

    suspend fun sync(token: String, repo: String): SyncResult = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        val errors = mutableListOf<String>()

        try {
            opLog.migrateFromOldLog()

            val pendingFile = opLog.rotate()
            if (pendingFile != null) {
                val operations = opLog.readPending(pendingFile)
                if (operations.isNotEmpty()) {
                    val entries = operations.flatMap { op ->
                        when (op.type) {
                            OpType.ADD, OpType.MODIFY -> {
                                val localFile = File(pileDir, op.path.substringAfter("pile/"))
                                if (localFile.exists()) {
                                    listOf(TreeEntry(path = op.path, content = localFile.readText(Charsets.UTF_8)))
                                } else emptyList()
                            }
                            OpType.DELETE -> {
                                listOf(TreeEntry(path = op.path, content = null, delete = true))
                            }
                            OpType.RENAME -> {
                                val localFile = File(pileDir, op.path.substringAfter("pile/"))
                                buildList {
                                    if (op.oldPath != null) {
                                        add(TreeEntry(path = op.oldPath, content = null, delete = true))
                                    }
                                    if (localFile.exists()) {
                                        add(TreeEntry(path = op.path, content = localFile.readText(Charsets.UTF_8)))
                                    }
                                }
                            }
                        }
                    }

                    val commitResult = gitTransport.batchCommit(token, repo, entries)
                    if (commitResult.isSuccess) {
                        uploaded = operations.size
                        opLog.discardPending(pendingFile)
                    } else {
                        opLog.mergePending(pendingFile)
                        errors.add("Upload failed: ${commitResult.exceptionOrNull()?.message}")
                    }
                } else {
                    opLog.discardPending(pendingFile)
                }
            }

            val remoteResult = gitTransport.listPileFiles(token, repo)
            if (remoteResult.isSuccess) {
                val remoteFiles = remoteResult.getOrThrow().toSet()
                val localFiles = pileDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".md") }
                    ?.map { it.name }
                    ?.toSet() ?: emptySet()

                for (fileName in remoteFiles - localFiles) {
                    val contentResult = gitTransport.downloadFile(token, repo, "pile/$fileName")
                    if (contentResult.isSuccess) {
                        File(pileDir, fileName).writeText(contentResult.getOrThrow(), Charsets.UTF_8)
                        downloaded++
                    } else {
                        errors.add("Download $fileName failed: ${contentResult.exceptionOrNull()?.message}")
                    }
                }
            }

            if (errors.isEmpty()) {
                userPreferences.setLastSyncedAt(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            errors.add("Sync failed: ${e.message}")
        }

        SyncResult(uploaded = uploaded, downloaded = downloaded, errors = errors)
    }
}
