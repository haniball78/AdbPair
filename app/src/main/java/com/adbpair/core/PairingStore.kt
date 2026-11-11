package com.adbpair.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the single screen shows. The service writes it; the UI only observes. */
data class UiState(
    val state: PairingState = PairingState.IDLE,
    val detail: String = "",
    val fingerprint: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val grantHeld: Boolean = false,
    val allowedConnectionTimeMs: Long? = null,
    val lastError: String = "",
    val checklistDone: Boolean = false,
)

/**
 * The shared state store. Owned by the Application process; the foreground
 * service writes, MainActivity observes. Persistence is SharedPreferences
 * (small, synchronous, no database for a pairing-only app).
 */
object PairingStore {

    private const val PREFS = "adbpair"
    private const val KEY_CHECKLIST = "checklist_done"
    private const val KEY_GRANT_TOGGLE = "grant_toggle_on"
    private const val KEY_IDENTITY_VERIFIED = "identity_verified"
    private const val KEY_LAST_PAIR_MS = "last_pair_ms"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    fun isChecklistDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHECKLIST, false)

    fun setChecklistDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CHECKLIST, done).apply()
        update { it.copy(checklistDone = done) }
    }

    /** The user-visible self-grant toggle. Default ON once granted; the user can turn it off. */
    fun isGrantToggleOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GRANT_TOGGLE, true)

    fun setGrantToggle(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GRANT_TOGGLE, on).apply()
    }

    /** Whether the current identity was ever verified (a live connect succeeded).
     * Set in afterConnected, cleared on forget. A previously-verified key
     * failing AUTH is a revoke, not a landing (G2). */
    fun isIdentityVerified(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IDENTITY_VERIFIED, false)

    fun setIdentityVerified(context: Context, verified: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_IDENTITY_VERIFIED, verified).apply()
    }

    /** When the last pair succeeded (ms since epoch), or null if none / forgotten (G7). */
    fun getLastPairMs(context: Context): Long? {
        val ms = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_PAIR_MS, 0L)
        return ms.takeIf { it > 0 }
    }

    fun setLastPairMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_PAIR_MS, ms).apply()
    }
}
