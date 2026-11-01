package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import deputy.broker.BrokerSnapshot
import deputy.broker.Direction
import deputy.broker.GrantRecord
import deputy.broker.present.ActivityItem
import deputy.broker.present.Tone
import io.github.greg7gkb.deputy.DeputyApp
import deputy.planner.ClaudePlanner
import deputy.planner.Transcript.Step
import kotlinx.datetime.toLocalDateTime

@Composable
fun AskScreen(
    app: DeputyApp,
    snapshot: BrokerSnapshot,
    items: List<ActivityItem>,
    calendarGranted: Boolean,
    onRequestCalendar: () -> Unit,
    onSeeActivity: () -> Unit,
) {
    val transcript by app.transcript.steps.collectAsStateWithLifecycle()
    val plannerBusy by app.planner.busy.collectAsStateWithLifecycle()
    val probeBusy by app.probe.busy.collectAsStateWithLifecycle()
    val busy = plannerBusy || probeBusy
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    /** The top box and the suggestions always start something new. */
    fun send(request: String) {
        val r = request.trim()
        if (r.isEmpty() || busy) return
        text = ""
        app.act({
            if (r == PROBE_SUGGESTION) app.runProbe() else { app.planner.reset(); app.planner.handle(r) }
        })
    }

    /** The reply box under Claude's last message continues that conversation. */
    fun reply(message: String) {
        val r = message.trim()
        if (r.isEmpty() || busy) return
        app.act({ app.planner.handle(r) })
    }

    // One card per request, newest first (the same order as Keyring and Activity); steps read
    // top to bottom inside each card.
    val runs = remember(transcript) { transcript.groupBy { it.conversation }.values.toList().reversed() }
    // The newest request is the first card; keep it in view as it fills in.
    val firstRun = 4 + (if (calendarGranted) 0 else 1)
    LaunchedEffect(runs.size) {
        if (runs.isNotEmpty()) list.animateScrollToItem(firstRun)
    }

    ScreenColumn(list) {
        item { AskHeader(snapshot) }
        if (!calendarGranted) item { CalendarBanner(onRequestCalendar) }
        item {
            Column {
                SectionLabel("Ask Deputy")
                Text(
                    if (app.planner is ClaudePlanner) "The assistant is Claude (${(app.planner as ClaudePlanner).model}), in the cloud. It sees your request and the times Deputy hands back, never your calendar. It can only propose; Deputy decides."
                    else "No API key is configured, so the assistant is a keyword script. It can only propose; Deputy decides.",
                    style = Type.Sub.copy(fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
                VSpace(8.dp)
                AskInput(
                    text, onChange = { text = it }, enabled = !busy, onSend = { send(text) },
                    placeholder = if (app.planner.inConversation) "Start a new request…" else "Find a time with Dana…",
                )
            }
        }
        item { Suggestions(app.planner.suggestions + PROBE_SUGGESTION, enabled = !busy, onPick = ::send) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(if (transcript.isEmpty()) "Nothing asked yet" else "Requests, newest first", Modifier.weight(1f))
                if (transcript.isNotEmpty() && !busy) SmallButton("New conversation", onClick = { app.transcript.clear(); app.planner.reset() })
            }
        }
        itemsIndexed(runs) { i, run ->
            // Only the newest conversation can be continued, and only once the assistant has answered.
            val canReply = i == 0 && !busy && app.planner.inConversation && run.any { it.source == app.planner.name }
            RunCard(run, working = busy && i == 0, onReply = if (canReply) ::reply else null)
        }
        item { TodayCard(snapshot, items, onSeeActivity) }
    }
}

@Composable
private fun AskHeader(snapshot: BrokerSnapshot) {
    val active = snapshot.grants.filter { it.status(snapshot.now) == GrantRecord.Status.ACTIVE }
    val keys = active.count { it.grant.direction == Direction.OUTBOUND }
    val askers = active.filter { it.grant.direction == Direction.INBOUND }.map { it.grant.holder }.distinct().size
    val error = snapshot.integrityError
    Column {
        ScreenHeader(
            "Deputy",
            trailing = {
                Text(
                    "${plural(keys, "key")} · ${plural(askers, "asker")}",
                    style = Type.Sub.copy(fontSize = 12.5.sp, color = Palette.Ink),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Palette.Card)
                        .border(1.dp, Palette.Line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            },
        )
        Text(
            error ?: "All connections healthy",
            style = Type.Sub.copy(color = if (error != null) Palette.Red else Palette.Muted),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CalendarBanner(onRequest: () -> Unit) {
    DCard(background = Palette.BlueBg, border = Palette.BlueBg, padding = 12.dp) {
        Text("Calendar access needed", style = Type.CardTitle.copy(fontSize = 14.5.sp, color = Palette.Blue))
        Text(
            "Deputy reads only when you're busy, never titles, people or places. Writing is for the holds and invites you approve.",
            style = Type.Sub.copy(color = Palette.Blue),
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        SmallButton("Allow calendar access", onClick = onRequest, color = Palette.Blue)
    }
}

@Composable
private fun AskInput(text: String, onChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, placeholder: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = Color(0xFF8A8B8E), fontSize = 14.sp) },
            singleLine = true,
            textStyle = Type.Body,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.Card,
                unfocusedContainerColor = Palette.Card,
                unfocusedBorderColor = Palette.Line,
                focusedBorderColor = Palette.Muted,
                cursorColor = Palette.Ink,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        val canSend = enabled && text.isNotBlank()
        Text(
            "Send",
            style = Type.Body.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (canSend) Palette.Ink else Palette.Muted.copy(alpha = 0.4f))
                .clickable(enabled = canSend, onClick = onSend)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(suggestions: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach {
            val shape = RoundedCornerShape(999.dp)
            Text(
                it,
                style = Type.Sub.copy(fontSize = 13.sp, color = if (enabled) Palette.Ink else Palette.Muted),
                modifier = Modifier
                    .clip(shape)
                    .background(Palette.Card)
                    .border(1.dp, Palette.Line, shape)
                    .clickable(enabled = enabled) { onPick(it) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

private const val PROBE_SUGGESTION = "Try to break the rules (scripted test)"

/** Who a step comes from. Null for notes about the run itself (token counts, API errors). */
private fun actorOf(step: Step): String? = when (step.kind) {
    Step.Kind.USER -> Actors.YOU
    Step.Kind.SAYS, Step.Kind.PROPOSES -> if (step.source == "scripted test") Actors.PROBE else "Assistant (${step.source})"
    Step.Kind.BROKER -> Actors.DEPUTY
    Step.Kind.INFO -> null
}

/** One request and everything that followed it, with a caption whenever the actor changes. */
@Composable
private fun RunCard(run: List<Step>, working: Boolean, onReply: ((String) -> Unit)? = null) {
    DCard(padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            run.forEachIndexed { i, step ->
                val actor = actorOf(step)
                if (actor != null && step.kind != Step.Kind.USER && (i == 0 || actorOf(run[i - 1]) != actor)) {
                    Text(actor.uppercase(), style = Type.Mono.copy(fontSize = 10.sp, color = if (actor == Actors.DEPUTY) Palette.Green else Palette.Muted),
                        modifier = Modifier.padding(top = 4.dp))
                }
                StepView(step)
            }
            if (working) Text("working…", style = Type.Mono)
            if (onReply != null) {
                var reply by rememberSaveable { mutableStateOf("") }
                Spacer(Modifier.height(4.dp))
                AskInput(reply, onChange = { reply = it }, enabled = true, onSend = { onReply(reply); reply = "" }, placeholder = "Reply to Claude…")
            }
        }
    }
}

/** One planner step. Proposals are the typed tool calls; Deputy's lines are its verdicts. */
@Composable
private fun StepView(step: Step) {
    when (step.kind) {
        Step.Kind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                step.text,
                style = Type.Body.copy(color = Color.White),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                    .background(Palette.Ink)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        Step.Kind.SAYS -> Text(step.text, style = Type.Body)
        Step.Kind.INFO -> Text(step.text, style = Type.Mono.copy(fontSize = 10.5.sp), modifier = Modifier.padding(top = 4.dp))
        Step.Kind.PROPOSES -> ProposalCard(step)
        Step.Kind.BROKER -> {
            val refused = step.text.startsWith("refused")
            Text(
                step.text,
                style = Type.Mono.copy(color = if (refused) Palette.Red else Palette.Muted),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (refused) Palette.RedBg else Color.Transparent)
                    .padding(horizontal = if (refused) 6.dp else 0.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProposalCard(step: Step) {
    var open by remember { mutableStateOf(false) }
    val detail = step.detail
    DCard(padding = 10.dp, modifier = Modifier.clickable(enabled = detail != null) { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("asks Deputy: ", style = Type.Mono)
            Text(step.text, style = Type.Mono.copy(fontSize = 13.sp, color = Palette.Ink), modifier = Modifier.weight(1f))
            if (detail != null) Text(if (open) "hide" else "details", style = Type.Mono.copy(color = Palette.Green))
        }
        if (open && detail != null) {
            Text(
                detail,
                style = Type.Mono.copy(fontSize = 10.5.sp),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Chip).padding(8.dp),
            )
        }
    }
}

@Composable
private fun TodayCard(snapshot: BrokerSnapshot, items: List<ActivityItem>, onSeeActivity: () -> Unit) {
    val today = snapshot.now.toLocalDateTime(snapshot.zone).date
    val todays = items.filter { it.at.toLocalDateTime(snapshot.zone).date == today }
    fun count(tone: Tone) = todays.count { it.tone == tone }
    Column {
        SectionLabel("Today")
        VSpace(8.dp)
        DCard {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                CountRow("Actions taken", count(Tone.DONE))
                CountRow("Asked you first", count(Tone.ASKED))
                CountRow("Questions from others", count(Tone.INBOUND))
                CountRow("Blocked attempts", count(Tone.REFUSED), Palette.Red)
            }
            Text(
                "See activity",
                style = Type.Sub.copy(color = Palette.Green, textDecoration = TextDecoration.Underline),
                modifier = Modifier.padding(top = 10.dp).clickable(onClick = onSeeActivity),
            )
        }
    }
}

@Composable
private fun CountRow(label: String, n: Int, color: Color = Palette.Ink) {
    Row {
        Text(label, style = Type.Sub.copy(color = color), modifier = Modifier.weight(1f))
        Text("$n", style = Type.Sub.copy(color = color))
    }
}
