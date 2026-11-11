package com.adbpair.core

/**
 * Classifies a pairing/connect failure into the ACTIONABLE kind. Pure Kotlin:
 * the classifier takes the exception's simple class name (never the exception
 * itself, so it stays free of the kadb dependency) plus the surrounding state.
 *
 * The classification is the fast step the whole design hinges on: the owner's
 * order ("if failed/stale, mint a new identity fast") and the research
 * ("re-pairing does not fix AUTH") are both right — for different failures.
 * KEY_LANDING waits; EXPIRED re-mints; everything else re-discovers or retries.
 */
enum class FailureKind {
    /** Pair just succeeded; AUTH is the key still landing in adb_keys. Wait + backoff. Never re-mint, never re-pair. */
    KEY_LANDING,
    /** AUTH, not a fresh pair, attempts remain. Retry the connect; do not re-pair. */
    AUTH,
    /** AUTH persists after the full patient-retry window, or the key is past its 7-day window / revoked. Fresh identity + fresh pair, user-confirmed. */
    EXPIRED,
    /** Wireless debugging is off (adb_wifi_enabled = 0, readable with no permission). Guided re-enable; same identity. */
    TOGGLE_OFF,
    /** Nothing answers on any candidate port. Re-discover; surface a manual port entry if the toggle is on. */
    UNREACHABLE,
    /** A call exceeded its budget. Retry with the next candidate; the call is abandoned, not retried blindly. */
    TIMEOUT,
    /** TLS handshake failure that is not an AUTH failure. Re-discover and retry; do not re-pair. */
    TLS,
    UNKNOWN,
}

data class FailureContext(
    /** Minutes since the last successful pair, or null if there was no recent pair. */
    val minutesSincePair: Long?,
    /** Consecutive AUTH failures while parked on the same port. */
    val patientAttempts: Int,
    /** Whether wireless debugging is currently on. */
    val wirelessOn: Boolean,
    /** Whether an identity (cert + key files) exists. */
    val keyPresent: Boolean,
    /** Whether this identity was ever verified (a live connect succeeded). */
    val keyPreviouslyVerified: Boolean = false,
)

object FailureClassifier {

    fun classify(errorSimpleName: String?, context: FailureContext): FailureKind {
        if (!context.wirelessOn) return FailureKind.TOGGLE_OFF
        val name = errorSimpleName.orEmpty()
        val isAuth = "Auth" in name
        val isTimeout = "Timeout" in name || "SocketTimeout" in name
        val isTls = "SSL" in name || "Tls" in name || "TLS" in name
        val isUnreachable = "ConnectException" in name || "UnknownHost" in name ||
            "NoRouteToHost" in name
        return when {
            isAuth -> {
                val recentPair = context.minutesSincePair != null && context.minutesSincePair < 10
                when {
                    recentPair -> FailureKind.KEY_LANDING
                    // A previously-verified key failing AUTH is a revoke, not
                    // a landing: one retry rules out a transient, then it is
                    // EXPIRED (G2 — cuts revoke detection from 7 min to ~30 s).
                    context.keyPreviouslyVerified && context.patientAttempts >= 1 -> FailureKind.EXPIRED
                    PairingStateMachine.patientRetryExhausted(context.patientAttempts) -> FailureKind.EXPIRED
                    else -> FailureKind.AUTH
                }
            }
            isTimeout -> FailureKind.TIMEOUT
            isTls -> FailureKind.TLS
            isUnreachable -> FailureKind.UNREACHABLE
            else -> FailureKind.UNKNOWN
        }
    }
}
