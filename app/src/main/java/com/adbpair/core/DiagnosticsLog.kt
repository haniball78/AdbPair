package com.adbpair.core

import android.os.SystemClock
import java.io.File
import java.util.UUID

/**
 * Forensic diagnostics: one JSONL line per event, fingerprint-only (never key
 * material), pairing codes scrubbed. Takes a plain directory so it stays
 * unit-testable on the JVM without Robolectric.
 *
 * Every line carries the process generation + boot uptime, so a log can prove
 * whether the process died and came back (elapsed-time monotonicity alone
 * cannot — elapsedRealtime() counts since boot, not since process start).
 */
class DiagnosticsLog(private val dir: File) {

    private val generation: String = UUID.randomUUID().toString().take(8)
    private val file: File = File(dir, LOG_FILE)
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    fun log(tag: String, event: String, fields: Map<String, String> = emptyMap()) {
        val scrubbed = fields.mapValues { (_, value) -> scrub(value) }
        val parts = ArrayList<String>()
        parts += "\"ts\":${System.currentTimeMillis()}"
        parts += "\"uptime_ms\":${SystemClock.elapsedRealtime()}"
        parts += "\"gen\":\"$generation\""
        parts += "\"tag\":${json(tag)}"
        parts += "\"event\":${json(event)}"
        for ((key, value) in scrubbed) parts += "\"$key\":${json(value)}"
        val line = "{${parts.joinToString(",")}}"
        synchronized(lock) {
            rotateIfNeeded()
            file.appendText(line + "\n")
        }
    }

    fun files(): List<File> {
        val all = dir.listFiles()?.filter { it.name.startsWith("adbpair-log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        return all
    }

    /** A standalone 6-digit number in our logs is a pairing code. Ports are at most 5 digits. */
    internal fun scrub(value: String): String =
        value.replace(Regex("\\b\\d{6}\\b"), "******")

    private fun json(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun rotateIfNeeded() {
        if (file.exists() && file.length() > MAX_BYTES) {
            for (i in MAX_ROTATIONS - 1 downTo 1) {
                val older = File(dir, "$LOG_FILE.$i")
                val newer = File(dir, "$LOG_FILE.${i + 1}")
                if (older.exists()) older.renameTo(newer)
            }
            file.renameTo(File(dir, "$LOG_FILE.1"))
        }
    }

    companion object {
        const val LOG_FILE = "adbpair-log.jsonl"
        const val MAX_BYTES = 2L * 1024L * 1024L
        const val MAX_ROTATIONS = 3
    }
}
