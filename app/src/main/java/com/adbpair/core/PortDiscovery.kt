package com.adbpair.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where a resolved connect port came from. Every port value is logged with its source. */
enum class PortSourceName { SAVED, LIVE_PROPERTY, MDNS, MANUAL }

/** One resolved port plus its source. */
data class PortResolution(val port: Int, val source: PortSourceName)

/** The two live sources. Kept behind an interface so the chain is unit-testable. */
interface LivePortSource {
    fun livePort(): Int?
    fun discover(host: String, timeoutSeconds: Long): Int?
}

/**
 * Port resolution chain: saved → live property → mDNS. Each source is tried
 * only when the previous one failed (or returned the same stale port).
 * Never caches a port across attempts — the caller re-resolves every time.
 */
class PortChain(private val liveSource: LivePortSource) {

    fun resolve(savedPort: Int?, host: String, discoverTimeoutSeconds: Long = 12): PortResolution? {
        if (savedPort != null && savedPort in 1..65535) {
            return PortResolution(savedPort, PortSourceName.SAVED)
        }
        val live = liveSource.livePort()
        if (live != null && live != savedPort) return PortResolution(live, PortSourceName.LIVE_PROPERTY)
        val discovered = liveSource.discover(host, discoverTimeoutSeconds)
        if (discovered != null && discovered != savedPort && discovered != live) {
            return PortResolution(discovered, PortSourceName.MDNS)
        }
        return null
    }

    companion object {
        fun isValidPort(port: Int?): Boolean = port != null && port in 1..65535
    }
}

/** Android implementation of the live sources. */
class AndroidPortSource(private val context: Context) : LivePortSource {

    /** Live connect port from the hidden SystemProperties reflection (greylist: never relied on alone). */
    override fun livePort(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * mDNS discovery of _adb-tls-connect._tcp, resolving the first service whose
     * address matches the paired host. Ported from DroidDeck's findConnectPort
     * (same latch + resolve queue; NSD is flaky, so this is the LAST resort,
     * never the first).
     */
    override fun discover(host: String, timeoutSeconds: Long): Int? {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val paired = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val matches: (InetAddress) -> Boolean =
            if (paired.isLoopbackAddress) ::isLocalAddress else { address -> address.address.contentEquals(paired.address) }
        val resolvedPort = AtomicInteger(-1)
        val finished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val queue = ArrayDeque<NsdServiceInfo>()
        val seen = mutableSetOf<String>()
        var resolving = false
        val lock = Any()

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            val next = synchronized(lock) {
                if (finished.get() || resolving || queue.isEmpty()) null
                else queue.removeFirst().also { resolving = true }
            }
            if (next != null) {
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host
                        if (address != null && matches(address) && finished.compareAndSet(false, true)) {
                            resolvedPort.set(serviceInfo.port)
                            latch.countDown()
                        } else {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }
                    }
                }
                runCatching { manager.resolveService(next, listener) }.onFailure {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains("_adb-tls-connect._tcp") == true) {
                    synchronized(lock) {
                        if (seen.add(serviceInfo.serviceName)) queue.addLast(serviceInfo)
                    }
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) { latch.countDown() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
        }

        try {
            manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
        return resolvedPort.get().takeIf { it in 1..65535 }
    }

    companion object {
        /** Loopback matching: any local address counts (127.0.0.1, ::1, 0.0.0.0). */
        fun isLocalAddress(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.hostAddress == "127.0.0.1" || address.hostAddress == "::1"
    }
}
