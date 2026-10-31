package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import deputy.broker.Capabilities
import deputy.broker.CapabilitySpec
import deputy.broker.Direction
import deputy.broker.RiskTier

@Composable
fun CanDoScreen() {
    val groups = Capabilities.catalog.groupBy { it.group }
    ScreenColumn {
        item { ScreenHeader("What Deputy can do", "Every action is typed, and each one says up front whether it asks you first.") }
        items(groups.entries.toList(), key = { it.key }) { (group, specs) -> GroupCard(group, specs) }
    }
}

@Composable
private fun GroupCard(group: String, specs: List<CapabilitySpec>) {
    val head = specs.first()
    DCard {
        Row(Modifier.alpha(if (specs.none { it.available }) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Text(group, style = Type.CardTitle, modifier = Modifier.weight(1f))
            Text("${head.adapterLabel} · tier ${head.adapterTier.rank}", style = Type.Mono)
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            specs.forEach { CapabilityRow(it, showAdapter = it.adapterLabel != head.adapterLabel) }
        }
    }
}

@Composable
private fun CapabilityRow(spec: CapabilitySpec, showAdapter: Boolean) {
    val dimmed = !spec.available
    Row(Modifier.alpha(if (dimmed) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, style = Type.Body, modifier = Modifier.weight(1f, fill = false))
                if (spec.direction == Direction.INBOUND) {
                    Spacer(Modifier.width(6.dp))
                    Badge("inbound", BadgeStyle.INBOUND)
                }
            }
            val notes = listOfNotNull(
                if (showAdapter) "${spec.adapterLabel} · tier ${spec.adapterTier.rank}" else null,
                if (dimmed) "not connected yet" else null,
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = Type.Mono)
        }
        Spacer(Modifier.width(10.dp))
        val (label, style) = when {
            spec.requiresHumanGesture -> "Your tap" to BadgeStyle.TAP
            spec.risk == RiskTier.REVERSIBLE -> "Just does it" to BadgeStyle.JUST
            spec.risk == RiskTier.COMPENSABLE -> "Undoable" to BadgeStyle.UNDO
            else -> "Asks first" to BadgeStyle.ASK
        }
        Badge(label, style)
    }
}
