package com.adbpair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbIdentityTest {

    @Test
    fun `two mints produce different identities`() {
        val first = AdbIdentity.generateFreshIdentity(System.currentTimeMillis() + 3_650L * 24 * 60 * 60 * 1_000)
        val second = AdbIdentity.generateFreshIdentity(System.currentTimeMillis() + 3_650L * 24 * 60 * 60 * 1_000)
        assertFalse(first.first.contentEquals(second.first))
        assertFalse(first.second.contentEquals(second.second))
    }

    @Test
    fun `minted identity is PEM encoded`() {
        val (cert, key) = AdbIdentity.generateFreshIdentity(System.currentTimeMillis() + 3_650L * 24 * 60 * 60 * 1_000)
        val certText = cert.toString(Charsets.UTF_8)
        val keyText = key.toString(Charsets.UTF_8)
        assertTrue(certText.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(certText.trimEnd().endsWith("-----END CERTIFICATE-----"))
        assertTrue(keyText.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue(keyText.trimEnd().endsWith("-----END PRIVATE KEY-----"))
        assertEquals(1, certText.lines().filter { it.length > 64 && !it.startsWith("-----") }.size)
    }
}
