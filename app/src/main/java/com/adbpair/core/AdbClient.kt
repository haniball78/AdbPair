package com.adbpair.core

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ShellResult(val exitCode: Int, val output: String, val allOutput: String)

/**
 * The Kadb wrapper. Every blocking call runs on a dedicated thread with a
 * CANCELLABLE timeout: Kadb itself has no timeouts (Kadb.pair can hang
 * forever — the 2026-11-09 first-pair hang). On timeout the future is
 * abandoned and the state guard released; the orphaned socket thread
 * finishes on its own and is harmless — the state machine re-checks by
 * attempting a connect, never by trusting the abandoned call.
 *
 * Identity files live in noBackupFilesDir (never backed up, never shared).
 */
class AdbClient(private val context: Context) {

    private val directory: File get() = context.noBackupFilesDir
    private val executor = Executors.newCachedThreadPool()

    @Synchronized
    fun ensureIdentity() {
        val cert = File(directory, CERT_FILE)
        val key = File(directory, KEY_FILE)
        if (cert.isFile && key.isFile) {
            KadbCert.set(cert.readBytes(), key.readBytes())
            return
        }
        // Mint fresh here, never KadbCert.get(): it only generates when its
        // in-memory key is empty, so after a reset it rewrites the SAME key
        // and the reset never changes the fingerprint (the kadb reset bug).
        val identity = AdbIdentity.generateFreshIdentity(
            notAfterMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650),
        )
        KadbCert.set(identity.first, identity.second)
        writeAtomically(cert, identity.first)
        writeAtomically(key, identity.second)
    }

    /** First 16 hex chars of the SHA-256 of the cert file. Fingerprint only — never key material. */
    fun fingerprint(): String? {
        val cert = File(directory, CERT_FILE)
        if (!cert.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.readBytes())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun pair(host: String, port: Int, code: String) {
        ensureIdentity()
        runWithTimeout(PairingStateMachine.PAIR_TIMEOUT_MS) {
            Kadb.pair(host, port, code, "AdbPair")
        }
        saveHost(host)
    }

    /** Verified live connection: a TLS connect + an authenticated `id` probe. */
    fun connectAndProbe(host: String, port: Int): Boolean {
        ensureIdentity()
        val result = shell(host, port, "id", PairingStateMachine.CONNECT_TIMEOUT_MS + PairingStateMachine.PROBE_TIMEOUT_MS)
        if (result.exitCode == 0) saveConnection(host, port)
        return result.exitCode == 0
    }

    fun shell(host: String, port: Int, command: String, timeoutMs: Long): ShellResult {
        ensureIdentity()
        return runWithTimeout(timeoutMs) {
            Kadb.create(host, port).use { adb ->
                val change = adb.shell(command)
                ShellResult(change.exitCode, change.output, change.allOutput)
            }
        }
    }

    /** One-time self-grant from the app's own shell after a first live connection. Approved 2026-11-11. */
    fun grantWriteSecureSettings(host: String, port: Int): Boolean {
        val result = runCatching {
            shell(host, port, "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", 10_000L)
        }.getOrNull() ?: return false
        return result.exitCode == 0
    }

    fun revokeWriteSecureSettings(host: String, port: Int): Boolean {
        val result = runCatching {
            shell(host, port, "pm revoke ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", 10_000L)
        }.getOrNull() ?: return false
        return result.exitCode == 0
    }

    fun hasWriteSecureSettingsGrant(): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED

    /** Reads the 7-day expiry setting. Returns null when unreadable (never crashes on it). */
    fun readAllowedConnectionTime(): Long? = runCatching {
        Settings.Global.getLong(context.contentResolver, "adb_allowed_connection_time", -1L)
            .takeIf { it >= 0 }
    }.getOrNull()

    /** Re-enable wireless debugging via the shell (only when the grant is held). */
    fun setWirelessDebugging(host: String, port: Int, enabled: Boolean): Boolean {
        if (!enabled) {
            return runCatching {
                shell(host, port, "settings put global adb_wifi_enabled 0", 10_000L).exitCode == 0
            }.getOrDefault(false)
        }
        return runCatching {
            shell(host, port, "settings put global adb_wifi_enabled 0", 10_000L)
            Thread.sleep(1500)
            shell(host, port, "settings put global adb_wifi_enabled 1", 10_000L).exitCode == 0
        }.getOrDefault(false)
    }

    /** Strong reset: delete identity + ports. The next ensureIdentity mints a NEW keypair. */
    fun forgetIdentity() {
        for (name in listOf(CERT_FILE, KEY_FILE, HOST_FILE, CONNECTION_FILE)) {
            runCatching { File(directory, name).delete() }
        }
    }

    fun savedHost(): String? {
        val file = File(directory, HOST_FILE)
        if (!file.isFile) return null
        return file.readText().trim().takeIf { it.isNotEmpty() }
    }

    fun savedPort(): Int? {
        val lines = File(directory, CONNECTION_FILE).takeIf { it.isFile }?.readLines() ?: return null
        val host = savedHost() ?: return null
        if (lines.size != 2 || lines[0] != host) return null
        return lines[1].toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun saveHost(host: String) {
        writeAtomically(File(directory, HOST_FILE), host.toByteArray())
    }

    private fun saveConnection(host: String, port: Int) {
        writeAtomically(File(directory, CONNECTION_FILE), "$host\n$port".toByteArray())
    }

    private fun writeAtomically(destination: File, contents: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeBytes(contents)
        check(temporary.renameTo(destination)) { "Failed to save ${destination.name}" }
    }

    private fun <T> runWithTimeout(timeoutMs: Long, block: Callable<T>): T {
        val future = executor.submit(block)
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        }
    }

    companion object {
        const val CERT_FILE = "adbpair-cert.pem"
        const val KEY_FILE = "adbpair-key.pem"
        const val HOST_FILE = "adbpair-host.txt"
        const val CONNECTION_FILE = "adbpair-connection.txt"
        const val LOOPBACK = "127.0.0.1"
    }
}
