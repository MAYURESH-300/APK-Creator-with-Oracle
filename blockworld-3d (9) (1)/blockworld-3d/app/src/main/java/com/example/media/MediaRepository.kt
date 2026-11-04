package com.example.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

enum class MediaKind { IMAGE, VIDEO }

data class ImportedMedia(
    val id: String,
    val displayName: String,
    val kind: MediaKind,
    /** App-private filename, never an arbitrary external path. */
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0L,
    val thumbnailName: String
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

/**
 * Persists imported media under files/media_library. Source bytes are copied once;
 * world instances should reference ImportedMedia.id rather than copy the file.
 *
 * All file copying, image bounds decoding, thumbnail generation, and video metadata
 * extraction run on Dispatchers.IO. Do not call this from the GL render thread.
 */
class MediaRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "media_library").apply { mkdirs() }
    private val assetsDir = File(root, "assets").apply { mkdirs() }
    private val thumbnailsDir = File(root, "thumbnails").apply { mkdirs() }
    private val manifest = File(root, "media-index.json")

    @Volatile private var cached: MutableList<ImportedMedia>? = null

    suspend fun list(): List<ImportedMedia> = withContext(Dispatchers.IO) {
        loadIndex().toList()
    }

    suspend fun get(id: String): ImportedMedia? = withContext(Dispatchers.IO) {
        loadIndex().firstOrNull { it.id == id }
    }

    suspend fun importImage(uri: Uri): ImportedMedia = withContext(Dispatchers.IO) {
        import(uri, MediaKind.IMAGE)
    }

    suspend fun importVideo(uri: Uri): ImportedMedia = withContext(Dispatchers.IO) {
        import(uri, MediaKind.VIDEO)
    }

    suspend fun thumbnailFile(mediaId: String): File? = withContext(Dispatchers.IO) {
        val item = loadIndex().firstOrNull { it.id == mediaId } ?: return@withContext null
        File(thumbnailsDir, item.thumbnailName).takeIf { it.isFile }
    }

    suspend fun assetFile(mediaId: String): File? = withContext(Dispatchers.IO) {
        val item = loadIndex().firstOrNull { it.id == mediaId } ?: return@withContext null
        File(assetsDir, item.fileName).takeIf { it.isFile }
    }

    /** Remove a library item only when caller has confirmed no saved world object references it. */
    suspend fun removeUnreferenced(mediaId: String, isReferencedByAnyScene: suspend (String) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (isReferencedByAnyScene(mediaId)) return@withContext false
            val items = loadIndex()
            val item = items.firstOrNull { it.id == mediaId } ?: return@withContext false
            items.remove(item)
            File(assetsDir, item.fileName).delete()
            File(thumbnailsDir, item.thumbnailName).delete()
            saveIndex(items)
            true
        }

    private fun import(uri: Uri, kind: MediaKind): ImportedMedia {
        val resolver = appContext.contentResolver
        val sourceName = queryDisplayName(resolver, uri) ?: "imported_${kind.name.lowercase()}"
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(sourceName.substringAfterLast('.', ""))
            ?: if (kind == MediaKind.IMAGE) "application/octet-stream" else "video/mp4"

        if (kind == MediaKind.IMAGE && !mime.startsWith("image/")) {
            throw IOException("Selected file is not identified as an image ($mime).")
        }
        if (kind == MediaKind.VIDEO && !mime.startsWith("video/") && mime != "application/octet-stream") {
            throw IOException("Selected file is not identified as a video ($mime).")
        }

        val id = UUID.randomUUID().toString()
        val ext = sourceName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: if (kind == MediaKind.IMAGE) "img" else "video"
        val internalName = "$id.$ext"
        val target = File(assetsDir, internalName)
        var copied = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Android could not open the selected file.")
            input.use { src ->
                FileOutputStream(target).use { dst ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = src.read(buffer)
                        if (count < 0) break
                        copied += count
                        // Safety cap: configurable and intentionally below common filesystem limits.
                        if (copied > MAX_IMPORT_BYTES) throw IOException("File is larger than the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit.")
                        dst.write(buffer, 0, count)
                    }
                }
            }

            val dimensionsAndDuration = if (kind == MediaKind.IMAGE) imageMetadata(target) else videoMetadata(target)
            val thumbName = "$id.jpg"
            val thumbFile = File(thumbnailsDir, thumbName)
            if (kind == MediaKind.IMAGE) {
                createImageThumbnail(target, thumbFile)
            } else {
                createVideoThumbnail(target, thumbFile)
            }

            val item = ImportedMedia(
                id = id,
                displayName = sourceName,
                kind = kind,
                fileName = internalName,
                mimeType = mime,
                byteSize = copied,
                width = dimensionsAndDuration.first,
                height = dimensionsAndDuration.second,
                durationMs = dimensionsAndDuration.third,
                thumbnailName = thumbName
            )
            val items = loadIndex()
            items.add(item)
            saveIndex(items)
            return item
        } catch (t: Throwable) {
            target.delete()
            File(thumbnailsDir, "$id.jpg").delete()
            throw t
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun imageMetadata(file: File): Triple<Int, Int, Long> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Unsupported or damaged image.")
        if (options.outWidth.toLong() * options.outHeight > MAX_IMAGE_PIXELS) {
            // Metadata is accepted, but thumbnail decoding is sampled below to avoid a full-size bitmap.
        }
        return Triple(options.outWidth, options.outHeight, 0L)
    }

    private fun videoMetadata(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (width <= 0 || height <= 0) throw IOException("No readable video track was found. The video may use an unsupported codec.")
            return Triple(width, height, duration)
        } catch (e: RuntimeException) {
            throw IOException("Could not read video metadata. The file may be damaged or use an unsupported codec.", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun createImageThumbnail(source: File, destination: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > THUMBNAIL_EDGE * 2 || bounds.outHeight / sample > THUMBNAIL_EDGE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1); inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: throw IOException("Image thumbnail decoding failed.")
        bitmap.useBitmap { scaled ->
            FileOutputStream(destination).use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw IOException("Could not save image thumbnail.")
            }
        }
    }

    private fun createVideoThumbnail(source: File, destination: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Video decoder could not produce a thumbnail frame.")
            frame.useBitmap { bitmap ->
                val scale = minOf(1f, THUMBNAIL_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
                val thumb = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    FileOutputStream(destination).use { out ->
                        if (!thumb.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw IOException("Could not save video poster.")
                    }
                } finally { if (thumb !== bitmap) thumb.recycle() }
            }
        } catch (e: RuntimeException) {
            throw IOException("Video thumbnail generation failed; this format may not be supported on this device.", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun loadIndex(): MutableList<ImportedMedia> {
        cached?.let { return it }
        val items = mutableListOf<ImportedMedia>()
        if (manifest.isFile) {
            try {
                val array = JSONArray(manifest.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    items.add(
                        ImportedMedia(
                            id = o.getString("id"),
                            displayName = o.getString("displayName"),
                            kind = MediaKind.valueOf(o.getString("kind")),
                            fileName = o.getString("fileName"),
                            mimeType = o.optString("mimeType", "application/octet-stream"),
                            byteSize = o.optLong("byteSize", 0L),
                            width = o.optInt("width", 0),
                            height = o.optInt("height", 0),
                            durationMs = o.optLong("durationMs", 0L),
                            thumbnailName = o.getString("thumbnailName")
                        )
                    )
                }
            } catch (_: Exception) {
                // Do not overwrite a damaged manifest automatically; allow recovery by reporting empty list.
                return mutableListOf()
            }
        }
        cached = items
        return items
    }

    private fun saveIndex(items: List<ImportedMedia>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("displayName", item.displayName); put("kind", item.kind.name)
                put("fileName", item.fileName); put("mimeType", item.mimeType); put("byteSize", item.byteSize)
                put("width", item.width); put("height", item.height); put("durationMs", item.durationMs)
                put("thumbnailName", item.thumbnailName)
            })
        }
        val temp = File(root, "media-index.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(manifest)) {
            manifest.writeText(array.toString())
            temp.delete()
        }
        cached = items.toMutableList()
    }

    companion object {
        const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
        private const val MAX_IMAGE_PIXELS = 80_000_000L
        private const val THUMBNAIL_EDGE = 512
    }
}
