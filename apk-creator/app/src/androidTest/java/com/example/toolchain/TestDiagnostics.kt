
package com.example.toolchain

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestDiagnostics : TestWatcher() {

    companion object {
        private const val TAG = "ToolchainDiagnostics"

        private fun logFile(): File {
            val context =
                InstrumentationRegistry.getInstrumentation().targetContext

            val directory = File(context.filesDir, "test-logs")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException(
                    "Cannot create test log directory: ${directory.absolutePath}"
                )
            }

            return File(directory, "toolchain-tests.log")
        }

        @Synchronized
        fun log(stage: String, message: String) {
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

            val line = "[$timestamp] [$stage] $message"

            Log.i(TAG, line)

            try {
                logFile().appendText(line + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Could not write test log file", e)
            }
        }

        fun exception(stage: String, error: Throwable) {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))

            log(stage, "EXCEPTION:\n$writer")

            var cause = error.cause
            var depth = 0

            while (cause != null && depth < 20) {
                log(
                    stage,
                    "CAUSE[$depth]: ${cause.javaClass.name}: ${cause.message}"
                )
                cause = cause.cause
                depth++
            }
        }

        fun checkpoint(stage: String) {
            log(stage, "CHECKPOINT PASSED")
        }
    }

    override fun starting(description: Description) {
        log("TEST_START", description.displayName)
    }

    override fun succeeded(description: Description) {
        log("TEST_PASS", description.displayName)
    }

    override fun failed(error: Throwable, description: Description) {
        log("TEST_FAIL", description.displayName)
        exception("TEST_FAIL", error)
    }

    override fun finished(description: Description) {
        log("TEST_END", description.displayName)
    }
}
