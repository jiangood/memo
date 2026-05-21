# OpLog Sync Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-file GitHub Content API sync with Git Tree API batch commits and file-rotation-based operation tracking.

**Architecture:** Six new components (OpModel, OpLog, GitTransport, GitHubTransport, SyncQueue/SyncProcessor/SyncScheduler) replace the current `OperationLog` + per-file push logic. `GitHubSyncManager` retains its public API (`isSyncing`, `launchSync()`, `syncAndAwait()`), internally delegating to the new pipeline.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, coroutines, GitHub Git Data API, org.json

---

### Task 1: Operation data model (OpType + Operation)

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/log/OpModel.kt`

- [ ] **Step 1: Create OpModel.kt with OpType enum and Operation data class**

```kotlin
package fumi.day.literalmemo.data.log

import org.json.JSONObject
import java.util.UUID

enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String = UUID.randomUUID().toString(),
    val type: OpType,
    val path: String,
    val oldPath: String? = null,
    val time: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("path", path)
        oldPath?.let { put("oldPath", it) }
        put("time", time)
    }.toString()

    companion object {
        fun fromJson(json: String): Operation? = try {
            val obj = JSONObject(json)
            Operation(
                id = obj.getString("id"),
                type = OpType.valueOf(obj.getString("type")),
                path = obj.getString("path"),
                oldPath = obj.optString("oldPath", null).ifEmpty { null },
                time = obj.getLong("time")
            )
        } catch (e: Exception) { null }
    }
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 2: OpLog — JSONL append, file rotation, read, merge

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/log/OpLog.kt`

- [ ] **Step 1: Create OpLog.kt**

```kotlin
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
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: GitTransport interface + TreeEntry

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/git/GitTransport.kt`

- [ ] **Step 1: Create GitTransport.kt**

```kotlin
package fumi.day.literalmemo.data.git

interface GitTransport {
    suspend fun batchCommit(
        token: String,
        repo: String,
        entries: List<TreeEntry>
    ): Result<Unit>

    suspend fun listPileFiles(token: String, repo: String): Result<List<String>>
    suspend fun downloadFile(token: String, repo: String, path: String): Result<String>
}

