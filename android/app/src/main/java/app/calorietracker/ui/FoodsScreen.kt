package app.calorietracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Food
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch

/** Which custom food the form edits: null = closed, NEW = a new food. */
private const val NEW = -1L

@Composable
fun FoodsScreen(vm: AppViewModel, onPick: (Food) -> Unit) {
    LaunchedEffect(Unit) { vm.loadQuick() }
    var editing by remember { mutableStateOf<Long?>(null) }
    val p = LocalPalette.current
    val quick = vm.quick

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTitle("My foods", Modifier.weight(1f))
                    Button(onClick = { editing = NEW }) { Text("+ New food") }
                }
                SubText("Foods that aren't in the database, like a home recipe or a product label. They show up in search.")
                Spacer(Modifier.height(4.dp))
                when {
                    quick == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    quick.mine.isEmpty() -> EmptyNote("No custom foods yet.")
                    else -> quick.mine.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = p.grid)
                        FoodRow(f) { editing = f.id }
                    }
                }
            }
        }
        item {
            AppCard {
                CardTitle("Favorites")
                SubText("Tap ♡ on any food to pin it here and to the top of search. Tap one to log it.")
                Spacer(Modifier.height(4.dp))
                when {
                    quick == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    quick.favorites.isEmpty() -> EmptyNote("No favorites yet.")
                    else -> quick.favorites.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = p.grid)
                        FoodRow(f) { onPick(f) }
                    }
                }
            }
        }
    }

    editing?.let { id ->
        FoodFormSheet(vm, foodId = if (id == NEW) null else id, onDismiss = { editing = null })
    }
}

@Composable
private fun FoodRow(food: Food, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        FoodName(food.name)
        MixedLine(foodSub(food.copy(last = null)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodFormSheet(vm: AppViewModel, foodId: Long?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(foodId == null) }
    var name by remember { mutableStateOf("") }
    var servingName by remember { mutableStateOf("") }
    var servingGrams by remember { mutableStateOf("100") }
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val fields = listOf(
        "kcal" to "Calories (kcal)", "protein" to "Protein (g)", "carbs" to "Carbs (g)", "fat" to "Fat (g)",
        "fiber" to "Fiber (g)", "sugar" to "Sugar (g)", "sodium" to "Sodium (mg)",
    )

    LaunchedEffect(foodId) {
        if (foodId == null) return@LaunchedEffect
        val f = vm.getFood(foodId) ?: return@LaunchedEffect onDismiss()
        val serving = f.units.firstOrNull { it.name != GRAM }
        val grams = serving?.grams ?: 100.0
        val k = grams / 100.0
        name = f.name
        servingName = serving?.name ?: ""
        servingGrams = grams.trimmed()
        values.value = mapOf(
            "kcal" to f.kcal, "protein" to f.protein, "carbs" to f.carbs, "fat" to f.fat,
            "fiber" to f.fiber, "sugar" to f.sugar, "sodium" to f.sodium,
        ).mapValues { (_, v) -> if (v > 0) (v * k).trimmed() else "" }
        loaded = true
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (!loaded) {
            CircularProgressIndicator(Modifier.padding(48.dp).align(Alignment.CenterHorizontally))
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (foodId == null) "New food" else "Edit food", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = servingName, onValueChange = { servingName = it }, label = { Text("Serving name") },
                    placeholder = { Text("serving") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                NumberField(servingGrams, { servingGrams = it }, "Serving size", Modifier.weight(1f), suffix = "g")
            }
            SubText("Nutrition for one serving:")
            fields.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (key, label) ->
                        NumberField(
                            values.value[key] ?: "",
                            { values.value = values.value + (key to it) },
                            label,
                            Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (foodId != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy,
                    modifier = Modifier.width(140.dp).height(48.dp),
                    onClick = {
                        val grams = parseNum(servingGrams)
                        if (name.isBlank()) return@Button vm.toast("Enter a name")
                        if (grams == null || grams <= 0) return@Button vm.toast("Enter the serving size in grams")
                        val nums = fields.associate { (key, _) ->
                            val raw = values.value[key].orEmpty()
                            key to (if (raw.isBlank()) 0.0 else parseNum(raw))
                        }
                        if (nums.values.any { it == null || it < 0 }) return@Button vm.toast("Check the numbers")
                        val body = jsonBody(
                            "name" to name.trim(), "serving_name" to servingName.trim().ifEmpty { "serving" },
                            "serving_grams" to grams, *nums.map { (k, v) -> k to v }.toTypedArray(),
                        )
                        busy = true
                        scope.launch {
                            val ok = vm.saveCustomFood(foodId, body)
                            busy = false
                            if (ok) close()
                        }
                    },
                ) { Text("Save") }
            }
        }
    }

    if (confirmDelete && foodId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("Past log entries keep their numbers.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (vm.deleteCustomFood(foodId)) close() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
