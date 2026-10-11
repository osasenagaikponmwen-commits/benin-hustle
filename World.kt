package com.nagi.hustle

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    val ar = (a shr 16) and 255
    val ag = (a shr 8) and 255
    val ab = a and 255
    val br = (b shr 16) and 255
    val bg = (b shr 8) and 255
    val bb = b and 255
    val r = (ar + (br - ar) * k).toInt()
    val g = (ag + (bg - ag) * k).toInt()
    val bl = (ab + (bb - ab) * k).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

fun arcPts(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, n: Int): FloatArray {
    val a = FloatArray((n + 1) * 2)
    for (i in 0..n) {
        val th = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
        a[i * 2] = (cx + r * cos(th)).toFloat()
        a[i * 2 + 1] = (cy + r * sin(th)).toFloat()
    }
    return a
}

/** A road is a line of points with a width. Vehicles drive along it. */
class Road(val name: String, val pts: FloatArray, val width: Float, val closed: Boolean = false) {
    val n = pts.size / 2
    val path = Path()
    val cum = FloatArray(n + 1)
    var total = 0f

    init {
        path.moveTo(pts[0], pts[1])
        for (i in 1 until n) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        if (closed) path.close()
        var acc = 0f
        for (i in 0 until n) {
            cum[i] = acc
            val j = if (i + 1 < n) i + 1 else if (closed) 0 else -1
            if (j >= 0) acc += hypot(pts[j * 2] - pts[i * 2], pts[j * 2 + 1] - pts[i * 2 + 1])
        }
        cum[n] = acc
        total = acc
    }

    /** Puts x, y and the heading (radians) at distance d along the road into out. */
    fun at(d0: Float, out: FloatArray) {
        if (total <= 0f) {
            out[0] = pts[0]
            out[1] = pts[1]
            out[2] = 0f
            return
        }
        val d = ((d0 % total) + total) % total
        var i = 0
        while (i < n - 1 && cum[i + 1] <= d) i++
        val j = if (i + 1 < n) i + 1 else 0
        val segLen = max(0.0001f, cum[i + 1] - cum[i])
        val t = ((d - cum[i]) / segLen).coerceIn(0f, 1f)
        val x0 = pts[i * 2]
        val y0 = pts[i * 2 + 1]
        val x1 = pts[j * 2]
        val y1 = pts[j * 2 + 1]
        out[0] = x0 + (x1 - x0) * t
        out[1] = y0 + (y1 - y0) * t
        out[2] = atan2(y1 - y0, x1 - x0)
    }

    /** Distance from a point to the middle of the road. */
    fun dist(x: Float, y: Float): Float {
        var best = 1e9f
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val j = if (i + 1 < n) i + 1 else 0
            val ax = pts[i * 2]
            val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]
            val by = pts[j * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            var t = if (l2 > 0f) ((x - ax) * dx + (y - ay) * dy) / l2 else 0f
            t = t.coerceIn(0f, 1f)
            val d = hypot(x - (ax + dx * t), y - (ay + dy * t))
            if (d < best) best = d
        }
        return best
    }
}

class Building(val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float, val wall: Int, val roof: Int, val pitched: Boolean)

/** A vehicle that drives up and down a road. */
class Vehicle(val road: Road, var d: Float, val speed: Float, val kind: Int, var dir: Int)

/** A person wandering near a place, for life in the crowds. */
class Walker(var x: Float, var y: Float, val homeX: Float, val homeY: Float, val range: Float, val shade: Int) {
    var tx = x
    var ty = y
}

/**
 * Benin City as a top-down map. Roads, markets and landmarks are placed where they are in the
 * real city, in simplified form.
 */
object World {

    val roads = ArrayList<Road>()
    val buildings = ArrayList<Building>()
    val vehicles = ArrayList<Vehicle>()
    val walkers = ArrayList<Walker>()
    private var treeData = FloatArray(0)
    private var ready = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()
    private val path = Path()
    var px = 0f
    var py = 0f
    private val out3 = FloatArray(3)

    private val WALLS = intArrayOf(0xFFE8DCC4.toInt(), 0xFFD9C7A3.toInt(), 0xFFCDB69A.toInt(), 0xFFEFE6D6.toInt(), 0xFFC9A98B.toInt(), 0xFFDDD2BE.toInt(), 0xFFB9C4C9.toInt(), 0xFFE5C9A8.toInt())
    private val ROOFS = intArrayOf(0xFF9AA0A6.toInt(), 0xFFB8BEC4.toInt(), 0xFF8B9096.toInt(), 0xFFA4553A.toInt(), 0xFFB66A45.toInt(), 0xFF5B7A5C.toInt(), 0xFF4C6A8A.toInt(), 0xFFB5493A.toInt())
    private val TARPS = intArrayOf(0xFFE63946.toInt(), 0xFF2A9D8F.toInt(), 0xFFF4A261.toInt(), 0xFF457B9D.toInt(), 0xFFE9C46A.toInt(), 0xFF8E44AD.toInt(), 0xFF1D8348.toInt(), 0xFFF1FAEE.toInt())

    // places that keep ordinary buildings out
    private class Zone(val x: Float, val y: Float, val rx: Float, val ry: Float) {
        fun hit(px: Float, py: Float, pad: Float): Boolean {
            val dx = (px - x) / (rx + pad)
            val dy = (py - y) / (ry + pad)
            return dx * dx + dy * dy < 1f
        }
    }

    private val zones = ArrayList<Zone>()

