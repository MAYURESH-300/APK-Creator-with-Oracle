package com.example.media

import android.content.Context
import com.example.game.ObjectType
import com.example.game.WorldManager
import com.example.game.WorldObject
import com.example.model.Vector3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the transforms of placed media planes separately from existing GLB swaps. */
object MediaWorldStore {
    private const val STORE_NAME = "media_library/world-placements.json"

    fun save(context: Context, objects: List<WorldObject>): Boolean = try {
        val array = JSONArray()
        objects.filter { it.isMediaPlane }.forEach { obj ->
            val fileName = obj.mediaFilePath?.let { File(it).name } ?: return@forEach
            val thumbnailName = obj.mediaThumbnailPath?.let { File(it).name }.orEmpty()
            array.put(JSONObject()
                .put("id", obj.id)
                .put("name", obj.name)
                .put("mediaId", obj.mediaId.orEmpty())
                .put("fileName", fileName)
                .put("thumbnailName", thumbnailName)
                .put("kind", obj.mediaKind ?: if (obj.type == ObjectType.MEDIA_VIDEO) "VIDEO" else "IMAGE")
                .put("aspectRatio", obj.mediaAspectRatio)
                .put("x", obj.position.x).put("y", obj.position.y).put("z", obj.position.z)
                .put("width", obj.size.x).put("height", obj.size.y).put("depth", obj.size.z)
                .put("rotationYDegrees", obj.rotationYDegrees)
                .put("rotationXDegrees", obj.rotationXDegrees)
                .put("rotationZDegrees", obj.rotationZDegrees)
            )
        }
        val target = File(context.applicationContext.filesDir, STORE_NAME)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(JSONObject().put("version", 1).put("objects", array).toString())
        if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace media placement store")
        if (!temp.renameTo(target)) {
            target.writeText(JSONObject().put("version", 1).put("objects", array).toString())
            temp.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Replaces any in-memory media planes with the last committed scene records. */
    fun restore(context: Context, worldManager: WorldManager) {
        worldManager.objects.filter { it.isMediaPlane }.map { it.id }.forEach { worldManager.removeObject(it) }
        val store = File(context.applicationContext.filesDir, STORE_NAME)
        if (!store.isFile) return
        try {
            val root = JSONObject(store.readText())
            val records = root.optJSONArray("objects") ?: JSONArray()
            val assets = File(context.applicationContext.filesDir, "media_library/assets")
            val thumbs = File(context.applicationContext.filesDir, "media_library/thumbnails")
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val mediaFile = File(assets, record.optString("fileName"))
                if (!mediaFile.isFile) continue
                val kind = record.optString("kind", "IMAGE").uppercase()
                val isVideo = kind == "VIDEO"
                val thumbName = record.optString("thumbnailName")
                val thumbFile = if (thumbName.isNotBlank()) File(thumbs, thumbName) else null
                val width = record.optDouble("width", 3.0).toFloat().coerceIn(0.2f, 20f)
                val height = record.optDouble("height", 2.0).toFloat().coerceIn(0.2f, 20f)
                val obj = WorldObject(
                    id = record.optString("id", "media_${record.optString("mediaId")}_$index"),
                    name = record.optString("name", mediaFile.name),
                    position = Vector3(
                        record.optDouble("x", 0.0).toFloat(),
                        record.optDouble("y", 2.0).toFloat(),
                        record.optDouble("z", 0.0).toFloat()
                    ),
                    size = Vector3(width, height, record.optDouble("depth", 0.08).toFloat().coerceIn(0.02f, 1f)),
                    color = floatArrayOf(1f, 1f, 1f, 1f),
                    type = if (isVideo) ObjectType.MEDIA_VIDEO else ObjectType.MEDIA_IMAGE,
                    mediaId = record.optString("mediaId").takeIf { it.isNotBlank() },
                    mediaFilePath = mediaFile.absolutePath,
                    mediaThumbnailPath = thumbFile?.takeIf { it.isFile }?.absolutePath,
                    mediaKind = if (isVideo) "VIDEO" else "IMAGE",
                    mediaAspectRatio = record.optDouble("aspectRatio", width / height.toDouble()).toFloat().coerceIn(0.1f, 10f),
                    rotationYDegrees = record.optDouble("rotationYDegrees", 0.0).toFloat(),
                    rotationXDegrees = record.optDouble("rotationXDegrees", 0.0).toFloat(),
                    rotationZDegrees = record.optDouble("rotationZDegrees", 0.0).toFloat()
                )
                worldManager.addObject(obj)
            }
        } catch (_: Exception) {
            // A damaged media placement file must not prevent the rest of the game from loading.
        }
    }
}
