package com.adbpair.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureClassifierTest {

    private fun context(
        minutesSincePair: Long? = null,
        patientAttempts: Int = 0,
        wirelessOn: Boolean = true,
        keyPresent: Boolean = true,
        keyPreviouslyVerified: Boolean = false,
    ) = FailureContext(minutesSincePair, patientAttempts, wirelessOn, keyPresent, keyPreviouslyVerified)

    @Test
    fun `AUTH right after a pair is KEY_LANDING never re-mint`() {
        assertEquals(
            FailureKind.KEY_LANDING,
            FailureClassifier.classify("AdbPairAuthException", context(minutesSincePair = 2)),
        )
    }

    @Test
    fun `AUTH on a previously verified key is EXPIRED after one retry`() {
        assertEquals(
            FailureKind.EXPIRED,
            FailureClassifier.classify(
                "AdbPairAuthException",
                context(minutesSincePair = 60, patientAttempts = 1, keyPreviouslyVerified = true),
            ),
        )
    }

    @Test
    fun `AUTH on a never verified key is plain AUTH retry`() {
        assertEquals(
            FailureKind.AUTH,
            FailureClassifier.classify(
                "AdbPairAuthException",
                context(minutesSincePair = 60, patientAttempts = 1, keyPreviouslyVerified = false),
            ),
        )
    }

    @Test
    fun `AUTH after the patient window is EXPIRED`() {
        assertEquals(
            FailureKind.EXPIRED,
            FailureClassifier.classify(
                "AdbPairAuthException",
                context(minutesSincePair = 60, patientAttempts = 14),
            ),
        )
    }

    @Test
    fun `AUTH otherwise is plain AUTH retry`() {
        assertEquals(
            FailureKind.AUTH,
            FailureClassifier.classify("AdbPairAuthException", context(minutesSincePair = 60)),
        )
    }

    @Test
    fun `wireless off wins over any error name`() {
        assertEquals(
            FailureKind.TOGGLE_OFF,
            FailureClassifier.classify("AdbPairAuthException", context(wirelessOn = false)),
        )
    }

    @Test
    fun `timeout and unreachable and TLS classify by name`() {
        assertEquals(FailureKind.TIMEOUT, FailureClassifier.classify("SocketTimeoutException", context()))
        assertEquals(FailureKind.TIMEOUT, FailureClassifier.classify("TimeoutException", context()))
        assertEquals(FailureKind.UNREACHABLE, FailureClassifier.classify("ConnectException", context()))
        assertEquals(FailureKind.UNREACHABLE, FailureClassifier.classify("UnknownHostException", context()))
        assertEquals(FailureKind.TLS, FailureClassifier.classify("SSLHandshakeException", context()))
    }

    @Test
    fun `unknown error is UNKNOWN`() {
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.classify("RuntimeException", context()))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.classify(null, context()))
    }
}
