package com.example.game

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object RenderDiagnostics {

    private const val TAG = "BlockWorldRenderer"

    private val failedSubsystems =
        ConcurrentHashMap<String, String>()

    /**
     * Records a failure against the affected subsystem.
     * Duplicate errors are not printed continuously every frame.
     */
    fun reportFailure(
        subsystem: String,
        message: String,
        error: Exception? = null
    ) {
        val previous = failedSubsystems.put(
            subsystem,
            message
        )

        if (previous != message) {
            Log.e(
                TAG,
                "Subsystem=$subsystem; Error=$message",
                error
            )
        }
    }

    fun reportRecovery(subsystem: String) {
        val previous = failedSubsystems.remove(subsystem)

        if (previous != null) {
            Log.i(
                TAG,
                "Subsystem recovered: $subsystem"
            )
        }
    }

    fun getFailedSubsystems(): Map<String, String> {
        return failedSubsystems.toMap()
    }

    fun clear(subsystem: String) {
        failedSubsystems.remove(subsystem)
    }
}