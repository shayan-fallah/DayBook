package app.calorietracker.ui

import androidx.compose.ui.graphics.Color
import app.calorietracker.data.TimeEntry
import app.calorietracker.data.WorkPrefs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Times from the server are Unix seconds; days, weeks and months are in the phone's timezone.

val PROJECT_COLORS = listOf("#2a78d6", "#eb6834", "#1baf7a", "#8b6cf0", "#e0508f", "#e8a317", "#14a3b8", "#d03b3b")
const val NO_PROJECT_COLOR = "#9a9890"
val PRIORITY_NAMES = listOf("None", "Low", "Medium", "High")

private val JM_FA = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
private val WD_FA = mapOf(
    DayOfWeek.SUNDAY to "یکشنبه", DayOfWeek.MONDAY to "دوشنبه", DayOfWeek.TUESDAY to "سه‌شنبه",
    DayOfWeek.WEDNESDAY to "چهارشنبه", DayOfWeek.THURSDAY to "پنجشنبه", DayOfWeek.FRIDAY to "جمعه", DayOfWeek.SATURDAY to "شنبه",
)
private val WD_FA_SHORT = mapOf(
    DayOfWeek.SUNDAY to "ی", DayOfWeek.MONDAY to "د", DayOfWeek.TUESDAY to "س", DayOfWeek.WEDNESDAY to "چ",
    DayOfWeek.THURSDAY to "پ", DayOfWeek.FRIDAY to "ج", DayOfWeek.SATURDAY to "ش",
)
private val longFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val dowShort = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val dowLong = DateTimeFormatter.ofPattern("EEEE", Locale.US)

fun zone(): ZoneId = ZoneId.systemDefault()
fun dayStart(d: LocalDate): Long = d.atStartOfDay(zone()).toEpochSecond()
fun dateOfTs(ts: Long): LocalDate = Instant.ofEpochSecond(ts).atZone(zone()).toLocalDate()
fun timeOfTs(ts: Long): LocalTime = Instant.ofEpochSecond(ts).atZone(zone()).toLocalTime()
fun tsOf(d: LocalDate, t: LocalTime): Long = d.atTime(t).atZone(zone()).toEpochSecond()
fun hm(ts: Long): String = timeOfTs(ts).let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }
fun hm(t: LocalTime): String = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

/** 3725 -> "1:02:05" */
fun clock(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/** 5400 -> "1 h 30 min" */
fun hrs(sec: Long): String {
    val m = Math.round(sec / 60.0)
    if (m < 60) return "$m min"
    return if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"
}

fun hoursDec(sec: Long) = "${fmt1(sec / 3600.0)} h"

fun TimeEntry.endOr(now: Long) = endTs ?: now
fun overlap(start: Long, end: Long, a: Long, b: Long): Long = (minOf(end, b) - maxOf(start, a)).coerceAtLeast(0)
fun TimeEntry.overlap(a: Long, b: Long, now: Long) = overlap(startTs, endOr(now), a, b)

fun hexColor(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex ?: NO_PROJECT_COLOR)) }
    .getOrDefault(Color(0xFF9A9890))

fun faDigits(s: String) = s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

// ---------------------------------------------------------------- calendar-aware helpers

fun WorkPrefs.jalali() = calendar == "jalali"

fun WorkPrefs.weekStartDay(): DayOfWeek = when (weekStart) {
    "sat" -> DayOfWeek.SATURDAY
    "sun" -> DayOfWeek.SUNDAY
    "mon" -> DayOfWeek.MONDAY
    else -> if (jalali()) DayOfWeek.SATURDAY else DayOfWeek.MONDAY
}

fun WorkPrefs.startOfWeek(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(weekStartDay()))

fun WorkPrefs.monthStart(d: LocalDate): LocalDate {
    if (!jalali()) return d.withDayOfMonth(1)
    val j = Jalali.fromDate(d)
    return Jalali.toDate(j.y, j.m, 1)
}

fun WorkPrefs.addMonths(d: LocalDate, k: Int): LocalDate {
    if (!jalali()) return d.withDayOfMonth(1).plusMonths(k.toLong())
    val j = Jalali.fromDate(d)
    val i = j.y * 12 + (j.m - 1) + k
    return Jalali.toDate(Math.floorDiv(i, 12), Math.floorMod(i, 12) + 1, 1)
}

fun WorkPrefs.yearStart(d: LocalDate): LocalDate =
    if (jalali()) Jalali.toDate(Jalali.fromDate(d).y, 1, 1) else d.withDayOfYear(1)

/** "Wed, Nov 18", or with Shamsi dates "چهارشنبه ۲۷ آبان · Nov 18". */
fun WorkPrefs.dayTitle(d: LocalDate): String {
    if (!jalali()) return d.format(longFmt)
    val j = Jalali.fromDate(d)
    return "${isolate("${WD_FA[d.dayOfWeek]} ${faDigits(j.d.toString())} ${JM_FA[j.m - 1]}")} · ${d.format(shortFmt)}"
}

fun WorkPrefs.relDay(d: LocalDate): String {
    val rel = when (ChronoUnit.DAYS.between(LocalDate.now(), d)) {
        0L -> "Today"
        -1L -> "Yesterday"
        1L -> "Tomorrow"
        else -> return dayTitle(d)
    }
    return if (jalali()) "$rel · ${shortDay(d)}" else "$rel, ${d.format(shortFmt)}"
}

fun WorkPrefs.shortDay(d: LocalDate): String {
    if (!jalali()) return d.format(shortFmt)
    val j = Jalali.fromDate(d)
    return isolate("${faDigits(j.d.toString())} ${JM_FA[j.m - 1]}")
}

fun WorkPrefs.monthTitle(d: LocalDate): String {
    if (!jalali()) return d.format(monthFmt)
    val j = Jalali.fromDate(d)
    return isolate("${JM_FA[j.m - 1]} ${faDigits(j.y.toString())}")
}

fun WorkPrefs.monthShort(d: LocalDate): String =
    if (!jalali()) d.format(DateTimeFormatter.ofPattern("MMM", Locale.US)) else JM_FA[Jalali.fromDate(d).m - 1].take(3)

fun WorkPrefs.yearTitle(d: LocalDate): String =
    if (jalali()) faDigits(Jalali.fromDate(d).y.toString()) else d.year.toString()

fun WorkPrefs.dayNum(d: LocalDate): String =
    if (jalali()) faDigits(Jalali.fromDate(d).d.toString()) else d.dayOfMonth.toString()

fun WorkPrefs.weekdayShort(d: LocalDate): String = if (jalali()) WD_FA_SHORT.getValue(d.dayOfWeek) else d.format(dowShort)

/** "Today", "Tomorrow", "3 days late", "Friday" or a date. */
fun WorkPrefs.dueText(due: String): String {
    val d = LocalDate.parse(due)
    val diff = ChronoUnit.DAYS.between(LocalDate.now(), d)
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff < 0 -> "${-diff} days late"
        diff < 7 -> if (jalali()) WD_FA.getValue(d.dayOfWeek) else d.format(dowLong)
        else -> shortDay(d)
    }
}

/** Work days skip Friday with Shamsi dates, and the weekend otherwise. */
fun WorkPrefs.isWorkDay(d: LocalDate): Boolean =
    if (jalali()) d.dayOfWeek != DayOfWeek.FRIDAY else d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
