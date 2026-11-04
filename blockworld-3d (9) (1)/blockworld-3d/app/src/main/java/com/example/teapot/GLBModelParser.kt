package com.example.teapot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import android.util.Base64
import android.util.Log
import com.example.graphics.MeshBuilder
import com.example.model.Vector3
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Static + skeletal-animation glTF 2.0 GLB importer for the project's OpenGL ES 2 renderer.
 * Supports indexed triangle/list-strip-fan geometry, node TRS transforms, skins with JOINTS_0 /
 * WEIGHTS_0, LINEAR/STEP/CUBICSPLINE animation clips and embedded bitmap base-colour textures.
 * Draco/Meshopt and KTX2/BasisU are intentionally rejected with a useful message.
 */
object GLBModelParser {
    private const val GLB_MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942
    private const val MAX_FILE_BYTES = 1536L * 1024L * 1024L
    private const val MAX_SOURCE_VERTICES = 4_000_000
    private const val MAX_SOURCE_INDICES = 12_000_000
    private const val MAX_TOTAL_TRIANGLES = 3_000_000
    private const val MAX_PART_VERTICES = 60_000
    private const val MAX_TEXTURE_EDGE = 2048

    private data class GlbDocument(val json: JSONObject, val binary: ByteBuffer)
    private data class MeshInstance(val meshIndex: Int, val nodeIndex: Int, val skinIndex: Int, val transform: FloatArray)
    private data class PrimitiveSpec(
        val attributes: JSONObject,
        val primitive: JSONObject,
        val mode: Int,
        val material: JSONObject?,
        val nodeIndex: Int,
        val skinIndex: Int,
        val nodeTransform: FloatArray
    )

