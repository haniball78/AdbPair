package com.adbpair.core

/** States of the single pairing state machine. Owned by the foreground service. */
enum class PairingState {
    IDLE,
    RESUMING,
    CONNECTED,
    STALE,
    PARKED_AUTH,
    PAIRING,
    PAIRING_WAIT_KEY,
}

/** Events that drive the state machine. */
enum class PairingEvent {
    RESUME_REQUESTED,
    CONNECT_VERIFIED,
    AUTH_FAILED_FRESH,
    AUTH_FAILED_PARKED,
    UNREACHABLE,
    TIMEOUT,
    PAIR_REQUESTED,
    PAIR_SUCCEEDED,
    PAIR_FAILED,
    PAIR_TIMEOUT,
    KEY_LANDED,
    TOGGLE_OFF,
    REVOKED_OR_EXPIRED,
    FORGET_REQUESTED,
    DIALOG_DEAD,
}

/**
 * The one pairing state machine. Pure Kotlin, no Android types: the UI observes,
 * the foreground service owns it, and no composable ever owns a recovery loop.
 *
 * Rules baked into the table:
 * - "Paired" means a VERIFIED live connection, never file existence.
 * - AUTH right after a pair parks the machine (key-landing window) — it never
 *   re-pairs and never re-mints. Re-minting is a user-confirmed FORGET, not a retry.
 * - Patient retry counts only while PARKED_AUTH on the same port.
 */
class PairingStateMachine {

    var state: PairingState = PairingState.IDLE
        private set

    /** Consecutive AUTH failures while parked on the same port. Reset on any success or port change. */
    var patientAttempts: Int = 0
        private set

    fun transition(event: PairingEvent): PairingState {
        state = when (state) {
            PairingState.IDLE -> when (event) {
                PairingEvent.RESUME_REQUESTED -> PairingState.RESUMING
                PairingEvent.PAIR_REQUESTED -> PairingState.PAIRING
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.RESUMING -> when (event) {
                PairingEvent.CONNECT_VERIFIED -> {
                    patientAttempts = 0
                    PairingState.CONNECTED
                }
                PairingEvent.AUTH_FAILED_FRESH -> {
                    patientAttempts = 0
                    PairingState.PARKED_AUTH
                }
                PairingEvent.AUTH_FAILED_PARKED -> {
                    patientAttempts += 1
                    PairingState.PARKED_AUTH
                }
                PairingEvent.TOGGLE_OFF, PairingEvent.REVOKED_OR_EXPIRED -> PairingState.STALE
                PairingEvent.UNREACHABLE, PairingEvent.TIMEOUT -> PairingState.STALE
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.CONNECTED -> when (event) {
                PairingEvent.RESUME_REQUESTED -> PairingState.RESUMING
                PairingEvent.TOGGLE_OFF, PairingEvent.REVOKED_OR_EXPIRED,
                PairingEvent.AUTH_FAILED_FRESH, PairingEvent.UNREACHABLE,
                PairingEvent.TIMEOUT -> PairingState.RESUMING
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.STALE -> when (event) {
                PairingEvent.RESUME_REQUESTED -> PairingState.RESUMING
                PairingEvent.PAIR_REQUESTED -> PairingState.PAIRING
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.PARKED_AUTH -> when (event) {
                PairingEvent.CONNECT_VERIFIED, PairingEvent.KEY_LANDED -> {
                    patientAttempts = 0
                    PairingState.CONNECTED
                }
                PairingEvent.AUTH_FAILED_PARKED -> {
                    patientAttempts += 1
                    PairingState.PARKED_AUTH
                }
                PairingEvent.TOGGLE_OFF, PairingEvent.REVOKED_OR_EXPIRED -> PairingState.STALE
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.PAIRING -> when (event) {
                PairingEvent.PAIR_SUCCEEDED -> {
                    // A new pair means a new key-landing window: the post-pair
                    // retry gets a fresh budget. (Was: a pre-pair exhaustion
                    // left the counter at 14, so the retry broke at once and
                    // a fresh pair could never connect — device-proven 2026-11-11.)
                    patientAttempts = 0
                    PairingState.PAIRING_WAIT_KEY
                }
                PairingEvent.PAIR_FAILED, PairingEvent.PAIR_TIMEOUT,
                PairingEvent.DIALOG_DEAD -> PairingState.IDLE
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
            PairingState.PAIRING_WAIT_KEY -> when (event) {
                PairingEvent.CONNECT_VERIFIED, PairingEvent.KEY_LANDED -> {
                    patientAttempts = 0
                    PairingState.CONNECTED
                }
                PairingEvent.AUTH_FAILED_PARKED -> {
                    patientAttempts += 1
                    PairingState.PAIRING_WAIT_KEY
                }
                PairingEvent.TOGGLE_OFF, PairingEvent.REVOKED_OR_EXPIRED -> PairingState.STALE
                PairingEvent.FORGET_REQUESTED -> PairingState.IDLE
                else -> state
            }
        }
        return state
    }

    fun resetPatientAttempts() {
        patientAttempts = 0
    }

    companion object {
        const val MAX_PATIENT_RETRIES = 14
        const val PATIENT_RETRY_DELAY_MS = 30_000L
        const val PATIENT_RETRY_WINDOW_MS = 420_000L
        const val PAIR_TIMEOUT_MS = 60_000L
        const val RESUME_BUDGET_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 3_000L

        fun patientRetryExhausted(attempts: Int): Boolean = attempts >= MAX_PATIENT_RETRIES
    }
}
