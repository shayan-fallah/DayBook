package app.calorietracker

import app.calorietracker.data.Api
import app.calorietracker.data.Day
import app.calorietracker.data.Food
import app.calorietracker.data.Me
import app.calorietracker.data.Profile
import app.calorietracker.data.QuickLists
import app.calorietracker.data.SearchReply
import app.calorietracker.data.Stats
import app.calorietracker.data.jsonBody
import app.calorietracker.data.normalizeServerUrl
import app.calorietracker.ui.isolate
import app.calorietracker.ui.parseNum
import app.calorietracker.ui.portionText
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
}
