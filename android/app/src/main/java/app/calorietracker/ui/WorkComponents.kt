package app.calorietracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.WorkStore
import app.calorietracker.data.TimeEntry
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// ---------------------------------------------------------------- icons (the core set has no clock or "more")

private fun strokeIcon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    .build()

val TimerIcon: ImageVector by lazy {
    strokeIcon("Timer", "M12,5a8,8 0 1,0 0.01,0z M12,9v4l2.5,2.5 M9.5,2.5h5")
}

val MoreIcon: ImageVector by lazy {
    ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M3.5,12a1.6,1.6 0 1,0 3.2,0a1.6,1.6 0 1,0 -3.2,0z M10.4,12a1.6,1.6 0 1,0 3.2,0a1.6,1.6 0 1,0 -3.2,0z M17.3,12a1.6,1.6 0 1,0 3.2,0a1.6,1.6 0 1,0 -3.2,0z"),
            fill = SolidColor(Color.Black),
        )
        .build()
}

val PlanIcon: ImageVector by lazy {
    strokeIcon("Plan", "M4,5h16v15h-16z M4,9h16 M8,3v4 M16,3v4 M8,13h3 M8,16h6")
}

val FoodIcon: ImageVector by lazy {
    strokeIcon("Food", "M12,8c-3,-3 -8,-1 -7,5s4,8 7,6c3,2 6,0 7,-6s-4,-8 -7,-5z M12,8c0,-2 1,-4 3,-5")
}

// ---------------------------------------------------------------- small pieces

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Text that may be Persian: right order, left-aligned like the rest of the UI. */
@Composable
fun MixedText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    Text(
        text, modifier = modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = style.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Left),
    )
}

/** Segmented sub-tabs, e.g. Timer | Projects | Reports. */
@Composable
fun SubTabs(tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        tabs.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = selected == key, onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, tabs.size), icon = {},
            ) { Text(label, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
fun NavBar(label: String, onPrev: () -> Unit, onNext: () -> Unit, onPick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        Text(
            label,
            modifier = Modifier.weight(1f).let { if (onPick != null) it.clickable(onClick = onPick) else it }.padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
            maxLines = 1,
        )
        FilledTonalIconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
    }
}

/** A drop-down to pick one of [options] (key to label). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Picker(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text, fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        )
                    },
                    onClick = {
                        onSelect(key)
                        open = false
                    },
                )
            }
        }
    }
}

/** Project and task pickers that stay in step: picking a task picks its project. */
@Composable
fun ProjectTaskPickers(work: WorkStore, projectId: Long?, taskId: Long?, onChange: (Long?, Long?) -> Unit) {
    val projects = work.projects.filter { !it.archived || it.id == projectId }
    val tasks = work.tasks.filter {
        (!it.done || it.id == taskId) && (projectId == null || it.projectId == null || it.projectId == projectId)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Picker(
            "Project",
            listOf("" to "No project") + projects.map { it.id.toString() to it.name + if (it.archived) " (archived)" else "" },
            projectId?.toString() ?: "",
            Modifier.weight(1f),
        ) { key ->
            val pid = key.toLongOrNull()
            val t = work.task(taskId)
            onChange(pid, if (t != null && (t.projectId == null || t.projectId == pid)) taskId else null)
        }
        Picker(
            "Task",
            listOf("" to "No task") + tasks.map { it.id.toString() to it.title },
            taskId?.toString() ?: "",
            Modifier.weight(1f),
        ) { key ->
            val tid = key.toLongOrNull()
            val t = work.task(tid)
            onChange(if (t?.projectId != null) t.projectId else projectId, tid)
        }
    }
}

/** A field that opens a date picker. [allowClear] adds a ✕ to remove the date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    display: String,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
    onPick: (LocalDate?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = if (date == null) "" else display, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label, maxLines = 1) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("None") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            trailingIcon = if (allowClear && date != null) {
                { IconButton(onClick = { onPick(null) }) { Icon(Icons.Filled.Clear, "Remove the date") } }
            } else null,
        )
        Box(Modifier.matchParentSize().padding(end = if (allowClear && date != null) 48.dp else 0.dp).clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** A field that opens a 24-hour time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, time: LocalTime?, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = time?.let { hm(it) } ?: "", onValueChange = {}, readOnly = true, singleLine = true, enabled = enabled,
            label = { Text(label, maxLines = 1) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (enabled) "--:--" else "now") },
        )
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

/** Seconds since [since] plus [base], updated every second. */
@Composable
fun liveSeconds(work: WorkStore, since: Long?, base: Long = 0): Long {
    var now by remember { mutableLongStateOf(work.now()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = work.now()
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    return base + if (since != null) (now - since).coerceAtLeast(0) else 0
}

/** The current time (server clock), refreshed every [periodMs]. */
@Composable
fun liveNow(work: WorkStore, periodMs: Long): Long {
    var now by remember { mutableLongStateOf(work.now()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = work.now()
            delay(periodMs)
        }
    }
    return now
}

/** The running timer, shown above the tab bar on other screens. */
@Composable
fun TimerBar(work: WorkStore, running: TimeEntry, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val secs = liveSeconds(work, running.startTs)
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(hexColor(work.projectColor(running.projectId)))
                Spacer(Modifier.width(10.dp))
                MixedText(
                    work.titleOf(running.description, running.projectId, running.taskId), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(clock(secs), fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { work.stopTimer() }) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(p.critical), Alignment.Center) {
                    Box(Modifier.size(11.dp).background(Color.White))
                }
            }
        }
    }
}
