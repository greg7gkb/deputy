# Build log

The running record of what was built, what broke, and what it taught. This log plus
the working prototype is the real deliverable of the project. Newest entries on top.

Keep entries short and honest. A failure that taught something is worth more here
than a success that did not.

---

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
- Repo initialised with git (baseline staged, not yet committed).

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
