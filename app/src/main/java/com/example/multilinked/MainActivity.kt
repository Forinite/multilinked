package com.example.multilinked

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
import org.json.JSONObject
import java.util.UUID

/**
 * multilinked: minimal offline chain-relay chat.
 * Every phone advertises + discovers, links to nearby phones (Nearby Connections,
 * P2P_CLUSTER), and relays each new message to all its other neighbours.
 */
class MainActivity : Activity() {

    private val serviceId = "com.example.multilinked"
    private val myId = UUID.randomUUID().toString().take(6)   // unique per app run
    private lateinit var client: ConnectionsClient

    private val neighbors = mutableMapOf<String, String>()    // endpointId -> device name
    private val pending = mutableMapOf<String, String>()      // endpointId -> device name
    private val seen = HashSet<String>()                      // message ids already handled
    private var running = false
    private var myName = ""
    private var allowed = setOf<String>()                     // empty = link with anyone

    private lateinit var nameInput: EditText
    private lateinit var allowedInput: EditText
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var msgInput: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        toggle = Button(this).apply {
            text = "Start"
            setOnClickListener { if (running) stopNearby() else requestPermsThenStart() }
        }
        statusView = TextView(this).apply { text = "Stopped" ; setPadding(0, dp(6), 0, dp(6)) }
        chatView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(chatView) }
        msgInput = EditText(this).apply {
            hint = "Type a message"; setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener { sendLocal() }
        }
        val sendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(msgInput); addView(sendBtn)
        }

        root.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(allowedInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(sendRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    override fun onDestroy() {
        if (running) stopNearby()
        super.onDestroy()
    }

    // ---------- permissions ----------

    private fun neededPermissions(): Array<String> {
        val l = mutableListOf<String>()
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
        updateStatus()
    }

    private fun stopNearby() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        neighbors.clear()
        pending.clear()
        running = false
        toggle.text = "Start"
        nameInput.isEnabled = true
        allowedInput.isEnabled = true
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
            // Both sides discover each other; only the lower id starts the connection
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

    // ---------- messages ----------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try { handleIncoming(JSONObject(String(bytes)), endpointId) } catch (_: Exception) {}
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // The relay rule: show it, then forward to every neighbour except the sender
    private fun handleIncoming(msg: JSONObject, from: String) {
        val id = msg.getString("id")
        if (!seen.add(id)) return                      // already handled: drop
        val hops = msg.getInt("hops") + 1
        msg.put("hops", hops)
        addLine("${msg.getString("sender")} ($hops hop${if (hops == 1) "" else "s"}): ${msg.getString("text")}")
        sendToNeighbors(msg, from)
    }

    private fun sendLocal() {
        val text = msgInput.text.toString().trim()
        if (text.isEmpty() || !running) return
        val msg = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("sender", myName)
            .put("text", text)
            .put("hops", 0)
        seen.add(msg.getString("id"))
        addLine("me: $text")
        sendToNeighbors(msg, null)
        msgInput.setText("")
    }

    private fun sendToNeighbors(msg: JSONObject, except: String?) {
        val bytes = msg.toString().toByteArray()
        for (endpoint in neighbors.keys) {
            if (endpoint != except) client.sendPayload(endpoint, Payload.fromBytes(bytes))
        }
    }

    private fun addLine(line: String) {
        chatView.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
