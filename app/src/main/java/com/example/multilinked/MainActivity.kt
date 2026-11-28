package com.example.multilinked

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
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
import androidx.core.content.FileProvider
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
 * Network shape: always a TREE. Every phone carries a group id. Two phones that are
 * already in the same group never link (that link would close a loop). When two
 * groups link they merge into one group id; when a link drops, each side gets a
 * fresh group id so the halves can rejoin later.
 * Audio: because the network is a tree, a frame is simply passed on to every
 * neighbour except the one it came from. No duplicates, no routing decisions.
 * Playback: the phone keeps its audio buffer near 240 ms. If it falls behind it
 * skips frames to catch up; if it runs dry it waits for a cushion to rebuild.
 */
class MainActivity : Activity() {

    private val serviceId = "com.example.multilinked"
    private val myId = UUID.randomUUID().toString().take(6)
    private var groupId = myId                                // changes as groups merge / split
    private lateinit var client: ConnectionsClient

    private val neighbors = mutableMapOf<String, String>()    // endpointId -> device name
    private val idOf = HashMap<String, String>()              // endpointId -> device id (for logs)
    private val pending = mutableMapOf<String, String>()      // endpointId -> device name (connecting)
    private val pendingSince = mutableMapOf<String, Long>()
    private val loopLinks = HashSet<String>()                 // links being closed because they made a loop
    private var running = false
    private var searching = false
    private var myName = ""
    private var allowed = setOf<String>()                     // empty = link with anyone
    private var isSpeaker = false
    private val searchSeconds = 30
    private val maxLinks = 5

    // packet types
    private val tAudio = 1
    private val tHello = 2                                    // "my group is X" (sent right after linking)
    private val tGroup = 3                                    // "the group is now X" (flooded through the tree)

    // audio: 480 samples (60 ms) -> 480 mu-law bytes on the wire, 960 bytes of PCM16 locally
    private val sampleRate = 8000
    private val frameSamples = 480
    private val frameMs = 60L
    private val pcmFrameBytes = frameSamples * 2

    // playback delay control
    private val prebufferFrames = 4                           // 240 ms cushion before (re)starting playback
    private val maxBufferMs = 400L                            // above this, skip frames to catch up

    // send queue limit per link
    private val maxInFlight = 4
    private val inFlight = HashMap<Long, Pair<String, Long>>()  // payloadId -> (endpoint, time sent)
    private var dropped = 0

    // statistics, summarised in the log every ~6 s
    private class LinkStats {
        var sent = 0; var dropped = 0; var done = 0; var failed = 0
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
    private var driftInit = false
    private var driftSid = 0
    private var driftBaseAt = 0L
    private var driftBaseSeq = 0
    private var driftNow = 0L
    private var driftMin = 0L
    private var driftMax = 0L
    private var spkDelayMax = 0L
    private var tickCount = 0

    @Volatile private var broadcasting = false

    private val lastSeq = HashMap<Int, Int>()                 // streamId -> newest frame number seen
    private var track: AudioTrack? = null
    private var curSid = 0
    private var framesQueued = 0
    private var framesWritten = 0L
    private var playbackOverflow = 0
    private var rebuffers = 0
    private var skippedForDelay = 0
    private var trackStarted = false
    private var firstPlayLogged = false
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
        val shareBtn = Button(this).apply { text = "Text"; setOnClickListener { shareLog() } }
        val fileBtn = Button(this).apply { text = "File"; setOnClickListener { shareLogFile() } }
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
        logPanel.addView(row(backBtn, shareBtn, fileBtn, clearBtn), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        logPanel.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val root = FrameLayout(this)
        root.addView(mainView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(logPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        loadPreviousLog()
        log("App opened. ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), Google Play services ${playServicesVersion()}, device id $myId. Build: tree-only links v1")
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

    private fun logHeader(): String =
        "multilinked log from \"${nameInput.text}\"/$myId (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n"

    private fun shareLog() {
        val text = logHeader() + logLines.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "multilinked log ${nameInput.text}")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000))
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    // Shares the whole log as a text file (no copy-paste size limit)
    private fun shareLogFile() {
        try {
            val tag = nameInput.text.toString().filter { it.isLetterOrDigit() }.ifEmpty { "phone" }
            val f = File(filesDir, "multilinked-log-$tag.txt")
            f.writeText(logHeader() + logLines.joinToString("\n") + "\n")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "multilinked log $tag")
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log file"))
        } catch (e: Exception) {
            log("could not share the log file: ${e.message}")
            statusView.text = "Could not share the log file: ${e.message}"
        }
    }

    private fun describe(e: Exception): String =
        if (e is ApiException) "code ${e.statusCode} (${ConnectionsStatusCodes.getStatusCodeString(e.statusCode)})"
        else (e.message ?: e.toString())

