package io.github.greg7gkb.deputy.ui

import deputy.broker.Principal
import io.github.greg7gkb.deputy.Lab

/**
 * One name per actor, everywhere. Phase 0 mixes real parts (you, Deputy) with stand-ins (a
 * scripted assistant, simulated friends), so the stand-ins always say so.
 */
object Actors {
    const val YOU = "You"
    const val ASSISTANT = "Assistant (scripted)"
    const val DEPUTY = "Deputy"

    fun label(p: Principal?, lab: Lab): String = when {
        p == null -> DEPUTY
        p.kind == Principal.Kind.USER -> YOU
        p.kind == Principal.Kind.ASSISTANT -> ASSISTANT
        lab.isSimulated(p) -> "${p.displayName} (simulated)"
        else -> p.displayName
    }
}
