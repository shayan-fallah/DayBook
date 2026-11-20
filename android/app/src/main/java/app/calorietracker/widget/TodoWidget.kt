package app.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import app.calorietracker.R
import app.calorietracker.data.TaskItem
import app.calorietracker.ui.dueText
import java.time.LocalDate

/** To-do list: open to-dos grouped like the app (overdue, today, upcoming, someday). Tap the circle to finish one. */
class TodoWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetRepo.flow(context)
        provideContent {
            val s by flow.collectAsState()
            TodoContent(s)
        }
    }
}

class TodoWidgetReceiver : DaybookWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()
}

internal fun todoGroups(tasks: List<TaskItem>, today: String = LocalDate.now().toString()): List<Pair<String, List<TaskItem>>> =
    listOf(
        "Overdue" to tasks.filter { it.due != null && it.due < today }.sortedBy { it.due },
        "Today" to tasks.filter { it.due == today }.sortedByDescending { it.priority },
        "Upcoming" to tasks.filter { it.due != null && it.due > today }.sortedBy { it.due },
        "Someday" to tasks.filter { it.due == null }.sortedWith(compareByDescending<TaskItem> { it.priority }.thenBy { it.id }),
    ).filter { it.second.isNotEmpty() }

private sealed interface TodoRow
private data class GroupRow(val name: String) : TodoRow
private data class TaskRow(val task: TaskItem) : TodoRow

@Composable
private fun TodoContent(s: WidgetSnapshot) {
    WidgetFrame {
        if (s.state != "ok") {
            NotReady(s)
        } else {
            val today = LocalDate.now().toString()
            val open = s.tasks
            val overdue = open.count { it.due != null && it.due < today }
            val sub = when {
                open.isEmpty() -> "All done"
                overdue > 0 -> "${open.size} open · $overdue overdue"
                else -> "${open.size} open"
            }
            Header("To-do", sub, s.error, openApp("todo")) {
                IconButton(R.drawable.ic_w_refresh, "Refresh", actionRunCallback<RefreshAction>())
                IconButton(R.drawable.ic_w_add, "New to-do", openApp("newtask"), fill = GlanceTheme.colors.primary)
            }
            Spacer(GlanceModifier.height(4.dp))
            if (open.isEmpty()) {
                Box(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(openApp("newtask")),
                    contentAlignment = Alignment.Center,
                ) {
                    Muted("Nothing to do. Tap + to add a to-do.", size = 13, lines = 2)
                }
            } else {
                val rows = todoGroups(open, today).flatMap { (name, list) -> listOf(GroupRow(name)) + list.map { TaskRow(it) } }
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(rows) { row ->
                        when (row) {
                            is GroupRow -> Label(
                                row.name, GlanceModifier.padding(top = 6.dp, bottom = 2.dp), size = 12, bold = true,
                                color = if (row.name == "Overdue") GlanceTheme.colors.error else GlanceTheme.colors.primary,
                            )
                            is TaskRow -> TaskLine(s, row.task, today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskLine(s: WidgetSnapshot, t: TaskItem, today: String) {
    val late = t.due != null && t.due < today
    val sub = listOfNotNull(t.projectId?.let { s.projectName(it) }, t.due?.let { s.prefs.dueText(it) }).joinToString(" · ")
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(36.dp).clickable(actionRunCallback<ToggleTaskAction>(actionParametersOf(TaskKey to t.id))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(R.drawable.ic_w_circle), "Mark done", GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(
                    when (t.priority) {
                        3 -> GlanceTheme.colors.error
                        2 -> GlanceTheme.colors.primary
                        else -> GlanceTheme.colors.outline
                    },
                ),
            )
        }
        Spacer(GlanceModifier.width(4.dp))
        Column(GlanceModifier.defaultWeight().clickable(openApp("todo"))) {
            Label(t.title, lines = 2)
            if (sub.isNotEmpty()) {
                Label(sub, size = 11, color = if (late) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant)
            }
        }
        if (t.priority > 0) {
            Label(
                "!".repeat(t.priority), GlanceModifier.padding(horizontal = 6.dp), bold = true,
                color = if (t.priority == 3) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            )
        }
    }
}
