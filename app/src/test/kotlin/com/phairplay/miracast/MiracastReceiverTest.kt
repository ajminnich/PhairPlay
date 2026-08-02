package com.phairplay.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.phairplay.airplay.RtspRequest
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiracastReceiverTest — verifies honest fail-closed behavior and RTSP groundwork.
 */
class MiracastReceiverTest {

    @Test
    fun `start does not present DNS-SD groundwork as a functional Miracast receiver`() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<WifiP2pManager>(relaxed = true)
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns manager

        MiracastReceiver(context) { states.add(it) }.start()

        verify(exactly = 0) { manager.initialize(any(), any(), any()) }
        verify(exactly = 0) { manager.addLocalService(any(), any(), any()) }
        assertEquals(listOf(ProtocolState.ERROR), states)
    }

    @Test
    fun `start emits error even when WifiP2pManager is unavailable`() {
        val context = mockk<Context>(relaxed = true)
        val states = mutableListOf<ProtocolState>()

        MiracastReceiver(context) { states.add(it) }.start()

        assertTrue(states.contains(ProtocolState.ERROR))
    }

    @Test
    fun `WFD RTSP port uses Miracast default`() {
        assertEquals(7236, MiracastReceiver.WFD_RTSP_PORT)
    }

    @Test
    fun `WFD RTSP server advertises sink capabilities`() {
        val server = WfdRtspServer(
            onSessionStarted = {},
            onSessionStopped = {}
        )

        val response = server.routeRequest(
            RtspRequest(
                method = "GET_PARAMETER",
                uri = "rtsp://192.168.49.1/wfd1.0",
                headers = mapOf("CSeq" to "2"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("text/parameters", response.headers["Content-Type"])
        assertTrue(response.body.contains("wfd_audio_codecs"))
        assertTrue(response.body.contains("wfd_video_formats"))
        assertTrue(response.body.contains("wfd_client_rtp_ports"))
    }

    @Test
    fun `WFD RTSP PLAY emits connected state once`() {
        var started = 0
        val server = WfdRtspServer(
            onSessionStarted = { started++ },
            onSessionStopped = {}
        )
        val request = RtspRequest(
            method = "PLAY",
            uri = "rtsp://192.168.49.1/wfd1.0",
            headers = mapOf("CSeq" to "5"),
            body = ""
        )

        assertEquals(200, server.routeRequest(request).statusCode)
        assertEquals(200, server.routeRequest(request).statusCode)

        assertEquals(1, started)
    }
}
