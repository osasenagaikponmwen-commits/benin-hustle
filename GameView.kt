package com.nagi.hustle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val SKINS = intArrayOf(0xFF6B4423.toInt(), 0xFF8D5524.toInt(), 0xFF5A3A22.toInt(), 0xFFA0673A.toInt(), 0xFF3E2A1C.toInt())
private val SHIRTS = intArrayOf(
    0xFFE4572E.toInt(), 0xFF29A19C.toInt(), 0xFFF3A712.toInt(), 0xFF4D7EA8.toInt(),
    0xFFA663CC.toInt(), 0xFF59A96A.toInt(), 0xFFD7263D.toInt(), 0xFFEFEFEF.toInt()
)

/**
 * The whole game screen: the city, the people in it, and the controls drawn on top.
 * Walk with the thumb on the left, use the round buttons on the right.
 */
class GameView(context: Context) : View(context) {

    class Remote(val id: Int, var name: String) {
        var x = 0f
        var y = 0f
        var tx = 0f
        var ty = 0f
        var face = 0f
        var busy = false
        var seen = false
        var cls = 1
    }

    // ----- hooks the activity listens to -----
    var onAction: ((String, Int, Double) -> Unit)? = null
    var onKeke: ((Int) -> Unit)? = null
    var onPos: ((Float, Float) -> Unit)? = null
    var onMenu: (() -> Unit)? = null

    // ----- the world as the host last told us -----
    private val remotes = HashMap<Int, Remote>()
    private val known = HashSet<Int>()
    private var meId = -1
    private var meName = ""
    private var meX = START_X
    private var meY = START_Y
    private var meFace = 1.5f
    private var posInit = false
    private var cash = 0L
    private var energy = 100
    private var food = 80
    private var rep = 50
    private var know = 0
    private var goods = 0
    private var day = 1
    private var total = TOTAL_DAYS
    private var hour = 7f
    private var rain = false
    private var dark = false
    private var evTitle = ""
    private var evText = ""
    private var rentIn = 0
    private var rent = 45000L
    private var bank = 0L
    private var acct = false
    private var usd = 0
    private var cls = 1
    private var lvl = 1
    private var hurt = false
    private var fx = 1600.0
    private var fuel = false
    private val lit = BooleanArray(ZONES) { true }
    private val prices = IntArray(PLACES.size)
    private var busyUntil = 0L
    private var busyTotal = 0L
    private var busyLabel = ""

    // ----- local state -----
    private val d = context.resources.displayMetrics.density
    private val zoom = d * 1.05f
    private var camX = START_X
    private var camY = START_Y
    private var lastNs = System.nanoTime()
    private val t0 = System.nanoTime()
    private var lastSend = 0L
    private var sentX = -1f
    private var sentY = -1f

    private var joyId = -1
    private var joyBX = 0f
    private var joyBY = 0f
    private var joyX = 0f
    private var joyY = 0f

    private var tpOn = false
    private var tpFromX = 0f
    private var tpFromY = 0f
    private var tpToX = 0f
    private var tpToY = 0f
    private var tpStart = 0L
    private var tpMs = 0L

    private var miniOn = false
    private var miniPlace = 0
    private var miniPos = 0f
    private var miniDir = 1f

    private var curPlace = -1
    private var bannerText = ""
    private var bannerT = 0f
    private var toastText = ""
    private var toastT = 0f
    private var toastBig = false

    private val btnActs = ArrayList<String>()
    private val btnX = ArrayList<Float>()
    private val btnY = ArrayList<Float>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    init {
        World.init()
        isFocusable = true
    }

    // ---------- messages from the host ----------

