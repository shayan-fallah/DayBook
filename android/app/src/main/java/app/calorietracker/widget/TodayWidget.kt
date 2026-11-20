package app.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import app.calorietracker.R
import app.calorietracker.ui.dayTitle
import app.calorietracker.ui.fmt
import app.calorietracker.ui.hrs
import java.time.LocalDate
import kotlin.math.abs

/** Today: calories left with macros, time tracked, and the timer. Shrinks to one line at 4×1. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetRepo.flow(context)
        provideContent {
            val s by flow.collectAsState()
            TodayContent(s)
        }
    }
}

class TodayWidgetReceiver : DaybookWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

private class Calories(val eaten: Double, val budget: Double) {
    val left get() = budget - eaten
    val over get() = left < 0
    val progress get() = if (budget > 0) (eaten / budget).coerceIn(0.0, 1.0).toFloat() else 0f
    val text get() = fmt(abs(left))
    val unit get() = if (over) "kcal over" else "kcal left"
}

@Composable
private fun TodayContent(s: WidgetSnapshot) {
    WidgetFrame {
        if (s.state != "ok") {
            NotReady(s)
        } else {
            val today = LocalDate.now()
            // A snapshot from yesterday: nothing eaten yet today as far as we know.
            val day = s.day
            val fresh = day?.date == today.toString()
            val cal = Calories(if (fresh) day!!.kcal else 0.0, day?.budget ?: 0.0)
            val height = LocalSize.current.height
            when {
                height < 100.dp -> Compact(s, cal)
                else -> Full(s, cal, today, showMacros = fresh && height >= 170.dp)
            }
        }
    }
}

@Composable
private fun Compact(s: WidgetSnapshot, cal: Calories) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight().clickable(openApp("today"))) {
            Label("${cal.text} ${cal.unit}", size = 15, bold = true, color = if (cal.over) GlanceTheme.colors.error else null)
            Muted("${hrs(s.trackedToday())} tracked today", size = 11)
        }
        val r = s.running
        if (r != null) {
            Chrono(r.startTs, s.skew)
            IconButton(R.drawable.ic_w_stop, "Stop timer", actionRunCallback<StopTimerAction>(), fill = GlanceTheme.colors.error)
        } else {
            val last = s.last()
            IconButton(R.drawable.ic_w_play, "Start timer", startTimer(last?.projectId, last?.taskId, last?.description ?: ""))
        }
        IconButton(R.drawable.ic_w_add, "Add food", openApp("add"), fill = GlanceTheme.colors.primary)
    }
}

@Composable
private fun Full(s: WidgetSnapshot, cal: Calories, today: LocalDate, showMacros: Boolean) {
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(openApp("today"))) {
                Muted(s.error ?: s.prefs.dayTitle(today), size = 12)
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(cal.text, size = 26, bold = true, color = if (cal.over) GlanceTheme.colors.error else null)
                    Muted(" ${cal.unit}", GlanceModifier.padding(bottom = 4.dp), size = 13)
                }
            }
            IconButton(R.drawable.ic_w_refresh, "Refresh", actionRunCallback<RefreshAction>())
            IconButton(R.drawable.ic_w_add, "Add food", openApp("add"), fill = GlanceTheme.colors.primary, size = 40.dp)
        }
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            cal.progress, GlanceModifier.fillMaxWidth().height(6.dp),
            color = if (cal.over) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        val d = s.day
        if (showMacros && d != null) {
            Spacer(GlanceModifier.height(6.dp))
            Muted(
                "Protein ${fmt(d.protein)}/${fmt(d.proteinGoal)} · Carbs ${fmt(d.carbs)}/${fmt(d.carbsGoal)} · " +
                    "Fat ${fmt(d.fat)}/${fmt(d.fatGoal)} g",
                size = 11,
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Muted("${hrs(s.trackedToday())} tracked today", size = 11)
        }
        TimerRow(s)
    }
}
