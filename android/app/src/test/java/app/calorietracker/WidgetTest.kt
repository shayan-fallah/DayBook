package app.calorietracker

import app.calorietracker.data.Api
import app.calorietracker.data.Project
import app.calorietracker.data.TaskItem
import app.calorietracker.data.TimeEntry
import app.calorietracker.ui.dayStart
import app.calorietracker.widget.AgendaItem
import app.calorietracker.widget.DayRow
import app.calorietracker.widget.DaySummary
import app.calorietracker.widget.ItemRow
import app.calorietracker.widget.WidgetSnapshot
import app.calorietracker.widget.agendaRows
import app.calorietracker.widget.todoGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The data behind the home-screen widgets. */
class WidgetTest {
    private val json = Api("", null).json

    @Test fun snapshotSurvivesSaving() {
        val s = WidgetSnapshot(
            state = "ok", fetchedAt = 1795000000, skew = -3,
            projects = listOf(Project(1, "پروژه سایت", "#eb6834")),
            tasks = listOf(TaskItem(7, projectId = 1, title = "Fix login", due = "2026-11-19", priority = 3)),
            running = TimeEntry(5, projectId = 1, description = "Deploy", startTs = 1794999000),
            entries = listOf(TimeEntry(4, startTs = 1794990000, endTs = 1794995400)),
            agenda = listOf(AgendaItem("b1", "Write", "Site", "#eb6834", 1795003600, 1795007200, block = true, projectId = 1)),
            day = DaySummary("2026-11-19", kcal = 1250.0, budget = 2300.0, protein = 80.0, proteinGoal = 140.0),
        )
        val text = json.encodeToString(WidgetSnapshot.serializer(), s)
        assertEquals(s, json.decodeFromString(WidgetSnapshot.serializer(), text))
        // An old or empty save still loads.
        assertEquals("empty", json.decodeFromString(WidgetSnapshot.serializer(), "{}").state)
    }

    @Test fun agendaSkipsWhatEndedAndGroupsByDay() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val now = System.currentTimeMillis() / 1000
        val ended = AgendaItem("b1", "Ended", start = now - 7200, end = now - 60, block = true)
        val current = AgendaItem("b2", "Current", start = now - 600, end = now + 600, block = true)
        val later = AgendaItem("b3", "Tomorrow block", start = dayStart(tomorrow) + 9 * 3600, end = dayStart(tomorrow) + 10 * 3600)
        val allDay = AgendaItem("g1", "Holiday", day = tomorrow.toString())
        val old = AgendaItem("g0", "Yesterday", day = today.minusDays(1).toString())
        val rows = agendaRows(WidgetSnapshot(state = "ok", agenda = listOf(later, ended, allDay, current, old)), today)
        assertEquals(
            listOf(DayRow(today), ItemRow(current, true), DayRow(tomorrow), ItemRow(allDay, false), ItemRow(later, false)),
            rows,
        )
    }

    @Test fun trackedTodayCountsOnlyToday() {
        val today = LocalDate.now()
        val now = System.currentTimeMillis() / 1000
        val yesterday = dayStart(today.minusDays(1))
        val s = WidgetSnapshot(
            state = "ok",
            entries = listOf(TimeEntry(1, startTs = yesterday + 3600, endTs = yesterday + 7200)),
            running = TimeEntry(2, startTs = now - 60),
        )
        val expected = minOf(60, now - dayStart(today))
        assertTrue(s.trackedToday() in expected..expected + 2)
    }

    @Test fun todoGroupsInOrder() {
        val today = "2026-11-19"
        val tasks = listOf(
            TaskItem(1, title = "Someday low"),
            TaskItem(2, title = "Someday high", priority = 3),
            TaskItem(3, title = "Late", due = "2026-11-17"),
            TaskItem(4, title = "Today", due = today),
            TaskItem(5, title = "Next week", due = "2026-11-25"),
            TaskItem(6, title = "Tomorrow", due = "2026-11-20"),
        )
        val groups = todoGroups(tasks, today)
        assertEquals(listOf("Overdue", "Today", "Upcoming", "Someday"), groups.map { it.first })
        assertEquals(listOf("Tomorrow", "Next week"), groups[2].second.map { it.title })
        assertEquals(listOf("Someday high", "Someday low"), groups[3].second.map { it.title })
    }
}