    fun init() {
        if (ready) return
        ready = true

        // ---- roads ----
        roads.add(Road("Ring Road", arcPts(KX, KY, 330f, 0f, 355f, 71), 56f, true))
        roads.add(Road("2nd East Circular Road", arcPts(KX, KY, 560f, -75f, 75f, 40), 34f))
        roads.add(Road("3rd East Circular Road", arcPts(KX, KY, 800f, -70f, 70f, 40), 30f))
        roads.add(Road("2nd West Circular Road", arcPts(KX, KY, 560f, 105f, 255f, 40), 34f))
        roads.add(Road("3rd West Circular Road", arcPts(KX, KY, 800f, 110f, 250f, 40), 30f))
        roads.add(Road("New Lagos Road", floatArrayOf(1270f, 1500f, 1000f, 1440f, 760f, 1330f, 520f, 1220f, 340f, 1125f), 42f))
        roads.add(Road("Airport Road", floatArrayOf(1812f, 1247f, 1980f, 1050f, 2150f, 850f, 2330f, 640f, 2500f, 430f, 2640f, 300f), 42f))
        roads.add(Road("Ihama Road", floatArrayOf(1600f, 1170f, 1580f, 950f, 1540f, 780f, 1500f, 660f), 36f))
        roads.add(Road("Etete Road", floatArrayOf(1500f, 660f, 1250f, 640f, 1050f, 600f, 880f, 560f), 30f))
        roads.add(Road("Limit Road", floatArrayOf(1500f, 660f, 1700f, 620f, 1950f, 600f, 2150f, 850f), 30f))
        roads.add(Road("Akpakpava Street", floatArrayOf(1702f, 1186f, 1760f, 1050f, 1840f, 900f, 1930f, 760f, 2050f, 640f), 34f))
        roads.add(Road("Oba Market Road", floatArrayOf(1930f, 1500f, 2150f, 1500f, 2400f, 1490f, 2700f, 1470f, 3050f, 1450f), 36f))
        roads.add(
            Road(
                "Sapele Road",
                floatArrayOf(1853f, 1712f, 2100f, 1900f, 2300f, 2060f, moatX(40.0), moatY(40.0), 2800f, 2420f, 3050f, 2600f, 3250f, 2700f), 40f
            )
        )
        roads.add(Road("Sakpoba Road", floatArrayOf(1543f, 1825f, 1520f, 2000f, 1500f, 2200f, 1540f, 2420f, 1650f, 2650f, 1750f, 2900f), 40f))
        roads.add(Road("Igun Street", floatArrayOf(1520f, 1960f, 1400f, 1960f, 1250f, 1960f, 1100f, 2010f), 28f))
        roads.add(Road("Ikpoba Road", floatArrayOf(1540f, 2420f, 1850f, 2440f, 2150f, 2520f, 2400f, 2600f), 34f))
        roads.add(Road("Siluko Road", floatArrayOf(1314f, 1665f, 1050f, 1850f, 780f, 2100f, 520f, 2380f, 300f, 2650f), 36f))
        roads.add(Road("Ekosodin Road", floatArrayOf(640f, 1280f, 560f, 1380f, 470f, 1450f), 26f))
        roads.add(Road("Aduwawa Bypass", floatArrayOf(2150f, 850f, 2500f, 900f, 2850f, 1050f, 3050f, 1300f, 3050f, 1450f), 36f))

        // ---- places that keep random buildings out ----
        zones.add(Zone(KX, KY, 275f, 275f))
        zones.add(Zone(1300f, 1230f, 175f, 135f))
        zones.add(Zone(2130f, 1380f, 190f, 125f))
        zones.add(Zone(2130f, 1650f, 105f, 105f))
        zones.add(Zone(1380f, 2200f, 130f, 110f))
        zones.add(Zone(300f, 1100f, 300f, 300f))
        zones.add(Zone(2650f, 280f, 300f, 140f))
        zones.add(Zone(2450f, 2650f, 230f, 230f))
        zones.add(Zone(moatX(40.0), moatY(40.0), 60f, 60f))
        zones.add(Zone(1320f, 1960f, 170f, 60f))
        zones.add(Zone(1600f, 1320f, 70f, 70f))
        zones.add(Zone(1900f, 1260f, 115f, 85f))
        zones.add(Zone(2090f, 1090f, 150f, 105f))
        zones.add(Zone(880f, 1280f, 120f, 110f))
        zones.add(Zone(2650f, 2050f, 190f, 140f))
        zones.add(Zone(2290f, 1925f, 125f, 95f))
        zones.add(Zone(1740f, 1880f, 110f, 55f))
        zones.add(Zone(2440f, 1280f, 130f, 70f))
        zones.add(Zone(650f, 1190f, 60f, 50f))
        zones.add(Zone(2700f, 1200f, 160f, 110f))
        zones.add(Zone(1830f, 2150f, 160f, 130f))
        zones.add(Zone(1690f, 800f, 70f, 60f))

        makeBuildings()
        makeTrees()
        makeTraffic()
        makeWalkers()
    }

    private fun roadClear(x: Float, y: Float): Float {
        var best = 1e9f
        for (rd in roads) {
            val d = rd.dist(x, y) - rd.width / 2
            if (d < best) best = d
        }
        return best
    }

    private fun inZone(x: Float, y: Float, pad: Float): Boolean {
        for (z in zones) if (z.hit(x, y, pad)) return true
        return false
    }

    private fun makeBuildings() {
        val rnd = Random(2024)
        val grid = HashMap<Int, ArrayList<Building>>()
        val o = FloatArray(3)
        for (rd in roads) {
            var d = 20f
            while (d < rd.total) {
                rd.at(d, o)
                for (side in intArrayOf(-1, 1)) {
                    val w = 24f + rnd.nextFloat() * 36f
                    val h = 22f + rnd.nextFloat() * 28f
                    val off = rd.width / 2 + 7f + h / 2
                    val nx = -sin(o[2].toDouble()).toFloat() * side
                    val ny = cos(o[2].toDouble()).toFloat() * side
                    val cx = o[0] + nx * off
                    val cy = o[1] + ny * off
                    if (cx < 40f || cy < 40f || cx > WORLD_W - 40f || cy > WORLD_H - 40f) continue
                    val dc = hypot(cx - KX, cy - KY)
                    val prob = (1.0f - dc / 2300f).coerceIn(0.2f, 0.95f)
                    if (rnd.nextFloat() > prob) continue
                    if (inZone(cx, cy, 10f)) continue
                    if (roadClear(cx, cy) < max(w, h) * 0.5f + 2f) continue
                    val key = (cx / 120f).toInt() * 1000 + (cy / 120f).toInt()
                    var clash = false
                    for (gx in -1..1) {
                        for (gy in -1..1) {
                            val list = grid[key + gx * 1000 + gy] ?: continue
                            for (b in list) {
                                if (hypot(b.x - cx, b.y - cy) < (max(b.w, b.h) + max(w, h)) * 0.5f + 2f) clash = true
                            }
                        }
                    }
                    if (clash) continue
                    val nearGra = hypot(cx - 1480f, cy - 640f) < 380f
                    val wall = if (nearGra) 0xFFF7F3EA.toInt() else WALLS[rnd.nextInt(WALLS.size)]
                    val roof = if (nearGra) (if (rnd.nextBoolean()) 0xFFB5493A.toInt() else 0xFFA4553A.toInt()) else ROOFS[rnd.nextInt(ROOFS.size)]
                    val b = Building(cx, cy, w * (if (nearGra) 1.4f else 1f), h * (if (nearGra) 1.3f else 1f), Math.toDegrees(o[2].toDouble()).toFloat(), wall, roof, rnd.nextInt(3) != 0)
                    buildings.add(b)
                    var list = grid[key]
                    if (list == null) {
                        list = ArrayList()
                        grid[key] = list
                    }
                    list.add(b)
                }
                d += 52f + rnd.nextFloat() * 40f
            }
        }
    }

