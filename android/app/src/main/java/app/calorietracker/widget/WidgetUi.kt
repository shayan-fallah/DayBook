package app.calorietracker.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.calorietracker.MainActivity
import app.calorietracker.Nav
import app.calorietracker.R
import app.calorietracker.data.StopReply
import app.calorietracker.data.TaskItem
import app.calorietracker.data.TimeEntry
import app.calorietracker.data.TimerReply
import app.calorietracker.data.jsonBody
import app.calorietracker.ui.DarkScheme
import app.calorietracker.ui.LightScheme
import app.calorietracker.ui.hexColor

/** The app's own colors (light and dark), so the widgets look like Daybook. */
val WidgetColors = ColorProviders(light = LightScheme, dark = DarkScheme)

val ProjectKey = ActionParameters.Key<Long>("project")
val TaskKey = ActionParameters.Key<Long>("task")
val DescKey = ActionParameters.Key<String>("desc")
private val OpenKey = ActionParameters.Key<String>(Nav.EXTRA_OPEN)

/** Open the app on a screen, see [Nav]. */
fun openApp(target: String): Action = actionStartActivity<MainActivity>(actionParametersOf(OpenKey to target))

fun startTimer(projectId: Long?, taskId: Long?, description: String): Action = actionRunCallback<StartTimerAction>(
    actionParametersOf(ProjectKey to (projectId ?: -1L), TaskKey to (taskId ?: -1L), DescKey to description),
)

/** All three widgets refresh every 30 minutes (and right after being added) if their data is old. */
abstract class DaybookWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRepo.requestRefresh(context, onlyIfStale = true)
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
fun WidgetFrame(content: @Composable ColumnScope.() -> Unit) {
    GlanceTheme(colors = WidgetColors) {
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                .background(GlanceTheme.colors.surface).padding(12.dp),
            content = content,
        )
    }
}

@Composable
fun Label(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    size: Int = 13,
    color: ColorProvider? = null,
    bold: Boolean = false,
    lines: Int = 1,
    center: Boolean = false,
) {
    Text(
        text, modifier,
        style = TextStyle(
            color = color ?: GlanceTheme.colors.onSurface,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        ),
        maxLines = lines,
    )
}

@Composable
fun Muted(text: String, modifier: GlanceModifier = GlanceModifier, size: Int = 12, lines: Int = 1) =
    Label(text, modifier, size, GlanceTheme.colors.onSurfaceVariant, lines = lines)

