package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /** Override with 0 in deterministic unit tests to disable the real-time watchdog. */
    private val registrationTimeoutMs: Long = REGISTRATION_TIMEOUT_MS
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Keep Wi-Fi multicast reception enabled for the lifetime of the advertisement. Android's
    // NSD implementation needs this on older platform releases and when the foreground service is
    // running while the Activity is backgrounded. The manifest already grants
    // CHANGE_WIFI_MULTICAST_STATE; Ethernet-only devices simply have no useful lock to acquire.
    private var multicastLock: WifiManager.MulticastLock? = null

    // Android's NSD advertiser on the onn TV completes only the second of two back-to-back
    // registration operations. Submit ancillary RAOP first so essential AirPlay is second, and
    // track their callbacks independently because only AirPlay controls receiver readiness.
    @Volatile private var airPlayRegistered = false
    @Volatile private var raopRegistered = false

    // Every start gets a distinct generation. Android can deliver an NSD callback after its
    // listener was unregistered; without this token, an old AirPlay success could advertise a
    // newer start (or an old failure could tear the newer registration down).
    private var registrationGeneration = 0L

    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registrationTimeoutJob: Job? = null

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    @Synchronized
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registrationGeneration++
        val generation = registrationGeneration
        airPlayRegistered = false
        raopRegistered = false

        val effectiveName = resolveDisplayName(displayNameOverride)
        val airPlayServiceName = resolveAirPlayServiceName(effectiveName)
        Logger.i("Starting mDNS advertising as '$airPlayServiceName'")
        requestedName = airPlayServiceName

        acquireMulticastLock()

        // The working reference receiver submits RAOP first and AirPlay second. Preserve that
        // ordering so the onn NSD daemon's second completed request is the picker-visible one.
        // RAOP is ancillary: a synchronous vendor failure must not suppress AirPlay submission.
        try {
            registerRaopService(effectiveName, generation)
        } catch (e: Exception) {
            Logger.e("Failed to submit ancillary RAOP mDNS registration; continuing", e)
        }

        try {
            scheduleRegistrationTimeout(generation, "_airplay._tcp") { airPlayRegistered }
            registerAirPlayService(airPlayServiceName, generation)
        } catch (e: Exception) {
            // A synchronous NsdManager failure must not leak the multicast lock or a partially
            // registered service. Let AirPlayReceiver's startup guard report the ERROR state.
            stopInternal(notifyDisabled = false)
            throw e
        }
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    @Synchronized
    fun stop() {
        stopInternal(notifyDisabled = true)
    }

    /** Performs teardown, optionally reporting a user-visible disabled state. */
    @Synchronized
    private fun stopInternal(notifyDisabled: Boolean) {
        Logger.i("Stopping mDNS advertising")
        // Unregister independently: one stale/failed listener must not prevent the other service
        // from being torn down during restart.
        airPlayListener?.let { unregisterSafely(it, "_airplay._tcp") }
        raopListener?.let { unregisterSafely(it, "_raop._tcp") }
        airPlayListener = null
        raopListener = null
        airPlayRegistered = false
        raopRegistered = false
        isStarted = false
        registrationTimeoutJob?.cancel()
        registrationTimeoutJob = null
        releaseMulticastLock()
        if (notifyDisabled) onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    @Synchronized
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        // This is an internal re-advertisement while AirPlay remains enabled. Emitting DISABLED
        // here makes the Home card incorrectly say "Enable in Settings" between registrations.
        stopInternal(notifyDisabled = false)
        start(displayNameOverride)
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Avoids a cross-service instance-name collision in Android TV's Java mDNS backend.
     *
     * The onn firmware advertises its built-in `_androidtvremote2._tcp` service using the system
     * device name. Its mDNS implementation incorrectly rejects another local service with that
     * same instance name even when the service type differs, leaving AirPlay without a callback.
     * A user-supplied distinct name is preserved; the system-default name gets an AirPlay suffix.
     */
    private fun resolveAirPlayServiceName(baseName: String): String {
        val systemName = NetworkUtils.getDeviceName(context)
        if (!baseName.equals(systemName, ignoreCase = true)) return baseName

        val suffix = AIRPLAY_NAME_SUFFIX
        val maxBaseCharacters = (MAX_MDNS_LABEL_CHARACTERS - suffix.length).coerceAtLeast(1)
        return baseName.take(maxBaseCharacters).trimEnd() + suffix
    }

    /** Acquires one non-reference-counted multicast lock for this advertisement lifecycle. */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return

        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG) ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            Logger.d("mDNS multicast lock acquired")
        } catch (e: Exception) {
            // Some Ethernet-only and vendor Android TV builds reject Wi-Fi lock operations even
            // with the manifest permission. NsdManager may still work, so log and continue.
            multicastLock = null
            Logger.e("Could not acquire mDNS multicast lock", e)
        }
    }

    /** Releases the multicast lock exactly once; safe after partial startup or repeated stop. */
    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try {
            if (lock.isHeld) lock.release()
            Logger.d("mDNS multicast lock released")
        } catch (e: Exception) {
            Logger.e("Could not release mDNS multicast lock", e)
        }
    }

    private fun unregisterSafely(
        listener: NsdManager.RegistrationListener,
        serviceLabel: String
    ) {
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            // Non-fatal: the service will expire via its mDNS TTL.
            Logger.e("Error unregistering mDNS $serviceLabel service (non-fatal)", e)
        }
    }

    /**
     * Fails essential AirPlay registration when Android NSD produces neither callback.
     *
     * The first RAOP request is ancillary and intentionally has no fatal watchdog. This watchdog
     * belongs to the second, picker-visible AirPlay request so callback loss remains visible and
     * recoverable without making RAOP a readiness requirement.
     */
    private fun scheduleRegistrationTimeout(
        generation: Long,
        serviceLabel: String,
        isRegistered: () -> Boolean
    ) {
        registrationTimeoutJob?.cancel()
        if (registrationTimeoutMs <= 0L) return

        registrationTimeoutJob = timeoutScope.launch {
            delay(registrationTimeoutMs)
            synchronized(this@MdnsService) {
                if (!isStarted || generation != registrationGeneration || isRegistered()) {
                    return@synchronized
                }
                Logger.e("mDNS registration timed out for $serviceLabel")
                stopInternal(notifyDisabled = false)
                onStateChange(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String, generation: Long) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress(context))
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        airPlayListener = createRegistrationListener(
            generation = generation,
            serviceLabel = "_airplay._tcp",
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = { onAirPlayRegistered(generation) },
            onFailure = { errorCode -> onAirPlayRegistrationFailed(generation, errorCode) }
        )
        Logger.d("Submitting mDNS registration: _airplay._tcp")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String, generation: Long) {
        val macHex = NetworkUtils.getMacAddress(context).replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@$displayName"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        raopListener = createRegistrationListener(
            generation = generation,
            serviceLabel = "_raop._tcp",
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = { onRaopRegistered(generation) },
            onFailure = { errorCode -> onRaopRegistrationFailed(generation, errorCode) }
        )
        Logger.d("Submitting mDNS registration: _raop._tcp")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Marks the receiver ready when the essential AirPlay advertisement succeeds. RAOP is useful
     * for audio senders but is intentionally not a readiness gate: the onn NSD daemon may leave the
     * first back-to-back request pending while successfully advertising the second (AirPlay) one.
     */
    @Synchronized
    private fun onAirPlayRegistered(generation: Long) {
        if (!isStarted || generation != registrationGeneration || airPlayRegistered) return
        airPlayRegistered = true
        registrationTimeoutJob?.cancel()
        registrationTimeoutJob = null
        onStateChange(ProtocolState.ADVERTISING)
    }

    /** Records ancillary RAOP success without changing AirPlay's user-visible state. */
    @Synchronized
    private fun onRaopRegistered(generation: Long) {
        if (!isStarted || generation != registrationGeneration || raopRegistered) return
        raopRegistered = true
    }

    /** AirPlay registration failure is fatal because the receiver cannot appear in pickers. */
    @Synchronized
    private fun onAirPlayRegistrationFailed(generation: Long, errorCode: Int) {
        if (!isStarted || generation != registrationGeneration) return
        Logger.e("Essential AirPlay mDNS registration failed, errorCode=$errorCode")
        stopInternal(notifyDisabled = false)
        onStateChange(ProtocolState.ERROR)
    }

    /** RAOP is ancillary; failure must not tear down a working AirPlay advertisement. */
    @Synchronized
    private fun onRaopRegistrationFailed(generation: Long, errorCode: Int) {
        if (!isStarted || generation != registrationGeneration) return
        raopRegistered = false
        Logger.w("Ancillary RAOP mDNS registration failed, errorCode=$errorCode; continuing")
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        generation: Long,
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@MdnsService) {
                    if (!isStarted || generation != registrationGeneration) {
                        Logger.d("Ignoring stale mDNS success callback for $serviceLabel")
                        return@synchronized
                    }
                    // NsdManager may append " (2)" to resolve name conflicts.
                    // Log the actual name so we can debug picker-visibility issues.
                    Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                    onRegisteredName?.invoke(serviceInfo.serviceName)
                    onSuccess()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@MdnsService) {
                    if (!isStarted || generation != registrationGeneration) {
                        Logger.d("Ignoring stale mDNS failure callback for $serviceLabel")
                        return@synchronized
                    }
                    // Error codes from NsdManager:
                    //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                    //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                    //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        Logger.w("mDNS $serviceLabel already active — treating as success")
                        onSuccess()
                    } else {
                        // AirPlay and RAOP deliberately have different failure policies. Delegate
                        // teardown/state handling to the service-specific callback.
                        onFailure(errorCode)
                    }
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"

        private const val MULTICAST_LOCK_TAG = "PhairPlay:mDNS"

        private const val REGISTRATION_TIMEOUT_MS = 8_000L

        private const val AIRPLAY_NAME_SUFFIX = " AirPlay"

        private const val MAX_MDNS_LABEL_CHARACTERS = 63
    }
}
