package io.github.greg7gkb.deputy.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import deputy.broker.Outcome
import io.github.greg7gkb.deputy.DeputyApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Gutter = 18.dp
private val CardShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * Runs a broker or planner call in the app's scope, off the main thread, so it outlives the
 * screen that started it. [then] runs back on the main thread with the result.
 */
fun <T> DeputyApp.act(block: suspend () -> T, then: (Result<T>) -> Unit = {}) {
    scope.launch {
        val result = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Deputy", "UI action failed", e)
            Result.failure(e)
        }
        withContext(Dispatchers.Main) { then(result) }
    }
}

/** A short, plain reading of an outcome, for the Lab log. Activity has the full story. */
fun Outcome.summary(): String = when (this) {
    is Outcome.Done -> "done"
    is Outcome.GrantIssued -> "key issued"
    is Outcome.Refused -> "refused: $detail"
    is Outcome.AwaitingUser -> "waiting for you to decide"
    is Outcome.Failed -> "failed: $error"
    is Outcome.Ok -> "ok"
}

fun plural(n: Int, word: String, plural: String = word + "s") = "$n " + if (n == 1) word else plural

/** A vertically scrolling screen with the standard gutters. */
@Composable
fun ScreenColumn(state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun ScreenHeader(title: String, sub: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.Title, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (sub != null) Text(sub, style = Type.Sub, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Sub, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun DCard(
    modifier: Modifier = Modifier,
    background: Color = Palette.Card,
    border: Color = Palette.Line,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(background)
            .border(1.dp, border, CardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Note(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.Sub.copy(fontSize = 13.sp),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Note).padding(12.dp),
    )
}

enum class BadgeStyle(val bg: Color, val fg: Color, val outline: Boolean = false, val mono: Boolean = false) {
    JUST(Palette.GreenBg, Palette.Green),
    UNDO(Color.Transparent, Palette.Green, outline = true),
    ASK(Palette.AmberBg, Palette.Amber),
    TAP(Palette.BlueBg, Palette.Blue),
    INBOUND(Palette.BlueBg, Palette.Blue, mono = true),
    RED(Palette.RedBg, Palette.Red),
    ATTESTED(Color(0xFFDFE9DF), Color(0xFF23502E), mono = true),
    OBSERVED(Palette.BlueBg, Palette.Blue, mono = true),
    INFERRED(Color(0xFFEFE6F4), Color(0xFF5B3672), mono = true),
    PLAIN(Palette.Chip, Color(0xFF444444), mono = true),
}

@Composable
fun Badge(text: String, style: BadgeStyle, modifier: Modifier = Modifier) {
    val shape = if (style.outline) RoundedCornerShape(999.dp) else RoundedCornerShape(6.dp)
    Text(
        text,
        style = if (style.mono) Type.Mono.copy(fontSize = 11.sp, color = style.fg) else Type.Sub.copy(fontSize = 12.sp, color = style.fg),
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(style.bg)
            .then(if (style.outline) Modifier.border(1.dp, style.fg, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun MonoChip(text: String) {
    Text(
        text,
        style = Type.Mono.copy(color = Palette.Ink),
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Palette.Chip).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(chips: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        chips.forEach { MonoChip(it) }
    }
}

@Composable
fun DarkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Color.White),
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp) }
}

@Composable
fun LineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Ink,
    border: Color = Palette.Line,
    borderWidth: Dp = 1.dp,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(borderWidth, if (enabled) border else Palette.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Palette.Card, contentColor = color),
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, textAlign = TextAlign.Center) }
}

/** The small white "Revoke" / "Undo" button from the mockups. */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Palette.Ink) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = Type.Sub.copy(fontSize = 13.sp, color = if (enabled) color else Palette.Muted),
        modifier = Modifier
            .clip(shape)
            .background(Palette.Card)
            .border(1.dp, Palette.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Segment).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Palette.Card else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.Sub.copy(color = if (on) Palette.Ink else Palette.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
            }
        }
    }
}

/** Two-column label/value rows, like the mockup's key detail card. */
@Composable
fun KeyValueTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, style = Type.Sub, modifier = Modifier.width(110.dp))
                Spacer(Modifier.width(10.dp))
                Text(v, style = Type.Sub.copy(color = Palette.Ink), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
