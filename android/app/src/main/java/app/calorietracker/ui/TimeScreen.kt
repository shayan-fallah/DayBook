package app.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.ReportData
import app.calorietracker.WorkStore
import app.calorietracker.data.Project
import app.calorietracker.data.TimeEntry
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TimeScreen(work: WorkStore) {
    Column(Modifier.fillMaxSize()) {
        SubTabs(
            listOf("timer" to "Timer", "projects" to "Projects", "reports" to "Reports"), work.timeTab,
            { work.timeTab = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        when (work.timeTab) {
            "projects" -> ProjectsPane(work)
            "reports" -> ReportsPane(work)
            else -> TimerPane(work)
        }
    }
}

// ---------------------------------------------------------------- timer and log

/** Which time entry the editor is open for; entry null = a new one. */
private data class EntryTarget(val entry: TimeEntry?)

@Composable
private fun TimerPane(work: WorkStore) {
    LaunchedEffect(Unit) { work.loadRecent() }
    var sheet by remember { mutableStateOf<EntryTarget?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { TimerCard(work) }
        item { TimeLog(work, onOpen = { sheet = EntryTarget(it) }, onAdd = { sheet = EntryTarget(null) }) }
        item {
            TextButton(onClick = { work.loadRecent(moreDays = 14) }, modifier = Modifier.fillMaxWidth()) { Text("Show older") }
        }
    }
    sheet?.let { EntrySheet(work, it.entry) { sheet = null } }
}

@Composable
private fun TimerCard(work: WorkStore) {
    val p = LocalPalette.current
    val r = work.running
    val focus = LocalFocusManager.current
    var desc by remember(r?.id, r?.description) { mutableStateOf(r?.description ?: work.draftDescription) }
    val pid = if (r != null) r.projectId else work.draftProject
    val tid = if (r != null) r.taskId else work.draftTask

    AppCard {
        OutlinedTextField(
            value = desc,
            onValueChange = {
                desc = it
                if (r == null) work.draftDescription = it
            },
            placeholder = { Text("What are you working on?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (!it.isFocused && r != null && desc.trim() != r.description) work.updateRunning(desc.trim(), r.projectId, r.taskId)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focus.clearFocus()
                if (r == null) work.startTimer(desc.trim(), pid, tid)
            }),
        )
        Spacer(Modifier.height(10.dp))
        ProjectTaskPickers(work, pid, tid) { np, nt ->
            if (r != null) work.updateRunning(desc.trim(), np, nt)
            else {
                work.draftProject = np
                work.draftTask = nt
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                clock(liveSeconds(work, r?.startTs)), modifier = Modifier.weight(1f),
                fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (r != null) MaterialTheme.colorScheme.onSurface else p.muted,
            )
            Button(
                onClick = {
                    focus.clearFocus()
                    if (r != null) work.stopTimer() else work.startTimer(desc.trim(), pid, tid)
                },
                modifier = Modifier.height(52.dp).width(116.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (r != null) ButtonDefaults.buttonColors(containerColor = p.critical) else ButtonDefaults.buttonColors(),
            ) { Text(if (r != null) "Stop" else "Start", fontSize = 17.sp) }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = p.grid)
        Spacer(Modifier.height(10.dp))
        TimerStats(work)
    }
}

@Composable
private fun TimerStats(work: WorkStore) {
    val p = LocalPalette.current
    val r = work.running
    val today = LocalDate.now()
    val t0 = dayStart(today)
    val t1 = dayStart(today.plusDays(1))
    val w0 = dayStart(work.prefs.startOfWeek(today))
    val done = work.recent.orEmpty().filter { it.endTs != null }
    val dayDone = done.sumOf { overlap(it.startTs, it.endTs!!, t0, t1) }
    val weekDone = done.sumOf { overlap(it.startTs, it.endTs!!, w0, t1) }
    val day = liveSeconds(work, r?.startTs?.let { max(it, t0) }, dayDone)
    val week = liveSeconds(work, r?.startTs?.let { max(it, w0) }, weekDone)
    val goal = work.prefs.workGoal * 3600
    Row {
        Column(Modifier.weight(1f)) {
            Text("Today", style = MaterialTheme.typography.bodySmall, color = p.ink2)
            Text(clock(day), fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Text("This week", style = MaterialTheme.typography.bodySmall, color = p.ink2)
            Text(clock(week), fontWeight = FontWeight.SemiBold)
        }
        if (goal > 0) {
            Column(Modifier.weight(1f)) {
                Text("Daily goal", style = MaterialTheme.typography.bodySmall, color = p.ink2)
                Text("${fmt1(work.prefs.workGoal)} h", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Meter((day / goal).toFloat(), p.accent, height = 6.dp)
            }
        }
    }
}

@Composable
private fun TimeLog(work: WorkStore, onOpen: (TimeEntry) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    val list = work.recent
    AppCard(padding = 0.dp) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Time log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onAdd, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> SubText(
                "Nothing logged in this period. Press Start, or add time by hand.",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color = p.muted,
            )
            else -> list.groupBy { dateOfTs(it.startTs) }.forEach { (day, entries) ->
                val doneSecs = entries.filter { it.endTs != null }.sumOf { it.endTs!! - it.startTs }
                val live = entries.firstOrNull { it.endTs == null }
                Row(
                    Modifier.fillMaxWidth().background(p.surface2).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(work.prefs.relDay(day), style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.weight(1f))
                    Text(clock(liveSeconds(work, live?.startTs, doneSecs)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                entries.forEach { e ->
                    EntryRow(work, e, onClick = { onOpen(e) })
                    HorizontalDivider(color = p.grid)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(work: WorkStore, e: TimeEntry, onClick: () -> Unit) {
    val p = LocalPalette.current
    val running = e.endTs == null
    val t = work.task(e.taskId)
    val where = work.projectName(e.projectId) + if (t != null && e.description.isNotBlank()) " › ${t.title}" else ""
    val span = "${hm(e.startTs)}–${if (running) "now" else hm(e.endTs!!)}" +
        if (!running && dateOfTs(e.endTs!! - 1) != dateOfTs(e.startTs)) " (+1 day)" else ""
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(hexColor(work.projectColor(e.projectId)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MixedText(work.titleOf(e.description, e.projectId, e.taskId), Modifier.weight(1f, fill = false))
                if (running) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = p.critical, shape = RoundedCornerShape(5.dp)) {
                        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
                    }
                }
            }
            Text("${isolate(where)} · $span", style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(clock(if (running) liveSeconds(work, e.startTs) else e.endTs!! - e.startTs), fontWeight = FontWeight.SemiBold)
        if (running) {
            IconButton(onClick = { work.stopTimer() }) {
                Box(Modifier.size(12.dp).background(p.critical))
            }
        } else {
            IconButton(onClick = { work.startTimer(e.description, e.projectId, e.taskId) }) {
                Icon(Icons.Filled.PlayArrow, "Continue this", tint = p.accent)
            }
        }
    }
}

/** Start and end from a date and two times; an end at or before the start is on the next day. */
fun spanOf(date: LocalDate, start: LocalTime, end: LocalTime?): Pair<Long, Long?> {
    val a = tsOf(date, start)
    if (end == null) return a to null
    val b = tsOf(if (end <= start) date.plusDays(1) else date, end)
    return a to b
}

fun spanHint(a: Long, b: Long?, now: Long): String {
    val secs = (b ?: now) - a
    return "Duration: ${hrs(secs)}" + if (b != null && dateOfTs(b - 1) != dateOfTs(a)) " · ends the next day" else ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(work: WorkStore, entry: TimeEntry?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val running = entry != null && entry.endTs == null
    val defaults = remember {
        val n = LocalTime.now().withSecond(0).withNano(0).let { it.minusMinutes((it.minute % 5).toLong()) }
        val s = if (n.hour >= 1) n.minusHours(1) else LocalTime.MIDNIGHT
        s to n
    }
    var desc by remember { mutableStateOf(entry?.description ?: "") }
    var pid by remember { mutableStateOf(entry?.projectId) }
    var tid by remember { mutableStateOf(entry?.taskId) }
    var date by remember { mutableStateOf(entry?.let { dateOfTs(it.startTs) } ?: LocalDate.now()) }
    var start by remember { mutableStateOf(entry?.let { timeOfTs(it.startTs) } ?: defaults.first) }
    var end by remember { mutableStateOf(entry?.endTs?.let { timeOfTs(it) } ?: if (running) null else defaults.second) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (entry == null) "Add time" else if (running) "Edit running timer" else "Edit time", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                desc, { desc = it }, label = { Text("What did you do?") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            ProjectTaskPickers(work, pid, tid) { np, nt -> pid = np; tid = nt }
            DateField("Date", date, work.prefs.dayTitle(date)) { it?.let { d -> date = d } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeField("Start", start, Modifier.weight(1f)) { start = it }
                TimeField("End", end, Modifier.weight(1f), enabled = !running) { end = it }
            }
            val (a, b) = spanOf(date, start, end)
            SubText(spanHint(a, b, work.now()))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy, modifier = Modifier.width(140.dp).height(48.dp),
                    onClick = {
                        val body = jsonBody(
                            "description" to desc.trim(), "project_id" to pid, "task_id" to tid, "start_ts" to a,
                            *(if (b != null) arrayOf<Pair<String, Any?>>("end_ts" to b) else emptyArray()),
                        )
                        busy = true
                        scope.launch {
                            val ok = work.saveEntry(entry?.id, body)
                            busy = false
                            if (ok) close()
                        }
                    },
                ) { Text(if (entry == null) "Add" else "Save") }
            }
        }
    }
    if (confirmDelete && entry != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this time entry?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (work.deleteEntry(entry)) close() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- projects

private const val NEW_PROJECT = -1L

@Composable
private fun ProjectsPane(work: WorkStore) {
    LaunchedEffect(Unit) {
        work.loadProjects()
        work.loadTasks()
    }
    var editing by remember { mutableStateOf<Long?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    val active = work.projects.filter { !it.archived }
    val archived = work.projects.filter { it.archived }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Button(onClick = { editing = NEW_PROJECT }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New project")
            }
        }
        item {
            AppCard(padding = 0.dp) {
                if (active.isEmpty()) {
                    EmptyNote("No projects yet. Projects group your time, plans and to-dos.")
                }
                active.forEachIndexed { i, pr ->
                    if (i > 0) HorizontalDivider(color = p.grid)
                    ProjectRow(work, pr) { editing = pr.id }
                }
            }
        }
        if (archived.isNotEmpty()) {
            item {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide archived projects" else "Show ${archived.size} archived project${if (archived.size == 1) "" else "s"}")
                }
            }
            if (showArchived) {
                item {
                    AppCard(padding = 0.dp) {
                        archived.forEachIndexed { i, pr ->
                            if (i > 0) HorizontalDivider(color = p.grid)
                            ProjectRow(work, pr) { editing = pr.id }
                        }
                    }
                }
            }
        }
    }
    editing?.let { id ->
        ProjectSheet(work, if (id == NEW_PROJECT) null else work.project(id)) { editing = null }
    }
}

@Composable
private fun ProjectRow(work: WorkStore, pr: Project, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(hexColor(pr.color), size = 12)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MixedText(pr.name)
            Text(
                "${hoursDec(pr.total)} logged" + if (pr.openTasks > 0) " · ${pr.openTasks} open to-do${if (pr.openTasks == 1) "" else "s"}" else "",
                style = MaterialTheme.typography.bodySmall, color = p.muted,
            )
        }
        if (!pr.archived) {
            IconButton(onClick = { work.startTimer("", pr.id, null) }) {
                Icon(Icons.Filled.PlayArrow, "Start timer on ${pr.name}", tint = p.accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProjectSheet(work: WorkStore, project: Project?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val used = work.projects.filter { !it.archived }.map { it.color }.toSet()
    var name by remember { mutableStateOf(project?.name ?: "") }
    var color by remember { mutableStateOf(project?.color ?: PROJECT_COLORS.firstOrNull { it !in used } ?: PROJECT_COLORS[0]) }
    var archived by remember { mutableStateOf(project?.archived ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val palette = (PROJECT_COLORS + listOf("#7b8794", "#b04fc9", "#2fa36b", "#c27c0e")).let { if (color in it) it else it + color }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (project == null) "New project" else "Edit project", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            SubText("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(hexColor(c))
                            .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { color = c },
                        Alignment.Center,
                    ) { if (c == color) Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
            if (project != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Archived")
                        SubText("Hidden from pickers; its time stays in reports.")
                    }
                    Switch(archived, { archived = it })
                }
                SubText("${hrs(project.total)} logged in total · ${project.openTasks} open to-do${if (project.openTasks == 1) "" else "s"}.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (project != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy && name.isNotBlank(), modifier = Modifier.width(140.dp).height(48.dp),
                    onClick = {
                        busy = true
                        scope.launch {
                            val body = if (project == null) jsonBody("name" to name.trim(), "color" to color)
                            else jsonBody("name" to name.trim(), "color" to color, "archived" to archived)
                            val ok = work.saveProject(project?.id, body)
                            busy = false
                            if (ok) close()
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
    if (confirmDelete && project != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${project.name}”?") },
            text = {
                Text(
                    (if (project.openTasks > 0) "Its ${project.openTasks} open to-do${if (project.openTasks == 1) " is" else "s are"} deleted too. " else "") +
                        "Logged time and plans stay, without a project. Archiving keeps everything.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (work.deleteProject(project)) close() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- reports

@Composable
private fun ReportsPane(work: WorkStore) {
    LaunchedEffect(Unit) { work.loadReport() }
    val (a, b) = work.reportRange()
    val prefs = work.prefs
    val label = when (work.reportPreset) {
        "day" -> prefs.relDay(a)
        "week" -> "${prefs.shortDay(a)} – ${prefs.shortDay(b.minusDays(1))}"
        "month" -> prefs.monthTitle(a)
        else -> prefs.yearTitle(a)
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            SubTabs(listOf("day" to "Day", "week" to "Week", "month" to "Month", "year" to "Year"), work.reportPreset, { work.setReport(it) })
        }
        item { NavBar(label, onPrev = { work.shiftReport(-1) }, onNext = { work.shiftReport(1) }) }
        val r = work.report
        if (r == null || r.from != a) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        val summary = summarize(work, r)
        item { ReportTiles(work, r, summary) }
        if (summary.total > 0) item { ReportChartCard(work, r) }
        item { ReportProjects(work, summary) }
    }
}

private class ProjectSlot(val projectId: Long?) {
    var secs = 0L
    var planned = 0L
    val tasks = mutableMapOf<Long?, Long>()
}

private class Summary(val total: Long, val planned: Long, val days: Int, val entries: Int, val projects: List<ProjectSlot>)

private fun summarize(work: WorkStore, r: ReportData): Summary {
    val from = dayStart(r.from)
    val to = dayStart(r.to)
    val now = work.now()
    val slots = linkedMapOf<Long?, ProjectSlot>()
    fun slot(id: Long?) = slots.getOrPut(id) { ProjectSlot(id) }
    var total = 0L
    val days = mutableSetOf<LocalDate>()
    for (e in r.entries) {
        val s = e.overlap(from, to, now)
        if (s <= 0) continue
        total += s
        val sl = slot(e.projectId)
        sl.secs += s
        sl.tasks[e.taskId] = (sl.tasks[e.taskId] ?: 0) + s
        var d = dateOfTs(max(e.startTs, from))
        while (dayStart(d) < min(e.endOr(now), to)) {
            days += d
            d = d.plusDays(1)
        }
    }
    var planned = 0L
    for (bl in r.blocks) {
        val s = overlap(bl.startTs, bl.endTs, from, to)
        planned += s
        slot(bl.projectId).planned += s
    }
    return Summary(total, planned, days.size, r.entries.size, slots.values.sortedWith(compareByDescending<ProjectSlot> { it.secs }.thenByDescending { it.planned }))
}

@Composable
private fun ReportTiles(work: WorkStore, r: ReportData, s: Summary) {
    val elapsed = max(1L, min(ChronoUnit.DAYS.between(r.from, r.to), ChronoUnit.DAYS.between(r.from, LocalDate.now()) + 1))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Total", fmt1(s.total / 3600.0), "h", "${s.entries} entr${if (s.entries == 1) "y" else "ies"}", Modifier.weight(1f))
            Tile("Average", fmt1(if (s.days > 0) s.total / 3600.0 / s.days else 0.0), "h / day", "On days you worked", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Days worked", "${s.days}", "/ $elapsed", if (work.prefs.workGoal > 0) "Goal ${fmt1(work.prefs.workGoal)} h a day" else "", Modifier.weight(1f))
            Tile("Planned", fmt1(s.planned / 3600.0), "h",
                if (s.planned > 0) "${(s.total * 100.0 / s.planned).roundToInt()}% of it logged" else "Nothing planned", Modifier.weight(1f))
        }
    }
}

private class Bucket(val start: Long, val end: Long, val label: String, val title: String) {
    val parts = linkedMapOf<Long?, Long>()
    val total get() = parts.values.sum()
}

private fun buckets(work: WorkStore, r: ReportData): List<Bucket> {
    val prefs = work.prefs
    val out = mutableListOf<Bucket>()
    when (r.preset) {
        "day" -> for (h in 0 until 24) {
            val s = tsOf(r.from, LocalTime.of(h, 0))
            out += Bucket(s, s + 3600, "$h", String.format(java.util.Locale.US, "%02d:00–%02d:00", h, (h + 1) % 24))
        }
        "year" -> for (i in 0 until 12) {
            val m = prefs.addMonths(r.from, i)
            out += Bucket(dayStart(m), dayStart(prefs.addMonths(r.from, i + 1)), prefs.monthShort(m), prefs.monthTitle(m))
        }
        else -> {
            var d = r.from
            while (d < r.to) {
                out += Bucket(dayStart(d), dayStart(d.plusDays(1)), if (r.preset == "week") prefs.weekdayShort(d) else prefs.dayNum(d), prefs.dayTitle(d))
                d = d.plusDays(1)
            }
        }
    }
    val now = work.now()
    for (e in r.entries) for (bk in out) {
        val s = e.overlap(bk.start, bk.end, now)
        if (s > 0) bk.parts[e.projectId] = (bk.parts[e.projectId] ?: 0) + s
    }
    return out
}

@Composable
private fun ReportChartCard(work: WorkStore, r: ReportData) {
    val p = LocalPalette.current
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val bs = remember(r, work.projects) { buckets(work, r) }
    val n = bs.size
    var selected by remember(r) { mutableIntStateOf(bs.indexOfLast { it.total > 0 }.coerceAtLeast(0)) }
    AppCard {
        CardTitle("Hours")
        SubText(
            when (r.preset) {
                "day" -> "Per hour of the day. Tap a bar for details."
                "year" -> "Per month. Tap a bar for details."
                else -> "Per day. Tap a bar for details."
            },
        )
        Spacer(Modifier.height(6.dp))
        bs.getOrNull(selected)?.let { b ->
            val detail = if (b.total == 0L) "Nothing logged"
            else b.parts.entries.sortedByDescending { it.value }.joinToString(" · ") { "${work.projectName(it.key)} ${hrs(it.value)}" }
            Text("${b.title} · $detail", style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 2, modifier = Modifier.height(36.dp))
        }
        val axisStyle = TextStyle(fontSize = 10.sp, color = p.muted)
        Canvas(
            Modifier.fillMaxWidth().height(190.dp)
                .pointerInput(bs) {
                    val left = 34.dp.toPx()
                    detectTapGestures { o -> selected = ((o.x - left) / ((size.width - left - 4.dp.toPx()) / n)).toInt().coerceIn(0, n - 1) }
                }
                .pointerInput(bs) {
                    val left = 34.dp.toPx()
                    detectHorizontalDragGestures { change, _ ->
                        selected = ((change.position.x - left) / ((size.width - left - 4.dp.toPx()) / n)).toInt().coerceIn(0, n - 1)
                    }
                },
        ) {
            val left = 34.dp.toPx()
            val right = 4.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 20.dp.toPx()
            val iw = size.width - left - right
            val ih = size.height - top - bottom
            val maxH = max(0.25, bs.maxOf { it.total } / 3600.0) * 1.08
            val step = niceStep(maxH)
            val yTop = ceil(maxH / step) * step
            fun y(h: Double) = (top + ih - (h / yTop) * ih).toFloat()
            var v = 0.0
            while (v <= yTop + 1e-9) {
                drawLine(if (v == 0.0) p.axis else p.grid, Offset(left, y(v)), Offset(size.width - right, y(v)), 1.dp.toPx())
                label(measurer, "${fmt1(v)} h", left - 6.dp.toPx(), y(v), axisStyle, align = 1f)
                v += step
            }
            val slot = iw / n
            val bw = max(1f, min(min(24.dp.toPx(), slot * 0.7f), slot - 2.dp.toPx()))
            drawRect(ink.copy(alpha = 0.06f), topLeft = Offset(left + slot * selected, top), size = Size(slot, ih))
            val gap = 2.dp.toPx()
            bs.forEachIndexed { i, b ->
                val parts = b.parts.entries.filter { it.value > 0 }
                val x0 = left + slot * i + (slot - bw) / 2
                var acc = 0L
                parts.forEachIndexed { j, part ->
                    val y1 = y(acc / 3600.0)
                    acc += part.value
                    val y0 = y(acc / 3600.0)
                    val bottomY = y1 - if (j > 0) gap else 0f
                    if (bottomY - y0 < 0.5f) return@forEachIndexed
                    val rr = if (j == parts.lastIndex) min(4.dp.toPx(), bw / 2) else 0f
                    val path = Path().apply {
                        addRoundRect(RoundRect(Rect(x0, y0, x0 + bw, bottomY), CornerRadius(rr), CornerRadius(rr), CornerRadius.Zero, CornerRadius.Zero))
                    }
                    drawPath(path, hexColor(work.projectColor(part.key)))
                }
            }
            val every = if (n <= 12) 1 else if (n <= 24) 3 else 5
            bs.forEachIndexed { i, b ->
                if (i % every == 0) {
                    val cx = (left + slot * i + slot / 2).coerceIn(left + 8.dp.toPx(), size.width - 10.dp.toPx())
                    label(measurer, b.label, cx, size.height - bottom / 2, axisStyle, align = 0.5f)
                }
            }
        }
    }
}

@Composable
private fun ReportProjects(work: WorkStore, s: Summary) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(setOf<Long?>()) }
    AppCard {
        CardTitle("By project")
        SubText("Tap a project to see its tasks. The line marks the planned time.")
        Spacer(Modifier.height(6.dp))
        if (s.projects.isEmpty()) {
            EmptyNote("Nothing logged or planned in this period.")
            return@AppCard
        }
        val maxV = max(1L, s.projects.maxOf { max(it.secs, it.planned) })
        s.projects.forEachIndexed { i, sl ->
            if (i > 0) HorizontalDivider(color = p.grid)
            val color = hexColor(work.projectColor(sl.projectId))
            Column(Modifier.fillMaxWidth().clickable { open = if (sl.projectId in open) open - sl.projectId else open + sl.projectId }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color)
                    Spacer(Modifier.width(8.dp))
                    MixedText(work.projectName(sl.projectId), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        hrs(sl.secs) + (if (s.total > 0) " · ${(sl.secs * 100.0 / s.total).roundToInt()}%" else "") +
                            if (sl.planned > 0) " · ${fmt1(sl.planned / 3600.0)} h planned" else "",
                        style = MaterialTheme.typography.bodySmall, color = p.ink2,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surface2)) {
                    Box(Modifier.fillMaxWidth((sl.secs.toFloat() / maxV).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    if (sl.planned > 0) {
                        Box(Modifier.fillMaxWidth((sl.planned.toFloat() / maxV).coerceIn(0f, 1f)).height(6.dp)) {
                            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(6.dp).background(p.ink2))
                        }
                    }
                }
                if (sl.projectId in open) {
                    Spacer(Modifier.height(6.dp))
                    if (sl.tasks.isEmpty()) SubText("Only planned, nothing logged yet.")
                    sl.tasks.entries.sortedByDescending { it.value }.forEach { (tid, secs) ->
                        Row(Modifier.padding(start = 18.dp, top = 2.dp)) {
                            MixedText(
                                if (tid == null) "No task" else work.task(tid)?.title ?: "Finished or deleted task",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = p.ink2,
                            )
                            Text(hrs(secs), style = MaterialTheme.typography.bodySmall, color = p.ink2)
                        }
                    }
                }
            }
        }
    }
}
