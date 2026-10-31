# Build log

The running record of what was built, what broke, and what it taught. This log plus
the working prototype is the real deliverable of the project. Newest entries on top.

Keep entries short and honest. A failure that taught something is worth more here
than a success that did not.

---

## 2026-10-31 — Adapter ladder, Phase 0 adapter, revised screens

- Adapter tiers widened from four to six: on-device API, cloud API, code
  trigger, handed affordance, UI driving, go to the app. The bottom rung is
  the definition of failure (no utility offered). Handed affordance ranks above
  UI driving on purpose. Stance: optimistic, since Google's on-device and cloud
  APIs cover a lot.
- Phase 0 adapter is now the on-device calendar, not the garage. The hard rule
  caps the garage at tier 4, so it could never show "acted under a token."
  Calendar is tier 1, spans every risk tier, seeds the fact store, and is Phase
  3's first inbound answer. Garage moves to Phase 1. Reasoning in `design.md`
  section 11.
- Inbound responder: no public cloud surface for now. Everything is proxied
  through the phone app, and any cloud piece is a relay reachable only by
  Deputy's own app, holding no facts or keys.
- The 10-28 screen PDF broke the garage hard rule in three places (assistant
  opens the gate, a voice request opens it, Sam's link fires it), because it
  predates the rule. Revised screens in `docs/ux/screens.html`, plus new ones
  for the inbound direction (Who may ask, Why this answer, What Deputy knows).
- Checking the scan for the guest-pass redesign: Door declares no guest-related
  deep link, so "prefill Door's guest form" is aspirational. Today it is nearly
  tier 6. Small example of the build correcting the design.
- Scanner moved out of the repo to `~/code/deputy-scan/`, where all scan code
  and output now live. Fixed the "~104 apps" wording: 119 packages scanned, 104
  of which declare any intent filter.

## 2026-10-31 — Inbound direction and the fact store

- Decided the "inverted data" idea (publish how to ask me; answer under policy)
  belongs in Deputy as the inbound side of the same broker, not a second project.
  Written up as `design.md` section 10; `CLAUDE.md` gains a Fact store component,
  a hard rule (broker answers inbound, fixed typed vocabulary, planner never does),
  and a Phase 3.
- The one change that affects Phase 0: facts get a first-class store with
  provenance labels (attested / observed / inferred / asserted) and freshness, and
  adapters write into it rather than caching privately.
- Composition analysis of the 115 scanned APKs added to `design.md` section 8;
  scripts and full report live in `~/code/deputy-scan/`. Headline: the median app's
  own code is 30% of its identifiable bytecode.
- Repo initialised with git; baseline committed.

## 2026-10-28 — Project seeded from design conversation

- Settled the concept, the architecture, the six single-user scenarios, and the
  two-Deputy multi-user flows. Captured in `CLAUDE.md` and `docs/design.md`.
- Ran a read-only capability scan of ~104 installed third-party apps
  (`tools/deputy_scan.sh`). Key finding: only the OS exposes clean on-device APIs;
  no third-party app in the set did. App Actions were declared by only 3 apps
  (Instacart `UPDATE_CART`, Strava exercise intents, NordVPN connect).
- Confirmed the garage app is Door (formerly Latch), Glance-based widgets. Decided
  the adapter embeds the real unlock button and keeps a human tap. No headless
  actuation of a physical lock (see the hard rule in `CLAUDE.md`).
- Next: Phase 0, the broker (token store, policy check, audit log) + Keyring + kill
  switch + one adapter, end to end.
