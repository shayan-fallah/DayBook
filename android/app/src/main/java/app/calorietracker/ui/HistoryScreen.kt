package app.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Stats
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val RANGES = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year")

@Composable
fun HistoryScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadStats() }
    val s = vm.stats
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RANGES.forEach { (n, label) ->
                    FilterChip(selected = vm.statsRange == n, onClick = { vm.loadStats(n) }, label = { Text(label) })
                }
            }
        }
        if (s == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item { Tiles(s) }
        item {
            AppCard {
                CardTitle("Calories per day")
                SubText("Bars are what you ate; the line is your goal plus exercise. Tap a day for details.")
                Spacer(Modifier.height(8.dp))
                if (s.loggedDays == 0) EmptyNote("Nothing logged in this period.") else KcalChart(s)
            }
        }
        item { WeightCard(vm, s) }
        item { MacroSplitCard(s) }
        item { MealSplitCard(s) }
        item { TopFoodsCard(s) }
    }
}

@Composable
private fun Tiles(s: Stats) {
    val n = s.days.size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Average per day", fmt(s.avg.kcal), "kcal", "Goal ${fmt(s.targets.kcal)} kcal", Modifier.weight(1f))
            Tile("Days logged", "${s.loggedDays}", "/ $n",
                if (s.loggedDays > 0) "${(s.loggedDays * 100.0 / n).roundToInt()}% of days" else "Start logging today", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("On target", "${s.onTarget}", if (s.onTarget == 1) "day" else "days", "Within ±10% of goal", Modifier.weight(1f))
            Tile("Streak", "${s.streak}", if (s.streak == 1) "day" else "days",
                if (s.streak > 0) "Logged in a row" else "Log today to start one", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, sub: String, modifier: Modifier) {
    val p = LocalPalette.current
    AppCard(modifier, padding = 14.dp) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.ink2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", color = p.ink2, modifier = Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
    }
}

private fun niceStep(range: Double, target: Int = 4): Double {
    val raw = range / target
    if (raw <= 0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    return (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 2.5) 2.5 else if (n <= 5) 5.0 else 10.0) * mag
}

private fun compact(v: Double) = if (v >= 1000) "${fmt1(v / 1000)}K" else fmt(v)

private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, align: Float = 0f) {
    // align: 0 = left edge at x, 0.5 = centered, 1 = right edge at x. y is the vertical center.
    val layout = m.measure(text, style)
    drawText(layout, topLeft = Offset(x - layout.size.width * align, y - layout.size.height / 2f))
}

@Composable
private fun KcalChart(s: Stats) {
    val p = LocalPalette.current
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val days = s.days
    val lastLogged = days.indexOfLast { it.kcal != null }.coerceAtLeast(0)
    var selected by remember(s) { mutableIntStateOf(lastLogged) }

    val sel = days.getOrNull(selected)
    if (sel != null) {
        val text = if (sel.kcal == null) "${longDate(sel.date)} · not logged" else {
            val diff = sel.kcal - sel.budget
            "${longDate(sel.date)} · ${fmt(sel.kcal)} of ${fmt(sel.budget)} kcal (${fmt(abs(diff))} ${if (diff > 0) "over" else "under"})\n" +
                "Protein ${fmt(sel.protein)} g · carbs ${fmt(sel.carbs)} g · fat ${fmt(sel.fat)} g"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = ink, modifier = Modifier.height(36.dp))
    }

    val axisStyle = TextStyle(fontSize = 10.sp, color = p.muted)
    val goalStyle = TextStyle(fontSize = 10.sp, color = p.ink2, fontWeight = FontWeight.SemiBold)
    val n = days.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(days) {
                val left = 34.dp.toPx()
                fun pick(x: Float) {
                    val slot = (size.width - left - 4.dp.toPx()) / n
                    selected = ((x - left) / slot).toInt().coerceIn(0, n - 1)
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(days) {
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
        val maxV = max(500.0, days.maxOf { max(it.kcal ?: 0.0, it.budget) }) * 1.08
        val step = niceStep(maxV)
        val yTop = ceil(maxV / step) * step
        fun y(v: Double) = (top + ih - (v / yTop) * ih).toFloat()

        var v = 0.0
        while (v <= yTop + 0.001) {
            drawLine(if (v == 0.0) p.axis else p.grid, Offset(left, y(v)), Offset(size.width - right, y(v)), 1.dp.toPx())
            label(measurer, compact(v), left - 6.dp.toPx(), y(v), axisStyle, align = 1f)
            v += step
        }

        val slot = iw / n
        val bw = max(1f, min(min(24.dp.toPx(), slot * 0.7f), slot - 2.dp.toPx()))
        // Selected column highlight
        drawRect(ink.copy(alpha = 0.06f), topLeft = Offset(left + slot * selected, top), size = androidx.compose.ui.geometry.Size(slot, ih))

        days.forEachIndexed { i, d ->
            val k = d.kcal ?: return@forEachIndexed
            if (k <= 0) return@forEachIndexed
            val x0 = left + slot * i + (slot - bw) / 2
            val y0 = y(k)
            val r = min(4.dp.toPx(), bw / 2)
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(x0, y0, x0 + bw, top + ih),
                        topLeft = CornerRadius(r), topRight = CornerRadius(r),
                        bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(path, p.accent)
        }

        // Goal line, stepping up on days with exercise
        val goal = Path()
        days.forEachIndexed { i, d ->
            val x0 = left + slot * i
            if (i == 0) goal.moveTo(x0, y(d.budget)) else goal.lineTo(x0, y(d.budget))
            goal.lineTo(x0 + slot, y(d.budget))
        }
        drawPath(goal, surface, style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round))
        drawPath(goal, p.ink2, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))

        val every = when {
            n <= 7 -> 1
            n <= 31 -> 7
            n <= 92 -> 14
            else -> 61
        }
        days.forEachIndexed { i, d ->
            if ((n - 1 - i) % every == 0) {
                val text = if (n <= 7) weekday(d.date) else shortDate(d.date)
                val cx = (left + slot * i + slot / 2).coerceIn(left + 14.dp.toPx(), size.width - 16.dp.toPx())
                label(measurer, text, cx, size.height - bottom / 2, axisStyle, align = 0.5f)
            }
        }
        val lastBudget = days.last().budget
        label(measurer, "Goal ${fmt(lastBudget)}", size.width - right, y(lastBudget) - 9.dp.toPx(), goalStyle, align = 1f)
    }
}

