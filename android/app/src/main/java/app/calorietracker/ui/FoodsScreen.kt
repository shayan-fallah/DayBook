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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Food
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Which food the editor is open for: null = closed, [NEW] = a new food. */
private const val NEW = -1L

@Composable
fun FoodsScreen(vm: AppViewModel, onPick: (Food) -> Unit) {
    LaunchedEffect(Unit) { vm.loadQuick() }
    var editing by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Food>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val p = LocalPalette.current
    val quick = vm.quick

    LaunchedEffect(query, refresh) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(250)
        results = vm.search(q) ?: emptyList()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            AppCard {
                CardTitle("Find a food")
                SubText("Look up any food to check or change its calories, macros and serving sizes.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search foods: نان، برنج، سیب…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(12.dp),
                )
                val list = results
                if (query.isNotBlank()) {
                    when {
                        list == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                        list.isEmpty() -> {
                            EmptyNote("No matches.")
                            TextButton(onClick = {
                                newName = query.trim()
                                editing = NEW
                            }) { Text("Create “${query.trim()}” as a new food") }
                        }
                        else -> list.take(20).forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(color = p.grid)
                            FoodRow(f) { editing = f.id }
                        }
                    }
                }
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTitle("My foods", Modifier.weight(1f))
                    Button(onClick = {
                        newName = ""
                        editing = NEW
                    }) { Text("+ New food") }
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
        if (quick != null && quick.edited.isNotEmpty()) {
            item {
                AppCard {
                    CardTitle("Edited foods")
                    SubText("Foods from the database you changed. Only you see your values; open one to go back to the original.")
                    Spacer(Modifier.height(4.dp))
                    quick.edited.forEachIndexed { i, f ->
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
        FoodEditorSheet(
            vm, foodId = if (id == NEW) null else id, initialName = newName,
            onDismiss = { editing = null }, onSaved = { refresh++ }, // search again to show the new values
        )
    }
}

@Composable
private fun FoodRow(food: Food, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FoodName(food.name, Modifier.weight(1f, fill = false))
            val tag = if (food.custom) "mine" else if (food.edited) "edited" else null
            if (tag != null) {
                Spacer(Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                    Text(tag, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
        MixedLine(foodSub(food.copy(last = null)))
    }
}

/** One serving size row in the editor. */
private class UnitDraft(name: String, grams: String) {
    var name by mutableStateOf(name)
    var grams by mutableStateOf(grams)
    val key = nextKey++

    companion object {
        var nextKey = 0
    }
}

private val NUTRIENT_FIELDS = listOf(
    "kcal" to "Calories (kcal)", "protein" to "Protein (g)", "carbs" to "Carbs (g)", "fat" to "Fat (g)",
    "fiber" to "Fiber (g)", "sugar" to "Sugar (g)", "sodium" to "Sodium (mg)",
)

/**
 * Edit any food (or create one when [foodId] is null). Your own foods change in place; a food from the
 * database keeps its original for everyone else and can be reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodEditorSheet(
    vm: AppViewModel,
    foodId: Long?,
    initialName: String = "",
    onSaved: (Long) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current
    var food by remember { mutableStateOf<Food?>(null) }
    var loaded by remember { mutableStateOf(foodId == null) }
    var name by remember { mutableStateOf(initialName) }
    val units = remember { mutableStateListOf<UnitDraft>() }
    var basisKey by remember { mutableIntStateOf(-1) } // -1 = per 100 g, else a UnitDraft key
    var basisGrams by remember { mutableStateOf(100.0) }
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    var updateEntries by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "delete" or "reset"
    var busy by remember { mutableStateOf(false) }

    fun gramsOf(key: Int): Double? = if (key == -1) 100.0 else units.firstOrNull { it.key == key }?.grams?.let { parseNum(it) }?.takeIf { it > 0 }

    LaunchedEffect(foodId) {
        if (foodId == null) {
            units.add(UnitDraft("", ""))
            return@LaunchedEffect
        }
        val f = vm.getFood(foodId) ?: return@LaunchedEffect onDismiss()
        food = f
        name = f.name
        f.units.filter { it.name != GRAM }.forEach {
            units.add(UnitDraft(it.name, java.math.BigDecimal(it.grams).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()))
        }
        // Your own foods were typed in per serving: show them that way again.
        val first = units.firstOrNull()
        if (f.custom && first != null) {
            basisKey = first.key
            basisGrams = parseNum(first.grams) ?: 100.0
        }
        val k = basisGrams / 100.0
        values.value = mapOf(
            "kcal" to f.kcal, "protein" to f.protein, "carbs" to f.carbs, "fat" to f.fat,
            "fiber" to f.fiber, "sugar" to f.sugar, "sodium" to f.sodium,
        ).mapValues { (_, v) -> if (v > 0) (v * k).trimmed() else "" }
        loaded = true
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    /** Show the same food for a different amount. */
    fun changeBasis(key: Int) {
        val g = gramsOf(key) ?: return
        val k = g / basisGrams
        values.value = values.value.mapValues { (_, raw) -> parseNum(raw)?.takeIf { raw.isNotBlank() && it >= 0 }?.let { (it * k).trimmed() } ?: raw }
        basisKey = key
        basisGrams = g
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (!loaded) {
            CircularProgressIndicator(Modifier.padding(48.dp).align(Alignment.CenterHorizontally))
            return@ModalBottomSheet
        }
        val f = food
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (foodId == null) "New food" else "Edit food", style = MaterialTheme.typography.titleLarge)
            if (f != null && !f.custom) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        if (f.edited && f.original != null) "You changed this food. The original is “${f.original.name}”, ${fmt(f.original.kcal)} kcal per 100 g."
                        else "This food comes from the food database. Your changes are only for you.",
                        modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            SubText("Serving sizes, like “slice = 30 g” or “عدد = 120 g”. Grams always work too.")
            units.forEach { u ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = u.name, onValueChange = { u.name = it }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("slice") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    )
                    NumberField(u.grams, {
                        u.grams = it
                        if (basisKey == u.key) parseNum(it)?.takeIf { g -> g > 0 }?.let { g -> basisGrams = g }
                    }, "Weight", Modifier.width(110.dp), suffix = "g")
                    IconButton(onClick = {
                        if (basisKey == u.key) {
                            basisKey = -1
                            basisGrams = 100.0
                        }
                        units.remove(u)
                    }) { Icon(Icons.Filled.Close, "Remove this serving size", Modifier.size(18.dp)) }
                }
            }
            TextButton(onClick = { units.add(UnitDraft("", "")) }) { Text("+ Add a serving size") }
            val basisOptions = listOf("-1" to "100 g") + units.filter { it.name.isNotBlank() && (parseNum(it.grams) ?: 0.0) > 0 }
                .map { it.key.toString() to "1 × ${isolate(it.name.trim())} (${fmt1(parseNum(it.grams))} g)" }
            Picker("Values below are for", basisOptions, basisKey.toString()) { changeBasis(it.toInt()) }
            NUTRIENT_FIELDS.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (key, label) ->
                        NumberField(values.value[key] ?: "", { values.value = values.value + (key to it) }, label, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (f != null && f.uses > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also update the ${f.uses} time${if (f.uses == 1) "" else "s"} I logged it", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(updateEntries, { updateEntries = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (f != null && f.custom) {
                    OutlinedButton(
                        onClick = { confirm = "delete" },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                if (f != null && f.edited) OutlinedButton(onClick = { confirm = "reset" }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !busy, modifier = Modifier.width(130.dp).height(48.dp),
                    onClick = {
                        if (name.isBlank()) return@Button vm.toast("Enter a name")
                        val unitJson = mutableListOf<JsonObject>()
                        for (u in units) {
                            val g = parseNum(u.grams)
                            if (u.name.isBlank() && u.grams.isBlank()) continue
                            if (u.name.isBlank()) return@Button vm.toast("Give every serving size a name")
                            if (g == null || g <= 0) return@Button vm.toast("Enter how many grams “${u.name.trim()}” weighs")
                            unitJson += jsonBody("name" to u.name.trim(), "grams" to g)
                        }
                        val nums = NUTRIENT_FIELDS.associate { (key, _) ->
                            val raw = values.value[key].orEmpty()
                            key to (if (raw.isBlank()) 0.0 else parseNum(raw))
                        }
                        if (nums.values.any { it == null || it < 0 }) return@Button vm.toast("Check the numbers")
                        val basis = gramsOf(basisKey) ?: 100.0
                        val body = jsonBody(
                            "name" to name.trim(), "units" to JsonArray(unitJson), "basis_grams" to basis,
                            "update_entries" to (updateEntries && f != null && f.uses > 0),
                            *nums.map { (k, v) -> k to v }.toTypedArray(),
                        )
                        busy = true
                        scope.launch {
                            val id = vm.saveFood(f?.id, body)
                            busy = false
                            if (id != null) {
                                onSaved(id)
                                close()
                            }
                        }
                    },
                ) { Text("Save") }
            }
        }
    }

    val f = food
    if (confirm != null && f != null) {
        val reset = confirm == "reset"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (reset) "Go back to the original values?" else "Delete “${f.name}”?") },
            text = {
                Text(
                    if (reset) "Your changes to this food are removed" + (if (updateEntries) " and your log entries are recalculated." else ".")
                    else "Past log entries keep their numbers.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        val ok = if (reset) vm.resetFood(f.id, updateEntries) else vm.deleteCustomFood(f.id)
                        if (ok) {
                            onSaved(f.id)
                            close()
                        }
                    }
                }) { Text(if (reset) "Reset" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