    private fun makeTrees() {
        val rnd = Random(77)
        val list = ArrayList<Float>()
        var tries = 0
        while (list.size < 2400 && tries < 20000) {
            tries++
            val x = rnd.nextFloat() * WORLD_W
            val y = rnd.nextFloat() * WORLD_H
            if (roadClear(x, y) < 16f) continue
            if (inZone(x, y, 4f)) continue
            val dc = hypot(x - KX, y - KY)
            if (dc < 700f && rnd.nextFloat() < 0.75f) continue
            list.add(x)
            list.add(y)
            list.add(8f + rnd.nextFloat() * 9f)
        }
        // plenty of trees in the quiet estates of GRA
        for (i in 0 until 160) {
            val a = rnd.nextFloat() * 6.2832f
            val rr = rnd.nextFloat() * 300f
            val x = 1480f + cos(a.toDouble()).toFloat() * rr
            val y = 640f + sin(a.toDouble()).toFloat() * rr
            if (roadClear(x, y) < 14f) continue
            list.add(x)
            list.add(y)
            list.add(9f + rnd.nextFloat() * 8f)
        }
        treeData = list.toFloatArray()
    }

    private fun makeTraffic() {
        val rnd = Random(5)
        for (rd in roads) {
            val count = if (rd.name == "Ring Road") 14 else max(2, (rd.total / 700f).toInt())
            for (i in 0 until count) {
                val kind = rnd.nextInt(10)
                val k = if (kind < 4) 0 else if (kind < 6) 1 else 2
                val dir = if (rd.closed) 1 else (if (rnd.nextBoolean()) 1 else -1)
                vehicles.add(Vehicle(rd, rnd.nextFloat() * rd.total, 55f + rnd.nextFloat() * 45f, k, dir))
            }
        }
    }

    private fun makeWalkers() {
        val rnd = Random(9)
        val spots = floatArrayOf(
            2130f, 1380f, 150f, 2130f, 1380f, 150f, 2130f, 1380f, 150f, 2130f, 1380f, 150f,
            1600f, 1500f, 230f, 1600f, 1500f, 230f, 1600f, 1500f, 230f,
            1380f, 2200f, 120f, 1380f, 2200f, 120f,
            1320f, 1920f, 120f, 300f, 1100f, 240f, 300f, 1100f, 240f, 300f, 1100f, 240f,
            450f, 1480f, 120f, 1480f, 640f, 220f, 2650f, 300f, 160f, 2450f, 2650f, 170f,
            1300f, 1230f, 130f, 2130f, 1650f, 80f
        )
        var i = 0
        while (i < spots.size) {
            for (k in 0 until 3) {
                val a = rnd.nextFloat() * 6.28f
                val rr = rnd.nextFloat() * spots[i + 2]
                val x = spots[i] + cos(a.toDouble()).toFloat() * rr
                val y = spots[i + 1] + sin(a.toDouble()).toFloat() * rr
                walkers.add(Walker(x, y, spots[i], spots[i + 1], spots[i + 2], rnd.nextInt(8)))
            }
            i += 3
        }
    }

    // ---------- moving things ----------

    private val urnd = Random()
    private val carColors = intArrayOf(0xFFD7263D.toInt(), 0xFFE9ECEF.toInt(), 0xFF3A86FF.toInt(), 0xFFF4A300.toInt(), 0xFF2B2D42.toInt())
    private val groundCols = intArrayOf(0xFF7BA655.toInt(), 0xFF9BBF68.toInt(), 0xFFB5A86A.toInt(), 0xFF6E9A4E.toInt(), 0xFFA8B070.toInt())
    private val patches = FloatArray(90 * 4)

    fun update(dt: Float, jam: Boolean) {
        val rnd = urnd
        for (v in vehicles) {
            val sp = if (jam && v.road.name == "Ring Road") v.speed * 0.15f else v.speed
            v.d += sp * dt * v.dir
            if (!v.road.closed) {
                if (v.d > v.road.total) {
                    v.d = v.road.total
                    v.dir = -1
                }
                if (v.d < 0f) {
                    v.d = 0f
                    v.dir = 1
                }
            }
        }
        for (w in walkers) {
            val dx = w.tx - w.x
            val dy = w.ty - w.y
            val l = hypot(dx, dy)
            if (l < 4f) {
                val a = rnd.nextFloat() * 6.28f
                val rr = rnd.nextFloat() * w.range
                w.tx = w.homeX + cos(a.toDouble()).toFloat() * rr
                w.ty = w.homeY + sin(a.toDouble()).toFloat() * rr
            } else {
                w.x += dx / l * 18f * dt
                w.y += dy / l * 18f * dt
            }
        }
    }

    // ---------- drawing ----------

    private fun shade(c: Int, day: Float): Int = mix(0xFF0B1022.toInt(), c, 0.55f + 0.45f * day)

    fun draw(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float, rain: Boolean, jam: Boolean) {
        drawGround(c, l, t, rt, b, day)
        drawMoat(c, l, t, rt, b, day)
        drawRoads(c, l, t, rt, b, day)
        drawTrees(c, l, t, rt, b, day)
        drawBuildings(c, l, t, rt, b, day)
        drawLandmarks(c, l, t, rt, b, tm, day)
        Landmarks.draw(c, l, t, rt, b, tm, day)
        drawVehicles(c, l, t, rt, b, day, rain)
        drawWalkers(c, l, t, rt, b)
        drawLabels(c, l, t, rt, b, day)
    }

