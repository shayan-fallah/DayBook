package app.calorietracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun SubText(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink2) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A food name: Persian text kept in the right character order but aligned left like the rest of the UI. */
@Composable
fun FoodName(name: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, maxLines: Int = 1) {
    Text(
        name,
        modifier = modifier,
        style = style.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Left),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A detail line that mixes English with Persian unit names. */
@Composable
fun MixedLine(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.muted) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(LocalPalette.current.surface2),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    placeholder: String? = null,
    decimal: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        singleLine = true,
        suffix = if (suffix != null) {
            { Text(suffix) }
        } else null,
        placeholder = if (placeholder != null) {
            { Text(placeholder, maxLines = 1) }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = if (onDone != null) ImeAction.Done else imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    )
}

@Composable
fun MealSelector(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        MEALS.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = selected == key,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, MEALS.size),
                icon = {},
            ) {
                Text(label, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalPalette.current.muted,
    )
}
