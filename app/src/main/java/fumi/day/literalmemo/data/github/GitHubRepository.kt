package fumi.day.literalmemo.data.github

import android.util.Base64
import fumi.day.literalmemo.data.git.GitForgeApi
import fumi.day.literalmemo.data.git.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubRepository @Inject constructor() : GitForgeApi {

    private val baseUrl = "https://api.github.com"

    private suspend fun makeRequest(
        method: String,
        url: String,
        token: String,
        body: String? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body)
                }
            }
            val responseCode = connection.responseCode
            val responseBody = try {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } catch (e: Exception) {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            Pair(responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun listPileFiles(token: String, repo: String): Result<List<RemoteFile>> =
        listFilesInDir(token, repo, "pile")

    override suspend fun listTrashFiles(token: String, repo: String): Result<List<RemoteFile>> =
        listFilesInDir(token, repo, "trash")

    private suspend fun listFilesInDir(token: String, repo: String, dir: String): Result<List<RemoteFile>> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/contents/$dir", token)
            when (code) {
                200 -> {
                    val files = mutableListOf<RemoteFile>()
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        if (obj.getString("name").endsWith(".md")) {
                            files.add(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha")))
                        }
                    }
                    Result.success(files)
                }
                404 -> Result.success(emptyList())
                else -> Result.failure(Exception("Failed to list files in $dir: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFile(token: String, repo: String, path: String): Result<RemoteFile> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/contents/$path", token)
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    val encoded = obj.getString("content").replace("\n", "")
                    val content = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    Result.success(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content))
                }
                else -> Result.failure(Exception("Failed to get file: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun putFile(
        token: String,
        repo: String,
        path: String,
        content: String,
        sha: String?,
        message: String
    ): Result<RemoteFile> {
        return try {
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val bodyObj = JSONObject().apply {
                put("message", message)
                put("content", encoded)
                if (sha != null) put("sha", sha)
            }
            val (code, body) = makeRequest("PUT", "$baseUrl/repos/$repo/contents/$path", token, bodyObj.toString())
            when (code) {
                200, 201 -> {
                    val obj = JSONObject(body).getJSONObject("content")
                    Result.success(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content))
                }
                else -> Result.failure(Exception("Failed to put file: $code - $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(
        token: String,
        repo: String,
        path: String,
        sha: String,
        message: String
    ): Result<Unit> {
        return try {
            val bodyObj = JSONObject().apply {
                put("message", message)
                put("sha", sha)
            }
            val (code, _) = makeRequest("DELETE", "$baseUrl/repos/$repo/contents/$path", token, bodyObj.toString())
            when (code) {
                200 -> Result.success(Unit)
                else -> Result.failure(Exception("Failed to delete file: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    suspend fun getCommitTreeSha(token: String, repo: String, commitSha: String): Result<String> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/git/commits/$commitSha", token)
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    Result.success(obj.getJSONObject("tree").getString("sha"))
                }
                else -> Result.failure(Exception("Failed to get commit: $code"))
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
}
