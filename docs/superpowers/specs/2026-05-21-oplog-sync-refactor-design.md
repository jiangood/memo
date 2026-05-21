# OpLog Sync Refactor Design

**Date**: 2026-05-21
**Status**: Draft

## Goal

Refactor the current sync system (per-file GitHub Content API + `synced` flag model) to an operation-log-based sync using Git Tree API batch commits, inspired by the existing `OperationLog` JSONL pattern.

## Current vs Proposed

| Aspect | Current | Proposed |
|---|---|---|
| Op types | `"CREATE"`/`"UPDATE"`/`"DELETE"` strings | `ADD`/`DELETE`/`RENAME`/`MODIFY` enum |
| Sync method | Per-file `PUT`/`DELETE` Content API | Git Tree API batch commit |
| Op tracking | `synced` flag in JSONL | File rotation (`oplog.jsonl` → `oplog.pending`) |
| Sync triggers | Immediate on launch | Debounced 3s + immediate on manual |
| Pull strategy | Full diff (list + compare + download) | Same (kept as-is) |
| Architecture | Monolithic `GitHubSyncManager` | Split: OpLog, SyncQueue, SyncScheduler, SyncProcessor |

## Retained from Current

- `filesDir/pile/` and `filesDir/trash/` directory layout
- JSON Lines format for operation log
- `GitForgeApi` interface (extended with `batchCommit`)
- `GitHubRepository` HTTP client (extended with Tree API)
- `UserPreferences` DataStore + EncryptedSharedPreferences
- `MemoRepositoryImpl` as the enqueue point
- Two-way sync: push local changes, then pull remote changes
- First-to-sync-wins conflict resolution
- All existing Dagger/Hilt DI wiring
- ViewModel integration points (launchSync, isSyncing, syncError)

## Operation Log (OpLog)

### Storage

- **File**: `filesDir/oplog.jsonl`
- **Format**: JSON Lines, append-only, one operation per line
- **Operations**: ADD, DELETE, RENAME, MODIFY

```jsonl
{"id":"a1b2c3","type":"ADD","path":"pile/20260521_100000.md","time":1000}
{"id":"d4e5f6","type":"DELETE","path":"pile/old.md","time":1001}
{"id":"g7h8i9","type":"RENAME","path":"pile/new.md","oldPath":"pile/old-old.md","time":1002}
{"id":"j0k1l2","type":"MODIFY","path":"pile/20260521_100000.md","time":1003}
```

- `id`: UUID, unique operation identifier
- `type`: ADD / DELETE / RENAME / MODIFY
- `path`: relative path within repo (e.g. `pile/file.md`)
- `oldPath`: RENAME only — the source path before rename
- `time`: epoch millis of the operation

### Data Model

```kotlin
enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String,        // UUID
    val type: OpType,
    val path: String,
    val oldPath: String?,  // only for RENAME
    val time: Long
)
```

Note: `content` is **not** stored in operations. On sync, the content is read from the local file at processing time. This avoids stale content in the log and keeps entries small.

### Writing

Operations are appended via `FileOutputStream(append=true)` + `BufferedWriter`, flushed after each write. All writes happen on `Dispatchers.IO`.

### File Rotation

The rotation pattern replaces the current `synced`-flag approach:

```
1. Rename oplog.jsonl → oplog.pending
2. Create new empty oplog.jsonl
3. Process oplog.pending entries
4. On success → delete oplog.pending
5. On failure → prepend oplog.pending content back to oplog.jsonl
```

This guarantees:
- Operations generated **during** sync are written to the new empty log, not lost
- Failed syncs are retried on next attempt
- No need to rewrite the file to update `synced` flags

### OpLog.kt (new)

```kotlin
@Singleton
class OpLog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun append(operation: Operation)
    suspend fun rotate(): File?            // rename log → pending, return pending file
    suspend fun readPending(file: File): List<Operation>
    suspend fun discardPending(file: File) // delete after successful sync
    suspend fun mergePending(file: File)   // prepend pending back on failure
    suspend fun readUnsynced(): List<Operation>  // for UI display only
}
```

