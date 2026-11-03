package com.example.toolchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter

@RunWith(AndroidJUnit4::class)
class InProcessCompilerSmokeTest {

    private val instrumentation =
        InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun prepareWorkspace(): File {
        val dir = File(context.cacheDir, "in-process-compiler-smoke")
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Cannot create test workspace: $dir" }

        val assetsDir = File(dir, "inputs")
        check(assetsDir.mkdirs())

        instrumentation.context.assets.open("android.jar").use { input ->
            File(assetsDir, "android.jar").outputStream().use(input::copyTo)
        }

        instrumentation.context.assets
            .open("kotlin-stdlib-1.9.24.jar").use { input ->
                File(assetsDir, "kotlin-stdlib-1.9.24.jar")
                    .outputStream().use(input::copyTo)
            }

        return dir
    }

    private fun compileJava(workspace: File): Pair<File, String> {
        val androidJar = File(workspace, "inputs/android.jar")
        val sourceDir = File(workspace, "src")
        val classesDir = File(workspace, "classes")
        check(sourceDir.mkdirs())
        check(classesDir.mkdirs())

        val source = File(sourceDir, "Greeter.java")
        source.writeText(
            """
            public class Greeter {
                public String message() {
                    return "JAVA_COMPILE_OK";
                }
            }
            """.trimIndent()
        )

        val output = StringWriter()
        val writer = PrintWriter(output)

        val success = BatchCompiler.compile(
            arrayOf(
                "-17",
                "-nowarn",
                "-proc:none",
                "-classpath", androidJar.absolutePath,
                "-d", classesDir.absolutePath,
                source.absolutePath
            ),
            writer,
            writer,
            null
        )

        writer.flush()

        val classFile = File(classesDir, "Greeter.class")
        val report = output.toString()

        assertTrue(
            "JAVA_COMPILER_FAILED\n$report",
            success && classFile.isFile && classFile.length() > 0
        )

        return classesDir to report
    }

    @Test
    fun test01JavaCompilerCreatesClassFile() {
        val workspace = prepareWorkspace()
        val (classesDir, _) = compileJava(workspace)

        val classFile = File(classesDir, "Greeter.class")
        assertTrue("Greeter.class was not created", classFile.isFile)
        assertTrue("Greeter.class is empty", classFile.length() > 0)
    }

    @Test
    fun test02KotlinCompilerCompilesAgainstJavaClass() {
        val workspace = prepareWorkspace()
        val (classesDir, _) = compileJava(workspace)

        val androidJar = File(workspace, "inputs/android.jar")
        val stdlibJar = File(workspace, "inputs/kotlin-stdlib-1.9.24.jar")
        val source = File(workspace, "KotlinProbe.kt")

        source.writeText(
            """
            class KotlinProbe {
                fun message(): String = Greeter().message()
            }
            """.trimIndent()
        )

        val output = ByteArrayOutputStream()
        val stream = PrintStream(output, true, "UTF-8")

        val args = arrayOf(
            "-classpath",
            listOf(androidJar, stdlibJar, classesDir)
                .joinToString(File.pathSeparator) { it.absolutePath },
            "-d", classesDir.absolutePath,
            "-no-stdlib",
            "-no-reflect",
            source.absolutePath
        )

        val result = try {
            K2JVMCompiler().exec(stream, *args)
        } catch (e: Throwable) {
            throw AssertionError(
                "KOTLIN_COMPILER_CRASHED\n${output.toString("UTF-8")}",
                e
            )
        } finally {
            stream.close()
        }

        val kotlinClass = File(classesDir, "KotlinProbe.class")
        assertTrue(
            "KOTLIN_COMPILER_FAILED: result=$result\n${output.toString("UTF-8")}",
            result == ExitCode.OK &&
                    kotlinClass.isFile &&
                    kotlinClass.length() > 0
        )
    }

    @Test
    fun test03D8ConvertsJavaAndKotlinClassesToDex() {
        val workspace = prepareWorkspace()
        val (classesDir, _) = compileJava(workspace)

        val androidJar = File(workspace, "inputs/android.jar")
        val stdlibJar = File(workspace, "inputs/kotlin-stdlib-1.9.24.jar")
        val kotlinSource = File(workspace, "KotlinProbe.kt")

        kotlinSource.writeText(
            """
            class KotlinProbe {
                fun message(): String = Greeter().message()
            }
            """.trimIndent()
        )

        val compilerOutput = ByteArrayOutputStream()
        val stream = PrintStream(compilerOutput, true, "UTF-8")

        val result = try {
            K2JVMCompiler().exec(
                stream,
                "-classpath",
                listOf(androidJar, stdlibJar, classesDir)
                    .joinToString(File.pathSeparator) { it.absolutePath },
                "-d", classesDir.absolutePath,
                "-no-stdlib",
                "-no-reflect",
                kotlinSource.absolutePath
            )
        } finally {
            stream.close()
        }

        assertTrue(
            "Kotlin prerequisite failed: $result\n${compilerOutput.toString("UTF-8")}",
            result == ExitCode.OK &&
                    File(classesDir, "KotlinProbe.class").isFile
        )

        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.toPath() }
            .toList()

        assertTrue("No class files available for D8", classFiles.isNotEmpty())

        val dexDir = File(workspace, "dex")
        check(dexDir.mkdirs())

        try {
            val command = D8Command.builder()
                .addProgramFiles(classFiles)
                .addLibraryFiles(androidJar.toPath())
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                .build()

            D8.run(command)
        } catch (e: Throwable) {
            throw AssertionError("D8_FAILED: ${e.message}", e)
        }

        val dexFile = File(dexDir, "classes.dex")
        assertTrue(
            "D8 did not produce a non-empty classes.dex",
            dexFile.isFile && dexFile.length() > 0
        )
    }
}



















