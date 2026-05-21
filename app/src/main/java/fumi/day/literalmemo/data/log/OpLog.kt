package fumi.day.literalmemo.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpLog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val logFile: File by lazy { File(context.filesDir, "oplog.jsonl") }
    private val lock = Any()

    suspend fun append(operation: Operation) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            logFile.appendText(operation.toJson() + "\n", Charsets.UTF_8)
        }
    }

    suspend fun rotate(): File? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!logFile.exists() || logFile.length() == 0L) return@withContext null
            val pending = File(context.filesDir, "oplog.pending")
            logFile.renameTo(pending)
            logFile.writeText("", Charsets.UTF_8)
            pending
        }
    }

    suspend fun readPending(file: File): List<Operation> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines(Charsets.UTF_8).mapNotNull { Operation.fromJson(it.trim()) }
    }

    suspend fun discardPending(file: File) = withContext(Dispatchers.IO) {
        file.delete()
    }

    suspend fun mergePending(file: File) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!file.exists()) return@withContext
            val pendingContent = file.readText(Charsets.UTF_8)
            val currentContent = if (logFile.exists()) logFile.readText(Charsets.UTF_8) else ""
            logFile.writeText(pendingContent + currentContent, Charsets.UTF_8)
            file.delete()
        }
    }

    suspend fun readUnsynced(): List<Operation> = withContext(Dispatchers.IO) {
        if (!logFile.exists()) return@withContext emptyList()
        logFile.readLines(Charsets.UTF_8).mapNotNull { Operation.fromJson(it.trim()) }
    }

    suspend fun migrateFromOldLog() = withContext(Dispatchers.IO) {
        val oldFile = File(context.filesDir, "operation_log.jsonl")
        if (!oldFile.exists()) return@withContext
        synchronized(lock) {
            val entries = oldFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                try {
                    val obj = org.json.JSONObject(line)
                    val op = obj.getString("op")
                    val fileName = obj.getString("file")
                    val synced = obj.optBoolean("synced", false)
                    if (synced) null else {
                        val type = when (op) {
                            "CREATE" -> OpType.ADD
                            "UPDATE" -> OpType.MODIFY
                            "DELETE" -> OpType.DELETE
                            else -> return@mapNotNull null
                        }
                        Operation(
                            id = obj.getString("id"),
                            type = type,
                            path = "pile/$fileName",
                            time = obj.optLong("ts", System.currentTimeMillis())
                        )
                    }
                } catch (e: Exception) { null }
            }
            if (entries.isNotEmpty()) {
                val content = entries.joinToString("\n") { it.toJson() } + "\n"
                logFile.appendText(content, Charsets.UTF_8)
            }
            oldFile.delete()
        }
    }
}
