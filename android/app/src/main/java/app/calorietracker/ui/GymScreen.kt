package app.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.ActivityInfo
import app.calorietracker.data.Day
import app.calorietracker.data.Workout
import app.calorietracker.data.WorkoutMeta
import app.calorietracker.data.WorkoutStats
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A dumbbell for the Gym tab (the core icon set has none). */
val DumbbellIcon: ImageVector by lazy {
    ImageVector.Builder("Dumbbell", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M5,6.5h3v11h-3z M16,6.5h3v11h-3z M2,9h2.2v6h-2.2z M19.8,9h2.2v6h-2.2z M8,11h8v2h-8z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}

private val KINDS = listOf("strength" to "Strength", "cardio" to "Cardio", "other" to "Sports & other")
private val INTENSITY_HINT = mapOf(
    "strength" to "Easy: light weights, long rests. Hard: heavy sets, supersets, short rests.",
    "cardio" to "Easy: you could sing. Moderate: you can still talk. Hard: only a few words at a time.",
    "other" to "How hard it felt overall.",
)
private val RANGES = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year")

/** Which workout the form is open for: a new one on [date], or an existing [workout]. */
data class WorkoutTarget(val workout: Workout?, val date: String)

@Composable
fun kindColor(kind: String): Color {
    val p = LocalPalette.current
    return when (kind) {
        "strength" -> p.protein
        "cardio" -> p.carbs
        else -> p.fat
    }
}

// ---------------------------------------------------------------- shared rows and the Today card

@Composable
fun WorkoutRow(w: Workout, withDate: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(kindColor(w.kind))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FoodName(w.name)
            Text(
                workoutSub(w, withDate),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content, textAlign = TextAlign.Left),
                color = p.muted,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(fmt(w.kcal.toDouble()), fontWeight = FontWeight.SemiBold)
        Text(" kcal", style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
fun WorkoutsCard(day: Day, onOpen: (Workout?) -> Unit) {
    val p = LocalPalette.current
    val list = day.workouts
    val total = list.sumOf { it.minutes ?: 0 }
    AppCard(padding = 0.dp) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Workouts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) {
                Text(
                    (if (total > 0) "${duration(total)} · " else "") + "${fmt(day.burned.toDouble())} kcal",
                    color = p.ink2, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(10.dp))
            }
            Button(onClick = { onOpen(null) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        if (list.isEmpty()) {
            Text(
                "No workout logged. Rest day?",
                color = p.muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        } else {
            list.forEach { w ->
                HorizontalDivider(color = p.grid)
                WorkoutRow(w) { onOpen(w) }
            }
            if (day.exercisePct < 100) {
                HorizontalDivider(color = p.grid)
                SubText(
                    if (day.exercisePct > 0) "Half of it (${fmt(day.exercise.toDouble())} kcal) is added to your goal."
                    else "Not added to your calorie goal (see Settings).",
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = p.muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Gym tab

@Composable
fun GymScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadGym() }
    var sheet by remember { mutableStateOf<WorkoutTarget?>(null) }
    val s = vm.gym
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Button(
                onClick = { sheet = WorkoutTarget(null, LocalDate.now().toString()) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Log workout")
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RANGES.forEach { (n, label) ->
                    FilterChip(selected = vm.gymRange == n, onClick = { vm.loadGym(n) }, label = { Text(label) })
                }
            }
        }
        if (s == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item { GymTiles(s) }
        item { TrainingCard(s) }
        item { MusclesCard(s) }
        item { ActivitiesCard(s) }
        item {
            AppCard(padding = 0.dp) {
                Text("Workout log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                if (s.workouts.isEmpty()) {
                    EmptyNote("No workouts in this period.")
                } else {
                    s.workouts.forEach { w ->
                        HorizontalDivider(color = LocalPalette.current.grid)
                        WorkoutRow(w, withDate = true) { sheet = WorkoutTarget(w, w.date) }
                    }
                }
            }
        }
    }
    sheet?.let { t -> WorkoutSheet(vm, t.workout, t.date) { sheet = null } }
}

@Composable
private fun GymTiles(s: WorkoutStats) {
    val t = s.totals
    val wk = s.week
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(s.start), LocalDate.parse(s.end)) + 1
    val weeks = max(1.0, days / 7.0)
    val left = wk.goal - wk.days
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Last 7 days", "${wk.days}", "/ ${wk.goal} days",
                if (left > 0) "$left more for your goal" else "Weekly goal reached", Modifier.weight(1f))
            Tile("Goal streak", "${s.goalStreak}", if (s.goalStreak == 1) "week" else "weeks",
                "Weeks with ${wk.goal}+ days", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Training time", fmt1(t.minutes / 60.0 / weeks), "h / week",
                "${hoursText(t.minutes)} in ${t.sessions} session${if (t.sessions == 1) "" else "s"}", Modifier.weight(1f))
            Tile("Burned", fmt(t.kcal.toDouble()), "kcal",
                if (t.sessions > 0) "About ${fmt(t.kcal.toDouble() / t.sessions)} per session" else "No workouts yet", Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrainingCard(s: WorkoutStats) {
    val p = LocalPalette.current
    val t = s.totals
    AppCard {
        CardTitle("Training time")
        SubText(if (s.bucketDays == 1) "Hours per day. Tap a bar for details." else "Hours per week. Tap a bar for details.")
        Spacer(Modifier.height(8.dp))
        if (t.minutes == 0) {
            EmptyNote("No workouts in this period.")
            return@AppCard
        }
        TrainingChart(s)
        Spacer(Modifier.height(10.dp))
        listOf("strength" to "Strength", "cardio" to "Cardio", "other" to "Other").forEach { (k, label) ->
            val v = when (k) {
                "strength" -> t.strength
                "cardio" -> t.cardio
                else -> t.other
            }
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(kindColor(k))
                Spacer(Modifier.width(8.dp))
                Text(label, modifier = Modifier.weight(1f))
                Text("${hoursText(v)} · ${(v * 100.0 / t.minutes).roundToInt()}%", color = p.ink2, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TrainingChart(s: WorkoutStats) {
    val p = LocalPalette.current
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val bs = s.buckets
    val n = bs.size
    var selected by remember(s) { mutableIntStateOf(bs.indexOfLast { it.sessions > 0 }.coerceAtLeast(0)) }
    val colors = listOf(p.protein, p.carbs, p.fat)

    bs.getOrNull(selected)?.let { b ->
        val title = if (b.start == b.end) longDate(b.start) else "${shortDate(b.start)} – ${shortDate(b.end)}"
        val detail = if (b.sessions == 0) "Rest" else buildList {
            if (b.strength > 0) add("Strength ${duration(b.strength)}")
            if (b.cardio > 0) add("Cardio ${duration(b.cardio)}")
            if (b.other > 0) add("Other ${duration(b.other)}")
        }.joinToString(" · ") + "\n${b.sessions} session${if (b.sessions == 1) "" else "s"} · ${fmt(b.kcal.toDouble())} kcal"
        Text("$title · $detail", style = MaterialTheme.typography.bodySmall, color = ink, modifier = Modifier.height(36.dp))
    }

    val axisStyle = TextStyle(fontSize = 10.sp, color = p.muted)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(bs) {
                val left = 34.dp.toPx()
                detectTapGestures { o ->
                    val slot = (size.width - left - 4.dp.toPx()) / n
                    selected = ((o.x - left) / slot).toInt().coerceIn(0, n - 1)
                }
            }
            .pointerInput(bs) {
                val left = 34.dp.toPx()
                detectHorizontalDragGestures { change, _ ->
                    val slot = (size.width - left - 4.dp.toPx()) / n
                    selected = ((change.position.x - left) / slot).toInt().coerceIn(0, n - 1)
                }
            },
    ) {
        val left = 34.dp.toPx()
        val right = 4.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = 20.dp.toPx()
        val iw = size.width - left - right
        val ih = size.height - top - bottom
        val maxH = max(1.0, bs.maxOf { it.minutes } / 60.0) * 1.08
        val step = niceStep(maxH)
        val yTop = ceil(maxH / step) * step
        fun y(hours: Double) = (top + ih - (hours / yTop) * ih).toFloat()

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
            val parts = listOf(b.strength, b.cardio, b.other).mapIndexed { k, m -> k to m }.filter { it.second > 0 }
            val x0 = left + slot * i + (slot - bw) / 2
            var acc = 0
            parts.forEachIndexed { j, (k, m) ->
                val y1 = y(acc / 60.0)
                acc += m
                val y0 = y(acc / 60.0)
                val bottomY = y1 - if (j > 0) gap else 0f
                if (bottomY - y0 < 0.5f) return@forEachIndexed
                val r = if (j == parts.lastIndex) min(4.dp.toPx(), bw / 2) else 0f
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(x0, y0, x0 + bw, bottomY),
                            topLeft = CornerRadius(r), topRight = CornerRadius(r),
                            bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, colors[k])
            }
        }

        val every = when {
            n <= 7 -> 1
            n <= 14 -> 4
            n <= 31 -> 7
            else -> 9
        }
        bs.forEachIndexed { i, b ->
            if ((n - 1 - i) % every == 0) {
                val text = if (s.bucketDays == 1 && n <= 7) weekday(b.start) else shortDate(b.start)
                val cx = (left + slot * i + slot / 2).coerceIn(left + 14.dp.toPx(), size.width - 16.dp.toPx())
                label(measurer, text, cx, size.height - bottom / 2, axisStyle, align = 0.5f)
            }
        }
    }
}

@Composable
private fun ColorBarRow(name: String, value: String, fraction: Float, color: Color, strong: Boolean = false) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FoodName(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                value, style = MaterialTheme.typography.bodySmall,
                color = if (strong) MaterialTheme.colorScheme.onSurface else p.ink2,
                fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(4.dp))
        Meter(fraction, color, height = 6.dp)
    }
}

@Composable
private fun MusclesCard(s: WorkoutStats) {
    val p = LocalPalette.current
    AppCard {
        CardTitle("Muscle groups")
        SubText("When you last trained each one, and how often in this period.")
        Spacer(Modifier.height(4.dp))
        val maxN = max(1, s.muscles.maxOfOrNull { it.sessions } ?: 1)
        s.muscles.forEach { m ->
            val due = m.daysAgo == null || m.daysAgo >= 7
            ColorBarRow(m.name, "${agoText(m.daysAgo)} · ${m.sessions}×", m.sessions.toFloat() / maxN, p.protein, strong = due)
        }
    }
}

@Composable
private fun ActivitiesCard(s: WorkoutStats) {
    AppCard {
        CardTitle("Activities")
        if (s.byActivity.isEmpty()) {
            EmptyNote("No workouts in this period.")
            return@AppCard
        }
        val maxM = max(1, s.byActivity.first().minutes)
        s.byActivity.forEach { a ->
            ColorBarRow(
                a.name,
                "${if (a.minutes > 0) duration(a.minutes) else "–"} · ${a.sessions}× · ${fmt(a.kcal.toDouble())} kcal",
                a.minutes.toFloat() / maxM,
                kindColor(a.kind),
            )
        }
    }
}

// ---------------------------------------------------------------- add / edit sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSheet(vm: AppViewModel, workout: Workout?, date: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (vm.workoutMeta == null) vm.ensureWorkoutMeta() else vm.loadWorkoutMeta()
    }
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val meta = vm.workoutMeta
        if (meta == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
        } else {
            WorkoutForm(vm, meta, workout, date, onDone = { close() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutForm(vm: AppViewModel, meta: WorkoutMeta, workout: Workout?, date: String, onDone: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    fun activityOf(key: String): ActivityInfo = meta.activities.firstOrNull { it.key == key } ?: meta.activities.last()

    var activity by remember { mutableStateOf("bodybuilding") }
    var intensity by remember { mutableStateOf("moderate") }
    var muscles by remember { mutableStateOf(setOf<String>()) }
    var name by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("1") }
    var mins by remember { mutableStateOf("0") }
    var distance by remember { mutableStateOf("") }
    var kcalText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun fill(src: Workout, editing: Boolean) {
        val a = activityOf(src.activity)
        activity = a.key
        intensity = src.intensity
        muscles = src.muscles.toSet()
        name = if (a.key == "other") src.name else ""
        hours = src.minutes?.let { (it / 60).toString() } ?: ""
        mins = src.minutes?.let { (it % 60).toString() } ?: ""
        distance = src.distanceKm?.trimmed() ?: ""
        kcalText = if (editing && src.kcalManual) src.kcal.toString() else ""
        notes = if (editing) src.notes else ""
        error = null
    }
    LaunchedEffect(workout) { if (workout != null) fill(workout, true) }

    val a = activityOf(activity)
    val minutes: Int? = run {
        if (hours.isBlank() && mins.isBlank()) return@run null
        val h = if (hours.isBlank()) 0.0 else parseNum(hours) ?: return@run -1
        val m = if (mins.isBlank()) 0.0 else parseNum(mins) ?: return@run -1
        (h * 60 + m).roundToInt()
    }
    val kg = meta.weight ?: meta.defaultWeight
    val estimate = if (minutes != null && minutes > 0) {
        val met = a.met.getOrElse(INTENSITY_INDEX[intensity] ?: 1) { 5.0 }
        ((met - 1) * kg * minutes / 60).roundToInt()
    } else null
    val typedKcal = if (kcalText.isBlank()) null else parseNum(kcalText)

    fun save() {
        val keepNoDuration = workout != null && workout.minutes == null && minutes == null
        if (!keepNoDuration && (minutes == null || minutes < 1 || minutes > 1440)) {
            error = "Enter how long you trained."
            return
        }
        if (kcalText.isNotBlank() && (typedKcal == null || typedKcal < 0)) {
            error = "Check the calories."
            return
        }
        val km = if (distance.isBlank()) null else parseNum(distance)
        if (a.distance && distance.isNotBlank() && (km == null || km < 0)) {
            error = "Check the distance."
            return
        }
        val fields = mutableListOf<Pair<String, Any?>>(
            "date" to date, "activity" to a.key, "intensity" to intensity, "notes" to notes.trim(),
            "muscles" to meta.muscles.map { it.key }.filter { it in muscles }, "kcal" to typedKcal,
        )
        if (!keepNoDuration) fields += "minutes" to minutes
        if (a.key == "other") fields += "name" to name.trim()
        if (a.distance) fields += "distance_km" to km
        busy = true
        error = null
        scope.launch {
            val kcal = vm.saveWorkout(workout?.id, jsonBody(*fields.toTypedArray()))
            busy = false
            if (kcal != null) {
                vm.toast(if (workout != null) "Workout saved" else "Logged ${if (a.key == "other") name.trim() else a.name} · ${fmt(kcal.toDouble())} kcal")
                onDone()
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val today = LocalDate.now().toString()
        Text(
            when {
                workout != null -> "Edit workout · ${shortDate(workout.date)}"
                date == today -> "Log workout"
                else -> "Log workout · ${shortDate(date)}"
            },
            style = MaterialTheme.typography.titleLarge,
        )

        val recent = if (workout == null) meta.recent.take(4) else emptyList()
        if (recent.isNotEmpty()) {
            Column {
                SubText("Repeat a recent workout")
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { r ->
                        val label = buildString {
                            append(isolate(r.name))
                            append(" · ").append(duration(r.minutes))
                            if (r.muscles.isNotEmpty()) append(" · ").append(r.muscles.joinToString(", ") { MUSCLE_NAME[it] ?: it })
                        }
                        FilterChip(selected = false, onClick = { fill(r, false) }, label = { Text(label, maxLines = 1) })
                    }
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KINDS.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = a.kind == key,
                    onClick = { if (a.kind != key) activity = meta.activities.first { it.kind == key }.key },
                    shape = SegmentedButtonDefaults.itemShape(i, KINDS.size),
                    icon = {},
                ) { Text(label, fontSize = 13.sp, maxLines = 1) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            meta.activities.filter { it.kind == a.kind }.forEach { x ->
                FilterChip(
                    selected = x.key == activity,
                    onClick = { activity = x.key },
                    label = { Text(if (x.key == "other") "Other…" else x.name) },
                )
            }
        }
        if (a.key == "other") {
            OutlinedTextField(
                name, { name = it }, label = { Text("Activity name") }, singleLine = true,
                placeholder = { Text("e.g. Climbing") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }

        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(hours, { hours = it }, "Hours", Modifier.weight(1f), suffix = "h", decimal = false, placeholder = "0")
                NumberField(mins, { mins = it }, "Minutes", Modifier.weight(1f), suffix = "min", decimal = false, placeholder = "0")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DURATIONS.forEach { d ->
                    FilterChip(
                        selected = minutes == d,
                        onClick = {
                            hours = (d / 60).toString()
                            mins = (d % 60).toString()
                        },
                        label = { Text(duration(d)) },
                    )
                }
            }
        }

        Column {
            SubText("Effort")
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("light", "moderate", "hard").forEachIndexed { i, key ->
                    SegmentedButton(
                        selected = intensity == key,
                        onClick = { intensity = key },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        icon = {},
                    ) { Text(INTENSITY_NAME[key] ?: key, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(4.dp))
            SubText(INTENSITY_HINT[a.kind] ?: "", color = p.muted)
        }

        if (a.kind == "strength") {
            Column {
                SubText("Muscles trained")
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    meta.muscles.forEach { m ->
                        FilterChip(
                            selected = m.key in muscles,
                            onClick = { muscles = if (m.key in muscles) muscles - m.key else muscles + m.key },
                            label = { Text(m.name) },
                        )
                    }
                }
            }
        }
        if (a.distance) {
            NumberField(distance, { distance = it }, "Distance", Modifier.fillMaxWidth(), suffix = "km", placeholder = "optional")
        }

        Surface(color = p.surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt((typedKcal ?: estimate?.toDouble())), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(" kcal burned", color = p.ink2, modifier = Modifier.padding(bottom = 4.dp))
                }
                SubText(
                    when {
                        typedKcal != null -> "Your number"
                        meta.weight != null -> "Estimated for ${fmt1(kg)} kg body weight"
                        else -> "Estimated for ${fmt(kg)} kg. Log your weight for a better estimate"
                    },
                    color = p.muted,
                )
            }
        }
        Column {
            NumberField(
                kcalText, { kcalText = it }, "Calories burned", Modifier.fillMaxWidth(), suffix = "kcal", decimal = false,
                placeholder = estimate?.let { "$it (estimate)" },
            )
            SubText("Leave empty to use the estimate, or type the number from your watch.", color = p.muted)
        }
        OutlinedTextField(
            notes, { if (it.length <= 300) notes = it }, label = { Text("Notes") }, maxLines = 3,
            placeholder = { Text("e.g. Bench 80 kg 5×5, new PR") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        error?.let { Text(it, color = p.critical, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (workout != null) {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete", color = p.critical) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { save() }, enabled = !busy, modifier = Modifier.width(140.dp).height(48.dp)) {
                Text(if (workout != null) "Save" else "Add")
            }
        }
    }

    if (confirmDelete && workout != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("Remove ${workout.name} on ${longDate(workout.date)}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (vm.deleteWorkout(workout)) onDone() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
