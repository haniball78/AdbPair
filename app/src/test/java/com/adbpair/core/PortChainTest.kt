package com.adbpair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortChainTest {

    private class FakeSource(
        private val live: Int? = null,
        private val discovered: Int? = null,
    ) : LivePortSource {
        override fun livePort(): Int? = live
        override fun discover(host: String, timeoutSeconds: Long): Int? = discovered
    }

    @Test
    fun `saved port wins`() {
        val chain = PortChain(FakeSource(live = 40001, discovered = 40002))
        assertEquals(PortResolution(37711, PortSourceName.SAVED), chain.resolve(37711, "127.0.0.1"))
    }

    @Test
    fun `live property is second`() {
        val chain = PortChain(FakeSource(live = 40001, discovered = 40002))
        assertEquals(PortResolution(40001, PortSourceName.LIVE_PROPERTY), chain.resolve(null, "127.0.0.1"))
    }

    @Test
    fun `mDNS is last`() {
        val chain = PortChain(FakeSource(live = null, discovered = 40002))
        assertEquals(PortResolution(40002, PortSourceName.MDNS), chain.resolve(null, "127.0.0.1"))
    }

    @Test
    fun `nothing anywhere resolves to null`() {
        val chain = PortChain(FakeSource())
        assertNull(chain.resolve(null, "127.0.0.1"))
    }

    @Test
    fun `invalid saved ports are skipped, not returned`() {
        val chain = PortChain(FakeSource(live = 40001))
        assertEquals(PortResolution(40001, PortSourceName.LIVE_PROPERTY), chain.resolve(0, "127.0.0.1"))
        assertEquals(PortResolution(40001, PortSourceName.LIVE_PROPERTY), chain.resolve(70_000, "127.0.0.1"))
    }

    @Test
    fun `a live port equal to the saved port does not shadow it`() {
        // Saved is tried first and returned; the chain never re-reports the same port twice.
        val chain = PortChain(FakeSource(live = 37711, discovered = 40002))
        assertEquals(PortResolution(37711, PortSourceName.SAVED), chain.resolve(37711, "127.0.0.1"))
    }
}
