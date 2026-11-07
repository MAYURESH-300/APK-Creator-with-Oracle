package com.example.media

import android.content.Context
import com.example.game.ObjectType
import com.example.game.WorldManager
import com.example.game.WorldObject
import com.example.model.Vector3
import org.json.JSONArray
import org.json.JSONObject

/** Persists only imported image/video world-plane records; media binaries remain in private media storage. */
object MediaWorldStore {
    private const val PREFS = "blockworld_media_world_v1"
    private const val KEY_OBJECTS = "objects"

    fun save(context: Context, objects: List<WorldObject>): Boolean = try {
        val array = JSONArray()
        objects.filter { it.isMediaPlane }.forEach { obj ->
            array.put(JSONObject().apply {
                put("id", obj.id); put("name", obj.name)
                put("px", obj.position.x); put("py", obj.position.y); put("pz", obj.position.z)
                put("sx", obj.size.x); put("sy", obj.size.y); put("sz", obj.size.z)
                put("rx", obj.rotationXDegrees); put("ry", obj.rotationYDegrees); put("rz", obj.rotationZDegrees)
                put("type", obj.type.name)
                put("mediaId", obj.mediaId ?: JSONObject.NULL)
                put("mediaFilePath", obj.mediaFilePath ?: JSONObject.NULL)
                put("mediaThumbnailPath", obj.mediaThumbnailPath ?: JSONObject.NULL)
                put("mediaKind", obj.mediaKind ?: JSONObject.NULL)
                put("mediaAspectRatio", obj.mediaAspectRatio)
                put("cr", obj.color.getOrElse(0) { 1f }); put("cg", obj.color.getOrElse(1) { 1f })
                put("cb", obj.color.getOrElse(2) { 1f }); put("ca", obj.color.getOrElse(3) { 1f })
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OBJECTS, array.toString()).commit()
    } catch (_: Exception) { false }

    fun restore(context: Context, worldManager: WorldManager): Boolean {
        return try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OBJECTS, null) ?: return false
        val array = JSONArray(raw)
        val existing = worldManager.objects.filter { it.isMediaPlane }.associateBy { it.id }
        val restoredIds = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val type = runCatching { ObjectType.valueOf(item.optString("type")) }.getOrNull()
                ?.takeIf { it == ObjectType.MEDIA_IMAGE || it == ObjectType.MEDIA_VIDEO } ?: continue
            val old = existing[id]
            val obj = old ?: WorldObject(
                id = id,
                name = item.optString("name", "Imported Media"),
                position = Vector3(),
                size = Vector3(1f, 1f, 0.08f),
                color = floatArrayOf(1f, 1f, 1f, 1f),
                type = type,
                mediaId = item.optString("mediaId").takeIf { it.isNotBlank() && it != "null" },
                mediaFilePath = item.optString("mediaFilePath").takeIf { it.isNotBlank() && it != "null" },
                mediaThumbnailPath = item.optString("mediaThumbnailPath").takeIf { it.isNotBlank() && it != "null" },
                mediaKind = item.optString("mediaKind").takeIf { it.isNotBlank() && it != "null" },
                mediaAspectRatio = item.optDouble("mediaAspectRatio", 1.0).toFloat().coerceIn(0.05f, 20f)
            ).also(worldManager::addObject)
            obj.position.set(item.optDouble("px", obj.position.x.toDouble()).toFloat(), item.optDouble("py", obj.position.y.toDouble()).toFloat(), item.optDouble("pz", obj.position.z.toDouble()).toFloat())
            obj.size.set(
                item.optDouble("sx", obj.size.x.toDouble()).toFloat().coerceIn(0.01f, 1000f),
                item.optDouble("sy", obj.size.y.toDouble()).toFloat().coerceIn(0.01f, 1000f),
                item.optDouble("sz", obj.size.z.toDouble()).toFloat().coerceIn(0.01f, 1000f)
            )
            obj.rotationXDegrees = item.optDouble("rx", obj.rotationXDegrees.toDouble()).toFloat()
            obj.rotationYDegrees = item.optDouble("ry", obj.rotationYDegrees.toDouble()).toFloat()
            obj.rotationZDegrees = item.optDouble("rz", obj.rotationZDegrees.toDouble()).toFloat()
            obj.color[0] = item.optDouble("cr", obj.color.getOrElse(0) { 1f }.toDouble()).toFloat().coerceIn(0f, 1f)
            obj.color[1] = item.optDouble("cg", obj.color.getOrElse(1) { 1f }.toDouble()).toFloat().coerceIn(0f, 1f)
            obj.color[2] = item.optDouble("cb", obj.color.getOrElse(2) { 1f }.toDouble()).toFloat().coerceIn(0f, 1f)
            obj.color[3] = item.optDouble("ca", obj.color.getOrElse(3) { 1f }.toDouble()).toFloat().coerceIn(0f, 1f)
            restoredIds += id
        }
        // Keep only records present in the latest saved scene; the private media files remain in the library.
        worldManager.objects.filter { it.isMediaPlane && it.id !in restoredIds }.map { it.id }.forEach(worldManager::removeObject)
        true
        } catch (_: Exception) { false }
    }
}