    private fun playServicesVersion(): String = try {
        packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

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

    private fun logStats() {
        val parts = ArrayList<String>()
        for ((ep, st) in txStats) {
            if (st.sent + st.dropped == 0) continue
            val avg = if (st.done > 0) st.sumMs / st.done else 0
            parts.add("to ${label(ep)}: sent ${st.sent}, dropped ${st.dropped}, failed ${st.failed}, send time avg ${avg}ms max ${st.maxMs}ms")
        }
        txStats.clear()
        if (rxFrames + rxGaps + rxStale > 0) {
            val by = (rxNew.keys + rxDup.keys).toSet().joinToString(", ") { "${label(it)} ${rxNew[it] ?: 0} new/${rxDup[it] ?: 0} dup" }
            parts.add("received $rxFrames new frames ($rxGaps missing, $rxStale duplicate/late) by neighbour: $by; longest gap between new frames ${rxMaxGap}ms")
            val warn = rxDup.filter { it.value > 3 }.keys
            if (warn.isNotEmpty()) parts.add("WARNING: duplicate frames from ${warn.joinToString(", ") { label(it) }} (a loop may exist)")
            parts.add("arrival timing vs first frame: now ${driftNow}ms, window ${driftMin}..${driftMax}ms (rising = delay building up in transit)")
            rxFrames = 0; rxGaps = 0; rxStale = 0; rxMaxGap = 0
            rxNew.clear(); rxDup.clear()
            driftMin = driftNow; driftMax = driftNow
        }
        if (broadcasting) {
            parts.add("speaker: capture-to-send delay max ${spkDelayMax}ms")
            spkDelayMax = 0
        }
        val t = track
        if (t != null) {
            val bufMs = if (trackStarted) bufferedMs(t) else 0
            parts.add("playback: buffered about ${bufMs}ms, device underruns ${t.underrunCount}, rebuffers $rebuffers, frames skipped to cut delay $skippedForDelay, frames lost to a full buffer $playbackOverflow")
        }
        if (parts.isNotEmpty()) log("STATS group $groupId links[${linkList()}] " + parts.joinToString(" | "))
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
        groupId = myId
        dropped = 0
        tickCount = 0
        nameInput.isEnabled = false
        allowedInput.isEnabled = false
        log("SESSION start: name \"$myName\"/$myId, group $groupId, role ${if (isSpeaker) "SPEAKER" else "listener"}, allowed ${if (allowed.isEmpty()) "anyone" else allowed.joinToString(",")}")
        logRadioState()
        refreshButtons()
        updateStatus()
        handler.postDelayed(tick, 2000)
        startSearch()
    }

    private fun stopSession() {
        stopBroadcast()
        stopSearch("session stopped")
        handler.removeCallbacks(endRunnable)
        handler.removeCallbacks(tick)
        stopTrack()
        running = false                     // set first so closing links are not logged as problems
        client.stopAllEndpoints()
        neighbors.clear()
        idOf.clear()
        pending.clear()
        pendingSince.clear()
        loopLinks.clear()
        inFlight.clear()
        txStats.clear()
        rxNew.clear(); rxDup.clear()
        groupId = myId
        nameInput.isEnabled = true
        allowedInput.isEnabled = true
        log("SESSION stopped")
        refreshButtons()
        statusView.text = "Stopped"
    }

    // The name other phones read before connecting: "deviceId|groupId|name"
    private fun advName(): String = "$myId|$groupId|$myName"

    // Advertise + discover together. Stops at the first new link or after searchSeconds.
    private fun startSearch() {
        if (!running || searching) return
        searching = true
        refreshButtons()
        log("SEARCH start as group $groupId (auto-stops at first new link or after $searchSeconds s). Both phones must be searching to find each other.")

        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(advName(), serviceId, connectionCallback, adv)
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
        statusView.text = "Running as $myName | group $groupId | links: $n | ${if (searching) "SEARCHING" else "not searching"} | frames dropped: $dropped"
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

    // ---------- groups (keeps the network a tree) ----------

    private class Adv(val id: String, val group: String, val name: String)

    private fun parse(s: String): Adv? {
        val p = s.split("|", limit = 3)
        return if (p.size < 3) null else Adv(p[0], p[1], p[2])
    }

    private fun isAllowed(name: String) = allowed.isEmpty() || allowed.contains(name.trim().lowercase())

    private fun sendControl(ep: String, type: Int, text: String) {
        val b = text.toByteArray()
        val buf = ByteBuffer.allocate(2 + b.size)
        buf.put(type.toByte())
        buf.put(b.size.toByte())
        buf.put(b)
        client.sendPayload(ep, Payload.fromBytes(buf.array()))
    }

    // Adopt a new group id and pass it on to everyone except the phone that told us
    private fun setGroup(g: String, why: String, except: String?) {
        if (g == groupId) return
        log("GROUP $groupId -> $g ($why)")
        groupId = g
        for (ep in neighbors.keys) if (ep != except) sendControl(ep, tGroup, g)
        updateStatus()
    }

    // Right after linking: compare groups. The side with the larger id adopts the smaller one.
    private fun handleHello(from: String, theirGroup: String) {
        log("HELLO from ${label(from)}: their group $theirGroup, mine $groupId")
        if (theirGroup == groupId) {
            // Same group: this link closes a loop. Keep the network a tree by closing it.
            loopLinks.add(from)
            log("LOOP: ${label(from)} is already in my group, so this link makes a loop. Closing it.")
            if (myId > (idOf[from] ?: "")) client.disconnectFromEndpoint(from)
        } else if (theirGroup < groupId) {
            setGroup(theirGroup, "merged with ${label(from)}", from)
        }
    }

    private fun handleGroup(from: String, g: String) {
        if (g != groupId) setGroup(g, "told by ${label(from)}", from)
    }

    // ---------- discovery + connections ----------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val adv = parse(info.endpointName)
            if (adv == null) { log("found $endpointId with unreadable name \"${info.endpointName}\": ignored"); return }
            idOf[endpointId] = adv.id
            val who = "\"${adv.name}\"/${adv.id} (group ${adv.group})"
            when {
                !isAllowed(adv.name) ->
                    log("found $who: IGNORED, not in my allowed list (${allowed.joinToString(",")})")
                neighbors.containsKey(endpointId) ->
                    log("found $who: already linked")
                pending.containsKey(endpointId) ->
                    log("found $who: connection already in progress")
                adv.group == groupId ->
                    log("found $who: SKIPPED, already in my group, linking would make a loop")
                neighbors.size >= maxLinks ->
                    log("found $who: SKIPPED, I already have $maxLinks links")
                myId < adv.id -> {
                    log("found $who: I start the connection")
                    pending[endpointId] = adv.name
                    pendingSince[endpointId] = SystemClock.elapsedRealtime()
                    client.requestConnection(advName(), endpointId, connectionCallback)
                        .addOnFailureListener {
                            pending.remove(endpointId); pendingSince.remove(endpointId)
                            log("request to $who FAILED: ${describe(it)}")
                        }
                }
                else -> log("found $who: waiting for them to start the connection (they must be searching too)")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            log("endpoint lost: ${label(endpointId)}")
        }
    }

    private fun rejectRequest(endpointId: String, from: String, why: String) {
        log("connection request from $from: REJECTED ($why)")
        pending.remove(endpointId)
        pendingSince.remove(endpointId)
        client.rejectConnection(endpointId)
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val adv = parse(info.endpointName)
            if (adv == null) { rejectRequest(endpointId, "\"${info.endpointName}\"", "unreadable name"); return }
            idOf[endpointId] = adv.id
            val who = "\"${adv.name}\"/${adv.id} (group ${adv.group})"
            if (!isAllowed(adv.name)) { rejectRequest(endpointId, who, "not in my allowed list"); return }
            if (adv.group == groupId) { rejectRequest(endpointId, who, "same group as mine, would make a loop"); return }
            if (neighbors.size >= maxLinks) { rejectRequest(endpointId, who, "I already have $maxLinks links"); return }
            log("connection with $who initiated: accepting")
            pending[endpointId] = adv.name
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
                sendControl(endpointId, tHello, groupId)
                stopSearch("linked with ${label(endpointId)}")
            } else {
                log("connection with ${name ?: label(endpointId)} FAILED: code $code (${ConnectionsStatusCodes.getStatusCodeString(code)})")
            }
            updateStatus()
        }

