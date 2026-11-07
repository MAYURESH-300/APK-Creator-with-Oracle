package com.example.teapot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.game.WorldObject
import com.example.media.MediaPlaneGlbFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A source-backed scene instance. Position is the world-space object centre (player uses its feet). */
data class SceneExportInstance(
    val modelId: String,
    val objectName: String,
    val sourcePath: String,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val sizeX: Float = 1f,
    val sizeY: Float = 1f,
    val sizeZ: Float = 1f,
    val rotationYDegrees: Float = 0f,
    val rotationXDegrees: Float = 0f,
    val rotationZDegrees: Float = 0f,
    val isPlayerCharacter: Boolean = false,
    /** Parser-normalized vertical extent (used to align the character's feet to the player position). */
    val normalizedHeight: Float = 2.5f,
    /** Media quads need exact X/Y aspect bounds; ordinary 3D models should keep uniform proportions. */
    val matchTargetBoundsExactly: Boolean = false
)

data class GlbSceneExportResult(
    val bytes: ByteArray,
    val objectCount: Int,
    val animationCount: Int,
    val messages: List<String>
)

/**
 * Offline glTF 2.0 binary scene merger. It merges source GLB JSON and their binary buffers, remaps
 * cross-file indices, keeps each source hierarchy/skeleton independent, and adds a wrapper node per
 * placed instance. No renderer geometry is regenerated and no source animation is fabricated.
 */
object GlbSceneExporter {
    private const val GLB_MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942
    private const val GLB_VERSION = 2

    private data class SourceGlb(val json: JSONObject, val binary: ByteArray)
    private data class Bounds(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float) {
        val centerX get() = (minX + maxX) * 0.5f
        val centerY get() = (minY + maxY) * 0.5f
        val centerZ get() = (minZ + maxZ) * 0.5f
        val extentX get() = (maxX - minX).coerceAtLeast(0.0001f)
        val extentY get() = (maxY - minY).coerceAtLeast(0.0001f)
        val extentZ get() = (maxZ - minZ).coerceAtLeast(0.0001f)
        val maxExtent get() = max(extentX, max(extentY, extentZ)).coerceAtLeast(0.0001f)
    }

    private data class Offsets(
        val accessors: Int, val bufferViews: Int, val meshes: Int, val materials: Int,
        val textures: Int, val images: Int, val samplers: Int, val nodes: Int,
        val skins: Int, val cameras: Int
    )

    /**
     * Exports the existing model scene plus placed image/video planes. This is additive: the
     * original export(context, instances) entry point and GLB merge behavior remain unchanged.
     * Video planes are represented by their still poster in exported GLB files.
     */
    fun exportWorld(
        context: Context,
        modelInstances: List<SceneExportInstance>,
        worldObjects: List<WorldObject>
    ): GlbSceneExportResult {
        val generatedMediaInstances = mutableListOf<SceneExportInstance>()
        val messages = mutableListOf<String>()
        worldObjects.filter { it.isMediaPlane }.forEach { obj ->
            val instance = MediaPlaneGlbFactory.createSceneExportInstance(context, obj)
            if (instance == null) {
                messages += "Skipped media plane '${obj.name}' because its image/poster could not be read."
            } else {
                generatedMediaInstances += instance
                if (obj.type == com.example.game.ObjectType.MEDIA_VIDEO) {
                    messages += "Video '${obj.name}' exported as a static poster; video playback is not part of the glTF 2.0 core format."
                }
            }
        }
        val generatedFiles = generatedMediaInstances.map { File(it.sourcePath) }
        try {
            val result = export(context, modelInstances + generatedMediaInstances)
            return result.copy(messages = result.messages + messages)
        } finally {
            generatedFiles.forEach { it.delete() }
        }
    }

