package com.example.media

import android.content.Context
import com.example.game.WorldObject
import com.example.teapot.SceneExportInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Builds a small, self-contained textured quad GLB for a placed image/video plane.
 * Videos are exported as a static JPEG poster because glTF 2.0 does not define native video
 * texture playback. Runtime playback in BlockWorld remains a real MediaPlayer->SurfaceTexture
 * pipeline and is not changed by this export adapter.
 */
object MediaPlaneGlbFactory {
    fun createSceneExportInstance(context: Context, obj: WorldObject): SceneExportInstance? {
        if (!obj.isMediaPlane) return null
        val textureFile = chooseExportTexture(obj) ?: return null
        val glbFile = createGlbFile(context, textureFile, obj.name) ?: return null
        return SceneExportInstance(
            modelId = obj.mediaId ?: obj.id,
            objectName = obj.name,
            sourcePath = glbFile.absolutePath,
            positionX = obj.position.x,
            positionY = obj.position.y,
            positionZ = obj.position.z,
            sizeX = obj.size.x,
            sizeY = obj.size.y,
            sizeZ = obj.size.z.coerceAtLeast(0.02f),
            rotationYDegrees = obj.rotationYDegrees,
            rotationXDegrees = obj.rotationXDegrees,
            rotationZDegrees = obj.rotationZDegrees,
            matchTargetBoundsExactly = true
        )
    }

    private fun chooseExportTexture(obj: WorldObject): File? {
        val source = obj.mediaFilePath?.let(::File)?.takeIf { it.isFile }
        val extension = source?.extension?.lowercase()
        // Core glTF 2.0 supports PNG and JPEG. Other formats are converted through the repository's
        // bounded JPEG poster/thumbnail, avoiding broken WebP/GIF assets in external GLB viewers.
        if (obj.type.name == "MEDIA_IMAGE" && extension in setOf("jpg", "jpeg", "png")) return source
        return obj.mediaThumbnailPath?.let(::File)?.takeIf { it.isFile } ?: source?.takeIf { extension in setOf("jpg", "jpeg", "png") }
    }

    private fun createGlbFile(context: Context, textureFile: File, objectName: String): File? = try {
        val png = textureFile.extension.equals("png", ignoreCase = true)
        val mimeType = if (png) "image/png" else "image/jpeg"
        val imageBytes = textureFile.readBytes()
        require(imageBytes.isNotEmpty()) { "Media image is empty" }

        val binary = ByteArrayOutputStream()
        val positionsOffset = binary.size()
        writeFloats(binary, floatArrayOf(
            -0.5f, -0.5f, 0f,
             0.5f, -0.5f, 0f,
             0.5f,  0.5f, 0f,
            -0.5f,  0.5f, 0f
        ))
        val positionsLength = binary.size() - positionsOffset
        padBinary(binary)
        val uvOffset = binary.size()
        writeFloats(binary, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f))
        val uvLength = binary.size() - uvOffset
        padBinary(binary)
        val indicesOffset = binary.size()
        writeShorts(binary, shortArrayOf(0, 1, 2, 0, 2, 3))
        val indicesLength = binary.size() - indicesOffset
        padBinary(binary)
        val imageOffset = binary.size()
        binary.write(imageBytes)
        val imageLength = imageBytes.size
        padBinary(binary)
        val bin = binary.toByteArray()

        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "BlockWorld 3D Media Plane Exporter"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("name", safeName(objectName)).put("mesh", 0)))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
                JSONObject()
                    .put("attributes", JSONObject().put("POSITION", 0).put("TEXCOORD_0", 1))
                    .put("indices", 2)
                    .put("material", 0)
            ))))
            .put("materials", JSONArray().put(JSONObject()
                .put("name", "Media Surface")
                .put("doubleSided", true)
                .put("alphaMode", "BLEND")
                .put("pbrMetallicRoughness", JSONObject()
                    .put("baseColorFactor", JSONArray().put(1).put(1).put(1).put(1))
                    .put("baseColorTexture", JSONObject().put("index", 0))
                    .put("metallicFactor", 0)
                    .put("roughnessFactor", 1)
                )
            ))
            .put("textures", JSONArray().put(JSONObject().put("sampler", 0).put("source", 0)))
            .put("images", JSONArray().put(JSONObject().put("bufferView", 3).put("mimeType", mimeType)))
            .put("samplers", JSONArray().put(JSONObject()
                .put("magFilter", 9729).put("minFilter", 9729)
                .put("wrapS", 33071).put("wrapT", 33071)
            ))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer", 0).put("byteOffset", positionsOffset).put("byteLength", positionsLength).put("target", 34962))
                .put(JSONObject().put("buffer", 0).put("byteOffset", uvOffset).put("byteLength", uvLength).put("target", 34962))
                .put(JSONObject().put("buffer", 0).put("byteOffset", indicesOffset).put("byteLength", indicesLength).put("target", 34963))
                .put(JSONObject().put("buffer", 0).put("byteOffset", imageOffset).put("byteLength", imageLength))
            )
            .put("accessors", JSONArray()
                .put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", 4).put("type", "VEC3")
                    .put("min", JSONArray().put(-0.5).put(-0.5).put(0)).put("max", JSONArray().put(0.5).put(0.5).put(0)))
                .put(JSONObject().put("bufferView", 1).put("componentType", 5126).put("count", 4).put("type", "VEC2")
                    .put("min", JSONArray().put(0).put(0)).put("max", JSONArray().put(1).put(1)))
                .put(JSONObject().put("bufferView", 2).put("componentType", 5123).put("count", 6).put("type", "SCALAR")
                    .put("min", JSONArray().put(0)).put("max", JSONArray().put(3)))
            )
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size)))

        val jsonRaw = gltf.toString().toByteArray(StandardCharsets.UTF_8)
        val jsonChunk = padBytes(jsonRaw, 0x20)
        val binChunk = padBytes(bin, 0)
        val totalSize = 12 + 8 + jsonChunk.size + 8 + binChunk.size
        val output = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(0x46546C67) // glTF magic
        output.putInt(2)
        output.putInt(totalSize)
        output.putInt(jsonChunk.size)
        output.putInt(0x4E4F534A) // JSON chunk
        output.put(jsonChunk)
        output.putInt(binChunk.size)
        output.putInt(0x004E4942) // BIN chunk
        output.put(binChunk)

        val directory = File(context.cacheDir, "media-export").apply { mkdirs() }
        val file = File(directory, "${System.nanoTime()}-${safeName(objectName)}.glb")
        file.writeBytes(output.array())
        file
    } catch (_: Exception) {
        null
    }

    private fun writeFloats(out: ByteArrayOutputStream, values: FloatArray) {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        out.write(buffer.array())
    }

    private fun writeShorts(out: ByteArrayOutputStream, values: ShortArray) {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putShort(value)
        out.write(buffer.array())
    }

    private fun padBinary(out: ByteArrayOutputStream) {
        while (out.size() % 4 != 0) out.write(0)
    }

    private fun padBytes(bytes: ByteArray, pad: Int): ByteArray {
        val paddedSize = (bytes.size + 3) and -4
        if (paddedSize == bytes.size) return bytes
        return bytes.copyOf(paddedSize).also { for (i in bytes.size until paddedSize) it[i] = pad.toByte() }
    }

    private fun safeName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(60)
        .ifBlank { "media_plane" }
}