        override fun onDisconnected(endpointId: String) {
            if (!running) return
            val lab = label(endpointId)
            val name = neighbors.remove(endpointId)
            if (name != null) {
                addLine("[link with $lab dropped] (now ${neighbors.size} link${if (neighbors.size == 1) "" else "s"})")
                if (loopLinks.remove(endpointId)) {
                    log("that was a redundant loop link: the network is still one group")
                } else {
                    // The network just split in two. This side gets its own group id so the halves can rejoin later.
                    val fresh = "$myId${Random.nextInt(256).toString(16)}"
                    setGroup(fresh, "link with $lab dropped, my side needs its own group", null)
                }
            } else {
                log("closed link to $lab (it was not fully linked)")
            }
            updateStatus()
        }
    }

    // ---------- packets ----------
    // audio: [type 1][streamId 4][frame number 4][hops 1][nameLen 1][name][mu-law audio]
    // hello/group: [type 1][length 1][group id text]

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

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.size < 2) return
            try {
                when (bytes[0].toInt() and 0xFF) {
                    tAudio -> handleAudio(bytes, endpointId)
                    tHello -> handleHello(endpointId, String(bytes, 2, bytes[1].toInt() and 0xFF))
                    tGroup -> handleGroup(endpointId, String(bytes, 2, bytes[1].toInt() and 0xFF))
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

    // The relay rule: pass each new frame to every neighbour except the one it came from, then play it
    private fun handleAudio(bytes: ByteArray, from: String) {
        val buf = ByteBuffer.wrap(bytes)
        buf.get()                                    // type
        val sid = buf.int
        val seq = buf.int
        val hops = buf.get().toInt() and 0xFF
        val nameLen = buf.get().toInt() and 0xFF
        val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
        val audio = ByteArray(buf.remaining()); buf.get(audio)

        // The network is a tree so duplicates should not happen; if they do, drop them
        val last = lastSeq[sid]
        if (last != null && seq <= last) {
            rxStale++
            rxDup[from] = (rxDup[from] ?: 0) + 1
            return
        }
        val now = SystemClock.elapsedRealtime()
        val recvHops = hops + 1
        if (last == null) log("new stream from ${String(nameBytes)} via ${label(from)}, $recvHops hop(s)")
        else if (seq > last + 1) rxGaps += seq - last - 1
        lastSeq[sid] = seq
        rxFrames++
        rxNew[from] = (rxNew[from] ?: 0) + 1

        // how late is this frame compared with when it "should" have arrived?
        if (!driftInit || sid != driftSid) {
            driftInit = true; driftSid = sid; driftBaseAt = now; driftBaseSeq = seq
            driftNow = 0; driftMin = 0; driftMax = 0
        } else {
            driftNow = (now - driftBaseAt) - (seq - driftBaseSeq).toLong() * frameMs
            if (driftNow < driftMin) driftMin = driftNow
            if (driftNow > driftMax) driftMax = driftNow
        }
        if (sid == gapSid && gapLastAt != 0L) {
            val g = now - gapLastAt
            if (g > rxMaxGap) rxMaxGap = g
        }
        gapSid = sid
        gapLastAt = now

        val forward = bytes.copyOf()
        forward[hopsOffset] = (hops + 1).toByte()
        forwardAudio(forward, from)

        if (!isSpeaker) play(sid, String(nameBytes), recvHops, audio)
    }

    // Send to every neighbour except one, but never let a slow link build up a backlog
    private fun forwardAudio(packet: ByteArray, except: String?) {
        val now = SystemClock.elapsedRealtime()
        inFlight.entries.removeIf { now - it.value.second > 1000 }   // safety: forget stale entries
        for (endpoint in neighbors.keys) {
            if (endpoint == except) continue
            val ls = txStats.getOrPut(endpoint) { LinkStats() }
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
        lastSeq[sid] = Int.MAX_VALUE        // ignore our own frames if they ever loop back to us
        spkDelayMax = 0
        broadcasting = true
        refreshButtons()
        addLine("[you are broadcasting, stream ${Integer.toHexString(sid)}, group $groupId, linked to: ${linkList()}]${if (searching) " (note: still searching)" else ""}")

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
                    val captured = SystemClock.elapsedRealtime()
                    handler.post {                                     // send from the main thread
                        val wait = SystemClock.elapsedRealtime() - captured
                        if (wait > spkDelayMax) spkDelayMax = wait
                        forwardAudio(packet, null)
                    }
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
        val size = maxOf(minBuf, sampleRate)          // about 0.5 s of room; we keep far less in it
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
        rebuffers = 0
        skippedForDelay = 0
        trackStarted = false
        firstPlayLogged = false
        addLine("$sender is speaking ($hops hop${if (hops == 1) "" else "s"})")
    }

    // how much audio is waiting to be played, in milliseconds
    private fun bufferedMs(t: AudioTrack): Long {
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (framesWritten - head) * 1000 / sampleRate
    }

    private fun play(sid: Int, sender: String, hops: Int, audio: ByteArray) {
        if (track == null || sid != curSid) startTrack(sid, sender, hops)
        val t = track ?: return

        if (trackStarted) {
            val buffered = bufferedMs(t)
            if (buffered <= 0) {
                // ran dry: pause and wait for a cushion to build up again
                t.pause()
                trackStarted = false
                framesQueued = 0
                rebuffers++
            } else if (buffered > maxBufferMs) {
                // too far behind: skip this frame to bring the delay back down
                skippedForDelay++
                handler.removeCallbacks(endRunnable)
                handler.postDelayed(endRunnable, 1500)
                return
            }
        }

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
            if (!firstPlayLogged) { firstPlayLogged = true; log("playback started") }
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