/** A round icon button. With [fill] it's a solid circle (drawn as an image, so it's round on every Android version). */
@Composable
fun IconButton(icon: Int, label: String, onClick: Action, fill: ColorProvider? = null, tint: ColorProvider? = null, size: Dp = 36.dp) {
    Box(GlanceModifier.size(size).clickable(onClick), contentAlignment = Alignment.Center) {
        if (fill != null) {
            Image(ImageProvider(R.drawable.ic_w_dot), null, GlanceModifier.size(size - 4.dp), colorFilter = ColorFilter.tint(fill))
        }
        Image(
            ImageProvider(icon), label, GlanceModifier.size(if (fill != null) 18.dp else 20.dp),
            colorFilter = ColorFilter.tint(tint ?: if (fill != null) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
fun Dot(hex: String, size: Dp = 10.dp) {
    Image(ImageProvider(R.drawable.ic_w_dot), null, GlanceModifier.size(size), colorFilter = ColorFilter.tint(ColorProvider(hexColor(hex))))
}

/** Title (tap opens the app) with small buttons on the right. */
@Composable
fun Header(title: String, sub: String?, error: String?, onTitle: Action, buttons: @Composable RowScope.() -> Unit) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight().clickable(onTitle)) {
            Label(title, size = 15, bold = true)
            when {
                error != null -> Label(error, size = 11, color = GlanceTheme.colors.error)
                sub != null -> Muted(sub, size = 12)
            }
        }
        buttons()
    }
}

/** Shown before the first load and when logged out. */
@Composable
fun NotReady(s: WidgetSnapshot) {
    Box(GlanceModifier.fillMaxSize().clickable(openApp("today")), contentAlignment = Alignment.Center) {
        Muted(
            if (s.state == "logged_out") "Open Daybook and log in to see your day here." else "Loading…",
            size = 13, lines = 3,
        )
    }
}

/** A live clock: Android's Chronometer keeps ticking on the home screen without waking the app. */
@Composable
fun Chrono(startTs: Long, skew: Long, big: Boolean = false) {
    val context = LocalContext.current
    val elapsedMs = ((System.currentTimeMillis() / 1000 + skew) - startTs).coerceAtLeast(0) * 1000
    val views = RemoteViews(context.packageName, if (big) R.layout.widget_chronometer_big else R.layout.widget_chronometer).apply {
        setChronometer(R.id.chronometer, SystemClock.elapsedRealtime() - elapsedMs, null, true)
    }
    AndroidRemoteViews(views)
}

/** The running timer with a stop button, or "Continue <last entry>" with a start button. */
@Composable
fun TimerRow(s: WidgetSnapshot) {
    val r = s.running
    Row(
        GlanceModifier.fillMaxWidth().cornerRadius(14.dp).background(GlanceTheme.colors.surfaceVariant)
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp).clickable(openApp("timer")),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r != null) {
            Dot(s.color(r.projectId))
            Spacer(GlanceModifier.width(8.dp))
            val title = s.titleOf(r)
            val project = s.projectName(r.projectId)
            Column(GlanceModifier.defaultWeight()) {
                Label(title, bold = true)
                if (project != title) Muted(project, size = 11)
            }
            Spacer(GlanceModifier.width(6.dp))
            Chrono(r.startTs, s.skew)
            IconButton(R.drawable.ic_w_stop, "Stop timer", actionRunCallback<StopTimerAction>(), fill = GlanceTheme.colors.error)
        } else {
            val last = s.last()
            Column(GlanceModifier.defaultWeight()) {
                Muted(if (last != null) "Continue" else "No timer running", size = 11)
                Label(last?.let { s.titleOf(it) } ?: "Start the timer", bold = true)
            }
            IconButton(
                R.drawable.ic_w_play, "Start timer",
                startTimer(last?.projectId, last?.taskId, last?.description ?: ""), fill = GlanceTheme.colors.primary,
            )
        }
    }
}

// ---------------------------------------------------------------- actions from the widgets

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetRepo.refresh(context)
    }
}

/** Tick a to-do: it disappears at once, then the server is told. */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskKey] ?: return
        val before = WidgetRepo.current(context)
        WidgetRepo.edit(context) { s -> s.copy(tasks = s.tasks.filter { it.id != id }, error = null) }
        WidgetRepo.act(context, before) { api -> api.patch<TaskItem>("/api/tasks/$id", jsonBody("done" to true)) }
    }
}

class StartTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val projectId = parameters[ProjectKey]?.takeIf { it > 0 }
        val taskId = parameters[TaskKey]?.takeIf { it > 0 }
        val description = parameters[DescKey] ?: ""
        val before = WidgetRepo.current(context)
        WidgetRepo.edit(context) { s ->
            val now = s.now()
            val stopped = s.running?.let { listOf(it.copy(endTs = now)) } ?: emptyList()
            s.copy(
                running = TimeEntry(id = -1, projectId = projectId, taskId = taskId, description = description, startTs = now),
                entries = stopped + s.entries, error = null,
            )
        }
        WidgetRepo.act(context, before) { api ->
            api.post<TimerReply>(
                "/api/time/timer/start",
                jsonBody("description" to description, "project_id" to projectId, "task_id" to taskId),
            )
        }
    }
}

class StopTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val before = WidgetRepo.current(context)
        WidgetRepo.edit(context) { s ->
            val stopped = s.running?.let { listOf(it.copy(endTs = s.now())) } ?: emptyList()
            s.copy(running = null, entries = stopped + s.entries, error = null)
        }
        WidgetRepo.act(context, before) { api -> api.post<StopReply>("/api/time/timer/stop") }
    }
}
