package com.adbpair.core

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortDiscoveryTest {

    @Test
    fun `own addresses always include loopback`() {
        assertTrue(InetAddress.getLoopbackAddress() in ownAddresses())
    }

    @Test
    fun `own service address accepts loopback and device IPs, rejects foreign ones`() {
        // The device's adbd advertises its active interface address (on this
        // phone the Tailscale IP), never loopback — a loopback-only match
        // could never see our own _adb-tls-connect service.
        val own = setOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("100.108.219.22"),
            InetAddress.getByName("192.168.1.50"),
        )
        assertTrue(isOwnServiceAddress(InetAddress.getByName("127.0.0.1"), own))
        assertTrue(isOwnServiceAddress(InetAddress.getByName("100.108.219.22"), own))
        assertTrue(isOwnServiceAddress(InetAddress.getByName("192.168.1.50"), own))
        assertFalse(isOwnServiceAddress(InetAddress.getByName("192.0.2.1"), own))
        assertFalse(isOwnServiceAddress(InetAddress.getByName("8.8.8.8"), own))
    }
}
