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
