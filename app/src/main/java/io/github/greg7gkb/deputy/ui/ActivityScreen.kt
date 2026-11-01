package io.github.greg7gkb.deputy.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import deputy.broker.present.ExternalRef
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

/**
 * The log, newest first. [focusArtifact] is set when the person followed a deputy://activity link
 * from something Deputy created (a hold in their calendar); that entry is scrolled to and outlined.
 */
@Composable
fun ActivityScreen(app: DeputyApp, snapshot: BrokerSnapshot, items: List<ActivityItem>, focusArtifact: String? = null) {
    var undoing by remember { mutableStateOf(emptySet<Long>()) }
    val context = LocalContext.current
    val list = rememberLazyListState()
    val focusIndex = focusArtifact?.let { id -> items.indexOfFirst { it.artifactId == id } } ?: -1
    LaunchedEffect(focusArtifact, focusIndex) {
        if (focusIndex >= 0) list.animateScrollToItem(focusIndex + 1)
    }

    fun open(ref: ExternalRef) {
        val uri = when (ref.kind) {
            ExternalRef.Kind.CALENDAR_EVENT -> ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.ref.toLong())
        }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun undo(seq: Long) {
        undoing = undoing + seq
        app.act({ app.broker.user.undo(seq) }) { undoing = undoing - seq }
    }

    ScreenColumn(list) {
        item {
            Column {
                ScreenHeader("Activity", "Everything Deputy did, refused, or was asked. Newest first.")
                if (focusArtifact != null && focusIndex < 0) {
                    Text("That link points to something that isn't in this phone's log.", style = Type.Sub.copy(color = Palette.Red))
                }
            }
        }
        if (items.isEmpty()) item { DCard { Text("Nothing yet. Every action, refusal and question will be written here.", style = Type.Sub) } }
        items(items, key = { it.seq }) {
            ActivityCard(it, Actors.label(it.actor, app), focused = it.artifactId != null && it.artifactId == focusArtifact,
                undoing = it.undoSeq in undoing, onUndo = ::undo, onOpen = ::open)
        }
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
private fun ActivityCard(item: ActivityItem, actor: String, focused: Boolean, undoing: Boolean, onUndo: (Long) -> Unit, onOpen: (ExternalRef) -> Unit) {
    val (bg, line) = when (item.tone) {
        Tone.REFUSED -> Palette.RedBg to Palette.RedLine
        Tone.ASKED -> Palette.Card to AmberLine
        else -> Palette.Card to Palette.Line
    }
    val red = item.tone == Tone.REFUSED || item.tone == Tone.FAILED
    DCard(background = bg, border = if (focused) Palette.Ink else line) {
        Text(actor, style = Type.Mono.copy(fontSize = 10.5.sp))
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
        val external = item.external
        if (tag != null || item.footer.isNotBlank() || undoSeq != null || external != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tag?.let { (text, style) -> Badge(text, style) }
                Text(item.footer, style = Type.Mono, modifier = Modifier.weight(1f))
                if (external != null) SmallButton("Open in Calendar", onClick = { onOpen(external) })
                if (undoSeq != null) SmallButton(if (undoing) "Undoing…" else "Undo", onClick = { onUndo(undoSeq) }, enabled = !undoing)
            }
        }
    }
}
