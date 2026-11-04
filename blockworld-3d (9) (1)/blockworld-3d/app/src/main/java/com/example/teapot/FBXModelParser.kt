package com.example.teapot

import com.example.graphics.MeshBuilder
import com.example.model.Vector3
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Static-mesh FBX importer.
 *
 * Supports the basic Vertices/PolygonVertexIndex geometry data in ASCII FBX and binary FBX 7.x,
 * including zlib-compressed binary arrays. It deliberately does not pretend that an unsupported
 * FBX is a cube. FBX materials/textures, node animation, skinning and skeletons need a full FBX
 * runtime/conversion step; use an embedded-texture GLB for textured static models in this renderer.
 */
object FBXModelParser {
    private const val MAX_FILE_BYTES = 1536L * 1024L * 1024L
    private const val MAX_POSITION_FLOATS = 12_000_000
    private const val MAX_POLYGON_INDICES = 2_000_000
    private const val MAX_TRIANGLES = 600_000
    private val BINARY_MAGIC = "Kaydara FBX Binary  \u0000\u001A\u0000".toByteArray(Charsets.ISO_8859_1)

    private data class BinaryArrays(
        var positions: FloatArray? = null,
        var polygonIndices: IntArray? = null
    )

    /** Prefer [parse(File, String, String, String, FloatArray)] for large files; it memory-maps binary FBX. */
    fun parse(
        inputStream: InputStream,
        modelId: String,
        modelName: String,
        category: String = "Imported",
        defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f)
    ): Custom3DMesh {
        val bytes = inputStream.use { readBounded(it, MAX_FILE_BYTES) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return if (isBinaryFbx(buffer)) {
            buildMesh(parseBinaryArrays(buffer), modelId, modelName, category, defaultColor)
        } else {
            parseAscii(ByteArrayInputStream(bytes), modelId, modelName, category, defaultColor)
        }
    }

    /** File overload avoids copying an entire large binary FBX into the Java heap. */
    fun parse(
        file: File,
        modelId: String,
        modelName: String,
        category: String = "Imported",
        defaultColor: FloatArray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f)
    ): Custom3DMesh {
        require(file.isFile) { "FBX file does not exist" }
        require(file.length() <= MAX_FILE_BYTES) {
            "FBX file exceeds 1.5 GB. Optimize the asset or export an uncompressed GLB."
        }
        FileInputStream(file).channel.use { channel ->
            if (channel.size() >= BINARY_MAGIC.size) {
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                if (isBinaryFbx(mapped)) {
                    return buildMesh(parseBinaryArrays(mapped), modelId, modelName, category, defaultColor)
                }
            }
        }
        return file.inputStream().use { parseAscii(it, modelId, modelName, category, defaultColor) }
    }

    private fun isBinaryFbx(buffer: ByteBuffer): Boolean {
        if (buffer.limit() < BINARY_MAGIC.size) return false
        for (i in BINARY_MAGIC.indices) if (buffer.get(i) != BINARY_MAGIC[i]) return false
        return true
    }

    private fun parseBinaryArrays(input: ByteBuffer): BinaryArrays {
        val b = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(isBinaryFbx(b)) { "The selected file is not a binary FBX file" }
        require(b.limit() >= 27) { "Binary FBX header is incomplete" }
        b.position(23)
        val version = b.int
        require(version >= 7000) { "Binary FBX versions older than 7.0 are not supported" }
        val wide = version >= 7500
        val headerBytes = if (wide) 25 else 13
        val arrays = BinaryArrays()
        b.position(27)

        fun parseNode(depth: Int = 0): Boolean {
            if (depth > 128) throw IllegalArgumentException("FBX node nesting is too deep")
            if (b.remaining() < headerBytes) return false
            val nodeStart = b.position()
            val endOffsetLong: Long
            val propertyCountLong: Long
            val propertyListLengthLong: Long
            if (wide) {
                endOffsetLong = b.long
                propertyCountLong = b.long
                propertyListLengthLong = b.long
            } else {
                endOffsetLong = b.int.toLong() and 0xFFFFFFFFL
                propertyCountLong = b.int.toLong() and 0xFFFFFFFFL
                propertyListLengthLong = b.int.toLong() and 0xFFFFFFFFL
            }
            val nameLength = b.get().toInt() and 0xFF
            if (endOffsetLong == 0L && propertyCountLong == 0L && propertyListLengthLong == 0L && nameLength == 0) {
                return false // FBX null record terminator; parent advances to its declared end.
            }
            require(endOffsetLong > nodeStart && endOffsetLong <= b.limit().toLong()) {
                "Invalid binary FBX node end offset"
            }
            require(propertyCountLong <= 100_000L && propertyListLengthLong <= endOffsetLong) {
                "Invalid binary FBX property list"
            }
            require(nameLength <= b.remaining()) { "Truncated binary FBX node name" }
            val nodeNameBytes = ByteArray(nameLength)
            b.get(nodeNameBytes)
            val nodeName = String(nodeNameBytes, Charsets.UTF_8)
            val propertyStart = b.position()
            val propertyEndLong = propertyStart.toLong() + propertyListLengthLong
            require(propertyEndLong <= endOffsetLong && propertyEndLong <= b.limit().toLong()) {
                "Binary FBX properties exceed the node bounds"
            }
            repeat(propertyCountLong.toInt()) {
                readBinaryProperty(b, nodeName, arrays)
                require(b.position().toLong() <= propertyEndLong) { "Invalid binary FBX property size" }
            }
            b.position(propertyEndLong.toInt())

            val nodeEnd = endOffsetLong.toInt()
            while (b.position() + headerBytes <= nodeEnd) {
                val childStart = b.position()
                if (!parseNode(depth + 1)) {
                    b.position(nodeEnd) // Skip this node's null terminator.
                    break
                }
                if (b.position() <= childStart) throw IllegalArgumentException("Malformed binary FBX child node")
            }
            b.position(nodeEnd)
            return true
        }

        while (b.position() + headerBytes <= b.limit()) {
            val start = b.position()
            if (!parseNode()) break
            if (b.position() <= start) break
            if (arrays.positions != null && arrays.polygonIndices != null) break
        }
        require(arrays.positions != null) {
            "No supported Vertices array was found in this FBX. Export the asset as GLB from Blender."
        }
        require(arrays.polygonIndices != null) {
            "No supported PolygonVertexIndex array was found in this FBX. Export the asset as a static mesh."
        }
        return arrays
    }

    private fun readBinaryProperty(buffer: ByteBuffer, nodeName: String, arrays: BinaryArrays) {
        require(buffer.hasRemaining()) { "Truncated binary FBX property" }
        val type = buffer.get().toInt().toChar()
        when (type) {
            'Y' -> skipChecked(buffer, 2)
            'C' -> skipChecked(buffer, 1)
            'I', 'F' -> skipChecked(buffer, 4)
            'D', 'L' -> skipChecked(buffer, 8)
            'S', 'R' -> {
                require(buffer.remaining() >= 4) { "Truncated binary FBX string/raw property" }
                val length = buffer.int
                require(length >= 0 && length <= buffer.remaining()) { "Invalid binary FBX string/raw property length" }
                skipChecked(buffer, length)
            }
            'f', 'd', 'l', 'i', 'b', 'c' -> {
                require(buffer.remaining() >= 12) { "Truncated binary FBX array header" }
                val count = buffer.int.toLong() and 0xFFFFFFFFL
                val encoding = buffer.int
                val compressedLength = buffer.int
                require(count <= Int.MAX_VALUE.toLong() && compressedLength >= 0 && compressedLength <= buffer.remaining()) {
                    "Invalid binary FBX array size"
                }
                val isVertices = nodeName == "Vertices" && arrays.positions == null && (type == 'd' || type == 'f')
                val isIndices = nodeName == "PolygonVertexIndex" && arrays.polygonIndices == null && (type == 'i' || type == 'l')
                if (isVertices) {
                    require(count in 9..MAX_POSITION_FLOATS.toLong()) {
                        "This FBX position array is too large for this mobile renderer (limit: $MAX_POSITION_FLOATS coordinates)."
                    }
                }
                if (isIndices) {
                    require(count in 3..MAX_POLYGON_INDICES.toLong()) {
                        "This FBX polygon-index array is too large for this mobile renderer (limit: $MAX_POLYGON_INDICES indices)."
                    }
                }
                val payloadStart = buffer.position()
                if (isVertices || isIndices) {
                    val componentBytes = when (type) {
                        'b', 'c' -> 1
                        'i', 'f' -> 4
                        'l', 'd' -> 8
                        else -> 4
                    }
                    val expectedBytes = count * componentBytes
                    require(expectedBytes <= Int.MAX_VALUE.toLong()) { "Binary FBX numeric array is too large" }
                    val payload = readArrayPayload(buffer, payloadStart, compressedLength, encoding, expectedBytes.toInt())
                    if (isVertices) arrays.positions = decodeFloatArray(payload, type, count.toInt())
                    if (isIndices) arrays.polygonIndices = decodeIntArray(payload, type, count.toInt())
                }
                skipChecked(buffer, compressedLength)
            }
            else -> throw IllegalArgumentException("Unsupported binary FBX property type '$type'")
        }
    }

    private fun readArrayPayload(
        source: ByteBuffer,
        payloadStart: Int,
        compressedLength: Int,
        encoding: Int,
        expectedBytes: Int
    ): ByteBuffer {
        require(payloadStart >= 0 && payloadStart.toLong() + compressedLength <= source.limit().toLong()) {
            "Binary FBX array exceeds the input file"
        }
        if (encoding == 0) {
            require(expectedBytes <= compressedLength) { "Truncated uncompressed binary FBX array" }
            val duplicate = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            duplicate.position(payloadStart)
            duplicate.limit(payloadStart + expectedBytes)
            return duplicate.slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        require(encoding == 1) { "Unsupported binary FBX array encoding $encoding" }
        val compressed = ByteArray(compressedLength)
        val copy = source.duplicate()
        copy.position(payloadStart)
        copy.get(compressed)
        val raw = InflaterInputStream(ByteArrayInputStream(compressed)).use { readBounded(it, expectedBytes.toLong()) }
        require(raw.size >= expectedBytes) { "Truncated compressed binary FBX array" }
        return ByteBuffer.wrap(raw, 0, expectedBytes).slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun decodeFloatArray(payload: ByteBuffer, type: Char, count: Int): FloatArray {
        val result = FloatArray(count)
        for (i in 0 until count) result[i] = when (type) {
            'f' -> payload.getFloat()
            'd' -> payload.getDouble().toFloat()
            else -> throw IllegalArgumentException("FBX Vertices must use float or double arrays")
        }
        return result
    }

    private fun decodeIntArray(payload: ByteBuffer, type: Char, count: Int): IntArray {
        val result = IntArray(count)
        for (i in 0 until count) result[i] = when (type) {
            'i' -> payload.int
            'l' -> payload.long.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            else -> throw IllegalArgumentException("FBX PolygonVertexIndex must use integer arrays")
        }
        return result
    }

    private fun skipChecked(buffer: ByteBuffer, bytes: Int) {
        require(bytes >= 0 && bytes <= buffer.remaining()) { "Truncated binary FBX property" }
        buffer.position(buffer.position() + bytes)
    }

    private fun buildMesh(
        arrays: BinaryArrays,
        modelId: String,
        modelName: String,
        category: String,
        defaultColor: FloatArray
    ): Custom3DMesh = buildMesh(arrays.positions ?: FloatArray(0), arrays.polygonIndices ?: IntArray(0), modelId, modelName, category, defaultColor)

    private fun buildMesh(
        rawPositions: FloatArray,
        polygonIndices: IntArray,
        modelId: String,
        modelName: String,
        category: String,
        defaultColor: FloatArray
    ): Custom3DMesh {
        require(rawPositions.size >= 9 && rawPositions.size % 3 == 0) {
            "The FBX does not contain valid XYZ mesh positions"
        }
        require(polygonIndices.isNotEmpty()) { "The FBX does not contain polygon indices" }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in rawPositions.indices step 3) {
            val x = rawPositions[i]; val y = rawPositions[i + 1]; val z = rawPositions[i + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
            maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        }
        require(minX.isFinite() && maxX.isFinite()) { "The FBX has no finite mesh positions" }

        var polygonSize = 0
        var triangleCount = 0L
        for (rawIndex in polygonIndices) {
            polygonSize++
            if (rawIndex < 0) {
                triangleCount += max(0, polygonSize - 2)
                polygonSize = 0
                require(triangleCount <= MAX_TRIANGLES.toLong()) {
                    "This FBX exceeds the static mobile mesh budget of $MAX_TRIANGLES triangles. Reduce/decimate it in Blender, or export a GLB."
                }
            }
        }
        require(polygonSize == 0) { "The FBX polygon index list is incomplete" }
        require(triangleCount > 0) { "No renderable FBX polygons were found" }

        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f
        val vertexFloatCount = triangleCount.toInt() * 9
        val outPositions = FloatArray(vertexFloatCount)
        val outNormals = FloatArray(vertexFloatCount)
        var cursor = 0
        var first = -1
        var previous = -1
        var currentPolySize = 0

        fun emitTriangle(a: Int, b: Int, c: Int) {
            val ai = a * 3; val bi = b * 3; val ci = c * 3
            require(ai >= 0 && bi >= 0 && ci >= 0 && ai + 2 < rawPositions.size && bi + 2 < rawPositions.size && ci + 2 < rawPositions.size) {
                "An FBX polygon references a vertex outside its position array"
            }
            val ax = rawPositions[ai] - centerX; val ay = rawPositions[ai + 1] - centerY; val az = rawPositions[ai + 2] - centerZ
            val bx = rawPositions[bi] - centerX; val by = rawPositions[bi + 1] - centerY; val bz = rawPositions[bi + 2] - centerZ
            val cx = rawPositions[ci] - centerX; val cy = rawPositions[ci + 1] - centerY; val cz = rawPositions[ci + 2] - centerZ
            outPositions[cursor] = ax; outPositions[cursor + 1] = ay; outPositions[cursor + 2] = az
            outPositions[cursor + 3] = bx; outPositions[cursor + 4] = by; outPositions[cursor + 5] = bz
            outPositions[cursor + 6] = cx; outPositions[cursor + 7] = cy; outPositions[cursor + 8] = cz

            var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
            var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
            var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
            nx /= length; ny /= length; nz /= length
            for (i in 0..2) {
                val offset = cursor + i * 3
                outNormals[offset] = nx; outNormals[offset + 1] = ny; outNormals[offset + 2] = nz
            }
            cursor += 9
        }

        for (rawIndex in polygonIndices) {
            val isLast = rawIndex < 0
            val vertexIndex = if (isLast) -rawIndex - 1 else rawIndex
            when (currentPolySize) {
                0 -> { first = vertexIndex; currentPolySize = 1 }
                1 -> { previous = vertexIndex; currentPolySize = 2 }
                else -> { emitTriangle(first, previous, vertexIndex); previous = vertexIndex; currentPolySize++ }
            }
            if (isLast) {
                currentPolySize = 0
                first = -1
                previous = -1
            }
        }

        val sizeX = (maxX - minX).coerceAtLeast(0.01f)
        val sizeY = (maxY - minY).coerceAtLeast(0.01f)
        val sizeZ = (maxZ - minZ).coerceAtLeast(0.01f)
        return Custom3DMesh(
            id = modelId,
            name = modelName,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(outPositions),
            normalBuffer = MeshBuilder.createFloatBuffer(outNormals),
            vertexCount = outPositions.size / 3,
            defaultColor = defaultColor.clone(),
            boundsSize = Vector3(sizeX, sizeY, sizeZ),
            isUserImported = true
        )
    }

    private fun parseAscii(
        inputStream: InputStream,
        modelId: String,
        modelName: String,
        category: String,
        defaultColor: FloatArray
    ): Custom3DMesh {
        val rawPositions = ArrayList<Float>()
        val rawIndices = ArrayList<Int>()
        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            var inVertices = false
            var inIndices = false
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("Vertices:")) {
                    inVertices = true
                    inIndices = false
                    val data = trimmed.substringAfter("Vertices:").substringAfter("a:").trim()
                    parseFloats(data, rawPositions)
                } else if (trimmed.startsWith("PolygonVertexIndex:")) {
                    inVertices = false
                    inIndices = true
                    val data = trimmed.substringAfter("PolygonVertexIndex:").substringAfter("a:").trim()
                    parseInts(data, rawIndices)
                } else {
                    if (inVertices) {
                        if (trimmed.startsWith("}") || trimmed.contains(":")) inVertices = false
                        else parseFloats(trimmed, rawPositions)
                    }
                    if (inIndices) {
                        if (trimmed.startsWith("}") || trimmed.contains(":")) inIndices = false
                        else parseInts(trimmed, rawIndices)
                    }
                }
            }
        }
        require(rawPositions.size >= 9) {
            "No static geometry was found in this FBX. Binary FBX 7.x is supported; animated FBX scenes need conversion to GLB/runtime support."
        }
        require(rawIndices.isNotEmpty()) { "No PolygonVertexIndex array found in ASCII FBX" }
        return buildMesh(rawPositions.toFloatArray(), rawIndices.toIntArray(), modelId, modelName, category, defaultColor)
    }

    private fun parseFloats(text: String, target: MutableList<Float>) {
        for (token in text.split(',')) {
            val value = token.trim().toFloatOrNull() ?: continue
            require(target.size < MAX_POSITION_FLOATS) { "ASCII FBX has too many position values for this mobile importer" }
            target.add(value)
        }
    }

    private fun parseInts(text: String, target: MutableList<Int>) {
        for (token in text.split(',')) {
            val value = token.trim().toIntOrNull() ?: continue
            require(target.size < MAX_POLYGON_INDICES) { "ASCII FBX has too many polygon indices for this mobile importer" }
            target.add(value)
        }
    }

    private fun readBounded(stream: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "FBX file exceeds 1.5 GB. Optimize it or export a GLB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
