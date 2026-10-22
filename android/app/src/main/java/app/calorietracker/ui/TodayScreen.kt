package app.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Day
import app.calorietracker.data.Entry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: AppViewModel, onAdd: (String) -> Unit) {
    var editing by remember { mutableStateOf<Entry?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val day = vm.day

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.loadDay(spinner = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                DateBar(
                    label = dateLabel(vm.date),
                    onPrev = { vm.changeDate(vm.date.minusDays(1)) },
                    onNext = { vm.changeDate(vm.date.plusDays(1)) },
                    onPick = { pickDate = true },
                )
            }
            if (day == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@LazyColumn
            }
            if (!day.targets.manual && day.targets.suggested == null) {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            "Add your height, weight and age in Settings to get a personal calorie goal.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { SummaryCard(day) }
            item { TrackersCard(vm, day) }
            items(MEALS, key = { it.first }) { (key, label) ->
                MealCard(
                    label = label,
                    entries = day.entries.filter { it.meal == key },
                    onAdd = { onAdd(key) },
                    onEdit = { editing = it },
                    onCopy = { vm.copyFromYesterday(key) },
                )
            }
            if (day.entries.isEmpty()) {
                item {
                    TextButton(onClick = { vm.copyFromYesterday(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Copy everything from yesterday")
                    }
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = vm.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.changeDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    editing?.let { entry ->
        if (entry.foodId != null) {
            PortionSheet(vm, foodId = entry.foodId, entry = entry, initialMeal = entry.meal,
                onDismiss = { editing = null }, onDone = { editing = null })
        } else {
            QuickSheet(vm, entry = entry, initialMeal = entry.meal, onDismiss = { editing = null })
        }
    }
}

@Composable
private fun DateBar(label: String, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
        Text(
            label,
            modifier = Modifier.weight(1f).clickable(onClick = onPick).padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        FilledTonalIconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(day: Day) {
    val p = LocalPalette.current
    val t = day.targets
    val tot = day.totals
    val budget = t.kcal + day.exercise
    val left = budget - tot.kcal
    val over = left < 0
    AppCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                fmt(kotlin.math.abs(left)),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp,
                color = if (over) p.critical else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (over) "kcal over goal ⚠" else "kcal left",
                color = if (over) p.critical else p.ink2,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Meter(if (budget > 0) (tot.kcal / budget).toFloat() else 0f, if (over) p.critical else p.accent, height = 12.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            "${fmt(t.kcal)} goal  −  ${fmt(tot.kcal)} food  +  ${fmt(day.exercise.toDouble())} exercise",
            style = MaterialTheme.typography.bodySmall,
            color = p.ink2,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MacroColumn("Protein", tot.protein, t.protein, p.protein, Modifier.weight(1f))
            MacroColumn("Carbs", tot.carbs, t.carbs, p.carbs, Modifier.weight(1f))
            MacroColumn("Fat", tot.fat, t.fat, p.fat, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = p.grid)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MiniStat("Fiber", "${fmt1(tot.fiber)} / ${fmt(t.fiber)} g", false)
            MiniStat("Sugar", "${fmt1(tot.sugar)} / ≤${fmt(t.sugar)} g", tot.sugar > t.sugar)
            MiniStat("Sodium", "${fmt(tot.sodium)} / ≤${fmt(t.sodium)} mg", tot.sodium > t.sodium)
        }
    }
}

@Composable
private fun MacroColumn(label: String, value: Double, goal: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = p.ink2)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(value), fontWeight = FontWeight.SemiBold)
            Text(" / ${fmt(goal)} g", style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Meter(if (goal > 0) (value / goal).toFloat() else 0f, color)
    }
}

@Composable
private fun MiniStat(label: String, value: String, over: Boolean) {
    val p = LocalPalette.current
    Row {
        Text("$label ", style = MaterialTheme.typography.bodySmall, color = p.ink2)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = if (over) p.critical else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TrackersCard(vm: AppViewModel, day: Day) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val goal = day.targets.water
    AppCard {
        Text("Water  ${day.water} / $goal glasses · ${fmt1(day.water * 0.25)} L", style = MaterialTheme.typography.bodySmall, color = p.ink2)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { vm.setWater(day.water - 1) }, modifier = Modifier.size(36.dp)) { Text("−", fontSize = 18.sp) }
            Row(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val count = maxOf(goal, day.water).coerceAtMost(16)
                for (i in 0 until count) {
                    Glass(
                        full = i < day.water,
                        modifier = Modifier.weight(1f, fill = false).size(width = 24.dp, height = 30.dp)
                            .clickable { vm.setWater(if (i + 1 == day.water) i else i + 1) },
                    )
                }
            }
            FilledTonalIconButton(onClick = { vm.setWater(day.water + 1) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, "Add a glass", Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        var weightText by remember(day.date, day.weight) { mutableStateOf(day.weight?.trimmed() ?: "") }
        var exerciseText by remember(day.date, day.exercise) { mutableStateOf(if (day.exercise > 0) day.exercise.toString() else "") }
        var weightFocused by remember { mutableStateOf(false) }
        var exerciseFocused by remember { mutableStateOf(false) }

        fun saveWeight() {
            val raw = weightText.trim()
            if (raw.isEmpty()) {
                if (day.weight != null) scope.launch { vm.saveWeight(null) }
                return
            }
            val kg = parseNum(raw)
            if (kg == null || kg < 20 || kg > 400) {
                vm.toast("Enter a weight between 20 and 400 kg")
                return
            }
            if (kg != day.weight) scope.launch { vm.saveWeight(kg) }
        }

        fun saveExercise() {
            val kcal = if (exerciseText.isBlank()) 0.0 else parseNum(exerciseText)
            if (kcal == null || kcal < 0 || kcal > 10000) {
                vm.toast("Enter exercise calories between 0 and 10,000")
                return
            }
            if (kcal.toInt() != day.exercise) scope.launch { vm.saveExercise(kcal.toInt()) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                value = weightText,
                onValueChange = { weightText = it },
                label = "Weight",
                suffix = "kg",
                placeholder = day.lastWeight?.trimmed(),
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (weightFocused && !it.isFocused) saveWeight()
                    weightFocused = it.isFocused
                },
                onDone = { focus.clearFocus() },
            )
            NumberField(
                value = exerciseText,
                onValueChange = { exerciseText = it },
                label = "Exercise",
                suffix = "kcal",
                placeholder = "0",
                decimal = false,
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (exerciseFocused && !it.isFocused) saveExercise()
                    exerciseFocused = it.isFocused
                },
                onDone = { focus.clearFocus() },
            )
        }
    }
}

@Composable
private fun Glass(full: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            moveTo(w * 0.12f, h * 0.08f)
            lineTo(w * 0.88f, h * 0.08f)
            lineTo(w * 0.76f, h * 0.92f)
            lineTo(w * 0.24f, h * 0.92f)
            close()
        }
        if (full) {
            val water = Path().apply {
                moveTo(w * 0.19f, h * 0.34f)
                lineTo(w * 0.81f, h * 0.34f)
                lineTo(w * 0.73f, h * 0.88f)
                lineTo(w * 0.27f, h * 0.88f)
                close()
            }
            drawPath(water, p.accent)
        }
        drawPath(outline, if (full) p.accent else p.axis, style = Stroke(width = 1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun MealCard(label: String, entries: List<Entry>, onAdd: () -> Unit, onEdit: (Entry) -> Unit, onCopy: () -> Unit) {
    val p = LocalPalette.current
    AppCard(padding = 0.dp) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text("${fmt(entries.sumOf { it.kcal })} kcal", color = p.ink2, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
            }
            Button(onClick = onAdd, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        if (entries.isEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nothing logged", color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopy) { Text("Copy yesterday's ${label.lowercase()}") }
            }
        } else {
            HorizontalDivider(color = p.grid)
            entries.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = p.grid)
                Row(
                    Modifier.fillMaxWidth().clickable { onEdit(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        FoodName(e.name)
                        MixedLine("${portionText(e)} · P ${fmt(e.protein)} C ${fmt(e.carbs)} F ${fmt(e.fat)}")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(fmt(e.kcal), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
