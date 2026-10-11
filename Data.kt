package com.nagi.hustle

import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object Colors {
    val INK = 0xFF101418.toInt()
    val PAPER = 0xFFF6F5F2.toInt()
    val ACCENT = 0xFFFF5B2E.toInt()
    val CARD = 0xFF1A2027.toInt()
    val CARD2 = 0xFF242C35.toInt()
    val MUTED = 0xFF9AA4AF.toInt()
    val GREEN = 0xFF3DDC84.toInt()
    val RED = 0xFFFF5252.toInt()
    val GOLD = 0xFFFFC857.toInt()
    val BLUE = 0xFF4DA3FF.toInt()
    val PURPLE = 0xFFA663CC.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun naira(v: Long): String = "₦" + String.format("%,d", v)

// ---------- the world ----------

const val WORLD_W = 3300f
const val WORLD_H = 3000f
const val KX = 1600f
const val KY = 1500f

/** The old city moat is not a perfect circle. This gives its distance from the centre at an angle. */
fun moatR(th: Double): Double = 1150.0 + 90.0 * sin(3.0 * th) + 50.0 * cos(5.0 * th)

fun moatX(deg: Double): Float {
    val th = deg * PI / 180.0
    return (KX + moatR(th) * cos(th)).toFloat()
}

fun moatY(deg: Double): Float {
    val th = deg * PI / 180.0
    return (KY + moatR(th) * sin(th)).toFloat()
}

// ---------- the places you can use ----------

const val P_OBA = 0
const val P_KINGS = 1
const val P_PALACE = 2
const val P_EMOTAN = 3
const val P_MUSEUM = 4
const val P_IGUN = 5
const val P_EKEOSA = 6
const val P_IKPOBA = 7
const val P_UNIBEN = 8
const val P_EKOSODIN = 9
const val P_GRA = 10
const val P_LODGE = 11
const val P_AIRPORT = 12
const val P_MOAT = 13
const val P_OLOKUN = 14
const val P_URHOKPOTA = 15
const val P_ASSEMBLY = 16
const val P_UBTH = 17
const val P_STADIUM = 18
const val P_SECRETARIAT = 19
const val P_BANKROW = 20
const val P_BDC = 21
const val P_NADIA = 22
const val P_ZOO = 23
const val P_NEWBENIN = 24
const val P_KADA = 25

/** The five power zones of the city. NEPA cuts them one at a time. */
const val ZONES = 5
val ZONE_NAMES = listOf("the city centre", "the Ugbowo side", "the Airport and south side", "the Oba Market side", "the GRA side")

/**
 * A spot on the map where you can do things. r is how close you must stand.
 * goods is the base price of one bag of goods there (0 means they do not trade).
 * oh and ch are opening and closing hours. tout means junction boys collect "tickets" there.
 */
class Place(
    val id: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val r: Float,
    val job: String,
    val pay: Int,
    val energy: Int,
    val food: Int,
    val goods: Int,
    val acts: List<String>,
    val zone: Int = 0,
    val oh: Int = 0,
    val ch: Int = 24,
    val tout: Boolean = false
)

val PLACES = listOf(
    Place(P_OBA, "Oba Market", 2130f, 1380f, 210f, "Market assistant", 4800, 22, 700, 700, listOf("work", "eat", "buy", "sell", "pos", "atm", "bus", "keke"), 3, 6, 21, true),
    Place(P_KINGS, "King's Square (Ring Road)", 1600f, 1500f, 260f, "Shop attendant", 6500, 24, 1300, 1100, listOf("work", "eat", "buy", "sell", "pos", "bank", "bus", "keke"), 0, 7, 22, true),
    Place(P_PALACE, "Oba's Palace", 1300f, 1230f, 190f, "Palace tour guide", 6200, 22, 0, 0, listOf("work", "keke"), 0, 9, 18),
    Place(P_EMOTAN, "Emotan Statue", 2130f, 1650f, 120f, "", 0, 0, 0, 0, listOf("visit", "keke"), 3),
    Place(P_MUSEUM, "National Museum", 1600f, 1500f, 90f, "", 0, 0, 0, 0, listOf("visit", "keke"), 0, 9, 17),
    Place(P_IGUN, "Igun Street", 1320f, 1900f, 160f, "Bronze caster's apprentice", 7600, 26, 900, 1500, listOf("work", "eat", "buy", "sell", "keke"), 0, 8, 19),
    Place(P_EKEOSA, "Ekeosa Market", 1380f, 2200f, 180f, "Trader's assistant", 5000, 22, 800, 650, listOf("work", "eat", "buy", "sell", "pos", "keke"), 2, 6, 20),
    Place(P_IKPOBA, "Ikpoba Hill", 2450f, 2650f, 230f, "Keke conductor", 8000, 26, 1100, 1000, listOf("work", "eat", "buy", "sell", "keke"), 2, 5, 22),
    Place(P_UNIBEN, "University of Benin", 300f, 1100f, 280f, "Campus tutor", 7000, 24, 1000, 1300, listOf("work", "eat", "study", "buy", "sell", "atm", "pos", "bus", "keke"), 1, 6, 22, true),
    Place(P_EKOSODIN, "Ekosodin Hostels", 450f, 1480f, 160f, "", 0, 0, 900, 0, listOf("eat", "rest", "pos", "atm", "keke"), 1),
    Place(P_GRA, "GRA (Ihama and Etete)", 1480f, 640f, 270f, "Office clerk", 10500, 28, 2800, 1800, listOf("work", "eat", "buy", "sell", "pos", "keke"), 4, 8, 22),
    Place(P_LODGE, "Akpakpava Lodge", 1790f, 1050f, 130f, "", 0, 0, 0, 0, listOf("rest", "keke"), 0),
    Place(P_AIRPORT, "Benin Airport", 2650f, 270f, 240f, "Airport porter", 9000, 28, 2500, 2000, listOf("work", "eat", "buy", "sell", "atm", "pos", "bus", "keke"), 2),
    Place(P_MOAT, "Benin Moat", moatX(40.0), moatY(40.0), 130f, "", 0, 0, 0, 0, listOf("visit", "keke"), 2),
    Place(P_OLOKUN, "Olokun Sculpture", 1600f, 1320f, 60f, "", 0, 0, 0, 0, listOf("visit", "keke"), 0),
    Place(P_URHOKPOTA, "Urhokpota Hall", 1900f, 1260f, 110f, "Event usher", 5600, 20, 0, 0, listOf("work", "visit", "keke"), 0, 9, 22),
    Place(P_ASSEMBLY, "Edo House of Assembly", 2090f, 1090f, 150f, "Assembly clerk", 9500, 26, 0, 0, listOf("work", "visit", "bus", "keke"), 0, 8, 17),
    Place(P_UBTH, "UBTH (Teaching Hospital)", 880f, 1280f, 150f, "Hospital orderly", 7800, 26, 900, 0, listOf("work", "eat", "treat", "donate", "atm", "keke"), 1),
    Place(P_STADIUM, "Samuel Ogbemudia Stadium", 2650f, 2050f, 180f, "Stadium steward", 6800, 24, 1000, 1200, listOf("work", "eat", "buy", "sell", "bus", "keke"), 2, 8, 23, true),
    Place(P_SECRETARIAT, "Secretariat Complex", 2290f, 1925f, 140f, "Civil service clerk", 8800, 22, 800, 0, listOf("work", "eat", "bank", "pos", "bus", "keke"), 0, 8, 17, true),
    Place(P_BANKROW, "Bank Row (Sapele Road)", 1740f, 1880f, 120f, "", 0, 0, 0, 0, listOf("bank", "atm", "pos", "keke"), 0),
    Place(P_BDC, "Bureau de Change", 2400f, 1280f, 90f, "", 0, 0, 0, 0, listOf("forex", "keke"), 3),
    Place(P_NADIA, "Nadia Bakery", 650f, 1190f, 85f, "Bakery counter", 4200, 18, 450, 0, listOf("work", "eat", "keke"), 1),
    Place(P_ZOO, "Ogba Zoo", 2700f, 1200f, 170f, "Zoo attendant", 5200, 20, 800, 0, listOf("work", "eat", "visit", "keke"), 3, 9, 18),
    Place(P_NEWBENIN, "New Benin Market", 1830f, 2150f, 190f, "Stall hand", 4400, 22, 650, 600, listOf("work", "eat", "buy", "sell", "pos", "atm", "bus", "keke"), 2, 6, 21, true),
    Place(P_KADA, "Kada Plaza", 1690f, 800f, 100f, "Rooftop waiter", 8200, 22, 2200, 0, listOf("work", "eat", "visit", "keke"), 4, 11, 24)
)

const val STUDY_PLACE = P_UNIBEN
const val STUDY_COST = 1500L

fun isOpenAt(pl: Place, hour: Double): Boolean = hour >= pl.oh && hour < pl.ch

fun hourText(h: Int): String = String.format("%d:00", h)

/** Which of the five power zones this spot is in: the zone of the nearest place. */
fun zoneAt(x: Float, y: Float): Int {
    var best = 0
    var bd = 1e9f
    for (pl in PLACES) {
        val d = hypot(x - pl.x, y - pl.y)
        if (d < bd) {
            bd = d
            best = pl.zone
        }
    }
    return best
}

/** The place whose circle you are standing in (the deepest one), or -1. */
fun placeAt(x: Float, y: Float): Int {
    var best = -1
    var bestD = 1e9f
    for (pl in PLACES) {
        val dd = hypot(x - pl.x, y - pl.y) - pl.r
        if (dd < 0f && dd < bestD) {
            bestD = dd
            best = pl.id
        }
    }
    return best
}

// ---------- who you can be ----------

const val C_STUDENT = 0
const val C_HUSTLER = 1
const val C_JAPA = 2
const val C_TRADER = 3
const val C_TICKET = 4
const val C_POSOP = 5
const val C_DRIVER = 6
const val C_CIVIL = 7
const val C_FLASH = 8

class HClass(
    val id: Int,
    val name: String,
    val tag: String,
    val perk: String,
    val cash: Long,
    val rent: Long,
    val place: Int,
    val usd: Int,
    val acct: Boolean
)

val CLASSES = listOf(
    HClass(C_STUDENT, "The Student", "UNIBEN kid. Broke, hungry, clever.", "Learns faster, cheap food at campus spots, pays low rent.", 5000L, 25000L, P_UNIBEN, 0, false),
    HClass(C_HUSTLER, "The Hustler", "Knows every route and every junction boy.", "Junction boys charge you half. Keke fares are cheaper.", 15000L, 40000L, P_KINGS, 0, false),
    HClass(C_JAPA, "The Japa Returnee", "Just landed from abroad with dollars.", "Starts with \$30. Never gets cheated at the Bureau de Change.", 3000L, 55000L, P_AIRPORT, 30, false),
    HClass(C_TRADER, "The Market Trader", "A stall at Oba Market and a loud voice.", "Better buying and selling prices. Earns more in markets.", 10000L, 40000L, P_OBA, 0, false),
    HClass(C_TICKET, "The Junction Boy", "You collect the 'tickets' at the junctions.", "Nobody taxes you. You can collect from the crowd.", 2000L, 30000L, P_KINGS, 0, false),
    HClass(C_POSOP, "The POS Operator", "Table, umbrella, machine, cash box.", "Free withdrawals. You can run a POS stand and earn fees.", 22000L, 45000L, P_UNIBEN, 0, false),
    HClass(C_DRIVER, "The Keke Driver", "Yellow keke, full tank, long queue.", "Free keke rides. You can carry passengers for money.", 20000L, 40000L, P_KINGS, 0, false),
    HClass(C_CIVIL, "The Civil Servant", "Steady salary and office connections.", "Daily salary into your bank. Earns more at government offices.", 25000L, 50000L, P_SECRETARIAT, 0, true),
    HClass(C_FLASH, "The Flash Boy", "GRA, fine cars, quick money, big risk.", "Risky online gigs can pay big, or bring police wahala.", 10000L, 70000L, P_KADA, 0, false)
)

fun extraActs(cls: Int, pl: Place): List<String> {
    val l = ArrayList<String>()
    when (cls) {
        C_DRIVER -> if (pl.tout || pl.id == P_AIRPORT) l.add("drive")
        C_TICKET -> if (pl.tout) l.add("collect")
        C_POSOP -> if (pl.acts.contains("pos")) l.add("stand")
        C_FLASH -> if (pl.id == P_GRA || pl.id == P_KADA || pl.id == P_KINGS) l.add("gig")
    }
    return l
}

// ---------- where you live ----------

class Home(val name: String, val rent: Long, val desc: String, val rob: Double)

val HOUSING = listOf(
    Home("Shared hostel (UNIBEN)", 5000L, "Cheap and social. Crowded and noisy.", 1.0),
    Home("Ekosodin room", 15000L, "Student area, parties, cheap food. Rowdy at night.", 1.1),
    Home("Self-contained (Ugbowo)", 25000L, "Privacy and your own space. Lonely.", 0.9),
    Home("Flat in GRA", 100000L, "Secure, gated, steady light. Respect, but very expensive.", 0.6),
    Home("Squatting at a friend's place", 0L, "No rent, but tension and eviction risk.", 1.0),
    Home("The street", 0L, "No rent. Dangerous, tiring and exposed.", 1.7)
)

fun homeOf(cls: Int): Int = when (cls) {
    C_STUDENT -> 0
    C_HUSTLER -> 1
    C_JAPA -> 2
    C_TRADER -> 1
    C_TICKET -> 4
    C_POSOP -> 1
    C_DRIVER -> 1
    C_CIVIL -> 2
    else -> 3
}

/** Your level in life, from your total worth. */
fun levelOf(w: Long): Int = if (w < 150000L) 1 else if (w < 600000L) 2 else if (w < 2500000L) 3 else if (w < 10000000L) 4 else 5

val LEVEL_NAMES = listOf("", "Survival", "Hustle", "Business", "Empire", "Big Boy")

/** A city-wide challenge that affects every player for a few days. */
class CityEvent(
    val title: String,
    val text: String,
    val days: Int,
    val pay: DoubleArray,
    val travel: Double = 1.0,
    val food: Double = 1.0,
    val travelTime: Double = 1.0,
    val blocked: Int = -1,
    val rain: Boolean = false,
    val dark: Boolean = false,
    val goods: Double = 1.0,
    val fuel: Boolean = false,
    val fx: Double = 1.0
)

private fun mult(vararg m: Pair<Int, Double>): DoubleArray {
    val a = DoubleArray(PLACES.size) { 1.0 }
    for (pr in m) a[pr.first] = pr.second
    return a
}

private fun all(v: Double): DoubleArray = DoubleArray(PLACES.size) { v }

val EVENTS = listOf(
    CityEvent(
        title = "Fuel scarcity",
        text = "Long queues at the filling stations. Keke fares double, buses thin out, NEPA cuts more often, POS charges jump and the dollar rises.",
        days = 3, pay = mult(), travel = 2.0, food = 1.15, travelTime = 1.2, fuel = true, fx = 1.06
    ),
    CityEvent(
        title = "Flood on Sapele Road",
        text = "Water over the road. You cannot work at Ikpoba Hill until it clears.",
        days = 2, pay = mult(), travel = 1.3, travelTime = 1.4, blocked = P_IKPOBA, rain = true
    ),
    CityEvent(
        title = "Ring Road gridlock",
        text = "The roundabout is jammed from every side. Every trip takes much longer.",
        days = 2, pay = mult(), travelTime = 2.2
    ),
    CityEvent(
        title = "ASUU strike",
        text = "Lectures have stopped. Pay at UNIBEN drops hard and you cannot study.",
        days = 4, pay = mult(P_UNIBEN to 0.4)
    ),
    CityEvent(
        title = "Exam week at UNIBEN",
        text = "Students are desperate for tutors. Pay at UNIBEN is up by half.",
        days = 3, pay = mult(P_UNIBEN to 1.5)
    ),
    CityEvent(
        title = "Igue festival",
        text = "Crowds fill the city for the Oba's festival. Oba Market, Ring Road, the Palace and Igun Street pay more.",
        days = 3, pay = mult(P_OBA to 1.6, P_KINGS to 1.3, P_PALACE to 1.5, P_IGUN to 1.3), food = 1.1
    ),
    CityEvent(
        title = "Light don go",
        text = "Total blackout across the city. No POS, no banks, no ATMs. Everybody is in the dark.",
        days = 2, pay = all(0.85), dark = true
    ),
    CityEvent(
        title = "Heavy rain",
        text = "Wet, muddy roads. Keke fares rise and trips take longer.",
        days = 2, pay = mult(), travel = 1.3, travelTime = 1.3, rain = true
    ),
    CityEvent(
        title = "Public holiday",
        text = "Offices are closed. Pay in GRA is halved and the shops dip a little.",
        days = 2, pay = mult(P_GRA to 0.5, P_KINGS to 0.85, P_OBA to 0.9, P_SECRETARIAT to 0.5, P_ASSEMBLY to 0.5), travelTime = 0.9
    ),
    CityEvent(
        title = "Goods glut",
        text = "Trucks have brought too much produce. Goods prices fall by a quarter.",
        days = 3, pay = mult(), goods = 0.75
    ),
    CityEvent(
        title = "Goods shortage",
        text = "Roads from the villages are bad. Goods prices rise by almost a third.",
        days = 3, pay = mult(), goods = 1.3
    ),
    CityEvent(
        title = "Naira falls",
        text = "The naira is sliding. Dollar rates jump and food costs more.",
        days = 4, pay = mult(), food = 1.1, fx = 1.15
    ),
    CityEvent(
        title = "Match day at the Stadium",
        text = "Big match at the Samuel Ogbemudia Stadium. Crowds, noise and good money around the stadium.",
        days = 2, pay = mult(P_STADIUM to 1.9, P_KINGS to 1.1, P_NEWBENIN to 1.15)
    )
)
