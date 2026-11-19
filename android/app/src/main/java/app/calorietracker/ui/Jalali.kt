package app.calorietracker.ui

import java.time.LocalDate

/**
 * Jalali (Hijri Shamsi) <-> Gregorian conversion.
 * Port of static/jalali.js: algorithm from jalaali-js (MIT, Behrang Noruzi Niya), Borkowski's breaks table.
 * Integer division truncates toward zero in Kotlin, just like `~~(a / b)` in the JavaScript original.
 */
object Jalali {
    data class JDate(val y: Int, val m: Int, val d: Int)

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635,
        2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    private fun div(a: Int, b: Int) = a / b
    private fun mod(a: Int, b: Int) = a - (a / b) * b

    private class Cal(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int, withoutLeap: Boolean): Cal {
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (withoutLeap) return Cal(0, gy, march)
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return Cal(leap, gy, march)
    }

    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        val d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408
        return d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
    }

    private fun d2g(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return Triple(gy, gm, gd)
    }

    private fun j2d(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy, true)
        return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
    }

    private fun d2j(jdn: Int): JDate {
        val gy = d2g(jdn).first
        var jy = gy - 621
        val r = jalCal(jy, false)
        var k = jdn - g2d(gy, 3, r.march)
        if (k >= 0) {
            if (k <= 185) return JDate(jy, 1 + div(k, 31), mod(k, 31) + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return JDate(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    fun fromDate(d: LocalDate): JDate = d2j(g2d(d.year, d.monthValue, d.dayOfMonth))

    fun toDate(jy: Int, jm: Int, jd: Int): LocalDate {
        val (y, m, d) = d2g(j2d(jy, jm, jd))
        return LocalDate.of(y, m, d)
    }
}
