package com.example.sfx

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Central audio owner for BlockWorld 3D.
 *
 * Assets expected at:
 *   app/src/main/assets/sfx/attack_hit.ogg
 *   app/src/main/assets/sfx/jump.ogg
 *   app/src/main/assets/sfx/background_theme.ogg
 *
 * SoundPool is used for short effects; MediaPlayer is used for the looping theme.
 * All assets are copied to app cache first, so this works even if Android packaged an asset
 * with compression (AssetFileDescriptor.openFd would not work for a compressed asset).
 */
object GameAudioManager {
    private const val TAG = "GameAudioManager"
    private const val MUSIC_ASSET = "sfx/background_theme.ogg"

    private var appContext: Context? = null
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<GameSound, Int>()
    private val readySounds = mutableSetOf<GameSound>()

    private var musicPlayer: MediaPlayer? = null
    private var currentMusicAsset: String? = null
    @Volatile private var musicRequested = false
    @Volatile private var musicPrepared = false

    private var masterVolume = 1.0f
    private var sfxVolume = 1.0f
    private var musicVolume = 0.65f
    @Volatile private var soundEnabled = true
    @Volatile private var sfxEnabled = true
    @Volatile private var musicEnabled = true

    @Synchronized
    fun initialize(context: Context) {
        if (soundPool != null && appContext != null) return

        appContext = context.applicationContext
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(attributes)
            .build()
        soundPool = pool
        soundIds.clear()
        readySounds.clear()

        pool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(this) {
                val sound = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                if (status == 0 && sound != null) {
                    readySounds.add(sound)
                } else if (status != 0) {
                    Log.e(TAG, "Sound load failed: $sound (status=$status)")
                }
            }
        }

        GameSound.values().forEach { sound ->
            val file = copyAssetToCache(sound.assetPath)
            if (file != null) {
                val id = pool.load(file.absolutePath, 1)
                if (id != 0) soundIds[sound] = id
                else Log.e(TAG, "SoundPool rejected ${sound.assetPath}")
            }
        }
    }

    /** Play one short sound. Calls before loading completes are safely ignored. */
    fun play(sound: GameSound, volume: Float = 1.0f) {
        val pool: SoundPool
        val id: Int
        val volumeNow: Float
        synchronized(this) {
            pool = soundPool ?: run {
                Log.w(TAG, "initialize(context) must be called before playing $sound")
                return
            }
            id = soundIds[sound] ?: return
            if (sound !in readySounds) return
            if (!soundEnabled || !sfxEnabled) return
            volumeNow = (masterVolume * sfxVolume * volume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }
        pool.play(id, volumeNow, volumeNow, 1, 0, 1.0f)
    }

    /** Begin looping background music. Safe to call repeatedly for the same asset. */
    @Synchronized
    fun playBackgroundMusic(assetPath: String = MUSIC_ASSET) {
        if (!soundEnabled || !musicEnabled) {
            musicRequested = false
            musicPlayer?.let { player ->
                if (musicPrepared) try { if (player.isPlaying) player.pause() } catch (_: Exception) { }
            }
            return
        }
        val context = appContext
        if (context == null) {
            Log.w(TAG, "initialize(context) must be called before playing background music")
            return
        }

        if (currentMusicAsset == assetPath && musicPlayer != null) {
            resumeBackgroundMusic()
            return
        }

        releaseMusicPlayer()
        val file = copyAssetToCache(assetPath) ?: return
        currentMusicAsset = assetPath
        musicRequested = true
        musicPrepared = false

        val player = MediaPlayer()
        musicPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.isLooping = true
        player.setVolume((masterVolume * musicVolume).coerceIn(0f, 1f), (masterVolume * musicVolume).coerceIn(0f, 1f))
        player.setOnPreparedListener { preparedPlayer ->
            synchronized(this) {
                if (musicPlayer !== preparedPlayer) return@setOnPreparedListener
                musicPrepared = true
                if (musicRequested) {
                    try { preparedPlayer.start() } catch (error: IllegalStateException) {
                        Log.e(TAG, "Could not start background music", error)
                    }
                }
            }
        }
        player.setOnErrorListener { failedPlayer, what, extra ->
            Log.e(TAG, "Background music error: what=$what extra=$extra")
            synchronized(this) {
                if (musicPlayer === failedPlayer) releaseMusicPlayer()
            }
            true
        }

        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare background music: $assetPath", error)
            releaseMusicPlayer()
        }
    }

    @Synchronized
    fun pauseBackgroundMusic() {
        musicRequested = false
        val player = musicPlayer ?: return
        if (musicPrepared) {
            try {
                if (player.isPlaying) player.pause()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Music could not be paused", error)
            }
        }
    }

    @Synchronized
    fun resumeBackgroundMusic() {
        musicRequested = true
        val player = musicPlayer ?: run {
            playBackgroundMusic()
            return
        }
        if (musicPrepared) {
            try {
                if (!player.isPlaying) player.start()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Music could not be resumed", error)
            }
        }
    }

    @Synchronized
    fun stopBackgroundMusic() {
        musicRequested = false
        releaseMusicPlayer()
        currentMusicAsset = null
    }

    @Synchronized
    fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
        if (!enabled) pauseBackgroundMusic()
        else if (musicEnabled) resumeBackgroundMusic()
    }

    @Synchronized
    fun setSfxEnabled(enabled: Boolean) {
        sfxEnabled = enabled
    }

    @Synchronized
    fun setMusicEnabled(enabled: Boolean) {
        musicEnabled = enabled
        if (enabled && soundEnabled) resumeBackgroundMusic() else pauseBackgroundMusic()
    }

    @Synchronized
    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        applyMusicVolume()
    }

    @Synchronized
    fun setSfxVolume(volume: Float) {
        sfxVolume = volume.coerceIn(0f, 1f)
    }

    @Synchronized
    fun setMusicVolume(volume: Float) {
        musicVolume = volume.coerceIn(0f, 1f)
        applyMusicVolume()
    }

    @Synchronized
    fun release() {
        musicRequested = false
        releaseMusicPlayer()
        try { soundPool?.release() } catch (_: Exception) { }
        soundPool = null
        soundIds.clear()
        readySounds.clear()
        appContext = null
    }

    private fun applyMusicVolume() {
        val value = if (soundEnabled && musicEnabled) (masterVolume * musicVolume).coerceIn(0f, 1f) else 0f
        try { musicPlayer?.setVolume(value, value) } catch (_: Exception) { }
    }

    private fun releaseMusicPlayer() {
        musicPrepared = false
        val player = musicPlayer
        musicPlayer = null
        if (player != null) {
            try { player.setOnPreparedListener(null) } catch (_: Exception) { }
            try { player.setOnErrorListener(null) } catch (_: Exception) { }
            try { player.stop() } catch (_: Exception) { }
            try { player.release() } catch (_: Exception) { }
        }
    }

    private fun copyAssetToCache(assetPath: String): File? {
        val context = appContext ?: return null
        val safeName = assetPath.replace('/', '_').replace('\\', '_')
        val cacheFolder = File(context.cacheDir, "game-audio")
        if (!cacheFolder.exists() && !cacheFolder.mkdirs()) {
            Log.e(TAG, "Could not create audio cache directory")
            return null
        }
        val target = File(cacheFolder, safeName)
        if (target.exists() && target.length() > 0L) return target

        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target
        } catch (error: Exception) {
            Log.e(TAG, "Missing audio asset: $assetPath", error)
            null
        }
    }
}
