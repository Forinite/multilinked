package com.example.multilinked

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.random.Random

/**
 * multilinked: offline chain-relay LIVE voice.
 * One phone is the SPEAKER: it records the mic in 40 ms frames and sends each frame
 * to its neighbours. Every phone forwards each frame to its other neighbours and
 * (unless it is the speaker) plays it straight away. Same relay rule as before,
 * but the "message" is a tiny slice of audio, and late/duplicate frames are dropped.
 */
class MainActivity : Activity() {

    private val serviceId = "com.example.multilinked"
    private val myId = UUID.randomUUID().toString().take(6)
    private lateinit var client: ConnectionsClient

    private val neighbors = mutableMapOf<String, String>()    // endpointId -> device name
    private val pending = mutableMapOf<String, String>()
    private var running = false
    private var myName = ""
    private var allowed = setOf<String>()                     // empty = link with anyone
    private var isSpeaker = false

    // audio: 8 kHz, 16-bit, mono. 40 ms = 320 samples = 640 bytes (16 KB/s per link)
    private val sampleRate = 8000
    private val frameBytes = 640
    private val prebufferFrames = 4                           // ~160 ms cushion before playing

    // speaker side
    @Volatile private var broadcasting = false

    // relay + listener side
    private val lastSeq = HashMap<Int, Int>()                 // streamId -> newest frame number seen
    private var track: AudioTrack? = null
    private var curSid = 0
    private var framesQueued = 0
    private var trackStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private val endRunnable = Runnable {
        if (track != null) { stopTrack(); addLine("[speaker stopped]") }
    }

    private lateinit var nameInput: EditText
    private lateinit var allowedInput: EditText
    private lateinit var roleBtn: Button
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var talkBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = Nearby.getConnectionsClient(this)

