package com.example.htmlandurlapkcreator

import com.example.htmlandurlapkcreator.build.ArscCompiler
import com.example.htmlandurlapkcreator.build.AxmlCompiler
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testArscCompiler() {
        val bytes = ArscCompiler.compileResourceTable("MyTestApp")
        assertNotNull(bytes)
        assertTrue(bytes.size > 100)
    }

    @Test
    fun testAxmlCompiler() {
        val bytes = AxmlCompiler.compileManifest("com.test.app", "TestApp")
        assertNotNull(bytes)
        assertTrue(bytes.size > 100)
    }
}