    private fun drawGround(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        p.shader = null
        p.color = mix(0xFF0C1A10.toInt(), 0xFF86B25F.toInt(), 0.35f + 0.65f * day)
        c.drawRect(max(0f, l), max(0f, t), min(WORLD_W, rt), min(WORLD_H, b), p)
        // farm and bush patches
        if (patches[0] == 0f) {
            val rnd = Random(31)
            for (i in 0 until 90) {
                patches[i * 4] = rnd.nextFloat() * WORLD_W
                patches[i * 4 + 1] = rnd.nextFloat() * WORLD_H
                patches[i * 4 + 2] = 90f + rnd.nextFloat() * 220f
                patches[i * 4 + 3] = 70f + rnd.nextFloat() * 160f
            }
        }
        for (i in 0 until 90) {
            val x = patches[i * 4]
            val y = patches[i * 4 + 1]
            val rx = patches[i * 4 + 2]
            val ry = patches[i * 4 + 3]
            if (x + rx < l || x - rx > rt || y + ry < t || y - ry > b) continue
            p.color = shade(groundCols[i % groundCols.size], day)
            r.set(x - rx, y - ry, x + rx, y + ry)
            c.drawOval(r, p)
        }
        // the old built-up city is paved and dusty
        p.color = shade(0xFFC7B496.toInt(), day)
        r.set(KX - 1000f, KY - 900f, KX + 1000f, KY + 900f)
        c.drawOval(r, p)
        p.color = shade(0xFFD2BFA2.toInt(), day)
        r.set(KX - 640f, KY - 580f, KX + 640f, KY + 580f)
        c.drawOval(r, p)
    }

    private fun drawMoat(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val ranges = floatArrayOf(-175f, -105f, -60f, 15f, 28f, 100f, 128f, 172f)
        var i = 0
        while (i < ranges.size) {
            path.reset()
            var a = ranges[i]
            var first = true
            while (a <= ranges[i + 1]) {
                val x = moatX(a.toDouble())
                val y = moatY(a.toDouble())
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else path.lineTo(x, y)
                a += 3f
            }
            s.style = Paint.Style.STROKE
            s.strokeCap = Paint.Cap.BUTT
            s.strokeWidth = 56f
            s.color = shade(0xFFB88A5E.toInt(), day)
            c.drawPath(path, s)
            s.strokeWidth = 34f
            s.color = shade(0xFF3F6B34.toInt(), day)
            c.drawPath(path, s)
            s.strokeWidth = 14f
            s.color = shade(0xFF2D4F2A.toInt(), day)
            c.drawPath(path, s)
            s.strokeCap = Paint.Cap.ROUND
            i += 2
        }
    }

    private fun drawRoads(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        s.style = Paint.Style.STROKE
        for (rd in roads) {
            s.strokeCap = Paint.Cap.BUTT
            s.strokeWidth = rd.width + 10f
            s.color = shade(0xFFB89B72.toInt(), day)
            c.drawPath(rd.path, s)
            s.strokeWidth = rd.width
            s.color = shade(0xFF4A4D55.toInt(), day)
            c.drawPath(rd.path, s)
            s.strokeWidth = 2.5f
            s.color = shade(0xFFF2D45C.toInt(), day)
            s.pathEffect = dash
            c.drawPath(rd.path, s)
            s.pathEffect = null
            s.strokeCap = Paint.Cap.ROUND
        }
    }

    private val dash = android.graphics.DashPathEffect(floatArrayOf(18f, 16f), 0f)

    private fun drawTrees(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val d = treeData
        var i = 0
        while (i < d.size) {
            val x = d[i]
            val y = d[i + 1]
            val rad = d[i + 2]
            i += 3
            if (x < l - 30f || x > rt + 30f || y < t - 30f || y > b + 30f) continue
            p.color = 0x33000000
            c.drawCircle(x + 4f, y + 5f, rad, p)
            p.color = shade(0xFF2F7A3C.toInt(), day)
            c.drawCircle(x, y, rad, p)
            p.color = shade(0xFF4C9B4F.toInt(), day)
            c.drawCircle(x - rad * 0.25f, y - rad * 0.25f, rad * 0.62f, p)
        }
    }

    private fun drawBuildings(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        for (bd in buildings) {
            if (bd.x < l - 60f || bd.x > rt + 60f || bd.y < t - 60f || bd.y > b + 60f) continue
            c.save()
            c.translate(bd.x, bd.y)
            c.rotate(bd.angle)
            p.color = 0x44000000
            r.set(-bd.w / 2 + 5f, -bd.h / 2 + 6f, bd.w / 2 + 5f, bd.h / 2 + 6f)
            c.drawRect(r, p)
            val seeThrough = hypot(bd.x - px, bd.y - py) < bd.w * 0.5f + 34f
            p.color = shade(bd.wall, day)
            r.set(-bd.w / 2, -bd.h / 2, bd.w / 2, bd.h / 2)
            c.drawRect(r, p)
            p.color = if (seeThrough) ((shade(bd.roof, day) and 0x00FFFFFF) or 0x55000000) else shade(bd.roof, day)
            r.set(-bd.w / 2 + 2f, -bd.h / 2 + 2f, bd.w / 2 - 2f, bd.h / 2 - 2f)
            c.drawRect(r, p)
            if (bd.pitched && !seeThrough) {
                p.color = shade(mix(bd.roof, 0xFF000000.toInt(), 0.25f), day)
                c.drawRect(-bd.w / 2 + 2f, -1.5f, bd.w / 2 - 2f, 1.5f, p)
                p.color = shade(mix(bd.roof, 0xFFFFFFFF.toInt(), 0.2f), day)
                c.drawRect(-bd.w / 2 + 2f, -bd.h / 2 + 2f, bd.w / 2 - 2f, -2f, p)
            }
            c.restore()
        }
    }

    private fun stallBlock(c: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, seed: Long, day: Float, tm: Float) {
        p.color = shade(0xFFC9A978.toInt(), day)
        r.set(cx - hw, cy - hh, cx + hw, cy + hh)
        c.drawRect(r, p)
        s.style = Paint.Style.STROKE
        s.strokeWidth = 4f
        s.color = shade(0xFF8A4B2D.toInt(), day)
        c.drawRect(r, s)
        val rnd = Random(seed)
        var y = cy - hh + 14f
        while (y < cy + hh - 14f) {
            var x = cx - hw + 14f
            while (x < cx + hw - 14f) {
                val w = 22f
                val h = 16f
                p.color = 0x44000000
                r.set(x + 2f, y + 3f, x + w + 2f, y + h + 3f)
                c.drawRect(r, p)
                p.color = shade(TARPS[rnd.nextInt(TARPS.size)], day)
                r.set(x, y, x + w, y + h)
                c.drawRect(r, p)
                if (rnd.nextInt(3) == 0) {
                    p.color = shade(TARPS[rnd.nextInt(TARPS.size)], day)
                    c.drawCircle(x + w / 2, y + h / 2, 9f, p)
                    p.color = 0x55FFFFFF
                    c.drawCircle(x + w / 2 - 2f, y + h / 2 - 2f, 4f, p)
                }
                x += 34f
            }
            y += 30f
        }
    }

