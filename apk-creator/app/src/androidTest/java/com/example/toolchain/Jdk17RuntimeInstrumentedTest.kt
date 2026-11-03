package com.example.toolchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.example.apkgenerator.toolchain.Jdk17Installer
@RunWith(AndroidJUnit4::class)
class Jdk17RuntimeInstrumentedTest {

    @Test
    fun extractJdkAndInspectExecutables() {
        val context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val jdkDir = Jdk17Installer.install(context)

        val java = File(jdkDir, "bin/java")
        val javac = File(jdkDir, "bin/javac")
        val release = File(jdkDir, "release")

        assertTrue("Missing java: ${java.absolutePath}", java.isFile)
        assertTrue("Missing javac: ${javac.absolutePath}", javac.isFile)
        assertTrue("Missing release metadata", release.isFile)
        assertTrue("java is not executable", java.canExecute())
        assertTrue("javac is not executable", javac.canExecute())

        val releaseText = release.readText()
        assertTrue(
            "JDK release metadata does not identify Java 17",
            releaseText.contains("JAVA_VERSION=\"17") ||
                    releaseText.contains("JAVA_VERSION=17")
        )

        val report = File(context.filesDir, "jdk17-runtime-report.txt")
        report.writeText(
            """
            JDK directory: ${jdkDir.absolutePath}
            java exists: ${java.isFile}
            java executable: ${java.canExecute()}
            javac exists: ${javac.isFile}
            javac executable: ${javac.canExecute()}
            release metadata:
            $releaseText
            """.trimIndent()
        )

        assertTrue("Runtime report was not created", report.isFile)
        assertEquals(jdkDir.canonicalFile, java.parentFile.parentFile.canonicalFile)
    }
}
