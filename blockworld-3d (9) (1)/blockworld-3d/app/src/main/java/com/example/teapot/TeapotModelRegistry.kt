package com.example.teapot

import android.content.Context
import android.net.Uri
import com.example.graphics.MeshBuilder
import com.example.model.Vector3
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

object TeapotModelRegistry {

    // Bound import copying before it can exhaust app storage/RAM. Large models can still be used
    // when optimized below this limit; full mesh parsing is deferred until a model is used.
    private const val MAX_IMPORT_FILE_BYTES = 512L * 1024L * 1024L
    private val modelsMap = LinkedHashMap<String, Custom3DMesh>()
    private val modelLoadLock = Any()
    @Volatile private var isInitialized = false
    @Volatile var lastImportError: String? = null
        private set

    @Synchronized fun getModels(): List<Custom3DMesh> = modelsMap.values.toList()

    @Synchronized fun getModelsByCategory(category: String): List<Custom3DMesh> {
        return if (category == "All") {
            modelsMap.values.toList()
        } else {
            modelsMap.values.filter { it.category.equals(category, ignoreCase = true) }
        }
    }

    @Synchronized fun getModel(id: String): Custom3DMesh? = modelsMap[id]

    fun initialize(
        context: Context,
        priorityModelId: String? = null,
        onProgress: ((String, Int, String) -> Unit)? = null
    ) {
        val shouldInitialize = synchronized(this) {
            if (isInitialized) false else { isInitialized = true; true }
        }
        if (!shouldInitialize) return

        // Register core runtime models only. The gallery may contain very large bundled GLBs and
        // imports; keeping all of their vertex buffers and decoded texture bitmaps resident was a
        // major source of frame drops and OOM crashes. Nonessential models become lightweight
        // entries and their actual meshes load only when tapped, dragged, or restored in-world.
        try {
            registerPackagedSampleModels(context)
        } catch (e: Exception) {
            android.util.Log.e("ModelRegistry", "Packaged GLB sample registration failed", e)
        }
        synchronized(this) {
            try { registerPrebuiltModels() }
            catch (e: Exception) { android.util.Log.e("ModelRegistry", "Built-in model registration failed", e) }
        }
        registerImportedModelsAsLazyEntries(context.applicationContext)

        // Restore only meshes that are already referenced by the saved scene, not every imported
        // file in the library. This keeps cold-start CPU and memory roughly proportional to the
        // active scene instead of the entire import history.
        val requiredIds = LinkedHashSet<String>()
        if (!priorityModelId.isNullOrBlank()) requiredIds += priorityModelId
        requiredIds += readSavedModelIds(context.applicationContext)
        for (modelId in requiredIds) {
            val modelName = getModel(modelId)?.name ?: modelId
            onProgress?.invoke(modelName, 0, "Restoring saved model")
            try {
                ensureModelLoaded(context.applicationContext, modelId) { percent, stage ->
                    onProgress?.invoke(modelName, percent, stage)
                }
            }
            catch (e: OutOfMemoryError) {
                lastImportError = "Model '$modelId' exceeds available memory and was skipped."
                android.util.Log.w("ModelRegistry", lastImportError ?: "Model skipped")
            } catch (e: Exception) {
                lastImportError = "Model '$modelId' could not be restored: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w("ModelRegistry", lastImportError ?: "Model failed", e)
            }
            onProgress?.invoke(modelName, 100, "Saved model restored")
        }
    }