### Enqueue Points

| Operation | Trigger | Current Equivalent |
|---|---|---|
| ADD | File created in `pile/` | `save()` with `isNew` |
| DELETE | File deleted from `pile/` | `delete()` |
| RENAME | File renamed within `pile/` | Not currently supported |
| MODIFY | File content changed | `save()` with existing file |

## Sync Flow

### Upload Direction (Local → Remote)

```
1. rotate() → oplog.pending
2. readPending() → List<Operation>
3. If empty → delete pending, return
4. For each operation, resolve current file content:
   ADD    → read local file → create git blob
   DELETE → no blob needed
   RENAME → read local file (at new path) → create git blob
   MODIFY → read local file → create git blob
5. POST /git/trees with base tree SHA + all changes
6. POST /git/commits
7. PATCH /git/refs/heads/master
8. On success → discardPending()
9. On failure → mergePending()
```

Key differences from current:
- Single commit for all pending operations instead of N API calls
- Content read at sync time from local filesystem
- No `synced` field management

### Download Direction (Remote → Local)

After upload completes (unchanged from current):

```
1. List remote files (GET /repos/{owner}/{repo}/contents/pile)
2. List local files (filesDir/pile/)
3. For each remote file not present locally → download
4. For each local file not present remotely → already removed, no action
```

Downloaded files are not recorded in OpLog (sync reconciliation, not user operations).

### Sync Triggers

| Trigger | Behavior |
|---|---|
| App onResume | If sync not already running, `launchSync()` |
| Operation enqueued | Delay 3s debounce timer, then start |
| "Sync Now" button | Cancel debounce, start immediately |
| Sync already running | All triggers ignored (`isSyncing` guard) |

### SyncScheduler.kt (new)

```kotlin
@Singleton
class SyncScheduler @Inject constructor(
    private val syncProcessor: SyncProcessor
) {
    fun onResume()
    fun onOperationEnqueued()
    fun syncNow()
}
```

Internal: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, 3s debounce via `Job` cancellation + `delay`.

### SyncQueue.kt (new)

```kotlin
data class SyncQueue(
    val operations: List<Operation>
) {
    val additions: List<Operation>   // ADD + MODIFY + RENAME destinations
    val deletions: List<Operation>   // DELETE + RENAME sources
}
```

Helper to classify operations into tree mutation types.

### SyncProcessor.kt (new)

Orchestrates the full sync pipeline:

```kotlin
@Singleton
class SyncProcessor @Inject constructor(
    private val opLog: OpLog,
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences
) {
    suspend fun sync(token: String, repo: String): SyncResult
}
```

Steps:
1. Rotate oplog
2. Read pending operations
3. Build Git Tree mutations
4. POST tree → commit → ref
5. Delete pending on success
6. Pull remote changes (diff + download)
7. Update `lastSyncedAt` in prefs

## Git Transport Layer

### GitTransport.kt (new)

Interface for high-level batch operations:

```kotlin
interface GitTransport {
    suspend fun batchCommit(
        token: String,
        repo: String,
        operations: List<Operation>,
        baseTreeSha: String?
    ): Result<Unit>

    suspend fun listPileFiles(token: String, repo: String): Result<List<String>>
    suspend fun downloadFile(token: String, repo: String, path: String): Result<String>
}
```

### GitHubTransport.kt (new)

Implementation using Git Data API (`POST /git/trees`, `POST /git/commits`, `PATCH /git/refs`):

```kotlin
@Singleton
class GitHubTransport @Inject constructor() : GitTransport
```

Git Tree API workflow for `batchCommit`:

```
1. For each operation:
   ADD/MODIFY/RENAME → POST /git/blobs (create blob from file content) → get SHA
   DELETE → no blob needed
2. Build tree entries list:
   ADD    → { path, mode: "100644", type: "blob", sha: blobSha }
   DELETE → { path, mode: "100644", type: "blob", sha: null }
   RENAME → two entries: delete old path, add new path
   MODIFY → { path, mode: "100644", type: "blob", sha: newBlobSha }
3. POST /git/trees with base_tree
4. POST /git/commits (parent = current HEAD)
5. PATCH /git/refs/heads/master
```

