package com.nagi.hustle

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

const val GAME_PORT = 47002
const val DISCOVERY_PORT = 47001
const val MAX_PLAYERS = 20
const val TOTAL_DAYS = 40
const val RENT_EVERY = 7
const val RENT = 45000L
const val DAY_MS = 30000L
const val START_X = 1600f
const val START_Y = 1760f


class Conn(val socket: Socket, private val out: PrintWriter) {
    var player: Player? = null

    /** Returns false if the connection is broken. */
    fun send(s: String): Boolean {
        synchronized(out) {
            out.println(s)
            out.flush()
            return !out.checkError()
        }
    }
}

/** Addresses used to shout "I am a room" to everyone on the Wi-Fi. */
fun broadcastAddresses(): List<InetAddress> {
    val list = ArrayList<InetAddress>()
    try {
        list.add(InetAddress.getByName("255.255.255.255"))
    } catch (e: Exception) {
    }
    try {
        val ifs = NetworkInterface.getNetworkInterfaces()
        while (ifs != null && ifs.hasMoreElements()) {
            val ni = ifs.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val b = ia.broadcast
                if (b != null) list.add(b)
            }
        }
    } catch (e: Exception) {
    }
    return list
}

/** This phone's own addresses on the local network, to show on the host screen. */
fun localIps(): List<String> {
    val list = ArrayList<String>()
    try {
        val ifs = NetworkInterface.getNetworkInterfaces()
        while (ifs != null && ifs.hasMoreElements()) {
            val ni = ifs.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is java.net.Inet4Address) {
                    val s = a.hostAddress
                    if (s != null) list.add(s)
                }
            }
        }
    } catch (e: Exception) {
    }
    return list
}

/** A question the host has put to a player, waiting for their answer. */
class Pending(val kind: String, var deadline: Long) {
    var a = 0
    var b = 0
    var c = 0
    var other = -1
    var amt = 0L
    var s = ""
}

class Player(val id: Int, var name: String, val cls: Int) {
    private val hc = CLASSES[cls]
    var cash = hc.cash
    var energy = 100
    var food = 80
    var rep = 50
    var know = 0
    var goods = 0
    var bank = 0L
    var acct = hc.acct
    var usd = hc.usd
    var fake = 0
    var housing = homeOf(cls)
    var rent = HOUSING[housing].rent
    var mdone = 0
    var flex = 0
    var x = PLACES[hc.place].x + ((id % 5) - 2) * 36f
    var y = PLACES[hc.place].y + 70f + (id / 5 % 3) * 30f
    var lx = x
    var ly = y
    var busyUntil = 0L
    var busyTotal = 0L
    var busyLabel = ""
    var online = true
    val visits = HashMap<Int, Int>()
    var tickets = 0
    var heat = 0
    var hurt = false
    var warned = false
    var refund = 0L
    var refundDay = 0
    var encCool = 0L
    var lvl = 1
    var pend: Pending? = null

    fun worth(rate: Double): Long {
        val good = max(0, usd - fake)
        return cash + bank + (good * rate).toLong() + rep * 200L + know * 150L + goods * 800L
    }
}


/**
 * The host phone runs this. It keeps the one true copy of the game. Every phone, including the
 * host's own, talks to it over the Wi-Fi.
 */
class GameServer(private val hostName: String, dayMsInit: Long = DAY_MS) {

    private var dayMs = dayMsInit

    private val lock = Any()
    private val players = ArrayList<Player>()
    private val conns = ArrayList<Conn>()
    private val saved = ArrayList<JSONObject>()
    private var nextId = 0

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var phase = "lobby"
    private var hours = 7.0
    private var lastDay = 1
    private var lastHour = 7
    private var event: CityEvent? = null
    private var eventEndDay = 0
    private val rnd = Random(System.nanoTime())
    private val drift = DoubleArray(PLACES.size) { 1.0 }
    private var fxBase = 1600.0
    private val out = BooleanArray(ZONES)
    private val backAt = DoubleArray(ZONES)
    private var secAcc = 0L
    private var saveAcc = 0L

    @Volatile
    var resumed = false
        private set

    /** Called every few seconds with the whole game written as text, so the host can keep it safe. */
    var onSave: ((String) -> Unit)? = null

    private fun currentDay(): Int = min(TOTAL_DAYS, (hours / 24.0).toInt() + 1)

    private fun hr(): Double = hours % 24.0

    private fun isNight(): Boolean = hr() >= 21.0 || hr() < 5.0

    private fun lit(zone: Int): Boolean {
        val ev = event
        if (ev != null && ev.dark) return false
        return !out[zone]
    }

    private fun rate(): Double {
        val ev = event
        return fxBase * (if (ev != null) ev.fx else 1.0)
    }

    private fun isWeekend(): Boolean {
        val d = currentDay() % 7
        return d == 6 || d == 0
    }

