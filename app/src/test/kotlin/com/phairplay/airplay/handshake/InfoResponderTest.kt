package com.phairplay.airplay.handshake

import android.content.Context
import android.content.SharedPreferences
import com.phairplay.util.NetworkUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InfoResponderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any<String>(), any()) } returns preferences
        every { preferences.getString(any(), any()) } returns null
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor

        mockkObject(NetworkUtils)
        every { NetworkUtils.getMacAddress(context) } returns TEST_MAC
        every { NetworkUtils.getPersistentUuid(context) } returns TEST_UUID
        every { NetworkUtils.getDeviceName(context) } returns "Test Receiver"
    }

    @After
    fun tearDown() {
        unmockkObject(NetworkUtils)
    }

    @Test
    fun `AirPlay qualifier returns only length-prefixed AirPlay TXT data`() {
        val request = qualifierRequest("txtAirPlay")

        val response = PlistCodec.decode(InfoResponder.buildForRequest(context, request))

        assertEquals(setOf("txtAirPlay"), response.keys)
        val records = decodeDnsSdTxt(response.getValue("txtAirPlay") as ByteArray)
        assertEquals(
            listOf(
                "deviceid=$TEST_MAC",
                "features=0x5A7FFFF7,0x1E",
                "model=AppleTV5,3",
                "srcvers=220.68",
                "vv=2",
                "pi=$TEST_UUID",
                "flags=0x4"
            ),
            records
        )
    }

    @Test
    fun `combined qualifier returns both advertised TXT payloads`() {
        val request = qualifierRequest("txtAirPlay", "txtRAOP")

        val response = PlistCodec.decode(InfoResponder.buildForRequest(context, request))

        assertEquals(setOf("txtAirPlay", "txtRAOP"), response.keys)
        assertEquals(
            listOf(
                "cn=0,1,2,3",
                "da=true",
                "et=0,3,5",
                "md=0,1,2",
                "sv=false",
                "tp=UDP",
                "vn=65537",
                "vs=220.68",
                "am=AppleTV5,3"
            ),
            decodeDnsSdTxt(response.getValue("txtRAOP") as ByteArray)
        )
    }

    @Test
    fun `unqualified request preserves full device info response`() {
        val response = PlistCodec.decode(InfoResponder.buildForRequest(context, ByteArray(0)))

        assertTrue("full response must include receiver identity", response.containsKey("deviceID"))
        assertTrue("full response must include display capabilities", response.containsKey("displays"))
        assertFalse("unqualified response is not a TXT probe", response.containsKey("txtAirPlay"))
        assertFalse("unqualified response is not a TXT probe", response.containsKey("txtRAOP"))
    }

    @Test
    fun `unsupported qualifier returns an empty qualified response`() {
        val request = qualifierRequest("notARealQualifier")

        val response = PlistCodec.decode(InfoResponder.buildForRequest(context, request))

        assertTrue(response.isEmpty())
    }

    private fun qualifierRequest(vararg qualifiers: String): ByteArray =
        PlistCodec.encode(mapOf("qualifier" to qualifiers.toList()))

    /** Parses raw DNS-SD TXT RDATA and validates every one-byte length prefix. */
    private fun decodeDnsSdTxt(data: ByteArray): List<String> {
        val records = mutableListOf<String>()
        var offset = 0
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xFF
            offset++
            assertTrue("TXT length prefix exceeds the remaining payload", offset + length <= data.size)
            records += String(data, offset, length, Charsets.UTF_8)
            offset += length
        }
        assertEquals("TXT parser must consume the complete payload", data.size, offset)
        return records
    }

    private companion object {
        const val TEST_MAC = "aa:bb:cc:dd:ee:ff"
        const val TEST_UUID = "12345678-1234-1234-1234-123456789abc"
    }
}
