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