### GitForgeApi Changes

The existing `GitForgeApi` interface keeps `listPileFiles`, `listTrashFiles`, `getFile`, `putFile`, `deleteFile` for pull operations. Remove `moveToTrash` default method. Add nothing — `GitTransport` handles the upload direction separately.

## Affected Files

### New Files

| File | Role |
|---|---|
| `data/log/OpLog.kt` | JSONL append, file rotation, read, merge |
| `data/sync/SyncQueue.kt` | In-memory queue classification |
| `data/sync/SyncScheduler.kt` | Trigger management, debounce, state guard |
| `data/sync/SyncProcessor.kt` | Batch operations → git commit flow |
| `data/git/GitTransport.kt` | Interface for batch commit + pull |
| `data/github/GitHubTransport.kt` | Git Tree API implementation |

### Modified Files

| File | Changes |
|---|---|
| `data/git/GitForge.kt` | Remove `moveToTrash` default method; keep rest for pull flow |
| `data/github/GitHubRepository.kt` | Extend with `getLatestCommitSha()`, `createBlob()`, `createTree()`, `createCommit()`, `updateRef()` helper methods; keep existing Content API methods for pull |
| `data/github/GitHubSyncManager.kt` | Refactor: delegate to `SyncProcessor` + `SyncScheduler`; keep `isSyncing`/`syncError` StateFlows and public API |
| `data/repository/MemoRepositoryImpl.kt` | Update `LogEntry` → `Operation`; emit `ADD`/`DELETE`/`MODIFY` types |
| `data/prefs/UserPreferences.kt` | Remove `lastSyncedShas` (no longer needed — tree-based sync doesn't track per-file SHAs) |
| `ui/settings/SettingsViewModel.kt` | No significant change (already delegates to syncManager) |
| `ui/list/MemoListViewModel.kt` | No change |
| `ui/edit/MemoEditViewModel.kt` | No change |
| `MainActivity.kt` | No change |

### Removed Files

| File | Replacement |
|---|---|
| `data/log/OperationLog.kt` | Replaced by `OpLog.kt` |

### Unchanged Files

| File | Reason |
|---|---|
| `di/AppModule.kt` | Only binds `MemoRepository` — no change needed |
| `ui/navigation/NavGraph.kt` | No sync logic |
| `ui/list/MemoListScreen.kt` | Sync UI already abstracts through StateFlows |
| `ui/settings/SettingsScreen.kt` | Same |
| `ui/theme/Theme.kt` | Unrelated |
| `ui/App.kt` | Unrelated |
| `LiteralMemoApp.kt` | Unrelated |
| `DefaultMemoInitializer.kt` | Unrelated |

## Error Handling

| Failure | Behavior |
|---|---|
| Network error in upload | `mergePending()` — pending content prepended back to oplog, retry on next sync |
| Network error in download | Skip failed files, log error, don't affect upload success |
| Corrupt oplog.jsonl | Log warning, truncate at last valid line + newline |
| 25MB+ file (GitHub limit) | Already rejected at import; skip in download with warning |
| No base tree (empty repo) | `baseTreeSha = null` → tree created without `base_tree` |

## Migration

On first launch after update:
1. `OperationLog.kt` entries (with `synced` flag) are read and re-enqueued as `Operation` entries in the new `OpLog`
2. `lastSyncedShas` in `UserPreferences` is discarded
3. Any `operation_log.jsonl` file is renamed to `oplog.jsonl`

This is a one-time migration in `OpLog` init.

## Testing

- Unit tests for `OpLog` append/rotate/read/merge (with temp files)
- Unit tests for `SyncQueue` classification
- Unit tests for `SyncScheduler` debounce timing
- Unit tests for `GitHubTransport.batchCommit` with mock HTTP
- Existing `GitHubSyncManager` tests updated to reflect new internals
