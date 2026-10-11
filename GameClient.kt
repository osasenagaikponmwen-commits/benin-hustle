package com.nagi.hustle

import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Every phone, including the host's own, plays through one of these. */
class GameClient {

    var onState: ((JSONObject) -> Unit)? = null
    var onMsg: ((String, Boolean) -> Unit)? = null
    var onPos: ((JSONObject) -> Unit)? = null
    var onTp: ((Float, Float, Long) -> Unit)? = null
    var onEnc: ((String, String, List<String>, Int) -> Unit)? = null
    var onEncEnd: (() -> Unit)? = null
    var onReveal: ((Int, String) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null

    @Volatile
    private var closed = false
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var out: PrintWriter? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun connect(ip: String, name: String, cls: Int = 1) {
        thread(isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, GAME_PORT), 5000)
                s.tcpNoDelay = true
                socket = s
                out = PrintWriter(s.getOutputStream(), true)
                send(JSONObject().put("t", "join").put("name", name).put("cls", cls))
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (!closed) {
                    val line = reader.readLine() ?: break
                    val o = JSONObject(line)
                    when (o.optString("t")) {
                        "state" -> onState?.invoke(o)
                        "msg" -> onMsg?.invoke(o.optString("m"), o.optBoolean("big", false))
                        "pos" -> onPos?.invoke(o)
                        "tp" -> onTp?.invoke(o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optLong("ms", 0L))
                        "enc" -> {
                            val arr = o.optJSONArray("opts")
                            val opts = ArrayList<String>()
                            if (arr != null) for (i in 0 until arr.length()) opts.add(arr.optString(i))
                            onEnc?.invoke(o.optString("title"), o.optString("text"), opts, o.optInt("tl", 30))
                        }
                        "encend" -> onEncEnd?.invoke()
                        "reveal" -> onReveal?.invoke(o.optInt("id", -1), o.optString("name"))
                        "full" -> {
                            closed = true
                            onClosed?.invoke("This room is full or the game has already started.")
                        }
                    }
                }
                if (!closed) {
                    closed = true
                    onClosed?.invoke("Disconnected from the host.")
                }
            } catch (e: Exception) {
                if (!closed) {
                    closed = true
                    onClosed?.invoke("Could not connect. Make sure you are on the same Wi-Fi as the host.")
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Sends on a background thread, because Android forbids network calls on the screen thread. */
    fun send(o: JSONObject) {
        val text = o.toString()
        try {
            sender.execute {
                val w = out
                if (w != null) {
                    w.println(text)
                    w.flush()
                }
            }
        } catch (e: Exception) {
        }
    }

    fun close() {
        closed = true
        onState = null
        onMsg = null
        onPos = null
        onTp = null
        onEnc = null
        onEncEnd = null
        onReveal = null
        onClosed = null
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        sender.shutdownNow()
    }
}

/** Listens for hosts shouting that they have a room open. */
class RoomFinder(private val onRooms: (List<Room>) -> Unit) {

    class Room(val ip: String, val name: String, val count: Int, val seen: Long)

    @Volatile
    private var running = false
    @Volatile
    private var sock: DatagramSocket? = null

    fun start() {
        running = true
        thread(isDaemon = true) {
            val rooms = LinkedHashMap<String, Room>()
            var lastSig = "?"
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                s.soTimeout = 1000
                sock = s
                val buf = ByteArray(256)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        val ip = p.address.hostAddress
                        if (parts.size >= 4 && parts[0] == "EKOHUSTLE" && ip != null) {
                            rooms[ip] = Room(ip, parts[1], parts[3].toIntOrNull() ?: 0, SystemClock.elapsedRealtime())
                        }
                    } catch (e: SocketTimeoutException) {
                        // nothing heard this second
                    }
                    val now = SystemClock.elapsedRealtime()
                    rooms.values.removeAll { now - it.seen > 4000 }
                    val list = rooms.values.toList()
                    val sig = list.joinToString(",") { it.ip + it.name + it.count }
                    if (sig != lastSig) {
                        lastSig = sig
                        onRooms(list)
                    }
                }
            } catch (e: Exception) {
                // could not listen; the person can still type the host's address
            } finally {
                try {
                    sock?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            sock?.close()
        } catch (e: Exception) {
        }
    }
}