    private fun readSavedModelIds(context: Context): Set<String> {
        val file = File(context.filesDir, "saved_swaps.json")
        if (!file.isFile) return emptySet()
        return runCatching {
            val root = JSONObject(file.readText())
            val ids = LinkedHashSet<String>()
            val swaps = root.optJSONObject("swaps") ?: root
            val keys = swaps.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "placed" || key == "swaps") continue
                swaps.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(ids::add)
            }
            val placed = root.optJSONArray("placed")
            if (placed != null) for (i in 0 until placed.length()) {
                placed.optJSONObject(i)?.optString("modelId")
                    ?.takeIf { it.isNotBlank() && it != "null" }?.let(ids::add)
            }
            ids
        }.getOrDefault(emptySet())
    }

    private fun registerImportedModelsAsLazyEntries(context: Context) {
        val storageDir = File(context.filesDir, "imported_models")
        val files = storageDir.listFiles() ?: return
        for (file in files) {
            val ext = file.extension.lowercase()
            if (!file.isFile || ext !in setOf("glb", "obj", "fbx")) continue
            val modelId = file.nameWithoutExtension
            try {
                val metaFile = File(storageDir, "$modelId.meta.json")
                val meta = if (metaFile.isFile) runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
                val displayName = meta?.optString("originalName")?.takeIf { it.isNotBlank() }
                    ?: "Imported " + modelId.takeLast(6)
                synchronized(this) {
                    if (!modelsMap.containsKey(modelId)) {
                        modelsMap[modelId] = createLazyStub(
                            id = modelId, name = displayName, category = "Imported",
                            sourcePath = file.absolutePath, isUserImported = true,
                            boundsSize = Vector3(1f, 1f, 1f),
                            defaultColor = floatArrayOf(0.68f, 0.68f, 0.68f, 1f)
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ModelRegistry", "Could not register imported model ${file.name}", e)
            }
        }
    }

    /**
     * Load one model's real geometry on a worker thread when it is about to be used. Callers should
     * invoke this from Dispatchers.IO. Duplicate requests serialize and the second caller reuses
     * the first result. The registry keeps a compact preview after unused models are evicted.
     */
    fun ensureModelLoaded(
        context: Context,
        modelId: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Custom3DMesh? = synchronized(modelLoadLock) {
        val existing = getModel(modelId) ?: return@synchronized null
        if (!existing.isLazyLoaded) return@synchronized existing
        try {
            val parsed = if (existing.sourcePath?.startsWith("asset://") == true) {
                val assetPath = existing.sourcePath.removePrefix("asset://")
                context.assets.open(assetPath).use { input ->
                    GLBModelParser.parse(input, existing.id, existing.name, existing.category, existing.defaultColor.clone(), onProgress)
                }
            } else {
                val source = existing.sourcePath?.let(::File)
                    ?: throw IllegalStateException("Model source file is missing")
                if (!source.isFile) throw IllegalStateException("Model source file no longer exists")
                parseFile(source, existing.id, existing.name, source.extension.lowercase(), onProgress)
            }
            val loaded = parsed.copy(
                id = existing.id,
                name = existing.name,
                category = existing.category,
                defaultColor = existing.defaultColor.clone(),
                isUserImported = existing.isUserImported,
                sourcePath = existing.sourcePath,
                isLazyLoaded = false,
                previewTriangles = existing.previewTriangles
            )
            synchronized(this) { modelsMap[modelId] = loaded }
            onProgress?.invoke(100, "Model ready")
            loaded
        } catch (e: OutOfMemoryError) {
            lastImportError = "Model '${existing.name}' exceeds available memory. Try an optimized GLB with smaller textures."
            android.util.Log.e("ModelRegistry", lastImportError ?: "Model out of memory", e)
            null
        } catch (e: Exception) {
            lastImportError = "Could not load '${existing.name}': ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.e("ModelRegistry", lastImportError ?: "Model parse failed", e)
            null
        }
    }

    /** Replace loaded but unused file-backed models with small gallery entries and bounded previews. */
    fun releaseUnusedModels(retainedModelIds: Set<String>) {
        synchronized(modelLoadLock) {
            synchronized(this) {
                val entries = modelsMap.toMap()
                for ((id, mesh) in entries) {
                    if (mesh.sourcePath.isNullOrBlank() || mesh.isLazyLoaded || id in retainedModelIds) continue
                    val preview = mesh.previewTriangles ?: runCatching { mesh.buildPreviewTriangles(72) }.getOrNull()
                    modelsMap[id] = createLazyStub(
                        id = mesh.id, name = mesh.name, category = mesh.category,
                        sourcePath = mesh.sourcePath,
                        isUserImported = mesh.isUserImported,
                        boundsSize = mesh.boundsSize.copy(),
                        defaultColor = mesh.defaultColor.clone(),
                        previewTriangles = preview,
                        wasReducedForMobile = mesh.wasReducedForMobile,
                        importWarning = mesh.importWarning
                    )
                }
            }
        }
        // Do not call System.gc() per placement; let Android's memory manager collect the released
        // mesh/texture references without forcing a synchronous collection on an interaction path.
    }

    private fun createLazyStub(
        id: String,
        name: String,
        category: String,
        sourcePath: String?,
        isUserImported: Boolean,
        boundsSize: Vector3,
        defaultColor: FloatArray,
        previewTriangles: FloatArray? = null,
        wasReducedForMobile: Boolean = false,
        importWarning: String? = null
    ): Custom3DMesh = Custom3DMesh(
        id = id,
        name = name,
        category = category,
        vertexBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_VERTICES),
        normalBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_NORMALS),
        indexBuffer = MeshBuilder.createShortBuffer(MeshBuilder.CUBE_INDICES),
        vertexCount = MeshBuilder.CUBE_INDICES.size,
        defaultColor = defaultColor.clone(),
        boundsSize = boundsSize.copy(),
        isUserImported = isUserImported,
        sourcePath = sourcePath,
        wasReducedForMobile = wasReducedForMobile,
        importWarning = importWarning,
        isLazyLoaded = true,
        previewTriangles = previewTriangles
    )

    private fun registerPrebuiltModels() {
        // Model 1: Genshin Floating Platform (Landform)
        val platformMesh = createPrebuiltPlatform(
            id = "prebuilt_floating_platform",
            name = "Floating Platform",
            category = "Landform",
            topColor = floatArrayOf(0.35f, 0.72f, 0.35f, 1f), // Lush grass green
            baseColor = floatArrayOf(0.45f, 0.42f, 0.38f, 1f)  // Stone rock
        )
        modelsMap[platformMesh.id] = platformMesh

        // Model 2: Ancient Rune Cube (Building)
        val runeCube = createPrebuiltCube(
            id = "prebuilt_rune_cube",
            name = "Ancient Rune Cube",
            category = "Building",
            color = floatArrayOf(0.32f, 0.65f, 0.88f, 1f),
            loadCost = 20
        )
        modelsMap[runeCube.id] = runeCube

        // Model 3: Adepti Teapot Vessel (Companion)
        val teapotVessel = createPrebuiltTeapot(
            id = "prebuilt_teapot_vessel",
            name = "Adepti Teapot",
            category = "Companion",
            color = floatArrayOf(0.95f, 0.75f, 0.22f, 1f)
        )
        modelsMap[teapotVessel.id] = teapotVessel

        // Model 4: Training Golem NPC (Companion)
        val trainingGolem = createPrebuiltGolem(
            id = "prebuilt_training_golem",
            name = "Training Golem NPC",
            category = "Companion",
            color = floatArrayOf(0.85f, 0.35f, 0.28f, 1f)
        )
        modelsMap[trainingGolem.id] = trainingGolem

        // Model 5: Crystal Monolith (Landscape)
        val crystalMonolith = createPrebuiltSpire(
            id = "prebuilt_crystal_monolith",
            name = "Crystal Monolith",
            category = "Landscape",
            color = floatArrayOf(0.72f, 0.38f, 0.95f, 1f)
        )
        modelsMap[crystalMonolith.id] = crystalMonolith

        // Model 6: Wooden Slat Bridge (Building)
        val woodenBridge = createPrebuiltBridge(
            id = "prebuilt_wooden_bridge",
            name = "Wooden Bridge",
            category = "Building",
            color = floatArrayOf(0.62f, 0.42f, 0.24f, 1f)
        )
        modelsMap[woodenBridge.id] = woodenBridge
    }

    fun importModelFromUri(context: Context, uri: Uri, fileName: String): Custom3DMesh? {
        var savedFile: File? = null
        var metadataFile: File? = null
        lastImportError = null
        return try {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("glb", "obj", "fbx")) {
                "Supported file types are .glb, .obj, and static-mesh .fbx. Use a self-contained .glb for material textures or animated/skeletal models."
            }
            val modelId = "imported_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val modelName = fileName.substringBeforeLast('.').ifBlank { "Imported Model" }
            val storageDir = File(context.filesDir, "imported_models")
            if (!storageDir.exists() && !storageDir.mkdirs()) throw IllegalStateException("Could not create model storage folder")
            savedFile = File(storageDir, "$modelId.$extension")
            context.contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(savedFile!!).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMPORT_FILE_BYTES) {
                            "Model file exceeds 512 MB. Export an optimized GLB or reduce unused data/textures."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("The selected model file could not be opened")
            require(savedFile!!.length() >= 16L) { "The selected model file is empty or too small to be a valid 3D model." }

            // Import copies and indexes the source only. It does not build giant vertex buffers or
            // decode all embedded textures until the user actually uses this model in the world.
            metadataFile = File(storageDir, "$modelId.meta.json")
            metadataFile!!.writeText(
                JSONObject().put("originalName", modelName).put("originalFileName", fileName).toString()
            )
            val stub = createLazyStub(
                id = modelId, name = modelName, category = "Imported",
                sourcePath = savedFile!!.absolutePath, isUserImported = true,
                boundsSize = Vector3(1f, 1f, 1f),
                defaultColor = floatArrayOf(0.68f, 0.68f, 0.68f, 1f)
            )
            synchronized(this) { modelsMap[modelId] = stub }
            stub
        } catch (e: Exception) {
            savedFile?.delete(); metadataFile?.delete()
            lastImportError = e.message ?: e.javaClass.simpleName
            null
        } catch (e: OutOfMemoryError) {
            savedFile?.delete(); metadataFile?.delete()
            lastImportError = "The device ran out of memory while importing this model."
            null
        }
    }

    private fun registerPackagedSampleModels(context: Context) {
        data class Sample(val id: String, val displayName: String)
        val known = mapOf(
            "flat_surface.glb" to Sample("flat_surface_platform", "Flat Surface Spawn Platform"),
            "cube.glb" to Sample("sample_cube_glb", "Cube GLB"),
            "green_cube.glb" to Sample("sample_green_cube_glb", "Green Cube GLB"),
            "genshin_impact_-_skirk.glb" to Sample("genshin_skirk_character", "Genshin Impact - Skirk")
        )
        // These four are actual in-world defaults or the default avatar, so make them render-ready.
        // Extra bundled samples stay as lightweight entries until selected from the gallery.
        val requiredRuntimeIds = setOf("flat_surface_platform", "sample_cube_glb", "sample_green_cube_glb", "genshin_skirk_character")
        val assets = context.assets.list("").orEmpty().filter { it.endsWith(".glb", true) }.sortedBy { it.lowercase() }
        val neutralGray = floatArrayOf(0.68f, 0.68f, 0.68f, 1f)
        for (assetName in assets) {
            val normalizedName = assetName.substringAfterLast('/').lowercase()
            val slug = normalizedName.substringBeforeLast('.').replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val sample = known[normalizedName] ?: Sample(
                "preloaded_${slug.ifBlank { "model" }}",
                assetName.substringBeforeLast('.').replace(Regex("[_-]+"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                    .split(' ').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
            )
            if (synchronized(this) { modelsMap.containsKey(sample.id) }) continue
            try {
                val model = if (sample.id in requiredRuntimeIds) {
                    context.assets.open(assetName).use { input ->
                        GLBModelParser.parse(input, sample.id, sample.displayName, "Pre Models", neutralGray)
                    }.copy(
                        name = sample.displayName,
                        category = "Pre Models",
                        defaultColor = neutralGray.clone(),
                        isUserImported = false,
                        sourcePath = "asset://$assetName",
                        isLazyLoaded = false
                    )
                } else {
                    createLazyStub(
                        id = sample.id, name = sample.displayName, category = "Pre Models",
                        sourcePath = "asset://$assetName", isUserImported = false,
                        boundsSize = Vector3(1f, 1f, 1f), defaultColor = neutralGray
                    )
                }
                synchronized(this) { if (!modelsMap.containsKey(sample.id)) modelsMap[sample.id] = model }
            } catch (e: OutOfMemoryError) {
                lastImportError = "Preloaded GLB '$assetName' exceeds available memory and was skipped."
                android.util.Log.w("ModelRegistry", lastImportError ?: "Preloaded GLB skipped")
            } catch (e: Exception) {
                lastImportError = "Preloaded GLB '$assetName' could not load: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w("ModelRegistry", lastImportError ?: "Preloaded GLB skipped", e)
            }
        }
    }

    private fun parseFile(
        file: File,
        id: String,
        name: String,
        extension: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): Custom3DMesh {
        onProgress?.invoke(2, "Opening model file")
        return when (extension.lowercase()) {
            "glb" -> GLBModelParser.parse(file, id, name, "Imported", onProgress = onProgress)
            "obj" -> {
                onProgress?.invoke(15, "Parsing OBJ geometry")
                file.inputStream().use { OBJModelParser.parse(it, id, name, "Imported") }
                    .also { onProgress?.invoke(96, "Finalizing model") }
            }
            "fbx" -> {
                onProgress?.invoke(15, "Parsing FBX geometry")
                FBXModelParser.parse(file, id, name, "Imported")
                    .also { onProgress?.invoke(96, "Finalizing model") }
            }
            "gltf" -> throw IllegalArgumentException("Import a self-contained .glb file instead of plain .gltf")
            else -> throw IllegalArgumentException("Unsupported 3D model extension: $extension")
        }
    }

    // --- Mesh Generation Helpers for Prebuilt Models ---

    fun createPrebuiltCube(
        id: String,
        name: String,
        category: String,
        color: FloatArray,
        loadCost: Int = 20
    ): Custom3DMesh {
        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_VERTICES),
            normalBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_NORMALS),
            indexBuffer = MeshBuilder.createShortBuffer(MeshBuilder.CUBE_INDICES),
            vertexCount = MeshBuilder.CUBE_INDICES.size,
            defaultColor = color,
            boundsSize = Vector3(1f, 1f, 1f),
            loadCost = loadCost
        )
    }

    private fun createPrebuiltPlatform(
        id: String,
        name: String,
        category: String,
        topColor: FloatArray,
        baseColor: FloatArray
    ): Custom3DMesh {
        // Multi-tier floating island block (chamfered bevel)
        val vertices = floatArrayOf(
            // Top flat face
            -1.5f, 0.4f, 1.5f,   1.5f, 0.4f, 1.5f,   1.5f, 0.4f, -1.5f,
            -1.5f, 0.4f, 1.5f,   1.5f, 0.4f, -1.5f,  -1.5f, 0.4f, -1.5f,
            // Slanted sides (Island base narrowing down)
            -1.5f, 0.4f, 1.5f,   -1.0f, -0.6f, 1.0f,  1.0f, -0.6f, 1.0f,
            -1.5f, 0.4f, 1.5f,    1.0f, -0.6f, 1.0f,  1.5f, 0.4f, 1.5f,

            1.5f, 0.4f, 1.5f,    1.0f, -0.6f, 1.0f,   1.0f, -0.6f, -1.0f,
            1.5f, 0.4f, 1.5f,    1.0f, -0.6f, -1.0f,  1.5f, 0.4f, -1.5f,

            1.5f, 0.4f, -1.5f,   1.0f, -0.6f, -1.0f, -1.0f, -0.6f, -1.0f,
            1.5f, 0.4f, -1.5f,  -1.0f, -0.6f, -1.0f, -1.5f, 0.4f, -1.5f,

            -1.5f, 0.4f, -1.5f, -1.0f, -0.6f, -1.0f, -1.0f, -0.6f, 1.0f,
            -1.5f, 0.4f, -1.5f, -1.0f, -0.6f, 1.0f,  -1.5f, 0.4f, 1.5f,
            // Bottom cap
            -1.0f, -0.6f, 1.0f,  1.0f, -0.6f, -1.0f,  1.0f, -0.6f, 1.0f,
            -1.0f, -0.6f, 1.0f, -1.0f, -0.6f, -1.0f,  1.0f, -0.6f, -1.0f
        )

        val normals = FloatArray(vertices.size) { idx ->
            when (idx % 3) {
                0 -> vertices[idx] * 0.4f
                1 -> if (vertices[idx] > 0f) 1.0f else -0.8f
                else -> vertices[idx] * 0.4f
            }
        }

        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(vertices),
            normalBuffer = MeshBuilder.createFloatBuffer(normals),
            vertexCount = vertices.size / 3,
            defaultColor = topColor,
            boundsSize = Vector3(3.0f, 1.0f, 3.0f),
            loadCost = 30
        )
    }

    private fun createPrebuiltTeapot(
        id: String,
        name: String,
        category: String,
        color: FloatArray
    ): Custom3DMesh {
        // Octagonal jar with lid and spout
        val vList = ArrayList<Float>()
        val nList = ArrayList<Float>()
        val segments = 8
        val radius = 0.6f
        val height = 0.8f

        // Body cylinders
        for (i in 0 until segments) {
            val a1 = (i * 2.0 * Math.PI / segments).toFloat()
            val a2 = ((i + 1) * 2.0 * Math.PI / segments).toFloat()

            val x1 = kotlin.math.cos(a1) * radius; val z1 = kotlin.math.sin(a1) * radius
            val x2 = kotlin.math.cos(a2) * radius; val z2 = kotlin.math.sin(a2) * radius

            // Wall quad triangulated
            vList.addAll(listOf(x1, -height * 0.5f, z1,  x2, -height * 0.5f, z2,  x2, height * 0.5f, z2))
            vList.addAll(listOf(x1, -height * 0.5f, z1,  x2, height * 0.5f, z2,  x1, height * 0.5f, z1))

            nList.addAll(listOf(x1, 0f, z1,  x2, 0f, z2,  x2, 0f, z2))
            nList.addAll(listOf(x1, 0f, z1,  x2, 0f, z2,  x1, 0f, z1))
        }

        val posArr = vList.toFloatArray()
        val normArr = nList.toFloatArray()

        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(posArr),
            normalBuffer = MeshBuilder.createFloatBuffer(normArr),
            vertexCount = posArr.size / 3,
            defaultColor = color,
            boundsSize = Vector3(1.2f, 1.0f, 1.2f),
            loadCost = 60
        )
    }

    private fun createPrebuiltGolem(
        id: String,
        name: String,
        category: String,
        color: FloatArray
    ): Custom3DMesh {
        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_VERTICES),
            normalBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CUBE_NORMALS),
            indexBuffer = MeshBuilder.createShortBuffer(MeshBuilder.CUBE_INDICES),
            vertexCount = MeshBuilder.CUBE_INDICES.size,
            defaultColor = color,
            boundsSize = Vector3(1.0f, 2.0f, 1.0f),
            loadCost = 80
        )
    }

    private fun createPrebuiltSpire(
        id: String,
        name: String,
        category: String,
        color: FloatArray
    ): Custom3DMesh {
        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CRYSTAL_VERTICES),
            normalBuffer = MeshBuilder.createFloatBuffer(MeshBuilder.CRYSTAL_NORMALS),
            vertexCount = MeshBuilder.CRYSTAL_VERTICES.size / 3,
            defaultColor = color,
            boundsSize = Vector3(0.8f, 2.4f, 0.8f),
            loadCost = 40
        )
    }

    private fun createPrebuiltBridge(
        id: String,
        name: String,
        category: String,
        color: FloatArray
    ): Custom3DMesh {
        val bridgeVertices = floatArrayOf(
            -0.8f, 0.1f, 2.0f,   0.8f, 0.1f, 2.0f,   0.8f, 0.1f, -2.0f,
            -0.8f, 0.1f, 2.0f,   0.8f, 0.1f, -2.0f, -0.8f, 0.1f, -2.0f,
            -0.8f, -0.1f, -2.0f,  0.8f, -0.1f, -2.0f,  0.8f, -0.1f, 2.0f,
            -0.8f, -0.1f, -2.0f,  0.8f, -0.1f, 2.0f, -0.8f, -0.1f, 2.0f
        )
        val bridgeNormals = floatArrayOf(
            0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,
            0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,
            0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f
        )

        return Custom3DMesh(
            id = id,
            name = name,
            category = category,
            vertexBuffer = MeshBuilder.createFloatBuffer(bridgeVertices),
            normalBuffer = MeshBuilder.createFloatBuffer(bridgeNormals),
            vertexCount = bridgeVertices.size / 3,
            defaultColor = color,
            boundsSize = Vector3(1.6f, 0.2f, 4.0f),
            loadCost = 30
        )
    }
}
