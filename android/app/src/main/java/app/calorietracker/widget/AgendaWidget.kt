package app.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import app.calorietracker.R
import app.calorietracker.ui.dateOfTs
import app.calorietracker.ui.dayTitle
import app.calorietracker.ui.hexColor
import app.calorietracker.ui.hm
import app.calorietracker.ui.hrs
import app.calorietracker.ui.relDay
import java.time.LocalDate

/** Agenda: the running timer, then what's planned (and Google events) for today and the coming week. */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetRepo.flow(context)
        provideContent {
            val s by flow.collectAsState()
            AgendaContent(s)
        }
    }
}

class AgendaWidgetReceiver : DaybookWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

internal sealed interface AgendaRow
internal data class DayRow(val date: LocalDate) : AgendaRow
internal data class ItemRow(val item: AgendaItem, val now: Boolean) : AgendaRow

/** Day headings and items, skipping what has already ended. All-day events come first in each day. */
internal fun agendaRows(s: WidgetSnapshot, today: LocalDate = LocalDate.now()): List<AgendaRow> {
    val now = s.now()
    val byDay = s.agenda
        .filter { it.day != null || it.end > now }
        .groupBy { item -> item.day?.let { LocalDate.parse(it) } ?: maxOf(dateOfTs(item.start), today) }
        .filterKeys { it >= today }
        .toSortedMap()
    val rows = mutableListOf<AgendaRow>()
    for ((date, items) in byDay) {
        rows += DayRow(date)
        items.sortedWith(compareBy({ it.day == null }, { it.start })).forEach {
            rows += ItemRow(it, it.day == null && it.start <= now)
        }
    }
    return rows
}

@Composable
private fun AgendaContent(s: WidgetSnapshot) {
    WidgetFrame {
        if (s.state != "ok") {
            NotReady(s)
        } else {
            val today = LocalDate.now()
            Header(s.prefs.dayTitle(today), "${hrs(s.trackedToday())} tracked today", s.error, openApp("plan")) {
                IconButton(R.drawable.ic_w_refresh, "Refresh", actionRunCallback<RefreshAction>())
                IconButton(R.drawable.ic_w_add, "Plan time", openApp("plan"))
            }
            Spacer(GlanceModifier.height(8.dp))
            TimerRow(s)
            Spacer(GlanceModifier.height(4.dp))
            val rows = agendaRows(s, today)
            if (rows.isEmpty()) {
                Box(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(openApp("plan")),
                    contentAlignment = Alignment.Center,
                ) {
                    Muted("Nothing planned for the coming week. Tap + to plan time.", size = 13, lines = 3)
                }
            } else {
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(rows) { row ->
                        when (row) {
                            is DayRow -> Label(
                                s.prefs.relDay(row.date), GlanceModifier.padding(top = 8.dp, bottom = 2.dp),
                                size = 12, color = GlanceTheme.colors.primary, bold = true,
                            )
                            is ItemRow -> AgendaLine(s, row, today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaLine(s: WidgetSnapshot, row: ItemRow, today: LocalDate) {
    val item = row.item
    val muted = GlanceTheme.colors.onSurfaceVariant
    val time = when {
        item.day != null -> "All day"
        row.now -> "Now · until ${hm(item.end)}"
        else -> "${hm(item.start)} – ${hm(item.end)}"
    }
    val running = s.running
    val canStart = item.block && item.day == null && dateOfTs(item.start) <= today &&
        !(running != null && running.projectId == item.projectId && running.taskId == item.taskId &&
            running.description == item.description)
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(openApp("plan")),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.width(4.dp).height(36.dp).background(ColorProvider(hexColor(item.color))))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Label(time, size = 11, color = if (row.now) GlanceTheme.colors.primary else muted, bold = row.now)
            Label(item.title, bold = true)
            if (item.sub.isNotBlank()) Label(item.sub, size = 11, color = muted)
        }
        if (canStart) {
            IconButton(
                R.drawable.ic_w_play, "Start timer", startTimer(item.projectId, item.taskId, item.description),
                tint = GlanceTheme.colors.primary,
            )
        }
    }
}
