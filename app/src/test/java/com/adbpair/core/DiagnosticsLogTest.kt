package com.adbpair.core

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {

    @Test
    fun `log writes a line carrying tag and event`() {
        val dir = Files.createTempDirectory("adbpair-log-test").toFile()
        val log = DiagnosticsLog(dir)
        log.log("connect", "verified", mapOf("port" to "37711"))
        val text = dir.resolve(DiagnosticsLog.LOG_FILE).readText()
        assertTrue(text.contains("\"tag\":\"connect\""))
        assertTrue(text.contains("\"event\":\"verified\""))
        assertTrue(text.contains("\"port\":\"37711\""))
        assertTrue(text.contains("\"gen\":\""))
    }

    @Test
    fun `a six digit code is scrubbed, ports are not`() {
        val dir = Files.createTempDirectory("adbpair-log-test").toFile()
        val log = DiagnosticsLog(dir)
        log.log("pair", "submitted", mapOf("code" to "123456", "port" to "37711"))
        val text = dir.resolve(DiagnosticsLog.LOG_FILE).readText()
        assertFalse(text.contains("123456"))
        assertTrue(text.contains("******"))
        assertTrue(text.contains("37711"))
    }
}
