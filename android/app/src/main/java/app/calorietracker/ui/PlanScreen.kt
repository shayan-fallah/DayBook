package app.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.PlanWeek
import app.calorietracker.WorkStore
import app.calorietracker.data.GoogleEvent
import app.calorietracker.data.PlanBlock
import app.calorietracker.data.TaskItem
import app.calorietracker.data.WorkPrefs
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val HOUR = 48.dp

/** What the planned-block editor is open for: an existing [block], or a new one with these defaults. */
data class BlockTarget(
    val block: PlanBlock? = null,
    val date: LocalDate = LocalDate.now(),
    val startMin: Int? = null,
    val title: String = "",
    val projectId: Long? = null,
    val taskId: Long? = null,
)

@Composable
fun PlanScreen(work: WorkStore) {
    var blockTarget by remember { mutableStateOf<BlockTarget?>(null) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(
            listOf("schedule" to "Schedule", "todo" to "To-do"), work.planTab, { work.planTab = it },
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        if (work.planTab == "todo") {
            TodoPane(work, onSchedule = { t ->
                val today = LocalDate.now()
                val d = t.due?.let { LocalDate.parse(it) }?.takeIf { it >= today } ?: today
                work.planTab = "schedule"
                work.changePlanDate(d)
                blockTarget = BlockTarget(date = d, projectId = t.projectId, taskId = t.id)
            })
        } else {
            SchedulePane(work, onOpen = { blockTarget = it })
        }
    }
    blockTarget?.let { BlockSheet(work, it) { blockTarget = null } }
}

// ---------------------------------------------------------------- schedule

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchedulePane(work: WorkStore, onOpen: (BlockTarget) -> Unit) {
    LaunchedEffect(Unit) { work.loadWeek() }
    var pickDate by remember { mutableStateOf(false) }
    var taskSheet by remember { mutableStateOf<TaskItem?>(null) }
    val p = LocalPalette.current
    val uri = LocalUriHandler.current
    val prefs = work.prefs
    val day = work.planDate
    val wk = work.week

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        NavBar(prefs.relDay(day), onPrev = { work.changePlanDate(day.minusDays(1)) },
            onNext = { work.changePlanDate(day.plusDays(1)) }, onPick = { pickDate = true })
        Spacer(Modifier.height(6.dp))
        WeekStrip(work, wk)
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
        ) {
            Column {
                val d0 = dayStart(day)
                val d1 = dayStart(day.plusDays(1))
                val now = work.now()
                val blocks = wk?.blocks.orEmpty().filter { it.endTs > d0 && it.startTs < d1 }
                val entries = wk?.entries.orEmpty().filter { it.endOr(now) > d0 && it.startTs < d1 }
                val planned = blocks.sumOf { overlap(it.startTs, it.endTs, d0, d1) }
                val logged = entries.sumOf { it.overlap(d0, d1, now) }
                Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Planned ${hrs(planned)} · Logged ${hrs(logged)}", style = MaterialTheme.typography.bodyMedium)
                        if (prefs.workGoal > 0) Text("Goal ${fmt1(prefs.workGoal)} h", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Button(onClick = { onOpen(BlockTarget(date = day)) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Plan")
                    }
                }
                // All-day: Google all-day events and to-dos due that day
                val allDay = wk?.google.orEmpty().filter { it.allDay && it.startDate != null && it.startDate <= day.toString() && day.toString() < (it.endDate ?: it.startDate) }
                val due = work.tasks.filter { !it.done && it.due == day.toString() }
                if (allDay.isNotEmpty() || due.isNotEmpty() || wk?.googleError != null) {
                    FlowRow(
                        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        allDay.forEach { g -> Chip(g.title, hexColor(g.color)) { g.link?.let { runCatching { uri.openUri(it) } } } }
                        due.forEach { t -> Chip("☐ ${t.title}", hexColor(work.projectColor(t.projectId))) { taskSheet = t } }
                        wk?.googleError?.let { Text("Google: $it", color = p.critical, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                HorizontalDivider(color = p.grid)
                if (wk == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Timeline(
                        work, day, wk, blocks, Modifier.fillMaxWidth().weight(1f),
                        onBlock = { onOpen(BlockTarget(block = it)) },
                        onEvent = { g -> g.link?.let { runCatching { uri.openUri(it) } } },
                        onEmpty = { min -> onOpen(BlockTarget(date = day, startMin = min)) },
                    )
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { work.changePlanDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    taskSheet?.let { TaskSheet(work, it, onSchedule = null) { taskSheet = null } }
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        MixedText(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WeekStrip(work: WorkStore, wk: PlanWeek?) {
    val p = LocalPalette.current
    val prefs = work.prefs
    val start = prefs.startOfWeek(work.planDate)
    val now = work.now()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val a = dayStart(d)
            val b = dayStart(d.plusDays(1))
            val planned = wk?.blocks.orEmpty().sumOf { overlap(it.startTs, it.endTs, a, b) }
            val logged = wk?.entries.orEmpty().sumOf { it.overlap(a, b, now) }
            val on = d == work.planDate
            val isToday = d == LocalDate.now()
            Surface(
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(12.dp),
                border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).clickable { work.changePlanDate(d) },
            ) {
                Column(Modifier.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(prefs.weekdayShort(d), fontSize = 11.sp, maxLines = 1)
                    Text(prefs.dayNum(d), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (isToday && !on) p.accent else Color.Unspecified)
                    Text(if (planned > 0) "${fmt1(planned / 3600.0)}h" else if (logged > 0) "·" else " ", fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

private class Laid<T>(val ref: T, val s: Long, val e: Long) {
    var lane = 0
    var lanes = 1
}

/** Side-by-side columns for items that overlap in time. */
private fun <T> layoutLanes(items: List<Laid<T>>): List<Laid<T>> {
    val sorted = items.sortedWith(compareBy<Laid<T>> { it.s }.thenByDescending { it.e })
    var cluster = mutableListOf<Laid<T>>()
    var clusterEnd = Long.MIN_VALUE
    fun flush() {
        val lanes = mutableListOf<Long>()
        for (it in cluster) {
            var i = lanes.indexOfFirst { end -> end <= it.s }
            if (i < 0) {
                i = lanes.size
                lanes += 0L
            }
            lanes[i] = it.e
            it.lane = i
        }
        for (it in cluster) it.lanes = lanes.size
        cluster = mutableListOf()
    }
    for (it in sorted) {
        if (it.s >= clusterEnd) {
            flush()
            clusterEnd = Long.MIN_VALUE
        }
        cluster += it
        clusterEnd = maxOf(clusterEnd, it.e)
    }
    flush()
    return sorted
}

@Composable
private fun Timeline(
    work: WorkStore,
    day: LocalDate,
    wk: PlanWeek,
    blocks: List<PlanBlock>,
    modifier: Modifier,
    onBlock: (PlanBlock) -> Unit,
    onEvent: (GoogleEvent) -> Unit,
    onEmpty: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR.toPx() }
    val scroll = rememberScrollState()
    val d0 = dayStart(day)
    val d1 = dayStart(day.plusDays(1))
    val now = liveNow(work, 30_000) // moves the now line and the running timer's bar
    fun y(ts: Long) = HOUR * (((ts.coerceIn(d0, d1) - d0) / 3600.0).toFloat())

    LaunchedEffect(day) {
        val first = blocks.minOfOrNull { it.startTs }?.let { ((it - d0) / 3600.0 * hourPx).toInt() - (hourPx / 4).toInt() }
        val nowY = if (day == LocalDate.now()) ((work.now() - d0) / 3600.0 * hourPx).toInt() - hourPx.toInt() else null
        val target = listOfNotNull(first, nowY, (7 * hourPx).toInt()).min()
        scroll.scrollTo(target.coerceAtLeast(0))
    }

    Box(modifier.verticalScroll(scroll)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(HOUR * 24)) {
            val width = maxWidth
            val colLeft = 50.dp
            val colWidth = width - colLeft - 22.dp
            Canvas(
                Modifier.fillMaxSize().pointerInput(day) {
                    detectTapGestures { o -> onEmpty((((o.y / hourPx) * 60).toInt() / 30 * 30).coerceIn(0, 23 * 60 + 30)) }
                },
            ) {
                for (h in 1 until 24) drawLine(p.grid, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), 1.dp.toPx())
            }
            for (h in 0 until 24) {
                Text(
                    String.format(java.util.Locale.US, "%02d:00", h), fontSize = 10.sp, color = p.muted,
                    modifier = Modifier.offset(x = 6.dp, y = HOUR * h + 2.dp),
                )
            }
            val items = mutableListOf<Laid<Any>>()
            blocks.forEach { items += Laid<Any>(it, maxOf(it.startTs, d0), minOf(it.endTs, d1)) }
            wk.google.filter { !it.allDay && it.startTs != null && it.endTs != null && it.endTs > d0 && it.startTs < d1 }
                .forEach { items += Laid<Any>(it, maxOf(it.startTs!!, d0), minOf(it.endTs!!, d1)) }
            for (it in layoutLanes(items)) {
                val top = y(it.s)
                val h = maxOf(18.dp, y(it.e) - top - 2.dp)
                val laneW = colWidth / it.lanes
                val x = colLeft + laneW * it.lane
                when (val ref = it.ref) {
                    is PlanBlock -> TimelineItem(
                        title = work.titleOf(ref.title, ref.projectId, ref.taskId),
                        sub = "${hm(ref.startTs)}–${hm(ref.endTs)}" + when (ref.gcal) {
                            "synced" -> " ✓"
                            "pending" -> " ↻"
                            "error" -> " !"
                            else -> ""
                        },
                        color = hexColor(work.projectColor(ref.projectId)), striped = false,
                        modifier = Modifier.offset(x = x, y = top).width(laneW - 3.dp).height(h),
                        short = h < 34.dp,
                    ) { onBlock(ref) }
                    is GoogleEvent -> TimelineItem(
                        title = ref.title, sub = "${hm(ref.startTs!!)}–${hm(ref.endTs!!)} · ${ref.calendar}",
                        color = hexColor(ref.color), striped = true,
                        modifier = Modifier.offset(x = x, y = top).width(laneW - 3.dp).height(h),
                        short = h < 34.dp,
                    ) { onEvent(ref) }
                }
            }
            // What you actually logged, as thin bars on the right
            wk.entries.filter { it.endOr(now) > d0 && it.startTs < d1 }.forEach { e ->
                val top = y(e.startTs)
                Box(
                    Modifier.offset(x = width - 14.dp, y = top).width(8.dp).height(maxOf(3.dp, y(e.endOr(now)) - top))
                        .clip(RoundedCornerShape(3.dp)).background(hexColor(work.projectColor(e.projectId)).copy(alpha = 0.85f)),
                )
            }
            if (day == LocalDate.now()) {
                Box(Modifier.offset(x = 44.dp, y = y(now) - 1.dp).fillMaxWidth().height(2.dp).background(p.critical))
                Box(Modifier.offset(x = 40.dp, y = y(now) - 5.dp).size(10.dp).clip(CircleShape).background(p.critical))
            }
        }
    }
}

@Composable
private fun TimelineItem(title: String, sub: String, color: Color, striped: Boolean, modifier: Modifier, short: Boolean, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = if (striped) 0.12f else 0.22f).compositeOver(surface))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        if (short) {
            Row(Modifier.padding(horizontal = 6.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                MixedText(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(sub, fontSize = 10.sp, color = LocalPalette.current.ink2, maxLines = 1)
            }
        } else {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                MixedText(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (striped) FontWeight.Normal else FontWeight.Medium))
                Text(sub, fontSize = 10.sp, color = LocalPalette.current.ink2, maxLines = 1)
            }
        }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

// ---------------------------------------------------------------- planned block editor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockSheet(work: WorkStore, target: BlockTarget, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val b = target.block
    val startDefault = remember {
        val d = target.date
        target.startMin ?: if (d == LocalDate.now()) {
            val n = LocalTime.now()
            minOf(23 * 60, ((n.hour * 60 + n.minute + 1 + 29) / 30) * 30)
        } else 9 * 60
    }
    var title by remember { mutableStateOf(b?.title ?: target.title) }
    var pid by remember { mutableStateOf(b?.projectId ?: target.projectId) }
    var tid by remember { mutableStateOf(b?.taskId ?: target.taskId) }
    var date by remember { mutableStateOf(b?.let { dateOfTs(it.startTs) } ?: target.date) }
    var start by remember { mutableStateOf(b?.let { timeOfTs(it.startTs) } ?: LocalTime.of(startDefault / 60, startDefault % 60)) }
    var end by remember {
        mutableStateOf(b?.let { timeOfTs(it.endTs) } ?: (startDefault + 60).coerceAtMost(24 * 60 - 1).let { LocalTime.of(it / 60, it % 60) })
    }
    var notes by remember { mutableStateOf(b?.notes ?: "") }
    var repeat by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("4") }
    var askDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (b == null) "Plan time" else "Planned time", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                title, { title = it }, label = { Text("Title") }, placeholder = { Text("Optional if you pick a project") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            ProjectTaskPickers(work, pid, tid) { np, nt -> pid = np; tid = nt }
            DateField("Date", date, work.prefs.dayTitle(date)) { it?.let { d -> date = d } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeField("Start", start, Modifier.weight(1f)) { start = it }
                TimeField("End", end, Modifier.weight(1f)) { end = it }
            }
            if (b == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picker(
                        "Repeat",
                        listOf("" to "Doesn't repeat", "daily" to "Every day", "weekdays" to "Every work day", "weekly" to "Every week"),
                        repeat, Modifier.weight(1.4f),
                    ) { repeat = it }
                    if (repeat.isNotEmpty()) NumberField(count, { count = it }, "Times", Modifier.weight(1f), decimal = false)
                }
            }
            OutlinedTextField(
                notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            val (a, e) = spanOf(date, start, end)
            val hint = buildList {
                add(spanHint(a, e, work.now()))
                when (b?.gcal) {
                    "synced" -> add("In Google Calendar.")
                    "pending" -> add("Sending to Google Calendar…")
                    "error" -> add("Google Calendar: ${b.gcalError}")
                }
                if (b?.series != null) add("Part of a repeating plan.")
            }
            SubText(hint.joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (b != null) {
                    OutlinedButton(
                        onClick = { askDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                    OutlinedButton(onClick = {
                        work.startTimer(b.title, b.projectId, b.taskId)
                        close()
                    }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text("Start")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy && (title.isNotBlank() || pid != null || tid != null), modifier = Modifier.height(48.dp),
                    onClick = {
                        val end2 = e ?: return@Button
                        var repeatStarts: List<Long> = emptyList()
                        if (b == null && repeat.isNotEmpty()) {
                            val n = (count.toIntOrNull() ?: 4).coerceIn(2, 60)
                            val list = mutableListOf<Long>()
                            var d = date
                            while (list.size < n - 1) {
                                d = d.plusDays(if (repeat == "weekly") 7 else 1)
                                if (repeat == "weekdays" && !work.prefs.isWorkDay(d)) continue
                                list += tsOf(d, start)
                            }
                            repeatStarts = list
                        }
                        val body = jsonBody(
                            "title" to title.trim(), "project_id" to pid, "task_id" to tid, "notes" to notes.trim(),
                            "start_ts" to a, "end_ts" to end2,
                            *(if (repeatStarts.isNotEmpty()) arrayOf<Pair<String, Any?>>("repeat_starts" to repeatStarts) else emptyArray()),
                        )
                        busy = true
                        scope.launch {
                            val ok = work.saveBlock(b?.id, body)
                            busy = false
                            if (ok) {
                                if (b == null) work.planDate = date
                                close()
                            }
                        }
                    },
                ) { Text(if (b == null) "Add" else "Save") }
            }
            if (title.isBlank() && pid == null && tid == null) SubText("Give it a title or pick a project.", color = LocalPalette.current.muted)
        }
    }
    if (askDelete && b != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete this planned time?") },
            text = { if (b.series != null) Text("It's part of a repeating plan.") },
            confirmButton = {
                Row {
                    if (b.series != null) {
                        TextButton(onClick = {
                            askDelete = false
                            scope.launch { if (work.deleteBlock(b, following = true)) close() }
                        }) { Text("This and later") }
                    }
                    TextButton(onClick = {
                        askDelete = false
                        scope.launch { if (work.deleteBlock(b, following = false)) close() }
                    }) { Text(if (b.series != null) "Only this" else "Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- to-dos

@Composable
private fun TodoPane(work: WorkStore, onSchedule: (TaskItem) -> Unit) {
    LaunchedEffect(Unit) {
        work.loadTasks()
        work.loadProjects()
    }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(work.newTaskRequest) {
        if (work.newTaskRequest) {
            creating = true
            work.newTaskRequest = false
        }
    }
    val p = LocalPalette.current
    val prefs = work.prefs
    val filterPid = work.todoFilter.removePrefix("p").toLongOrNull()
    val list = work.tasks.filter {
        when (work.todoFilter) {
            "all" -> true
            "none" -> it.projectId == null
            else -> it.projectId == filterPid
        }
    }
    val today = LocalDate.now().toString()
    val open = list.filter { !it.done }
    val groups = listOf(
        "Overdue" to open.filter { it.due != null && it.due < today },
        "Today" to open.filter { it.due == today },
        "Upcoming" to open.filter { it.due != null && it.due > today },
        "Someday" to open.filter { it.due == null }.sortedWith(compareByDescending<TaskItem> { it.priority }.thenBy { it.id }),
        "Done recently" to list.filter { it.done }.sortedByDescending { it.doneAt ?: 0 },
    ).filter { it.second.isNotEmpty() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { QuickAdd(work, filterPid) }
        item {
            Picker(
                "Show",
                listOf("all" to "All to-dos", "none" to "Without a project") +
                    work.projects.filter { !it.archived || it.id == filterPid }.map { "p${it.id}" to it.name },
                work.todoFilter,
            ) { work.todoFilter = it }
        }
        if (groups.isEmpty()) {
            item { EmptyNote(if (list.isEmpty()) "Nothing to do yet. Add your first to-do above." else "All done.") }
        }
        groups.forEach { (name, items) ->
            item(key = name) {
                AppCard(padding = 0.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                            color = if (name == "Overdue") p.critical else MaterialTheme.colorScheme.onSurface)
                        Text("${items.size}", color = p.ink2)
                    }
                    items.forEach { t ->
                        HorizontalDivider(color = p.grid)
                        TaskRow(work, t, onOpen = { editing = t }, onSchedule = { onSchedule(t) })
                    }
                }
            }
        }
        item {
            TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ New to-do with details") }
        }
    }
    editing?.let { TaskSheet(work, it, onSchedule = { onSchedule(it) }) { editing = null } }
    if (creating) TaskSheet(work, null, onSchedule = null, defaultProject = filterPid) { creating = false }
}

@Composable
private fun QuickAdd(work: WorkStore, filterPid: Long?) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var pid by remember(filterPid) { mutableStateOf(filterPid) }
    fun add() {
        val t = title.trim()
        if (t.isEmpty()) return
        scope.launch {
            if (work.saveTask(null, jsonBody("title" to t, "due" to due?.toString(), "project_id" to pid))) title = ""
        }
    }
    AppCard(padding = 12.dp) {
        OutlinedTextField(
            title, { title = it }, placeholder = { Text("Add a to-do…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField("Due", due, work.prefs.shortDay(due ?: LocalDate.now()), Modifier.weight(1f), allowClear = true) { due = it }
            Picker(
                "Project", listOf("" to "No project") + work.projects.filter { !it.archived }.map { it.id.toString() to it.name },
                pid?.toString() ?: "", Modifier.weight(1f),
            ) { pid = it.toLongOrNull() }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            focus.clearFocus()
            add()
        }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add") }
    }
}

@Composable
private fun TaskRow(work: WorkStore, t: TaskItem, onOpen: () -> Unit, onSchedule: () -> Unit) {
    val p = LocalPalette.current
    val prefs = work.prefs
    val project = work.project(t.projectId)
    val sub = buildList {
        project?.let { add(isolate(it.name)) }
        t.due?.let { add(prefs.dueText(it)) }
        if (t.priority > 0) add("${PRIORITY_NAMES[t.priority]} priority")
        t.remindAt?.takeIf { !t.done && it > work.now() }?.let { add("⏰ ${prefs.remindText(it)}") }
        if (t.total > 0) add("${hrs(t.total)} logged")
        if (t.notes.isNotBlank()) add("✎ notes")
    }
    val late = !t.done && t.due != null && t.due < LocalDate.now().toString()
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.padding(start = 12.dp).size(40.dp).clip(CircleShape).clickable { work.toggleTask(t) },
            Alignment.Center,
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (t.done) p.good else Color.Transparent)
                    .border(2.dp, if (t.done) p.good else p.axis, CircleShape),
                Alignment.Center,
            ) { if (t.done) Icon(Icons.Filled.Check, "Done", tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp, horizontal = 4.dp)) {
            Text(
                t.title, maxLines = 3,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Left),
                color = if (t.done) p.muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            if (sub.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (project != null) {
                        Dot(hexColor(project.color), size = 7)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        color = if (late) p.critical else if (t.priority == 3 && !t.done) p.carbs else p.muted,
                    )
                }
            }
        }
        if (!t.done) {
            IconButton(onClick = onSchedule) { Icon(Icons.Filled.DateRange, "Plan time for it", tint = p.ink2) }
            IconButton(onClick = { work.startTimer("", t.projectId, t.id) }) { Icon(Icons.Filled.PlayArrow, "Start timer", tint = p.accent) }
        }
    }
}

/** Today: the next full hour; another day: 9:00. */
private fun defaultRemindTime(d: LocalDate): LocalTime =
    if (d == LocalDate.now()) LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0) else LocalTime.of(9, 0)

private fun WorkPrefs.remindText(ts: Long): String {
    val d = dateOfTs(ts)
    val today = LocalDate.now()
    return when (d) {
        today -> hm(ts)
        today.plusDays(1) -> "Tomorrow ${hm(ts)}"
        else -> "${shortDay(d)} ${hm(ts)}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSheet(work: WorkStore, task: TaskItem?, onSchedule: ((TaskItem) -> Unit)?, defaultProject: Long? = null, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(task?.title ?: "") }
    var pid by remember { mutableStateOf(task?.projectId ?: defaultProject) }
    var due by remember { mutableStateOf(task?.due?.let { LocalDate.parse(it) }) }
    var priority by remember { mutableStateOf((task?.priority ?: 0).toString()) }
    var notes by remember { mutableStateOf(task?.notes ?: "") }
    var done by remember { mutableStateOf(task?.done ?: false) }
    var remindDate by remember { mutableStateOf(task?.remindAt?.let { dateOfTs(it) }) }
    var remindTime by remember { mutableStateOf(task?.remindAt?.let { timeOfTs(it) }) }
    LaunchedEffect(Unit) { if (work.telegram == null) work.loadTelegram(quiet = true) }
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
            Text(if (task == null) "New to-do" else "To-do", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker(
                    "Project",
                    listOf("" to "No project") + work.projects.filter { !it.archived || it.id == pid }.map { it.id.toString() to it.name },
                    pid?.toString() ?: "", Modifier.weight(1f),
                ) { pid = it.toLongOrNull() }
                DateField("Due", due, work.prefs.shortDay(due ?: LocalDate.now()), Modifier.weight(1f), allowClear = true) { due = it }
            }
            Picker("Priority", PRIORITY_NAMES.mapIndexed { i, n -> i.toString() to n }, priority) { priority = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField(
                    "Remind me", remindDate, work.prefs.shortDay(remindDate ?: LocalDate.now()), Modifier.weight(1f),
                    allowClear = true,
                ) { d ->
                    remindDate = d
                    if (d != null && remindTime == null) remindTime = defaultRemindTime(d)
                }
                TimeField("At", remindTime, Modifier.weight(1f), enabled = remindDate != null) { remindTime = it }
            }
            if (remindDate != null && work.telegram?.linked == false) {
                SubText("Reminders arrive in Telegram. Set it up in Settings → Telegram.", color = LocalPalette.current.muted)
            }
            OutlinedTextField(
                notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            if (task != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Done", modifier = Modifier.weight(1f))
                    Switch(done, { done = it })
                }
                if (task.total > 0) SubText("${hrs(task.total)} logged on it.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                    if (onSchedule != null && !task.done) {
                        OutlinedButton(onClick = {
                            close()
                            onSchedule(task)
                        }) { Text("Schedule") }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy && title.isNotBlank(), modifier = Modifier.height(48.dp),
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = work.saveTask(
                                task?.id,
                                jsonBody(
                                    "title" to title.trim(), "project_id" to pid, "due" to due?.toString(),
                                    "priority" to priority.toInt(), "notes" to notes, "done" to done,
                                    "remind_at" to remindDate?.let { tsOf(it, remindTime ?: defaultRemindTime(it)) },
                                ),
                            )
                            busy = false
                            if (ok) close()
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
    if (confirmDelete && task != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${task.title}”?") },
            text = { Text("Time logged on it stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (work.deleteTask(task)) close() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
