package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MdnsServiceTest — Unit tests for MdnsService.
 *
 * WHY: MdnsService is the gateway between PhairPlay and macOS discovery.
 * If the mDNS registration is wrong (wrong service type, missing TXT records),
 * macOS will never show PhairPlay in the AirPlay menu. These tests verify that
 * the registration is correct without actually using the network.
 *
 * HOW: We mock the Android [NsdManager] and [Context] to avoid needing a real
 * Android device. MockK is used to create mock objects and verify interactions.
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsServiceTest {

    // The class under test
    private lateinit var mdnsService: MdnsService

    // Mock objects — these simulate Android system services without real hardware
    private lateinit var mockContext: Context
    private lateinit var mockNsdManager: NsdManager
    private lateinit var mockWifiManager: WifiManager
    private lateinit var mockMulticastLock: WifiManager.MulticastLock

    @Before
    fun setup() {
        // Create mocks for Android dependencies
        mockContext = mockk(relaxed = true)
        mockNsdManager = mockk(relaxed = true)
        mockWifiManager = mockk(relaxed = true)
        mockMulticastLock = mockk(relaxed = true)

        // Tell the mock context to return our mock NsdManager
        every { mockContext.getSystemService(Context.NSD_SERVICE) } returns mockNsdManager
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(Context.WIFI_SERVICE) } returns mockWifiManager
        every { mockWifiManager.createMulticastLock(any()) } returns mockMulticastLock
        every { mockMulticastLock.isHeld } returns true

        mdnsService = MdnsService(mockContext, registrationTimeoutMs = 0L)
    }

    private fun captureRegistrationListeners(): MutableList<NsdManager.RegistrationListener> {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        } answers {
            listeners += thirdArg<NsdManager.RegistrationListener>()
        }
        return listeners
    }

    private fun notifyRegistered(
        listener: NsdManager.RegistrationListener,
        serviceName: String
    ) {
        val serviceInfo = mockk<NsdServiceInfo>(relaxed = true)
        every { serviceInfo.serviceName } returns serviceName
        listener.onServiceRegistered(serviceInfo)
    }

    /**
     * Test: When start() is called, MdnsService registers exactly 2 mDNS services.
     *
     * WHY: RAOP is submitted first and AirPlay second so the onn NSD daemon completes the
     * picker-visible AirPlay request when it drops the first back-to-back callback.
     */
    @Test
    fun `start registers two mDNS services`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        } answers {
            listeners += thirdArg<NsdManager.RegistrationListener>()
        }

        mdnsService.start()

        // Both requests are deliberately submitted back-to-back: RAOP first, AirPlay second.
        assertEquals(2, listeners.size)
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: Calling start() twice does not register services twice.
     *
     * WHY: If start() is accidentally called twice, we'd have duplicate mDNS
     * registrations, which could cause conflicts. The idempotency check must work.
     */
    @Test
    fun `start is idempotent when called twice`() {
        val listeners = captureRegistrationListeners()

        mdnsService.start()
        mdnsService.start()  // Second call should be ignored

        assertEquals(2, listeners.size)
        mdnsService.start()

        // Still one RAOP + AirPlay lifecycle, not duplicate registrations.
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: When stop() is called after start(), both services are unregistered.
     *
     * WHY: When the app closes, mDNS services must be unregistered so they
     * disappear from the macOS AirPlay menu. Failing to unregister means the
     * device stays in the menu even when PhairPlay is not running.
     */
    @Test
    fun `stop unregisters services after start`() {
        val listeners = captureRegistrationListeners()

        mdnsService.start()
        assertEquals(2, listeners.size)
        mdnsService.stop()

        // Verify that unregisterService was called for each registered listener
        verify(exactly = 2) {
            mockNsdManager.unregisterService(any())
        }
    }

    /**
     * Test: Calling stop() without a prior start() does not crash.
     *
     * WHY: MainActivity.onDestroy() always calls receiver.stop(), even if
     * onCreate() failed before start() was called. Stop must be safe to call
     * in any state.
     */
    @Test
    fun `stop without start does not crash`() {
        // This must not throw any exception
        mdnsService.stop()
    }

    /**
     * Test: AIRPLAY_PORT is 7000 (the standard AirPlay port).
     *
     * WHY: AirPlay requires exactly port 7000. Using any other port means
     * macOS won't be able to connect to PhairPlay.
     */
    @Test
    fun `AIRPLAY_PORT is 7000`() {
        assertEquals(7000, MdnsService.AIRPLAY_PORT)
    }

    /**
     * Test: stop() without start() emits DISABLED state.
     *
     * WHY: After stop(), the UI should show the protocol as disabled
     * even if start() was never called.
     */
    @Test
    fun `stop emits DISABLED protocol state`() {
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.stop()

        assertTrue(states.contains(com.phairplay.service.ProtocolState.DISABLED))
    }

    /** Internal re-advertisement must not tell the UI that AirPlay was disabled in Settings. */
    @Test
    fun `restart does not emit DISABLED protocol state`() {
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start()
        service.restart()

        assertTrue(
            "Restart is an internal re-advertisement and must not emit DISABLED",
            com.phairplay.service.ProtocolState.DISABLED !in states
        )
    }

    /** Advertising over Wi-Fi keeps multicast enabled, then releases it during teardown. */
    @Test
    fun `start acquires and stop releases non reference counted multicast lock`() {
        mdnsService.start()

        verify(exactly = 1) { mockMulticastLock.setReferenceCounted(false) }
        verify(exactly = 1) { mockMulticastLock.acquire() }

        mdnsService.stop()
        mdnsService.stop() // Repeated teardown must not under-release the non-reference-counted lock.

        verify(exactly = 1) { mockMulticastLock.release() }
    }

    /** Essential AirPlay failure cleans up partial NSD state and multicast resources. */
    @Test
    fun `AirPlay registration failure emits error and releases multicast lock`() {
        val listeners = captureRegistrationListeners()
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start()
        listeners[1].onRegistrationFailed(mockk(relaxed = true), 0)

        assertTrue(states.contains(com.phairplay.service.ProtocolState.ERROR))
        verify(exactly = 1) { mockMulticastLock.release() }
    }

    /** RAOP is ancillary; only the second, AirPlay callback controls picker readiness. */
    @Test
    fun `AirPlay callback alone controls advertising state`() {
        val listeners = captureRegistrationListeners()
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start("Living Room TV")
        assertEquals(2, listeners.size)
        notifyRegistered(listeners[0], "AABBCCDDEEFF@Living Room TV")

        assertTrue(ProtocolState.ADVERTISING !in states)

        notifyRegistered(listeners[1], "Living Room TV")

        assertTrue(ProtocolState.ADVERTISING in states)
    }

    /** A late AirPlay callback from a stopped lifecycle must not advertise the new lifecycle. */
    @Test
    fun `stale AirPlay callback after restart cannot advertise`() {
        val listeners = captureRegistrationListeners()
        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start("Old Name")
        val staleAirPlayListener = listeners[1]
        service.restart("New Name")

        assertEquals(4, listeners.size) // RAOP + AirPlay for each lifecycle.
        notifyRegistered(staleAirPlayListener, "Old Name")
        assertTrue("A stale success must be ignored", ProtocolState.ADVERTISING !in states)

        notifyRegistered(listeners[3], "New Name")
        assertTrue(ProtocolState.ADVERTISING in states)
    }

    /** Failure of the first, ancillary RAOP request must not tear AirPlay down. */
    @Test
    fun `RAOP registration failure is nonfatal`() {
        val listeners = captureRegistrationListeners()
        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start("Living Room TV")
        listeners[0].onRegistrationFailed(mockk(relaxed = true), 0)

        assertTrue(ProtocolState.ERROR !in states)
        verify(exactly = 0) { mockMulticastLock.release() }

        notifyRegistered(listeners[1], "Living Room TV")
        assertTrue(ProtocolState.ADVERTISING in states)
    }

    /** A missing RAOP callback must not trip the watchdog after AirPlay is confirmed. */
    @Test
    fun `RAOP callback timeout is nonfatal after AirPlay succeeds`() {
        val listeners = captureRegistrationListeners()
        val error = CountDownLatch(1)
        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = {
                states.add(it)
                if (it == ProtocolState.ERROR) error.countDown()
            },
            registrationTimeoutMs = 500L
        )

        service.start("Living Room TV")
        // Leave listeners[0] (RAOP) pending forever and confirm only AirPlay.
        notifyRegistered(listeners[1], "Living Room TV")

        assertTrue(ProtocolState.ADVERTISING in states)
        assertTrue("RAOP must not own a fatal watchdog", !error.await(750, TimeUnit.MILLISECONDS))
        verify(exactly = 0) { mockMulticastLock.release() }
        service.stop()
    }

    /** A synchronous vendor rejection of RAOP must still be followed by the AirPlay request. */
    @Test
    fun `synchronous RAOP submission failure still submits AirPlay`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        var submissions = 0
        every {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        } answers {
            submissions++
            if (submissions == 1) throw IllegalStateException("RAOP rejected")
            listeners += thirdArg<NsdManager.RegistrationListener>()
        }
        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(
            mockContext,
            onStateChange = { states.add(it) },
            registrationTimeoutMs = 0L
        )

        service.start("Living Room TV")

        assertEquals(2, submissions)
        assertEquals(1, listeners.size)
        assertTrue(ProtocolState.ERROR !in states)
        notifyRegistered(listeners.single(), "Living Room TV")
        assertTrue(ProtocolState.ADVERTISING in states)
    }

    /** Callback loss is surfaced instead of leaving AirPlay indefinitely stuck on startup. */
    @Test
    fun `registration watchdog emits error and releases multicast lock`() {
        val error = CountDownLatch(1)
        val service = MdnsService(
            mockContext,
            onStateChange = { if (it == ProtocolState.ERROR) error.countDown() },
            registrationTimeoutMs = 20L
        )

        service.start()

        assertTrue("Expected registration timeout", error.await(2, TimeUnit.SECONDS))
        verify(exactly = 1) { mockMulticastLock.release() }
    }

    /**
     * Test: restart() calls stop then start (2 unregistrations + 2 registrations).
     *
     * WHY: Restart must fully tear down and re-advertise so the device name
     * change from Settings takes effect immediately.
     */
    @Test
    fun `restart unregisters then re-registers services`() {
        val listeners = captureRegistrationListeners()
        val service = MdnsService(mockContext, registrationTimeoutMs = 0L)

        service.start()
        service.restart()

        assertEquals(4, listeners.size)
        verify(exactly = 4) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
        verify(exactly = 2) { mockNsdManager.unregisterService(any()) }
    }
}
