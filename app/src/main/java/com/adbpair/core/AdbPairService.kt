package com.adbpair.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.adbpair.R
import com.adbpair.ui.MainActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The foreground service that OWNS the pairing state machine. The UI observes
 * PairingStore; it never owns a loop. All recovery loops live here and run
 * with no UI open.
 *
 * Foreground type connectedDevice: the correct type for a pairing daemon, and
 * exempt from the background-start restriction and the 6 h/24 h cap.
 */
class AdbPairService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: AdbClient
    private lateinit var log: DiagnosticsLog
    private val machine = PairingStateMachine()
    private var pairingJob: Job? = null
    private var lastPairMs: Long? = null
    private var lastNetworkAttemptMs: Long = 0
    private var pairingPort: Int? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        client = AdbClient(applicationContext)
        log = DiagnosticsLog(File(applicationContext.filesDir, "logs"))
        createChannel()
        startForeground(FOREGROUND_ID, stateNotification(getString(R.string.svc_starting)))
        registerNetworkCallback()
        PairingStore.update {
            it.copy(
                checklistDone = PairingStore.isChecklistDone(applicationContext),
                grantHeld = client.hasWriteSecureSettingsGrant(),
            )
        }
        log.log("service", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> scope.launch { resume() }
            ACTION_PAIR -> scope.launch { startPairingEpisode() }
            ACTION_SUBMIT_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                scope.launch { submitCode(code, port) }
            }
            ACTION_FORGET -> scope.launch { forget() }
            ACTION_SET_GRANT_TOGGLE -> {
                val on = intent.getBooleanExtra(EXTRA_GRANT_ON, true)
                PairingStore.setGrantToggle(applicationContext, on)
                if (!on && PairingStore.state.value.state == PairingState.CONNECTED) {
                    scope.launch { revokeGrantIfConnected() }
                }
            }
        }
        return START_STICKY
    }

    /** Revoke the self-grant over the live connection (the visible revoke path). */
    private suspend fun revokeGrantIfConnected() {
        val host = PairingStore.state.value.host ?: AdbClient.LOOPBACK
        val port = PairingStore.state.value.port ?: return
        val revoked = runCatching { client.revokeWriteSecureSettings(host, port) }.getOrDefault(false)
        log.log("grant", if (revoked) "revoked" else "revoke-failed")
        PairingStore.update { it.copy(grantHeld = client.hasWriteSecureSettingsGrant()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Fast resume path (5–8 s budget) ────────────────────────────────

    private suspend fun resume() {
        if (machine.state == PairingState.RESUMING) return
        machine.transition(PairingEvent.RESUME_REQUESTED)
        setUi(PairingState.RESUMING, getString(R.string.state_resuming))
        log.log("resume", "started")
        val host = client.savedHost() ?: AdbClient.LOOPBACK
        val resolution = PortChain(AndroidPortSource(applicationContext))
            .resolve(client.savedPort(), host)
        if (resolution == null) {
            log.log("resume", "no-port")
            machine.transition(PairingEvent.UNREACHABLE)
            setUi(PairingState.STALE, getString(R.string.err_no_port))
            return
        }
        log.log(
            "resume", "port",
            mapOf("port" to resolution.port.toString(), "source" to resolution.source.name),
        )
        attemptConnect(host, resolution.port)
    }

    /** One connect attempt + classification. Returns true when CONNECTED. */
    private suspend fun attemptConnect(host: String, port: Int): Boolean {
        val verified = runCatching { client.connectAndProbe(host, port) }
        val errorName = if (verified.isSuccess) null else errorNameOf(verified.exceptionOrNull())
        if (verified.getOrDefault(false)) {
            machine.transition(PairingEvent.CONNECT_VERIFIED)
            val fingerprint = client.fingerprint()
            log.log(
                "connect", "verified",
                mapOf("port" to port.toString()) +
                    (fingerprint?.let { mapOf("identity" to it) } ?: emptyMap()),
            )
            afterConnected(host, port)
            setUi(PairingState.CONNECTED, getString(R.string.state_connected), fingerprint, host, port)
            return true
        }
        val wirelessOn = isWirelessOn()
        val kind = FailureClassifier.classify(
            errorName,
            FailureContext(
                minutesSincePair = lastPairMs?.let { (System.currentTimeMillis() - it) / 60_000L },
                patientAttempts = machine.patientAttempts,
                wirelessOn = wirelessOn,
                keyPresent = client.fingerprint() != null,
            ),
        )
        log.log(
            "connect", "failed",
            mapOf("kind" to kind.name, "error" to (errorName ?: "unknown"), "port" to port.toString()),
        )
        when (kind) {
            FailureKind.KEY_LANDING, FailureKind.AUTH -> {
                machine.transition(
                    if (machine.state == PairingState.PAIRING_WAIT_KEY) PairingEvent.AUTH_FAILED_PARKED
                    else PairingEvent.AUTH_FAILED_FRESH,
                )
                setUi(
                    PairingState.PARKED_AUTH,
                    getString(R.string.state_waiting_key),
                )
                patientRetry(host, port)
            }
            FailureKind.TOGGLE_OFF -> {
                machine.transition(PairingEvent.TOGGLE_OFF)
                setUi(PairingState.STALE, getString(R.string.err_toggle_off))
            }
            FailureKind.EXPIRED -> {
                machine.transition(PairingEvent.REVOKED_OR_EXPIRED)
                setUi(PairingState.STALE, getString(R.string.err_expired))
            }
            else -> {
                machine.transition(PairingEvent.UNREACHABLE)
                setUi(PairingState.STALE, getString(R.string.err_unreachable))
            }
        }
        return false
    }

    /** After a verified connect: self-grant (approved) + read the 7-day expiry setting. */
    private suspend fun afterConnected(host: String, port: Int) {
        val toggleOn = PairingStore.isGrantToggleOn(applicationContext)
        if (toggleOn && !client.hasWriteSecureSettingsGrant()) {
            val granted = runCatching { client.grantWriteSecureSettings(host, port) }.getOrDefault(false)
            log.log("grant", if (granted) "granted" else "grant-failed")
        }
        val allowedMs = client.readAllowedConnectionTime()
        PairingStore.update {
            it.copy(grantHeld = client.hasWriteSecureSettingsGrant(), allowedConnectionTimeMs = allowedMs)
        }
        log.log(
            "connect", "post",
            mapOf(
                "grantHeld" to client.hasWriteSecureSettingsGrant().toString(),
                "allowedMs" to (allowedMs?.toString() ?: "unknown"),
            ),
        )
    }

    /** Patient retry: 30 s × 14 while parked on the same port. Never re-pairs, never re-mints. */
    private suspend fun patientRetry(host: String, port: Int) {
        var attempts = 0
        while (machine.state == PairingState.PARKED_AUTH || machine.state == PairingState.PAIRING_WAIT_KEY) {
            if (PairingStateMachine.patientRetryExhausted(machine.patientAttempts)) break
            delay(PairingStateMachine.PATIENT_RETRY_DELAY_MS)
            attempts += 1
            log.log("retry", "attempt", mapOf("n" to attempts.toString(), "port" to port.toString()))
            val resolution = PortChain(AndroidPortSource(applicationContext))
                .resolve(client.savedPort(), host)
            val tryPort = resolution?.port ?: port
            if (runCatching { client.connectAndProbe(host, tryPort) }.getOrDefault(false)) {
                machine.transition(PairingEvent.CONNECT_VERIFIED)
                afterConnected(host, tryPort)
                setUi(PairingState.CONNECTED, getString(R.string.state_connected), client.fingerprint(), host, tryPort)
                return
            }
            machine.transition(PairingEvent.AUTH_FAILED_PARKED)
        }
        if (machine.state == PairingState.PARKED_AUTH || machine.state == PairingState.PAIRING_WAIT_KEY) {
            machine.transition(PairingEvent.REVOKED_OR_EXPIRED)
            setUi(PairingState.STALE, getString(R.string.err_expired))
        }
    }

    // ── Fresh-pair path ────────────────────────────────────────────────

    private suspend fun startPairingEpisode() {
        if (machine.state == PairingState.PAIRING || machine.state == PairingState.PAIRING_WAIT_KEY) return
        machine.transition(PairingEvent.PAIR_REQUESTED)
        setUi(PairingState.PAIRING, getString(R.string.state_pairing))
        log.log("pair", "episode-started")
        // Discover the pairing port in the background; the panel shows the
        // manual port field until discovery resolves (or if it never does).
        pairingJob?.cancel()
        pairingJob = scope.launch {
            pairingPort = discoverPairingPort()
            log.log("pair", "port", mapOf("port" to (pairingPort?.toString() ?: "none")))
            PairingStore.update { it.copy(port = pairingPort) }
            postPairingNotification()
        }
        postPairingNotification()
    }

    private suspend fun submitCode(code: String, port: Int) {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            setUi(machine.state, getString(R.string.err_code_format))
            return
        }
        if (port !in 1..65535) {
            setUi(machine.state, getString(R.string.err_port_format))
            return
        }
        val host = AdbClient.LOOPBACK
        log.log("pair", "submitted", mapOf("port" to port.toString()))
        val paired = runCatching { client.pair(host, port, code) }
        if (paired.isFailure) {
            val name = errorNameOf(paired.exceptionOrNull())
            log.log("pair", "failed", mapOf("error" to (name ?: "unknown")))
            machine.transition(PairingEvent.PAIR_FAILED)
            setUi(PairingState.IDLE, getString(R.string.err_pair_failed))
            return
        }
        lastPairMs = System.currentTimeMillis()
        val fingerprint = client.fingerprint()
        machine.transition(PairingEvent.PAIR_SUCCEEDED)
        log.log(
            "pair", "succeeded",
            mapOf("port" to port.toString()) +
                (fingerprint?.let { mapOf("identity" to it) } ?: emptyMap()),
        )
        setUi(PairingState.PAIRING_WAIT_KEY, getString(R.string.state_waiting_key), fingerprint)
        // Poll the connect port with backoff: 2/5/10/20/30 s, then patient retry.
        for (waitMs in listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)) {
            delay(waitMs)
            if (machine.state != PairingState.PAIRING_WAIT_KEY) return
            val resolution = PortChain(AndroidPortSource(applicationContext))
                .resolve(client.savedPort(), host)
            val tryPort = resolution?.port ?: continue
            if (runCatching { client.connectAndProbe(host, tryPort) }.getOrDefault(false)) {
                machine.transition(PairingEvent.KEY_LANDED)
                afterConnected(host, tryPort)
                setUi(PairingState.CONNECTED, getString(R.string.state_connected), client.fingerprint(), host, tryPort)
                return
            }
            machine.transition(PairingEvent.AUTH_FAILED_PARKED)
        }
        patientRetry(host, PortChain(AndroidPortSource(applicationContext)).resolve(client.savedPort(), host)?.port ?: return)
    }

    /** NSD discovery of _adb-tls-pairing._tcp. Null when the dialog is not open. */
    private fun discoverPairingPort(): Int? {
        val manager = applicationContext.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val latch = java.util.concurrent.CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicInteger(-1)
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                found.set(serviceInfo.port)
                latch.countDown()
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
        }
        return try {
            manager.discoverServices("_adb-tls-pairing._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
            found.get().takeIf { it in 1..65535 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun forget() {
        machine.transition(PairingEvent.FORGET_REQUESTED)
        client.forgetIdentity()
        lastPairMs = null
        log.log("pair", "forgot-identity")
        setUi(PairingState.IDLE, getString(R.string.state_forgotten))
    }

    // ── Network change ────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastNetworkAttemptMs < 10_000L) return
                lastNetworkAttemptMs = now
                if (machine.state != PairingState.CONNECTED) {
                    log.log("network", "changed-resume")
                    scope.launch { resume() }
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun isWirelessOn(): Boolean = runCatching {
        android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    private fun errorNameOf(error: Throwable?): String? {
        var current = error
        while (current != null) {
            val name = current.javaClass.simpleName
            if (name.isNotEmpty()) return name
            current = current.cause
        }
        return null
    }

    private fun setUi(
        state: PairingState,
        detail: String,
        fingerprint: String? = null,
        host: String? = null,
        port: Int? = null,
    ) {
        PairingStore.update {
            it.copy(
                state = state,
                detail = detail,
                fingerprint = fingerprint ?: it.fingerprint,
                host = host ?: it.host,
                port = port ?: it.port,
            )
        }
        updateForegroundNotification()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun stateNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(FOREGROUND_ID, stateNotification(PairingStore.state.value.detail)) }
    }

    /** Pairing prompt notification. RemoteInput once the pairing port is known. */
    private fun postPairingNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_pair_title))
            .setContentText(getString(R.string.notif_pair_text))
            .setOnlyAlertOnce(true)
        val port = pairingPort
        if (port != null) {
            val remoteInput = RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.notif_code_hint))
                .build()
            val replyIntent = Intent(this, AdbPairService::class.java).apply {
                action = ACTION_SUBMIT_CODE
                putExtra(EXTRA_PORT, port)
            }
            val replyPending = PendingIntent.getService(
                this, 1, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val action = Notification.Action.Builder(
                null, getString(R.string.notif_code_action), replyPending,
            ).addRemoteInput(remoteInput).build()
            builder.addAction(action)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setContentIntent(openIntent)
        runCatching { manager.notify(PAIRING_NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        const val ACTION_RESUME = "com.adbpair.action.RESUME"
        const val ACTION_PAIR = "com.adbpair.action.PAIR"
        const val ACTION_SUBMIT_CODE = "com.adbpair.action.SUBMIT_CODE"
        const val ACTION_FORGET = "com.adbpair.action.FORGET"
        const val ACTION_SET_GRANT_TOGGLE = "com.adbpair.action.SET_GRANT_TOGGLE"
        const val EXTRA_CODE = "code"
        const val EXTRA_PORT = "port"
        const val EXTRA_GRANT_ON = "grant_on"
        const val KEY_CODE = "pairing_code"
        const val CHANNEL_ID = "adbpair"
        const val FOREGROUND_ID = 1
        const val PAIRING_NOTIFICATION_ID = 2

        fun resumeIntent(context: Context): Intent =
            Intent(context, AdbPairService::class.java).apply { action = ACTION_RESUME }

        fun pairIntent(context: Context): Intent =
            Intent(context, AdbPairService::class.java).apply { action = ACTION_PAIR }

        fun forgetIntent(context: Context): Intent =
            Intent(context, AdbPairService::class.java).apply { action = ACTION_FORGET }
    }
}
