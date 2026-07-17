package com.kotlin.ratherllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic unit tests (run on the JVM via `./gradlew test`, no device needed). */
class LogicUnitTest {

    @Test
    fun formatBytes_scalesUnits() {
        assertEquals("—", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1 MB", formatBytes(1024L * 1024))
        // GB and up get one decimal.
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("2.9 GB", formatBytes(3_155_051_328L)) // the Gemma 3 4B Q4_0 file
    }

    @Test
    fun rcMessage_mapsKnownCodes() {
        assertEquals("Not a GGUF model (bad magic header)", Rc.message(Rc.BAD_MAGIC))
        assertEquals("Engine not implemented yet (Stage 1 skeleton)", Rc.message(Rc.NOT_IMPLEMENTED))
        assertTrue(Rc.message(Rc.OOM).contains("memory", ignoreCase = true))
        assertTrue(Rc.message(-98765).contains("-98765")) // unknown code echoes the number
    }

    @Test
    fun role_wireNamesMatchChatTemplateRoles() {
        assertEquals("system", Role.System.wire)
        assertEquals("user", Role.User.wire)
        assertEquals("assistant", Role.Assistant.wire)
    }

    @Test
    fun genParams_defaultsFollowGemma3Recommendation() {
        val p = GenParams()
        assertEquals(1.0f, p.temperature, 0.0001f)
        assertEquals(64, p.topK)
        assertEquals(0.95f, p.topP, 0.0001f)
        assertTrue("min-p should filter the lossy-quant tail", p.minP > 0f)
    }

    // NB: GgufModelInfo.fromJson uses org.json, which isn't available in plain
    // JVM unit tests (it would just return null here) — covered by device tests.
}