    private fun drawLandmarks(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        fun visible(x: Float, y: Float, rad: Float) = !(x + rad < l || x - rad > rt || y + rad < t || y - rad > b)

        // ---- King's Square and the National Museum ----
        if (visible(KX, KY, 290f)) {
            p.color = shade(0xFF5FA05A.toInt(), day)
            c.drawCircle(KX, KY, 268f, p)
            s.style = Paint.Style.STROKE
            s.strokeWidth = 22f
            s.color = shade(0xFFD8CBB0.toInt(), day)
            c.drawCircle(KX, KY, 190f, s)
            s.strokeWidth = 3f
            s.color = shade(0xFF8FBF86.toInt(), day)
            c.drawCircle(KX, KY, 236f, s)
            for (k in 0 until 16) {
                val a = k * 0.3927f
                val x = KX + cos(a.toDouble()).toFloat() * 222f
                val y = KY + sin(a.toDouble()).toFloat() * 222f
                p.color = 0x33000000
                c.drawCircle(x + 3f, y + 4f, 11f, p)
                p.color = shade(0xFF2F7A3C.toInt(), day)
                c.drawCircle(x, y, 11f, p)
                p.color = shade(0xFF4C9B4F.toInt(), day)
                c.drawCircle(x - 3f, y - 3f, 6f, p)
            }
            // the circular museum building
            p.color = 0x44000000
            c.drawCircle(KX + 6f, KY + 8f, 74f, p)
            p.color = shade(0xFFEAE3D2.toInt(), day)
            c.drawCircle(KX, KY, 72f, p)
            p.color = shade(0xFFC9BFA6.toInt(), day)
            c.drawCircle(KX, KY, 58f, p)
            for (k in 0 until 28) {
                val a = k * 0.2244f
                p.color = shade(0xFF3A4A5C.toInt(), day)
                c.drawCircle(KX + cos(a.toDouble()).toFloat() * 65f, KY + sin(a.toDouble()).toFloat() * 65f, 3f, p)
            }
            p.color = shade(0xFFB5832F.toInt(), day)
            c.drawCircle(KX, KY, 34f, p)
            p.color = shade(0xFFD9A441.toInt(), day)
            c.drawCircle(KX - 5f, KY - 5f, 20f, p)
        }

        // ---- the Oba's palace ----
        if (visible(1300f, 1230f, 200f)) {
            val cx = 1300f
            val cy = 1230f
            p.color = 0x44000000
            r.set(cx - 165f + 8f, cy - 125f + 10f, cx + 165f + 8f, cy + 125f + 10f)
            c.drawRect(r, p)
            p.color = shade(0xFFD8B98A.toInt(), day)
            r.set(cx - 165f, cy - 125f, cx + 165f, cy + 125f)
            c.drawRect(r, p)
            s.style = Paint.Style.STROKE
            s.strokeWidth = 12f
            s.strokeCap = Paint.Cap.BUTT
            s.color = shade(0xFFA8452C.toInt(), day)
            c.drawRect(r, s)
            s.strokeCap = Paint.Cap.ROUND
            val roofs = floatArrayOf(-110f, -70f, 90f, 50f, 20f, -80f, 70f, 40f, -90f, 20f, 80f, 44f)
            var k = 0
            while (k < roofs.size) {
                p.color = shade(0xFF8A3B28.toInt(), day)
                r.set(cx + roofs[k], cy + roofs[k + 1], cx + roofs[k] + roofs[k + 2], cy + roofs[k + 1] + roofs[k + 3])
                c.drawRect(r, p)
                p.color = shade(0xFFB5543C.toInt(), day)
                r.set(cx + roofs[k] + 4f, cy + roofs[k + 1] + 4f, cx + roofs[k] + roofs[k + 2] - 4f, cy + roofs[k + 1] + roofs[k + 3] - 4f)
                c.drawRect(r, p)
                k += 4
            }
            // the tall tower with the bronze bird, seen from above
            p.color = 0x44000000
            c.drawCircle(cx - 20f + 10f, cy - 30f + 12f, 24f, p)
            p.color = shade(0xFF7A3322.toInt(), day)
            c.drawCircle(cx - 20f, cy - 30f, 22f, p)
            p.color = shade(0xFFA8452C.toInt(), day)
            c.drawCircle(cx - 20f, cy - 30f, 14f, p)
            p.color = shade(0xFFD9A441.toInt(), day)
            c.drawCircle(cx - 20f, cy - 30f, 5f, p)
            // the big arch gate, facing the square
            p.color = shade(0xFF5A2418.toInt(), day)
            r.set(cx + 120f, cy + 112f, cx + 165f, cy + 138f)
            c.drawRect(r, p)
            p.color = shade(0xFFD9A441.toInt(), day)
            r.set(cx + 128f, cy + 120f, cx + 158f, cy + 130f)
            c.drawRoundRect(r, 5f, 5f, p)
        }

        // ---- Oba Market and the Emotan statue opposite ----
        if (visible(2130f, 1380f, 200f)) stallBlock(c, 2130f, 1380f, 180f, 115f, 1L, day, tm)
        if (visible(2130f, 1650f, 110f)) {
            p.color = shade(0xFFE2D8C2.toInt(), day)
            c.drawCircle(2130f, 1650f, 98f, p)
            p.color = shade(0xFFCFC3A8.toInt(), day)
            c.drawCircle(2130f, 1650f, 70f, p)
            p.color = shade(0xFF9C8F76.toInt(), day)
            c.drawCircle(2130f, 1650f, 16f, p)
            p.color = shade(0xFFB5832F.toInt(), day)
            c.drawCircle(2130f, 1650f, 9f, p)
            // the sacred Uruhe tree beside the statue
            p.color = 0x33000000
            c.drawCircle(2178f, 1632f, 42f, p)
            p.color = shade(0xFF2F7A3C.toInt(), day)
            c.drawCircle(2172f, 1626f, 40f, p)
            p.color = shade(0xFF4C9B4F.toInt(), day)
            c.drawCircle(2160f, 1614f, 24f, p)
            p.color = shade(0xFF63B45E.toInt(), day)
            c.drawCircle(2182f, 1634f, 14f, p)
        }

        // ---- Igun Street, home of the bronze casters ----
        if (visible(1320f, 1960f, 200f)) {
            var x = 1190f
            var i = 0
            while (x < 1480f) {
                for (side in 0..1) {
                    val y = if (side == 0) 1914f else 1990f
                    p.color = 0x44000000
                    r.set(x + 3f, y + 4f, x + 33f, y + 28f)
                    c.drawRect(r, p)
                    p.color = shade(if ((i + side) % 2 == 0) 0xFF8A5A2B.toInt() else 0xFF6B4423.toInt(), day)
                    r.set(x, y, x + 30f, y + 24f)
                    c.drawRect(r, p)
                    p.color = shade(0xFFB88A3E.toInt(), day)
                    r.set(x + 3f, y + 3f, x + 27f, y + 21f)
                    c.drawRect(r, p)
                    // glow of the forge fire
                    val fl = 0.6f + 0.4f * sin(tm * 9f + x + side * 3f)
                    p.color = ((fl * 150f).toInt() shl 24) or 0xFF7A1A
                    c.drawCircle(x + 15f, y + 12f, 5f + 2f * fl, p)
                }
                x += 40f
                i++
            }
        }

        // ---- Ekeosa Market ----
        if (visible(1380f, 2200f, 150f)) stallBlock(c, 1380f, 2200f, 120f, 100f, 2L, day, tm)

        // ---- the University of Benin, Ugbowo campus ----
        if (visible(300f, 1100f, 320f)) drawCampus(c, tm, day)

        // ---- Ekosodin student hostels ----
        if (visible(450f, 1480f, 170f)) {
            val rnd = Random(12)
            for (gx in 0 until 6) {
                for (gy in 0 until 5) {
                    val x = 370f + gx * 34f
                    val y = 1410f + gy * 32f
                    p.color = 0x44000000
                    r.set(x + 3f, y + 4f, x + 29f, y + 25f)
                    c.drawRect(r, p)
                    p.color = shade(WALLS[rnd.nextInt(WALLS.size)], day)
                    r.set(x, y, x + 26f, y + 22f)
                    c.drawRect(r, p)
                    p.color = shade(ROOFS[rnd.nextInt(ROOFS.size)], day)
                    r.set(x + 2f, y + 2f, x + 24f, y + 20f)
                    c.drawRect(r, p)
                }
            }
        }

        // ---- GRA: big villas with gardens and pools ----
        if (visible(1480f, 640f, 300f)) {
            val rnd = Random(55)
            for (i in 0 until 16) {
                val a = rnd.nextFloat() * 6.28f
                val rr = 60f + rnd.nextFloat() * 220f
                val x = 1480f + cos(a.toDouble()).toFloat() * rr
                val y = 640f + sin(a.toDouble()).toFloat() * rr
                if (roadClear(x, y) < 60f) continue
                p.color = shade(0xFF77B35E.toInt(), day)
                r.set(x - 46f, y - 40f, x + 46f, y + 40f)
                c.drawRect(r, p)
                s.style = Paint.Style.STROKE
                s.strokeWidth = 3f
                s.color = shade(0xFFEDE7D9.toInt(), day)
                c.drawRect(r, s)
                p.color = 0x44000000
                r.set(x - 26f + 5f, y - 20f + 6f, x + 26f + 5f, y + 14f + 6f)
                c.drawRect(r, p)
                p.color = shade(0xFFF5F1EA.toInt(), day)
                r.set(x - 26f, y - 20f, x + 26f, y + 14f)
                c.drawRect(r, p)
                p.color = shade(0xFFB5493A.toInt(), day)
                r.set(x - 22f, y - 16f, x + 22f, y + 10f)
                c.drawRect(r, p)
                if (i % 2 == 0) {
                    p.color = shade(0xFF4DB6E8.toInt(), day)
                    r.set(x + 8f, y + 18f, x + 36f, y + 32f)
                    c.drawRoundRect(r, 4f, 4f, p)
                }
            }
        }

        // ---- the lodge ----
        if (visible(1790f, 1050f, 80f)) {
            p.color = 0x44000000
            r.set(1766f + 5f, 1030f + 6f, 1816f + 5f, 1066f + 6f)
            c.drawRect(r, p)
            p.color = shade(0xFFF1E4CC.toInt(), day)
            r.set(1766f, 1030f, 1816f, 1066f)
            c.drawRect(r, p)
            p.color = shade(0xFF4C6A8A.toInt(), day)
            r.set(1770f, 1034f, 1812f, 1062f)
            c.drawRect(r, p)
            p.color = shade(0xFFF6F5F2.toInt(), day)
            r.set(1776f, 1040f, 1796f, 1050f)
            c.drawRoundRect(r, 3f, 3f, p)
            r.set(1800f, 1040f, 1808f, 1056f)
            c.drawRect(r, p)
        }

        // ---- Benin Airport ----
        if (visible(2650f, 280f, 320f)) {
            p.color = shade(0xFF8E9298.toInt(), day)
            r.set(2380f, 195f, 2960f, 235f)
            c.drawRect(r, p)
            p.color = shade(0xFFF2F2F2.toInt(), day)
            var x = 2395f
            while (x < 2940f) {
                c.drawRect(x, 213f, x + 22f, 217f, p)
                x += 40f
            }
            p.color = shade(0xFFA7ABB0.toInt(), day)
            r.set(2500f, 240f, 2820f, 330f)
            c.drawRect(r, p)
            p.color = 0x44000000
            r.set(2540f + 6f, 262f + 8f, 2760f + 6f, 312f + 8f)
            c.drawRect(r, p)
            p.color = shade(0xFFEAEAEA.toInt(), day)
            r.set(2540f, 262f, 2760f, 312f)
            c.drawRect(r, p)
            p.color = shade(0xFF4C6A8A.toInt(), day)
            r.set(2548f, 270f, 2752f, 296f)
            c.drawRect(r, p)
            p.color = shade(0xFFD9D9D9.toInt(), day)
            c.drawCircle(2790f, 280f, 12f, p)
            // a plane takes off along the runway
            val px = 2380f + ((tm * 60f) % 640f)
            val py = 215f - (if (px > 2800f) (px - 2800f) * 0.15f else 0f)
            p.color = 0x44000000
            r.set(px - 20f + 6f, py - 4f + 8f, px + 20f + 6f, py + 4f + 8f)
            c.drawOval(r, p)
            p.color = 0xFFF8F8F8.toInt()
            r.set(px - 20f, py - 4f, px + 20f, py + 4f)
            c.drawOval(r, p)
            path.reset()
            path.moveTo(px - 4f, py)
            path.lineTo(px + 4f, py - 20f)
            path.lineTo(px + 9f, py - 20f)
            path.lineTo(px + 5f, py)
            path.lineTo(px + 9f, py + 20f)
            path.lineTo(px + 4f, py + 20f)
            path.close()
            c.drawPath(path, p)
        }

        // ---- Ikpoba Hill ----
        if (visible(2450f, 2650f, 260f)) {
            val cols = intArrayOf(0xFF6FA24F.toInt(), 0xFF5F9645.toInt(), 0xFF4F8A3C.toInt(), 0xFF457E36.toInt(), 0xFF3B7330.toInt())
            for (k in 0 until 5) {
                p.color = shade(cols[k], day)
                val rr = 230f - k * 42f
                r.set(2450f - rr, 2650f - rr * 0.85f, 2450f + rr, 2650f + rr * 0.85f)
                c.drawOval(r, p)
            }
            p.color = shade(0xFF8A6A48.toInt(), day)
            c.drawCircle(2450f, 2650f, 14f, p)
            p.color = shade(0xFF3A3F46.toInt(), day)
            c.drawRect(2446f, 2610f, 2454f, 2650f, p)
            if (((tm * 1.2f).toInt() % 2) == 0) {
                p.color = 0xFFFF2D2D.toInt()
                c.drawCircle(2450f, 2606f, 5f, p)
                p.color = 0x44FF2D2D
                c.drawCircle(2450f, 2606f, 14f, p)
            }
        }

        // ---- a stone marker on the moat ----
        if (visible(moatX(40.0), moatY(40.0), 80f)) {
            val mx = moatX(40.0)
            val my = moatY(40.0)
            p.color = shade(0xFFC9C0AE.toInt(), day)
            r.set(mx - 26f, my - 70f, mx + 26f, my - 52f)
            c.drawRect(r, p)
            p.color = shade(0xFF8A7F6B.toInt(), day)
            r.set(mx - 22f, my - 68f, mx + 22f, my - 54f)
            c.drawRect(r, p)
        }
    }

