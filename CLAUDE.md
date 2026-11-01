@README.md

# Working on Deputy

The README above is the project description: architecture, hard rules, risk tiers and
build order. Treat its hard rules as non-negotiable. This file adds how to work in the repo.

## Where things are decided

- `docs/design.md`: the reasoning behind the project. Read the relevant section when a
  design question comes up, not every session.
- `docs/decisions.md`: implementation decisions (D1, D2, ...) and the assumptions made to
  unblock work. Record new ones there; code comments cite them by number.
- `docs/log.md`: the running build log, newest first. Keep it current: what was built,
  what broke, what it taught. That log plus the working prototype is the actual
  deliverable of this project, more than the code itself.

## Code rules

- `broker/` stays pure Kotlin/JVM: no Android imports, stdlib and kotlinx only, and no
  `java.*` types in its public API (so it can go multiplatform later). Everything that
  enforces lives there, and so does all user-facing wording (`broker/present`).
- Broker state is event-sourced (D2): change state only by appending an `Event` to the
  journal. Never add mutable broker state the journal can't reproduce.
- New capabilities are new `Action` and `Permit` types with checks in `Policy`; there is no
  free-text or catch-all action (D7).
- Never connect the planner directly to an MCP server or the API's MCP connector; MCP only
  goes behind the broker as an adapter, or in front of it with Deputy as the server (D24).
- Adapters read the minimum. The calendar adapter must never query titles, descriptions,
  locations or attendees (D9).
- Run `./gradlew :broker:test` after any broker change; add a test for every new rule.

## Testing on the emulator

The debug build has an adb-only driver (guarded by the `DUMP` permission):

```
adb shell am broadcast -a io.github.greg7gkb.deputy.DRIVE -n io.github.greg7gkb.deputy/.DebugDriver --es cmd <cmd>
```

Commands are listed in `app/src/debug/.../DebugDriver.kt`; results go to logcat under
`DeputyDriver`. It deliberately cannot approve, decline or revoke: those need a real tap
(use `adb shell input tap` against a `uiautomator dump`).

## Versioning

- The Android app's version lives in `app/build.gradle.kts`: `versionName` is `MAJOR.MINOR`
  (0.x while the project is a prototype), `versionCode` is an integer that only goes up.
- Bump `versionName` for each milestone worth naming, and `versionCode` for every build that gets
  installed on a real phone (Android refuses to update to a lower code).
- Tag the milestone commit `vX.Y` and add a line to `docs/log.md`. The Lab screen shows the
  running version.
- History: 0.1 was Phase 0 end to end (broker, Keyring, kill switch, calendar adapter). 0.2 adds
  the Claude planner, links back from what Deputy creates, and the feedback round. 0.3 closes
  Phase 0: follow-up conversations, sandbox calendar, LLM logs, sync fixes, cancelling invites
  (`docs/phase0.md`).

## Commits

- The repo is public. Never put Claude session links in commit messages or tracked
  files; record them in the gitignored `sessions.local.md`.
- Commits use the GitHub noreply address set in the repo's git config.
