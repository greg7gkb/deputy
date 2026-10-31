package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import deputy.broker.BrokerSnapshot
import deputy.broker.Direction
import deputy.broker.GrantId
import deputy.broker.GrantTemplates
import deputy.broker.Principal
import deputy.broker.present.KeyView
import deputy.broker.present.Keyring
import io.github.greg7gkb.deputy.DeputyApp
import kotlinx.coroutines.delay

@Composable
fun KeyringScreen(app: DeputyApp, snapshot: BrokerSnapshot) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(emptySet<GrantId>()) }
    var adding by remember { mutableStateOf(false) }

    val views = remember(snapshot) {
        snapshot.grants.sortedByDescending { it.grant.issuedAt }.map { Keyring.describe(it, snapshot.now, snapshot.zone) }
    }
    val direction = if (segment == 0) Direction.OUTBOUND else Direction.INBOUND
    val shown = views.filter { it.direction == direction }
    val active = shown.filter { it.active }
    val ended = shown.filterNot { it.active }
    val activeOut = views.count { it.active && it.direction == Direction.OUTBOUND }
    val activeIn = views.count { it.active && it.direction == Direction.INBOUND }

    fun revoke(id: GrantId) {
        revoking = revoking + id
        app.act({ app.broker.user.revoke(id) }) { revoking = revoking - id }
    }

    fun add() {
        adding = true
        app.act({
            if (direction == Direction.OUTBOUND) app.broker.user.mintGrant(Principal.Assistant, Direction.OUTBOUND, GrantTemplates.calendarForScheduling())
            else app.lab.pairZane()
        }) { adding = false }
    }

    ScreenColumn {
        item { ScreenHeader("Keyring", "Each key opens one thing, for a while. Newest first.") }
        item { Segmented(listOf("May do · $activeOut", "Who may ask · $activeIn"), segment) { segment = it } }
        if (active.isEmpty()) item {
            DCard {
                Text(
                    if (direction == Direction.OUTBOUND) "No keys. The assistant can't do anything until you give it one."
                    else "Nobody can ask about you.",
                    style = Type.Sub,
                )
            }
        }
        items(active, key = { it.grantId.value }) { KeyCard(it, Actors.label(it.holderPrincipal, app.lab), revoking = it.grantId in revoking, onRevoke = { revoke(it.grantId) }) }
        item {
            Column {
                SectionLabel("Add a key")
                VSpace(8.dp)
                LineButton(
                    if (direction == Direction.OUTBOUND) "Calendar for scheduling, for the assistant" else "Let Zane ask about weekday evenings",
                    onClick = ::add,
                    enabled = !adding && !(direction == Direction.INBOUND && active.any { it.holderPrincipal == app.lab.zane }),
                )
            }
        }
        if (direction == Direction.INBOUND) item { NeverAnsweredNote() }
        item { KillSwitch(activeOut + activeIn, pending = snapshot.pending.size) { app.act({ app.broker.user.revokeAll() }) } }
        if (ended.isNotEmpty()) {
            item {
                Text(
                    "Ended · ${ended.size}  " + if (showEnded) "▾" else "▸",
                    style = Type.Sub,
                    modifier = Modifier.clickable { showEnded = !showEnded }.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            if (showEnded) items(ended, key = { it.grantId.value }) { KeyCard(it, Actors.label(it.holderPrincipal, app.lab), revoking = false, onRevoke = {}) }
        }
    }
}

@Composable
private fun KeyCard(view: KeyView, holder: String, revoking: Boolean, onRevoke: () -> Unit) {
    DCard(Modifier.alpha(if (view.active) 1f else 0.55f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("held by $holder", style = Type.Mono)
                Text(view.title, style = Type.CardTitle)
                Text(view.sentence, style = Type.Sub, modifier = Modifier.padding(top = 3.dp))
            }
            if (view.active) {
                Spacer(Modifier.width(10.dp))
                SmallButton(if (revoking) "Revoking…" else "Revoke", onClick = onRevoke, enabled = !revoking)
            }
        }
        ChipRow(view.chips)
    }
}

@Composable
private fun NeverAnsweredNote() {
    Note(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.Ink)) { append("Never answered, for anyone: ") }
        append("event titles, who you meet, where you are. Every decline gets the same reply, \"not available\", so a refusal doesn't give anything away.")
    })
}

/** Two taps, no dialog: the first arms it for a few seconds, the second revokes everything. */
@Composable
private fun KillSwitch(activeKeys: Int, pending: Int, onRevokeAll: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) { delay(4_000); armed = false }
    }
    val nothing = activeKeys == 0 && pending == 0
    Column(Modifier.padding(top = 6.dp)) {
        LineButton(
            when {
                nothing -> "Nothing to revoke"
                armed && activeKeys == 0 -> "Tap again to cancel what's waiting"
                armed && activeKeys == 1 -> "Tap again to revoke 1 key"
                armed -> "Tap again to revoke all $activeKeys keys"
                else -> "Revoke everything"
            },
            onClick = {
                if (armed) { armed = false; onRevokeAll() } else armed = true
            },
            enabled = !nothing,
            color = Palette.Red,
            border = Palette.Red,
            borderWidth = 1.5.dp,
        )
        Text(
            "Stops every key at once, for the assistant and for everyone who may ask, and cancels anything waiting for you.",
            style = Type.Sub.copy(fontSize = 12.5.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
