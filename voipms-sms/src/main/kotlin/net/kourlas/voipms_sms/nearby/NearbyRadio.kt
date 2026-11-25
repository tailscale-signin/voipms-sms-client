package net.kourlas.voipms_sms.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.kourlas.voipms_sms.R
import java.util.Collections
import java.util.WeakHashMap

internal data class NearbyState(
    val status: Int = R.string.nearby_introduction,
    val started: Boolean = false,
    val devices: Map<String, String> = emptyMap(),
    val peer: String? = null,
    val label: String = "",
    val comparison: String? = null,
    val active: Boolean = false,
)

/** A connection can follow the user back into the SMS thread; its socket is released when the app leaves the foreground. */
@Suppress("DEPRECATION")
internal object NearbyRadio {
    val state = MutableStateFlow(NearbyState())
    private val ui = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val timer = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var app: Application? = null
    private var generation = 0
    private var hosting = false
    private var ownsGroup = false
    private var selected = false
    private var exchange: Job? = null
    private var link: NearbyLink? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private val visible = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val leave = Runnable { if (visible.isEmpty()) stop() }
    private val lifecycle =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                visible.add(a)
                timer.removeCallbacks(leave)
            }

            override fun onActivityStopped(a: Activity) {
                visible.remove(a)
                if (visible.isEmpty()) timer.postDelayed(leave, if (state.value.active) 1200 else 30000)
            }

            override fun onActivityCreated(
                a: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(a: Activity) = Unit

            override fun onActivityPaused(a: Activity) = Unit

            override fun onActivitySaveInstanceState(
                a: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(a: Activity) = Unit
        }

    fun permission() =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun allowed(c: Context) = ContextCompat.checkSelfPermission(c, permission()) == PackageManager.PERMISSION_GRANTED

    private fun action(
        token: Int,
        onDone: () -> Unit = {},
        onFail: () -> Unit = { stop(R.string.nearby_radio_error) },
    ) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (token == generation) onDone()
        }

        override fun onFailure(reason: Int) {
            if (token == generation) onFail()
        }
    }

    @SuppressLint("MissingPermission") // The runtime-specific permission is checked before each group of calls.
    fun start(
        activity: Activity,
        host: Boolean,
    ) {
        stop()
        if (!allowed(activity)) {
            stop(R.string.nearby_permission_denied)
            return
        }
        val m = activity.application.getSystemService(WifiP2pManager::class.java)
        if (m == null || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            stop(R.string.nearby_no_hardware)
            return
        }
        if (!host && !LocationManagerCompat.isLocationEnabled(activity.getSystemService(LocationManager::class.java))) {
            stop(R.string.nearby_location_disabled)
            return
        }
        val c = activity.application
        val token = generation
        try {
            val ch = m.initialize(c, Looper.getMainLooper()) { if (token == generation) stop(R.string.nearby_disconnected) }
            if (ch == null) {
                stop(R.string.nearby_radio_error)
                return
            }
            manager = m
            channel = ch
            app = c
            hosting = host
            visible.add(activity)
            c.registerActivityLifecycleCallbacks(lifecycle)
            state.value = NearbyState(if (host) R.string.nearby_start_host else R.string.nearby_finding, true)
            val observer =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (token != generation) return
                        if (!allowed(c)) {
                            stop(R.string.nearby_permission_denied)
                            return
                        }
                        try {
                            when (intent.action) {
                                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                                    if (!host && !selected) {
                                        m.requestPeers(ch) { list ->
                                            if (token == generation && !selected) {
                                                state.value =
                                                    state.value.copy(
                                                        devices =
                                                            list.deviceList
                                                                .filter { !it.deviceAddress.isNullOrBlank() }
                                                                .associate { it.deviceAddress to it.deviceName.orEmpty() },
                                                    )
                                            }
                                        }
                                    }
                                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> if (ownsGroup || selected) inspect(c, m, ch, token)
                                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                                    if (intent.getIntExtra(
                                            WifiP2pManager.EXTRA_WIFI_STATE,
                                            WifiP2pManager.WIFI_P2P_STATE_ENABLED,
                                        ) ==
                                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                                    ) {
                                        stop(R.string.nearby_radio_error)
                                    }
                            }
                        } catch (_: SecurityException) {
                            stop(R.string.nearby_permission_denied)
                        }
                    }
                }
            ContextCompat.registerReceiver(
                c,
                observer,
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = observer
            // Never take over or remove a pre-existing Wi-Fi Direct group belonging to a different app.
            m.requestGroupInfo(ch) { group ->
                if (token != generation) return@requestGroupInfo
                if (group != null) {
                    stop(R.string.nearby_group_in_use)
                    return@requestGroupInfo
                }
                if (!allowed(c)) {
                    stop(R.string.nearby_permission_denied)
                    return@requestGroupInfo
                }
                if (host) {
                    m.createGroup(
                        ch,
                        action(token, {
                            ownsGroup = true
                            state.value = state.value.copy(status = R.string.nearby_host_waiting)
                            inspect(c, m, ch, token)
                        }),
                    )
                } else {
                    m.discoverPeers(ch, action(token))
                }
            }
        } catch (_: RuntimeException) {
            stop(R.string.nearby_radio_error)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val c = app ?: return
        val m = manager ?: return
        val ch = channel ?: return
        if (hosting || selected || address !in state.value.devices) return
        if (!allowed(c)) {
            stop(R.string.nearby_permission_denied)
            return
        }
        selected = true
        state.value = state.value.copy(status = R.string.nearby_connecting, devices = emptyMap())
        val token = generation
        try {
            val config =
                WifiP2pConfig().apply {
                    deviceAddress = address
                    groupOwnerIntent = 0
                }
            m.connect(ch, config, action(token, { inspect(c, m, ch, token) }))
        } catch (_: RuntimeException) {
            stop(R.string.nearby_radio_error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun inspect(
        c: Context,
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        token: Int,
    ) {
        if (!allowed(c)) {
            stop(R.string.nearby_permission_denied)
            return
        }
        m.requestConnectionInfo(ch) { info ->
            if (token != generation || info == null) return@requestConnectionInfo
            if (!info.groupFormed) {
                if (exchange != null) stop(R.string.nearby_disconnected)
                return@requestConnectionInfo
            }
            if (exchange != null) return@requestConnectionInfo
            val owner = info.groupOwnerAddress ?: return@requestConnectionInfo
            ownsGroup = true
            val socket = NearbyLink()
            link = socket
            exchange =
                ui.launch {
                    try {
                        val scope = withContext(Dispatchers.IO) { NearbyStore.scope(c) }
                        socket.exchange(c, scope, info.isGroupOwner, owner, { peer, label, code ->
                            withContext(Dispatchers.Main) {
                                if (token != generation) return@withContext false
                                val response = CompletableDeferred<Boolean>()
                                answer = response
                                state.value = NearbyState(R.string.nearby_compare, true, peer = peer, label = label, comparison = code)
                                withTimeout(120000) { response.await() }
                            }
                        }, { peer, label ->
                            withContext(Dispatchers.Main) {
                                if (token == generation) {
                                    state.value =
                                        NearbyState(
                                            R.string.nearby_connected,
                                            true,
                                            peer = peer,
                                            label = label,
                                            active = true,
                                        )
                                }
                            }
                        })
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (
                        _: Exception,
                    ) {
                        // A partial transmission remains queued until a durable peer acknowledgement.
                    } finally {
                        socket.close()
                        if (token == generation) stop(R.string.nearby_disconnected)
                    }
                }
        }
    }

    fun confirm(matches: Boolean) {
        if (!matches) {
            stop(R.string.nearby_cancelled)
            return
        }
        if (answer?.complete(true) == true) state.value = state.value.copy(comparison = null, status = R.string.nearby_wait_peer)
    }

    @SuppressLint("MissingPermission")
    fun stop(status: Int = R.string.nearby_introduction) {
        generation++
        timer.removeCallbacks(leave)
        val c = app
        val m = manager
        val ch = channel
        app = null
        manager = null
        channel = null
        if (c != null) {
            receiver?.let { runCatching { c.unregisterReceiver(it) } }
            c.unregisterActivityLifecycleCallbacks(lifecycle)
            visible.clear()
        }
        receiver = null
        answer?.cancel()
        answer = null
        link?.close()
        link = null
        exchange?.cancel()
        exchange = null
        if (m != null && ch != null) {
            fun closeChannel() {
                if (Build.VERSION.SDK_INT >= 27) ch.close()
            }
            if (c != null && allowed(c)) {
                val close =
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = closeChannel()

                        override fun onFailure(reason: Int) = closeChannel()
                    }
                runCatching {
                    when {
                        ownsGroup -> m.removeGroup(ch, close)
                        selected -> m.cancelConnect(ch, close)
                        else -> m.stopPeerDiscovery(ch, close)
                    }
                }.onFailure { closeChannel() }
            } else {
                closeChannel()
            }
        }
        selected = false
        ownsGroup = false
        hosting = false
        state.value = NearbyState(status)
    }
}
