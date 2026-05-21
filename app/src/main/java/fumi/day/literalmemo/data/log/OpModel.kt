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
