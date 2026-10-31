package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import deputy.broker.BrokerSnapshot
import deputy.broker.present.ActivityItem
import deputy.broker.present.Tone
import io.github.greg7gkb.deputy.DeputyApp

private val AmberLine = Color(0xFFE6CB9C)

@Composable
fun ActivityScreen(app: DeputyApp, snapshot: BrokerSnapshot, items: List<ActivityItem>) {
    var undoing by remember { mutableStateOf(emptySet<Long>()) }

    fun undo(seq: Long) {
        undoing = undoing + seq
        app.act({ app.broker.user.undo(seq) }) { undoing = undoing - seq }
    }

    ScreenColumn {
        item { ScreenHeader("Activity", "Everything Deputy did, refused, or was asked.") }
        if (items.isEmpty()) item { DCard { Text("Nothing yet. Every action, refusal and question will be written here.", style = Type.Sub) } }
        items(items, key = { it.seq }) { ActivityCard(it, undoing = it.undoSeq in undoing, onUndo = ::undo) }
        item {
            val error = snapshot.integrityError
            Text(
                if (error != null) "Log check failed: $error" else "Log verified: ${plural(snapshot.entries.size, "entry", "entries")}, hash-chained",
                style = Type.Mono.copy(color = if (error != null) Palette.Red else Palette.Muted),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ActivityCard(item: ActivityItem, undoing: Boolean, onUndo: (Long) -> Unit) {
    val (bg, line) = when (item.tone) {
        Tone.REFUSED -> Palette.RedBg to Palette.RedLine
        Tone.ASKED -> Palette.Card to AmberLine
        else -> Palette.Card to Palette.Line
    }
    val red = item.tone == Tone.REFUSED || item.tone == Tone.FAILED
    DCard(background = bg, border = line) {
        Row(verticalAlignment = Alignment.Top) {
            Text(item.title, style = Type.CardTitle.copy(color = if (red) Palette.Red else Palette.Ink), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(item.time, style = Type.Mono, modifier = Modifier.padding(top = 3.dp))
        }
        Text(item.body, style = Type.Sub.copy(color = if (item.tone == Tone.FAILED) Palette.Red else Palette.Muted), modifier = Modifier.padding(top = 3.dp))
        val tag = when (item.tone) {
            Tone.INBOUND -> "inbound" to BadgeStyle.INBOUND
            Tone.ASKED -> "asks first" to BadgeStyle.ASK
            else -> null
        }
        val undoSeq = item.undoSeq
        if (tag != null || item.footer.isNotBlank() || undoSeq != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tag?.let { (text, style) -> Badge(text, style) }
                Text(item.footer, style = Type.Mono, modifier = Modifier.weight(1f))
                if (undoSeq != null) SmallButton(if (undoing) "Undoing…" else "Undo", onClick = { onUndo(undoSeq) }, enabled = !undoing)
            }
        }
    }
}
