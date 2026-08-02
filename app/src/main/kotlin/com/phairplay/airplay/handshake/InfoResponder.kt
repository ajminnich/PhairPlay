package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.util.NetworkUtils

/**
 * InfoResponder — builds the binary-plist body for `GET /info`, the first request a macOS
 * AirPlay sender makes. It advertises the receiver's identity and capability bits so the
 * sender knows to continue with pairing → FairPlay → mirroring.
 *
 * Values are kept consistent with what [com.phairplay.airplay.MdnsService] advertises so the
 * sender sees one coherent device.
 *
 * NOTE: the Ed25519 public key (`pk`) is added in the pairing phase once a persistent
 * identity exists; macOS still proceeds to pair-setup without it.
 */
object InfoResponder {

    /**
     * Builds the response to an AirPlay `GET /info` request.
     *
     * Recent senders first issue a binary-plist qualifier request for the DNS-SD TXT payloads
     * they would otherwise obtain through Bonjour. Those payloads are returned as plist data
     * values containing the DNS-SD wire representation: one unsigned length byte followed by
     * each UTF-8 `key=value` string. An unqualified request retains the full device-info response
     * used by older senders.
     */
    fun buildForRequest(
        context: Context,
        requestBody: ByteArray,
        width: Int = 1920,
        height: Int = 1080,
        pinRequired: Boolean = false
    ): ByteArray {
        val qualifiers = parseTxtQualifiers(requestBody)
            ?: return build(context, width, height, pinRequired)

        val response = linkedMapOf<String, Any?>()
        if (TXT_AIRPLAY in qualifiers) response[TXT_AIRPLAY] = buildAirPlayTxt(context)
        if (TXT_RAOP in qualifiers) response[TXT_RAOP] = buildRaopTxt()
        return PlistCodec.encode(response)
    }

    fun build(context: Context, width: Int = 1920, height: Int = 1080, pinRequired: Boolean = false): ByteArray {
        val mac = NetworkUtils.getMacAddress(context)
        // When PIN access control is on, set the "pairing/PIN required" status bit so the sender runs
        // the SRP pair-setup flow. NOTE: exact flag semantics are sender-version-dependent — verify
        // against macOS and adjust if pairing doesn't trigger.
        val statusFlags = if (pinRequired) STATUS_FLAGS or STATUS_FLAG_PIN_REQUIRED else STATUS_FLAGS
        val info = mapOf(
            "deviceID" to mac,
            "macAddress" to mac,
            "features" to AIRPLAY_FEATURES,
            "statusFlags" to statusFlags,
            "model" to MODEL,
            "name" to NetworkUtils.getDeviceName(context),
            "sourceVersion" to SOURCE_VERSION,
            "pi" to NetworkUtils.getPersistentUuid(context),
            "pk" to PairingKeys.get(context).edPublic,
            "vv" to 2L,
            "protovers" to "1.1",
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            // NOTE: macOS IGNORES this for system-audio AirPlay — it sends ALAC (ct=2) regardless of
            // what we advertise (verified: advertising AAC-only still got ALAC). So we keep the broad
            // set (mirroring negotiates AAC-ELD from it, which works). Audio-only would need a
            // software ALAC decoder since this TV has no hardware ALAC codec.
            "audioFormats" to listOf(
                mapOf("type" to 100L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L),
                mapOf("type" to 101L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L)
            ),
            "audioLatencies" to listOf(
                mapOf("type" to 100L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L),
                mapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L)
            ),
            // Screen the sender can mirror to — without this, macOS aborts after key setup.
            "displays" to listOf(
                mapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "width" to width.toLong(),
                    "height" to height.toLong(),
                    "widthPixels" to width.toLong(),
                    "heightPixels" to height.toLong(),
                    "rotation" to false,
                    "refreshRate" to (1.0 / 60.0),
                    "overscanned" to false,   // false = macOS uses the full advertised resolution
                    "features" to 14L
                )
            )
        )
        return PlistCodec.encode(info)
    }

    /** Returns null when this is not a binary-plist qualifier request. */
    private fun parseTxtQualifiers(requestBody: ByteArray): Set<String>? {
        if (requestBody.size < BPLIST_MAGIC.size ||
            !requestBody.copyOfRange(0, BPLIST_MAGIC.size).contentEquals(BPLIST_MAGIC)) {
            return null
        }

        val request = runCatching { PlistCodec.decode(requestBody) }.getOrNull() ?: return null
        if (!request.containsKey(QUALIFIER)) return null

        val values = when (val qualifier = request[QUALIFIER]) {
            is String -> listOf(qualifier)
            is List<*> -> qualifier.filterIsInstance<String>()
            else -> return null
        }
        return values.filterTo(linkedSetOf()) { it == TXT_AIRPLAY || it == TXT_RAOP }
    }

    private fun buildAirPlayTxt(context: Context): ByteArray = encodeDnsSdTxt(
        listOf(
            "deviceid" to NetworkUtils.getMacAddress(context),
            "features" to AIRPLAY_FEATURES_TXT,
            "model" to MODEL,
            "srcvers" to SOURCE_VERSION,
            "vv" to "2",
            "pi" to NetworkUtils.getPersistentUuid(context),
            "flags" to "0x4"
        )
    )

    private fun buildRaopTxt(): ByteArray = encodeDnsSdTxt(
        listOf(
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "md" to "0,1,2",
            "sv" to "false",
            "tp" to "UDP",
            "vn" to "65537",
            "vs" to SOURCE_VERSION,
            "am" to MODEL
        )
    )

    /** Encodes DNS-SD TXT strings as `<one-byte length><key=value bytes>` records. */
    private fun encodeDnsSdTxt(records: List<Pair<String, String>>): ByteArray {
        val encodedRecords = records.map { (key, value) ->
            "$key=$value".toByteArray(Charsets.UTF_8).also { bytes ->
                require(bytes.size <= MAX_TXT_RECORD_BYTES) {
                    "DNS-SD TXT record exceeds $MAX_TXT_RECORD_BYTES bytes: $key"
                }
            }
        }
        val result = ByteArray(encodedRecords.sumOf { it.size + 1 })
        var offset = 0
        encodedRecords.forEach { record ->
            result[offset++] = record.size.toByte()
            record.copyInto(result, destinationOffset = offset)
            offset += record.size
        }
        return result
    }

    /** 64-bit features value; mirrors MdnsService's "0x5A7FFFF7,0x1E" (low,high 32-bit halves). */
    private const val AIRPLAY_FEATURES = 0x1E5A7FFFF7L

    private const val AIRPLAY_FEATURES_TXT = "0x5A7FFFF7,0x1E"

    /** Matches RPiPlay's /info statusFlags (0x44). */
    private const val STATUS_FLAGS = 68L

    /** Status bit advertising that the receiver requires PIN pairing (0x8 — verify vs macOS). */
    private const val STATUS_FLAG_PIN_REQUIRED = 0x8L

    private const val MODEL = "AppleTV5,3"
    private const val SOURCE_VERSION = "220.68"
    private const val QUALIFIER = "qualifier"
    private const val TXT_AIRPLAY = "txtAirPlay"
    private const val TXT_RAOP = "txtRAOP"
    private const val MAX_TXT_RECORD_BYTES = 255
    private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
}
