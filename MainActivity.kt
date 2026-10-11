package com.nagi.hustle

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One line of the rankings. */
class Rank(val id: Int, val name: String, val worth: Long, val lvl: Int, val cls: Int, val online: Boolean)

class MainActivity : AppCompatActivity() {

    private var server: GameServer? = null
    private var client: GameClient? = null
    private var finder: RoomFinder? = null
    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())

    private var screen = ""
    private var isHost = false
    private var myName = ""
    private var myId = -1
    private var myCls = 1
    private var lastState: JSONObject? = null
    private var gameView: GameView? = null
    private var encDialog: AlertDialog? = null
    private var encTimer: Runnable? = null

    private var roomsBox: LinearLayout? = null
    private var nameField: EditText? = null
    private var lobbyText: TextView? = null
    private var startBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Colors.INK)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    "game" -> menuDialog()
                    "hero", "lobby", "end" -> showMenu()
                    else -> finish()
                }
            }
        })
        showMenu()
    }

    override fun onDestroy() {
        stopNet()
        super.onDestroy()
    }

    // ---------- small helpers ----------

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(text: String, accent: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(if (accent) Colors.INK else Colors.PAPER)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (accent) Colors.ACCENT else Colors.CARD2)
        }
        setOnClickListener { onClick() }
    }

    private fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun field(hint: String, number: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Colors.MUTED)
        setTextColor(Colors.PAPER)
        textSize = 16f
        setSingleLine(true)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Colors.CARD)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(top)
        return p
    }

    private fun setScreen(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun stopNet() {
        closeEnc()
        finder?.stop()
        finder = null
        client?.close()
        client = null
        server?.stop()
        server = null
        gameView = null
        isHost = false
    }

    private fun prefs() = getSharedPreferences("hustle", MODE_PRIVATE)

    // ---------- menu ----------

    private fun showMenu() {
        stopNet()
        screen = "menu"
        lastState = null
        val col = vbox()
        col.setPadding(dp(20), dp(28), dp(20), dp(24))

        col.addView(tv("BENIN HUSTLE", 32f, Colors.PAPER, true))
        col.addView(tv("Live the real Benin City hustle with up to $MAX_PLAYERS friends on the same Wi-Fi. No internet needed.", 14f, Colors.MUTED), lp(4))

        val nf = field("Your name")
        nf.setText(prefs().getString("name", "") ?: "")
        nameField = nf
        col.addView(nf, lp(22))

        col.addView(btn("Host a new game", true) {
            if (nameValue() != null) chooseHero { lengthDialog { ms -> startHost(false, ms) } }
        }, lp(14))

        val save = prefs().getString("save", "") ?: ""
        if (save.isNotEmpty()) {
            var day = 0
            try {
                day = JSONObject(save).optInt("day", 0)
            } catch (e: Exception) {
            }
            col.addView(btn("Continue saved game (day $day)", false) {
                if (nameValue() != null) startHost(true, DAY_MS)
            }, lp(10))
            col.addView(tv("Saved games are kept on the host's phone. Everyone rejoins with the same name.", 12f, Colors.MUTED), lp(4))
        }

        col.addView(tv("Games nearby", 16f, Colors.PAPER, true), lp(24))
        val box = vbox()
        roomsBox = box
        box.addView(tv("Looking for hosts on this Wi-Fi...", 13f, Colors.MUTED))
        col.addView(box, lp(8))

        col.addView(tv("Or type the host's address", 13f, Colors.MUTED), lp(18))
        val ipf = field("For example 192.168.43.1", true)
        col.addView(ipf, lp(6))
        col.addView(btn("Join with address", false) {
            val ip = ipf.text.toString().trim()
            if (ip.isEmpty()) toast("Type the address shown on the host's screen.")
            else if (nameValue() != null) chooseHero { joinRoom(ip) }
        }, lp(8))

        col.addView(
            tv(
                "How to play: everyone joins the same Wi-Fi (or the host's hotspot). One person taps Host a new game, " +
                    "the others pick that room, and the host starts when everyone is in. Pick who you are, walk around the city, " +
                    "work, eat, rest, bank your money, and pay your rent. The richest, most respected player after $TOTAL_DAYS days wins.",
                13f, Colors.MUTED
            ), lp(22)
        )

        val sv = ScrollView(this)
        sv.addView(col)
        setScreen(sv)

        val f = RoomFinder { list -> ui.post { showRooms(list) } }
        finder = f
        f.start()
    }

    private fun showRooms(list: List<RoomFinder.Room>) {
        val box = roomsBox ?: return
        if (screen != "menu") return
        box.removeAllViews()
        if (list.isEmpty()) {
            box.addView(tv("No games found yet. Ask the host to open one, and make sure you are on the same Wi-Fi.", 13f, Colors.MUTED))
            return
        }
        for (room in list) {
            box.addView(btn("Join ${room.name}'s game  (${room.count}/$MAX_PLAYERS)", false) {
                if (nameValue() != null) chooseHero { joinRoom(room.ip) }
            }, lp(6))
        }
    }

    private fun nameValue(): String? {
        val n = nameField?.text?.toString()?.trim() ?: ""
        if (n.isEmpty()) {
            toast("Type your name first.")
            return null
        }
        prefs().edit().putString("name", n).apply()
        myName = n
        return n
    }

    // ---------- choosing who you are ----------

    private fun chooseHero(then: () -> Unit) {
        finder?.stop()
        finder = null
        screen = "hero"
        var sel = prefs().getInt("cls", 1).coerceIn(0, CLASSES.size - 1)
        val col = vbox()
        col.setPadding(dp(18), dp(24), dp(18), dp(24))
        col.addView(tv("Who are you in Benin City?", 24f, Colors.PAPER, true))
        col.addView(tv("Pick the life you start with. Everybody dey hustle, but not in the same way.", 13f, Colors.MUTED), lp(4))

        val cards = ArrayList<LinearLayout>()
        fun paint() {
            for (i in cards.indices) {
                cards[i].background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (i == sel) 0xFF2A3440.toInt() else Colors.CARD)
                    setStroke(dp(2), if (i == sel) Colors.ACCENT else 0x00000000)
                }
            }
        }
        for (hc in CLASSES) {
            val card = vbox()
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.addView(tv(hc.name, 17f, Colors.PAPER, true))
            card.addView(tv(hc.tag, 13f, Colors.MUTED), lp(2))
            card.addView(tv(hc.perk, 13f, Colors.GOLD), lp(4))
            var start = "Starts with " + naira(hc.cash) + " at " + PLACES[hc.place].name
            if (hc.usd > 0) start += " and \$" + hc.usd
            card.addView(tv(start, 12f, Colors.MUTED), lp(4))
            card.setOnClickListener {
                sel = hc.id
                paint()
            }
            cards.add(card)
            col.addView(card, lp(10))
        }
        paint()
        col.addView(btn("Start with this life", true) {
            myCls = sel
            prefs().edit().putInt("cls", sel).apply()
            then()
        }, lp(18))
        col.addView(btn("Back", false) { showMenu() }, lp(8))
        val sv = ScrollView(this)
        sv.addView(col)
        setScreen(sv)
    }

    // ---------- connecting ----------

    private fun lengthDialog(then: (Long) -> Unit) {
        val labels = listOf("Quick game (about 20 minutes)", "Standard game (about 40 minutes)", "Long game (about 80 minutes)")
        val vals = longArrayOf(30000L, 60000L, 120000L)
        AlertDialog.Builder(this)
            .setTitle("How long should the game be?")
            .setItems(items(labels)) { _, i -> then(vals[i]) }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun startHost(resume: Boolean, dayMs: Long) {
        val name = nameValue() ?: return
        finder?.stop()
        finder = null
        val s = GameServer(name, dayMs)
        if (resume) {
            val ok = s.loadSave(prefs().getString("save", "") ?: "")
            if (!ok) {
                toast("The saved game could not be loaded.")
                return
            }
        }
        s.onSave = { text -> prefs().edit().putString("save", text).apply() }
        thread(isDaemon = true) {
            try {
                s.start()
                ui.post {
                    server = s
                    isHost = true
                    connect("127.0.0.1", name)
                }
            } catch (e: Exception) {
                ui.post { toast("Could not open a room: ${e.message}") }
            }
        }
    }

    private fun joinRoom(ip: String) {
        val name = nameValue() ?: return
        finder?.stop()
        finder = null
        isHost = false
        connect(ip, name)
    }

    private fun connect(ip: String, name: String) {
        myName = name
        val cl = GameClient()
        client = cl
        cl.onState = { o -> ui.post { onState(o) } }
        cl.onMsg = { m, big -> ui.post { gameView?.toast(m, big) ?: toast(m) } }
        cl.onPos = { o -> ui.post { gameView?.applyPositions(o) } }
        cl.onTp = { x, y, ms -> ui.post { gameView?.teleport(x, y, ms) } }
        cl.onEnc = { title, text, opts, secs -> ui.post { showEnc(title, text, opts, secs) } }
        cl.onEncEnd = { ui.post { closeEnc() } }
        cl.onReveal = { id, _ -> ui.post { gameView?.reveal(id) } }
        cl.onClosed = { why ->
            ui.post {
                toast(why)
                showMenu()
            }
        }
        showLobby()
        cl.connect(ip, name, myCls)
    }

    private fun onState(o: JSONObject) {
        lastState = o
        myId = o.optInt("you", -1)
        when (o.optString("phase")) {
            "lobby" -> {
                if (screen != "lobby") showLobby()
                updateLobby(o)
            }
            "playing" -> {
                if (screen != "game") showGame()
                gameView?.applyState(o)
            }
            "ended" -> {
                if (screen != "end") showEnd()
                updateEnd(o)
            }
        }
    }

    private fun ranks(o: JSONObject): List<Rank> {
        val list = ArrayList<Rank>()
        val arr = o.optJSONArray("players") ?: return list
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            list.add(Rank(b.getInt("id"), b.getString("n"), b.optLong("w", 0L), b.optInt("lv", 1), b.optInt("cl", 1), b.optBoolean("on", true)))
        }
        list.sortByDescending { it.worth }
        return list
    }

    // ---------- lobby ----------

    private fun showLobby() {
        screen = "lobby"
        val col = vbox()
        col.setPadding(dp(20), dp(28), dp(20), dp(24))
        col.addView(tv("Waiting room", 26f, Colors.PAPER, true))
        val t = tv("Connecting...", 15f, Colors.PAPER)
        lobbyText = t
        col.addView(t, lp(16))

        if (isHost) {
            val ips = localIps()
            val addr = if (ips.isEmpty()) "Connect this phone to Wi-Fi or turn on your hotspot." else
                "Friends can also join by typing: " + ips.joinToString("  or  ")
            col.addView(tv(addr, 13f, Colors.MUTED), lp(14))
            col.addView(tv("Keep this phone awake and this app open. If it closes, the game stops for everybody.", 12f, Colors.MUTED), lp(8))
            val b = btn(if (server?.resumed == true) "Resume the game" else "Start the game", true) { server?.startGame() }
            startBtn = b
            col.addView(b, lp(20))
        } else {
            col.addView(tv("The host will start the game when everyone is in.", 13f, Colors.MUTED), lp(14))
        }
        col.addView(btn("Leave", false) { showMenu() }, lp(14))
        val sv = ScrollView(this)
        sv.addView(col)
        setScreen(sv)
    }

    private fun updateLobby(o: JSONObject) {
        val list = ranks(o)
        val sb = StringBuilder()
        sb.append("Players (${list.size}/$MAX_PLAYERS):\n")
        for (r in list) {
            sb.append("• ").append(r.name).append("  -  ").append(CLASSES[r.cls.coerceIn(0, CLASSES.size - 1)].name)
            if (r.id == myId) sb.append("  (you)")
            sb.append("\n")
        }
        lobbyText?.text = sb.toString()
    }

    // ---------- the game ----------

    private fun showGame() {
        screen = "game"
        val gv = GameView(this)
        gameView = gv
        gv.onPos = { x, y -> client?.send(JSONObject().put("t", "pos").put("x", x.toDouble()).put("y", y.toDouble())) }
        gv.onMenu = { menuDialog() }
        gv.onKeke = { pl -> destDialog("keke", pl) }
        gv.onAction = { act, pl, bonus -> doAction(act, pl, bonus) }
        setScreen(gv)
        val st = lastState
        if (st != null) gv.applyState(st)
    }

    private fun send(o: JSONObject) {
        client?.send(o)
    }

    private fun act(a: String, pl: Int): JSONObject = JSONObject().put("t", "act").put("a", a).put("pl", pl)

    private fun doAction(act: String, pl: Int, bonus: Double) {
        when (act) {
            "bank" -> bankDialog(pl)
            "atm" -> cashOutDialog("ATM", "atm", pl)
            "pos" -> cashOutDialog("POS", "pos", pl)
            "forex" -> forexDialog(pl)
            "bus" -> destDialog("bus", pl)
            else -> send(act(act, pl).put("bonus", bonus))
        }
    }

    private fun items(list: List<String>): Array<CharSequence> = Array(list.size) { list[it] }

    private fun destDialog(mode: String, from: Int) {
        val here = PLACES[from]
        val ids = ArrayList<Int>()
        val names = ArrayList<String>()
        for (pl in PLACES) {
            if (pl.id == from) continue
            if (mode == "bus" && !pl.acts.contains("bus")) continue
            val dist = hypot((pl.x - here.x).toDouble(), (pl.y - here.y).toDouble())
            val fare = if (mode == "bus") max(100.0, 60.0 + dist * 0.18) else 200.0 + dist * 0.4
            ids.add(pl.id)
            names.add(pl.name + "  (about " + naira(fare.toLong()) + ")")
        }
        AlertDialog.Builder(this)
            .setTitle(if (mode == "bus") "Take the shuttle bus to..." else "Take a keke to...")
            .setItems(items(names)) { _, i -> send(act(mode, from).put("to", ids[i])) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickAmount(title: String, maxV: Long, dollars: Boolean, cb: (Long) -> Unit) {
        if (maxV <= 0L) {
            toast("There is nothing to use here.")
            return
        }
        val opts = if (dollars) longArrayOf(10, 30, 50, 100, 200) else longArrayOf(1000, 2000, 5000, 10000, 20000, 50000, 100000)
        val vals = ArrayList<Long>()
        val labels = ArrayList<String>()
        for (v in opts) {
            if (v <= maxV) {
                vals.add(v)
                labels.add(if (dollars) "\$$v" else naira(v))
            }
        }
        vals.add(maxV)
        labels.add("All (" + (if (dollars) "\$$maxV" else naira(maxV)) + ")")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items(labels)) { _, i -> cb(vals[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bankDialog(pl: Int) {
        val gv = gameView ?: return
        val labels = ArrayList<String>()
        if (!gv.myAcct()) labels.add("Open an account (" + naira(1000L) + ")")
        labels.add("Deposit cash")
        labels.add("Withdraw cash")
        AlertDialog.Builder(this)
            .setTitle("Bank  |  Cash " + naira(gv.myCash()) + "  |  Account " + naira(gv.myBank()))
            .setItems(items(labels)) { _, i ->
                val label = labels[i]
                if (label.startsWith("Open")) send(act("bank", pl).put("op", "open"))
                else if (label.startsWith("Deposit")) pickAmount("Deposit how much?", gv.myCash(), false) { n -> send(act("bank", pl).put("op", "dep").put("n", n)) }
                else pickAmount("Withdraw how much?", gv.myBank(), false) { n -> send(act("bank", pl).put("op", "wdr").put("n", n)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cashOutDialog(title: String, a: String, pl: Int) {
        val gv = gameView ?: return
        if (!gv.myAcct()) {
            toast("You need a bank account first. Open one at a bank branch.")
            return
        }
        pickAmount("$title: withdraw from your account (balance " + naira(gv.myBank()) + ")", gv.myBank(), false) { n ->
            send(act(a, pl).put("op", "wdr").put("n", n))
        }
    }

    private fun forexDialog(pl: Int) {
        val gv = gameView ?: return
        val rate = gv.dollarRate()
        val labels = ArrayList<String>()
        val ops = ArrayList<String>()
        val ns = ArrayList<Int>()
        for (n in intArrayOf(10, 30, 50, 100, 200)) {
            labels.add("Buy \$$n  (about " + naira((n * rate * 1.015).toLong()) + ")")
            ops.add("buy")
            ns.add(n)
        }
        val have = gv.myUsd()
        if (have > 0) {
            for (n in intArrayOf(10, 50, 100)) {
                if (n < have) {
                    labels.add("Sell \$$n")
                    ops.add("sell")
                    ns.add(n)
                }
            }
            labels.add("Sell all (\$$have)")
            ops.add("sell")
            ns.add(have)
        }
        AlertDialog.Builder(this)
            .setTitle("Bureau de Change  |  about " + naira(rate.toLong()) + " per dollar  |  you have \$$have")
            .setItems(items(labels)) { _, i -> send(act("forex", pl).put("op", ops[i]).put("n", ns[i])) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- the menu inside the game ----------

    private fun menuDialog() {
        val labels = listOf("Rankings", "My missions", "Where I live", "People nearby (tell your name, give cash, rob)", "Send money to someone I know (bank)", "How this city works", "Leave the game")
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items(labels)) { _, i ->
                when (i) {
                    0 -> rankingsDialog()
                    1 -> missionsDialog()
                    2 -> homeDialog()
                    3 -> nearbyDialog()
                    4 -> transferDialog()
                    5 -> helpDialog()
                    else -> confirmLeave()
                }
            }
            .setNegativeButton("Back to the game", null)
            .show()
    }

    private fun rankingsDialog() {
        val st = lastState ?: return
        val sb = StringBuilder()
        var n = 1
        for (r in ranks(st)) {
            sb.append(n).append(". ").append(r.name)
            if (r.id == myId) sb.append(" (you)")
            sb.append("\n    ").append(naira(r.worth)).append("  |  Level ").append(r.lvl).append(" ").append(LEVEL_NAMES[r.lvl.coerceIn(1, 5)])
            sb.append("  |  ").append(CLASSES[r.cls.coerceIn(0, CLASSES.size - 1)].name)
            if (!r.online) sb.append("  (offline)")
            sb.append("\n")
            n++
        }
        AlertDialog.Builder(this)
            .setTitle("Rankings (total worth)")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun nearbyDialog() {
        val gv = gameView ?: return
        val list = gv.nearby(260f)
        if (list.isEmpty()) {
            toast("Nobody is close enough. Walk up to another player first.")
            return
        }
        val names = list.map { it.second }
        AlertDialog.Builder(this)
            .setTitle("Who?")
            .setItems(items(names)) { _, i ->
                val id = list[i].first
                val nm = list[i].second
                val what = listOf("Tell them your name", "Give them cash", "Rob them (risky!)")
                AlertDialog.Builder(this)
                    .setTitle(nm)
                    .setItems(items(what)) { _, j ->
                        if (j == 0) {
                            send(JSONObject().put("t", "act").put("a", "reveal").put("to", id))
                        } else if (j == 1) {
                            pickAmount("Give how much cash?", gv.myCash(), false) { n ->
                                send(JSONObject().put("t", "act").put("a", "xfer").put("to", id).put("n", n).put("mode", "cash"))
                            }
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Rob this person?")
                                .setMessage("They can fight back, run, or call the boys. Robbing costs you reputation and brings police heat. Word spreads.")
                                .setPositiveButton("Do it") { _, _ -> send(JSONObject().put("t", "act").put("a", "rob").put("to", id)) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun transferDialog() {
        val gv = gameView ?: return
        val list = gv.everyone()
        if (list.isEmpty()) {
            toast("You only know people who told you their name. Walk up to someone and ask them to tell you.")
            return
        }
        if (!gv.myAcct()) {
            toast("You need a bank account first. Open one at a bank branch.")
            return
        }
        val names = list.map { it.second }
        AlertDialog.Builder(this)
            .setTitle("Send money to...")
            .setItems(items(names)) { _, i ->
                val id = list[i].first
                pickAmount("How much? (they need an account too, small fee)", gv.myBank(), false) { n ->
                    send(JSONObject().put("t", "act").put("a", "xfer").put("to", id).put("n", n).put("mode", "bank"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun missionsDialog() {
        val names = listOf(
            "First Move: take a keke or bus (+" + naira(500L) + ")",
            "First Trade: sell goods at a market (+" + naira(1000L) + ")",
            "Junction Tax: get past the junction boys for less (+" + naira(1000L) + ")",
            "Open an Account at a bank (+" + naira(1000L) + ")",
            "Bureau de Change Deal: sell dollars (+" + naira(2000L) + ")",
            "Blood Donation at UBTH (+" + naira(1500L) + ")",
            "Night Survivor: escape footsteps in the dark (+" + naira(1500L) + ")"
        )
        var mask = 0
        val st = lastState
        if (st != null) {
            val arr = st.optJSONArray("players")
            if (arr != null) for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.getInt("id") == myId) mask = b.optInt("ms", 0)
            }
        }
        val sb = StringBuilder()
        for (i in names.indices) {
            sb.append(if ((mask and (1 shl i)) != 0) "[done]  " else "[  ]  ").append(names[i]).append("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle("Missions")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun homeDialog() {
        val labels = ArrayList<String>()
        for (h in HOUSING) labels.add(h.name + "  |  rent " + naira(h.rent) + " a week\n" + h.desc)
        AlertDialog.Builder(this)
            .setTitle("Where do you live? (moving costs " + naira(2000L) + ")")
            .setItems(items(labels)) { _, i -> send(JSONObject().put("t", "act").put("a", "home").put("i", i)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun helpDialog() {
        val hc = CLASSES[myCls.coerceIn(0, CLASSES.size - 1)]
        val text = "You are " + hc.name + ". " + hc.perk + "\n\n" +
            "WALK with your thumb on the left. Buttons appear on the right when you reach a place.\n\n" +
            "CASH can be robbed. Put it in the bank (open an account at a bank branch, 8am to 5pm) and use ATMs and POS to get it back out.\n\n" +
            "NEPA takes light in different parts of the city. No light means no POS, no ATM, no banking, and offices close.\n\n" +
            "At junctions, boys ask for 'tickets'. Pay them four times and they will protect you.\n\n" +
            "Night is dangerous. Footsteps behind you, mad men in the road, and robbers. Travel in groups and bank your cash.\n\n" +
            "Rent is due every $RENT_EVERY days. Fuel scarcity doubles fares and cuts the power more often. " +
            "The player with the most total worth after $TOTAL_DAYS days wins."
        AlertDialog.Builder(this)
            .setTitle("How this city works")
            .setMessage(text)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Leave the game?")
            .setMessage(if (isHost) "You are the host. If you leave, the game stops for everyone. It is saved, and you can continue it from the menu later." else "Leave this game? You can rejoin with the same name.")
            .setPositiveButton("Leave") { _, _ -> showMenu() }
            .setNegativeButton("Stay", null)
            .show()
    }

    // ---------- "Wetin you do?" ----------

    private fun closeEnc() {
        val t = encTimer
        if (t != null) ui.removeCallbacks(t)
        encTimer = null
        try {
            encDialog?.dismiss()
        } catch (e: Exception) {
        }
        encDialog = null
    }

    private fun showEnc(title: String, text: String, opts: List<String>, secs: Int) {
        if (screen != "game") return
        closeEnc()
        val col = vbox()
        col.setPadding(dp(18), dp(8), dp(18), dp(8))
        col.addView(tv(text, 15f, Colors.PAPER), lp(0))
        val timer = tv("Decide quickly: ${secs}s", 12f, Colors.MUTED)
        col.addView(timer, lp(8))
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(col) }).setCancelable(false).create()
        for (i in opts.indices) {
            col.addView(btn(opts[i], i == 0) {
                send(JSONObject().put("t", "choose").put("i", i))
                closeEnc()
            }, lp(8))
        }
        encDialog = dialog
        dialog.show()
        var left = secs
        val r = object : Runnable {
            override fun run() {
                left--
                if (left < 0) return
                timer.text = "Decide quickly: ${left}s"
                ui.postDelayed(this, 1000L)
            }
        }
        encTimer = r
        ui.postDelayed(r, 1000L)
    }

    // ---------- the end ----------

    private fun showEnd() {
        closeEnc()
        screen = "end"
        val col = vbox()
        col.setPadding(dp(20), dp(28), dp(20), dp(24))
        col.addView(tv("The city has spoken", 26f, Colors.PAPER, true))
        val t = tv("", 15f, Colors.PAPER)
        lobbyText = t
        col.addView(t, lp(16))
        col.addView(btn("Back to the menu", true) { showMenu() }, lp(20))
        val sv = ScrollView(this)
        sv.addView(col)
        setScreen(sv)
    }

    private fun updateEnd(o: JSONObject) {
        val list = ranks(o)
        val sb = StringBuilder()
        if (list.isNotEmpty()) sb.append("Winner: ").append(list[0].name).append("\n\n")
        var n = 1
        for (r in list) {
            sb.append(n).append(". ").append(r.name)
            if (r.id == myId) sb.append(" (you)")
            sb.append("  -  ").append(naira(r.worth)).append("  (").append(LEVEL_NAMES[min(5, max(1, r.lvl))]).append(")\n")
            n++
        }
        lobbyText?.text = sb.toString()
    }
}
