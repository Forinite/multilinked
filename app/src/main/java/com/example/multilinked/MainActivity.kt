package com.example.multilinked

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * multilinked: offline chain-relay LIVE voice (one speaker, everyone else listens).
 *
 * Links: any number of links can stay connected.
 * Tree:  for each broadcast, audio is first flooded briefly. Each phone then picks ONE
 *        parent (the neighbour that delivered it in the fewest hops) and sends PRUNE
 *        ("do not send me this stream") to its other neighbours. Phones only forward
 *        audio to neighbours that have not pruned them, so the audio flows along a tree
 *        and the extra links sit idle as spare routes.
 * Heal:  if the parent link drops or no new frames arrive for 0.8 s, the phone sends
 *        GRAFT ("send it to me again") to all neighbours and picks a new parent.
 */
class MainActivity : Activity() {

    private val serviceId = "com.example.multilinked"
    private val myId = UUID.randomUUID().toString().take(6)
    private lateinit var client: ConnectionsClient

    private val neighbors = mutableMapOf<String, String>()    // endpointId -> device name
    private val idOf = HashMap<String, String>()              // endpointId -> device id (for logs)
    private val pending = mutableMapOf<String, String>()      // endpointId -> device name (connecting)
    private val pendingSince = mutableMapOf<String, Long>()
    private var running = false
    private var searching = false
    private var myName = ""
    private var allowed = setOf<String>()                     // empty = link with anyone
    private var isSpeaker = false
    private val searchSeconds = 30

    // packet types
    private val tAudio = 1
    private val tPrune = 2
    private val tGraft = 3

    // audio: 480 samples (60 ms) -> 480 mu-law bytes on the wire, 960 bytes of PCM16 locally
    private val sampleRate = 8000
    private val frameSamples = 480
    private val pcmFrameBytes = frameSamples * 2
    private val prebufferFrames = 3

    // tree settings
    private val decideAfterMs = 400L      // how long to flood before choosing a parent
    private val healAfterMs = 800L        // silence that makes a phone look for a new parent

    // send queue limit per link
    private val maxInFlight = 4
    private val inFlight = HashMap<Long, Pair<String, Long>>()  // payloadId -> (endpoint, time sent)
    private var dropped = 0

    // per-stream tree state
    private class StreamState(val sid: Int, var startedAt: Long) {
        val bestHops = HashMap<String, Int>()     // neighbour -> fewest hops it delivered
        val order = HashMap<String, Int>()        // neighbour -> order of first delivery
        val pruned = HashSet<String>()            // neighbours that asked us NOT to send this stream
        var parent: String? = null
        var decided = false
        var isRoot = false                        // true on the speaker for its own stream
        var lastNewFrameAt = startedAt
        var lastGraftAt = 0L
    }
    private val streams = HashMap<Int, StreamState>()

    // statistics, summarised in the log every ~6 s
    private class LinkStats {
        var sent = 0; var skipped = 0; var dropped = 0; var done = 0; var failed = 0
        var sumMs = 0L; var maxMs = 0L
    }
    private val txStats = HashMap<String, LinkStats>()
    private val rxNew = HashMap<String, Int>()
    private val rxDup = HashMap<String, Int>()
    private var rxFrames = 0
    private var rxGaps = 0
    private var rxStale = 0
    private var rxMaxGap = 0L
    private var gapSid = 0
    private var gapLastAt = 0L
    private var tickCount = 0

    @Volatile private var broadcasting = false

    private val lastSeq = HashMap<Int, Int>()                 // streamId -> newest frame number seen
    private var track: AudioTrack? = null
    private var curSid = 0
    private var framesQueued = 0
    private var framesWritten = 0L
    private var playbackOverflow = 0
    private var trackStarted = false
    private val handler = Handler(Looper.getMainLooper())