@Composable
private fun WeightCard(vm: AppViewModel, s: Stats) {
    val p = LocalPalette.current
    val w = s.weights
    var showLog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val parts = mutableListOf<String>()
    s.currentWeight?.let { parts += "Now ${fmt1(it)} kg" }
    if (w.size >= 2) {
        val diff = w.last().kg - w.first().kg
        val sign = if (diff > 0) "+" else if (diff < 0) "−" else "±"
        parts += "$sign${fmt1(abs(diff))} kg since ${shortDate(w.first().date)}"
    }
    val target = s.targetWeight
    val current = s.currentWeight
    if (target != null && current != null) {
        val togo = target - current
        if (abs(togo) < 0.1) {
            parts += "Goal reached"
        } else {
            var line = "${fmt1(abs(togo))} kg to goal (${fmt1(target)} kg)"
            val rate = s.rateKgWeek ?: 0.0
            if (rate != 0.0 && (rate > 0) == (togo > 0)) line += ", about ${ceil(abs(togo / rate)).toInt()} weeks at your pace"
            parts += line
        }
    }

    AppCard {
        CardTitle("Weight")
        SubText(parts.joinToString(" · ").ifEmpty { "Log your weight on the Today tab to see a trend." })
        if (w.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            WeightChart(s)
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide weight log" else "Show weight log") }
            if (showLog) {
                w.reversed().forEach { pt ->
                    HorizontalDivider(color = p.grid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(longDate(pt.date), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${fmt1(pt.kg)} kg", style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { confirmDelete = pt.date }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = p.muted)
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { date ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete weight?") },
            text = { Text("Remove the weight logged on ${longDate(date)}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { vm.deleteWeight(date) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WeightChart(s: Stats) {
    val p = LocalPalette.current
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val pts = s.weights
    var selected by remember(s) { mutableIntStateOf(pts.lastIndex) }
    val start = LocalDate.parse(s.start)
    val span = max(1L, ChronoUnit.DAYS.between(start, LocalDate.parse(s.end))).toFloat()

    pts.getOrNull(selected)?.let { Text("${longDate(it.date)} · ${fmt1(it.kg)} kg", style = MaterialTheme.typography.bodySmall) }

    val axisStyle = TextStyle(fontSize = 10.sp, color = p.muted)
    val strongStyle = TextStyle(fontSize = 10.sp, color = p.ink2, fontWeight = FontWeight.SemiBold)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .pointerInput(pts) {
                detectTapGestures { o ->
                    val left = 38.dp.toPx()
                    val iw = size.width - left - 10.dp.toPx()
                    selected = pts.indices.minByOrNull { i ->
                        abs(left + ChronoUnit.DAYS.between(start, LocalDate.parse(pts[i].date)) / span * iw - o.x)
                    } ?: 0
                }
            },
    ) {
        val left = 38.dp.toPx()
        val right = 10.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = 20.dp.toPx()
        val iw = size.width - left - right
        val ih = size.height - top - bottom
        val values = pts.map { it.kg } + listOfNotNull(s.targetWeight)
        var lo = values.min()
        var hi = values.max()
        val pad = max(0.5, (hi - lo) * 0.15)
        lo -= pad
        hi += pad
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step
        fun x(date: String) = left + ChronoUnit.DAYS.between(start, LocalDate.parse(date)) / span * iw
        fun y(v: Double) = (top + ih - ((v - lo) / (hi - lo)) * ih).toFloat()

        var v = lo
        while (v <= hi + 1e-9) {
            drawLine(p.grid, Offset(left, y(v)), Offset(size.width - right, y(v)), 1.dp.toPx())
            label(measurer, fmt1(v), left - 6.dp.toPx(), y(v), axisStyle, align = 1f)
            v += step
        }
        label(measurer, shortDate(s.start), left, size.height - bottom / 2, axisStyle, 0f)
        label(measurer, shortDate(s.end), size.width - right, size.height - bottom / 2, axisStyle, 1f)

        s.targetWeight?.let { t ->
            drawLine(p.good, Offset(left, y(t)), Offset(size.width - right, y(t)), 1.5.dp.toPx())
            label(measurer, "Goal ${fmt1(t)} kg", left + 4.dp.toPx(), y(t) - 9.dp.toPx(), strongStyle, 0f)
        }
        if (pts.size > 1) {
            val line = Path()
            pts.forEachIndexed { i, pt -> if (i == 0) line.moveTo(x(pt.date), y(pt.kg)) else line.lineTo(x(pt.date), y(pt.kg)) }
            drawPath(line, p.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        pts.forEachIndexed { i, pt ->
            if (pts.size <= 60 || i == pts.lastIndex || i == selected) {
                val c = Offset(x(pt.date), y(pt.kg))
                val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, r + 2.dp.toPx(), c)
                drawCircle(p.accent, r, c)
            }
        }
    }
}

@Composable
private fun MacroSplitCard(s: Stats) {
    val p = LocalPalette.current
    val a = s.avg
    AppCard {
        CardTitle("Average macros")
        SubText("Share of calories on logged days.")
        if (a.kcal == null) {
            EmptyNote("Nothing logged in this period.")
            return@AppCard
        }
        val macros = listOf(
            Triple("Protein", (a.protein ?: 0.0) to s.targets.protein, p.protein) to 4.0,
            Triple("Carbs", (a.carbs ?: 0.0) to s.targets.carbs, p.carbs) to 4.0,
            Triple("Fat", (a.fat ?: 0.0) to s.targets.fat, p.fat) to 9.0,
        )
        val kcals = macros.map { (m, per) -> m.second.first * per }
        val total = kcals.sum().takeIf { it > 0 } ?: 1.0
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            macros.forEachIndexed { i, (m, _) ->
                if (kcals[i] > 0) {
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                        macros.lastIndex -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Box(Modifier.weight(kcals[i].toFloat()).fillMaxHeight().clip(shape).background(m.third))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        macros.forEachIndexed { i, (m, per) ->
            val targetPct = if (s.targets.kcal > 0) (m.second.second * per / s.targets.kcal * 100).roundToInt() else 0
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(m.third)
                Spacer(Modifier.width(8.dp))
                Text(m.first, modifier = Modifier.weight(1f))
                Text(
                    "${fmt(m.second.first)} g/day · ${(kcals[i] / total * 100).roundToInt()}% (target $targetPct%)",
                    color = p.ink2, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun BarRow(name: String, value: String, fraction: Float, food: Boolean = false) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (food) FoodName(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            else Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(value, color = p.ink2, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Meter(fraction, p.accent, height = 6.dp)
    }
}

@Composable
private fun MealSplitCard(s: Stats) {
    val total = s.meals.values.sum()
    AppCard {
        CardTitle("Calories by meal")
        if (total <= 0) {
            EmptyNote("Nothing logged in this period.")
            return@AppCard
        }
        val logged = max(1, s.loggedDays)
        MEALS.forEach { (key, label) ->
            val v = s.meals[key] ?: 0.0
            BarRow(label, "${fmt(v / logged)} kcal/day · ${(v / total * 100).roundToInt()}%", (v / total).toFloat())
        }
    }
}

@Composable
private fun TopFoodsCard(s: Stats) {
    AppCard {
        CardTitle("Top calorie sources")
        if (s.topFoods.isEmpty()) {
            EmptyNote("Nothing logged in this period.")
            return@AppCard
        }
        val maxK = s.topFoods.first().kcal.takeIf { it > 0 } ?: 1.0
        s.topFoods.forEach { f ->
            BarRow(f.name, "${fmt(f.kcal)} kcal · ${f.times}×", (f.kcal / maxK).toFloat(), food = true)
        }
    }
}