data class TreeEntry(
    val path: String,
    val content: String?,
    val delete: Boolean = false
)
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 4: Add Git Data API methods to GitHubRepository

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmemo/data/github/GitHubRepository.kt`

- [ ] **Step 1: Add Git Data API methods after the existing `deleteFile` method**

Add these methods before the closing `}` of the class:

```kotlin
    suspend fun getRef(token: String, repo: String, ref: String = "heads/master"): Result<String> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/git/ref/$ref", token)
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    Result.success(obj.getJSONObject("object").getString("sha"))
                }
                else -> Result.failure(Exception("Failed to get ref: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createBlob(token: String, repo: String, content: String): Result<String> {
        return try {
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val bodyObj = JSONObject().apply {
                put("content", encoded)
                put("encoding", "base64")
            }
            val (code, body) = makeRequest("POST", "$baseUrl/repos/$repo/git/blobs", token, bodyObj.toString())
            when (code) {
                201 -> {
                    val obj = JSONObject(body)
                    Result.success(obj.getString("sha"))
                }
                else -> Result.failure(Exception("Failed to create blob: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createTree(
        token: String,
        repo: String,
        baseTree: String?,
        treeEntries: List<Pair<String, String?>>
    ): Result<String> {
        return try {
            val entries = JSONArray()
            for ((path, sha) in treeEntries) {
                val entry = JSONObject().apply {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    if (sha != null) put("sha", sha)
                }
                entries.put(entry)
            }
            val bodyObj = JSONObject().apply {
                put("tree", entries)
                if (baseTree != null) put("base_tree", baseTree)
            }
            val (code, body) = makeRequest("POST", "$baseUrl/repos/$repo/git/trees", token, bodyObj.toString())
            when (code) {
                201 -> {
                    val obj = JSONObject(body)
                    Result.success(obj.getString("sha"))
                }
                else -> Result.failure(Exception("Failed to create tree: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCommit(
        token: String,
        repo: String,
        message: String,
        treeSha: String,
        parentSha: String?
    ): Result<String> {
        return try {
            val bodyObj = JSONObject().apply {
                put("message", message)
                put("tree", treeSha)
                if (parentSha != null) {
                    put("parents", JSONArray(listOf(parentSha)))
                } else {
                    put("parents", JSONArray())
                }
            }
            val (code, body) = makeRequest("POST", "$baseUrl/repos/$repo/git/commits", token, bodyObj.toString())
            when (code) {
                201 -> {
                    val obj = JSONObject(body)
                    Result.success(obj.getString("sha"))
                }
                else -> Result.failure(Exception("Failed to create commit: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRef(token: String, repo: String, ref: String, sha: String): Result<Unit> {
        return try {
            val bodyObj = JSONObject().apply {
                put("sha", sha)
                put("force", false)
            }
            val (code, _) = makeRequest("PATCH", "$baseUrl/repos/$repo/git/refs/heads/$ref", token, bodyObj.toString())
            when (code) {
                200 -> Result.success(Unit)
                else -> Result.failure(Exception("Failed to update ref: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 5: GitHubTransport — Git Tree API batch commit implementation

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/github/GitHubTransport.kt`

- [ ] **Step 1: Create GitHubTransport.kt**

```kotlin
package fumi.day.literalmemo.data.github

import fumi.day.literalmemo.data.git.GitTransport
import fumi.day.literalmemo.data.git.TreeEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubTransport @Inject constructor(
    private val api: GitHubRepository
) : GitTransport {

    override suspend fun batchCommit(
        token: String,
        repo: String,
        entries: List<TreeEntry>
    ): Result<Unit> = runCatching {
        val refResult = api.getRef(token, repo)
        if (refResult.isFailure) throw refResult.exceptionOrNull()!!
        val baseSha = refResult.getOrThrow()

        val blobShas = mutableMapOf<String, String>()
        for (entry in entries) {
            if (!entry.delete && entry.content != null) {
                val blobResult = api.createBlob(token, repo, entry.content)
                if (blobResult.isFailure) throw blobResult.exceptionOrNull()!!
                blobShas[entry.path] = blobResult.getOrThrow()
            }
        }

        val treePairs = entries.map { entry ->
            if (entry.delete) {
                entry.path to null
            } else {
                entry.path to (blobShas[entry.path] ?: error("Missing blob for ${entry.path}"))
            }
        }

        val treeResult = api.createTree(token, repo, baseSha, treePairs)
        if (treeResult.isFailure) throw treeResult.exceptionOrNull()!!
        val treeSha = treeResult.getOrThrow()

        val baseRefResult = api.getRef(token, repo, "heads/master")
        if (baseRefResult.isFailure) throw baseRefResult.exceptionOrNull()!!
        val parentSha = baseRefResult.getOrThrow()

        val message = "Sync ${entries.size} change(s)"
        val commitResult = api.createCommit(token, repo, message, treeSha, parentSha)
        if (commitResult.isFailure) throw commitResult.exceptionOrNull()!!
        val commitSha = commitResult.getOrThrow()

        val refResult2 = api.updateRef(token, repo, "master", commitSha)
        if (refResult2.isFailure) throw refResult2.exceptionOrNull()!!
    }

    override suspend fun listPileFiles(token: String, repo: String): Result<List<String>> {
        return api.listPileFiles(token, repo).map { files ->
            files.map { it.path.substringAfterLast("/") }
        }
    }

    override suspend fun downloadFile(token: String, repo: String, path: String): Result<String> {
        return api.getFile(token, repo, path).map { it.content }
    }
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 6: SyncQueue — operation classification utility

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/sync/SyncQueue.kt`

- [ ] **Step 1: Create SyncQueue.kt**

```kotlin
package fumi.day.literalmemo.data.sync

import fumi.day.literalmemo.data.log.OpType
import fumi.day.literalmemo.data.log.Operation

data class SyncQueue(
    val operations: List<Operation>
) {
    val additions: List<Operation>
        get() = operations.filter { it.type == OpType.ADD || it.type == OpType.MODIFY }

    val deletions: List<Operation>
        get() = operations.filter { it.type == OpType.DELETE }

    val renames: List<Operation>
        get() = operations.filter { it.type == OpType.RENAME }
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 7: SyncProcessor — orchestrates the sync pipeline

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/sync/SyncProcessor.kt`

- [ ] **Step 1: Create SyncProcessor.kt**

```kotlin
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
            // Phase 1: Upload pending operations
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

            // Phase 2: Download remote files not present locally
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
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 8: SyncScheduler — trigger management with debounce

**Files:**
- Create: `app/src/main/java/fumi/day/literalmemo/data/sync/SyncScheduler.kt`

- [ ] **Step 1: Create SyncScheduler.kt**

```kotlin
package fumi.day.literalmemo.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 9: Refactor GitHubSyncManager to delegate to new components

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmemo/data/github/GitHubSyncManager.kt`

- [ ] **Step 1: Replace GitHubSyncManager implementation**

The new version keeps the same public API (`isSyncing`, `syncError`, `launchSync()`, `syncAndAwait()`, `clearLocalData()`), delegates to `SyncScheduler` internally:

```kotlin
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
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 10: Update MemoRepositoryImpl to use new Operation/OpLog

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmemo/data/repository/MemoRepositoryImpl.kt`

- [ ] **Step 1: Replace OperationLog references with OpLog, LogEntry with Operation**

```kotlin
package fumi.day.literalmemo.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmemo.data.log.OpLog
import fumi.day.literalmemo.data.log.OpType
import fumi.day.literalmemo.data.log.Operation
import fumi.day.literalmemo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val opLog: OpLog
) : MemoRepository {

    private val pileDir: File by lazy {
        File(context.filesDir, "pile").also { it.mkdirs() }
    }

    private fun File.toMemo(): Memo? {
        if (!exists() || !isFile || !name.endsWith(".md")) return null
        return try {
            Memo(
                fileName = name,
                content = readText(Charsets.UTF_8),
                updatedAt = lastModified()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun observeAll(): Flow<List<Memo>> = flow {
        while (true) {
            val memos = pileDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.mapNotNull { it.toMemo() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            emit(memos)
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getByFileName(fileName: String): Memo? = withContext(Dispatchers.IO) {
        File(pileDir, fileName).toMemo()
    }

    override suspend fun save(memo: Memo) = withContext(Dispatchers.IO) {
        val file = File(pileDir, memo.fileName)
        val isNew = !file.exists()
        file.writeText(memo.content, Charsets.UTF_8)
        opLog.append(Operation(
            type = if (isNew) OpType.ADD else OpType.MODIFY,
            path = "pile/${memo.fileName}"
        ))
    }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(pileDir, fileName).delete()
        opLog.append(Operation(
            type = OpType.DELETE,
            path = "pile/$fileName"
        ))
    }
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 11: Clean up obsolete code

**Files:**
- Delete: `app/src/main/java/fumi/day/literalmemo/data/log/OperationLog.kt`
- Modify: `app/src/main/java/fumi/day/literalmemo/data/git/GitForge.kt`
- Modify: `app/src/main/java/fumi/day/literalmemo/data/prefs/UserPreferences.kt`

- [ ] **Step 1: Delete OperationLog.kt**

```bash
rm app/src/main/java/fumi/day/literalmemo/data/log/OperationLog.kt
```

- [ ] **Step 2: Remove moveToTrash default method from GitForgeApi**

```kotlin
package fumi.day.literalmemo.data.git

data class RemoteFile(
    val path: String,
    val sha: String,
    val content: String = ""
)

interface GitForgeApi {
    suspend fun listPileFiles(token: String, repo: String): Result<List<RemoteFile>>
    suspend fun listTrashFiles(token: String, repo: String): Result<List<RemoteFile>>
    suspend fun getFile(token: String, repo: String, path: String): Result<RemoteFile>
    suspend fun putFile(token: String, repo: String, path: String, content: String, sha: String? = null, message: String = "Update $path"): Result<RemoteFile>
    suspend fun deleteFile(token: String, repo: String, path: String, sha: String, message: String = "Delete $path"): Result<Unit>
}
```

- [ ] **Step 3: Remove lastSyncedShas from UserPreferences**

Remove from `UserPrefs` data class:
```kotlin
data class UserPrefs(
    val gitHubEnabled: Boolean = false,
    val gitHubToken: String = "",
    val gitHubRepo: String = "",
    val lastSyncedAt: Long? = null
)
```

Remove `Keys.LAST_SYNCED_SHAS`, `parseShas`, `setLastSyncedShas`. Update the `combine` flow, `resetSyncState()`, and `clearGitHubConfig()`:

```kotlin
package fumi.day.literalmemo.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPrefs(
    val gitHubEnabled: Boolean = false,
    val gitHubToken: String = "",
    val gitHubRepo: String = "",
    val lastSyncedAt: Long? = null
)

@Singleton
class UserPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val encryptedPrefs: SharedPreferences = run {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _gitHubToken = MutableStateFlow(
        encryptedPrefs.getString("github_token", "") ?: ""
    )

    private object Keys {
        val GITHUB_ENABLED = booleanPreferencesKey("github_enabled")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }

    val userPrefs: Flow<UserPrefs> = combine(
        context.dataStore.data,
        _gitHubToken
    ) { prefs, token ->
        UserPrefs(
            gitHubEnabled = prefs[Keys.GITHUB_ENABLED] ?: false,
            gitHubToken = token,
            gitHubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            lastSyncedAt = prefs[Keys.LAST_SYNCED_AT]
        )
    }

    suspend fun setGitConfig(enabled: Boolean, token: String, repo: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GITHUB_ENABLED] = enabled
            prefs[Keys.GITHUB_REPO] = repo
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString("github_token", token).apply()
        }
        _gitHubToken.value = token
    }

    suspend fun resetSyncState() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_SYNCED_AT)
        }
    }

    suspend fun setLastSyncedAt(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNCED_AT] = timestamp
        }
    }

    suspend fun clearGitHubConfig() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GITHUB_ENABLED] = false
            prefs[Keys.GITHUB_REPO] = ""
            prefs.remove(Keys.LAST_SYNCED_AT)
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().remove("github_token").apply()
        }
        _gitHubToken.value = ""
    }
}
```

- [ ] **Step 4: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 12: Update DI module (bind GitTransport)

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmemo/di/AppModule.kt`

- [ ] **Step 1: Add GitTransport binding**

```kotlin
package fumi.day.literalmemo.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fumi.day.literalmemo.data.git.GitTransport
import fumi.day.literalmemo.data.github.GitHubTransport
import fumi.day.literalmemo.data.repository.MemoRepository
import fumi.day.literalmemo.data.repository.MemoRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindMemoRepository(impl: MemoRepositoryImpl): MemoRepository

    @Binds
    abstract fun bindGitTransport(impl: GitHubTransport): GitTransport
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 13: Migration from old operation_log.jsonl

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmemo/data/log/OpLog.kt`

- [ ] **Step 1: Add `migrateFromOldLog()` to OpLog.kt**

```kotlin
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
```

- [ ] **Step 2: Call migration at start of SyncProcessor.sync()**

Add at the top of `sync()` body in `SyncProcessor.kt`:
```kotlin
            // One-time migration from old format
            opLog.migrateFromOldLog()

- [ ] **Step 2: Verify compiles**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

---

### Task 14: Full compilation verification

- [ ] **Step 1: Clean build**

```bash
./gradlew clean assembleDebug 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL in Xs`

- [ ] **Step 2: Run existing unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | tail -20
```

Expected: All existing tests pass (the ExampleUnitTest should still pass).
