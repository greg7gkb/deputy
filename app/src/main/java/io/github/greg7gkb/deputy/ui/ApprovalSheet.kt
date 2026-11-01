package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import deputy.broker.ApprovalKind
import deputy.broker.BrokerSnapshot
import deputy.broker.RequestId
import deputy.broker.present.Narrator
import io.github.greg7gkb.deputy.DeputyApp

/**
 * The broker asking first. Shown for the oldest live pending request, over every tab. It is a
 * plain overlay rather than a swipe-to-dismiss sheet: the only ways out are a decision or the
 * request expiring, so nothing is approved or declined by accident.
 */
@Composable
fun ApprovalSheet(app: DeputyApp, snapshot: BrokerSnapshot) {
    // A broker that failed its integrity check refuses every decision; don't offer one.
    if (snapshot.integrityError != null) return
    val live = snapshot.pending.filter { it.expiresAt > snapshot.now }.sortedBy { it.at }
    val req = live.firstOrNull() ?: return
    val view = remember(req, snapshot.now) { Narrator.approval(req, snapshot.zone, snapshot.now) }
    var deciding by remember { mutableStateOf<RequestId?>(null) }
    val busy = deciding == req.requestId

    fun decide(approve: Boolean) {
        deciding = req.requestId
        app.act({ if (approve) app.broker.user.approve(req.requestId) else app.broker.user.decline(req.requestId) }) { deciding = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Palette.Background)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(if (view.kind == ApprovalKind.MINT_GRANT) "New key" else "Asks first", BadgeStyle.ASK)
                Spacer(Modifier.width(8.dp))
                Text("from ${Actors.label(req.principal, app)}", style = Type.Mono, modifier = Modifier.weight(1f))
                if (live.size > 1) Text("1 of ${live.size}", style = Type.Mono)
            }
            Text(view.headline, style = Type.Title.copy(fontSize = 20.sp))
            Text(view.explanation, style = Type.Sub)
            DCard {
                KeyValueTable(view.rows)
                Spacer(Modifier.height(12.dp))
                Text(
                    view.machineLine,
                    style = Type.Mono.copy(fontSize = 10.5.sp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Chip).padding(8.dp),
                )
            }
            DarkButton(view.approveLabel, onClick = { decide(true) }, enabled = !busy)
            LineButton("Decline", onClick = { decide(false) }, enabled = !busy)
        }
    }
}
