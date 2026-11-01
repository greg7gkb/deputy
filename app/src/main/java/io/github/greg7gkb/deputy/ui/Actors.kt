package io.github.greg7gkb.deputy.ui

import deputy.broker.Principal
import io.github.greg7gkb.deputy.DeputyApp

/**
 * One name per actor, everywhere. The app mixes real parts (you, Deputy, Claude) with stand-ins
 * (a scripted test, simulated friends), so the stand-ins always say so.
 */
object Actors {
    const val YOU = "You"
    const val DEPUTY = "Deputy"
    const val PROBE = "Scripted test"

    /** "Assistant (Claude)" or "Assistant (scripted)", by which planner is configured. */
    fun assistant(app: DeputyApp) = "Assistant (${app.planner.name})"

    fun label(p: Principal?, app: DeputyApp): String = when {
        p == null -> DEPUTY
        p.kind == Principal.Kind.USER -> YOU
        p == DeputyApp.PROBE -> PROBE
        p.kind == Principal.Kind.ASSISTANT -> assistant(app)
        app.lab.isSimulated(p) -> "${p.displayName} (simulated)"
        else -> p.displayName
    }
}
