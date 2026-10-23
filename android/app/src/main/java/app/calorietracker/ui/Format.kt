package app.calorietracker.ui

import app.calorietracker.data.Entry
import app.calorietracker.data.Food
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

const val GRAM = "گرم"

val MEALS = listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snacks")
fun mealLabel(key: String) = MEALS.firstOrNull { it.first == key }?.second ?: key

fun mealForNow(): String {
    val t = LocalTime.now()
    val h = t.hour + t.minute / 60.0
    return when {
        h < 10.5 -> "breakfast"
        h < 15 -> "lunch"
        h < 18 -> "snack"
        h < 22.5 -> "dinner"
        else -> "snack"
    }
}

fun fmt(v: Double?): String =
    if (v == null || v.isNaN()) "–" else String.format(Locale.US, "%,d", v.roundToLong())

fun fmt1(v: Double?): String {
    if (v == null || v.isNaN()) return "–"
    val r = Math.round(v * 10) / 10.0
    return if (r == floor(r)) String.format(Locale.US, "%,d", r.toLong()) else String.format(Locale.US, "%,.1f", r)
}

/** Parse a typed number: Persian/Arabic digits, "," or "٫" decimals, and fractions like 1/2. */
fun parseNum(input: String): Double? {
    val s = buildString {
        for (c in input.trim()) {
            when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                ',', '٫' -> append('.')
                ' ' -> {}
                else -> append(c)
            }
        }
    }
    if (s.isEmpty()) return null
    val frac = Regex("""^(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)$""").matchEntire(s)
    if (frac != null) {
        val b = frac.groupValues[2].toDouble()
        return if (b == 0.0) null else frac.groupValues[1].toDouble() / b
    }
    return s.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** Wrap Persian text in Unicode isolates so it doesn't scramble the English text around it. */
fun isolate(s: String) = "⁨$s⁩"
fun unitText(name: String?) = if (name == null || name == GRAM) "g" else isolate(name)

fun portionText(e: Entry): String {
    if (e.foodId == null) return "Quick add"
    val base = "${fmt1(e.qty)} ${unitText(e.unit)}"
    return if (e.unit != GRAM && e.grams != null && e.grams > 0) "$base · ${fmt(e.grams)} g" else base
}

fun foodSub(f: Food): String {
    val last = f.last
    if (last != null) return "${fmt1(last.qty)} ${unitText(last.unit)} · ${fmt(last.kcal)} kcal"
    val u = f.unit1
    val unitPart = if (u != null && u.name != GRAM) " · 1 ${isolate(u.name)} = ${fmt(u.grams)} g" else ""
    return "${fmt(f.kcal)} kcal / 100 g$unitPart"
}

private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val longFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val dowFmt = DateTimeFormatter.ofPattern("EEE", Locale.US)

fun shortDate(iso: String): String = LocalDate.parse(iso).format(shortFmt)
fun longDate(iso: String): String = LocalDate.parse(iso).format(longFmt)
fun weekday(iso: String): String = LocalDate.parse(iso).format(dowFmt)

fun dateLabel(d: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(LocalDate.now(), d)
    val rel = when (diff) {
        0L -> "Today"
        -1L -> "Yesterday"
        1L -> "Tomorrow"
        else -> null
    }
    return if (rel != null) "$rel, ${d.format(shortFmt)}" else d.format(longFmt)
}

fun Double.trimmed(): String = fmt1(this).replace(",", "")

// ---------------------------------------------------------------- workouts

val INTENSITY_NAME = mapOf("light" to "Easy", "moderate" to "Moderate", "hard" to "Hard")
val INTENSITY_INDEX = mapOf("light" to 0, "moderate" to 1, "hard" to 2)
val MUSCLE_NAME = mapOf(
    "chest" to "Chest", "back" to "Back", "shoulders" to "Shoulders", "biceps" to "Biceps", "triceps" to "Triceps",
    "legs" to "Legs", "abs" to "Abs", "full_body" to "Full body",
)
val DURATIONS = listOf(30, 45, 60, 75, 90, 120)

/** 95 -> "1 h 35 min". */
fun duration(min: Int?): String {
    if (min == null) return ""
    val h = min / 60
    val m = min % 60
    return if (h == 0) "$m min" else if (m == 0) "$h h" else "$h h $m min"
}

fun hoursText(min: Int) = "${fmt1(min / 60.0)} h"

fun agoText(days: Int?) = when (days) {
    null -> "Not yet"
    0 -> "Today"
    1 -> "Yesterday"
    else -> "$days days ago"
}

fun workoutSub(w: app.calorietracker.data.Workout, withDate: Boolean = false): String {
    val parts = mutableListOf<String>()
    if (withDate) parts += longDate(w.date)
    if (w.minutes != null) {
        parts += duration(w.minutes)
        parts += INTENSITY_NAME[w.intensity] ?: w.intensity
    }
    if (w.muscles.isNotEmpty()) parts += w.muscles.joinToString(", ") { MUSCLE_NAME[it] ?: it }
    val km = w.distanceKm
    if (km != null && km > 0) {
        var d = "${fmt1(km)} km"
        if (w.activity == "running" && w.minutes != null) {
            val pace = w.minutes / km
            val sec = Math.round((pace - floor(pace)) * 60).toInt() % 60
            d += String.format(Locale.US, " (%d:%02d /km)", floor(pace).toInt(), sec)
        }
        parts += d
    }
    if (w.minutes == null) parts += "Calories only"
    if (w.notes.isNotBlank()) parts += w.notes
    return parts.joinToString(" · ")
}
