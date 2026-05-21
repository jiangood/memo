package fumi.day.literalmemo.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val op: String,
    val fileName: String,
    val content: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("op", op)
        put("file", fileName)
        if (content != null) put("content", content)
        put("ts", timestamp)
        if (synced) put("synced", true)
    }.toString()

    companion object {
        fun fromJson(json: String): LogEntry? = try {
            val obj = JSONObject(json)
            LogEntry(
                id = obj.getString("id"),
                op = obj.getString("op"),
                fileName = obj.getString("file"),
                content = if (obj.has("content")) obj.getString("content") else null,
                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                synced = obj.optBoolean("synced", false)
            )
        } catch (e: Exception) { null }
    }
}

@Singleton
class OperationLog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val logFile: File by lazy {
        File(context.filesDir, "operation_log.jsonl")
    }
    private val lock = Any()

    suspend fun append(entry: LogEntry) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            logFile.appendText(entry.toJson() + "\n", Charsets.UTF_8)
        }
    }

    suspend fun unsynced(): List<LogEntry> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!logFile.exists()) return@withContext emptyList()
            logFile.readLines(Charsets.UTF_8)
                .mapNotNull { LogEntry.fromJson(it.trim()) }
                .filter { !it.synced }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            logFile.writeText("", Charsets.UTF_8)
        }
    }

    suspend fun markSynced(ids: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!logFile.exists() || ids.isEmpty()) return@withContext
            val entries = logFile.readLines(Charsets.UTF_8)
                .mapNotNull { LogEntry.fromJson(it.trim()) }
            val updated = entries.map { entry ->
                if (entry.id in ids) entry.copy(synced = true) else entry
            }
            logFile.writeText(updated.joinToString("\n") { it.toJson() } + "\n", Charsets.UTF_8)
        }
    }
}
