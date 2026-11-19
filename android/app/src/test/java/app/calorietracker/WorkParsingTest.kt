package app.calorietracker

import app.calorietracker.data.Api
import app.calorietracker.data.EntriesReply
import app.calorietracker.data.Food
import app.calorietracker.data.GoogleEventsReply
import app.calorietracker.data.GoogleStatus
import app.calorietracker.data.PlanReply
import app.calorietracker.data.ProjectsReply
import app.calorietracker.data.QuickLists
import app.calorietracker.data.TasksReply
import app.calorietracker.data.WorkPrefs
import app.calorietracker.ui.Jalali
import app.calorietracker.ui.addMonths
import app.calorietracker.ui.clock
import app.calorietracker.ui.dayNum
import app.calorietracker.ui.hrs
import app.calorietracker.ui.isWorkDay
import app.calorietracker.ui.monthStart
import app.calorietracker.ui.overlap
import app.calorietracker.ui.startOfWeek
import app.calorietracker.ui.yearStart
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The time, planner and food-editing replies from app.py, plus the calendar math the screens rely on. */
class WorkParsingTest {
    private val json = Api("", null).json
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun projectsAndTasks() {
        val p = json.decodeFromString<ProjectsReply>(fixture("projects.json"))
        assertEquals(2, p.projects.size)
        assertEquals("پروژه سایت", p.projects[1].name)
        assertEquals(5400L, p.projects[1].total)
        assertEquals(1, p.projects[0].openTasks)
        val t = json.decodeFromString<TasksReply>(fixture("tasks.json"))
        val bug = t.tasks.first { !it.done }
        assertEquals(3, bug.priority)
        assertEquals(1L, bug.projectId)
        assertNull(bug.due)
        assertTrue(t.tasks.any { it.done && it.doneAt != null })
    }

    @Test fun timeEntriesAndPlan() {
        val e = json.decodeFromString<EntriesReply>(fixture("time_entries.json"))
        assertEquals(2, e.entries.size)
        assertNull(e.running)
        assertTrue(e.now > 0)
        assertEquals(5400L, e.entries[1].endTs!! - e.entries[1].startTs)
        val p = json.decodeFromString<PlanReply>(fixture("plan.json"))
        assertEquals(3, p.blocks.size)
        assertEquals(1, p.blocks.map { it.series }.toSet().size)
        assertFalse(p.google)
        assertNull(p.blocks[0].gcal)
    }

    @Test fun prefsAndGoogle() {
        val prefs = json.decodeFromString<WorkPrefs>(fixture("prefs.json"))
        assertEquals("jalali", prefs.calendar)
        assertEquals(8.0, prefs.workGoal, 0.0)
        val off = json.decodeFromString<GoogleStatus>(fixture("google.json"))
        assertFalse(off.available)
        assertTrue(off.redirectUri.endsWith("/api/google/callback"))
        val on = json.decodeFromString<GoogleStatus>(
            """{"available":true,"connected":true,"redirect_uri":"https://x/api/google/callback","email":"me@example.com",
               "calendar_id":"me@example.com","show_events":true,"last_sync":1795024000,"last_error":null,
               "needs_reauth":false,"pending":2,"pushed":1}""",
        )
        assertEquals(2, on.pending)
        assertEquals("me@example.com", on.calendarId)
        val ev = json.decodeFromString<GoogleEventsReply>(
            """{"connected":true,"events":[{"id":"a","title":"Standup","calendar":"Team","color":"#f00","link":null,
               "all_day":false,"start_ts":100,"end_ts":200},{"id":"b","title":"Holiday","calendar":"Me","color":"#0f0",
               "link":"https://g","all_day":true,"start_date":"2026-11-20","end_date":"2026-11-21"}],"changed":1,"error":null}""",
        )
        assertEquals(2, ev.events.size)
        assertTrue(ev.events[1].allDay)
        assertEquals("2026-11-20", ev.events[1].startDate)
    }

    @Test fun editedFood() {
        val f = json.decodeFromString<Food>(fixture("food_edited.json"))
        assertTrue(f.edited)
        assertNotNull(f.original)
        assertEquals(52.0, f.original!!.kcal, 0.01)
        assertEquals(77.0, f.kcal, 0.01)
        assertTrue(f.uses >= 0)
        val q = json.decodeFromString<QuickLists>(fixture("quick_edited.json"))
        assertTrue(q.edited.any { it.id == f.id && it.edited })
    }

    @Test fun jalaliConversion() {
        val j = Jalali.fromDate(LocalDate.of(2026, 11, 18))
        assertEquals(Jalali.JDate(1405, 8, 27), j)
        assertEquals(LocalDate.of(2025, 3, 21), Jalali.toDate(1404, 1, 1)) // Nowruz
        assertEquals(LocalDate.of(2026, 3, 20), Jalali.toDate(1404, 12, 29))
        // Round trip over a few years
        var d = LocalDate.of(2023, 1, 1)
        while (d.year < 2028) {
            val x = Jalali.fromDate(d)
            assertEquals(d, Jalali.toDate(x.y, x.m, x.d))
            d = d.plusDays(13)
        }
    }

    @Test fun calendarHelpers() {
        val greg = WorkPrefs(calendar = "gregorian")
        val shamsi = WorkPrefs(calendar = "jalali")
        val wed = LocalDate.of(2026, 11, 18)
        assertEquals(DayOfWeek.MONDAY, greg.startOfWeek(wed).dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, shamsi.startOfWeek(wed).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, WorkPrefs(weekStart = "sun").startOfWeek(wed).dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 1), greg.monthStart(wed))
        assertEquals(LocalDate.of(2026, 10, 23), shamsi.monthStart(wed)) // 1 Aban 1405
        assertEquals(LocalDate.of(2026, 11, 22), shamsi.addMonths(shamsi.monthStart(wed), 1)) // 1 Azar
        assertEquals(LocalDate.of(2026, 3, 21), shamsi.yearStart(wed))
        assertEquals("۲۷", shamsi.dayNum(wed))
        assertFalse(shamsi.isWorkDay(LocalDate.of(2026, 11, 20))) // Friday
        assertTrue(greg.isWorkDay(LocalDate.of(2026, 11, 20)))
        assertFalse(greg.isWorkDay(LocalDate.of(2026, 11, 21)))
    }

    @Test fun durations() {
        assertEquals("1:02:05", clock(3725))
        assertEquals("0:00:00", clock(-5))
        assertEquals("45 min", hrs(2700))
        assertEquals("1 h 30 min", hrs(5400))
        assertEquals("2 h", hrs(7200))
        assertEquals(30L, overlap(100, 200, 170, 300))
        assertEquals(0L, overlap(100, 200, 300, 400))
    }
}
