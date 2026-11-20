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
import android.os.SystemClock
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
 * multilinked: offline chain-relay LIVE voice (one speaker, everyone else listens).
 * Audio: 8 kHz mono, 60 ms frames, compressed with mu-law to 8 KB/s per link.
 * Each link may have at most [maxInFlight] frames waiting; beyond that frames are
 * dropped, so a slow link gives choppy audio instead of ever-growing delay.
 */
class MainActivity : Activity() {

    private val serviceId = "com.example.multilinked"
    private val myId = UUID.randomUUID().toString().take(6)
    private lateinit var client: ConnectionsClient

    private val neighbors = mutableMapOf<String, String>()    // endpointId -> device name
    private val pending = mutableMapOf<String, String>()
    private var running = false
    private var locked = false
    private var myName = ""
    private var allowed = setOf<String>()                     // empty = link with anyone
    private var isSpeaker = false

    // audio: 480 samples (60 ms) -> 480 mu-law bytes on the wire, 960 bytes of PCM16 locally
    private val sampleRate = 8000
    private val frameSamples = 480
    private val pcmFrameBytes = frameSamples * 2
    private val prebufferFrames = 3                           // ~180 ms cushion before playing

    // send queue limit per link
    private val maxInFlight = 4
    private val inFlight = HashMap<Long, Pair<String, Long>>()  // payloadId -> (endpoint, time sent)
    private var dropped = 0

    @Volatile private var broadcasting = false

    private val lastSeq = HashMap<Int, Int>()                 // streamId -> newest frame number seen
    private var track: AudioTrack? = null
    private var curSid = 0
    private var framesQueued = 0
    private var trackStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private val endRunnable = Runnable {
        if (track != null) { stopTrack(); addLine("[speaker stopped]") }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (running) { updateStatus(); handler.postDelayed(this, 2000) }
        }
    }

    private lateinit var nameInput: EditText
    private lateinit var allowedInput: EditText
    private lateinit var roleBtn: Button
    private lateinit var toggle: Button
    private lateinit var lockBtn: Button
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
        lockBtn = Button(this).apply {
            setOnClickListener { lockLinks() }
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
        root.addView(lockBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        lockBtn.isEnabled = running && !locked
        lockBtn.text = if (locked) "Links locked" else "Stop searching (lock links)"
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
        locked = false
        dropped = 0
        toggle.text = "Stop"
        nameInput.isEnabled = false
        allowedInput.isEnabled = false
        refreshButtons()
        updateStatus()
        handler.postDelayed(tick, 2000)
    }

    // Stop searching/advertising so the radio is used for audio only
    private fun lockLinks() {
        client.stopAdvertising()
        client.stopDiscovery()
        locked = true
        refreshButtons()
        addLine("[search stopped: links locked]")
    }

    private fun stopNearby() {
        stopBroadcast()
        handler.removeCallbacks(endRunnable)
        handler.removeCallbacks(tick)
        stopTrack()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        neighbors.clear()
        pending.clear()
        inFlight.clear()
        running = false
        locked = false
        toggle.text = "Start"
        nameInput.isEnabled = true
        allowedInput.isEnabled = true
        refreshButtons()
        statusView.text = "Stopped"
    }

    private fun updateStatus() {
        val n = if (neighbors.isEmpty()) "none yet" else neighbors.values.joinToString(", ")
        statusView.text = "Running as $myName | links: $n | frames dropped (slow link): $dropped"
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

    // ---------- packets: [streamId 4][frame number 4][hops 1][nameLen 1][name][mu-law audio] ----------

    private val hopsOffset = 8

    private fun encode(sid: Int, seq: Int, hops: Int, sender: String, audio: ByteArray): ByteArray {
        val nameBytes = sender.toByteArray().let { it.copyOf(minOf(it.size, 30)) }
        val buf = ByteBuffer.allocate(10 + nameBytes.size + audio.size)
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
            try { handleIncoming(bytes, endpointId) } catch (_: Exception) {}
        }

        // A sent frame has left the queue: free up a slot for that link
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) {
                inFlight.remove(update.payloadId)
            }
        }
    }

    // The relay rule, per audio frame: forward it onward, then play it here
    private fun handleIncoming(bytes: ByteArray, from: String) {
        val buf = ByteBuffer.wrap(bytes)
        val sid = buf.int
        val seq = buf.int
        val hops = buf.get().toInt() and 0xFF
        val nameLen = buf.get().toInt() and 0xFF
        val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
        val audio = ByteArray(buf.remaining()); buf.get(audio)

        // Only accept frames newer than the last one: drops duplicates (loops) and late frames
        val last = lastSeq[sid]
        if (last != null && seq <= last) return
        lastSeq[sid] = seq

        val forward = bytes.copyOf()
        forward[hopsOffset] = (hops + 1).toByte()
        sendToNeighbors(forward, from)

        if (!isSpeaker) play(sid, String(nameBytes), hops + 1, audio)
    }

    // Send to every neighbour except one, but never let a slow link build up a backlog
    private fun sendToNeighbors(packet: ByteArray, except: String?) {
        val now = SystemClock.elapsedRealtime()
        inFlight.entries.removeIf { now - it.value.second > 1000 }   // safety: forget stale entries
        for (endpoint in neighbors.keys) {
            if (endpoint == except) continue
            val waiting = inFlight.values.count { it.first == endpoint }
            if (waiting >= maxInFlight) { dropped++; continue }       // link is behind: drop this frame
            val payload = Payload.fromBytes(packet)
            inFlight[payload.id] = Pair(endpoint, now)
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
            return
        }

        val sid = Random.nextInt()
        lastSeq[sid] = Int.MAX_VALUE        // ignore our own frames if they loop back to us
        broadcasting = true
        refreshButtons()
        addLine("[you are broadcasting]")

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

    private fun play(sid: Int, sender: String, hops: Int, audio: ByteArray) {
        if (track == null || sid != curSid) startTrack(sid, sender, hops)
        val t = track ?: return
        val pcm = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val s = muLawDecode(audio[i])
            pcm[2 * i] = (s and 0xFF).toByte()
            pcm[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
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