    fun export(context: Context, instances: List<SceneExportInstance>): GlbSceneExportResult {
        require(instances.isNotEmpty()) { "There are no scene objects to export." }
        val binaryOut = ByteArrayOutputStream()
        val accessorsOut = JSONArray()
        val bufferViewsOut = JSONArray()
        val meshesOut = JSONArray()
        val materialsOut = JSONArray()
        val texturesOut = JSONArray()
        val imagesOut = JSONArray()
        val samplersOut = JSONArray()
        val nodesOut = JSONArray()
        val skinsOut = JSONArray()
        val camerasOut = JSONArray()
        val lightsOut = JSONArray()
        val animationsOut = JSONArray()
        val sceneRoots = JSONArray()
        val extensionsUsedOut = LinkedHashSet<String>()
        // Exported scene uses core glTF fallbacks where present; do not make optional lighting /
        // material extensions a hard requirement for otherwise standards-compliant external viewers.
        val uniqueNodeNames = HashSet<String>()
        val uniqueAnimationNames = HashSet<String>()
        val instanceNameCounts = HashMap<String, Int>()
        val messages = mutableListOf<String>()
        var assetOut: JSONObject? = null
        var exportedCount = 0
        var animationCount = 0

        for (instance in instances) {
            val source = try {
                readSource(context, instance.sourcePath)
            } catch (e: Exception) {
                messages += "Skipped '${instance.objectName}': ${e.message ?: "GLB could not be read"}"
                continue
            }
            val sourceJson = source.json
            val rootExtensions = sourceJson.optJSONObject("extensions")
            if (rootExtensions != null) {
                val extensionNames = rootExtensions.keys().asSequence().toSet()
                val unsupportedRoot = extensionNames - setOf("KHR_lights_punctual")
                require(unsupportedRoot.isEmpty()) {
                    "'${instance.objectName}' uses root-level extensions that cannot be safely merged: ${unsupportedRoot.joinToString()}. Re-export this model with the extension baked into core glTF data."
                }
            }
            val asset = sourceJson.optJSONObject("asset")
            require(asset?.optString("version")?.startsWith("2.") == true) {
                "'${instance.objectName}' is not a glTF 2.x model. Export glTF 2.0 GLB files."
            }
            val used = sourceJson.optJSONArray("extensionsUsed")
            val sourceExtensions = linkedSetOf<String>()
            if (used != null) for (i in 0 until used.length()) {
                sourceExtensions += used.optString(i)
                extensionsUsedOut += used.optString(i)
            }
            val required = sourceJson.optJSONArray("extensionsRequired")
            if (required != null) for (i in 0 until required.length()) {
                sourceExtensions += required.optString(i)
                extensionsUsedOut += required.optString(i)
                // Data stays on the copied nodes/materials, but is intentionally not marked required:
                // viewers without an optional enhancement can still render the core PBR fallback.
            }
            // These extensions contain resource indices or compressed payloads this exporter does
            // not rewrite. Refuse them rather than emitting a superficially valid but broken GLB.
            val unsupportedMergeExtensions = sourceExtensions.intersect(
                setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_materials_variants")
            )
            require(unsupportedMergeExtensions.isEmpty()) {
                "GLB merge cannot safely remap ${unsupportedMergeExtensions.joinToString()}. Export that model with standard mesh data and without material variants."
            }
            if (assetOut == null) assetOut = JSONObject(asset.toString())

            val sourceBuffers = sourceJson.optJSONArray("buffers")
            require(sourceBuffers == null || sourceBuffers.length() <= 1) {
                "'${instance.objectName}' uses multiple buffers, which this self-contained GLB exporter does not support. Re-export it as a single-buffer GLB."
            }
            val logicalBinLength = sourceBuffers?.optJSONObject(0)?.optInt("byteLength", source.binary.size)
                ?: source.binary.size
            require(logicalBinLength <= source.binary.size) { "GLB buffer is shorter than its declared byteLength for '${instance.objectName}'." }
            val sourceBin = source.binary.copyOf(logicalBinLength)
            padBinary(binaryOut)
            val binaryBase = binaryOut.size().toLong()
            binaryOut.write(sourceBin)
            padBinary(binaryOut)

            val sourceLightOffset = lightsOut.length()
            sourceJson.optJSONObject("extensions")?.optJSONObject("KHR_lights_punctual")?.optJSONArray("lights")?.let { sourceLights ->
                for (lightIndex in 0 until sourceLights.length()) lightsOut.put(JSONObject(sourceLights.getJSONObject(lightIndex).toString()))
            }
            val offsets = Offsets(
                accessorsOut.length(), bufferViewsOut.length(), meshesOut.length(), materialsOut.length(),
                texturesOut.length(), imagesOut.length(), samplersOut.length(), nodesOut.length(),
                skinsOut.length(), camerasOut.length()
            )
            val instanceName = uniqueInstanceName(instance.objectName.ifBlank { instance.modelId }, instanceNameCounts)
            val sourceBounds = calculateSceneBounds(sourceJson, source.binary)
            val scaleX: Float
            val scaleY: Float
            val scaleZ: Float
            // Keep source geometry proportional in the exported scene. Scaling X/Y/Z independently
            // to a gameplay collider deforms tall models into thin cards (especially when a model
            // is swapped onto the spawn platform, whose collider is intentionally very flat).
            // Match the longest source dimension to the longest target dimension, like a 3D editor
            // uniform-scale operation, and leave the embedded source mesh/material data untouched.
            if (instance.matchTargetBoundsExactly) {
                // A media source is a unit quad. Respect the imported image/video aspect ratio
                // instead of forcing a square, but keep it flat on Z.
                scaleX = instance.sizeX.coerceAtLeast(0.0001f) / sourceBounds.extentX
                scaleY = instance.sizeY.coerceAtLeast(0.0001f) / sourceBounds.extentY
                scaleZ = scaleX
            } else {
                val uniform = if (instance.isPlayerCharacter) {
                    // Match the renderer's normalized player maximum extent.
                    2.0f / sourceBounds.maxExtent
                } else {
                    max(
                        instance.sizeX.coerceAtLeast(0.0001f),
                        max(instance.sizeY.coerceAtLeast(0.0001f), instance.sizeZ.coerceAtLeast(0.0001f))
                    ) / sourceBounds.maxExtent
                }
                scaleX = uniform
                scaleY = uniform
                scaleZ = uniform
            }
            // Match the runtime renderer's T * Rx * Ry * Rz * S transform, including the
            // selected object's three-axis rotation, without moving its centre around the origin.
            val hx = Math.toRadians(instance.rotationXDegrees.toDouble()) * 0.5
            val hy = Math.toRadians(instance.rotationYDegrees.toDouble()) * 0.5
            val hz = Math.toRadians(instance.rotationZDegrees.toDouble()) * 0.5
            val sx = sin(hx); val cx = cos(hx)
            val sy = sin(hy); val cy = cos(hy)
            val sz = sin(hz); val cz = cos(hz)
            val qx = (sx * cy * cz + cx * sy * sz).toFloat()
            val qy = (cx * sy * cz - sx * cy * sz).toFloat()
            val qz = (cx * cy * sz + sx * sy * cz).toFloat()
            val qw = (cx * cy * cz - sx * sy * sz).toFloat()
            val cxScaled = sourceBounds.centerX * scaleX
            val cyScaled = sourceBounds.centerY * scaleY
            val czScaled = sourceBounds.centerZ * scaleZ
            val tx = 2f * (qy * czScaled - qz * cyScaled)
            val ty = 2f * (qz * cxScaled - qx * czScaled)
            val tz = 2f * (qx * cyScaled - qy * cxScaled)
            val rotatedCx = cxScaled + qw * tx + (qy * tz - qz * ty)
            val rotatedCy = cyScaled + qw * ty + (qz * tx - qx * tz)
            val rotatedCz = czScaled + qw * tz + (qx * ty - qy * tx)
            val targetCenterY = if (instance.isPlayerCharacter) {
                instance.positionY + (instance.normalizedHeight.coerceAtLeast(0f) * 0.4f)
            } else instance.positionY
            val wrapperTranslation = floatArrayOf(
                instance.positionX - rotatedCx,
                targetCenterY - rotatedCy,
                instance.positionZ - rotatedCz
            )

            // Binary data is shared by reference through one aggregate buffer, so all byte offsets move.
            appendArray(sourceJson.optJSONArray("bufferViews"), bufferViewsOut) { o ->
                // byteOffset is optional in glTF and defaults to zero. It still has to be rebased
                // when this source is appended after another GLB; otherwise the next model reads
                // vertex/index/image bytes from the first model's binary payload.
                o.put("byteOffset", o.optLong("byteOffset", 0L) + binaryBase)
                o.put("buffer", 0)
            }
            appendArray(sourceJson.optJSONArray("accessors"), accessorsOut) { o ->
                val view = o.optInt("bufferView", -1)
                if (view >= 0) o.put("bufferView", view + offsets.bufferViews)
                val sparse = o.optJSONObject("sparse")
                sparse?.optJSONObject("indices")?.let { if (it.has("bufferView")) it.put("bufferView", it.getInt("bufferView") + offsets.bufferViews) }
                sparse?.optJSONObject("values")?.let { if (it.has("bufferView")) it.put("bufferView", it.getInt("bufferView") + offsets.bufferViews) }
            }
            appendArray(sourceJson.optJSONArray("samplers"), samplersOut) { }
            val sourceImages = sourceJson.optJSONArray("images")
            val sourceViews = sourceJson.optJSONArray("bufferViews") ?: JSONArray()
            if (sourceImages != null) for (imageIndex in 0 until sourceImages.length()) {
                val originalImage = sourceImages.getJSONObject(imageIndex)
                val image = JSONObject(originalImage.toString())
                if (image.has("bufferView")) image.put("bufferView", image.getInt("bufferView") + offsets.bufferViews)

                val uri = image.optString("uri", "")
                var mimeType = image.optString("mimeType", "")
                var payload: ByteArray? = null
                if (uri.isNotBlank()) {
                    require(uri.startsWith("data:")) {
                        "Image URI '$uri' is external; embed images inside the source GLB before exporting"
                    }
                    val decoded = decodeDataUri(uri)
                    payload = decoded.bytes
                    if (mimeType.isBlank()) mimeType = decoded.mimeType
                    image.remove("uri")
                } else if (mimeType.equals("image/webp", ignoreCase = true)) {
                    // Read the original bufferView before rebasing; the new image bytes will be
                    // appended at the end of the combined BIN chunk.
                    val oldViewIndex = originalImage.optInt("bufferView", -1)
                    require(oldViewIndex in 0 until sourceViews.length()) {
                        "WebP image $imageIndex has no valid embedded bufferView"
                    }
                    val oldView = sourceViews.getJSONObject(oldViewIndex)
                    val startOffset = oldView.optLong("byteOffset", 0L) + originalImage.optLong("byteOffset", 0L)
                    val byteLength = oldView.optLong("byteLength", -1L)
                    require(startOffset >= 0L && byteLength > 0L && startOffset + byteLength <= source.binary.size.toLong()) {
                        "WebP image $imageIndex is outside the source BIN chunk"
                    }
                    payload = source.binary.copyOfRange(startOffset.toInt(), (startOffset + byteLength).toInt())
                }

                if (mimeType.equals("image/webp", ignoreCase = true)) {
                    val originalBytes = payload ?: run {
                        val oldViewIndex = originalImage.optInt("bufferView", -1)
                        require(oldViewIndex in 0 until sourceViews.length()) { "WebP image $imageIndex has no embedded bytes" }
                        val oldView = sourceViews.getJSONObject(oldViewIndex)
                        val startOffset = oldView.optLong("byteOffset", 0L) + originalImage.optLong("byteOffset", 0L)
                        val byteLength = oldView.optLong("byteLength", -1L)
                        require(startOffset >= 0L && byteLength > 0L && startOffset + byteLength <= source.binary.size.toLong()) {
                            "WebP image $imageIndex is outside the source BIN chunk"
                        }
                        source.binary.copyOfRange(startOffset.toInt(), (startOffset + byteLength).toInt())
                    }
                    val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                        ?: error("WebP image $imageIndex could not be decoded for standard GLB export")
                    val pngBytes = try {
                        ByteArrayOutputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "WebP-to-PNG conversion failed" }
                            output.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    padBinary(binaryOut)
                    val imageOffset = binaryOut.size()
                    binaryOut.write(pngBytes)
                    val imageView = JSONObject()
                        .put("buffer", 0)
                        .put("byteOffset", imageOffset)
                        .put("byteLength", pngBytes.size)
                    bufferViewsOut.put(imageView)
                    image.put("bufferView", bufferViewsOut.length() - 1)
                    image.put("mimeType", "image/png")
                    image.remove("uri")
                } else if (payload != null) {
                    padBinary(binaryOut)
                    val imageOffset = binaryOut.size()
                    binaryOut.write(payload)
                    val imageView = JSONObject()
                        .put("buffer", 0)
                        .put("byteOffset", imageOffset)
                        .put("byteLength", payload.size)
                    bufferViewsOut.put(imageView)
                    image.put("bufferView", bufferViewsOut.length() - 1)
                    image.put("mimeType", mimeType.ifBlank { "image/png" })
                    image.remove("uri")
                }
                require(image.optString("mimeType") in setOf("image/png", "image/jpeg")) {
                    "Image $imageIndex uses unsupported MIME type '${image.optString("mimeType")}'. Re-export it as PNG or JPEG before GLB export."
                }
                imagesOut.put(image)
            }
            // WebP texture images were converted to PNG and their texture extensions were removed.
            extensionsUsedOut.remove("EXT_texture_webp")
            appendArray(sourceJson.optJSONArray("textures"), texturesOut) { texture ->
                if (texture.has("source")) texture.put("source", texture.getInt("source") + offsets.images)
                if (texture.has("sampler")) texture.put("sampler", texture.getInt("sampler") + offsets.samplers)
                val ext = texture.optJSONObject("extensions")
                val basis = ext?.optJSONObject("KHR_texture_basisu")
                if (basis?.has("source") == true) basis.put("source", basis.getInt("source") + offsets.images)
                val webp = ext?.optJSONObject("EXT_texture_webp")
                if (webp?.has("source") == true) {
                    val embeddedPngIndex = webp.getInt("source") + offsets.images
                    // This extension's image is transcoded to a core PNG image below. Promote it
                    // to the ordinary source so viewers need no EXT_texture_webp support.
                    if (!texture.has("source")) texture.put("source", embeddedPngIndex)
                    ext.remove("EXT_texture_webp")
                }
                if (ext != null && ext.length() == 0) texture.remove("extensions")
            }
            appendArray(sourceJson.optJSONArray("materials"), materialsOut) { material ->
                remapMaterialTextureIndices(material, offsets.textures)
            }
            appendArray(sourceJson.optJSONArray("meshes"), meshesOut) { mesh ->
                mesh.optJSONArray("primitives")?.let { primitives ->
                    for (p in 0 until primitives.length()) {
                        val primitive = primitives.getJSONObject(p)
                        if (primitive.has("material")) primitive.put("material", primitive.getInt("material") + offsets.materials)
                        if (primitive.has("indices")) primitive.put("indices", primitive.getInt("indices") + offsets.accessors)
                        val attrs = primitive.optJSONObject("attributes")
                        attrs?.let { remapIntegerValues(it, offsets.accessors) }
                        primitive.optJSONArray("targets")?.let { targets ->
                            for (t in 0 until targets.length()) remapIntegerValues(targets.getJSONObject(t), offsets.accessors)
                        }
                        val extensions = primitive.optJSONObject("extensions")
                        extensions?.optJSONObject("EXT_mesh_gpu_instancing")?.optJSONObject("attributes")?.let {
                            remapIntegerValues(it, offsets.accessors)
                        }
                    }
                }
            }
            appendArray(sourceJson.optJSONArray("cameras"), camerasOut) { }
            appendArray(sourceJson.optJSONArray("skins"), skinsOut) { skin ->
                skin.optJSONArray("joints")?.let { joints ->
                    for (j in 0 until joints.length()) joints.put(j, joints.getInt(j) + offsets.nodes)
                }
                if (skin.has("skeleton")) skin.put("skeleton", skin.getInt("skeleton") + offsets.nodes)
                if (skin.has("inverseBindMatrices")) skin.put("inverseBindMatrices", skin.getInt("inverseBindMatrices") + offsets.accessors)
            }
            val nodeJson = sourceJson.optJSONArray("nodes") ?: JSONArray()
            appendArray(nodeJson, nodesOut) { node ->
                if (node.has("mesh")) node.put("mesh", node.getInt("mesh") + offsets.meshes)
                if (node.has("skin")) node.put("skin", node.getInt("skin") + offsets.skins)
                if (node.has("camera")) node.put("camera", node.getInt("camera") + offsets.cameras)
                node.optJSONArray("children")?.let { children ->
                    for (j in 0 until children.length()) children.put(j, children.getInt(j) + offsets.nodes)
                }
                val light = node.optJSONObject("extensions")?.optJSONObject("KHR_lights_punctual")
                if (light?.has("light") == true) light.put("light", light.getInt("light") + sourceLightOffset)
                val originalNodeName = node.optString("name").takeIf { it.isNotBlank() }
                if (originalNodeName != null) node.put("name", uniqueName("${instanceName}_${sanitize(originalNodeName)}", uniqueNodeNames))
            }
            // Each model's animations stay tied to its own copied nodes and accessors.
            val sourceAnimations = sourceJson.optJSONArray("animations")
            if (sourceAnimations == null || sourceAnimations.length() == 0) {
                messages += "'${instanceName}' has no embedded animation clips; its static model was exported."
            }
            if (sourceAnimations != null) {
                for (a in 0 until sourceAnimations.length()) {
                    val animation = JSONObject(sourceAnimations.getJSONObject(a).toString())
                    animation.optJSONArray("samplers")?.let { samplers ->
                        for (s in 0 until samplers.length()) {
                            val sampler = samplers.getJSONObject(s)
                            if (sampler.has("input")) sampler.put("input", sampler.getInt("input") + offsets.accessors)
                            if (sampler.has("output")) sampler.put("output", sampler.getInt("output") + offsets.accessors)
                        }
                    }
                    animation.optJSONArray("channels")?.let { channels ->
                        for (c in 0 until channels.length()) {
                            val target = channels.getJSONObject(c).optJSONObject("target") ?: continue
                            if (target.has("node")) target.put("node", target.getInt("node") + offsets.nodes)
                        }
                    }
                    val oldName = animation.optString("name", "Animation ${a + 1}").ifBlank { "Animation ${a + 1}" }
                    animation.put("name", uniqueName("${instanceName}_${sanitize(oldName)}", uniqueAnimationNames))
                    animationsOut.put(animation)
                    animationCount++
                }
            }

            val roots = rootNodes(sourceJson, nodeJson.length())
            val wrapper = JSONObject()
                .put("name", instanceName)
                .put("translation", JSONArray().put(wrapperTranslation[0]).put(wrapperTranslation[1]).put(wrapperTranslation[2]))
                .put("rotation", JSONArray().put(0.0).put(sin(Math.toRadians(instance.rotationYDegrees.toDouble()) * 0.5)).put(0.0).put(cos(Math.toRadians(instance.rotationYDegrees.toDouble()) * 0.5)))
                .put("scale", JSONArray().put(scaleX).put(scaleY).put(scaleZ))
                .put("children", JSONArray().also { ch -> roots.forEach { ch.put(it + offsets.nodes) } })
            nodesOut.put(wrapper)
            sceneRoots.put(nodesOut.length() - 1)
            exportedCount++
        }

        require(exportedCount > 0) {
            "No GLB objects could be exported. Import or select self-contained .glb models first. ${messages.joinToString(" ")}"
        }
        val outputAsset = assetOut ?: JSONObject().put("version", "2.0")
        outputAsset.remove("minVersion")
        outputAsset.put("version", "2.0")
        outputAsset.put("generator", "BlockWorld 3D Offline Scene Exporter")
        val root = JSONObject()
            .put("asset", outputAsset)
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("name", "BlockWorld Scene").put("nodes", sceneRoots)))
            .put("nodes", nodesOut)
            .put("meshes", meshesOut)
            .put("accessors", accessorsOut)
            .put("bufferViews", bufferViewsOut)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", binaryOut.size())))
        if (materialsOut.length() > 0) root.put("materials", materialsOut)
        if (texturesOut.length() > 0) root.put("textures", texturesOut)
        if (imagesOut.length() > 0) root.put("images", imagesOut)
        if (samplersOut.length() > 0) root.put("samplers", samplersOut)
        if (skinsOut.length() > 0) root.put("skins", skinsOut)
        if (camerasOut.length() > 0) root.put("cameras", camerasOut)
        if (animationsOut.length() > 0) root.put("animations", animationsOut)
        if (extensionsUsedOut.isNotEmpty()) root.put("extensionsUsed", JSONArray().also { a -> extensionsUsedOut.forEach { a.put(it) } })
        if (lightsOut.length() > 0) {
            root.put("extensions", JSONObject().put("KHR_lights_punctual", JSONObject().put("lights", lightsOut)))
        }

        val bin = binaryOut.toByteArray().let { bytes -> if (bytes.size % 4 == 0) bytes else bytes.copyOf(align4(bytes.size)) }
        root.getJSONArray("buffers").getJSONObject(0).put("byteLength", bin.size)
        validateMergedDocument(root, bin.size)
        val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        val jsonChunk = padBytes(jsonBytes, 0x20)
        val binChunk = padBytes(bin, 0)
        val totalLength = 12 + 8 + jsonChunk.size + 8 + binChunk.size
        val output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(GLB_MAGIC)
        output.putInt(GLB_VERSION)
        output.putInt(totalLength)
        output.putInt(jsonChunk.size)
        output.putInt(JSON_CHUNK)
        output.put(jsonChunk)
        output.putInt(binChunk.size)
        output.putInt(BIN_CHUNK)
        output.put(binChunk)
        if (messages.isEmpty()) messages += "All $exportedCount GLB scene objects exported."
        return GlbSceneExportResult(output.array(), exportedCount, animationCount, messages)
    }

    /** Fail export rather than delivering a GLB whose references work only in our own parser. */
    private fun validateMergedDocument(root: JSONObject, binaryLength: Int) {
        val buffers = root.optJSONArray("buffers") ?: error("Export has no buffers array")
        require(buffers.length() == 1) { "Export must contain one embedded GLB buffer" }
        require(buffers.getJSONObject(0).optInt("byteLength", -1) in 0..binaryLength) {
            "Export buffer byteLength is outside the BIN chunk"
        }
        val views = root.optJSONArray("bufferViews") ?: JSONArray()
        for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            require(view.optInt("buffer", 0) == 0) { "BufferView $i references a missing buffer" }
            val offset = view.optLong("byteOffset", 0L)
            val length = view.optLong("byteLength", -1L)
            require(offset >= 0L && length >= 0L && offset + length <= binaryLength.toLong()) {
                "BufferView $i is outside the exported BIN chunk"
            }
        }
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        for (i in 0 until accessors.length()) {
            val accessor = accessors.getJSONObject(i)
            if (accessor.has("bufferView")) {
                require(accessor.optInt("bufferView", -1) in 0 until views.length()) {
                    "Accessor $i references a missing bufferView"
                }
            }
            val sparse = accessor.optJSONObject("sparse")
            sparse?.optJSONObject("indices")?.let { ref ->
                require(ref.optInt("bufferView", -1) in 0 until views.length()) { "Sparse accessor $i has an invalid indices bufferView" }
            }
            sparse?.optJSONObject("values")?.let { ref ->
                require(ref.optInt("bufferView", -1) in 0 until views.length()) { "Sparse accessor $i has an invalid values bufferView" }
            }
        }
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val skins = root.optJSONArray("skins") ?: JSONArray()
        val cameras = root.optJSONArray("cameras") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.has("mesh")) require(node.optInt("mesh", -1) in 0 until meshes.length()) { "Node $i references a missing mesh" }
            if (node.has("skin")) require(node.optInt("skin", -1) in 0 until skins.length()) { "Node $i references a missing skin" }
            if (node.has("camera")) require(node.optInt("camera", -1) in 0 until cameras.length()) { "Node $i references a missing camera" }
            node.optJSONArray("children")?.let { children ->
                for (j in 0 until children.length()) require(children.optInt(j, -1) in 0 until nodes.length()) {
                    "Node $i has an invalid child index"
                }
            }
        }
        for (i in 0 until meshes.length()) {
            val primitives = meshes.getJSONObject(i).optJSONArray("primitives") ?: JSONArray()
            for (j in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(j)
                if (primitive.has("indices")) require(primitive.optInt("indices", -1) in 0 until accessors.length()) { "Mesh $i primitive $j has invalid indices" }
                if (primitive.has("material")) require(primitive.optInt("material", -1) in 0 until (root.optJSONArray("materials")?.length() ?: 0)) { "Mesh $i primitive $j has invalid material" }
                val attrs = primitive.optJSONObject("attributes")
                if (attrs != null) {
                    val keys = attrs.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        require(attrs.optInt(key, -1) in 0 until accessors.length()) { "Mesh $i primitive $j has invalid $key accessor" }
                    }
                }
            }
        }
        val scenes = root.optJSONArray("scenes") ?: error("Export has no scenes array")
        require(scenes.length() > 0 && root.optInt("scene", -1) in 0 until scenes.length()) { "Export has no valid active scene" }
        for (i in 0 until scenes.length()) {
            scenes.getJSONObject(i).optJSONArray("nodes")?.let { roots ->
                for (j in 0 until roots.length()) require(roots.optInt(j, -1) in 0 until nodes.length()) { "Scene $i has an invalid root node" }
            }
        }
        val images = root.optJSONArray("images") ?: JSONArray()
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            require(image.has("bufferView") && !image.has("uri")) { "Image $i is not embedded in the GLB" }
            require(image.optString("mimeType") in setOf("image/png", "image/jpeg")) { "Image $i is not a core glTF PNG/JPEG image" }
            require(image.optInt("bufferView", -1) in 0 until views.length()) { "Image $i references a missing bufferView" }
        }
        val textures = root.optJSONArray("textures") ?: JSONArray()
        for (i in 0 until textures.length()) {
            val texture = textures.getJSONObject(i)
            if (texture.has("source")) require(texture.optInt("source", -1) in 0 until images.length()) { "Texture $i references a missing image" }
            if (texture.has("sampler")) require(texture.optInt("sampler", -1) in 0 until (root.optJSONArray("samplers")?.length() ?: 0)) { "Texture $i references a missing sampler" }
        }
    }

    private data class DecodedDataUri(val mimeType: String, val bytes: ByteArray)
    private fun decodeDataUri(uri: String): DecodedDataUri {
        val comma = uri.indexOf(',')
        require(uri.startsWith("data:") && comma >= 0) { "Malformed embedded image data URI" }
        val metadata = uri.substring(5, comma)
        val mime = metadata.substringBefore(';').ifBlank { "application/octet-stream" }
        val payload = uri.substring(comma + 1)
        val bytes = if (metadata.split(';').any { it.equals("base64", true) }) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(StandardCharsets.UTF_8)
        }
        return DecodedDataUri(mime, bytes)
    }

    private fun readSource(context: Context, sourcePath: String): SourceGlb {
        val bytes = when {
            sourcePath.startsWith("asset://") -> context.assets.open(sourcePath.removePrefix("asset://")).use { it.readBytes() }
            File(sourcePath).isFile -> File(sourcePath).readBytes()
            else -> throw IllegalArgumentException("source GLB is missing: $sourcePath")
        }
        return parseGlb(bytes)
    }

    private fun parseGlb(bytes: ByteArray): SourceGlb {
        require(bytes.size >= 20) { "File is too short to be a GLB" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == GLB_MAGIC) { "File does not have the GLB magic header" }
        require(buffer.int == GLB_VERSION) { "Only binary glTF 2.0 is supported" }
        val declaredLength = buffer.int
        require(declaredLength in 20..bytes.size) { "Invalid GLB declared length" }
        var json: JSONObject? = null
        var binary = ByteArray(0)
        while (buffer.position() + 8 <= declaredLength) {
            val length = buffer.int
            val type = buffer.int
            require(length >= 0 && buffer.position().toLong() + length <= declaredLength) { "Invalid GLB chunk length" }
            val chunk = ByteArray(length)
            buffer.get(chunk)
            when (type) {
                JSON_CHUNK -> json = JSONObject(String(chunk, StandardCharsets.UTF_8).trimEnd('\u0000', ' ', '\t', '\r', '\n'))
                BIN_CHUNK -> binary = chunk
            }
            val padding = (4 - (length % 4)) % 4
            if (buffer.position() + padding <= declaredLength) buffer.position(buffer.position() + padding)
        }
        val parsedJson = json ?: throw IllegalArgumentException("GLB has no JSON chunk")
        val buffers = parsedJson.optJSONArray("buffers")
        if (buffers != null && buffers.length() > 0) {
            val first = buffers.getJSONObject(0)
            if (first.has("uri")) {
                val uri = first.optString("uri")
                require(uri.startsWith("data:")) { "External buffer URI '$uri' cannot be embedded automatically; create a self-contained GLB first." }
                binary = decodeDataUri(uri).bytes
                first.remove("uri")
            }
        }
        val extensions = LinkedHashSet<String>()
        listOf("extensionsUsed", "extensionsRequired").forEach { k -> parsedJson.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) extensions += a.optString(i) } }
        val unsupported = extensions.intersect(setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu"))
        require(unsupported.isEmpty()) { "unsupported compressed glTF extensions: ${unsupported.joinToString()}" }
        return SourceGlb(parsedJson, binary)
    }

    private fun rootNodes(json: JSONObject, nodeCount: Int): List<Int> {
        val scenes = json.optJSONArray("scenes")
        if (scenes != null && scenes.length() > 0) {
            val index = json.optInt("scene", 0).coerceIn(0, scenes.length() - 1)
            val array = scenes.getJSONObject(index).optJSONArray("nodes")
            if (array != null) return (0 until array.length()).map { array.getInt(it) }
        }
        val nodes = json.optJSONArray("nodes") ?: return emptyList()
        val children = HashSet<Int>()
        for (i in 0 until nodes.length()) nodes.getJSONObject(i).optJSONArray("children")?.let { a -> for (j in 0 until a.length()) children += a.getInt(j) }
        return (0 until nodeCount).filter { it !in children }
    }

    private fun appendArray(source: JSONArray?, target: JSONArray, rewrite: (JSONObject) -> Unit) {
        if (source == null) return
        for (i in 0 until source.length()) {
            val copy = JSONObject(source.getJSONObject(i).toString())
            rewrite(copy)
            target.put(copy)
        }
    }

    /** Accessor dictionaries (mesh attributes/morph targets/instancing attributes) contain only accessor indices. */
    private fun remapIntegerValues(json: JSONObject, offset: Int) {
        val keys = json.keys()
        val names = mutableListOf<String>()
        while (keys.hasNext()) names += keys.next()
        for (key in names) {
            val value = json.opt(key)
            if (value is Number) json.put(key, value.toInt() + offset)
        }
    }

    private fun remapMaterialTextureIndices(material: JSONObject, offset: Int) {
        fun walk(value: Any?, key: String = "") {
            when (value) {
                is JSONObject -> {
                    if (key.lowercase(Locale.ROOT).contains("texture") && value.has("index")) {
                        value.put("index", value.getInt("index") + offset)
                    }
                    val keys = value.keys(); val all = mutableListOf<String>()
                    while (keys.hasNext()) all += keys.next()
                    for (k in all) walk(value.opt(k), k)
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), key)
            }
        }
        walk(material)
    }

    private fun uniqueInstanceName(name: String, counts: MutableMap<String, Int>): String {
        val base = sanitize(name).ifBlank { "Object" }
        val count = (counts[base] ?: 0) + 1
        counts[base] = count
        return if (count == 1) base else "${base}_$count"
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var index = 2
        while (!used.add("${base}_$index")) index++
        return "${base}_$index"
    }

    private fun sanitize(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9_.-]+"), "_").trim('_')

    private fun padBinary(out: ByteArrayOutputStream) { while (out.size() % 4 != 0) out.write(0) }
    private fun align4(value: Int): Int = (value + 3) and -4
    private fun padBytes(input: ByteArray, value: Int): ByteArray = if (input.size % 4 == 0) input else input.copyOf(align4(input.size)).also { for (i in input.size until it.size) it[i] = value.toByte() }

    private fun calculateSceneBounds(json: JSONObject, binary: ByteArray): Bounds {
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val accessors = json.optJSONArray("accessors") ?: JSONArray()
        val views = json.optJSONArray("bufferViews") ?: JSONArray()
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        val roots = rootNodes(json, nodes.length())
        val visited = HashSet<Int>()
        fun visit(index: Int, parent: FloatArray) {
            if (index !in 0 until nodes.length() || !visited.add(index)) return
            val node = nodes.getJSONObject(index)
            val global = multiply(parent, localMatrix(node))
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex in 0 until meshes.length()) {
                val primitives = meshes.getJSONObject(meshIndex).optJSONArray("primitives")
                if (primitives != null) for (p in 0 until primitives.length()) {
                    val primitive = primitives.getJSONObject(p)
                    val positionAccessor = primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    if (positionAccessor !in 0 until accessors.length()) continue
                    val bounds = accessorBounds(positionAccessor, accessors, views, binary) ?: continue
                    for (x in floatArrayOf(bounds[0], bounds[3])) for (y in floatArrayOf(bounds[1], bounds[4])) for (z in floatArrayOf(bounds[2], bounds[5])) {
                        val tx = global[0] * x + global[4] * y + global[8] * z + global[12]
                        val ty = global[1] * x + global[5] * y + global[9] * z + global[13]
                        val tz = global[2] * x + global[6] * y + global[10] * z + global[14]
                        minX = min(minX, tx); minY = min(minY, ty); minZ = min(minZ, tz)
                        maxX = max(maxX, tx); maxY = max(maxY, ty); maxZ = max(maxZ, tz)
                    }
                }
            }
            node.optJSONArray("children")?.let { children -> for (c in 0 until children.length()) visit(children.getInt(c), global) }
        }
        roots.forEach { visit(it, identity()) }
        if (!minX.isFinite() || !maxX.isFinite()) {
            // Fallback for unusual files without a standard active scene hierarchy.
            for (i in 0 until accessors.length()) {
                val accessor = accessors.getJSONObject(i)
                if (accessor.optString("type") != "VEC3") continue
                val b = accessorBounds(i, accessors, views, binary) ?: continue
                minX = min(minX, b[0]); minY = min(minY, b[1]); minZ = min(minZ, b[2])
                maxX = max(maxX, b[3]); maxY = max(maxY, b[4]); maxZ = max(maxZ, b[5])
            }
        }
        require(minX.isFinite() && maxX.isFinite()) { "Model has no readable POSITION accessors" }
        return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun accessorBounds(index: Int, accessors: JSONArray, views: JSONArray, binary: ByteArray): FloatArray? {
        val accessor = accessors.optJSONObject(index) ?: return null
        val minArr = accessor.optJSONArray("min")
        val maxArr = accessor.optJSONArray("max")
        if (minArr != null && maxArr != null && minArr.length() >= 3 && maxArr.length() >= 3) {
            return floatArrayOf(minArr.getDouble(0).toFloat(), minArr.getDouble(1).toFloat(), minArr.getDouble(2).toFloat(), maxArr.getDouble(0).toFloat(), maxArr.getDouble(1).toFloat(), maxArr.getDouble(2).toFloat())
        }
        val viewIndex = accessor.optInt("bufferView", -1)
        if (viewIndex !in 0 until views.length() || accessor.optInt("componentType") != 5126 || accessor.optString("type") != "VEC3") return null
        val view = views.getJSONObject(viewIndex)
        val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val count = accessor.optInt("count", 0)
        val stride = view.optInt("byteStride", 12)
        if (count <= 0 || offset < 0 || offset.toLong() + (count - 1L) * stride + 12 > binary.size) return null
        val bb = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        val mn = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val mx = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (i in 0 until count) for (c in 0..2) {
            val value = bb.getFloat(offset + i * stride + c * 4)
            mn[c] = min(mn[c], value); mx[c] = max(mx[c], value)
        }
        return floatArrayOf(mn[0], mn[1], mn[2], mx[0], mx[1], mx[2])
    }

    private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private fun localMatrix(node: JSONObject): FloatArray {
        val matrix = node.optJSONArray("matrix")
        if (matrix != null && matrix.length() == 16) return FloatArray(16) { matrix.getDouble(it).toFloat() }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
        val out = identity()
        out[0] = (1 - 2 * (qy * qy + qz * qz)) * sx
        out[1] = (2 * (qx * qy + qz * qw)) * sx
        out[2] = (2 * (qx * qz - qy * qw)) * sx
        out[4] = (2 * (qx * qy - qz * qw)) * sy
        out[5] = (1 - 2 * (qx * qx + qz * qz)) * sy
        out[6] = (2 * (qy * qz + qx * qw)) * sy
        out[8] = (2 * (qx * qz + qy * qw)) * sz
        out[9] = (2 * (qy * qz - qx * qw)) * sz
        out[10] = (1 - 2 * (qx * qx + qy * qy)) * sz
        out[12] = tx; out[13] = ty; out[14] = tz
        return out
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0..3) for (row in 0..3) {
            var value = 0f
            for (k in 0..3) value += a[k * 4 + row] * b[column * 4 + k]
            out[column * 4 + row] = value
        }
        return out
    }
}