    private val endRunnable = Runnable {
        if (track != null) { stopTrack(); addLine("[speaker stopped]") }
    }
    private val searchTimeout = Runnable { stopSearch("timed out after $searchSeconds s") }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            updateStatus()
            clearStalePending()
            tickCount++
            if (tickCount % 3 == 0) logStats()
            handler.postDelayed(this, 2000)
        }
    }
    // watches for a stream that stopped arriving while we expected it
    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            for (st in streams.values.toList()) {
                if (st.isRoot || !st.decided || st.parent == null) continue
                val quiet = now - st.lastNewFrameAt
                if (quiet >= healAfterMs && now - st.lastGraftAt > 1500) {
                    heal(st, "no new frames for $quiet ms")
                }
            }
            handler.postDelayed(this, 400)
        }
    }

    // log
    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logFile by lazy { File(filesDir, "multilinked.log") }
    private var logVisible = false

    // views
    private lateinit var mainView: LinearLayout
    private lateinit var logPanel: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var nameInput: EditText
    private lateinit var allowedInput: EditText
    private lateinit var roleBtn: Button
    private lateinit var toggle: Button
    private lateinit var searchBtn: Button
    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var talkBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = Nearby.getConnectionsClient(this)

        // ----- main screen -----
        mainView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        nameInput = EditText(this).apply {
            hint = "This device's name (e.g. B)"; setText("A"); setSingleLine()
        }
        allowedInput = EditText(this).apply {
            hint = "Only link with (e.g. A,C). Empty = anyone"; setSingleLine()
        }
        roleBtn = Button(this).apply {
            setOnClickListener { isSpeaker = !isSpeaker; refreshButtons() }
        }
        toggle = Button(this).apply {
            setOnClickListener { if (running) stopSession() else requestPermsThenStart() }
        }
        searchBtn = Button(this).apply {
            setOnClickListener { if (searching) stopSearch("stopped by you") else startSearch() }
        }
        val logsBtn = Button(this).apply {
            text = "Show logs"
            setOnClickListener { showLogs(true) }
        }
        statusView = TextView(this).apply { text = "Stopped"; setPadding(0, dp(6), 0, dp(6)) }
        chatView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(chatView) }
        talkBtn = Button(this).apply {
            textSize = 18f
            setOnClickListener { if (broadcasting) stopBroadcast() else startBroadcast() }
        }

        mainView.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(allowedInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(roleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(row(toggle, searchBtn), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(logsBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainView.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        mainView.addView(talkBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(100)))

        // ----- log screen -----
        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        val backBtn = Button(this).apply { text = "Back"; setOnClickListener { showLogs(false) } }
        val shareBtn = Button(this).apply { text = "Share"; setOnClickListener { shareLog() } }
        val clearBtn = Button(this).apply {
            text = "Clear"
            setOnClickListener {
                logLines.clear()
                try { logFile.writeText("") } catch (_: Exception) {}
                logText.text = ""
            }
        }
        logText = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logText) }
        logPanel.addView(row(backBtn, shareBtn, clearBtn), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        logPanel.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val root = FrameLayout(this)
        root.addView(mainView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(logPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        loadPreviousLog()
        log("App opened. ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), Google Play services ${playServicesVersion()}, device id $myId. Build: tree routing v1")
        refreshButtons()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (logVisible) showLogs(false) else super.onBackPressed()
    }

    override fun onDestroy() {
        if (running) stopSession()
        super.onDestroy()
    }

    private fun refreshButtons() {
        roleBtn.isEnabled = !running
        roleBtn.text = if (isSpeaker) "Role: SPEAKER (tap to change)" else "Role: Listener (tap to change)"
        toggle.text = if (running) "Stop session" else "Start session"
        searchBtn.isEnabled = running
        searchBtn.text = if (searching) "Searching... (tap to stop)" else "Search for devices"
        talkBtn.isEnabled = running && isSpeaker
        talkBtn.text = when {
            !isSpeaker -> "Listening (only the speaker can talk)"
            broadcasting -> "Stop broadcasting"
            else -> "Start broadcasting"
        }
    }

    // ---------- logging ----------

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        logLines.add(line)
        if (logLines.size > 4000) logLines.removeAt(0)
        try { logFile.appendText(line + "\n") } catch (_: Exception) {}
        if (logVisible) {
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun loadPreviousLog() {
        try {
            if (logFile.exists()) {
                if (logFile.length() > 500_000) { logFile.delete(); return }
                val prev = logFile.readLines().takeLast(300)
                if (prev.isNotEmpty()) {
                    logLines.add("---- previous run (last ${prev.size} lines) ----")
                    logLines.addAll(prev)
                    logLines.add("---- new run ----")
                }
            }
        } catch (_: Exception) {}
    }

    private fun showLogs(show: Boolean) {
        logVisible = show
        mainView.visibility = if (show) View.GONE else View.VISIBLE
        logPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            logText.text = logLines.joinToString("\n") + "\n"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun shareLog() {
        val header = "multilinked log from \"${nameInput.text}\"/$myId (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n"
        val text = header + logLines.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "multilinked log ${nameInput.text}")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000))
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    private fun describe(e: Exception): String =
        if (e is ApiException) "code ${e.statusCode} (${ConnectionsStatusCodes.getStatusCodeString(e.statusCode)})"
        else (e.message ?: e.toString())

    private fun playServicesVersion(): String = try {
        packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun hex(sid: Int): String = Integer.toHexString(sid)

    // "Name/deviceId" so two phones with the same name can be told apart
    private fun label(ep: String): String {
        val n = neighbors[ep] ?: pending[ep] ?: ep.take(4)
        val id = idOf[ep]
        return if (id != null) "$n/$id" else n
    }

    private fun linkList(): String =
        if (neighbors.isEmpty()) "none" else neighbors.keys.joinToString(", ") { label(it) }

    private fun logRadioState() {
        val bt = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
        } catch (_: Exception) { null }
        val wifi = try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { null }
        val loc = try {
            if (Build.VERSION.SDK_INT >= 28)
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
            else null
        } catch (_: Exception) { null }
        fun f(b: Boolean?) = when (b) { true -> "ON"; false -> "OFF"; null -> "unknown" }
        log("Radios: bluetooth ${f(bt)}, wifi ${f(wifi)}, location ${f(loc)}")
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        log(if (missing.isEmpty()) "Permissions: all granted" else "Permissions MISSING: ${missing.joinToString { it.substringAfterLast('.') }}")
    }

    private fun treeInfo(): String? {
        val now = SystemClock.elapsedRealtime()
        val st = streams.values.maxByOrNull { it.lastNewFrameAt } ?: return null
        if (now - st.lastNewFrameAt > 20000) return null
        val p = st.parent
        val role = when {
            st.isRoot -> "I am the ROOT"
            p != null -> "parent ${label(p)}"
            else -> "parent undecided"
        }
        val skip = if (st.pruned.isEmpty()) "nobody" else st.pruned.joinToString(", ") { label(it) }
        return "TREE ${hex(st.sid)}: $role, not sending to: $skip"
    }

    private fun logStats() {
        val parts = ArrayList<String>()
        for ((ep, st) in txStats) {
            if (st.sent + st.dropped + st.skipped == 0) continue
            val avg = if (st.done > 0) st.sumMs / st.done else 0
            parts.add("to ${label(ep)}: sent ${st.sent}, skipped(pruned) ${st.skipped}, dropped ${st.dropped}, failed ${st.failed}, send time avg ${avg}ms max ${st.maxMs}ms")
        }
        txStats.clear()
        if (rxFrames + rxGaps + rxStale > 0) {
            val by = (rxNew.keys + rxDup.keys).toSet().joinToString(", ") { "${label(it)} ${rxNew[it] ?: 0} new/${rxDup[it] ?: 0} dup" }
            parts.add("received $rxFrames new frames ($rxGaps missing, $rxStale duplicate/late) by neighbour: $by; longest gap between new frames ${rxMaxGap}ms")
            rxFrames = 0; rxGaps = 0; rxStale = 0; rxMaxGap = 0
            rxNew.clear(); rxDup.clear()
        }
        val t = track
        if (t != null) {
            val bufMs = if (trackStarted) ((framesWritten - t.playbackHeadPosition.toLong()) * 1000 / sampleRate) else 0
            parts.add("playback: buffered about ${bufMs}ms, underruns so far ${t.underrunCount}, frames lost to a full buffer $playbackOverflow")
        }
        treeInfo()?.let { parts.add(it) }
        if (parts.isNotEmpty()) log("STATS links[${linkList()}] " + parts.joinToString(" | "))
    }

    // ---------- permissions ----------

    private fun neededPermissions(): Array<String> {
        val l = mutableListOf<String>()
        l += Manifest.permission.RECORD_AUDIO
        l += Manifest.permission.ACCESS_COARSE_LOCATION
        l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_ADVERTISE
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.NEARBY_WIFI_DEVICES
        return l.toTypedArray()
    }

    private fun requestPermsThenStart() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSession() else {
            log("Asking for permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startSession()
        else {
            statusView.text = "Permissions denied. Allow them in Settings and press Start again."
            log("Permissions DENIED: ${perms.indices.filter { results.getOrNull(it) != PackageManager.PERMISSION_GRANTED }.joinToString { perms[it].substringAfterLast('.') }}")
        }
    }

    // ---------- session + search ----------

    private fun startSession() {
        myName = nameInput.text.toString().trim().ifEmpty { "?" }
        allowed = allowedInput.text.toString().split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        running = true
        dropped = 0
        tickCount = 0
        nameInput.isEnabled = false
        allowedInput.isEnabled = false
        log("SESSION start: name \"$myName\"/$myId, role ${if (isSpeaker) "SPEAKER" else "listener"}, allowed ${if (allowed.isEmpty()) "anyone" else allowed.joinToString(",")}")
        logRadioState()
        refreshButtons()
        updateStatus()
        handler.postDelayed(tick, 2000)
        handler.postDelayed(watch, 400)
        startSearch()
    }

    private fun stopSession() {
        stopBroadcast()
        stopSearch("session stopped")
        handler.removeCallbacks(endRunnable)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watch)
        stopTrack()
        running = false                     // set first so closing links are not logged as problems
        client.stopAllEndpoints()
        neighbors.clear()
        idOf.clear()
        pending.clear()
        pendingSince.clear()
        inFlight.clear()
        txStats.clear()
        streams.clear()
        rxNew.clear(); rxDup.clear()
        nameInput.isEnabled = true
        allowedInput.isEnabled = true
        log("SESSION stopped")
        refreshButtons()
        statusView.text = "Stopped"
    }

    // Advertise + discover together. Stops at the first new link or after searchSeconds.
    private fun startSearch() {
        if (!running || searching) return
        searching = true
        refreshButtons()
        log("SEARCH start (auto-stops at first new link or after $searchSeconds s). Both phones must be searching to find each other.")

        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising("$myId|$myName", serviceId, connectionCallback, adv)
            .addOnSuccessListener { log("advertising started") }
            .addOnFailureListener { log("advertising FAILED: ${describe(it)}"); statusView.text = "Advertising failed: ${describe(it)}" }

        val disc = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, discoveryCallback, disc)
            .addOnSuccessListener { log("discovery started") }
            .addOnFailureListener { log("discovery FAILED: ${describe(it)}"); statusView.text = "Discovery failed: ${describe(it)}" }

        handler.postDelayed(searchTimeout, searchSeconds * 1000L)
        updateStatus()
    }

    private fun stopSearch(reason: String) {
        if (!searching) return
        searching = false
        handler.removeCallbacks(searchTimeout)
        client.stopAdvertising()
        client.stopDiscovery()
        log("SEARCH stop: $reason")
        refreshButtons()
        updateStatus()
    }

    private fun updateStatus() {
        val n = if (neighbors.isEmpty()) "none yet" else neighbors.values.joinToString(", ")
        statusView.text = "Running as $myName | links: $n | ${if (searching) "SEARCHING" else "not searching"} | frames dropped: $dropped"
    }

    private fun clearStalePending() {
        val now = SystemClock.elapsedRealtime()
        val stale = pendingSince.filter { now - it.value > 15000 }.keys.toList()
        for (id in stale) {
            pendingSince.remove(id)
            val lab = label(id)
            pending.remove(id)
            log("connection attempt with $lab never finished after 15 s: cleared")
        }
    }

    // ---------- discovery + connections ----------

    private fun parse(s: String): Pair<String, String>? {
        val i = s.indexOf('|')
        return if (i < 0) null else Pair(s.substring(0, i), s.substring(i + 1))
    }

    private fun isAllowed(name: String) = allowed.isEmpty() || allowed.contains(name.trim().lowercase())

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parsed = parse(info.endpointName)
            if (parsed == null) { log("found $endpointId with unreadable name \"${info.endpointName}\": ignored"); return }
            val (remoteId, remoteName) = parsed
            idOf[endpointId] = remoteId
            when {
                !isAllowed(remoteName) ->
                    log("found \"$remoteName\"/$remoteId: IGNORED, not in my allowed list (${allowed.joinToString(",")})")
                neighbors.containsKey(endpointId) ->
                    log("found \"$remoteName\"/$remoteId: already linked")
                pending.containsKey(endpointId) ->
                    log("found \"$remoteName\"/$remoteId: connection already in progress")
                myId < remoteId -> {
                    log("found \"$remoteName\"/$remoteId: I start the connection")
                    pending[endpointId] = remoteName
                    pendingSince[endpointId] = SystemClock.elapsedRealtime()
                    client.requestConnection("$myId|$myName", endpointId, connectionCallback)
                        .addOnFailureListener {
                            pending.remove(endpointId); pendingSince.remove(endpointId)
                            log("request to \"$remoteName\"/$remoteId FAILED: ${describe(it)}")
                        }
                }
                else -> log("found \"$remoteName\"/$remoteId: waiting for them to start the connection (they must be searching too)")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            log("endpoint lost: ${label(endpointId)}")
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parsed = parse(info.endpointName)
            if (parsed == null || !isAllowed(parsed.second)) {
                log("connection request from \"${info.endpointName}\": REJECTED (not allowed)")
                client.rejectConnection(endpointId)
                return
            }
            idOf[endpointId] = parsed.first
            log("connection with \"${parsed.second}\"/${parsed.first} initiated: accepting")
            pending[endpointId] = parsed.second
            pendingSince[endpointId] = SystemClock.elapsedRealtime()
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pending.remove(endpointId)
            pendingSince.remove(endpointId)
            val code = result.status.statusCode
            if (result.status.isSuccess && name != null) {
                neighbors[endpointId] = name
                addLine("[linked with ${label(endpointId)}] (now ${neighbors.size} link${if (neighbors.size == 1) "" else "s"})")
                // A new neighbour during a live stream: tell it not to send us that stream
                val now = SystemClock.elapsedRealtime()
                for (st in streams.values) {
                    if (!st.isRoot && st.parent != null && now - st.lastNewFrameAt < 5000) {
                        sendControl(endpointId, tPrune, st.sid)
                    }
                }
                stopSearch("linked with ${label(endpointId)}")
            } else {
                log("connection with ${name ?: endpointId} FAILED: code $code (${ConnectionsStatusCodes.getStatusCodeString(code)})")
            }
            updateStatus()
        }

        override fun onDisconnected(endpointId: String) {
            if (!running) return
            val lab = label(endpointId)
            val name = neighbors.remove(endpointId)
            if (name != null) {
                addLine("[link with $lab dropped] (now ${neighbors.size} link${if (neighbors.size == 1) "" else "s"})")
                for (st in streams.values.toList()) {
                    st.pruned.remove(endpointId)
                    if (!st.isRoot && st.parent == endpointId) heal(st, "parent $lab dropped")
                }
            } else {
                log("closed link to $lab (it was not fully linked)")
            }
            updateStatus()
        }
    }

    // ---------- packets ----------
    // audio: [type 1][streamId 4][frame number 4][hops 1][nameLen 1][name][mu-law audio]
    // prune/graft: [type 1][streamId 4]

    private val hopsOffset = 9

    private fun encode(sid: Int, seq: Int, hops: Int, sender: String, audio: ByteArray): ByteArray {
        val nameBytes = sender.toByteArray().let { it.copyOf(minOf(it.size, 30)) }
        val buf = ByteBuffer.allocate(11 + nameBytes.size + audio.size)
        buf.put(tAudio.toByte())
        buf.putInt(sid)
        buf.putInt(seq)
        buf.put(hops.toByte())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(audio)
        return buf.array()
    }

    private fun sendControl(ep: String, type: Int, sid: Int) {
        val b = ByteBuffer.allocate(5)
        b.put(type.toByte())
        b.putInt(sid)
        client.sendPayload(ep, Payload.fromBytes(b.array()))
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty()) return
            try {
                when (bytes[0].toInt() and 0xFF) {
                    tAudio -> handleAudio(bytes, endpointId)
                    tPrune, tGraft -> handleControl(bytes, endpointId)
                }
            } catch (e: Exception) {
                log("bad packet from ${label(endpointId)}: ${e.message}")
            }
        }

        // A sent frame has left the queue: free a slot for that link and record how long it took
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            val entry = inFlight.remove(update.payloadId) ?: return
            val st = txStats.getOrPut(entry.first) { LinkStats() }
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val ms = SystemClock.elapsedRealtime() - entry.second
                st.done++
                st.sumMs += ms
                if (ms > st.maxMs) st.maxMs = ms
            } else {
                st.failed++
            }
        }
    }

    private fun stateFor(sid: Int): StreamState {
        streams[sid]?.let { return it }
        val st = StreamState(sid, SystemClock.elapsedRealtime())
        streams[sid] = st
        if (streams.size > 3) {
            val victim = streams.values.filter { it !== st && !it.isRoot }.minByOrNull { it.startedAt }
            if (victim != null) streams.remove(victim.sid)
        }
        return st
    }

    private fun handleControl(bytes: ByteArray, from: String) {
        val type = bytes[0].toInt() and 0xFF
        val sid = ByteBuffer.wrap(bytes).getInt(1)
        val st = stateFor(sid)
        if (type == tPrune) {
            st.pruned.add(from)
            log("PRUNE from ${label(from)} for stream ${hex(sid)}: I will not send it to them")
        } else {
            st.pruned.remove(from)
            log("GRAFT from ${label(from)} for stream ${hex(sid)}: I will send it to them again")
        }
    }

    // Choose the neighbour that delivered the stream in the fewest hops as the one parent
    private fun decideParent(st: StreamState) {
        val parent = st.bestHops.keys.filter { neighbors.containsKey(it) }
            .minWithOrNull(compareBy<String>({ st.bestHops[it] ?: 99 }, { st.order[it] ?: 99 })) ?: return
        st.parent = parent
        st.decided = true
        val others = neighbors.keys.filter { it != parent }
        for (ep in others) sendControl(ep, tPrune, st.sid)
        val heard = st.bestHops.entries.joinToString(", ") { "${label(it.key)}=${it.value}" }
        log("TREE stream ${hex(st.sid)}: parent ${label(parent)} (heard hop counts: $heard); told ${others.size} other neighbour(s) not to send it to me: ${if (others.isEmpty()) "-" else others.joinToString(", ") { label(it) }}")
    }

    // Lost the parent: ask every neighbour to send the stream again, then choose again
    private fun heal(st: StreamState, why: String) {
        val now = SystemClock.elapsedRealtime()
        log("HEAL stream ${hex(st.sid)}: $why. Asking ${neighbors.size} neighbour(s) to send it to me again")
        st.parent = null
        st.decided = false
        st.bestHops.clear()
        st.order.clear()
        st.startedAt = now
        st.lastGraftAt = now
        st.lastNewFrameAt = now
        for (ep in neighbors.keys) sendControl(ep, tGraft, st.sid)
    }

    // The relay rule, per audio frame: forward it onward (only to neighbours that want it), then play it
    private fun handleAudio(bytes: ByteArray, from: String) {
        val buf = ByteBuffer.wrap(bytes)
        buf.get()                                    // type
        val sid = buf.int
        val seq = buf.int
        val hops = buf.get().toInt() and 0xFF
        val nameLen = buf.get().toInt() and 0xFF
        val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
        val audio = ByteArray(buf.remaining()); buf.get(audio)

        val st = stateFor(sid)
        if (st.isRoot) { rxStale++; return }         // our own stream coming back to us

        val now = SystemClock.elapsedRealtime()
        val recvHops = hops + 1
        val prev = st.bestHops[from]
        if (prev == null) st.order[from] = st.order.size
        if (prev == null || recvHops < prev) st.bestHops[from] = recvHops
        if (!st.decided && now - st.startedAt >= decideAfterMs) decideParent(st)

        // Only accept frames newer than the last one: drops duplicates (loops) and late frames
        val last = lastSeq[sid]
        if (last != null && seq <= last) {
            rxStale++
            rxDup[from] = (rxDup[from] ?: 0) + 1
            return
        }
        if (last == null) log("new stream ${hex(sid)} from ${String(nameBytes)} via ${label(from)}, $recvHops hop(s)")
        else if (seq > last + 1) rxGaps += seq - last - 1
        lastSeq[sid] = seq
        rxFrames++
        rxNew[from] = (rxNew[from] ?: 0) + 1
        st.lastNewFrameAt = now

        if (sid == gapSid && gapLastAt != 0L) {
            val g = now - gapLastAt
            if (g > rxMaxGap) rxMaxGap = g
        }
        gapSid = sid
        gapLastAt = now

        val forward = bytes.copyOf()
        forward[hopsOffset] = (hops + 1).toByte()
        forwardAudio(forward, from, st)

        if (!isSpeaker) play(sid, String(nameBytes), recvHops, audio)
    }

    // Send to every neighbour except one and except those that pruned us,
    // but never let a slow link build up a backlog
    private fun forwardAudio(packet: ByteArray, except: String?, st: StreamState?) {
        val now = SystemClock.elapsedRealtime()
        inFlight.entries.removeIf { now - it.value.second > 1000 }   // safety: forget stale entries
        for (endpoint in neighbors.keys) {
            if (endpoint == except) continue
            val ls = txStats.getOrPut(endpoint) { LinkStats() }
            if (st != null && st.pruned.contains(endpoint)) { ls.skipped++; continue }
            val waiting = inFlight.values.count { it.first == endpoint }
            if (waiting >= maxInFlight) { dropped++; ls.dropped++; continue }   // link is behind: drop
            val payload = Payload.fromBytes(packet)
            inFlight[payload.id] = Pair(endpoint, now)
            ls.sent++
            client.sendPayload(endpoint, payload)
        }
    }

    // ---------- mu-law (G.711) compression: 16-bit sample <-> 8-bit ----------

    private fun muLawEncode(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > 32635) s = 32635
        s += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun muLawDecode(b: Byte): Int {
        val u = b.toInt().inv() and 0xFF
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val sample = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
        return if ((u and 0x80) != 0) -sample else sample
    }

    // ---------- speaker: mic -> frames ----------

    private fun startBroadcast() {
        if (!running || !isSpeaker || broadcasting) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusView.text = "Microphone permission needed"; return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, pcmFrameBytes * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            statusView.text = "Could not open the microphone"
            log("microphone could not be opened")
            return
        }

        val sid = Random.nextInt()
        lastSeq[sid] = Int.MAX_VALUE        // ignore our own frames if they loop back to us
        val root = stateFor(sid)
        root.isRoot = true
        root.decided = true
        broadcasting = true
        refreshButtons()
        addLine("[you are broadcasting, stream ${hex(sid)}, linked to: ${linkList()}]${if (searching) " (note: still searching)" else ""}")

        Thread {
            val pcm = ByteArray(pcmFrameBytes)
            var seq = 0
            try {
                rec.startRecording()
                while (broadcasting) {
                    var got = 0
                    while (got < pcmFrameBytes && broadcasting) {
                        val n = rec.read(pcm, got, pcmFrameBytes - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < pcmFrameBytes) break
                    val audio = ByteArray(frameSamples)
                    for (i in 0 until frameSamples) {
                        val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                        audio[i] = muLawEncode(sample)
                    }
                    val packet = encode(sid, seq++, 0, myName, audio)
                    handler.post { forwardAudio(packet, null, root) }   // send from the main thread
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }.start()
    }

    private fun stopBroadcast() {
        if (!broadcasting) return
        broadcasting = false
        refreshButtons()
        addLine("[broadcast stopped]")
    }

    // ---------- listener: frames -> speaker ----------

    private fun startTrack(sid: Int, sender: String, hops: Int) {
        stopTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, sampleRate)          // about 0.5 s buffer caps the delay
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        curSid = sid
        framesQueued = 0
        framesWritten = 0
        playbackOverflow = 0
        trackStarted = false
        addLine("$sender is speaking ($hops hop${if (hops == 1) "" else "s"})")
    }

    private fun play(sid: Int, sender: String, hops: Int, audio: ByteArray) {
        if (track == null || sid != curSid) startTrack(sid, sender, hops)
        val t = track ?: return
        val pcm = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val s = muLawDecode(audio[i])
            pcm[2 * i] = (s and 0xFF).toByte()
            pcm[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val w = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        if (w > 0) framesWritten += w / 2
        if (w < pcm.size) playbackOverflow++
        framesQueued++
        if (!trackStarted && framesQueued >= prebufferFrames) {
            t.play()
            trackStarted = true
            log("playback started")
        }
        handler.removeCallbacks(endRunnable)
        handler.postDelayed(endRunnable, 1500)         // no frames for 1.5 s = speaker stopped
    }

    private fun stopTrack() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    // shows a line on the main screen and records it in the log
    private fun addLine(line: String) {
        chatView.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        log(line)
    }
}