    fun parse(
        inputStream: InputStream,
        modelId: String,
        modelName: String,
        category: String = "Imported",
        defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f),
        onProgress: ((Int, String) -> Unit)? = null
    ): Custom3DMesh {
        onProgress?.invoke(3, "Reading model file")
        val bytes = inputStream.use { readBounded(it, MAX_FILE_BYTES) }
        onProgress?.invoke(12, "Reading model file")
        return parseDocument(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), modelId, modelName, category, defaultColor, onProgress)
    }

    /** Prefer this file overload for imported models; it memory-maps the GLB instead of duplicating the whole file. */
    fun parse(
        file: File,
        modelId: String,
        modelName: String,
        category: String = "Imported",
        defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f),
        onProgress: ((Int, String) -> Unit)? = null
    ): Custom3DMesh {
        onProgress?.invoke(3, "Opening model file")
        require(file.isFile) { "GLB file does not exist" }
        require(file.length() in 20L..MAX_FILE_BYTES) { "GLB size is outside the supported 20-byte to 1.5-GB range" }
        FileInputStream(file).channel.use { channel ->
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
            onProgress?.invoke(12, "Reading model file")
            return parseDocument(mapped, modelId, modelName, category, defaultColor, onProgress)
        }
    }

    private fun parseDocument(
        input: ByteBuffer,
        modelId: String,
        modelName: String,
        category: String,
        defaultColor: FloatArray,
        onProgress: ((Int, String) -> Unit)?
    ): Custom3DMesh {
        val buffer = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        onProgress?.invoke(16, "Reading model structure")
        require(buffer.remaining() >= 20) { "GLB file is too small" }
        require(buffer.int == GLB_MAGIC) { "Invalid GLB header. Select a binary .glb file, not a plain .gltf file." }
        val version = buffer.int
        val totalLength = buffer.int.toLong() and 0xFFFFFFFFL
        require(version == 2) { "Only glTF 2.0 GLB files are supported" }
        require(totalLength in 20L..buffer.limit().toLong()) { "Invalid GLB declared file length" }

        var jsonText: String? = null
        var binary = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.position().toLong() + 8L <= totalLength) {
            val chunkLength = buffer.int.toLong() and 0xFFFFFFFFL
            val chunkType = buffer.int
            require(chunkLength <= totalLength - buffer.position().toLong() && chunkLength <= Int.MAX_VALUE.toLong()) {
                "Invalid GLB chunk length"
            }
            val start = buffer.position()
            when (chunkType) {
                JSON_CHUNK -> {
                    val jsonBytes = ByteArray(chunkLength.toInt())
                    buffer.get(jsonBytes)
                    jsonText = String(jsonBytes, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
                }
                BIN_CHUNK -> {
                    val part = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    part.position(start)
                    part.limit(start + chunkLength.toInt())
                    binary = part.slice().order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(start + chunkLength.toInt())
                }
                else -> buffer.position(start + chunkLength.toInt())
            }
        }
        val json = JSONObject(jsonText ?: throw IllegalArgumentException("GLB contains no JSON chunk"))
        val extensionNames = buildSet {
            listOf("extensionsUsed", "extensionsRequired").forEach { key ->
                json.optJSONArray(key)?.let { names -> for (i in 0 until names.length()) add(names.optString(i)) }
            }
        }
        val unsupported = extensionNames.intersect(setOf("KHR_draco_mesh_compression", "EXT_meshopt_compression"))
        require(unsupported.isEmpty()) {
            "This GLB uses compressed geometry (${unsupported.joinToString()}). Export an uncompressed GLB from Blender."
        }
        require(extensionNames.none { it == "KHR_texture_basisu" }) {
            "This GLB uses KTX2/BasisU textures. Export embedded PNG/JPEG textures for the current renderer."
        }

        val meshes = json.optJSONArray("meshes") ?: throw IllegalArgumentException("GLB has no meshes")
        require(meshes.length() > 0) { "GLB has no mesh primitives" }
        val accessors = json.optJSONArray("accessors") ?: throw IllegalArgumentException("GLB has no accessors")
        val bufferViews = json.optJSONArray("bufferViews") ?: JSONArray()
        val materials = json.optJSONArray("materials")
        val textures = json.optJSONArray("textures")
        val images = json.optJSONArray("images")
        val samplers = json.optJSONArray("samplers")
        val nodesJson = json.optJSONArray("nodes") ?: JSONArray()
        val nodeData = parseNodes(nodesJson)
        val skinData = parseSkins(json.optJSONArray("skins"), accessors, bufferViews, binary)
        val clips = parseAnimations(json.optJSONArray("animations"), accessors, bufferViews, binary)
        val bindGlobalMatrices = computeGlobalBindMatrices(nodeData)
        val bindSkinPalettes = buildSkinPalettes(skinData, bindGlobalMatrices)
        val instances = collectMeshInstances(json, meshes.length(), nodeData)
        val specs = ArrayList<PrimitiveSpec>()
        onProgress?.invoke(24, "Analyzing geometry")
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        // Pass 1 computes shared bounds from all active scene nodes and all primitives.
        var analyzedInstances = 0
        val totalInstances = instances.size.coerceAtLeast(1)
        for (instance in instances) {
            if (instance.meshIndex !in 0 until meshes.length()) continue
            val primitiveList = meshes.getJSONObject(instance.meshIndex).optJSONArray("primitives") ?: continue
            for (primitiveIndex in 0 until primitiveList.length()) {
                val primitive = primitiveList.getJSONObject(primitiveIndex)
                val mode = primitive.optInt("mode", 4)
                if (mode !in 4..6) continue
                val attributes = primitive.optJSONObject("attributes") ?: continue
                if (!attributes.has("POSITION")) continue
                val sourcePositions = readFloatAccessor(attributes.getInt("POSITION"), accessors, bufferViews, binary, 3)
                val skin = skinData.getOrNull(instance.skinIndex)
                val jointAccessor = attributes.optInt("JOINTS_0", -1)
                val weightAccessor = attributes.optInt("WEIGHTS_0", -1)
                val positions = if (skin != null && jointAccessor >= 0 && weightAccessor >= 0) {
                    val bindJoints = readFloatAccessor(jointAccessor, accessors, bufferViews, binary, 4)
                        .map { it.toInt().coerceAtLeast(0) }.toIntArray()
                    val bindWeights = readFloatAccessor(weightAccessor, accessors, bufferViews, binary, 4)
                    normalizeWeights(bindWeights)
                    skinPositionsAtBindPose(sourcePositions, bindJoints, bindWeights, bindSkinPalettes[instance.skinIndex])
                } else {
                    sourcePositions.also { transformPositionsInPlace(it, instance.transform) }
                }
                for (i in positions.indices step 3) {
                    if (i + 2 >= positions.size) break
                    val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                    minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
                    maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
                }
                val materialIndex = primitive.optInt("material", -1)
                val material = materials?.let { if (materialIndex in 0 until it.length()) it.getJSONObject(materialIndex) else null }
                specs.add(PrimitiveSpec(attributes, primitive, mode, material, instance.nodeIndex, instance.skinIndex, instance.transform))
            }
            analyzedInstances++
            onProgress?.invoke(24 + (analyzedInstances * 26 / totalInstances), "Analyzing geometry")
        }
        require(specs.isNotEmpty() && minX.isFinite() && maxX.isFinite()) {
            "No renderable mesh primitives were found in the GLB"
        }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f
        val extent = max(maxX - minX, max(maxY - minY, maxZ - minZ)).coerceAtLeast(0.00001f)
        // Normalize exported centimetre/millimetre assets so they do not disappear outside the camera range.
        val coordinateScale = 2.5f / extent
        val textureCache = HashMap<Int, Bitmap?>()
        val outputParts = ArrayList<Custom3DMeshPart>()
        var remainingTriangles = MAX_TOTAL_TRIANGLES
        val triEstimates = specs.map { estimateTriangles(it, accessors) }
        val totalTrianglesEstimate = triEstimates.sum().coerceAtLeast(1L)
        val wasReducedForMobile = totalTrianglesEstimate > MAX_TOTAL_TRIANGLES.toLong()
        val importWarning = if (wasReducedForMobile) {
            "The model exceeded the 3-million-triangle mobile budget; geometry was sampled to keep it renderable."
        } else null

        onProgress?.invoke(52, "Building meshes and textures")
        for ((specIndex, spec) in specs.withIndex()) {
            if (remainingTriangles <= 0) break
            val sourcePositions = readFloatAccessor(spec.attributes.getInt("POSITION"), accessors, bufferViews, binary, 3)
            val vertexCount = sourcePositions.size / 3
            val rawIndices = if (spec.primitive.has("indices")) {
                readIndexAccessor(spec.primitive.getInt("indices"), accessors, bufferViews, binary)
            } else IntArray(vertexCount) { it }

            val sourceNormals = if (spec.attributes.has("NORMAL")) {
                readFloatAccessor(spec.attributes.getInt("NORMAL"), accessors, bufferViews, binary, 3)
            } else generateVertexNormals(sourcePositions, rawIndices, spec.mode)

            val jointsAccessor = spec.attributes.optInt("JOINTS_0", -1)
            val weightsAccessor = spec.attributes.optInt("WEIGHTS_0", -1)
            val skin = skinData.getOrNull(spec.skinIndex)
            val joints = if (skin != null && jointsAccessor >= 0 && weightsAccessor >= 0) {
                readFloatAccessor(jointsAccessor, accessors, bufferViews, binary, 4)
                    .map { it.toInt().coerceAtLeast(0) }.toIntArray()
            } else null
            val weights = if (joints != null) {
                readFloatAccessor(weightsAccessor, accessors, bufferViews, binary, 4).also { normalizeWeights(it) }
            } else null
            val usableSkinIndex = if (
                skin != null && joints != null && weights != null &&
                joints.size == vertexCount * 4 && weights.size == vertexCount * 4
            ) spec.skinIndex else -1

            // A skinned GLB's raw POSITION values are not the displayed bind pose by themselves.
            // Skin them once before the first draw, using the same joint palette used by the
            // animation runtime. This fixes characters that otherwise appear lying on their side
            // until a first animation frame is applied, and keeps centering/scaling consistent.
            val transformedPositions = if (usableSkinIndex >= 0) {
                skinPositionsAtBindPose(sourcePositions, joints!!, weights!!, bindSkinPalettes[usableSkinIndex])
            } else {
                sourcePositions.clone().also { transformPositionsInPlace(it, spec.nodeTransform) }
            }
            val transformedNormals = if (usableSkinIndex >= 0) {
                skinNormalsAtBindPose(sourceNormals, joints!!, weights!!, bindSkinPalettes[usableSkinIndex])
            } else {
                sourceNormals.clone().also { transformNormalsInPlace(it, spec.nodeTransform) }
            }
            val fullTriangleCount = estimateModeTriangles(rawIndices.size, spec.mode)
            if (fullTriangleCount <= 0) continue
            val proportionalBudget = if (totalTrianglesEstimate > MAX_TOTAL_TRIANGLES) {
                (triEstimates[specIndex].toDouble() * MAX_TOTAL_TRIANGLES / totalTrianglesEstimate).toInt().coerceAtLeast(1)
            } else fullTriangleCount
            val triangleBudget = min(remainingTriangles, min(fullTriangleCount, proportionalBudget))
            val triangles = triangulate(rawIndices, spec.mode, triangleBudget)
            if (triangles.isEmpty()) continue
            remainingTriangles -= triangles.size / 3

            val pbr = spec.material?.optJSONObject("pbrMetallicRoughness")
            val textureInfo = pbr?.optJSONObject("baseColorTexture")
                ?: spec.material?.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")?.optJSONObject("diffuseTexture")
            val texCoordIndex = textureInfo?.optInt("texCoord", 0) ?: 0
            val texCoordKey = "TEXCOORD_$texCoordIndex"
            val sourceUvs = if (spec.attributes.has(texCoordKey)) readFloatAccessor(spec.attributes.getInt(texCoordKey), accessors, bufferViews, binary, 2) else null
            require(textureInfo == null || sourceUvs != null) {
                "Material '${spec.material?.optString("name", "material")}' references missing $texCoordKey UV data"
            }
            textureInfo?.let { applyTextureTransform(sourceUvs!!, it) }

            var wrapS = 10497; var wrapT = 10497; var minFilter = 9987; var magFilter = 9729
            val textureIndex = textureInfo?.optInt("index", -1) ?: -1
            val textureDefinition = textures?.let { if (textureIndex in 0 until it.length()) it.getJSONObject(textureIndex) else null }
            if (textureDefinition != null) {
                val samplerIndex = textureDefinition.optInt("sampler", -1)
                val sampler = samplers?.let { if (samplerIndex in 0 until it.length()) it.getJSONObject(samplerIndex) else null }
                wrapS = sampler?.optInt("wrapS", wrapS) ?: wrapS
                wrapT = sampler?.optInt("wrapT", wrapT) ?: wrapT
                minFilter = sampler?.optInt("minFilter", minFilter) ?: minFilter
                magFilter = sampler?.optInt("magFilter", magFilter) ?: magFilter
            }
            val imageIndex = textureDefinition?.let { it.optInt("source", -1) } ?: -1
            val bitmap: Bitmap? = if (imageIndex >= 0 && images != null && imageIndex < images.length()) {
                if (textureCache.containsKey(imageIndex)) textureCache[imageIndex]
                else decodeImage(images.getJSONObject(imageIndex), bufferViews, binary).also { textureCache[imageIndex] = it }
            } else null
            if (textureInfo != null && bitmap == null) {
                Log.w("GLBModelParser", "Base-colour image could not be decoded; material '${spec.material?.optString("name", "material")}' falls back to its authored color")
            }
            val materialColor = readBaseColorFactor(pbr, spec.material, bitmap != null, defaultColor)
            val doubleSided = spec.material?.optBoolean("doubleSided", false) ?: false

            outputParts += buildIndexedParts(
                positions = transformedPositions,
                normals = transformedNormals,
                uvs = sourceUvs,
                triangles = triangles,
                center = floatArrayOf(centerX, centerY, centerZ),
                coordinateScale = coordinateScale,
                baseColor = materialColor,
                bitmap = bitmap,
                wrapS = wrapS,
                wrapT = wrapT,
                minFilter = minFilter,
                magFilter = magFilter,
                doubleSided = doubleSided,
                sourcePositions = if (usableSkinIndex >= 0) sourcePositions else null,
                sourceNormals = if (usableSkinIndex >= 0) sourceNormals else null,
                joints = if (usableSkinIndex >= 0) joints else null,
                weights = if (usableSkinIndex >= 0) weights else null,
                skinIndex = usableSkinIndex
            )
            onProgress?.invoke(52 + ((specIndex + 1) * 43 / specs.size.coerceAtLeast(1)), "Building meshes and textures")
        }
        require(outputParts.isNotEmpty()) { "The GLB contains no renderable triangles" }

        val animations = if (clips.isNotEmpty() && skinData.isNotEmpty()) {
            GltfAnimationRuntime(
                nodes = nodeData,
                skins = skinData,
                clips = clips,
                boundsCenter = floatArrayOf(centerX, centerY, centerZ),
                coordinateScale = coordinateScale
            )
        } else null
        if (animations != null && !animations.supportsSkeletalAnimation) {
            Log.w("GLBModelParser", "GLB has animation clips but no animation channels targeting this model's skin joints")
        } else if (animations != null) {
            Log.i("GLBModelParser", "$modelName: skeletal clips=${animations.animationNames.joinToString()}, skinnedParts=${outputParts.count { it.skinningData != null }}")
        }
        onProgress?.invoke(98, "Finalizing model")
        val result = Custom3DMesh(
            id = modelId,
            name = modelName,
            category = category,
            vertexBuffer = outputParts.first().vertexBuffer,
            normalBuffer = outputParts.first().normalBuffer,
            indexBuffer = outputParts.first().indexBuffer,
            vertexCount = outputParts.sumOf { it.vertexCount },
            defaultColor = outputParts.first().baseColor,
            boundsSize = Vector3(
                ((maxX - minX) * coordinateScale).coerceAtLeast(0.05f),
                ((maxY - minY) * coordinateScale).coerceAtLeast(0.05f),
                ((maxZ - minZ) * coordinateScale).coerceAtLeast(0.05f)
            ),
            isUserImported = true,
            renderParts = outputParts,
            animationRuntime = animations,
            wasReducedForMobile = wasReducedForMobile,
            importWarning = importWarning
        )
        onProgress?.invoke(100, "Model ready")
        return result
    }

    private fun buildIndexedParts(
        positions: FloatArray,
        normals: FloatArray,
        uvs: FloatArray?,
        triangles: IntArray,
        center: FloatArray,
        coordinateScale: Float,
        baseColor: FloatArray,
        bitmap: Bitmap?,
        wrapS: Int,
        wrapT: Int,
        minFilter: Int,
        magFilter: Int,
        doubleSided: Boolean,
        sourcePositions: FloatArray?,
        sourceNormals: FloatArray?,
        joints: IntArray?,
        weights: FloatArray?,
        skinIndex: Int
    ): List<Custom3DMeshPart> {
        val built = ArrayList<Custom3DMeshPart>()
        val builder = BatchBuilder(uvs != null, sourcePositions != null)

        fun flush() {
            if (builder.indexCount == 0) return
            val outPositions = builder.positions.copyOf(builder.vertexCount * 3)
            val outNormals = builder.normals.copyOf(builder.vertexCount * 3)
            val skinning = if (skinIndex >= 0 && sourcePositions != null && sourceNormals != null && joints != null && weights != null) {
                GltfSkinningPartData(
                    skinIndex = skinIndex,
                    sourcePositions = builder.skinPositions.copyOf(builder.vertexCount * 3),
                    sourceNormals = builder.skinNormals.copyOf(builder.vertexCount * 3),
                    joints = builder.skinJoints.copyOf(builder.vertexCount * 4),
                    weights = builder.skinWeights.copyOf(builder.vertexCount * 4)
                )
            } else null
            built.add(
                Custom3DMeshPart(
                    vertexBuffer = MeshBuilder.createFloatBuffer(outPositions),
                    normalBuffer = MeshBuilder.createFloatBuffer(outNormals),
                    indexBuffer = MeshBuilder.createShortBuffer(builder.indices.copyOf(builder.indexCount)),
                    vertexCount = builder.indexCount,
                    texCoordBuffer = builder.uvs?.let { MeshBuilder.createFloatBuffer(it.copyOf(builder.vertexCount * 2)) },
                    textureBitmap = bitmap,
                    baseColor = baseColor.clone(),
                    restPositions = outPositions.clone(),
                    restNormals = outNormals.clone(),
                    skinningData = skinning,
                    samplerWrapS = wrapS,
                    samplerWrapT = wrapT,
                    samplerMinFilter = minFilter,
                    samplerMagFilter = magFilter,
                    doubleSided = doubleSided
                )
            )
            builder.clear()
        }

        for (tri in 0 until triangles.size / 3) {
            val a = triangles[tri * 3]; val b = triangles[tri * 3 + 1]; val c = triangles[tri * 3 + 2]
            if (a !in 0 until positions.size / 3 || b !in 0 until positions.size / 3 || c !in 0 until positions.size / 3) continue
            var newVertices = 0
            if (!builder.contains(a)) newVertices++
            if (b != a && !builder.contains(b)) newVertices++
            if (c != a && c != b && !builder.contains(c)) newVertices++
            if (builder.vertexCount + newVertices > MAX_PART_VERTICES || builder.indexCount + 3 > 300_000) flush()
            val ia = builder.add(a, positions, normals, uvs, center, coordinateScale, sourcePositions, sourceNormals, joints, weights)
            val ib = builder.add(b, positions, normals, uvs, center, coordinateScale, sourcePositions, sourceNormals, joints, weights)
            val ic = builder.add(c, positions, normals, uvs, center, coordinateScale, sourcePositions, sourceNormals, joints, weights)
            builder.indices[builder.indexCount++] = ia.toShort()
            builder.indices[builder.indexCount++] = ib.toShort()
            builder.indices[builder.indexCount++] = ic.toShort()
        }
        flush()
        return built
    }

    private class BatchBuilder(private val withUvs: Boolean, private val withSkin: Boolean) {
        val positions = FloatArray(MAX_PART_VERTICES * 3)
        val normals = FloatArray(MAX_PART_VERTICES * 3)
        val uvs: FloatArray? = if (withUvs) FloatArray(MAX_PART_VERTICES * 2) else null
        val indices = ShortArray(300_000)
        val skinPositions = if (withSkin) FloatArray(MAX_PART_VERTICES * 3) else FloatArray(0)
        val skinNormals = if (withSkin) FloatArray(MAX_PART_VERTICES * 3) else FloatArray(0)
        val skinJoints = if (withSkin) IntArray(MAX_PART_VERTICES * 4) else IntArray(0)
        val skinWeights = if (withSkin) FloatArray(MAX_PART_VERTICES * 4) else FloatArray(0)
        private val remap = HashMap<Int, Int>(MAX_PART_VERTICES)
        var vertexCount = 0; private set
        var indexCount = 0

        fun contains(source: Int) = remap.containsKey(source)

        fun add(
            source: Int,
            positionsSource: FloatArray,
            normalsSource: FloatArray,
            uvSource: FloatArray?,
            center: FloatArray,
            scale: Float,
            rawPositions: FloatArray?,
            rawNormals: FloatArray?,
            joints: IntArray?,
            weights: FloatArray?
        ): Int {
            remap[source]?.let { return it }
            val local = vertexCount++
            remap[source] = local
            val src3 = source * 3; val dst3 = local * 3
            positions[dst3] = (positionsSource[src3] - center[0]) * scale
            positions[dst3 + 1] = (positionsSource[src3 + 1] - center[1]) * scale
            positions[dst3 + 2] = (positionsSource[src3 + 2] - center[2]) * scale
            normals[dst3] = normalsSource.getOrElse(src3) { 0f }
            normals[dst3 + 1] = normalsSource.getOrElse(src3 + 1) { 1f }
            normals[dst3 + 2] = normalsSource.getOrElse(src3 + 2) { 0f }
            if (uvs != null && uvSource != null && source * 2 + 1 < uvSource.size) {
                uvs[local * 2] = uvSource[source * 2]
                uvs[local * 2 + 1] = uvSource[source * 2 + 1]
            }
            if (withSkin && rawPositions != null && rawNormals != null && joints != null && weights != null) {
                skinPositions[dst3] = rawPositions.getOrElse(src3) { 0f }
                skinPositions[dst3 + 1] = rawPositions.getOrElse(src3 + 1) { 0f }
                skinPositions[dst3 + 2] = rawPositions.getOrElse(src3 + 2) { 0f }
                skinNormals[dst3] = rawNormals.getOrElse(src3) { 0f }
                skinNormals[dst3 + 1] = rawNormals.getOrElse(src3 + 1) { 1f }
                skinNormals[dst3 + 2] = rawNormals.getOrElse(src3 + 2) { 0f }
                for (j in 0..3) {
                    skinJoints[local * 4 + j] = joints.getOrElse(source * 4 + j) { 0 }
                    skinWeights[local * 4 + j] = weights.getOrElse(source * 4 + j) { if (j == 0) 1f else 0f }
                }
            }
            return local
        }

        fun clear() { remap.clear(); vertexCount = 0; indexCount = 0 }
    }

    private fun parseNodes(nodesJson: JSONArray): List<GltfNodeData> {
        val parent = IntArray(nodesJson.length()) { -1 }
        for (i in 0 until nodesJson.length()) {
            val children = nodesJson.optJSONObject(i)?.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.optInt(c, -1)
                if (child in parent.indices && parent[child] < 0) parent[child] = i
            }
        }
        return List(nodesJson.length()) { index ->
            val node = nodesJson.getJSONObject(index)
            val t = node.optJSONArray("translation")?.toFloatArray(3, floatArrayOf(0f, 0f, 0f)) ?: floatArrayOf(0f, 0f, 0f)
            val r = node.optJSONArray("rotation")?.toFloatArray(4, floatArrayOf(0f, 0f, 0f, 1f)) ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = node.optJSONArray("scale")?.toFloatArray(3, floatArrayOf(1f, 1f, 1f)) ?: floatArrayOf(1f, 1f, 1f)
            val matrix = node.optJSONArray("matrix")?.takeIf { it.length() == 16 }?.toFloatArray(16, identityMatrix())
            GltfNodeData(parent[index], t, r, s, matrix, node.optString("name").takeIf { it.isNotBlank() })
        }
    }

    /** Compute the bind-pose global matrix for every node in glTF column-major order. */
    private fun computeGlobalBindMatrices(nodes: List<GltfNodeData>): Array<FloatArray> {
        val globals = Array(nodes.size) { FloatArray(16) }
        val status = IntArray(nodes.size)
        fun visit(index: Int) {
            if (index !in nodes.indices || status[index] == 2 || status[index] == 1) return
            status[index] = 1
            val node = nodes[index]
            val local = node.matrix?.clone() ?: composeTrs(node.translation, node.rotation, node.scale)
            val parent = node.parentIndex
            if (parent in nodes.indices && parent != index) {
                visit(parent)
                Matrix.multiplyMM(globals[index], 0, globals[parent], 0, local, 0)
            } else {
                System.arraycopy(local, 0, globals[index], 0, 16)
            }
            status[index] = 2
        }
        for (i in nodes.indices) visit(i)
        return globals
    }

    /** Palette entries use the same global-joint * inverse-bind convention as GltfAnimationRuntime. */
    private fun buildSkinPalettes(
        skins: List<GltfSkinData>,
        globalMatrices: Array<FloatArray>
    ): Array<Array<FloatArray>> = Array(skins.size) { skinIndex ->
        val skin = skins[skinIndex]
        Array(skin.joints.size) { slot ->
            val result = FloatArray(16)
            val nodeIndex = skin.joints[slot]
            val inverseBind = skin.inverseBindMatrices.getOrNull(slot) ?: identityMatrix()
            if (nodeIndex in globalMatrices.indices) {
                Matrix.multiplyMM(result, 0, globalMatrices[nodeIndex], 0, inverseBind, 0)
            } else {
                System.arraycopy(inverseBind, 0, result, 0, 16)
            }
            result
        }
    }

    private fun skinPositionsAtBindPose(
        sourcePositions: FloatArray,
        joints: IntArray,
        weights: FloatArray,
        palette: Array<FloatArray>
    ): FloatArray {
        val output = FloatArray(sourcePositions.size)
        val vertexCount = sourcePositions.size / 3
        for (vertex in 0 until vertexCount) {
            val p = vertex * 3
            val jBase = vertex * 4
            val x = sourcePositions[p]; val y = sourcePositions[p + 1]; val z = sourcePositions[p + 2]
            var ox = 0f; var oy = 0f; var oz = 0f; var total = 0f
            for (influence in 0..3) {
                val weight = weights.getOrElse(jBase + influence) { 0f }.coerceAtLeast(0f)
                val jointSlot = joints.getOrElse(jBase + influence) { -1 }
                if (weight <= 0.00001f || jointSlot !in palette.indices) continue
                val m = palette[jointSlot]
                ox += (m[0] * x + m[4] * y + m[8] * z + m[12]) * weight
                oy += (m[1] * x + m[5] * y + m[9] * z + m[13]) * weight
                oz += (m[2] * x + m[6] * y + m[10] * z + m[14]) * weight
                total += weight
            }
            if (total > 0.00001f) {
                val inv = 1f / total
                output[p] = ox * inv; output[p + 1] = oy * inv; output[p + 2] = oz * inv
            } else {
                output[p] = x; output[p + 1] = y; output[p + 2] = z
            }
        }
        return output
    }

    private fun skinNormalsAtBindPose(
        sourceNormals: FloatArray,
        joints: IntArray,
        weights: FloatArray,
        palette: Array<FloatArray>
    ): FloatArray {
        val output = FloatArray(sourceNormals.size)
        val vertexCount = sourceNormals.size / 3
        for (vertex in 0 until vertexCount) {
            val p = vertex * 3
            val jBase = vertex * 4
            val x = sourceNormals[p]; val y = sourceNormals[p + 1]; val z = sourceNormals[p + 2]
            var ox = 0f; var oy = 0f; var oz = 0f; var total = 0f
            for (influence in 0..3) {
                val weight = weights.getOrElse(jBase + influence) { 0f }.coerceAtLeast(0f)
                val jointSlot = joints.getOrElse(jBase + influence) { -1 }
                if (weight <= 0.00001f || jointSlot !in palette.indices) continue
                val m = palette[jointSlot]
                ox += (m[0] * x + m[4] * y + m[8] * z) * weight
                oy += (m[1] * x + m[5] * y + m[9] * z) * weight
                oz += (m[2] * x + m[6] * y + m[10] * z) * weight
                total += weight
            }
            if (total > 0.00001f) {
                ox /= total; oy /= total; oz /= total
            } else {
                ox = x; oy = y; oz = z
            }
            val length = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(0.00001f)
            output[p] = ox / length; output[p + 1] = oy / length; output[p + 2] = oz / length
        }
        return output
    }

    private fun parseSkins(skinsJson: JSONArray?, accessors: JSONArray, views: JSONArray, binary: ByteBuffer): List<GltfSkinData> {
        if (skinsJson == null) return emptyList()
        return List(skinsJson.length()) { i ->
            val skin = skinsJson.getJSONObject(i)
            val jointsJson = skin.getJSONArray("joints")
            val joints = IntArray(jointsJson.length()) { jointsJson.optInt(it, -1) }
            val inverse = if (skin.has("inverseBindMatrices")) {
                readFloatAccessor(skin.getInt("inverseBindMatrices"), accessors, views, binary, 16)
            } else FloatArray(joints.size * 16).also { arr ->
                for (j in joints.indices) System.arraycopy(identityMatrix(), 0, arr, j * 16, 16)
            }
            val matrices = joints.indices.map { j ->
                FloatArray(16) { k -> inverse.getOrElse(j * 16 + k) { if (k % 5 == 0) 1f else 0f } }
            }
            GltfSkinData(joints, matrices)
        }
    }

    private fun parseAnimations(animationsJson: JSONArray?, accessors: JSONArray, views: JSONArray, binary: ByteBuffer): List<GltfAnimationClipData> {
        if (animationsJson == null) return emptyList()
        val out = ArrayList<GltfAnimationClipData>()
        for (animationIndex in 0 until animationsJson.length()) {
            val animation = animationsJson.getJSONObject(animationIndex)
            val samplersJson = animation.optJSONArray("samplers") ?: continue
            val samplers = ArrayList<GltfAnimationSamplerData>()
            for (i in 0 until samplersJson.length()) {
                val sampler = samplersJson.getJSONObject(i)
                val inputIndex = sampler.optInt("input", -1)
                val outputIndex = sampler.optInt("output", -1)
                if (inputIndex !in 0 until accessors.length() || outputIndex !in 0 until accessors.length()) {
                    samplers.add(GltfAnimationSamplerData(FloatArray(0), FloatArray(0), 0, "LINEAR")); continue
                }
                val times = readFloatAccessor(inputIndex, accessors, views, binary, 1)
                val components = accessorComponents(accessors.getJSONObject(outputIndex).optString("type", "VEC3"))
                val values = readFloatAccessor(outputIndex, accessors, views, binary, components)
                samplers.add(GltfAnimationSamplerData(times, values, components, sampler.optString("interpolation", "LINEAR")))
            }
            val channelsJson = animation.optJSONArray("channels") ?: JSONArray()
            val channels = ArrayList<GltfAnimationChannelData>()
            for (c in 0 until channelsJson.length()) {
                val channel = channelsJson.getJSONObject(c)
                val target = channel.optJSONObject("target") ?: continue
                val path = target.optString("path", "")
                if (path !in setOf("translation", "rotation", "scale", "weights")) continue
                channels.add(GltfAnimationChannelData(channel.optInt("sampler", -1), target.optInt("node", -1), path))
            }
            val duration = samplers.maxOfOrNull { it.times.maxOrNull() ?: 0f } ?: 0f
            val name = animation.optString("name", "Animation ${animationIndex + 1}").ifBlank { "Animation ${animationIndex + 1}" }
            if (channels.isNotEmpty() && duration > 0f) out.add(GltfAnimationClipData(name, samplers, channels, duration))
        }
        return out
    }

    private fun collectMeshInstances(json: JSONObject, meshCount: Int, nodes: List<GltfNodeData>): List<MeshInstance> {
        val nodesJson = json.optJSONArray("nodes")
        val scenes = json.optJSONArray("scenes")
        if (nodesJson == null || nodes.isEmpty()) return (0 until meshCount).map { MeshInstance(it, -1, -1, identityMatrix()) }
        val output = ArrayList<MeshInstance>()
        val visited = HashSet<Int>()
        val sceneRoots = scenes?.let { arr ->
            val sceneIndex = json.optInt("scene", 0).coerceIn(0, (arr.length() - 1).coerceAtLeast(0))
            arr.optJSONObject(sceneIndex)?.optJSONArray("nodes")
        }
        fun visit(index: Int, parentMatrix: FloatArray, depth: Int) {
            if (index !in 0 until nodesJson.length() || depth > 128 || !visited.add(index)) return
            val node = nodesJson.getJSONObject(index)
            val local = nodeLocalMatrix(node, nodes[index])
            val world = FloatArray(16)
            Matrix.multiplyMM(world, 0, parentMatrix, 0, local, 0)
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex >= 0) output.add(MeshInstance(meshIndex, index, node.optInt("skin", -1), world))
            val children = node.optJSONArray("children")
            if (children != null) for (i in 0 until children.length()) visit(children.optInt(i, -1), world, depth + 1)
            visited.remove(index)
        }
        if (sceneRoots != null && sceneRoots.length() > 0) {
            for (i in 0 until sceneRoots.length()) visit(sceneRoots.optInt(i, -1), identityMatrix(), 0)
        } else {
            for (i in nodes.indices) if (nodes[i].parentIndex < 0) visit(i, identityMatrix(), 0)
        }
        if (output.isEmpty()) return (0 until meshCount).map { MeshInstance(it, -1, -1, identityMatrix()) }
        return output
    }

    private fun nodeLocalMatrix(nodeJson: JSONObject, data: GltfNodeData): FloatArray {
        data.matrix?.let { return it.clone() }
        return composeTrs(data.translation, data.rotation, data.scale)
    }

    private fun composeTrs(t: FloatArray, qIn: FloatArray, s: FloatArray): FloatArray {
        val q = qIn.clone()
        val ql = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).coerceAtLeast(0.000001f)
        for (i in 0..3) q[i] /= ql
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            (1f - 2f * (yy + zz)) * s[0], (2f * (xy + wz)) * s[0], (2f * (xz - wy)) * s[0], 0f,
            (2f * (xy - wz)) * s[1], (1f - 2f * (xx + zz)) * s[1], (2f * (yz + wx)) * s[1], 0f,
            (2f * (xz + wy)) * s[2], (2f * (yz - wx)) * s[2], (1f - 2f * (xx + yy)) * s[2], 0f,
            t[0], t[1], t[2], 1f
        )
    }

    private fun estimateTriangles(spec: PrimitiveSpec, accessors: JSONArray): Long {
        val count = if (spec.primitive.has("indices")) {
            accessors.getJSONObject(spec.primitive.getInt("indices")).optLong("count", 0L)
        } else {
            accessors.getJSONObject(spec.attributes.getInt("POSITION")).optLong("count", 0L)
        }
        return estimateModeTriangles(count.toInt(), spec.mode).toLong()
    }

    private fun estimateModeTriangles(count: Int, mode: Int): Int = when (mode) {
        4 -> count / 3
        5, 6 -> (count - 2).coerceAtLeast(0)
        else -> 0
    }

    private fun triangulate(source: IntArray, mode: Int, maxTriangles: Int): IntArray {
        val total = estimateModeTriangles(source.size, mode)
        val target = min(total, maxTriangles.coerceAtLeast(0))
        if (target <= 0) return IntArray(0)
        val result = IntArray(target * 3)
        // Uniformly sample complete triangles across all primitives; do not stop at the first primitive.
        for (sample in 0 until target) {
            val triangle = ((sample.toLong() * total) / target).toInt().coerceIn(0, total - 1)
            val out = sample * 3
            when (mode) {
                4 -> { val src = triangle * 3; result[out] = source[src]; result[out + 1] = source[src + 1]; result[out + 2] = source[src + 2] }
                5 -> { val i = triangle + 2; if (i % 2 == 0) { result[out] = source[i - 2]; result[out + 1] = source[i - 1] } else { result[out] = source[i - 1]; result[out + 1] = source[i - 2] }; result[out + 2] = source[i] }
                6 -> { val i = triangle + 2; result[out] = source[0]; result[out + 1] = source[i - 1]; result[out + 2] = source[i] }
            }
        }
        return result
    }

    private fun readFloatAccessor(
        index: Int,
        accessors: JSONArray,
        views: JSONArray,
        binary: ByteBuffer,
        expectedComponents: Int
    ): FloatArray {
        val accessor = accessors.getJSONObject(index)
        val count = accessor.getInt("count")
        require(count in 1..MAX_SOURCE_VERTICES) { "GLB accessor has too many elements ($count)" }
        val componentType = accessor.optInt("componentType", 5126)
        val componentBytes = componentSize(componentType)
        val packed = expectedComponents * componentBytes
        val output = FloatArray(count * expectedComponents)
        val source = binary.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (accessor.has("bufferView")) {
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val start = view.optInt("byteOffset", 0).toLong() + accessor.optInt("byteOffset", 0).toLong()
            val stride = view.optInt("byteStride", packed)
            require(stride >= packed) { "Invalid GLB accessor byteStride" }
            for (i in 0 until count) {
                val base = start + i.toLong() * stride
                require(base >= 0L && base + packed <= source.limit().toLong()) { "GLB accessor exceeds BIN data" }
                for (c in 0 until expectedComponents) output[i * expectedComponents + c] = readComponent(source, (base + c * componentBytes).toInt(), componentType, accessor.optBoolean("normalized", false))
            }
        }
        val sparse = accessor.optJSONObject("sparse")
        if (sparse != null) {
            val sparseCount = sparse.getInt("count")
            require(sparseCount in 1..count) { "Invalid sparse accessor count" }
            val indexInfo = sparse.getJSONObject("indices")
            val indexType = indexInfo.getInt("componentType")
            val indexBytes = componentSize(indexType)
            require(indexType == 5121 || indexType == 5123 || indexType == 5125) { "Sparse accessor indices must be unsigned integers" }
            val indexView = views.getJSONObject(indexInfo.getInt("bufferView"))
            val indexStart = indexView.optInt("byteOffset", 0).toLong() + indexInfo.optInt("byteOffset", 0).toLong()
            val valueInfo = sparse.getJSONObject("values")
            val valueView = views.getJSONObject(valueInfo.getInt("bufferView"))
            val valuesStart = valueView.optInt("byteOffset", 0).toLong() + valueInfo.optInt("byteOffset", 0).toLong()
            for (i in 0 until sparseCount) {
                val io = indexStart + i.toLong() * indexBytes
                val vo = valuesStart + i.toLong() * packed
                require(io >= 0 && io + indexBytes <= source.limit().toLong() && vo >= 0 && vo + packed <= source.limit().toLong()) { "Sparse accessor exceeds BIN data" }
                val target = readUnsigned(source, io.toInt(), indexType)
                require(target in 0 until count) { "Sparse accessor index is out of range" }
                for (c in 0 until expectedComponents) output[target * expectedComponents + c] = readComponent(source, (vo + c * componentBytes).toInt(), componentType, accessor.optBoolean("normalized", false))
            }
        }
        return output
    }

    private fun readIndexAccessor(index: Int, accessors: JSONArray, views: JSONArray, binary: ByteBuffer): IntArray {
        val accessor = accessors.getJSONObject(index)
        val count = accessor.getInt("count")
        require(count in 1..MAX_SOURCE_INDICES) { "GLB primitive has too many indices ($count)" }
        val type = accessor.getInt("componentType")
        require(type == 5121 || type == 5123 || type == 5125) { "GLB index data must use unsigned byte/short/int" }
        val bytesPer = componentSize(type)
        val result = IntArray(count)
        val bb = binary.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (accessor.has("bufferView")) {
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val start = view.optInt("byteOffset", 0).toLong() + accessor.optInt("byteOffset", 0).toLong()
            val stride = view.optInt("byteStride", bytesPer)
            for (i in 0 until count) {
                val off = start + i.toLong() * stride
                require(off >= 0 && off + bytesPer <= bb.limit().toLong()) { "GLB index accessor exceeds BIN data" }
                result[i] = readUnsigned(bb, off.toInt(), type)
            }
        }
        return result
    }

    private fun readComponent(bb: ByteBuffer, offset: Int, type: Int, normalized: Boolean): Float = when (type) {
        5126 -> bb.getFloat(offset)
        5120 -> { val v = bb.get(offset).toInt(); if (normalized) (v / 127f).coerceAtLeast(-1f) else v.toFloat() }
        5121 -> { val v = bb.get(offset).toInt() and 255; if (normalized) v / 255f else v.toFloat() }
        5122 -> { val v = bb.getShort(offset).toInt(); if (normalized) (v / 32767f).coerceAtLeast(-1f) else v.toFloat() }
        5123 -> { val v = bb.getShort(offset).toInt() and 65535; if (normalized) v / 65535f else v.toFloat() }
        5125 -> { val v = bb.getInt(offset).toLong() and 0xFFFFFFFFL; if (normalized) (v.toDouble() / 4294967295.0).toFloat() else v.toFloat() }
        else -> throw IllegalArgumentException("Unsupported GLB componentType $type")
    }

    private fun readUnsigned(bb: ByteBuffer, offset: Int, type: Int): Int = when (type) {
        5121 -> bb.get(offset).toInt() and 255
        5123 -> bb.getShort(offset).toInt() and 65535
        5125 -> (bb.getInt(offset).toLong() and 0xFFFFFFFFL).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else -> throw IllegalArgumentException("Unsupported unsigned GLB integer type $type")
    }

    private fun componentSize(type: Int): Int = when (type) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        5125, 5126 -> 4
        else -> throw IllegalArgumentException("Unsupported GLB component type: $type")
    }

    private fun accessorComponents(type: String): Int = when (type) {
        "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
        else -> 1
    }

    private fun generateVertexNormals(positions: FloatArray, indices: IntArray, mode: Int): FloatArray {
        val normals = FloatArray(positions.size)
        val tri = triangulate(indices, mode, min(estimateModeTriangles(indices.size, mode), MAX_TOTAL_TRIANGLES))
        for (i in tri.indices step 3) {
            val a = tri[i]; val b = tri[i + 1]; val c = tri[i + 2]
            if (a !in 0 until positions.size / 3 || b !in 0 until positions.size / 3 || c !in 0 until positions.size / 3) continue
            val ai = a * 3; val bi = b * 3; val ci = c * 3
            val e1x = positions[bi] - positions[ai]; val e1y = positions[bi + 1] - positions[ai + 1]; val e1z = positions[bi + 2] - positions[ai + 2]
            val e2x = positions[ci] - positions[ai]; val e2y = positions[ci + 1] - positions[ai + 1]; val e2z = positions[ci + 2] - positions[ai + 2]
            val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
            for (idx in intArrayOf(ai, bi, ci)) { normals[idx] += nx; normals[idx + 1] += ny; normals[idx + 2] += nz }
        }
        for (i in normals.indices step 3) {
            val len = sqrt(normals[i] * normals[i] + normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2])
            if (len > 0.00001f) { normals[i] /= len; normals[i + 1] /= len; normals[i + 2] /= len }
            else { normals[i] = 0f; normals[i + 1] = 1f; normals[i + 2] = 0f }
        }
        return normals
    }

    private fun normalizeWeights(weights: FloatArray) {
        for (i in weights.indices step 4) {
            var sum = 0f
            for (j in 0..3) sum += weights.getOrElse(i + j) { 0f }.coerceAtLeast(0f)
            if (sum > 0.00001f) for (j in 0..3) weights[i + j] = weights[i + j].coerceAtLeast(0f) / sum
            else { weights[i] = 1f; for (j in 1..3) weights[i + j] = 0f }
        }
    }

    private fun transformPositionsInPlace(positions: FloatArray, matrix: FloatArray) {
        for (i in positions.indices step 3) {
            if (i + 2 >= positions.size) break
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            positions[i] = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
            positions[i + 1] = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
            positions[i + 2] = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        }
    }

    private fun transformNormalsInPlace(normals: FloatArray, matrix: FloatArray) {
        val inverse = FloatArray(16); val normalMatrix = FloatArray(16)
        if (Matrix.invertM(inverse, 0, matrix, 0)) Matrix.transposeM(normalMatrix, 0, inverse, 0)
        else System.arraycopy(matrix, 0, normalMatrix, 0, 16)
        for (i in normals.indices step 3) {
            if (i + 2 >= normals.size) break
            val x = normalMatrix[0] * normals[i] + normalMatrix[4] * normals[i + 1] + normalMatrix[8] * normals[i + 2]
            val y = normalMatrix[1] * normals[i] + normalMatrix[5] * normals[i + 1] + normalMatrix[9] * normals[i + 2]
            val z = normalMatrix[2] * normals[i] + normalMatrix[6] * normals[i + 1] + normalMatrix[10] * normals[i + 2]
            val len = sqrt(x * x + y * y + z * z).coerceAtLeast(0.00001f)
            normals[i] = x / len; normals[i + 1] = y / len; normals[i + 2] = z / len
        }
    }

    private fun applyTextureTransform(uvs: FloatArray, textureInfo: JSONObject) {
        val transform = textureInfo.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform") ?: return
        val offset = transform.optJSONArray("offset")
        val scale = transform.optJSONArray("scale")
        val offsetU = offset?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val offsetV = offset?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val scaleU = scale?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val scaleV = scale?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val rotation = transform.optDouble("rotation", 0.0).toFloat()
        val c = kotlin.math.cos(rotation); val s = kotlin.math.sin(rotation)
        for (i in uvs.indices step 2) {
            if (i + 1 >= uvs.size) break
            val u = uvs[i] * scaleU; val v = uvs[i + 1] * scaleV
            uvs[i] = u * c - v * s + offsetU
            uvs[i + 1] = u * s + v * c + offsetV
        }
    }

    private fun readBaseColorFactor(pbr: JSONObject?, material: JSONObject?, hasBitmap: Boolean, fallback: FloatArray): FloatArray {
        val factor = pbr?.optJSONArray("baseColorFactor")
            ?: material?.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")?.optJSONArray("diffuseFactor")
        if (factor != null && factor.length() >= 3) return FloatArray(4) { factor.optDouble(it, if (it == 3) 1.0 else 1.0).toFloat().coerceIn(0f, 1f) }
        return if (hasBitmap) floatArrayOf(1f, 1f, 1f, 1f) else fallback.clone()
    }

    private fun decodeImage(image: JSONObject, views: JSONArray, binary: ByteBuffer): Bitmap? {
        return try {
            val bytes = when {
                image.has("bufferView") -> {
                    val view = views.getJSONObject(image.getInt("bufferView"))
                    val offset = view.optInt("byteOffset", 0); val length = view.getInt("byteLength")
                    if (offset < 0 || length <= 0 || offset.toLong() + length > binary.limit().toLong()) return null
                    ByteArray(length).also { out -> binary.duplicate().apply { position(offset) }.get(out) }
                }
                image.optString("uri").startsWith("data:") -> {
                    val uri = image.getString("uri"); val comma = uri.indexOf(',')
                    if (comma >= 0) Base64.decode(uri.substring(comma + 1), Base64.DEFAULT) else return null
                }
                else -> return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_TEXTURE_EDGE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
            })
        } catch (e: Exception) {
            Log.w("GLBModelParser", "Unable to decode embedded GLB image: ${e.message}")
            null
        }
    }

    private fun identityMatrix() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun JSONArray.toFloatArray(count: Int, defaults: FloatArray): FloatArray = FloatArray(count) { i ->
        if (i < length()) optDouble(i, defaults.getOrElse(i) { 0f }.toDouble()).toFloat() else defaults.getOrElse(i) { 0f }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val temp = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(temp)
            if (n < 0) break
            total += n
            require(total <= limit && total <= Int.MAX_VALUE.toLong()) { "GLB file is too large for this Android import path" }
            out.write(temp, 0, n)
        }
        return out.toByteArray()
    }
}