    fun applyState(o: JSONObject) {
        meId = o.optInt("you", meId)
        day = o.optInt("day", 1)
        total = o.optInt("total", TOTAL_DAYS)
        hour = o.optDouble("hour", 8.0).toFloat()
        rain = o.optBoolean("rain", false)
        dark = o.optBoolean("dark", false)
        evTitle = o.optString("ev", "")
        evText = o.optString("evText", "")
        rentIn = o.optInt("rentIn", 0)
        fx = o.optDouble("fx", 1600.0)
        fuel = o.optBoolean("fuel", false)
        val lt = o.optJSONArray("lit")
        if (lt != null) for (i in 0 until min(lt.length(), ZONES)) lit[i] = lt.optInt(i, 1) == 1
        val gp = o.optJSONArray("gp")
        if (gp != null) for (i in 0 until min(gp.length(), prices.size)) prices[i] = gp.optInt(i)
        val arr = o.optJSONArray("players") ?: return
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val id = b.getInt("id")
            if (id == meId) {
                meName = b.getString("n")
                cash = b.getLong("c")
                energy = b.getInt("e")
                food = b.getInt("f")
                rep = b.getInt("r")
                know = b.optInt("k", 0)
                goods = b.optInt("g", 0)
                bank = b.optLong("bk", 0L)
                acct = b.optBoolean("ac", false)
                usd = b.optInt("us", 0)
                cls = b.optInt("cl", 1).coerceIn(0, CLASSES.size - 1)
                lvl = b.optInt("lv", 1)
                hurt = b.optBoolean("hu", false)
                rent = b.optLong("rt", rent)
                val rem = b.getLong("b")
                busyUntil = SystemClock.elapsedRealtime() + rem
                busyTotal = b.getLong("bt")
                busyLabel = b.optString("bl")
                if (!posInit) {
                    posInit = true
                    meX = b.optDouble("x", meX.toDouble()).toFloat()
                    meY = b.optDouble("y", meY.toDouble()).toFloat()
                    camX = meX
                    camY = meY
                }
            } else {
                var rm = remotes[id]
                if (rm == null) {
                    rm = Remote(id, b.getString("n"))
                    remotes[id] = rm
                }
                rm.name = b.getString("n")
                rm.cls = b.optInt("cl", 1).coerceIn(0, CLASSES.size - 1)
                if (!rm.seen) {
                    rm.seen = true
                    rm.x = b.optDouble("x", 0.0).toFloat()
                    rm.y = b.optDouble("y", 0.0).toFloat()
                    rm.tx = rm.x
                    rm.ty = rm.y
                }
            }
        }
    }

    fun applyPositions(o: JSONObject) {
        val arr = o.optJSONArray("p") ?: return
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val id = a.getInt(0)
            if (id == meId) continue
            val rm = remotes[id] ?: continue
            rm.tx = a.getDouble(1).toFloat()
            rm.ty = a.getDouble(2).toFloat()
            rm.busy = a.getInt(3) == 1
            if (!rm.seen) {
                rm.seen = true
                rm.x = rm.tx
                rm.y = rm.ty
            }
        }
    }

    fun teleport(x: Float, y: Float, ms: Long) {
        if (ms <= 0L) {
            meX = x
            meY = y
            camX = x
            camY = y
            posInit = true
            return
        }
        tpOn = true
        tpFromX = meX
        tpFromY = meY
        tpToX = x
        tpToY = y
        tpStart = SystemClock.elapsedRealtime()
        tpMs = ms
    }

    fun toast(text: String, big: Boolean) {
        toastText = text
        toastT = if (big) 6f else 4f
        toastBig = big
    }

    fun currentPlace(): Place? = if (curPlace >= 0) PLACES[curPlace] else null

    /** Other players standing within the given distance of you. */
    fun nearby(radius: Float): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>()
        for (rm in remotes.values) {
            if (rm.seen && hypot(rm.x - meX, rm.y - meY) <= radius) l.add(Pair(rm.id, label(rm) + (if (known.contains(rm.id)) "" else " (stranger)")))
        }
        return l
    }

    /** Every other player in the game. */
    fun everyone(): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>()
        for (rm in remotes.values) if (rm.seen && known.contains(rm.id)) l.add(Pair(rm.id, rm.name))
        return l
    }

    fun myCash(): Long = cash
    fun myBank(): Long = bank
    fun myAcct(): Boolean = acct
    fun myUsd(): Int = usd
    fun myClass(): Int = cls
    fun dollarRate(): Double = fx
    fun hasLight(): Boolean = lit[zoneAt(meX, meY)] && !dark
    fun isNightNow(): Boolean = hour >= 21f || hour < 5f

    private fun label(rm: Remote): String =
        if (known.contains(rm.id)) rm.name else CLASSES[rm.cls].name.removePrefix("The ")

    /** Someone told you their name. */
    fun reveal(id: Int) {
        known.add(id)
    }

    private fun isBusy(): Boolean = SystemClock.elapsedRealtime() < busyUntil

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == joyId) {
                        joyX = e.getX(i)
                        joyY = e.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == joyId) joyId = -1
            }
            MotionEvent.ACTION_CANCEL -> joyId = -1
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (miniOn) {
            finishMini()
            return
        }
        if (hypot(x - (width - 34f * d), y - 26f * d) < 26f * d) {
            onMenu?.invoke()
            return
        }
        val busy = isBusy() || tpOn
        for (i in btnActs.indices) {
            if (hypot(x - btnX[i], y - btnY[i]) < 33f * d) {
                if (!busy) press(btnActs[i])
                return
            }
        }
        if (joyId == -1 && x < width * 0.62f && y > height * 0.14f) {
            joyId = id
            joyBX = x
            joyBY = y
            joyX = x
            joyY = y
        }
    }

    private fun press(act: String) {
        val pl = curPlace
        if (pl < 0) return
        if (act == "work") {
            miniOn = true
            miniPlace = pl
            miniPos = 0f
            miniDir = 1f
            return
        }
        if (act == "keke") {
            onKeke?.invoke(pl)
            return
        }
        busyUntil = SystemClock.elapsedRealtime() + 700L
        onAction?.invoke(act, pl, 1.0)
    }

    private fun finishMini() {
        miniOn = false
        val dd = abs(miniPos - 0.5f)
        val score = if (dd <= 0.1f) 1.5 else if (dd <= 0.22f) 1.2 else 0.85
        toast(if (score >= 1.5) "Perfect timing!" else if (score >= 1.2) "Good work." else "A bit slow.", false)
        busyUntil = SystemClock.elapsedRealtime() + 700L
        onAction?.invoke("work", miniPlace, score)
    }

    // ---------- the frame ----------

    private fun daylight(): Float {
        val x = (hour - 6f) / 12f
        if (x <= 0f || x >= 1f) return 0f
        return min(1f, max(0f, sin(x * PI).toFloat()) * 1.5f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastNs) / 1.0e9f).coerceIn(0f, 0.1f)
        lastNs = nowNs
        val tm = (nowNs - t0) / 1.0e9f
        val day01 = daylight()

        step(dt)

        c.drawColor(0xFF0B1022.toInt())
        val halfW = w / 2f / zoom
        val halfH = h / 2f / zoom
        val l = camX - halfW
        val rt = camX + halfW
        val t = camY - halfH
        val b = camY + halfH

        c.save()
        c.translate(w / 2f, h / 2f)
        c.scale(zoom, zoom)
        c.translate(-camX, -camY)
        World.px = meX
        World.py = meY
        World.draw(c, l, t, rt, b, tm, day01, rain, evTitle == "Ring Road gridlock")
        drawPeople(c, tm, l, t, rt, b)
        c.restore()

        drawNight(c, w, h, day01, tm)
        if (rain) drawRain(c, w, h, tm)
        drawHud(c, w, h, tm)
        drawControls(c, w, h, tm)
        if (miniOn) drawMini(c, w, h)

        postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        World.update(dt, evTitle == "Ring Road gridlock")

        val now = SystemClock.elapsedRealtime()
        if (tpOn) {
            val f = ((now - tpStart).toFloat() / max(1L, tpMs)).coerceIn(0f, 1f)
            val e = f * f * (3f - 2f * f)
            meX = tpFromX + (tpToX - tpFromX) * e
            meY = tpFromY + (tpToY - tpFromY) * e
            meFace = atan2(tpToY - tpFromY, tpToX - tpFromX)
            if (f >= 1f) tpOn = false
        } else if (joyId != -1 && !isBusy() && !miniOn) {
            val dx = joyX - joyBX
            val dy = joyY - joyBY
            val len = hypot(dx, dy)
            if (len > 8f * d) {
                val mag = min(1f, len / (52f * d))
                val sp = 130f * mag
                meX = (meX + dx / len * sp * dt).coerceIn(20f, WORLD_W - 20f)
                meY = (meY + dy / len * sp * dt).coerceIn(20f, WORLD_H - 20f)
                meFace = atan2(dy, dx)
            }
        }

        // the camera follows you smoothly
        val k = min(1f, dt * 6f)
        camX += (meX - camX) * k
        camY += (meY - camY) * k

        for (rm in remotes.values) {
            val dx = rm.tx - rm.x
            val dy = rm.ty - rm.y
            if (abs(dx) + abs(dy) > 0.5f) rm.face = atan2(dy, dx)
            val kk = min(1f, dt * 8f)
            rm.x += dx * kk
            rm.y += dy * kk
        }

        // which place am I standing at?
        var best = -1
        var bestD = 1e9f
        for (pl in PLACES) {
            val dd = hypot(meX - pl.x, meY - pl.y) - pl.r
            if (dd < 0f && dd < bestD) {
                bestD = dd
                best = pl.id
            }
        }
        if (best != curPlace) {
            curPlace = best
            if (best >= 0) {
                bannerText = PLACES[best].name
                bannerT = 3f
            }
        }

        if (miniOn) {
            val speed = (1.15f + min(day, 40) * 0.025f) * 1.0f
            miniPos += miniDir * speed * dt
            if (miniPos > 1f) {
                miniPos = 1f
                miniDir = -1f
            }
            if (miniPos < 0f) {
                miniPos = 0f
                miniDir = 1f
            }
        }

        if (bannerT > 0f) bannerT -= dt
        if (toastT > 0f) toastT -= dt

        // tell the host where I am, about eight times a second
        if (now - lastSend > 125L && (meX != sentX || meY != sentY)) {
            lastSend = now
            sentX = meX
            sentY = meY
            onPos?.invoke(meX, meY)
        }
    }

    // ---------- people ----------

    private fun person(c: Canvas, x: Float, y: Float, id: Int, face: Float, walking: Boolean, working: Boolean, tm: Float) {
        val skin = SKINS[abs(id) % SKINS.size]
        val shirt = SHIRTS[abs(id * 3 + 1) % SHIRTS.size]
        p.color = 0x55000000
        rect.set(x - 9f + 3f, y - 6f + 5f, x + 9f + 3f, y + 6f + 5f)
        c.drawOval(rect, p)
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(face.toDouble()).toFloat())
        val sw = if (walking) sin(tm * 12f + id) * 4f else 0f
        p.color = 0xFF2F3B47.toInt()
        rect.set(-3f + sw, -7f, 5f + sw, -3f)
        c.drawOval(rect, p)
        rect.set(-3f - sw, 3f, 5f - sw, 7f)
        c.drawOval(rect, p)
        p.color = shirt
        rect.set(-6f, -8f, 6f, 8f)
        c.drawOval(rect, p)
        if (working) {
            val hit = sin(tm * 14f) * 3f
            p.color = skin
            rect.set(5f + hit, -6f, 11f + hit, -3f)
            c.drawOval(rect, p)
            rect.set(5f + hit, 3f, 11f + hit, 6f)
            c.drawOval(rect, p)
        }
        p.color = skin
        c.drawCircle(1f, 0f, 5.3f, p)
        p.color = 0xFF161616.toInt()
        c.drawCircle(-0.5f, 0f, 4.2f, p)
        c.restore()
    }

    private fun tag(c: Canvas, text: String, x: Float, y: Float, me: Boolean) {
        p.textSize = if (me) 12f else 10.5f
        p.isFakeBoldText = true
        val tw = p.measureText(text)
        p.color = if (me) 0xFFFF5B2E.toInt() else 0xAA000000.toInt()
        rect.set(x - tw / 2 - 6f, y - 13f, x + tw / 2 + 6f, y + 4f)
        c.drawRoundRect(rect, 9f, 9f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawText(text, x - tw / 2, y, p)
        p.isFakeBoldText = false
    }

    private fun drawPeople(c: Canvas, tm: Float, l: Float, t: Float, rt: Float, b: Float) {
        for (rm in remotes.values) {
            if (!rm.seen) continue
            if (rm.x < l - 40f || rm.x > rt + 40f || rm.y < t - 40f || rm.y > b + 40f) continue
            val moving = abs(rm.tx - rm.x) + abs(rm.ty - rm.y) > 3f
            person(c, rm.x, rm.y, rm.id, rm.face, moving, rm.busy && !moving, tm)
            tag(c, label(rm), rm.x, rm.y - 16f, false)
        }
        if (tpOn) {
            // you are riding a keke
            c.save()
            c.translate(meX, meY)
            c.rotate(Math.toDegrees(meFace.toDouble()).toFloat())
            p.color = 0x55000000
            rect.set(-14f + 3f, -9f + 4f, 14f + 3f, 9f + 4f)
            c.drawRoundRect(rect, 5f, 5f, p)
            p.color = 0xFFFFC400.toInt()
            rect.set(-14f, -9f, 14f, 9f)
            c.drawRoundRect(rect, 5f, 5f, p)
            p.color = 0xFF1B8A4B.toInt()
            c.drawRect(-14f, -2.5f, 14f, 2.5f, p)
            p.color = 0xFF2C3E5A.toInt()
            c.drawRect(4f, -7f, 9f, 7f, p)
            c.restore()
            return
        }
        val busy = isBusy()
        val walking = joyId != -1 && !busy
        // a ring under your feet so you can find yourself
        val pulse = 1f + 0.08f * sin(tm * 4f)
        st.color = 0xAAFF5B2E.toInt()
        st.strokeWidth = 2.5f
        c.drawCircle(meX, meY, 15f * pulse, st)
        person(c, meX, meY, max(0, meId), meFace, walking, busy, tm)
        tag(c, meName, meX, meY - 18f, true)
    }

    // ---------- light, weather ----------

    private fun drawNight(c: Canvas, w: Float, h: Float, day: Float, tm: Float) {
        val night = 1f - day
        val out = !hasLight()
        var a = (night * 0.6f)
        if (out) a = min(0.8f, a + 0.18f)
        if (a > 0.02f) {
            p.color = ((a * 255f).toInt() shl 24) or 0x08102A
            c.drawRect(0f, 0f, w, h, p)
        }
        if (night > 0.4f && !out) {
            // street light glow around every place and a lantern around you
            for (pl in PLACES) {
                val sx = (pl.x - camX) * zoom + w / 2f
                val sy = (pl.y - camY) * zoom + h / 2f
                if (sx < -200f || sx > w + 200f || sy < -200f || sy > h + 200f) continue
                glow(c, sx, sy, 90f * zoom * 0.9f, night)
            }
            val mx = (meX - camX) * zoom + w / 2f
            val my = (meY - camY) * zoom + h / 2f
            glow(c, mx, my, 70f * zoom * 0.8f, night)
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, night: Float) {
        val base = (night * 28f).toInt()
        p.color = (base shl 24) or 0xFFE9A0
        c.drawCircle(x, y, r, p)
        p.color = (base shl 24) or 0xFFE9A0
        c.drawCircle(x, y, r * 0.65f, p)
        p.color = (base shl 24) or 0xFFF3C0
        c.drawCircle(x, y, r * 0.35f, p)
    }

    private fun drawRain(c: Canvas, w: Float, h: Float, tm: Float) {
        p.color = 0x33203050
        c.drawRect(0f, 0f, w, h, p)
        st.color = 0x88C8DCFF.toInt()
        st.strokeWidth = 2f
        for (i in 0 until 110) {
            val x = ((i * 97) % 1000) / 1000f * w + (tm * 40f) % 20f
            val y = (((i * 53) % 1000) / 1000f * h + tm * 700f) % (h + 40f) - 20f
            c.drawLine(x, y, x - 8f, y + 24f, st)
        }
        if ((tm % 9f) < 0.12f) {
            p.color = 0x55FFFFFF
            c.drawRect(0f, 0f, w, h, p)
        }
    }

    // ---------- the HUD ----------

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, center: Boolean) {
        p.textSize = size * d
        p.isFakeBoldText = bold
        val tw = p.measureText(s)
        val px = if (center) x - tw / 2 else x
        p.color = 0x99000000.toInt()
        c.drawText(s, px + 1.2f * d, y + 1.2f * d, p)
        p.color = color
        c.drawText(s, px, y, p)
        p.isFakeBoldText = false
    }

    private fun bar(c: Canvas, x: Float, y: Float, wd: Float, frac: Float, color: Int, label: String) {
        txt(c, label, x, y + 7f * d, 9.5f, 0xFFDDE3EA.toInt(), true, false)
        val bx = x + 24f * d
        p.color = 0x88000000.toInt()
        rect.set(bx, y, bx + wd, y + 8f * d)
        c.drawRoundRect(rect, 4f * d, 4f * d, p)
        p.color = color
        rect.set(bx, y, bx + max(8f * d, wd * frac.coerceIn(0f, 1f)), y + 8f * d)
        c.drawRoundRect(rect, 4f * d, 4f * d, p)
    }

    private fun pill(c: Canvas, cx: Float, cy: Float, text: String, size: Float, bg: Int, fg: Int) {
        p.textSize = size * d
        p.isFakeBoldText = true
        val tw = p.measureText(text)
        p.color = bg
        rect.set(cx - tw / 2 - 12f * d, cy - size * d * 0.95f, cx + tw / 2 + 12f * d, cy + size * d * 0.45f)
        c.drawRoundRect(rect, 14f * d, 14f * d, p)
        p.color = fg
        c.drawText(text, cx - tw / 2, cy, p)
        p.isFakeBoldText = false
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, tm: Float) {
        // money and stats, top left
        txt(c, naira(cash), 14f * d, 34f * d, 25f, 0xFFFFC857.toInt(), true, false)
        bar(c, 14f * d, 46f * d, 84f * d, energy / 100f, 0xFF3DDC84.toInt(), "EN")
        bar(c, 14f * d, 60f * d, 84f * d, food / 100f, 0xFFFF8A3D.toInt(), "FD")
        bar(c, 14f * d, 74f * d, 84f * d, min(100, rep) / 100f, 0xFF4DA3FF.toInt(), "RP")
        bar(c, 14f * d, 88f * d, 84f * d, know / 100f, 0xFFA663CC.toInt(), "KN")
        var ly = 114f
        if (bank > 0L) {
            txt(c, "Bank " + naira(bank), 14f * d, ly * d, 11.5f, 0xFF9FE3B5.toInt(), true, false)
            ly += 15f
        }
        if (usd > 0) {
            txt(c, "\$" + usd, 14f * d, ly * d, 11.5f, 0xFFB7F0A0.toInt(), true, false)
            ly += 15f
        }
        if (goods > 0) {
            txt(c, "Goods: $goods bags", 14f * d, ly * d, 11.5f, 0xFFFFFFFF.toInt(), true, false)
            ly += 15f
        }
        txt(c, "L$lvl " + LEVEL_NAMES[lvl.coerceIn(1, 5)] + (if (hurt) "  (hurt)" else ""), 14f * d, ly * d, 11f, if (hurt) 0xFFFF8A8A.toInt() else 0xFFFFC857.toInt(), true, false)

        // clock, top centre
        val hh = hour.toInt()
        val mm = ((hour - hh) * 60f).toInt() / 10 * 10
        val clock = "Day $day/$total   " + String.format("%02d:%02d", hh, mm)
        pill(c, w * 0.5f - 6f * d, 28f * d, clock, 13f, 0x99000000.toInt(), 0xFFFFFFFF.toInt())
        txt(c, "Rent " + naira(rent) + (if (rentIn <= 0) " today" else " in $rentIn days"), w * 0.5f - 6f * d, 48f * d, 10.5f, 0xFFDDE3EA.toInt(), false, true)
        var py = 74f
        if (evTitle.isNotEmpty()) {
            pill(c, w * 0.5f - 6f * d, py * d, "⚠ $evTitle", 12.5f, 0xEEFFC857.toInt(), 0xFF101418.toInt())
            py += 26f
        }
        if (!hasLight()) {
            pill(c, w * 0.5f - 6f * d, py * d, "🔌 No light here", 12f, 0xEE3A2A00.toInt(), 0xFFFFC857.toInt())
            py += 26f
        }
        if (curPlace >= 0) {
            val cp = PLACES[curPlace]
            if (cp.oh > 0 || cp.ch < 24) {
                val open = isOpenAt(cp, hour.toDouble())
                if (!open) pill(c, w * 0.5f - 6f * d, py * d, "Closed. Opens " + hourText(cp.oh), 12f, 0xEE402020.toInt(), 0xFFFFFFFF.toInt())
            }
        }

        // menu button and minimap, top right
        p.color = 0x99000000.toInt()
        c.drawCircle(w - 34f * d, 26f * d, 19f * d, p)
        p.color = 0xFFFFFFFF.toInt()
        for (i in -1..1) {
            rect.set(w - 34f * d - 8f * d, 26f * d + i * 6f * d - 1.2f * d, w - 34f * d + 8f * d, 26f * d + i * 6f * d + 1.2f * d)
            c.drawRoundRect(rect, 2f * d, 2f * d, p)
        }
        drawMinimap(c, w - 62f * d, 104f * d, 52f * d)

        // the name of the place you just arrived at
        if (bannerT > 0f && bannerText.isNotEmpty()) {
            val a = (min(1f, bannerT) * 255f).toInt()
            p.textSize = 22f * d
            p.isFakeBoldText = true
            val tw = p.measureText(bannerText)
            p.color = (a shl 24) or 0x000000
            c.drawText(bannerText, w / 2f - tw / 2f + 1.5f * d, h * 0.3f + 1.5f * d, p)
            p.color = (a shl 24) or 0xFFFFFF
            c.drawText(bannerText, w / 2f - tw / 2f, h * 0.3f, p)
            p.isFakeBoldText = false
        }

        // what you are doing now
        if (isBusy() && busyTotal > 0) {
            val left = (busyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val f = 1f - left.toFloat() / busyTotal
            val bw = 190f * d
            val by = h - 168f * d
            txt(c, busyLabel, w / 2f, by - 8f * d, 12.5f, 0xFFFFFFFF.toInt(), true, true)
            p.color = 0x88000000.toInt()
            rect.set(w / 2f - bw / 2, by, w / 2f + bw / 2, by + 9f * d)
            c.drawRoundRect(rect, 5f * d, 5f * d, p)
            p.color = 0xFFFFC857.toInt()
            rect.set(w / 2f - bw / 2, by, w / 2f - bw / 2 + max(9f * d, bw * f), by + 9f * d)
            c.drawRoundRect(rect, 5f * d, 5f * d, p)
        }

        // messages
        if (toastT > 0f && toastText.isNotEmpty()) {
            val a = min(1f, toastT)
            val bg = ((a * 200f).toInt() shl 24) or (if (toastBig) 0x3A2A00 else 0x000000)
            val fg = ((a * 255f).toInt() shl 24) or 0xFFFFFF
            val maxW = w - 40f * d
            p.textSize = 13f * d
            p.isFakeBoldText = true
            val lines = wrap(toastText, maxW - 28f * d)
            val lh = 17f * d
            val boxH = lines.size * lh + 14f * d
            val top = h - 138f * d - boxH
            p.color = bg
            rect.set(20f * d, top, w - 20f * d, top + boxH)
            c.drawRoundRect(rect, 14f * d, 14f * d, p)
            p.color = fg
            var y = top + 20f * d
            for (ln in lines) {
                c.drawText(ln, w / 2f - p.measureText(ln) / 2f, y, p)
                y += lh
            }
            p.isFakeBoldText = false
        }
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(" ")) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(tryLine) > maxW && line.isNotEmpty()) {
                out.add(line)
                line = word
            } else line = tryLine
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawMinimap(c: Canvas, cx: Float, cy: Float, rad: Float) {
        val scale = rad / 750f
        p.color = 0xAA101418.toInt()
        c.drawCircle(cx, cy, rad + 3f * d, p)
        c.save()
        path.reset()
        path.addCircle(cx, cy, rad, Path.Direction.CW)
        c.clipPath(path)
        p.color = 0xFF55803F.toInt()
        c.drawCircle(cx, cy, rad, p)
        c.translate(cx, cy)
        c.scale(scale, scale)
        c.translate(-meX, -meY)
        p.color = 0xFFB9A783.toInt()
        rect.set(KX - 1000f, KY - 900f, KX + 1000f, KY + 900f)
        c.drawOval(rect, p)
        st.style = Paint.Style.STROKE
        st.strokeCap = Paint.Cap.ROUND
        for (rd in World.roads) {
            st.color = 0xFFF3EEDF.toInt()
            st.strokeWidth = max(rd.width * 1.1f, 2f / scale)
            c.drawPath(rd.path, st)
        }
        for (pl in PLACES) {
            if (pl.id == P_MUSEUM) continue
            p.color = if (pl.id == curPlace) 0xFFFFC857.toInt() else 0xFFFF5B2E.toInt()
            c.drawCircle(pl.x, pl.y, 5f / scale, p)
        }
        for (rm in remotes.values) {
            if (!rm.seen) continue
            p.color = 0xFF4DA3FF.toInt()
            c.drawCircle(rm.x, rm.y, 4f / scale, p)
        }
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(meX, meY, 5.5f / scale, p)
        p.color = 0xFF101418.toInt()
        c.drawCircle(meX, meY, 3f / scale, p)
        c.restore()
        st.color = 0xFFFFFFFF.toInt()
        st.strokeWidth = 2f * d
        c.drawCircle(cx, cy, rad, st)
    }

    // ---------- the buttons ----------

    private fun icon(a: String): String = when (a) {
        "work" -> "💼"
        "eat" -> "🍛"
        "buy" -> "🛍"
        "sell" -> "💰"
        "study" -> "📚"
        "rest" -> "🛏"
        "visit" -> "🏛"
        "donate" -> "🩸"
        "bank" -> "🏦"
        "atm" -> "🏧"
        "pos" -> "💳"
        "forex" -> "💱"
        "treat" -> "💉"
        "bus" -> "🚌"
        "stand" -> "🧾"
        "drive" -> "🚖"
        "collect" -> "🎟"
        "gig" -> "💻"
        else -> "🚕"
    }

    private fun caption(a: String, pl: Place): String = when (a) {
        "work" -> "Work"
        "eat" -> "Eat " + naira(pl.food.toLong())
        "buy" -> "Buy " + naira(prices[pl.id].toLong())
        "sell" -> "Sell ($goods)"
        "study" -> "Study"
        "rest" -> "Sleep"
        "visit" -> if (pl.id == P_MUSEUM) "Enter " + naira(500L) else if (pl.id == P_KADA) "Rooftop " + naira(3000L) else if (pl.id == P_ZOO) "Enter " + naira(800L) else "Visit"
        "bank" -> "Bank"
        "donate" -> "Donate blood"
        "atm" -> "ATM"
        "pos" -> "POS"
        "forex" -> "Dollars"
        "treat" -> "Doctor"
        "bus" -> "Bus"
        "stand" -> "POS stand"
        "drive" -> "Drive"
        "collect" -> "Collect"
        "gig" -> "Gig"
        else -> "Keke"
    }

    private fun drawControls(c: Canvas, w: Float, h: Float, tm: Float) {
        // the floating thumb stick
        if (joyId != -1) {
            val dx = joyX - joyBX
            val dy = joyY - joyBY
            val len = hypot(dx, dy)
            val mx = 52f * d
            val kx = if (len > mx) joyBX + dx / len * mx else joyX
            val ky = if (len > mx) joyBY + dy / len * mx else joyY
            p.color = 0x33FFFFFF
            c.drawCircle(joyBX, joyBY, mx, p)
            st.color = 0x88FFFFFF.toInt()
            st.strokeWidth = 2f * d
            c.drawCircle(joyBX, joyBY, mx, st)
            p.color = 0xAAFFFFFF.toInt()
            c.drawCircle(kx, ky, 22f * d, p)
        }

        btnActs.clear()
        btnX.clear()
        btnY.clear()
        if (curPlace < 0 || miniOn) return
        val pl = PLACES[curPlace]
        val busy = isBusy() || tpOn
        val list = ArrayList<String>()
        for (a in pl.acts) if (a != "keke" && a != "bus") list.add(a)
        for (a in extraActs(cls, pl)) list.add(a)
        if (pl.acts.contains("bus")) list.add("bus")
        list.add("keke")
        val rows = 5
        for (i in list.indices) {
            val col = i / rows
            val row = i % rows
            val x = w - 44f * d - col * 74f * d
            val y = h - 60f * d - row * 70f * d
            btnActs.add(list[i])
            btnX.add(x)
            btnY.add(y)
            val a = list[i]
            val enabled = !busy
            p.color = if (enabled) 0xCC101418.toInt() else 0x88101418.toInt()
            c.drawCircle(x, y, 28f * d, p)
            st.color = if (enabled) 0xFFFF5B2E.toInt() else 0x55FF5B2E
            st.strokeWidth = 2.5f * d
            c.drawCircle(x, y, 28f * d, st)
            p.textSize = 24f * d
            p.color = if (enabled) 0xFFFFFFFF.toInt() else 0x66FFFFFF
            val ic = icon(a)
            c.drawText(ic, x - p.measureText(ic) / 2f, y + 9f * d, p)
            txt(c, caption(a, pl), x, y + 41f * d, 10f, if (enabled) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt(), true, true)
        }
    }

    private fun drawMini(c: Canvas, w: Float, h: Float) {
        p.color = 0x99000000.toInt()
        c.drawRect(0f, 0f, w, h, p)
        val pl = PLACES[miniPlace]
        txt(c, pl.job.ifEmpty { "Work" }, w / 2f, h * 0.38f, 20f, 0xFFFFFFFF.toInt(), true, true)
        txt(c, "Tap anywhere when the marker is in the green zone", w / 2f, h * 0.38f + 26f * d, 12.5f, 0xFFDDE3EA.toInt(), false, true)
        val bw = w - 60f * d
        val bx = 30f * d
        val by = h * 0.5f
        val bh = 26f * d
        p.color = 0xFF242C35.toInt()
        rect.set(bx, by, bx + bw, by + bh)
        c.drawRoundRect(rect, bh / 2, bh / 2, p)
        p.color = 0xFF8A6D1F.toInt()
        rect.set(bx + bw * 0.28f, by, bx + bw * 0.72f, by + bh)
        c.drawRect(rect, p)
        p.color = 0xFF3DDC84.toInt()
        rect.set(bx + bw * 0.4f, by, bx + bw * 0.6f, by + bh)
        c.drawRect(rect, p)
        p.color = 0xFFF6F5F2.toInt()
        val mx = bx + bw * miniPos
        rect.set(mx - 5f * d, by - 8f * d, mx + 5f * d, by + bh + 8f * d)
        c.drawRoundRect(rect, 5f * d, 5f * d, p)
    }
}
