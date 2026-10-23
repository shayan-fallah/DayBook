package app.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Profile
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

private val ACTIVITY = listOf(
    "sedentary" to "Sedentary: desk job, little exercise",
    "light" to "Light: exercise 1–3 days/week",
    "moderate" to "Moderate: exercise 3–5 days/week",
    "active" to "Active: exercise 6–7 days/week",
    "very" to "Very active: physical job or 2×/day training",
)
private val ACTIVITY_FACTOR = mapOf("sedentary" to 1.2, "light" to 1.375, "moderate" to 1.55, "active" to 1.725, "very" to 1.9)
private val RATES = listOf(
    "-1" to "Lose 1 kg / week", "-0.75" to "Lose 0.75 kg / week", "-0.5" to "Lose 0.5 kg / week",
    "-0.25" to "Lose 0.25 kg / week", "0" to "Maintain weight", "0.25" to "Gain 0.25 kg / week", "0.5" to "Gain 0.5 kg / week",
)

private val EXERCISE_PCT = listOf(
    "100" to "Add all of them to my daily goal",
    "50" to "Add half (estimates often run high)",
    "0" to "Don't add them",
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadProfile() }
    val profile = vm.profile
    var showPassword by remember { mutableStateOf(false) }
    val p = LocalPalette.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
        } else {
            ProfileForm(vm, profile)
        }
        AppCard {
            CardTitle("Account")
            Spacer(Modifier.height(4.dp))
            Text("Signed in as ${vm.username}", style = MaterialTheme.typography.bodyMedium)
            SubText(vm.serverUrl)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showPassword = true }) { Text("Change password") }
                OutlinedButton(onClick = { vm.logout() }) { Text("Log out") }
            }
        }
        AppCard {
            CardTitle("Your data")
            SubText("Download your food log, weights and workouts as CSV from the web version: Settings → Your data.")
        }
        Text(
            "Food data from Fig Fit (figfit.ir), licensed CC BY-NC-SA 4.0.",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (showPassword) PasswordDialog(vm) { showPassword = false }
}

