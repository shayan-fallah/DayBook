package app.calorietracker

import app.calorietracker.data.Api
import app.calorietracker.data.Day
import app.calorietracker.data.Food
import app.calorietracker.data.Me
import app.calorietracker.data.Profile
import app.calorietracker.data.QuickLists
import app.calorietracker.data.SearchReply
import app.calorietracker.data.Stats
import app.calorietracker.data.WorkoutMeta
import app.calorietracker.data.WorkoutStats
import app.calorietracker.data.jsonBody
import app.calorietracker.data.normalizeServerUrl
import app.calorietracker.ui.duration
import app.calorietracker.ui.isolate
import app.calorietracker.ui.parseNum
import app.calorietracker.ui.portionText
import app.calorietracker.ui.workoutSub
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes real responses captured from app.py, so the Kotlin models can't drift from the server. */
class ApiParsingTest {
    private val json = Api("", null).json
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun me() {
        val me = json.decodeFromString<Me>(fixture("me.json"))
        assertEquals("shayan", me.user)
    }

    @Test fun day() {
        val d = json.decodeFromString<Day>(fixture("day.json"))
        assertTrue(d.entries.isNotEmpty())
        val e = d.entries.first { it.foodId != null }
        assertNotNull(e.unit)
        assertTrue(e.grams!! > 0)
        assertTrue(d.totals.kcal > 0)
        assertEquals(1900.0, d.targets.kcal, 0.01)
        assertNotNull(d.targets.suggested)
        assertNotNull(d.lastWeight)
        assertTrue(d.entries.any { it.foodId == null }) // the quick-add latte
    }

    @Test fun quickAndSearch() {
        val q = json.decodeFromString<QuickLists>(fixture("quick.json"))
        assertTrue(q.recent.isNotEmpty())
        assertNotNull(q.recent.first().last)
        val s = json.decodeFromString<SearchReply>(fixture("search.json"))
        assertTrue(s.results.isNotEmpty())
        assertTrue(s.results.any { it.unit1 != null })
    }

    @Test fun food() {
        val f = json.decodeFromString<Food>(fixture("food.json"))
        assertTrue(f.units.isNotEmpty())
        assertTrue(f.units.any { it.name == "گرم" && it.grams == 1.0 })
        assertTrue(f.kcal > 0)
    }

    @Test fun stats() {
        val s = json.decodeFromString<Stats>(fixture("stats.json"))
        assertEquals(30, s.days.size)
        assertTrue(s.loggedDays > 0)
        assertTrue(s.topFoods.isNotEmpty())
        assertTrue(s.weights.isNotEmpty())
        assertEquals(76.0, s.targetWeight!!, 0.01)
        assertEquals(-0.5, s.rateKgWeek!!, 0.01)
        assertTrue(s.meals.containsKey("lunch"))
        assertNotNull(s.avg.kcal)
    }

    @Test fun profile() {
        val p = json.decodeFromString<Profile>(fixture("profile.json"))
        assertEquals("male", p.sex)
        assertEquals(178.0, p.heightCm!!, 0.01)
        assertEquals(-0.5, p.rateKgWeek!!, 0.01)
        assertNull(p.calorieGoal)
        assertEquals(8, p.waterGoal)
    }

    @Test fun numbers() {
        assertEquals(2.0, parseNum("۲")!!, 0.0)
        assertEquals(1.5, parseNum("1٫5")!!, 0.0)
        assertEquals(1.5, parseNum("1,5")!!, 0.0)
        assertEquals(0.5, parseNum("1/2")!!, 0.0)
        assertNull(parseNum(""))
        assertNull(parseNum("abc"))
        assertNull(parseNum("1/0"))
    }

    @Test fun serverUrls() {
        assertEquals("http://1.2.3.4:8080", normalizeServerUrl(" 1.2.3.4:8080/ "))
        assertEquals("https://calories.example.com", normalizeServerUrl("calories.example.com"))
        assertEquals("http://myhost:8080", normalizeServerUrl("myhost:8080"))
        assertEquals("https://x.io", normalizeServerUrl("https://x.io/"))
    }

    @Test fun bodies() {
        val b = jsonBody("date" to "2026-01-01", "qty" to 1.5, "meal" to null, "want_token" to true)
        assertEquals("""{"date":"2026-01-01","qty":1.5,"meal":null,"want_token":true}""", b.toString())
    }

    @Test fun portionLine() {
        val d = json.decodeFromString<Day>(fixture("day.json"))
        val e = d.entries.first { it.foodId != null && it.unit != "گرم" }
        val text = portionText(e)
        assertTrue(text, text.contains(isolate(e.unit!!)))
        assertTrue(text, text.endsWith(" g"))
    }

    @Test fun oldDayWithoutWorkouts() {
        val d = json.decodeFromString<Day>(fixture("day.json"))
        assertTrue(d.workouts.isEmpty())
        assertEquals(100, d.exercisePct)
    }

    @Test fun workoutDay() {
        val d = json.decodeFromString<Day>(fixture("workout_day.json"))
        assertEquals(3, d.workouts.size)
        assertEquals(d.workouts.sumOf { it.kcal }, d.burned)
        assertEquals(d.burned, d.exercise)
        val lift = d.workouts.first()
        assertEquals("strength", lift.kind)
        assertTrue(lift.muscles.isNotEmpty())
        val run = d.workouts.last()
        assertEquals("running", run.activity)
        assertEquals(7.0, run.distanceKm!!, 0.001)
        assertEquals("40 min · Moderate · 7 km (5:43 /km) · Tempo", workoutSub(run))
    }

    @Test fun workoutMeta() {
        val m = json.decodeFromString<WorkoutMeta>(fixture("workouts_meta.json"))
        assertEquals("bodybuilding", m.activities.first().key)
        assertTrue(m.activities.all { it.met.size == 3 })
        assertTrue(m.activities.first { it.key == "running" }.distance)
        assertEquals(8, m.muscles.size)
        assertNotNull(m.weight)
        assertTrue(m.recent.isNotEmpty())
    }

    @Test fun workoutStats() {
        val s = json.decodeFromString<WorkoutStats>(fixture("workouts_stats.json"))
        assertEquals(7, s.buckets.size)
        assertEquals(1, s.bucketDays)
        assertEquals(s.totals.minutes, s.buckets.sumOf { it.minutes })
        assertTrue(s.week.goal in 1..7)
        assertEquals(7, s.muscles.size)
        assertTrue(s.byActivity.isNotEmpty())
        val long = json.decodeFromString<WorkoutStats>(fixture("workouts_stats_90.json"))
        assertEquals(7, long.bucketDays)
        assertEquals(13, long.buckets.size)
    }

    @Test fun profileWithTraining() {
        val p = json.decodeFromString<Profile>(fixture("profile_v2.json"))
        assertEquals(4, p.workoutGoal)
        assertEquals(100, p.exercisePct)
    }

    @Test fun durations() {
        assertEquals("45 min", duration(45))
        assertEquals("1 h", duration(60))
        assertEquals("1 h 30 min", duration(90))
    }

    @Test fun listsInJsonBody() {
        assertEquals("""{"muscles":["chest","back"],"kcal":null}""", jsonBody("muscles" to listOf("chest", "back"), "kcal" to null).toString())
    }
}
