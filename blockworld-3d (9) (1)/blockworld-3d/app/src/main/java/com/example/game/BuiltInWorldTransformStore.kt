package com.example.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists transforms for the three authored scene objects without changing model-swap data. */
object BuiltInWorldTransformStore {
    private const val PREFS = "blockworld_builtin_world_transforms_v1"
    private const val KEY_TRANSFORMS = "transforms"
    private val builtInIds = setOf("platform_spawn", "sample_cube", "sample_green_cube")

    fun save(context: Context, objects: List<WorldObject>): Boolean = try {
        val array = JSONArray()
        objects.filter { it.id in builtInIds }.forEach { obj ->
            array.put(JSONObject().apply {
                put("id", obj.id)
                put("px", obj.position.x); put("py", obj.position.y); put("pz", obj.position.z)
                put("sx", obj.size.x); put("sy", obj.size.y); put("sz", obj.size.z)
                put("rx", obj.rotationXDegrees); put("ry", obj.rotationYDegrees); put("rz", obj.rotationZDegrees)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRANSFORMS, array.toString()).commit()
    } catch (_: Exception) { false }

    fun restore(context: Context, objects: List<WorldObject>): Boolean {
        return try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSFORMS, null)
            ?: return false
        val array = JSONArray(raw)
        val byId = objects.filter { it.id in builtInIds }.associateBy { it.id }
        for (i in 0 until array.length()) {
            val saved = array.optJSONObject(i) ?: continue
            val obj = byId[saved.optString("id")] ?: continue
            obj.position.set(saved.optDouble("px", obj.position.x.toDouble()).toFloat(), saved.optDouble("py", obj.position.y.toDouble()).toFloat(), saved.optDouble("pz", obj.position.z.toDouble()).toFloat())
            obj.size.set(saved.optDouble("sx", obj.size.x.toDouble()).toFloat(), saved.optDouble("sy", obj.size.y.toDouble()).toFloat(), saved.optDouble("sz", obj.size.z.toDouble()).toFloat())
            obj.rotationXDegrees = saved.optDouble("rx", obj.rotationXDegrees.toDouble()).toFloat()
            obj.rotationYDegrees = saved.optDouble("ry", obj.rotationYDegrees.toDouble()).toFloat()
            obj.rotationZDegrees = saved.optDouble("rz", obj.rotationZDegrees.toDouble()).toFloat()
        }
        true
        } catch (_: Exception) { false }
    }
}