    private fun drawCampus(c: Canvas, tm: Float, day: Float) {
        val cx = 300f
        val cy = 1100f
        p.color = shade(0xFF6FB05C.toInt(), day)
        c.drawCircle(cx, cy, 285f, p)
        s.style = Paint.Style.STROKE
        s.strokeWidth = 5f
        s.color = shade(0xFFE5DCC6.toInt(), day)
        path.reset()
        path.moveTo(cx + 40f, cy + 20f)
        path.quadTo(cx - 20f, cy - 40f, cx - 120f, cy - 60f)
        path.moveTo(cx + 40f, cy + 20f)
        path.quadTo(cx, cy + 90f, cx - 100f, cy + 130f)
        path.moveTo(cx + 40f, cy + 20f)
        path.lineTo(cx + 180f, cy + 20f)
        c.drawPath(path, s)

        // sports oval
        p.color = shade(0xFF8A5A3A.toInt(), day)
        r.set(cx - 200f, cy + 80f, cx - 70f, cy + 170f)
        c.drawOval(r, p)
        p.color = shade(0xFF4F9A4B.toInt(), day)
        r.set(cx - 188f, cy + 92f, cx - 82f, cy + 158f)
        c.drawOval(r, p)

        // lecture theatres and halls
        val blocks = floatArrayOf(-140f, -150f, 90f, 40f, -30f, -110f, 70f, 36f, 40f, 60f, 100f, 44f, -150f, -40f, 60f, 40f, 80f, -50f, 60f, 36f)
        var k = 0
        while (k < blocks.size) {
            val x = cx + blocks[k]
            val y = cy + blocks[k + 1]
            p.color = 0x44000000
            r.set(x + 5f, y + 7f, x + blocks[k + 2] + 5f, y + blocks[k + 3] + 7f)
            c.drawRect(r, p)
            p.color = shade(0xFFEDE7D9.toInt(), day)
            r.set(x, y, x + blocks[k + 2], y + blocks[k + 3])
            c.drawRect(r, p)
            p.color = shade(0xFFB5493A.toInt(), day)
            r.set(x + 3f, y + 3f, x + blocks[k + 2] - 3f, y + blocks[k + 3] - 3f)
            c.drawRect(r, p)
            k += 4
        }
        // the administrative block with its clock tower
        p.color = shade(0xFFF4EFE3.toInt(), day)
        r.set(cx + 10f, cy - 20f, cx + 110f, cy + 20f)
        c.drawRect(r, p)
        p.color = shade(0xFF8A3B28.toInt(), day)
        r.set(cx + 14f, cy - 16f, cx + 106f, cy + 16f)
        c.drawRect(r, p)
        p.color = 0x44000000
        r.set(cx + 50f + 5f, cy - 12f + 7f, cx + 72f + 5f, cy + 10f + 7f)
        c.drawRect(r, p)
        p.color = shade(0xFFF4EFE3.toInt(), day)
        r.set(cx + 50f, cy - 12f, cx + 72f, cy + 10f)
        c.drawRect(r, p)
        p.color = shade(0xFFFFFFFF.toInt(), day)
        c.drawCircle(cx + 61f, cy - 1f, 7f, p)

        // main gate on the road side
        p.color = shade(0xFFEDE7D9.toInt(), day)
        r.set(cx + 236f, cy + 4f, cx + 252f, cy + 22f)
        c.drawRect(r, p)
        r.set(cx + 236f, cy + 50f, cx + 252f, cy + 68f)
        c.drawRect(r, p)
        p.color = shade(0xFF1D4E89.toInt(), day)
        r.set(cx + 236f, cy + 22f, cx + 252f, cy + 50f)
        c.drawRect(r, p)

        // trees on the lawns
        val rnd = Random(3)
        for (i in 0 until 26) {
            val a = rnd.nextFloat() * 6.28f
            val rr = 30f + rnd.nextFloat() * 240f
            val x = cx + cos(a.toDouble()).toFloat() * rr
            val y = cy + sin(a.toDouble()).toFloat() * rr
            p.color = 0x33000000
            c.drawCircle(x + 4f, y + 5f, 12f, p)
            p.color = shade(0xFF2F7A3C.toInt(), day)
            c.drawCircle(x, y, 12f, p)
            p.color = shade(0xFF4C9B4F.toInt(), day)
            c.drawCircle(x - 3f, y - 3f, 7f, p)
        }
    }