        val root = LinearLayout(this).apply {
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
            text = "Start"
            setOnClickListener { if (running) stopNearby() else requestPermsThenStart() }
        }
        statusView = TextView(this).apply { text = "Stopped"; setPadding(0, dp(6), 0, dp(6)) }
        chatView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(chatView) }
        talkBtn = Button(this).apply {
            textSize = 18f
            setOnClickListener { if (broadcasting) stopBroadcast() else startBroadcast() }
        }

        root.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(allowedInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(roleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(talkBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(100)))
        setContentView(root)
        refreshButtons()
    }

    override fun onDestroy() {
        if (running) stopNearby()
        super.onDestroy()
    }

    private fun refreshButtons() {
        roleBtn.isEnabled = !running
        roleBtn.text = if (isSpeaker) "Role: SPEAKER (tap to change)" else "Role: Listener (tap to change)"
        talkBtn.isEnabled = running && isSpeaker
        talkBtn.text = when {
            !isSpeaker -> "Listening (only the speaker can talk)"
            broadcasting -> "Stop broadcasting"
            else -> "Start broadcasting"
        }
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
        if (missing.isEmpty()) startNearby() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startNearby()
        else statusView.text = "Permissions denied. Allow them in Settings and press Start again."
    }

    // ---------- start / stop ----------

    private fun startNearby() {
        myName = nameInput.text.toString().trim().ifEmpty { "?" }
        allowed = allowedInput.text.toString().split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising("$myId|$myName", serviceId, connectionCallback, adv)
            .addOnFailureListener { statusView.text = "Advertising failed: ${it.message}" }

        val disc = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, discoveryCallback, disc)
            .addOnFailureListener { statusView.text = "Discovery failed: ${it.message}" }

        running = true
        toggle.text = "Stop"
        nameInput.isEnabled = false
        allowedInput.isEnabled = false
        refreshButtons()
        updateStatus()
    }

    private fun stopNearby() {
        stopBroadcast()
        handler.removeCallbacks(endRunnable)
        stopTrack()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        neighbors.clear()
        pending.clear()
        running = false
        toggle.text = "Start"
        nameInput.isEnabled = true
        allowedInput.isEnabled = true
        refreshButtons()
        statusView.text = "Stopped"
    }

    private fun updateStatus() {
        val n = if (neighbors.isEmpty()) "none yet" else neighbors.values.joinToString(", ")
        statusView.text = "Running as $myName | direct links: $n"
    }

    // ---------- discovery + connections ----------

    private fun parse(s: String): Pair<String, String>? {
        val i = s.indexOf('|')
        return if (i < 0) null else Pair(s.substring(0, i), s.substring(i + 1))
    }

    private fun isAllowed(name: String) = allowed.isEmpty() || allowed.contains(name.trim().lowercase())

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (remoteId, remoteName) = parse(info.endpointName) ?: return
            if (!isAllowed(remoteName)) return
            if (neighbors.containsKey(endpointId) || pending.containsKey(endpointId)) return
            if (myId < remoteId) {
                client.requestConnection("$myId|$myName", endpointId, connectionCallback)
                    .addOnFailureListener { statusView.text = "Connect failed: ${it.message}" }
            }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parsed = parse(info.endpointName)
            if (parsed == null || !isAllowed(parsed.second)) {
                client.rejectConnection(endpointId)
                return
            }
            pending[endpointId] = parsed.second
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pending.remove(endpointId)
            if (result.status.isSuccess && name != null) {
                neighbors[endpointId] = name
                addLine("[linked with $name]")
            }
            updateStatus()
        }

        override fun onDisconnected(endpointId: String) {
            val name = neighbors.remove(endpointId)
            if (name != null) addLine("[link with $name dropped]")
            updateStatus()
        }
    }

    // ---------- packets: [streamId 4][frame number 4][hops 1][nameLen 1][name][pcm audio] ----------

    private val hopsOffset = 8

    private fun encode(sid: Int, seq: Int, hops: Int, sender: String, pcm: ByteArray): ByteArray {
        val nameBytes = sender.toByteArray().let { it.copyOf(minOf(it.size, 30)) }
        val buf = ByteBuffer.allocate(10 + nameBytes.size + pcm.size)
        buf.putInt(sid)
        buf.putInt(seq)
        buf.put(hops.toByte())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(pcm)
        return buf.array()
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try { handleIncoming(bytes, endpointId) } catch (_: Exception) {}
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // The relay rule, per audio frame: forward it onward, then play it here
    private fun handleIncoming(bytes: ByteArray, from: String) {
        val buf = ByteBuffer.wrap(bytes)
        val sid = buf.int
        val seq = buf.int
        val hops = buf.get().toInt() and 0xFF
        val nameLen = buf.get().toInt() and 0xFF
        val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
        val pcm = ByteArray(buf.remaining()); buf.get(pcm)

        // Only accept frames newer than the last one: drops duplicates (loops) and late frames
        val last = lastSeq[sid]
        if (last != null && seq <= last) return
        lastSeq[sid] = seq

        val forward = bytes.copyOf()
        forward[hopsOffset] = (hops + 1).toByte()
        sendToNeighbors(forward, from)

        if (!isSpeaker) play(sid, String(nameBytes), hops + 1, pcm)
    }

    private fun sendToNeighbors(packet: ByteArray, except: String?) {
        for (endpoint in neighbors.keys) {
            if (endpoint != except) client.sendPayload(endpoint, Payload.fromBytes(packet))
        }
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
            maxOf(minBuf, frameBytes * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            statusView.text = "Could not open the microphone"
            return
        }

        val sid = Random.nextInt()
        lastSeq[sid] = Int.MAX_VALUE        // ignore our own frames if they loop back to us
        broadcasting = true
        refreshButtons()
        addLine("[you are broadcasting]")

        Thread {
            val buf = ByteArray(frameBytes)
            var seq = 0
            try {
                rec.startRecording()
                while (broadcasting) {
                    var got = 0
                    while (got < frameBytes && broadcasting) {
                        val n = rec.read(buf, got, frameBytes - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < frameBytes) break
                    val packet = encode(sid, seq++, 0, myName, buf)
                    handler.post { sendToNeighbors(packet, null) }   // send from the main thread
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
        trackStarted = false
        addLine("$sender is speaking ($hops hop${if (hops == 1) "" else "s"})")
    }

    private fun play(sid: Int, sender: String, hops: Int, pcm: ByteArray) {
        if (track == null || sid != curSid) startTrack(sid, sender, hops)
        val t = track ?: return
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        framesQueued++
        if (!trackStarted && framesQueued >= prebufferFrames) {
            t.play()
            trackStarted = true
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

    private fun addLine(line: String) {
        chatView.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
