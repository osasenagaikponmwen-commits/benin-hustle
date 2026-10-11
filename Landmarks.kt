package com.nagi.hustle

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The hand-drawn buildings for the newer places on the map. */
object Landmarks {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val carCols = intArrayOf(0xFFD7263D.toInt(), 0xFFE9ECEF.toInt(), 0xFF3A86FF.toInt(), 0xFFF4A300.toInt(), 0xFF2B2D42.toInt(), 0xFF2A9D8F.toInt())
    private val tarps = intArrayOf(0xFFE63946.toInt(), 0xFF2A9D8F.toInt(), 0xFFF4A261.toInt(), 0xFF457B9D.toInt(), 0xFFE9C46A.toInt(), 0xFF8E44AD.toInt(), 0xFF1D8348.toInt(), 0xFFF1FAEE.toInt())

    private fun sh(c: Int, day: Float): Int = mix(0xFF0B1022.toInt(), c, 0.55f + 0.45f * day)

    private fun vis(x: Float, y: Float, rad: Float, l: Float, t: Float, rt: Float, b: Float): Boolean =
        !(x + rad < l || x - rad > rt || y + rad < t || y - rad > b)

    private fun rect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        p.color = color
        r.set(x0, y0, x1, y1)
        c.drawRect(r, p)
    }

    private fun shadowRect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        rect(c, x0 + 5f, y0 + 6f, x1 + 5f, y1 + 6f, 0x44000000)
    }

    /** A flat-roofed building seen from above, with a shadow. */
    private fun block(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, wall: Int, roof: Int, day: Float) {
        shadowRect(c, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        rect(c, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, sh(wall, day))
        rect(c, cx - w / 2 + 2f, cy - h / 2 + 2f, cx + w / 2 - 2f, cy + h / 2 - 2f, sh(roof, day))
    }

    private fun car(c: Canvas, x: Float, y: Float, color: Int, day: Float) {
        rect(c, x - 7f, y - 4f, x + 7f, y + 4f, sh(color, day))
        rect(c, x - 2f, y - 3f, x + 3f, y + 3f, 0x66FFFFFF)
    }

    private fun flag(c: Canvas, x: Float, y: Float, day: Float) {
        s.strokeWidth = 1.6f
        s.color = 0xFF555555.toInt()
        c.drawLine(x, y, x, y - 22f, s)
        rect(c, x, y - 22f, x + 5f, y - 14f, sh(0xFF1E8A4A.toInt(), day))
        rect(c, x + 5f, y - 22f, x + 10f, y - 14f, sh(0xFFFFFFFF.toInt(), day))
        rect(c, x + 10f, y - 22f, x + 15f, y - 14f, sh(0xFF1E8A4A.toInt(), day))
    }

    fun draw(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        olokun(c, l, t, rt, b, tm, day)
        urhokpota(c, l, t, rt, b, day)
        assembly(c, l, t, rt, b, day)
        ubth(c, l, t, rt, b, tm, day)
        stadium(c, l, t, rt, b, day)
        secretariat(c, l, t, rt, b, day)
        bankRow(c, l, t, rt, b, tm, day)
        bureau(c, l, t, rt, b, day)
        nadia(c, l, t, rt, b, day)
        zoo(c, l, t, rt, b, day)
        newBenin(c, l, t, rt, b, day)
        kada(c, l, t, rt, b, tm, day)
    }

    private fun olokun(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        val cx = 1600f
        val cy = 1320f
        if (!vis(cx, cy, 70f, l, t, rt, b)) return
        p.color = 0x44000000
        c.drawCircle(cx + 4f, cy + 5f, 54f, p)
        p.color = sh(0xFF3D9AD1.toInt(), day)
        c.drawCircle(cx, cy, 52f, p)
        s.strokeWidth = 5f
        s.color = sh(0xFFE6F2F8.toInt(), day)
        c.drawCircle(cx, cy, 52f, s)
        p.color = sh(0xFFC9C2B2.toInt(), day)
        c.drawCircle(cx, cy, 24f, p)
        p.color = sh(0xFFB07A2A.toInt(), day)
        c.drawCircle(cx, cy, 13f, p)
        s.strokeWidth = 2.5f
        s.color = sh(0xFFE8C45A.toInt(), day)
        for (k in 0 until 8) {
            val a = k * 0.7854f
            c.drawLine(cx + cos(a.toDouble()).toFloat() * 11f, cy + sin(a.toDouble()).toFloat() * 11f, cx + cos(a.toDouble()).toFloat() * 21f, cy + sin(a.toDouble()).toFloat() * 21f, s)
        }
        p.color = 0x99FFFFFF.toInt()
        for (k in 0 until 8) {
            val a = k * 0.7854f + tm * 0.6f
            c.drawCircle(cx + cos(a.toDouble()).toFloat() * 40f, cy + sin(a.toDouble()).toFloat() * 40f, 2.6f, p)
        }
    }

    private fun urhokpota(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 1900f
        val cy = 1260f
        if (!vis(cx, cy, 120f, l, t, rt, b)) return
        rect(c, cx - 95f, cy - 70f, cx + 95f, cy + 70f, sh(0xFF6FA24F.toInt(), day))
        rect(c, cx - 8f, cy + 22f, cx + 8f, cy + 70f, sh(0xFFE5D3A3.toInt(), day))
        block(c, cx, cy - 10f, 130f, 54f, 0xFFF1E6CF.toInt(), 0xFFB5493A.toInt(), day)
        rect(c, cx - 4f, cy - 36f, cx + 4f, cy + 16f, sh(0xFF8F3326.toInt(), day))
        for (k in 0 until 9) {
            p.color = sh(0xFFFFFFFF.toInt(), day)
            c.drawCircle(cx - 56f + k * 14f, cy + 24f, 3.4f, p)
        }
        flag(c, cx + 80f, cy + 46f, day)
    }

    private fun assembly(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 2090f
        val cy = 1090f
        if (!vis(cx, cy, 160f, l, t, rt, b)) return
        rect(c, cx - 135f, cy - 95f, cx + 135f, cy + 95f, sh(0xFF6FA24F.toInt(), day))
        rect(c, cx + 88f, cy + 6f, cx + 134f, cy + 70f, sh(0xFF8E959B.toInt(), day))
        for (k in 0 until 4) car(c, cx + 100f + (k % 2) * 22f, cy + 18f + (k / 2) * 30f, carCols[(k * 2) % carCols.size], day)
        block(c, cx, cy - 12f, 150f, 62f, 0xFFF6F3EA.toInt(), 0xFFD9D4C6.toInt(), day)
        p.color = sh(0xFF2E8B57.toInt(), day)
        c.drawCircle(cx, cy - 12f, 25f, p)
        p.color = 0x55FFFFFF
        c.drawCircle(cx - 7f, cy - 19f, 9f, p)
        for (k in 0 until 11) {
            p.color = sh(0xFFFFFFFF.toInt(), day)
            c.drawCircle(cx - 66f + k * 13.2f, cy + 24f, 3.2f, p)
        }
        flag(c, cx - 60f, cy + 74f, day)
        flag(c, cx, cy + 74f, day)
        flag(c, cx + 60f, cy + 74f, day)
    }

    private fun ubth(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        val cx = 880f
        val cy = 1280f
        if (!vis(cx, cy, 140f, l, t, rt, b)) return
        rect(c, cx - 105f, cy - 100f, cx + 105f, cy + 100f, sh(0xFFA7B3A0.toInt(), day))
        block(c, cx - 52f, cy, 30f, 92f, 0xFFF4F4F4.toInt(), 0xFFDADFE3.toInt(), day)
        block(c, cx + 52f, cy, 30f, 92f, 0xFFF4F4F4.toInt(), 0xFFDADFE3.toInt(), day)
        block(c, cx, cy, 76f, 28f, 0xFFF4F4F4.toInt(), 0xFFDADFE3.toInt(), day)
        rect(c, cx - 9f, cy - 3f, cx + 9f, cy + 3f, sh(0xFFD7263D.toInt(), day))
        rect(c, cx - 3f, cy - 9f, cx + 3f, cy + 9f, sh(0xFFD7263D.toInt(), day))
        p.color = sh(0xFF8E959B.toInt(), day)
        c.drawCircle(cx, cy - 74f, 21f, p)
        s.strokeWidth = 2.2f
        s.color = 0xFFFFFFFF.toInt()
        c.drawLine(cx - 6f, cy - 82f, cx - 6f, cy - 66f, s)
        c.drawLine(cx + 6f, cy - 82f, cx + 6f, cy - 66f, s)
        c.drawLine(cx - 6f, cy - 74f, cx + 6f, cy - 74f, s)
        for (k in 0 until 2) {
            val ax = cx - 46f + k * 40f
            val ay = cy + 72f
            rect(c, ax - 12f, ay - 6f, ax + 12f, ay + 6f, sh(0xFFFFFFFF.toInt(), day))
            rect(c, ax - 12f, ay - 1.5f, ax + 12f, ay + 1.5f, sh(0xFFD7263D.toInt(), day))
            val on = ((tm * 3f).toInt() + k) % 2 == 0
            p.color = if (on) 0xFFFF3B3B.toInt() else 0xFF3B7BFF.toInt()
            c.drawCircle(ax, ay - 6f, 3f, p)
        }
        if (day < 0.5f) {
            p.color = 0x22FF4040
            c.drawCircle(cx, cy, 120f, p)
        }
    }

    private fun stadium(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 2650f
        val cy = 2050f
        if (!vis(cx, cy, 200f, l, t, rt, b)) return
        p.color = 0x44000000
        r.set(cx - 175f + 6f, cy - 130f + 7f, cx + 175f + 6f, cy + 130f + 7f)
        c.drawOval(r, p)
        p.color = sh(0xFF70767C.toInt(), day)
        r.set(cx - 175f, cy - 130f, cx + 175f, cy + 130f)
        c.drawOval(r, p)
        val rings = intArrayOf(0xFFC2452D.toInt(), 0xFFE0E3E6.toInt(), 0xFF2F6FB0.toInt())
        for (k in 0 until 3) {
            p.color = sh(rings[k], day)
            val f = 1f - k * 0.07f
            r.set(cx - 165f * f, cy - 120f * f, cx + 165f * f, cy + 120f * f)
            c.drawOval(r, p)
        }
        p.color = sh(0xFFB5543A.toInt(), day)
        r.set(cx - 128f, cy - 88f, cx + 128f, cy + 88f)
        c.drawOval(r, p)
        p.color = sh(0xFF3F9B4B.toInt(), day)
        r.set(cx - 104f, cy - 64f, cx + 104f, cy + 64f)
        c.drawRoundRect(r, 24f, 24f, p)
        s.strokeWidth = 2f
        s.color = 0xCCFFFFFF.toInt()
        r.set(cx - 96f, cy - 56f, cx + 96f, cy + 56f)
        c.drawRect(r, s)
        c.drawLine(cx, cy - 56f, cx, cy + 56f, s)
        c.drawCircle(cx, cy, 20f, s)
        for (k in 0 until 4) {
            val tx = cx + (if (k % 2 == 0) -152f else 152f)
            val ty = cy + (if (k < 2) -108f else 108f)
            p.color = 0xFF2B2F33.toInt()
            c.drawCircle(tx, ty, 5f, p)
            if (day < 0.5f) {
                p.color = 0x33FFF3B0
                c.drawCircle(tx, ty, 60f, p)
            }
        }
    }

    private fun secretariat(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 2290f
        val cy = 1925f
        if (!vis(cx, cy, 150f, l, t, rt, b)) return
        rect(c, cx - 110f, cy - 80f, cx + 110f, cy + 90f, sh(0xFFA9B59F.toInt(), day))
        block(c, cx - 66f, cy - 6f, 40f, 96f, 0xFFD6DCE0.toInt(), 0xFF8FA1B0.toInt(), day)
        block(c, cx + 66f, cy - 6f, 40f, 96f, 0xFFD6DCE0.toInt(), 0xFF8FA1B0.toInt(), day)
        block(c, cx, cy - 46f, 100f, 30f, 0xFFD6DCE0.toInt(), 0xFF8FA1B0.toInt(), day)
        flag(c, cx, cy + 20f, day)
        for (k in 0 until 6) car(c, cx - 50f + k * 20f, cy + 70f, carCols[k % carCols.size], day)
    }

    private fun bankRow(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        val cx = 1740f
        val cy = 1880f
        if (!vis(cx, cy, 130f, l, t, rt, b)) return
        val awn = intArrayOf(0xFF2F6FB0.toInt(), 0xFFD7263D.toInt(), 0xFF1E8A4A.toInt(), 0xFFF4A300.toInt())
        for (k in 0 until 4) {
            val bx = cx - 75f + k * 50f
            block(c, bx, cy - 12f, 44f, 54f, 0xFFF1E6CF.toInt(), 0xFF9AA0A6.toInt(), day)
            rect(c, bx - 22f, cy + 15f, bx + 22f, cy + 23f, sh(awn[k], day))
            rect(c, bx - 6f, cy + 28f, bx + 6f, cy + 44f, sh(0xFF2B2F33.toInt(), day))
            val on = day < 0.6f || ((tm * 2f).toInt() + k) % 3 != 0
            rect(c, bx - 4f, cy + 30f, bx + 4f, cy + 36f, if (on) 0xFF7CFFB0.toInt() else 0xFF2E5A44.toInt())
        }
    }

    private fun bureau(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 2400f
        val cy = 1280f
        if (!vis(cx, cy, 170f, l, t, rt, b)) return
        rect(c, cx - 78f, cy - 40f, cx + 78f, cy + 44f, sh(0xFF8F8F86.toInt(), day))
        val umb = intArrayOf(0xFFD7263D.toInt(), 0xFF2F6FB0.toInt(), 0xFFF4A300.toInt(), 0xFF1E8A4A.toInt(), 0xFFE76F51.toInt())
        for (k in 0 until 5) {
            val ux = cx - 56f + k * 28f
            val uy = cy - 12f + (if (k % 2 == 0) -8f else 16f)
            p.color = 0x44000000
            c.drawCircle(ux + 3f, uy + 4f, 13f, p)
            p.color = sh(umb[k], day)
            c.drawCircle(ux, uy, 13f, p)
            p.color = 0x55FFFFFF
            c.drawCircle(ux - 3f, uy - 3f, 4f, p)
            p.color = 0xFF3E2A1C.toInt()
            c.drawCircle(ux + 14f, uy + 7f, 3.2f, p)
        }
        // the mosque next door
        val mx = cx + 112f
        val my = cy - 6f
        block(c, mx, my, 58f, 48f, 0xFFF1E6CF.toInt(), 0xFFE9E1CC.toInt(), day)
        p.color = sh(0xFF1E8A5A.toInt(), day)
        c.drawCircle(mx, my, 15f, p)
        p.color = 0x55FFFFFF
        c.drawCircle(mx - 4f, my - 4f, 5f, p)
        rect(c, mx + 36f, my - 34f, mx + 42f, my - 6f, sh(0xFFF1E6CF.toInt(), day))
        p.color = sh(0xFF1E8A5A.toInt(), day)
        c.drawCircle(mx + 39f, my - 36f, 5f, p)
    }

    private fun nadia(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 650f
        val cy = 1190f
        if (!vis(cx, cy, 90f, l, t, rt, b)) return
        block(c, cx, cy - 8f, 62f, 38f, 0xFFFFE2A8.toInt(), 0xFFD64D2C.toInt(), day)
        for (k in 0 until 8) {
            rect(c, cx - 32f + k * 8f, cy + 12f, cx - 24f + k * 8f, cy + 20f, sh(if (k % 2 == 0) 0xFFFFC857.toInt() else 0xFFFFFFFF.toInt(), day))
        }
        if (day < 0.5f) {
            p.color = 0x44FFD27A
            c.drawCircle(cx, cy + 8f, 54f, p)
        }
        for (k in 0 until 4) {
            p.color = sh(0xFFC9A978.toInt(), day)
            c.drawCircle(cx - 24f + k * 16f, cy + 34f, 5f, p)
        }
    }

    private fun zoo(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 2700f
        val cy = 1200f
        if (!vis(cx, cy, 170f, l, t, rt, b)) return
        rect(c, cx - 150f, cy - 100f, cx + 150f, cy + 100f, sh(0xFF6AAE50.toInt(), day))
        s.strokeWidth = 4f
        s.color = sh(0xFF8A4B2D.toInt(), day)
        r.set(cx - 150f, cy - 100f, cx + 150f, cy + 100f)
        c.drawRect(r, s)
        rect(c, cx - 5f, cy - 100f, cx + 5f, cy + 100f, sh(0xFFE5D3A3.toInt(), day))
        rect(c, cx - 150f, cy - 5f, cx + 150f, cy + 5f, sh(0xFFE5D3A3.toInt(), day))
        p.color = sh(0xFF3D9AD1.toInt(), day)
        r.set(cx - 112f, cy + 14f, cx - 38f, cy + 62f)
        c.drawOval(r, p)
        for (k in 0 until 2) {
            val ex = cx + 62f + k * 26f
            val ey = cy - 48f + k * 10f
            p.color = sh(0xFF8D949B.toInt(), day)
            c.drawCircle(ex, ey, 13f, p)
            c.drawCircle(ex - 11f, ey - 2f, 7f, p)
            c.drawCircle(ex + 11f, ey - 2f, 7f, p)
        }
        for (k in 0 until 3) {
            p.color = sh(0xFFD9A441.toInt(), day)
            c.drawCircle(cx - 74f + k * 14f, cy - 56f + (k % 2) * 10f, 8f, p)
        }
        p.color = sh(0xFFE9C46A.toInt(), day)
        r.set(cx + 62f, cy + 28f, cx + 84f, cy + 70f)
        c.drawRoundRect(r, 6f, 6f, p)
        p.color = sh(0xFF8A5A24.toInt(), day)
        for (k in 0 until 4) c.drawCircle(cx + 68f + (k % 2) * 10f, cy + 36f + k * 8f, 2.8f, p)
        for (k in 0 until 6) {
            p.color = sh(0xFF2F7A3C.toInt(), day)
            c.drawCircle(cx - 124f + k * 50f, cy + 80f, 12f, p)
        }
    }

    private fun newBenin(c: Canvas, l: Float, t: Float, rt: Float, b: Float, day: Float) {
        val cx = 1830f
        val cy = 2150f
        if (!vis(cx, cy, 190f, l, t, rt, b)) return
        rect(c, cx - 150f, cy - 120f, cx + 150f, cy + 120f, sh(0xFFC9A978.toInt(), day))
        s.strokeWidth = 4f
        s.color = sh(0xFF8A4B2D.toInt(), day)
        r.set(cx - 150f, cy - 120f, cx + 150f, cy + 120f)
        c.drawRect(r, s)
        var j = 0
        var y = cy - 100f
        while (y < cy + 100f) {
            var i = 0
            var x = cx - 132f
            while (x < cx + 124f) {
                if (!(i % 4 == 3)) {
                    rect(c, x + 2f, y + 3f, x + 24f, y + 19f, 0x44000000)
                    rect(c, x, y, x + 22f, y + 16f, sh(tarps[(i * 7 + j * 13) % tarps.size], day))
                }
                x += 30f
                i++
            }
            y += 34f
            j++
        }
    }

    private fun kada(c: Canvas, l: Float, t: Float, rt: Float, b: Float, tm: Float, day: Float) {
        val cx = 1690f
        val cy = 800f
        if (!vis(cx, cy, 120f, l, t, rt, b)) return
        block(c, cx, cy, 76f, 62f, 0xFF2B3A4A.toInt(), 0xFF3C5870.toInt(), day)
        s.strokeWidth = 3f
        s.color = if (day < 0.6f) 0xFFFF4FD8.toInt() else sh(0xFFB84BA0.toInt(), day)
        c.drawCircle(cx, cy, 18f, s)
        for (k in 0 until 14) {
            val a = k * 0.4488f
            val on = ((tm * 4f).toInt() + k) % 3 == 0
            p.color = if (on) 0xFFFFE066.toInt() else 0xFF8B7B30.toInt()
            c.drawCircle(cx + cos(a.toDouble()).toFloat() * 34f, cy + sin(a.toDouble()).toFloat() * 28f, 2.2f, p)
        }
        if (day < 0.5f) {
            val pulse = 0.6f + 0.4f * sin(tm * 3f)
            p.color = ((pulse * 40f).toInt() shl 24) or 0xFF3FD0
            c.drawCircle(cx, cy, 90f, p)
        }
        for (k in 0 until 5) car(c, cx - 36f + k * 18f, cy + 50f, carCols[(k + 2) % carCols.size], day)
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float, x: Float, y: Float): Float = hypot(a - x, b - y)
}