    private fun drawVehicles(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float, rain: Boolean) {
        for (v in vehicles) {
            v.road.at(v.d, out3)
            val heading = out3[2] + (if (v.dir < 0) 3.14159f else 0f)
            val side = v.road.width * 0.23f
            val x = out3[0] - sin(out3[2].toDouble()).toFloat() * side * v.dir
            val y = out3[1] + cos(out3[2].toDouble()).toFloat() * side * v.dir
            if (x < l - 40f || x > rt + 40f || y < t - 40f || y > b + 40f) continue
            c.save()
            c.translate(x, y)
            c.rotate(Math.toDegrees(heading.toDouble()).toFloat())
            when (v.kind) {
                0 -> {
                    // keke
                    p.color = 0x44000000
                    r.set(-12f + 3f, -8f + 4f, 12f + 3f, 8f + 4f)
                    c.drawRoundRect(r, 4f, 4f, p)
                    p.color = shade(0xFFFFC400.toInt(), day)
                    r.set(-12f, -8f, 12f, 8f)
                    c.drawRoundRect(r, 4f, 4f, p)
                    p.color = shade(0xFF1B8A4B.toInt(), day)
                    r.set(-12f, -2f, 12f, 2f)
                    c.drawRect(r, p)
                    p.color = shade(0xFF2C3E5A.toInt(), day)
                    r.set(3f, -6f, 8f, 6f)
                    c.drawRect(r, p)
                }
                1 -> {
                    // minibus
                    p.color = 0x44000000
                    r.set(-22f + 3f, -9f + 4f, 22f + 3f, 9f + 4f)
                    c.drawRoundRect(r, 3f, 3f, p)
                    p.color = shade(0xFFF2F2F2.toInt(), day)
                    r.set(-22f, -9f, 22f, 9f)
                    c.drawRoundRect(r, 3f, 3f, p)
                    p.color = shade(0xFF1B8A4B.toInt(), day)
                    r.set(-22f, -2f, 22f, 2f)
                    c.drawRect(r, p)
                    p.color = shade(0xFF2C3E5A.toInt(), day)
                    r.set(14f, -7f, 20f, 7f)
                    c.drawRect(r, p)
                }
                else -> {
                    // car
                    val cols = carColors
                    p.color = 0x44000000
                    r.set(-15f + 3f, -7f + 4f, 15f + 3f, 7f + 4f)
                    c.drawRoundRect(r, 5f, 5f, p)
                    p.color = shade(cols[(v.d.toInt() / 7) % cols.size], day)
                    r.set(-15f, -7f, 15f, 7f)
                    c.drawRoundRect(r, 5f, 5f, p)
                    p.color = shade(0xFF2C3E5A.toInt(), day)
                    r.set(-4f, -5f, 7f, 5f)
                    c.drawRect(r, p)
                }
            }
            if (day < 0.45f) {
                p.color = 0x44FFF2B0
                path.reset()
                path.moveTo(14f, -3f)
                path.lineTo(52f, -14f)
                path.lineTo(52f, 14f)
                path.lineTo(14f, 3f)
                path.close()
                c.drawPath(path, p)
            }
            c.restore()
        }
    }

