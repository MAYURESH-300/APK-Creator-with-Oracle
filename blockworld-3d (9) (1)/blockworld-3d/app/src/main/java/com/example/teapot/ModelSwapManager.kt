package com.example.teapot

import android.content.Context
import com.example.game.ObjectType
import com.example.game.WorldManager
import com.example.game.WorldObject
import com.example.model.Vector3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ModelSwapManager {

    // Map: targetEntityId -> modelId
    private val activeSwaps = ConcurrentHashMap<String, String>()

    // Dynamically drag-and-drop placed objects
    data class PlacedObjectData(
        val id: String,
        val name: String,
        val modelId: String,
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val sizeX: Float,
        val sizeY: Float,
        val sizeZ: Float,
        val category: String,
        val rotationXDegrees: Float = 0f,
        val rotationYDegrees: Float = 0f,
        val rotationZDegrees: Float = 0f
    )

    private val placedObjects = mutableListOf<PlacedObjectData>()

    @Synchronized
    fun getSwappedMesh(targetId: String): Custom3DMesh? {
        val modelId = activeSwaps[targetId] ?: return null
        return TeapotModelRegistry.getModel(modelId)
    }

    @Synchronized
    fun isSwapped(targetId: String): Boolean = activeSwaps.containsKey(targetId)

    @Synchronized
    fun getActiveSwapModelId(targetId: String): String? = activeSwaps[targetId]

    @Synchronized
    fun applySwap(targetId: String, modelId: String, worldManager: WorldManager?) {
        activeSwaps[targetId] = modelId

        // Swapping the visual mesh must not overwrite the placed object's saved shape.
        // GLGameRenderer scales the selected GLB to the existing WorldObject.size, so its
        // appearance follows the target bounds while the editor's saved dimensions persist.
        // Newly placed objects are initialized to their model bounds by GameViewModel before applySwap().
    }

    @Synchronized
    fun removeSwap(targetId: String) {
        activeSwaps.remove(targetId)
    }

    @Synchronized
    fun getAllSwaps(): Map<String, String> = HashMap(activeSwaps)

    /** IDs referenced by the last committed scene; used to eagerly load only active meshes at startup. */
    fun getSavedReferencedModelIds(context: Context): Set<String> {
        val file = File(context.filesDir, "saved_swaps.json")
        if (!file.isFile) return emptySet()
        return runCatching {
            val root = JSONObject(file.readText())
            val result = LinkedHashSet<String>()
            val swaps = root.optJSONObject("swaps") ?: root
            val keys = swaps.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "placed" || key == "swaps") continue
                swaps.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(result::add)
            }
            val placed = root.optJSONArray("placed")
            if (placed != null) for (i in 0 until placed.length()) {
                placed.optJSONObject(i)?.optString("modelId")
                    ?.takeIf { it.isNotBlank() && it != "null" }?.let(result::add)
            }
            result
        }.getOrDefault(emptySet())
    }

    /** Restore registry-independent swap/placement state captured when Spectator Mode was opened. */
    @Synchronized
    fun restoreInMemoryState(swaps: Map<String, String>, placed: List<PlacedObjectData>) {
        activeSwaps.clear()
        activeSwaps.putAll(swaps)
        placedObjects.clear()
        placedObjects.addAll(placed)
    }

    @Synchronized
    fun recordPlacedObject(
        id: String,
        name: String,
        modelId: String,
        position: Vector3,
        size: Vector3,
        category: String,
        rotationXDegrees: Float? = null,
        rotationYDegrees: Float? = null,
        rotationZDegrees: Float? = null
    ) {
        val previous = placedObjects.firstOrNull { it.id == id }
        placedObjects.removeAll { it.id == id }
        placedObjects.add(
            PlacedObjectData(
                id, name, modelId, position.x, position.y, position.z,
                size.x, size.y, size.z, category,
                rotationXDegrees ?: previous?.rotationXDegrees ?: 0f,
                rotationYDegrees ?: previous?.rotationYDegrees ?: 0f,
                rotationZDegrees ?: previous?.rotationZDegrees ?: 0f
            )
        )
    }

    @Synchronized
    fun getPlacedObjects(): List<PlacedObjectData> = ArrayList(placedObjects)

    @Synchronized
    fun removePlacedObject(id: String) {
        placedObjects.removeAll { it.id == id }
    }

    /**
     * Read the model saved for the player before model files are registered. The registry uses
     * this ID to synchronously parse the chosen imported character before the first game frame.
     */
    fun getSavedPlayerModelId(context: Context): String? {
        val file = File(context.filesDir, "saved_swaps.json")
        if (!file.isFile) return null
        return try {
            val root = JSONObject(file.readText())
            val swaps = root.optJSONObject("swaps") ?: root
            swaps.optString("player_character", "")
                .takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clear legacy world edits once, but preserve the user's selected player model. Built-in
     * platform/cube position, scale and mesh defaults are restored after the remaining save data
     * is read. Custom character choice is not considered part of the world-layout migration.
     */
    @Synchronized
    fun clearSavedWorldForMigration(context: Context) {
        val savedPlayerModelId = getSavedPlayerModelId(context)
        activeSwaps.clear()
        if (!savedPlayerModelId.isNullOrBlank()) {
            activeSwaps["player_character"] = savedPlayerModelId
        }
        placedObjects.clear()
        // Write back the preserved player selection rather than deleting every saved swap.
        saveSwapsToStorage(context.applicationContext)
    }

    @Synchronized
    fun saveSwapsToStorage(context: Context): Boolean {
        return try {
            val rootJson = JSONObject()

            // 1. Swaps Map
            val swapsJson = JSONObject()
            for ((targetId, modelId) in activeSwaps) {
                swapsJson.put(targetId, modelId)
            }
            rootJson.put("swaps", swapsJson)

            // 2. Placed Furnishings Array
            val placedArr = JSONArray()
            for (item in placedObjects) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("name", item.name)
                o.put("modelId", item.modelId)
                o.put("posX", item.posX.toDouble())
                o.put("posY", item.posY.toDouble())
                o.put("posZ", item.posZ.toDouble())
                o.put("sizeX", item.sizeX.toDouble())
                o.put("sizeY", item.sizeY.toDouble())
                o.put("sizeZ", item.sizeZ.toDouble())
                o.put("category", item.category)
                o.put("rotationXDegrees", item.rotationXDegrees)
                o.put("rotationYDegrees", item.rotationYDegrees)
                o.put("rotationZDegrees", item.rotationZDegrees)
                placedArr.put(o)
            }
            rootJson.put("placed", placedArr)

            val file = File(context.filesDir, "saved_swaps.json")
            file.writeText(rootJson.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun loadSwapsFromStorage(context: Context, worldManager: WorldManager?) {
        try {
            val file = File(context.filesDir, "saved_swaps.json")
            if (!file.exists()) return

            val jsonStr = file.readText()
            val rootJson = JSONObject(jsonStr)

            // Reconstruct placed objects
            if (rootJson.has("placed") && worldManager != null) {
                val placedArr = rootJson.getJSONArray("placed")
                for (i in 0 until placedArr.length()) {
                    val o = placedArr.getJSONObject(i)
                    val id = o.getString("id")
                    val name = o.getString("name")
                    val modelId = o.getString("modelId")
                    val posX = o.getDouble("posX").toFloat()
                    val posY = o.getDouble("posY").toFloat()
                    val posZ = o.getDouble("posZ").toFloat()
                    val sizeX = o.getDouble("sizeX").toFloat()
                    val sizeY = o.getDouble("sizeY").toFloat()
                    val sizeZ = o.getDouble("sizeZ").toFloat()
                    val category = o.optString("category", "Landform")
                    val rotationX = o.optDouble("rotationXDegrees", 0.0).toFloat()
                    val rotationY = o.optDouble("rotationYDegrees", 0.0).toFloat()
                    val rotationZ = o.optDouble("rotationZDegrees", 0.0).toFloat()

                    recordPlacedObject(
                        id, name, modelId,
                        Vector3(posX, posY, posZ),
                        Vector3(sizeX, sizeY, sizeZ),
                        category, rotationX, rotationY, rotationZ
                    )

                    // Restore the saved transform whether the object already exists (built-in
                    // platform/cubes) or must be recreated (user-placed objects).
                    val existing = worldManager.objects.firstOrNull { it.id == id }
                    if (existing != null) {
                        existing.position.set(posX, posY, posZ)
                        existing.size.set(sizeX, sizeY, sizeZ)
                        existing.rotationXDegrees = rotationX
                        existing.rotationYDegrees = rotationY
                        existing.rotationZDegrees = rotationZ
                    } else {
                        val model = TeapotModelRegistry.getModel(modelId)
                        val color = model?.defaultColor?.clone() ?: floatArrayOf(0.7f, 0.7f, 0.7f, 1f)
                        val newObj = WorldObject(
                            id = id,
                            name = name,
                            position = Vector3(posX, posY, posZ),
                            size = Vector3(sizeX, sizeY, sizeZ),
                            color = color,
                            type = when (category) {
                                "Landform", "Building" -> ObjectType.PLATFORM
                                "Companion" -> ObjectType.TRAINING_DUMMY
                                else -> ObjectType.BREAKABLE_CRATE
                            },
                            maxHealth = 200f,
                            rotationXDegrees = rotationX,
                            rotationYDegrees = rotationY,
                            rotationZDegrees = rotationZ
                        )
                        worldManager.addObject(newObj)
                    }
                }
            }

            // Restore active swaps mapping
            val swapsJson = if (rootJson.has("swaps")) rootJson.getJSONObject("swaps") else rootJson
            val keys = swapsJson.keys()
            while (keys.hasNext()) {
                val targetId = keys.next()
                if (targetId == "swaps" || targetId == "placed") continue
                val modelId = swapsJson.getString(targetId)
                applySwap(targetId, modelId, worldManager)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
