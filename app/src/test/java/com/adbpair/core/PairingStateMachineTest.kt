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
    fun `patient retry exhausts at fourteen`() {
        assertEquals(false, PairingStateMachine.patientRetryExhausted(13))
        assertEquals(true, PairingStateMachine.patientRetryExhausted(14))
        assertEquals(true, PairingStateMachine.patientRetryExhausted(15))
    }
}