@Composable
private fun ProfileForm(vm: AppViewModel, profile: Profile) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var age by remember(profile) { mutableStateOf(profile.age?.toString() ?: "") }
    var height by remember(profile) { mutableStateOf(profile.heightCm?.trimmed() ?: "") }
    var weight by remember(profile) { mutableStateOf(profile.weight?.trimmed() ?: "") }
    var activity by remember(profile) { mutableStateOf(profile.activity ?: "light") }
    var rate by remember(profile) { mutableStateOf(RATES.firstOrNull { it.first.toDouble() == (profile.rateKgWeek ?: 0.0) }?.first ?: "0") }
    var targetWeight by remember(profile) { mutableStateOf(profile.targetWeight?.trimmed() ?: "") }
    var goal by remember(profile) { mutableStateOf(profile.calorieGoal?.toString() ?: "") }
    var proteinPct by remember(profile) { mutableStateOf(profile.proteinPct.toString()) }
    var carbsPct by remember(profile) { mutableStateOf(profile.carbsPct.toString()) }
    var fatPct by remember(profile) { mutableStateOf(profile.fatPct.toString()) }
    var water by remember(profile) { mutableStateOf(profile.waterGoal.toString()) }
    var workoutGoal by remember(profile) { mutableStateOf(profile.workoutGoal.toString()) }
    var exercisePct by remember(profile) { mutableStateOf(profile.exercisePct.toString()) }
    var busy by remember { mutableStateOf(false) }

    // Live preview, same formula as the server (Mifflin-St Jeor).
    val a = parseNum(age)
    val h = parseNum(height)
    val w = parseNum(weight)
    var suggested: Int? = null
    var calcText = "Fill in sex, age, height and weight to get a suggested calorie goal."
    var floorNote: String? = null
    if (sex != null && a != null && h != null && w != null && a > 0 && h > 0 && w > 0) {
        val bmr = 10 * w + 6.25 * h - 5 * a + if (sex == "male") 5 else -161
        val tdee = bmr * (ACTIVITY_FACTOR[activity] ?: 1.375)
        val floor = if (sex == "male") 1500 else 1200
        val raw = tdee + rate.toDouble() * 7700 / 7
        suggested = (max(floor.toDouble(), raw) / 10).roundToInt() * 10
        calcText = "You burn about ${fmt(tdee)} kcal a day (resting ${fmt(bmr)}). Suggested goal: ${fmt(suggested.toDouble())} kcal/day."
        if (raw < floor) floorNote = "Raised to the $floor kcal safe minimum. Consider a slower goal."
    }
    val pcts = listOf(proteinPct, carbsPct, fatPct).map { parseNum(it) ?: 0.0 }
    val sum = pcts.sum().roundToInt()
    val effectiveGoal = parseNum(goal) ?: suggested?.toDouble() ?: 2000.0

    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Body and goal")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("male" to "Male", "female" to "Female").forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = sex == key,
                        onClick = { sex = key },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(age, { age = it }, "Age", Modifier.weight(1f), decimal = false, placeholder = "30")
                NumberField(height, { height = it }, "Height", Modifier.weight(1f), suffix = "cm", placeholder = "175")
                NumberField(weight, { weight = it }, "Weight", Modifier.weight(1f), suffix = "kg", placeholder = "75")
            }
            OptionDropdown("Activity", ACTIVITY, activity) { activity = it }
            SubText("If you log workouts, pick the level for your days without them, so they aren't counted twice.", color = p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionDropdown("Goal", RATES, rate, Modifier.weight(1.4f)) { rate = it }
                NumberField(targetWeight, { targetWeight = it }, "Goal weight", Modifier.weight(1f), suffix = "kg", placeholder = "optional")
            }
            Surface(color = p.surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(calcText, style = MaterialTheme.typography.bodyMedium)
                    floorNote?.let { SubText(it) }
                }
            }

            Spacer(Modifier.height(4.dp))
            CardTitle("Daily targets")
            NumberField(
                goal, { goal = it }, "Calorie goal", Modifier.fillMaxWidth(), suffix = "kcal", decimal = false,
                placeholder = suggested?.let { "$it (suggested)" } ?: "2000",
            )
            SubText("Leave empty to use the suggested goal.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(proteinPct, { proteinPct = it }, "Protein", Modifier.weight(1f), suffix = "%", decimal = false)
                NumberField(carbsPct, { carbsPct = it }, "Carbs", Modifier.weight(1f), suffix = "%", decimal = false)
                NumberField(fatPct, { fatPct = it }, "Fat", Modifier.weight(1f), suffix = "%", decimal = false)
            }
            if (sum != 100) {
                Text("Adds up to $sum%. It needs to be 100%.", color = p.critical, style = MaterialTheme.typography.bodySmall)
            } else {
                SubText(
                    "= ${fmt(effectiveGoal * pcts[0] / 400)} g protein, ${fmt(effectiveGoal * pcts[1] / 400)} g carbs, " +
                        "${fmt(effectiveGoal * pcts[2] / 900)} g fat per day",
                )
            }
            NumberField(water, { water = it }, "Water goal (glasses of 250 ml)", Modifier.fillMaxWidth(), decimal = false)

            Spacer(Modifier.height(4.dp))
            CardTitle("Training")
            NumberField(workoutGoal, { workoutGoal = it }, "Training days per week (goal)", Modifier.fillMaxWidth(), decimal = false, placeholder = "4")
            OptionDropdown("Workout calories", EXERCISE_PCT, exercisePct) { exercisePct = it }
            SubText("Burned calories raise how much you can eat that day.", color = p.muted)
            Button(
                enabled = !busy && sum == 100,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    fun opt(s: String): Any? = if (s.isBlank()) null else parseNum(s) ?: Double.NaN
                    val body = jsonBody(
                        "sex" to sex, "age" to opt(age), "height_cm" to opt(height), "weight" to opt(weight),
                        "activity" to activity, "rate_kg_week" to rate.toDouble(), "target_weight" to opt(targetWeight),
                        "calorie_goal" to opt(goal), "protein_pct" to opt(proteinPct), "carbs_pct" to opt(carbsPct),
                        "fat_pct" to opt(fatPct), "water_goal" to opt(water), "date" to LocalDate.now().toString(),
                        "workout_goal" to (opt(workoutGoal) ?: 4), "exercise_pct" to exercisePct.toInt(),
                    )
                    if (body.values.any { it.toString() == "NaN" }) return@Button vm.toast("Check the numbers")
                    busy = true
                    scope.launch {
                        vm.saveProfile(body)
                        busy = false
                    }
                },
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        onSelect(key)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PasswordDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(new, { new = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), supportingText = { Text("At least 6 characters") })
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && current.isNotEmpty() && new.length >= 6, onClick = {
                busy = true
                scope.launch {
                    val ok = vm.changePassword(current, new)
                    busy = false
                    if (ok) onDismiss()
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
