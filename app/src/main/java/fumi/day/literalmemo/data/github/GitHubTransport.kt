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
