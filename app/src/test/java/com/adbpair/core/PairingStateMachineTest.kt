package com.adbpair.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStateMachineTest {

    @Test
    fun `resume then verified connect reaches CONNECTED`() {
        val machine = PairingStateMachine()
        assertEquals(PairingState.RESUMING, machine.transition(PairingEvent.RESUME_REQUESTED))
        assertEquals(PairingState.CONNECTED, machine.transition(PairingEvent.CONNECT_VERIFIED))
    }

    @Test
    fun `fresh AUTH parks the machine and counts patient attempts`() {
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.RESUME_REQUESTED)
        assertEquals(PairingState.PARKED_AUTH, machine.transition(PairingEvent.AUTH_FAILED_FRESH))
        assertEquals(0, machine.patientAttempts)
        machine.transition(PairingEvent.AUTH_FAILED_PARKED)
        assertEquals(1, machine.patientAttempts)
        assertEquals(PairingState.PARKED_AUTH, machine.state)
    }

    @Test
    fun `key landing after a pair reaches CONNECTED and resets attempts`() {
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.PAIR_REQUESTED)
        assertEquals(PairingState.PAIRING_WAIT_KEY, machine.transition(PairingEvent.PAIR_SUCCEEDED))
        machine.transition(PairingEvent.AUTH_FAILED_PARKED)
        assertEquals(1, machine.patientAttempts)
        assertEquals(PairingState.CONNECTED, machine.transition(PairingEvent.KEY_LANDED))
        assertEquals(0, machine.patientAttempts)
    }

    @Test
    fun `toggle off and revoked both land in STALE, never in CONNECTED`() {
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.RESUME_REQUESTED)
        assertEquals(PairingState.STALE, machine.transition(PairingEvent.TOGGLE_OFF))
        machine.transition(PairingEvent.RESUME_REQUESTED)
        assertEquals(PairingState.STALE, machine.transition(PairingEvent.REVOKED_OR_EXPIRED))
    }

    @Test
    fun `forget returns to IDLE from any state`() {
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.RESUME_REQUESTED)
        machine.transition(PairingEvent.AUTH_FAILED_FRESH)
        assertEquals(PairingState.IDLE, machine.transition(PairingEvent.FORGET_REQUESTED))
    }

    @Test
    fun `pair timeout and dialog death return to IDLE`() {
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.PAIR_REQUESTED)
        assertEquals(PairingState.IDLE, machine.transition(PairingEvent.PAIR_TIMEOUT))
        machine.transition(PairingEvent.PAIR_REQUESTED)
        assertEquals(PairingState.IDLE, machine.transition(PairingEvent.DIALOG_DEAD))
    }

    @Test
    fun `a successful pair resets the patient budget after exhaustion`() {
        // Device-proven 2026-11-11 (241c957): a fresh install's pre-pair
        // retries exhausted the counter (14), so after a successful pair the
        // post-pair retry broke at once and the pair could never connect.
        // A new pair is a new key-landing window: fresh budget.
        val machine = PairingStateMachine()
        machine.transition(PairingEvent.RESUME_REQUESTED)
        machine.transition(PairingEvent.AUTH_FAILED_FRESH)
        repeat(14) { machine.transition(PairingEvent.AUTH_FAILED_PARKED) }
        assertEquals(14, machine.patientAttempts)
        assertEquals(PairingState.STALE, machine.transition(PairingEvent.REVOKED_OR_EXPIRED))
        assertEquals(PairingState.PAIRING, machine.transition(PairingEvent.PAIR_REQUESTED))
        assertEquals(PairingState.PAIRING_WAIT_KEY, machine.transition(PairingEvent.PAIR_SUCCEEDED))
        assertEquals(0, machine.patientAttempts)
    }

    @Test
    fun `key-landing retry is fast for the first minute, then normal`() {
        // PAIRING_WAIT_KEY: 5 s for the first 12 attempts, then 30 s.
        assertEquals(5_000L, PairingStateMachine.patientRetryDelayMs(PairingState.PAIRING_WAIT_KEY, 0))
        assertEquals(5_000L, PairingStateMachine.patientRetryDelayMs(PairingState.PAIRING_WAIT_KEY, 11))
        assertEquals(30_000L, PairingStateMachine.patientRetryDelayMs(PairingState.PAIRING_WAIT_KEY, 12))
        // PARKED_AUTH (an old identity failing): always the normal 30 s.
        assertEquals(30_000L, PairingStateMachine.patientRetryDelayMs(PairingState.PARKED_AUTH, 0))
        assertEquals(30_000L, PairingStateMachine.patientRetryDelayMs(PairingState.CONNECTED, 0))
    }

    @Test
    fun `a fresh pair stuck 30s auto-freshes, nothing else does`() {
        // PAIRING_WAIT_KEY + >30 s since the pair: stuck, auto-fresh.
        assertEquals(true, PairingStateMachine.shouldAutoFreshKey(PairingState.PAIRING_WAIT_KEY, 30_001L))
        assertEquals(true, PairingStateMachine.shouldAutoFreshKey(PairingState.PAIRING_WAIT_KEY, 120_000L))
        // PAIRING_WAIT_KEY + <=30 s: still within the landing window.
        assertEquals(false, PairingStateMachine.shouldAutoFreshKey(PairingState.PAIRING_WAIT_KEY, 30_000L))
        assertEquals(false, PairingStateMachine.shouldAutoFreshKey(PairingState.PAIRING_WAIT_KEY, 5_000L))
        // No pair at all (null elapsed): never auto-fresh.
        assertEquals(false, PairingStateMachine.shouldAutoFreshKey(PairingState.PAIRING_WAIT_KEY, null))
        // PARKED_AUTH (an old identity failing) and CONNECTED: never
        // auto-fresh, however long it has been.
        assertEquals(false, PairingStateMachine.shouldAutoFreshKey(PairingState.PARKED_AUTH, 120_000L))
        assertEquals(false, PairingStateMachine.shouldAutoFreshKey(PairingState.CONNECTED, 120_000L))
    }

    @Test
    fun `patient retry exhausts at fourteen`() {
        assertEquals(false, PairingStateMachine.patientRetryExhausted(13))
        assertEquals(true, PairingStateMachine.patientRetryExhausted(14))
        assertEquals(true, PairingStateMachine.patientRetryExhausted(15))
    }
}