    /** Current price of one bag of goods at a place. */
    private fun goodsPrice(place: Int): Long {
        val base = PLACES[place].goods
        if (base == 0) return 0L
        val ev = event
        val m = if (ev != null) ev.goods else 1.0
        return (base * drift[place] * m).toLong()
    }

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(GAME_PORT))
        serverSocket = ss
        running = true

        thread(isDaemon = true) {
            while (running) {
                try {
                    val s = ss.accept()
                    thread(isDaemon = true) { handle(s) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }

        thread(isDaemon = true) {
            var last = SystemClock.elapsedRealtime()
            while (running) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                val now = SystemClock.elapsedRealtime()
                val dt = now - last
                last = now
                try {
                    synchronized(lock) {
                        if (phase == "playing") {
                            advance(dt)
                            secAcc += dt
                            if (secAcc >= 1000L) {
                                secAcc = 0L
                                secondly(now)
                            }
                            saveAcc += dt
                            if (saveAcc >= 15000L) {
                                saveAcc = 0L
                                val cb = onSave
                                if (cb != null) cb(saveJson())
                            }
                        }
                    }
                } catch (e: Exception) {
                    // one bad tick must never stop the city
                    e.printStackTrace()
                }
            }
        }

        thread(isDaemon = true) {
            var n = 0
            while (running) {
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    break
                }
                n++
                broadcastPositions()
                if (n % 3 == 0) broadcastState()
            }
        }

        thread(isDaemon = true) {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.broadcast = true
                while (running) {
                    var count = 0
                    var ph = ""
                    synchronized(lock) {
                        count = players.size
                        ph = phase
                    }
                    if (ph == "lobby") {
                        val msg = "EKOHUSTLE|$hostName|$GAME_PORT|$count".toByteArray()
                        for (addr in broadcastAddresses()) {
                            try {
                                sock.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT))
                            } catch (e: Exception) {
                            }
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
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
            serverSocket?.close()
        } catch (e: Exception) {
        }
        synchronized(lock) {
            for (c in conns) {
                try {
                    c.socket.close()
                } catch (e: Exception) {
                }
            }
            conns.clear()
        }
    }

    fun startGame() {
        synchronized(lock) {
            if (phase != "lobby" || players.isEmpty()) return
            phase = "playing"
            if (resumed) {
                lastHour = hours.toInt()
                announce("Welcome back! Day $lastDay continues in Benin City.")
            } else {
                hours = 7.0
                lastDay = 1
                lastHour = 7
                announce("Day 1 begins in Benin City. Walk to a place, work, eat, rest, and pay your rent. Good luck!")
            }
        }
    }

    // ---------- saving and loading ----------

    private fun playerJson(p: Player): JSONObject {
        val o = JSONObject()
        o.put("n", p.name)
        o.put("cl", p.cls)
        o.put("c", p.cash)
        o.put("e", p.energy)
        o.put("f", p.food)
        o.put("r", p.rep)
        o.put("k", p.know)
        o.put("g", p.goods)
        o.put("bk", p.bank)
        o.put("ac", p.acct)
        o.put("us", p.usd)
        o.put("fk", p.fake)
        o.put("rt", p.rent)
        o.put("x", p.x.toDouble())
        o.put("y", p.y.toDouble())
        o.put("tk", p.tickets)
        o.put("ht", p.heat)
        o.put("hu", p.hurt)
        o.put("hm", p.housing)
        o.put("ms", p.mdone)
        o.put("fl", p.flex)
        return o
    }

    private fun restore(j: JSONObject): Player {
        val cl = j.optInt("cl", 1).coerceIn(0, CLASSES.size - 1)
        val p = Player(nextId++, j.optString("n", "Player"), cl)
        p.cash = j.optLong("c", p.cash)
        p.energy = j.optInt("e", 100)
        p.food = j.optInt("f", 80)
        p.rep = j.optInt("r", 50)
        p.know = j.optInt("k", 0)
        p.goods = j.optInt("g", 0)
        p.bank = j.optLong("bk", 0L)
        p.acct = j.optBoolean("ac", p.acct)
        p.usd = j.optInt("us", 0)
        p.fake = j.optInt("fk", 0)
        p.rent = j.optLong("rt", p.rent)
        p.x = j.optDouble("x", p.x.toDouble()).toFloat()
        p.y = j.optDouble("y", p.y.toDouble()).toFloat()
        p.lx = p.x
        p.ly = p.y
        p.tickets = j.optInt("tk", 0)
        p.heat = j.optInt("ht", 0)
        p.hurt = j.optBoolean("hu", false)
        p.housing = j.optInt("hm", p.housing).coerceIn(0, HOUSING.size - 1)
        p.rent = HOUSING[p.housing].rent
        p.mdone = j.optInt("ms", 0)
        p.flex = j.optInt("fl", 0)
        return p
    }

    private fun saveJson(): String {
        val o = JSONObject()
        o.put("day", lastDay)
        o.put("hours", hours)
        o.put("fxBase", fxBase)
        o.put("dm", dayMs)
        o.put("ev", event?.title ?: "")
        o.put("evEnd", eventEndDay)
        val arr = JSONArray()
        for (p in players) arr.put(playerJson(p))
        for (s in saved) {
            val nm = s.optString("n")
            if (players.none { it.name.equals(nm, ignoreCase = true) }) arr.put(s)
        }
        o.put("p", arr)
        return o.toString()
    }

    /** Brings back a game that was saved earlier. Call before anyone joins. */
    fun loadSave(text: String): Boolean {
        try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("p") ?: return false
            synchronized(lock) {
                saved.clear()
                for (i in 0 until arr.length()) saved.add(arr.getJSONObject(i))
                hours = o.optDouble("hours", 7.0)
                lastDay = o.optInt("day", 1)
                lastHour = hours.toInt()
                fxBase = o.optDouble("fxBase", 1600.0)
                dayMs = o.optLong("dm", dayMs)
                val evName = o.optString("ev", "")
                event = EVENTS.firstOrNull { it.title == evName }
                eventEndDay = o.optInt("evEnd", 0)
                resumed = true
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ---------- connections ----------

    private fun handle(s: Socket) {
        var conn: Conn? = null
        try {
            s.tcpNoDelay = true
            val c = Conn(s, PrintWriter(s.getOutputStream(), true))
            conn = c
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (running) {
                val line = reader.readLine() ?: break
                val o = JSONObject(line)
                when (o.optString("t")) {
                    "join" -> join(c, o.optString("name", "Player"), o.optInt("cls", 1))
                    "pos" -> {
                        val p = c.player
                        if (p != null) {
                            p.x = o.optDouble("x", p.x.toDouble()).toFloat().coerceIn(0f, WORLD_W)
                            p.y = o.optDouble("y", p.y.toDouble()).toFloat().coerceIn(0f, WORLD_H)
                        }
                    }
                    "act" -> {
                        val p = c.player
                        if (p != null) act(p, o)
                    }
                    "choose" -> {
                        val p = c.player
                        if (p != null) {
                            synchronized(lock) {
                                if (phase == "playing") resolve(p, o.optInt("i", 0))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // connection ended
            if (running) e.printStackTrace()
        } finally {
            val c = conn
            if (c != null) drop(c)
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun join(c: Conn, rawName: String, cls0: Int) {
        synchronized(lock) {
            if (c.player != null) return
            val clean = rawName.trim().take(14).ifEmpty { "Player" }
            val cls = cls0.coerceIn(0, CLASSES.size - 1)

            // Someone whose Wi-Fi dropped can come back with the same name.
            val back = players.firstOrNull { !it.online && it.name.equals(clean, ignoreCase = true) }
            if (back != null) {
                back.online = true
                c.player = back
                conns.add(c)
                if (phase != "lobby") {
                    c.send(JSONObject().put("t", "tp").put("x", back.x.toDouble()).put("y", back.y.toDouble()).put("ms", 0).toString())
                }
                return
            }

            // A player from a saved game takes their old self back.
            val sv = saved.firstOrNull { it.optString("n").equals(clean, ignoreCase = true) }
            if (sv != null && phase != "ended" && players.size < MAX_PLAYERS && players.none { it.name.equals(clean, ignoreCase = true) }) {
                saved.remove(sv)
                val p = restore(sv)
                c.player = p
                players.add(p)
                conns.add(c)
                if (phase != "lobby") {
                    c.send(JSONObject().put("t", "tp").put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("ms", 0).toString())
                }
                return
            }

            if (phase != "lobby" || players.size >= MAX_PLAYERS) {
                c.send("{\"t\":\"full\"}")
                return
            }
            var name = clean
            var n = 2
            while (players.any { it.name.equals(name, ignoreCase = true) }) {
                name = "$clean$n"
                n++
            }
            val p = Player(nextId++, name, cls)
            c.player = p
            players.add(p)
            conns.add(c)
        }
    }

    private fun drop(c: Conn) {
        synchronized(lock) {
            conns.remove(c)
            val p = c.player
            if (p != null) {
                if (phase == "lobby" && !resumed) players.remove(p) else p.online = false
            }
        }
    }

    private fun sendTo(p: Player, s: String) {
        for (c in conns) {
            if (c.player === p) c.send(s)
        }
    }

    private fun tell(p: Player, text: String) {
        sendTo(p, JSONObject().put("t", "msg").put("m", text).toString())
    }

    private fun announce(text: String) {
        val s = JSONObject().put("t", "msg").put("m", text).put("big", true).toString()
        for (c in conns) c.send(s)
    }

    private fun notifyZone(zone: Int, text: String) {
        for (p in players) {
            if (p.online && zoneAt(p.x, p.y) == zone) tell(p, text)
        }
    }

    private fun busy(p: Player, ms: Long, label: String) {
        p.busyUntil = SystemClock.elapsedRealtime() + ms
        p.busyTotal = ms
        p.busyLabel = label
    }

    private fun travel(p: Player, dest: Place, ms: Long, label: String) {
        p.x = dest.x
        p.y = dest.y + 60f
        p.lx = p.x
        p.ly = p.y
        busy(p, ms, label)
        mission(p, 0, 500L)
        sendTo(p, JSONObject().put("t", "tp").put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("ms", ms).toString())
    }

    // ---------- street encounters ("Wetin you do?") ----------

    private fun ask(p: Player, pd: Pending, title: String, text: String, opts: List<String>, secs: Int = 30) {
        pd.deadline = SystemClock.elapsedRealtime() + secs * 1000L
        p.pend = pd
        val arr = JSONArray()
        for (o in opts) arr.put(o)
        sendTo(p, JSONObject().put("t", "enc").put("title", title).put("text", text).put("opts", arr).put("tl", secs).toString())
    }

    private fun resolve(p: Player, i: Int) {
        val pd = p.pend ?: return
        p.pend = null
        p.encCool = SystemClock.elapsedRealtime() + 45000L
        sendTo(p, "{\"t\":\"encend\"}")
        when (pd.kind) {
            "tout" -> outTout(p, pd, i)
            "fight" -> fightRound(p, pd, i)
            "mad" -> outMad(p, pd, i)
            "foot" -> outFoot(p, i)
            "rob" -> outRob(p, pd, i)
            "busload" -> outBusLoad(p, pd, i)
            "busbreak" -> outBusBreak(p, pd, i)
            "nightkeke" -> outNightKeke(p, pd, i)
            "cashout" -> outCashout(p, pd, i)
            "poison" -> outPoison(p, i)
            "thief" -> outThief(p, i)
            "fire" -> outFire(p, i)
        }
    }

    private val missionNames = listOf("First Move", "First Trade", "Junction Tax", "Open an Account", "Bureau de Change Deal", "Blood Donation", "Night Survivor")

    private fun mission(p: Player, idx: Int, reward: Long) {
        if ((p.mdone and (1 shl idx)) != 0) return
        p.mdone = p.mdone or (1 shl idx)
        p.cash += reward
        tell(p, "Mission complete: ${missionNames[idx]}! +${naira(reward)}")
    }

    private fun lose(p: Player, frac: Double): Long {
        var f = frac * HOUSING[p.housing].rob * (1.0 + 0.15 * min(p.flex, 3))
        if (p.tickets >= 4) f *= 0.5
        val l = (p.cash * f).toLong()
        p.cash -= l
        return l
    }

    private fun heatCheck(p: Player) {
        if (p.heat >= 3) {
            val fine = p.cash / 4
            p.cash -= fine
            p.rep = max(0, p.rep - 10)
            p.heat = 0
            busy(p, 12000L, "Held at the police station")
            tell(p, "Police picked you up. You paid ${naira(fine)} to be released. Reputation -10.")
        }
    }

    // --- junction boys ---

    private fun startTout(p: Player, pl: Place) {
        if (p.cls == C_TICKET) return
        if (p.tickets >= 4 || p.warned) {
            p.warned = false
            p.encCool = SystemClock.elapsedRealtime() + 45000L
            tell(p, "The boys at ${pl.name} wave you through: 'Oga, na our person.'")
            return
        }
        val ev = event
        var fee = 200.0
        if (p.cls == C_HUSTLER) fee *= 0.5
        if (ev != null && ev.fuel) fee *= 2.0
        if (ev != null && ev.title.startsWith("Match day") && pl.id == P_STADIUM) fee *= 2.0
        if (isNight()) fee *= 1.5
        fee *= 1.0 + 0.5 * min(p.flex, 3)
        val f = fee.toLong()
        val pd = Pending("tout", 0L)
        pd.amt = f
        ask(
            p, pd, "Junction boys",
            "At ${pl.name} some boys block your way. 'Oga, wetin you carry? Drop something for the boys.' They want ${naira(f)}. Wetin you do?",
            listOf("Pay ${naira(f)}", "Bargain", "Refuse", "Run", "Show your reputation")
        )
    }

    private fun outTout(p: Player, pd: Pending, i: Int) {
        val fee = pd.amt
        when (i) {
            0 -> {
                val pay = min(fee, p.cash)
                p.cash -= pay
                p.tickets++
                p.rep = min(100, p.rep + 1)
                var m = "You paid ${naira(pay)} and they let you pass."
                if (p.tickets == 4) m += " The boys know you now. From today they wave you through."
                tell(p, m)
            }
            1 -> {
                if (rnd.nextInt(100) < 60) {
                    val pay = min(fee / 2, p.cash)
                    p.cash -= pay
                    tell(p, "You bargained them down to ${naira(pay)}.")
                    mission(p, 2, 1000L)
                } else {
                    val pay = min(fee * 3 / 2, p.cash)
                    p.cash -= pay
                    p.rep = max(0, p.rep - 1)
                    tell(p, "They got angry at the bargaining and took ${naira(pay)}.")
                }
            }
            2 -> {
                val roll = rnd.nextInt(100)
                if (roll < 35) {
                    tell(p, "They hissed, but let you go this time.")
                } else if (roll < 70) {
                    val l = min(p.cash, 300L + rnd.nextInt(1200))
                    p.cash -= l
                    p.rep = max(0, p.rep - 3)
                    p.tickets = max(0, p.tickets - 1)
                    tell(p, "They searched you and took ${naira(l)}. They will remember your face.")
                } else {
                    startFight(p, "a junction boy", 55 + rnd.nextInt(20), 300L + rnd.nextInt(900))
                }
            }
            3 -> {
                if (rnd.nextInt(100) < 55) {
                    p.energy = max(0, p.energy - 12)
                    tell(p, "You ran and lost them in the crowd. Energy -12.")
                } else {
                    val l = min(p.cash, fee * 2)
                    p.cash -= l
                    p.energy = max(0, p.energy - 15)
                    tell(p, "They caught you. They took ${naira(l)} and your energy is down.")
                }
            }
            else -> {
                if (p.rep >= 70) {
                    p.rep = min(100, p.rep + 1)
                    tell(p, "'Oga, sorry, we no know you.' They wave you through. Reputation +1.")
                    mission(p, 2, 1000L)
                } else {
                    val pay = min(fee * 2, p.cash)
                    p.cash -= pay
                    p.rep = max(0, p.rep - 1)
                    tell(p, "They laughed at you. Nobody knows you. You paid double: ${naira(pay)}.")
                }
            }
        }
    }

    // --- fights ---

    private fun startFight(p: Player, enemy: String, ehp: Int, loot: Long) {
        val pd = Pending("fight", 0L)
        pd.a = 100
        pd.b = ehp
        pd.s = enemy
        pd.amt = loot
        askFight(p, pd, "A fight starts with $enemy!")
    }

    private fun askFight(p: Player, pd: Pending, intro: String) {
        ask(p, pd, "Fight", intro + "\nYou: " + pd.a + " HP     " + pd.s + ": " + pd.b + " HP", listOf("Punch", "Kick", "Grapple", "Talk", "Run"), 25)
    }

    private fun fightRound(p: Player, pd: Pending, i: Int) {
        var log = ""
        var enemyActs = true
        when (i) {
            0 -> {
                if (rnd.nextInt(100) < 72) {
                    val d = 12 + rnd.nextInt(12)
                    pd.b -= d
                    log = "Your punch lands (-$d)."
                } else log = "You miss."
            }
            1 -> {
                if (rnd.nextInt(100) < 55) {
                    val d = 22 + rnd.nextInt(16)
                    pd.b -= d
                    log = "A heavy kick lands (-$d)."
                } else {
                    pd.a -= 8
                    log = "You miss and stumble (-8)."
                }
            }
            2 -> {
                if (rnd.nextInt(100) < 60) {
                    pd.b -= 15
                    enemyActs = false
                    log = "You hold him and he cannot hit back this round (-15)."
                } else log = "He slips out of your grip."
            }
            3 -> {
                if (rnd.nextInt(100) < 30 + p.rep / 4) {
                    tell(p, "You talked him down. Both of you walk away.")
                    return
                }
                log = "He is not listening."
            }
            else -> {
                if (rnd.nextInt(100) < 50) {
                    p.energy = max(0, p.energy - 10)
                    tell(p, "You got away. Energy -10.")
                    return
                }
                log = "You cannot get away."
            }
        }
        if (pd.b <= 0) {
            val loot = pd.amt
            p.cash += loot
            p.rep = min(100, p.rep + 3)
            p.energy = max(0, p.energy - 10)
            tell(p, "${pd.s} is down. You won! You took ${naira(loot)}. Reputation +3.")
            return
        }
        if (enemyActs && rnd.nextInt(100) < 60) {
            val d = 8 + rnd.nextInt(11)
            pd.a -= d
            log += " He hits you (-$d)."
        }
        if (pd.a <= 0) {
            val l = p.cash * 30 / 100
            p.cash -= l
            p.energy = max(0, p.energy - 30)
            p.rep = max(0, p.rep - 3)
            p.hurt = true
            busy(p, 6000L, "Recovering")
            tell(p, "You lost the fight and ${naira(l)}. You are hurt. Visit UBTH to get treated.")
            return
        }
        askFight(p, pd, log)
    }

    // --- mad men ---

    private fun startMad(p: Player) {
        val t = rnd.nextInt(5)
        val pd = Pending("mad", 0L)
        pd.a = t
        val names = listOf("The Naked One", "The Prophet", "The Aggressive One", "The Dancer", "The Collector")
        val texts = listOf(
            "A man with no clothes on walks down the middle of the road, talking to himself. Traffic is stuck behind him.",
            "A man in rags with a long staff preaches to nobody in particular.",
            "A man paces and mutters, his eyes jumping from face to face.",
            "A man dances in the road with no music. People laugh and step around him.",
            "A man pulls things out of a bin and lines them up on the kerb."
        )
        ask(
            p, pd, names[t], texts[t],
            listOf("Pass quietly", "Give small change (${naira(50L)})", "Talk to him", "Cross the road", "Stare")
        )
    }

    private fun outMad(p: Player, pd: Pending, i: Int) {
        val t = pd.a
        val r = rnd.nextInt(100)
        when (i) {
            0 -> {
                if (t == 2 && r < 20) {
                    p.energy = max(0, p.energy - 6)
                    tell(p, "He shouted at you and followed you for a while. Energy -6.")
                } else if (t == 0 && r < 50) {
                    busy(p, 3000L, "Stuck behind him")
                    tell(p, "You wait while he blocks the whole road.")
                } else tell(p, "You passed quietly. Nothing happened.")
            }
            1 -> {
                p.cash -= min(50L, p.cash)
                if (t == 1 && r < 50) {
                    p.rep = min(100, p.rep + 2)
                    tell(p, "He raised his staff: 'A blessing on your hustle.' Reputation +2.")
                } else if (t == 3 && r < 70) {
                    p.rep = min(100, p.rep + 1)
                    tell(p, "He danced for you and the crowd clapped. Reputation +1.")
                } else if (t == 2 && r < 40) {
                    p.energy = max(0, p.energy - 5)
                    tell(p, "He threw it back at you and cursed. Energy -5.")
                } else if (r < 15) {
                    p.warned = true
                    tell(p, "He smiled and pointed ahead: 'Boys dey that junction.' The next junction boys will let you pass.")
                } else tell(p, "He took the change and went on his way.")
            }
            2 -> {
                if (t == 2 && r < 60) {
                    p.energy = max(0, p.energy - 12)
                    tell(p, "That was a mistake. He chased you for two streets. Energy -12.")
                } else if (t == 1 && r < 45) {
                    p.know = min(100, p.know + 1)
                    tell(p, "What he said made no sense, but the more you think about it, the wiser it sounds. Knowledge +1.")
                } else if (t == 4 && r < 35) {
                    p.cash += 1500L
                    tell(p, "He handed you a wristwatch from the bin. It still works. You sold it for ${naira(1500L)}.")
                } else if (r < 15) {
                    p.cash += 3000L
                    tell(p, "He led you behind a wall to a bundle someone dropped. You found ${naira(3000L)}.")
                } else tell(p, "He said nothing that made sense.")
            }
            3 -> {
                busy(p, 4000L, "Crossing the road")
                tell(p, "You crossed the road to avoid him. It cost you time, but nothing else.")
            }
            else -> {
                if (t == 2 && r < 70) {
                    p.energy = max(0, p.energy - 15)
                    val l = min(p.cash, 200L)
                    p.cash -= l
                    tell(p, "Bad idea. He charged at you and you fell running away. Energy -15, lost ${naira(l)}.")
                } else if (isNight() && r < 30) {
                    p.energy = max(0, p.energy - 8)
                    tell(p, "He started screaming in the dark. Your heart is pounding. Energy -8.")
                } else tell(p, "He stared back, then lost interest.")
            }
        }
    }

    // --- night on the road ---

    private fun startFoot(p: Player) {
        val pd = Pending("foot", 0L)
        ask(
            p, pd, "Footsteps in the dark",
            "You dey waka alone. It is late and the street is dark. You hear footsteps behind you, getting closer. Wetin you do?",
            listOf("Speed up", "Look back", "Run", "Enter a nearby compound", "Call a friend")
        )
    }

    private fun outFoot(p: Player, i: Int) {
        when (i) {
            0 -> {
                if (rnd.nextInt(100) < 55) {
                    p.energy = max(0, p.energy - 8)
                    tell(p, "You walked faster and lost whoever it was. Energy -8.")
                    mission(p, 6, 1500L)
                } else {
                    val l = lose(p, 0.35)
                    p.energy = max(0, p.energy - 10)
                    tell(p, "He caught up. 'Drop your money!' You lost ${naira(l)}.")
                }
            }
            1 -> {
                if (rnd.nextInt(100) < 40) {
                    tell(p, "You looked back. Just a student with earphones. You breathe again.")
                } else {
                    val l = lose(p, 0.3)
                    tell(p, "Two boys. They took ${naira(l)} before you could shout.")
                }
            }
            2 -> {
                if (p.energy >= 45 && rnd.nextInt(100) < 65) {
                    p.energy = max(0, p.energy - 15)
                    tell(p, "You ran and they fell behind. Energy -15.")
                    mission(p, 6, 1500L)
                } else {
                    val l = lose(p, 0.4)
                    p.energy = max(0, p.energy - 15)
                    tell(p, "You tripped and they were on you. You lost ${naira(l)}.")
                }
            }
            3 -> {
                if (rnd.nextInt(100) < 60) {
                    busy(p, 5000L, "Waiting inside a compound")
                    tell(p, "A woman opened her gate and let you in until the street was clear.")
                } else {
                    val l = lose(p, 0.25)
                    tell(p, "The gate stayed shut. They took ${naira(l)} right there.")
                }
            }
            else -> {
                val friend = players.firstOrNull { it !== p && it.online && hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < 700.0 }
                if (friend != null && rnd.nextInt(100) < 75) {
                    friend.rep = min(100, friend.rep + 1)
                    tell(friend, "${p.name} called you for help on a dark road. You went to walk with them. Reputation +1.")
                    tell(p, "${friend.name} picked up and came to meet you. You walked the rest together.")
                    mission(p, 6, 1500L)
                } else if (rnd.nextInt(100) < 20) {
                    tell(p, "Nobody answered, but the footsteps turned into another street.")
                } else {
                    val l = lose(p, 0.3)
                    tell(p, "Nobody answered the phone. They took ${naira(l)}.")
                }
            }
        }
    }

    // --- being robbed by another player ---

    private fun outRob(p: Player, pd: Pending, i: Int) {
        val att = players.firstOrNull { it.id == pd.other }
        val demand = min(pd.amt, p.cash)
        if (att == null) return
        att.busyUntil = 0L
        att.heat += 1
        var res: String
        var attMsg: String
        if (p.tickets >= 4 && rnd.nextInt(100) < 50) {
            val fine = att.cash * 15 / 100
            att.cash -= fine
            att.rep = max(0, att.rep - 6)
            tell(p, "The junction boys who know you saw what happened and chased ${att.name} away.")
            tell(att, "Junction boys who protect ${p.name} chased you off and took ${naira(fine)}. Reputation -6.")
            heatCheck(att)
            return
        }
        when (i) {
            0 -> {
                p.cash -= demand
                att.cash += demand
                att.rep = max(0, att.rep - 2)
                res = "You gave ${att.name} ${naira(demand)}."
                attMsg = "${p.name} gave in. You took ${naira(demand)}. Reputation -2."
            }
            1 -> {
                val me = p.energy + (if (p.cls == C_TICKET) 15 else 0) + rnd.nextInt(40)
                val him = att.energy + (if (att.cls == C_TICKET) 15 else 0) + (if (isNight()) 10 else 0) + rnd.nextInt(40)
                p.energy = max(0, p.energy - 15)
                att.energy = max(0, att.energy - 10)
                if (me > him) {
                    val take = min(att.cash, att.cash * 20 / 100)
                    att.cash -= take
                    p.cash += take
                    att.rep = max(0, att.rep - 5)
                    res = "You beat ${att.name} and took ${naira(take)}."
                    attMsg = "${p.name} fought back and beat you. You lost ${naira(take)}. Reputation -5."
                } else {
                    val take = min(p.cash, demand * 13 / 10)
                    p.cash -= take
                    att.cash += take
                    p.rep = max(0, p.rep - 1)
                    res = "You lost the fight. ${att.name} took ${naira(take)}."
                    attMsg = "You beat ${p.name} and took ${naira(take)}."
                }
            }
            2 -> {
                val chance = p.energy * 100 / max(1, p.energy + att.energy + 20)
                p.energy = max(0, p.energy - 10)
                if (rnd.nextInt(100) < chance) {
                    res = "You ran and got away. Energy -10."
                    attMsg = "${p.name} ran away before you could take anything."
                } else {
                    p.cash -= demand
                    att.cash += demand
                    res = "You ran, but ${att.name} caught you and took ${naira(demand)}."
                    attMsg = "You caught ${p.name} and took ${naira(demand)}."
                }
            }
            else -> {
                if (rnd.nextInt(100) < min(70, 25 + p.rep / 4)) {
                    res = "You talked ${att.name} out of it."
                    attMsg = "${p.name} talked you out of it. Reputation -1."
                    att.rep = max(0, att.rep - 1)
                } else {
                    p.cash -= demand
                    att.cash += demand
                    res = "Talking did not work. ${att.name} took ${naira(demand)}."
                    attMsg = "${p.name} tried to talk, but you took ${naira(demand)}."
                }
            }
        }
        tell(p, res)
        tell(att, attMsg)
        heatCheck(att)
    }

    private fun robPlayer(p: Player, o: JSONObject) {
        val vid = o.optInt("to", -1)
        val v = players.firstOrNull { it.id == vid } ?: return
        if (v === p || !v.online) return
        if (hypot((v.x - p.x).toDouble(), (v.y - p.y).toDouble()) > 160.0) {
            tell(p, "${v.name} is too far from you.")
            return
        }
        if (v.pend != null || SystemClock.elapsedRealtime() < v.busyUntil) {
            tell(p, "${v.name} is busy right now.")
            return
        }
        if (v.cash < 500L) {
            tell(p, "${v.name} has almost no cash on them. Not worth it.")
            return
        }
        val pd = Pending("rob", 0L)
        pd.other = p.id
        pd.amt = max(1000L, v.cash / 2)
        busy(p, 20000L, "Holding up ${v.name}")
        tell(p, "You step up to ${v.name}: 'Oga, drop your money!' Waiting to see what they do...")
        ask(
            v, pd, "Robbery!",
            "${p.name} stops you: 'Oga, drop your money!' They want ${naira(min(pd.amt, v.cash))}. Wetin you do?",
            listOf("Give it", "Fight", "Run", "Talk him down"), 20
        )
    }

    // --- transport wahala ---

    private fun kekeFare(p: Player, dist: Double): Long {
        val ev = event
        var f = (200.0 + dist * 0.4) * (if (ev != null) ev.travel else 1.0)
        if (p.cls == C_HUSTLER) f *= 0.9
        if (p.cls == C_DRIVER) f = 0.0
        return f.toLong()
    }

    private fun kekeMs(dist: Double): Long {
        val ev = event
        return ((2200.0 + dist * 0.9) * (if (ev != null) ev.travelTime else 1.0)).toLong()
    }

    private fun outBusLoad(p: Player, pd: Pending, i: Int) {
        val dest = PLACES[pd.a]
        val dist = pd.c.toDouble()
        when (i) {
            0 -> {
                tell(p, "After a long wait the bus finally filled up.")
                travel(p, dest, pd.b + 9000L, "Riding to ${dest.name}")
            }
            1 -> {
                p.cash -= min(200L, p.cash)
                travel(p, dest, pd.b.toLong(), "Riding to ${dest.name}")
            }
            2 -> {
                p.cash += pd.amt
                p.energy = max(0, p.energy - 15)
                tell(p, "You took your fare back and set out on foot. Energy -15.")
                travel(p, dest, (pd.b * 2.2).toLong(), "Trekking to ${dest.name}")
            }
            else -> {
                p.cash += pd.amt
                val kf = kekeFare(p, dist)
                if (p.cash < kf) {
                    p.cash -= pd.amt
                    tell(p, "You cannot afford a keke, so you wait for the bus.")
                    travel(p, dest, pd.b + 9000L, "Riding to ${dest.name}")
                } else {
                    p.cash -= kf
                    tell(p, "You paid ${naira(kf)} for a keke instead.")
                    travel(p, dest, kekeMs(dist), "Riding to ${dest.name}")
                }
            }
        }
    }

    private fun outBusBreak(p: Player, pd: Pending, i: Int) {
        val dest = PLACES[pd.a]
        val ms = pd.b.toLong()
        when (i) {
            0 -> {
                if (rnd.nextInt(100) < 50) {
                    tell(p, "Another bus came after a while.")
                    travel(p, dest, (ms * 1.8).toLong(), "Riding to ${dest.name}")
                } else {
                    tell(p, "You waited and waited. Nothing came for a long time.")
                    travel(p, dest, ms * 3, "Still waiting for a bus")
                }
            }
            1 -> {
                p.energy = max(0, p.energy - 18)
                tell(p, "You trekked the rest of the way. Energy -18.")
                travel(p, dest, ms * 2, "Trekking to ${dest.name}")
            }
            2 -> {
                val pay = min(500L, p.cash)
                p.cash -= pay
                tell(p, "A keke stopped. You paid ${naira(pay)}.")
                travel(p, dest, (ms * 1.1).toLong(), "Riding to ${dest.name}")
            }
            else -> {
                if (rnd.nextInt(100) < 40) {
                    tell(p, "A friend with a car came and picked you up for free.")
                    travel(p, dest, ms, "Riding to ${dest.name}")
                } else {
                    tell(p, "Your friend did not answer. You waited a long time.")
                    travel(p, dest, (ms * 2.5).toLong(), "Waiting by the road")
                }
            }
        }
    }

    private fun outNightKeke(p: Player, pd: Pending, i: Int) {
        val dest = PLACES[pd.a]
        val ms = pd.b.toLong()
        when (i) {
            0 -> {
                if (p.cash < pd.amt) {
                    tell(p, "You do not have ${naira(pd.amt)}. The keke leaves.")
                } else {
                    p.cash -= pd.amt
                    travel(p, dest, ms, "Riding to ${dest.name}")
                }
            }
            1 -> {
                if (rnd.nextInt(100) < 50) {
                    val price = min(p.cash, pd.amt * 60 / 100)
                    p.cash -= price
                    tell(p, "You argued and he dropped the price to ${naira(price)}.")
                    travel(p, dest, ms, "Riding to ${dest.name}")
                } else {
                    busy(p, 3000L, "Arguing in the dark")
                    tell(p, "He hissed and drove off. No keke.")
                }
            }
            else -> {
                p.energy = max(0, p.energy - 20)
                var m = "You trekked in the dark. Energy -20."
                if (rnd.nextInt(100) < 35) {
                    val l = lose(p, 0.3)
                    m += " Someone robbed you of ${naira(l)}."
                }
                tell(p, m)
                travel(p, dest, (ms * 2.5).toLong(), "Trekking to ${dest.name}")
            }
        }
    }

    // --- cash-out choice, food poison, market chaos ---

    private fun outCashout(p: Player, pd: Pending, i: Int) {
        val amt = min(pd.amt, p.cash)
        when (i) {
            0 -> {
                val spend = amt * 70 / 100
                p.cash -= spend
                p.rep = min(100, p.rep + 6)
                p.flex += 2
                tell(p, "You partied at Kada Plaza, bought designer clothes and tipped everybody. You spent ${naira(spend)}. Everyone saw. Reputation +6, but now the whole city knows you have money.")
            }
            1 -> {
                p.flex = max(0, p.flex - 1)
                p.rep = min(100, p.rep + 1)
                if (p.acct) {
                    p.cash -= amt
                    p.bank += amt
                    tell(p, "You saved ${naira(amt)} in your account and told nobody. Quiet money grows.")
                } else tell(p, "You kept it quiet, but with no bank account it is still cash. Open an account to really protect it.")
            }
            else -> {
                val spend = amt * 20 / 100
                p.cash -= spend
                p.rep = min(100, p.rep + 2)
                var m = "You spent ${naira(spend)} on a small treat."
                if (p.acct) {
                    val save = amt * 50 / 100
                    p.cash -= save
                    p.bank += save
                    m += " ${naira(save)} went into the bank."
                }
                tell(p, m)
            }
        }
    }

    private fun startPoison(p: Player) {
        val pd = Pending("poison", 0L)
        ask(
            p, pd, "Belle dey pain you",
            "Two hours after that food from mama put, your stomach turns. You need to sit down. Wetin you do?",
            listOf("Go to UBTH (${naira(5000L)})", "Buy medicine (${naira(1000L)})", "Endure it", "Find another buka")
        )
    }

    private fun outPoison(p: Player, i: Int) {
        when (i) {
            0 -> {
                if (p.cash >= 5000L) {
                    p.cash -= 5000L
                    p.energy = min(100, p.energy + 10)
                    tell(p, "The doctor treated you. You are fine again.")
                } else {
                    p.energy = max(0, p.energy - 20)
                    tell(p, "You cannot afford UBTH. You suffer through it. Energy -20.")
                }
            }
            1 -> {
                val pay = min(1000L, p.cash)
                p.cash -= pay
                if (rnd.nextInt(100) < 55) tell(p, "The medicine worked. You recover quickly.")
                else {
                    p.energy = max(0, p.energy - 15)
                    tell(p, "The medicine did nothing. Energy -15.")
                }
            }
            2 -> {
                p.energy = max(0, p.energy - 25)
                p.food = max(0, p.food - 20)
                tell(p, "You endured it. It was a very long night. Energy -25.")
            }
            else -> {
                if (rnd.nextInt(100) < 50) tell(p, "Another buka had pepper soup that settled your stomach.")
                else {
                    p.energy = max(0, p.energy - 20)
                    tell(p, "Same problem at the next buka. Energy -20.")
                }
            }
        }
    }

    private fun startMarketChaos(p: Player) {
        if (rnd.nextInt(100) < 70) {
            ask(
                p, Pending("thief", 0L), "Thief caught!",
                "A thief has been caught in the market. A crowd is mobbing him and shouting. Wetin you do?",
                listOf("Join the mob", "Stop the mob", "Film it and sell the footage", "Walk away", "Help him escape")
            )
        } else {
            ask(
                p, Pending("fire", 0L), "Fire in the market!",
                "Smoke is rising from a section of stalls. Everybody is running. Wetin you do?",
                listOf("Help put out the fire", "Loot during the chaos", "Rescue goods from the stalls", "Flee")
            )
        }
    }

    private fun outThief(p: Player, i: Int) {
        val r = rnd.nextInt(100)
        when (i) {
            0 -> if (r < 40) {
                p.energy = max(0, p.energy - 10)
                tell(p, "You joined in and took a few blows yourself. Energy -10.")
            } else {
                p.rep = min(100, p.rep + 1)
                tell(p, "The crowd cheered you on. Reputation +1.")
            }
            1 -> if (r < 50) {
                p.rep = min(100, p.rep + 3)
                tell(p, "You pulled people off him. A woman says 'God bless you.' Reputation +3.")
            } else {
                p.energy = max(0, p.energy - 12)
                tell(p, "You tried to stop them and got hit in the chaos. Energy -12.")
            }
            2 -> if (r < 60) {
                p.cash += 2000L
                tell(p, "Your video sold for ${naira(2000L)}.")
            } else tell(p, "Nobody wanted the footage.")
            3 -> tell(p, "You walked away. Not your business.")
            else -> if (r < 50) {
                p.cash += 3000L
                p.rep = max(0, p.rep - 2)
                tell(p, "You slipped him out the back. He pressed ${naira(3000L)} into your hand. People saw you do it. Reputation -2.")
            } else {
                p.energy = max(0, p.energy - 20)
                tell(p, "The mob caught you helping him and you took a beating. Energy -20.")
            }
        }
    }

    private fun outFire(p: Player, i: Int) {
        val r = rnd.nextInt(100)
        when (i) {
            0 -> {
                p.rep = min(100, p.rep + 4)
                p.energy = max(0, p.energy - 15)
                if (r < 20) {
                    p.hurt = true
                    tell(p, "You helped put the fire out and got burned. Visit UBTH. Reputation +4.")
                } else tell(p, "You helped put out the fire. People call you a hero. Reputation +4, energy -15.")
            }
            1 -> if (r < 50) {
                p.cash += 4000L
                tell(p, "You grabbed ${naira(4000L)} worth of things in the chaos.")
            } else {
                val l = min(p.cash, 2000L)
                p.cash -= l
                p.rep = max(0, p.rep - 6)
                p.heat += 1
                tell(p, "You were caught looting. You paid ${naira(l)} to be let go. Reputation -6.")
                heatCheck(p)
            }
            2 -> {
                p.energy = max(0, p.energy - 10)
                val n = min(2, 12 - p.goods)
                p.goods += n
                tell(p, "You dragged goods out of the smoke. You now carry ${p.goods} bags. Energy -10.")
            }
            else -> tell(p, "You ran. The fire was put out without you.")
        }
    }

    // ---------- once a second: encounters, power cuts, level-ups ----------

    private fun secondly(now: Long) {
        for (p in players) {
            if (!p.online) continue
            val pd = p.pend
            if (pd != null) {
                if (now > pd.deadline) resolve(p, 0)
                continue
            }
            val moved = hypot((p.x - p.lx).toDouble(), (p.y - p.ly).toDouble()) > 6.0
            p.lx = p.x
            p.ly = p.y

            val w = p.worth(rate())
            val lv = levelOf(w)
            if (lv > p.lvl) {
                p.lvl = lv
                tell(p, "Level up! You are now Level $lv: ${LEVEL_NAMES[lv]}.")
            } else if (lv < p.lvl) p.lvl = lv

            if (now < p.busyUntil || now < p.encCool) continue
            val at = placeAt(p.x, p.y)
            val dark = !lit(zoneAt(p.x, p.y))
            if (at >= 0) {
                val pl = PLACES[at]
                if (pl.tout && rnd.nextInt(100) < 6) startTout(p, pl)
                else if ((at == P_OBA || at == P_NEWBENIN) && rnd.nextInt(1000) < 8) startMarketChaos(p)
            } else if (moved) {
                val night = isNight()
                val madChance = (if (night) 2.2 else 1.2) * (if (dark) 1.4 else 1.0)
                val footChance = if (night && p.cash > 3000L) 2.0 * (if (dark) 1.6 else 1.0) else 0.0
                val roll = rnd.nextDouble() * 100.0
                if (roll < footChance) startFoot(p)
                else if (roll < footChance + madChance) startMad(p)
            }
        }
    }

    // ---------- the actions ----------

    private fun closedMsg(pl: Place): String = "${pl.name} is closed now. It is open from ${hourText(pl.oh)} to ${hourText(pl.ch)}."

    private fun classWork(p: Player, id: Int): Double = when (p.cls) {
        C_STUDENT -> if (id == P_UNIBEN || id == P_NADIA) 1.15 else 1.0
        C_TRADER -> if (id == P_OBA || id == P_EKEOSA || id == P_NEWBENIN) 1.2 else 1.0
        C_CIVIL -> if (id == P_SECRETARIAT || id == P_ASSEMBLY) 1.3 else 1.0
        C_HUSTLER -> 1.05
        else -> 1.0
    }

    private fun act(p: Player, o: JSONObject) {
        synchronized(lock) {
            if (phase != "playing") return
            val now = SystemClock.elapsedRealtime()
            if (now < p.busyUntil) return
            if (p.pend != null) return
            val action = o.optString("a")

            if (action == "xfer") {
                transfer(p, o)
                return
            }
            if (action == "rob") {
                robPlayer(p, o)
                return
            }
            if (action == "reveal") {
                val v = players.firstOrNull { it.id == o.optInt("to", -1) }
                if (v != null && v !== p && hypot((v.x - p.x).toDouble(), (v.y - p.y).toDouble()) <= 260.0) {
                    sendTo(v, JSONObject().put("t", "reveal").put("id", p.id).put("name", p.name).toString())
                    tell(p, "You told ${v.name} who you are.")
                } else tell(p, "Nobody close enough to tell.")
                return
            }
            if (action == "home") {
                val i = o.optInt("i", -1)
                if (i < 0 || i >= HOUSING.size || i == p.housing) return
                val h = HOUSING[i]
                val cost = if (h.rent > 0L) 2000L else 0L
                if (p.cash < cost) {
                    tell(p, "Moving costs ${naira(cost)}.")
                    return
                }
                p.cash -= cost
                p.housing = i
                p.rent = h.rent
                tell(p, "You now live here: ${h.name}. Rent ${naira(h.rent)} a week.")
                return
            }

            val id = o.optInt("pl", -1)
            if (id < 0 || id >= PLACES.size) return
            val pl = PLACES[id]
            if (!pl.acts.contains(action) && !extraActs(p.cls, pl).contains(action)) return
            if (hypot((p.x - pl.x).toDouble(), (p.y - pl.y).toDouble()) > pl.r + 90.0) {
                tell(p, "You are too far from ${pl.name}.")
                return
            }
            val ev = event
            val day = currentDay()
            val hour = hr()
            val litHere = lit(pl.zone)
            val office = id == P_ASSEMBLY || id == P_SECRETARIAT || id == P_GRA

            // opening hours
            if (action == "work" || action == "buy" || action == "sell" || action == "study" || action == "visit" || action == "eat") {
                if (!isOpenAt(pl, hour)) {
                    tell(p, closedMsg(pl))
                    return
                }
            }

            when (action) {
                "work" -> {
                    if (ev != null && ev.blocked == id) {
                        tell(p, "You cannot work at ${pl.name} right now: ${ev.title}.")
                        return
                    }
                    if (office && !litHere) {
                        tell(p, "No light. The office has sent everybody home.")
                        return
                    }
                    if (p.energy < pl.energy) {
                        tell(p, "Too tired to work. Find a lodge and rest.")
                        return
                    }
                    val bonus = o.optDouble("bonus", 1.0).coerceIn(0.7, 1.5)
                    var mult = bonus * (if (ev != null) ev.pay[id] else 1.0) * classWork(p, id)
                    if (p.food < 20) mult *= 0.8
                    if (p.hurt) mult *= 0.7
                    if (!litHere) mult *= 0.8
                    mult *= if (id == STUDY_PLACE) 0.6 + p.know / 100.0 else 1.0 + p.know / 250.0
                    var pay = (pl.pay * mult).toLong()
                    p.energy -= pl.energy
                    p.rep = min(100, p.rep + (if (bonus >= 1.4) 2 else 1))
                    var extra = ""
                    val roll = rnd.nextInt(100)
                    if (roll < 10) {
                        pay += 1500L
                        extra = " A customer tipped you ${naira(1500L)}."
                    } else if (roll < 18) {
                        val l = min(3000L, p.cash + pay)
                        pay -= l
                        extra = " A pickpocket got ${naira(l)}."
                    }
                    p.cash += pay
                    busy(p, 7000L, "Working as ${pl.job}")
                    tell(p, "Shift done. You earned ${naira(pay)}.$extra" + (if (!litHere) " (No light, so business was slow.)" else ""))
                }
                "eat" -> {
                    val fm = if (ev != null) ev.food else 1.0
                    var cost = (pl.food * fm).toLong()
                    if (p.cls == C_STUDENT && (id == P_UNIBEN || id == P_NADIA || id == P_EKOSODIN)) cost = cost * 7 / 10
                    if (pl.food == 0) return
                    if (p.cash < cost) {
                        tell(p, "You cannot afford food here (${naira(cost)}).")
                        return
                    }
                    p.cash -= cost
                    p.food = min(100, p.food + 45)
                    busy(p, 3000L, "Eating")
                    tell(p, "You ate for ${naira(cost)}.")
                    if ((id == P_OBA || id == P_EKEOSA || id == P_EKOSODIN || id == P_NEWBENIN) && rnd.nextInt(100) < 5) startPoison(p)
                }
                "rest" -> {
                    val cost = if (id == P_LODGE) 2000L else 700L
                    if (p.cash < cost) {
                        tell(p, "A bed here costs ${naira(cost)}.")
                        return
                    }
                    p.cash -= cost
                    p.energy = min(100, p.energy + 65)
                    busy(p, 6000L, "Resting")
                    tell(p, "You slept for ${naira(cost)} and feel much better.")
                }
                "donate" -> {
                    val last = p.visits[1000 + id]
                    if (last != null && last == day) {
                        tell(p, "You already donated today.")
                        return
                    }
                    if (p.energy < 14) {
                        tell(p, "You are too weak to donate blood.")
                        return
                    }
                    p.visits[1000 + id] = day
                    p.cash += 5000L
                    p.energy -= 12
                    busy(p, 6000L, "Donating blood")
                    tell(p, "Thank you for donating. You got ${naira(5000L)} and a small drink.")
                    mission(p, 5, 1500L)
                }
                "treat" -> {
                    val cost = 2500L
                    if (p.cash < cost) {
                        tell(p, "Treatment costs ${naira(cost)}.")
                        return
                    }
                    p.cash -= cost
                    p.energy = min(100, p.energy + 35)
                    val was = p.hurt
                    p.hurt = false
                    busy(p, 6000L, "Seeing the doctor")
                    tell(p, if (was) "The doctor treated your injuries. You are fit to work again." else "A quick check-up and a drip. Energy up.")
                }
                "study" -> {
                    if (ev != null && ev.title == "ASUU strike") {
                        tell(p, "The lecturers are on strike. No classes.")
                        return
                    }
                    if (p.energy < 15) {
                        tell(p, "Too tired to study. Rest first.")
                        return
                    }
                    if (p.cash < STUDY_COST) {
                        tell(p, "You need ${naira(STUDY_COST)} for handouts and data.")
                        return
                    }
                    p.cash -= STUDY_COST
                    p.energy -= 15
                    val gain = if (p.cls == C_STUDENT) 12 else 8
                    p.know = min(100, p.know + gain)
                    p.rep = min(100, p.rep + 1)
                    busy(p, 5000L, "Studying")
                    tell(p, "You studied hard. Knowledge is now ${p.know}. Every job pays better.")
                }
                "buy" -> {
                    var price = goodsPrice(id)
                    if (p.cls == C_TRADER) price = price * 9 / 10
                    if (p.goods >= 12) {
                        tell(p, "You cannot carry more than 12 bags of goods.")
                        return
                    }
                    if (p.cash < price) {
                        tell(p, "You cannot afford goods here (${naira(price)}).")
                        return
                    }
                    p.cash -= price
                    p.goods += 1
                    busy(p, 1500L, "Buying goods")
                    tell(p, "Bought a bag of goods for ${naira(price)}. You carry ${p.goods}.")
                }
                "sell" -> {
                    if (p.goods <= 0) {
                        tell(p, "You have no goods to sell.")
                        return
                    }
                    val each = if (p.cls == C_TRADER) goodsPrice(id) else (goodsPrice(id) * 0.9).toLong()
                    val total = each * p.goods
                    val n = p.goods
                    p.cash += total
                    p.goods = 0
                    busy(p, 2000L, "Selling goods")
                    tell(p, "Sold $n bags at ${naira(each)} each for ${naira(total)}.")
                    mission(p, 1, 1000L)
                }
                "visit" -> {
                    val last = p.visits[id]
                    if (last != null && last == day) {
                        tell(p, "You already visited ${pl.name} today.")
                        return
                    }
                    val cost = when (id) {
                        P_MUSEUM -> 500L
                        P_KADA -> 3000L
                        P_ZOO -> 800L
                        else -> 0L
                    }
                    if (p.cash < cost) {
                        tell(p, "Entry costs ${naira(cost)}.")
                        return
                    }
                    p.cash -= cost
                    val gain = if (id == P_KADA) 4 else 2
                    p.rep = min(100, p.rep + gain)
                    p.visits[id] = day
                    busy(p, 4000L, "Visiting ${pl.name}")
                    tell(p, "You spent time at ${pl.name}. Reputation +$gain.")
                }
                "keke" -> {
                    val to = o.optInt("to", -1)
                    if (to < 0 || to >= PLACES.size || to == id) return
                    val dest = PLACES[to]
                    val dist = hypot((dest.x - pl.x).toDouble(), (dest.y - pl.y).toDouble())
                    var fare = kekeFare(p, dist)
                    val ms = kekeMs(dist)
                    if (isNight() && p.cls != C_DRIVER) {
                        fare = fare * 5 / 2
                        val pd = Pending("nightkeke", 0L)
                        pd.a = to
                        pd.b = ms.toInt()
                        pd.amt = fare
                        ask(
                            p, pd, "Night keke",
                            "It is late and keke are scarce. One stops. The driver says '${naira(fare)}' to ${dest.name}. The normal price is ${naira(fare * 2 / 5)}. Wetin you do?",
                            listOf("Pay ${naira(fare)}", "Argue", "Trek (dangerous)")
                        )
                        return
                    }
                    if (p.cash < fare) {
                        tell(p, "You cannot afford the keke fare (${naira(fare)}).")
                        return
                    }
                    p.cash -= fare
                    tell(p, if (fare == 0L) "You drive yourself there." else "Keke fare paid: ${naira(fare)}.")
                    travel(p, dest, ms, "Riding to ${dest.name}")
                }
                "bus" -> {
                    val to = o.optInt("to", -1)
                    if (to < 0 || to >= PLACES.size || to == id) return
                    val dest = PLACES[to]
                    if (!dest.acts.contains("bus")) return
                    if (hour < 6.0 || hour >= 20.0) {
                        tell(p, "The buses have stopped for the night. Take a keke.")
                        return
                    }
                    val dist = hypot((dest.x - pl.x).toDouble(), (dest.y - pl.y).toDouble())
                    var fare = 60.0 + dist * 0.18
                    if (ev != null && ev.fuel) fare *= 2.3
                    if (p.rep >= 70) fare *= 0.8
                    val f = max(100L, fare.toLong())
                    if (p.cash < f) {
                        tell(p, "The bus fare is ${naira(f)}.")
                        return
                    }
                    p.cash -= f
                    val tm = if (ev != null) ev.travelTime else 1.0
                    val ms = ((3500.0 + dist * 1.6) * tm).toLong()
                    val roll = rnd.nextInt(100)
                    if (roll < 65 && !(ev != null && ev.fuel && roll < 25)) {
                        tell(p, "Conductor: '${naira(f)}! Enter!' You paid ${naira(f)}.")
                        travel(p, dest, ms, "Riding the bus to ${dest.name}")
                    } else if (roll < 85) {
                        val pd = Pending("busload", 0L)
                        pd.a = to
                        pd.amt = f
                        pd.b = ms.toInt()
                        pd.c = dist.toInt()
                        ask(
                            p, pd, "Conductor wahala",
                            "You paid ${naira(f)} and sat down. The bus is not full. The conductor still dey shout '${naira(f)}! Enter with your change!' Ten minutes. Fifteen minutes. Wetin you do?",
                            listOf("Wait for it to fill", "Pay ${naira(200L)} extra to leave now", "Trek instead (refund)", "Take a keke instead (refund)")
                        )
                    } else {
                        val pd = Pending("busbreak", 0L)
                        pd.a = to
                        pd.amt = f
                        pd.b = ms.toInt()
                        pd.c = dist.toInt()
                        ask(
                            p, pd, "Bus breakdown",
                            "Halfway to ${dest.name} the bus spoils. The driver says: 'Everybody come down. Another bus dey come.' Wetin you do?",
                            listOf("Wait for another bus", "Trek the rest", "Take a keke (${naira(500L)})", "Call a friend")
                        )
                    }
                }
                "bank" -> bankAct(p, o)
                "atm" -> atmAct(p, o, litHere)
                "pos" -> posAct(p, o, pl, litHere)
                "forex" -> forexAct(p, o, litHere)
                "stand" -> {
                    if (!litHere) {
                        tell(p, "No light. Your POS machine is dead.")
                        return
                    }
                    if (p.energy < 16) {
                        tell(p, "Too tired to run a stand.")
                        return
                    }
                    val fuel = ev != null && ev.fuel
                    var earn = 1200L + rnd.nextInt(2000)
                    if (fuel) earn *= 3
                    if (isNight()) earn = earn * 7 / 10
                    p.energy -= 16
                    var extra = ""
                    if (rnd.nextInt(100) < 5) {
                        val l = min(p.cash, 3000L + rnd.nextInt(5000))
                        p.cash -= l
                        extra = " Robbers snatched ${naira(l)} from your cash box!"
                    }
                    if (pl.tout && rnd.nextInt(100) < 20) {
                        val t = min(p.cash, 500L)
                        p.cash -= t
                        extra += " The boys took ${naira(t)} 'ticket'."
                    }
                    p.cash += earn
                    busy(p, 6000L, "Running a POS stand")
                    tell(p, "A busy shift. You earned ${naira(earn)} in fees.$extra")
                }
                "drive" -> {
                    if (p.energy < 20) {
                        tell(p, "Too tired to drive passengers.")
                        return
                    }
                    val fuel = ev != null && ev.fuel
                    val demand = when (id) {
                        P_KINGS, P_UNIBEN -> 1.3
                        P_AIRPORT -> 1.4
                        P_OBA -> 1.2
                        else -> 1.0
                    }
                    val bonus = o.optDouble("bonus", 1.0).coerceIn(0.7, 1.5)
                    val gross = (5200.0 * demand * bonus * (if (fuel) 1.6 else 1.0)).toLong()
                    val cost = (1200.0 * (if (ev != null) ev.travel else 1.0)).toLong()
                    p.energy -= 20
                    val net = gross - cost
                    p.cash += net
                    p.rep = min(100, p.rep + 1)
                    busy(p, 7000L, "Carrying passengers")
                    tell(p, "You carried passengers all shift. ${naira(gross)} earned, ${naira(cost)} on fuel: ${naira(net)} profit.")
                }
                "collect" -> {
                    if (p.energy < 8) {
                        tell(p, "Too tired to stand at the junction.")
                        return
                    }
                    var earn = 800L + rnd.nextInt(2700)
                    if (ev != null && ev.fuel) earn = earn * 14 / 10
                    p.energy -= 8
                    p.rep = max(0, p.rep - 2)
                    var extra = ""
                    if (rnd.nextInt(100) < 10) {
                        p.heat += 1
                        val l = min(p.cash, 2000L)
                        p.cash -= l
                        extra = " Police came round and you paid ${naira(l)} to disappear."
                    }
                    p.cash += earn
                    busy(p, 5000L, "Collecting tickets")
                    tell(p, "You collected ${naira(earn)} from the passers-by. Reputation -2.$extra")
                    heatCheck(p)
                }
                "gig" -> {
                    if (p.energy < 15) {
                        tell(p, "Too tired.")
                        return
                    }
                    p.energy -= 15
                    val r = rnd.nextInt(100)
                    if (r < 50) {
                        val g = 12000L + rnd.nextInt(16000)
                        p.cash += g
                        busy(p, 7000L, "On the laptop")
                        tell(p, "The gig paid ${naira(g)}.")
                        val pd = Pending("cashout", 0L)
                        pd.amt = g
                        ask(
                            p, pd, "You just cashed out ${naira(g)}",
                            "Money dey your hand. Everybody is watching what you will do. Wetin you do?",
                            listOf("Blow am (party, fine clothes, flex)", "Stack am (save it quietly)", "Mix am (spend small, save small)")
                        )
                    } else if (r < 80) {
                        busy(p, 6000L, "On the laptop")
                        tell(p, "The client vanished. A whole day wasted.")
                    } else {
                        val l = min(p.cash, 12000L)
                        p.cash -= l
                        p.rep = max(0, p.rep - 8)
                        p.heat += 1 + (if (p.flex >= 3) 1 else 0)
                        busy(p, 12000L, "Police wahala")
                        tell(p, "Police wahala! You paid ${naira(l)} to settle it. Reputation -8.")
                        heatCheck(p)
                    }
                }
            }
        }
    }

    // ---------- banks, ATMs, POS, dollars, sending money ----------

    private fun bankAct(p: Player, o: JSONObject) {
        val hour = hr()
        val close = if (event != null && event!!.fuel) 14.0 else 17.0
        if (hour < 8.0 || hour >= close) {
            tell(p, "The bank is closed. It opens at 8:00 and closes at ${close.toInt()}:00.")
            return
        }
        if (!lit(zoneAt(p.x, p.y))) {
            tell(p, "No light. The bank is closed.")
            return
        }
        val op = o.optString("op")
        val n = o.optLong("n", 0L)
        var delay = 3000L
        if (rnd.nextInt(100) < 15) {
            delay = 12000L
            tell(p, "Queue wahala: forty-five minutes, two tellers, one window closed. At last it is your turn.")
        }
        when (op) {
            "open" -> {
                if (p.acct) {
                    tell(p, "You already have an account.")
                    return
                }
                if (p.cash < 1000L) {
                    tell(p, "Opening an account costs ${naira(1000L)}.")
                    return
                }
                p.cash -= 1000L
                p.acct = true
                mission(p, 3, 1000L)
                busy(p, delay, "Opening an account")
                tell(p, "Your account is open. You can now deposit, withdraw and send money.")
            }
            "dep" -> {
                if (!p.acct) {
                    tell(p, "You need an account first.")
                    return
                }
                val amt = min(n, p.cash)
                val fee = max(20L, amt / 200)
                if (amt <= fee) {
                    tell(p, "You have no cash to deposit.")
                    return
                }
                p.cash -= amt
                p.bank += amt - fee
                busy(p, delay, "Depositing")
                tell(p, "Deposited ${naira(amt)} (fee ${naira(fee)}). Safe from robbers now. Bank: ${naira(p.bank)}.")
            }
            "wdr" -> {
                if (!p.acct) {
                    tell(p, "You need an account first.")
                    return
                }
                val amt = min(n, p.bank)
                val fee = max(20L, amt / 200)
                if (amt <= fee) {
                    tell(p, "You have nothing to withdraw.")
                    return
                }
                p.bank -= amt
                p.cash += amt - fee
                busy(p, delay, "Withdrawing")
                tell(p, "Withdrew ${naira(amt)} (fee ${naira(fee)}). Careful with cash on the street.")
            }
        }
    }

    private fun atmAct(p: Player, o: JSONObject, litHere: Boolean) {
        if (!litHere) {
            tell(p, "The ATM is down. No light.")
            return
        }
        if (!p.acct) {
            tell(p, "You need a bank account first. Open one at a bank branch.")
            return
        }
        val amt = min(o.optLong("n", 0L), p.bank)
        val fee = max(30L, amt / 100)
        if (amt <= fee) {
            tell(p, "Your balance is too low.")
            return
        }
        if (rnd.nextInt(100) < 12) {
            busy(p, 3000L, "At the ATM")
            tell(p, "Unable to dispense cash. You try another machine later.")
            return
        }
        p.bank -= amt
        p.cash += amt - fee
        busy(p, 3000L, "At the ATM")
        var m = "ATM paid out ${naira(amt)} (fee ${naira(fee)})."
        if (isNight() && rnd.nextInt(100) < 30) {
            m += " Someone is watching you from across the road..."
            tell(p, m)
            startFoot(p)
            return
        }
        tell(p, m)
    }

    private fun posAct(p: Player, o: JSONObject, pl: Place, litHere: Boolean) {
        if (!litHere) {
            tell(p, "Network down. No light, so the POS is dead.")
            return
        }
        if (!p.acct) {
            tell(p, "You need a bank account first. Open one at a bank branch.")
            return
        }
        val ev = event
        var mult = 1.0
        val hour = hr()
        if (hour >= 18.0 && hour < 21.0) mult = 1.5
        if (isNight()) mult = 3.0
        if (isWeekend()) mult *= 1.5
        if (ev != null && ev.fuel) mult *= 2.5
        val amt = min(o.optLong("n", 0L), p.bank)
        var fee = max(100L, (amt * 0.02 * mult).toLong())
        if (p.cls == C_POSOP) fee = 0L
        if (amt <= fee) {
            tell(p, "Your balance is too low for that withdrawal.")
            return
        }
        if (isNight() && rnd.nextInt(100) < 50) {
            tell(p, "Most POS stands have closed. The one still open is charging triple.")
        }
        p.bank -= amt
        if (rnd.nextInt(100) < 4) {
            p.refund = amt
            p.refundDay = currentDay() + 1
            busy(p, 4000L, "At the POS")
            tell(p, "Network failure: you were debited but no cash came. 'Come back tomorrow.' The money should return tomorrow.")
            return
        }
        p.cash += amt - fee
        busy(p, 4000L, "At the POS")
        tell(p, "POS paid out ${naira(amt)} with a ${naira(fee)} charge.")
    }

    private fun forexAct(p: Player, o: JSONObject, litHere: Boolean) {
        val hour = hr()
        if (hour < 9.0 || hour >= 17.0) {
            tell(p, "The Bureau de Change has closed for the day. Come back from 9:00.")
            return
        }
        val japa = p.cls == C_JAPA
        var spread = if (japa) 0.005 else 0.015
        if (!litHere) spread *= 1.5
        val r = rate()
        val op = o.optString("op")
        val n = max(0, o.optInt("n", 0))
        if (op == "buy") {
            if (n <= 0) return
            val price = r * (1.0 + spread)
            val cost = (n * price).toLong()
            if (p.cash < cost) {
                tell(p, "$n dollars at ${naira(price.toLong())} each is ${naira(cost)}. You do not have that much cash.")
                return
            }
            p.cash -= cost
            p.usd += n
            var m = "You bought \$$n at ${naira(price.toLong())} each for ${naira(cost)}."
            if (!japa && rnd.nextInt(100) < 6) {
                p.fake += n
                m += " You count the notes carefully... they look fine."
            }
            busy(p, 3000L, "Counting dollars")
            tell(p, m)
        } else if (op == "sell") {
            val sellN = min(n, p.usd)
            if (sellN <= 0) {
                tell(p, "You have no dollars to sell.")
                return
            }
            val price = r * (1.0 - spread - 0.01)
            val genuine = max(0, p.usd - p.fake)
            val good = min(sellN, genuine)
            val bad = sellN - good
            var revenue = (good * price).toLong()
            p.usd -= sellN
            p.fake -= bad
            var m = "You sold \$$sellN."
            if (bad > 0) m += " The operator checks the notes: \$$bad were FAKE and worthless."
            if (!japa && good > 0 && rnd.nextInt(100) < 5) {
                val short = min(20000L, revenue / 10)
                revenue -= short
                m += " He counted the naira too fast. When you check later, it is ${naira(short)} short."
            }
            p.cash += revenue
            if (good > 0) mission(p, 4, 2000L)
            busy(p, 3000L, "Counting naira")
            tell(p, "$m You got ${naira(revenue)}.")
        }
    }

    private fun transfer(p: Player, o: JSONObject) {
        val vid = o.optInt("to", -1)
        val v = players.firstOrNull { it.id == vid } ?: return
        if (v === p || !v.online) return
        val n = max(0L, o.optLong("n", 0L))
        if (n <= 0L) return
        val mode = o.optString("mode")
        if (mode == "cash") {
            if (hypot((v.x - p.x).toDouble(), (v.y - p.y).toDouble()) > 260.0) {
                tell(p, "${v.name} is too far away to hand cash to.")
                return
            }
            if (p.cash < n) {
                tell(p, "You do not have ${naira(n)} in cash.")
                return
            }
            p.cash -= n
            v.cash += n
            tell(p, "You gave ${v.name} ${naira(n)} in cash.")
            tell(v, "${p.name} gave you ${naira(n)} in cash.")
        } else {
            if (!p.acct || !v.acct) {
                tell(p, "Both of you need bank accounts for a transfer.")
                return
            }
            if (!lit(zoneAt(p.x, p.y))) {
                tell(p, "No light, no network. The transfer will not go.")
                return
            }
            val fee = max(50L, n / 300)
            if (p.bank < n + fee) {
                tell(p, "Your bank balance is not enough for ${naira(n)} plus ${naira(fee)} fee.")
                return
            }
            p.bank -= n + fee
            v.bank += n
            tell(p, "Transferred ${naira(n)} to ${v.name} (fee ${naira(fee)}).")
            tell(v, "Credit alert: ${naira(n)} from ${p.name}.")
        }
    }

    // ---------- the clock ----------

    private fun advance(dtMs: Long) {
        hours += 24.0 * dtMs / dayMs
        val hi = hours.toInt()
        while (lastHour < hi) {
            lastHour++
            hourly()
        }
        val day = (hours / 24.0).toInt() + 1
        while (lastDay < day && phase == "playing") {
            lastDay++
            newDay(lastDay)
        }
    }

    /** NEPA decides, every hour, whether to take light. */
    private fun hourly() {
        val fuel = event?.fuel == true
        val base = doubleArrayOf(0.04, 0.07, 0.06, 0.06, 0.02)
        for (z in 0 until ZONES) {
            if (out[z]) {
                if (hours >= backAt[z]) {
                    out[z] = false
                    notifyZone(z, "Up NEPA! Light is back in ${ZONE_NAMES[z]}.")
                }
            } else {
                val chance = base[z] * (if (fuel) 2.2 else 1.0) * (if (isNight()) 1.2 else 1.0)
                if (rnd.nextDouble() < chance) {
                    out[z] = true
                    backAt[z] = hours + 2 + rnd.nextInt(if (fuel) 8 else 5)
                    notifyZone(z, "Light don go in ${ZONE_NAMES[z]}! POS, banks and ATMs are dead there until NEPA brings it back.")
                }
            }
        }
    }

    private fun newDay(day: Int) {
        if (day > TOTAL_DAYS) {
            phase = "ended"
            announce("The game is over! See who built the best life in Benin City.")
            val cb = onSave
            if (cb != null) cb("")
            return
        }
        val old = event
        if (old != null && day >= eventEndDay) {
            announce("${old.title} is over.")
            event = null
        }
        if (event == null && day >= 2 && rnd.nextInt(100) < 40) {
            val e = EVENTS[rnd.nextInt(EVENTS.size)]
            event = e
            eventEndDay = day + e.days
            announce("${e.title}: ${e.text}")
        }
        for (i in drift.indices) {
            drift[i] = (drift[i] + (rnd.nextDouble() - 0.5) * 0.18).coerceIn(0.8, 1.25)
        }
        fxBase = (fxBase * (1.0 + (rnd.nextDouble() - 0.5) * 0.05)).coerceIn(1400.0, 2200.0)
        for (p in players) {
            p.food = max(0, p.food - 32)
            p.energy = max(0, p.energy - (if (p.hurt) 12 else 6))
            if (p.food == 0) {
                p.energy = max(0, p.energy - 15)
                p.rep = max(0, p.rep - 3)
                tell(p, "You are starving! Eat something.")
            }
            if (p.cls == C_CIVIL) {
                p.bank += 5500L
            }
            if (p.refund > 0L && day >= p.refundDay) {
                p.bank += p.refund
                tell(p, "Your failed POS withdrawal of ${naira(p.refund)} has been reversed into your account.")
                p.refund = 0L
            }
            if (day % 3 == 0 && p.heat > 0) p.heat -= 1
            if (p.housing == 3) p.rep = min(100, p.rep + 1)
            if (p.housing == 5) p.energy = max(0, p.energy - 10)
            if (p.housing == 4 && day % 7 == 0 && rnd.nextInt(100) < 25) {
                p.rep = max(0, p.rep - 3)
                tell(p, "Tension at your friend's place. They want you to find your own room soon. Reputation -3.")
            }
            if (p.flex > 0 && day % 5 == 0) p.flex -= 1
            if (day % RENT_EVERY == 0) {
                var due = p.rent
                val fromCash = min(due, p.cash)
                p.cash -= fromCash
                due -= fromCash
                val fromBank = min(due, p.bank)
                p.bank -= fromBank
                due -= fromBank
                if (due <= 0L) {
                    tell(p, "Rent paid: ${naira(p.rent)}." + (if (fromBank > 0L) " Part of it came from your bank account." else ""))
                } else {
                    p.rep = max(0, p.rep - 10)
                    tell(p, "You could not pay your rent in full. The landlord is angry. Reputation -10.")
                }
            }
        }
    }

    // ---------- sending the world to everyone ----------

    private fun stateJson(): JSONObject {
        val o = JSONObject()
        val day = currentDay()
        val r = rate()
        o.put("t", "state")
        o.put("phase", phase)
        o.put("day", day)
        o.put("hour", hr())
        o.put("total", TOTAL_DAYS)
        o.put("host", hostName)
        o.put("fx", r)
        o.put("resumed", resumed)
        o.put("rentIn", ((day / RENT_EVERY) + 1) * RENT_EVERY - day)
        val ev = event
        if (ev != null) {
            o.put("ev", ev.title)
            o.put("evText", ev.text)
            o.put("rain", ev.rain)
            o.put("dark", ev.dark)
            o.put("fuel", ev.fuel)
        }
        val lt = JSONArray()
        for (z in 0 until ZONES) lt.put(if (lit(z)) 1 else 0)
        o.put("lit", lt)
        val gp = JSONArray()
        for (i in PLACES.indices) gp.put(goodsPrice(i))
        o.put("gp", gp)
        val now = SystemClock.elapsedRealtime()
        val arr = JSONArray()
        for (p in players) {
            val b = JSONObject()
            b.put("id", p.id)
            b.put("n", p.name)
            b.put("cl", p.cls)
            b.put("c", p.cash)
            b.put("e", p.energy)
            b.put("f", p.food)
            b.put("r", p.rep)
            b.put("k", p.know)
            b.put("g", p.goods)
            b.put("bk", p.bank)
            b.put("ac", p.acct)
            b.put("us", p.usd)
            b.put("rt", p.rent)
            b.put("hu", p.hurt)
            b.put("hm", p.housing)
            b.put("ms", p.mdone)
            b.put("w", p.worth(r))
            b.put("lv", levelOf(p.worth(r)))
            b.put("x", p.x.toDouble())
            b.put("y", p.y.toDouble())
            b.put("b", max(0L, p.busyUntil - now))
            b.put("bt", p.busyTotal)
            b.put("bl", p.busyLabel)
            b.put("on", p.online)
            arr.put(b)
        }
        o.put("players", arr)
        return o
    }

    private fun broadcastState() {
        synchronized(lock) {
            if (conns.isEmpty()) return
            val o = stateJson()
            sendAll(o)
        }
    }

    private fun broadcastPositions() {
        synchronized(lock) {
            if (conns.isEmpty() || phase != "playing") return
            val now = SystemClock.elapsedRealtime()
            val arr = JSONArray()
            for (p in players) {
                val a = JSONArray()
                a.put(p.id)
                a.put(p.x.toDouble())
                a.put(p.y.toDouble())
                a.put(if (p.busyUntil > now) 1 else 0)
                arr.put(a)
            }
            val o = JSONObject().put("t", "pos").put("p", arr)
            sendAll(o)
        }
    }

    /** Sends to every connection, putting each player's own id in so they know which one is them. */
    private fun sendAll(o: JSONObject) {
        val dead = ArrayList<Conn>()
        for (c in conns) {
            val p = c.player ?: continue
            o.put("you", p.id)
            if (!c.send(o.toString())) dead.add(c)
        }
        for (c in dead) {
            conns.remove(c)
            val p = c.player
            if (p != null) {
                if (phase == "lobby" && !resumed) players.remove(p) else p.online = false
            }
            try {
                c.socket.close()
            } catch (e: Exception) {
            }
        }
    }
}