    private fun drawWalkers(c: Canvas, l: Float, t: Float, rt: Float, b: Float) {
        val shirts = intArrayOf(0xFFE4572E.toInt(), 0xFF29A19C.toInt(), 0xFFF3A712.toInt(), 0xFF4D7EA8.toInt(), 0xFFA663CC.toInt(), 0xFF59A96A.toInt(), 0xFFD7263D.toInt(), 0xFFEFEFEF.toInt())
        for (w in walkers) {
            if (w.x < l - 20f || w.x > rt + 20f || w.y < t - 20f || w.y > b + 20f) continue
            p.color = 0x44000000
            c.drawCircle(w.x + 2f, w.y + 3f, 5f, p)
            p.color = shirts[w.shade % shirts.size]
            c.drawCircle(w.x, w.y, 4.6f, p)
            p.color = 0xFF3E2A1C.toInt()
            c.drawCircle(w.x, w.y, 2.6f, p)
        }
    }

    private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, light: Boolean) {
        p.textSize = size
        p.isFakeBoldText = true
        val tw = p.measureText(text)
        s.style = Paint.Style.STROKE
        s.textSize = size
        s.isFakeBoldText = true
        s.strokeWidth = size * 0.28f
        s.color = if (light) 0xCC10161C.toInt() else 0xDD10161C.toInt()
        c.drawText(text, x - tw / 2, y, s)
        p.color = if (light) 0xFFE9EEF3.toInt() else 0xFFFFFFFF.toInt()
        c.drawText(text, x - tw / 2, y, p)
        s.isFakeBoldText = false
        p.isFakeBoldText = false
    }

    private fun drawLabels(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        for (pl in PLACES) {
            if (pl.id == P_MUSEUM) continue
            val ly = when (pl.id) {
                P_KINGS -> pl.y - 285f
                P_PALACE -> pl.y - 150f
                P_OBA -> pl.y - 130f
                P_EKEOSA -> pl.y - 112f
                P_UNIBEN -> pl.y - 300f
                P_AIRPORT -> pl.y - 110f
                P_GRA -> pl.y - 285f
                P_IKPOBA -> pl.y - 235f
                P_MOAT -> pl.y - 90f
                P_EKOSODIN -> pl.y - 100f
                P_LODGE -> pl.y - 44f
                P_EMOTAN -> pl.y + 125f
                P_IGUN -> pl.y - 74f
                else -> pl.y - pl.r - 14f
            }
            if (pl.x < l - 200f || pl.x > rt + 200f || ly < t - 40f || ly > b + 40f) continue
            label(c, pl.name, pl.x, ly, 17f, false)
        }
        label(c, "National Museum", KX, KY + 100f, 12f, true)
        // road names, written along the road
        for (rd in roads) {
            if (rd.name == "Ring Road") continue
            rd.at(rd.total * 0.5f, out3)
            val x = out3[0]
            val y = out3[1]
            if (x < l - 100f || x > rt + 100f || y < t - 40f || y > b + 40f) continue
            c.save()
            c.translate(x, y)
            var deg = Math.toDegrees(out3[2].toDouble()).toFloat()
            if (deg > 90f) deg -= 180f
            if (deg < -90f) deg += 180f
            c.rotate(deg)
            label(c, rd.name, 0f, 4f, 11f, true)
            c.restore()
        }
        rd0(c, l, t, rt, b)
    }

    private fun rd0(c: Canvas, l: Float, t: Float, rt: Float, b: Float) {
        val x = KX
        val y = KY - 330f
        if (x < l - 100f || x > rt + 100f || y < t - 40f || y > b + 40f) return
        label(c, "Ring Road", x, y + 4f, 12f, true)
    }
}
