# Build log

The running record of what was built, what broke, and what it taught. This log plus
the working prototype is the real deliverable of the project. Newest entries on top.

Keep entries short and honest. A failure that taught something is worth more here
than a success that did not.

---

## 2026-10-31 — Validation on the phone, and what a refusal means

- Walked the validation list on the Pixel; nearly everything behaved as expected. One test
  clarified the model: "invite someone the key doesn't name" doesn't produce a refusal with
  Claude, because a well-behaved planner doesn't stretch a key it holds; it asks for a new key
  naming the new person, and that request, which the user sees and decides on, is the
  protection. Refusals show up when a planner overreaches, which Claude hasn't and the
  scripted test does on purpose.
- Recorded the stance on MCP (D24): none in the authority path; MCP only behind the broker or
  with Deputy as the server.
- Where Phase 0 stands: the plumbing and the UX of governed authority are shown end to end
  with a real model on a real calendar. Not yet shown: containment of *untrusted input*, since
  the planner has so far read only the user's own requests. That is Phase 1's first job.

## 2026-10-31 — Writes weren't reaching Google

- On the Pixel, an approved invite didn't appear in Google Calendar. The planner log showed
  Claude and the broker had done their part; the event sat in the phone's calendar store marked
  dirty, waiting for Android's periodic sync. A manual sync uploaded it in seconds. Deputy now
  requests an upload sync after every calendar write.
- Also from that session: Claude, asked "can you view my contacts?", said plainly it couldn't
  and named the tools it has, which is the behaviour the closed tool vocabulary is meant to give.

## 2026-10-31 — Version 0.2

- Started versioning the app: 0.1 (tagged retroactively) was Phase 0 end to end; 0.2 adds the
  Claude planner, links back from what Deputy creates, and the first feedback round. The Lab
  screen shows the running version. Convention in `CLAUDE.md`.

## 2026-10-31 (late night) — A real planner: Claude

- New `planner/` module (pure Kotlin/JVM): `ClaudePlanner` drives `claude-sonnet-5-5` through the
  Messages API with six strict tools that map one-to-one onto broker calls (`list_my_keys`,
  `request_key`, `find_free_slots`, `place_hold`, `release_hold`, `send_invite`). A hand-written
  loop rather than the SDK's tool runner, because a call can wait minutes for the user's tap and
  nothing may execute except through the `AgentPort`. Adaptive thinking at effort `medium`,
  prompt caching on the tools and system prompt, the server-side refusal fallback, and an
  append-only history so the model's thinking blocks replay unchanged.
- Tested against the real API on the laptop first (a live test, opt-in with `-PliveApi`): Claude
  listed its keys, asked for one, waited for approval, found slots, held one, and said what it
  did and didn't do. About 3 cents. Then on the emulator through the real UI.
- The scripted planner stays: as the assistant when no API key is configured, and as the
  "try to break the rules" script, which now has its own identity ("Scripted test") and is
  handed its own 10-minute key, so its refusals are never attributed to Claude.
- Noticed: asked for "one or two short sentences, no markdown", Claude still answered with a
  bulleted list of options. A prompt-tuning item, not a safety one.

## 2026-10-31 (night) — Links back from what Deputy creates

- Feedback from the first run on the Pixel: a hold in Google Calendar should link back to
  Deputy. Built generically (D19): every created artifact gets an id, holds carry a link in
  their description, and Activity opens at that entry with Undo and "Open in Calendar".
- Google Calendar rendered `deputy://activity/...` as plain text: custom schemes aren't
  linkified. Switched to https App Links on `greg7gkb.github.io`, which needed a fresh user
  site repo serving `/.well-known/assetlinks.json` (the old one, a friend's portfolio, is
  archived as `prince-website-2022`). Android verified the domain on the phone.
- Also from that feedback: every screen reads newest first, each Ask request is its own card,
  and every step and log entry names its actor (You, Assistant (scripted), Deputy, Zane
  (simulated)), because mixing real parts with stand-ins was confusing.

## 2026-10-31 (late) — What a notification listener sees on Android 15

- Added a bare NotificationListenerService (tier 1, the planned Phase 1 triage adapter) that
  logs only whether content was redacted, then sent real SMS through the emulator and posted
  notifications from the shell.
- Result: codes are hidden from third-party listeners ("Sensitive notification content
  hidden"), so is a phishing text that talks about codes, and so is *every* SMS from Google
  Messages, even "Running 10 min late". Plain notifications from other apps arrive intact.
  Details and the two readings in `design.md` section 8.
- What it taught: the OS is already enforcing part of "secrets never leak", which is
  encouraging for the thesis (the platform is moving the same way), but it also takes away
  the most natural Phase 1 demo. The phishing-text scenario as mocked up can't happen through
  a listener on Android 15, because the listener never sees the text. Phase 1 should either
  pick the SMS route deliberately (default-SMS role, or `READ_SMS` on a sideloaded build) or
  lead with email through the Gmail API, where nothing is redacted and Deputy's own labels and
  flow guard have to do the work. That choice is for the next session.

## 2026-10-31 (evening) — Phase 0, end to end on an emulator

**Built.** The broker (`broker/`, pure Kotlin/JVM), the Android app (`app/`), and 42 JVM
tests. Running on an API 35 emulator with a local test calendar, everything below works
through the real broker and the real CalendarContract provider (screenshots in
`docs/screenshots/`):

- The assistant asks for a "Calendar for scheduling" key; the approval card shows exactly
  what it would allow; on approval it finds three slots, never seeing a title, and places a
  tentative hold that appears in the calendar provider.
- "Send Dana an invite" asks for a second key scoped to dana@example.com, finds a slot, and
  then stops at an "Asks first" card because an invite can't be taken back. The search
  skipped the hold placed a moment earlier, because placing it marked the cached busy times
  stale.
- Zane's Deputy (simulated) gets a weekday-evening inbound key: Tuesday 7 pm is busy
  (climbing), Wednesday is free, Saturday and an unpaired stranger both get the same "not
  available", and the weekly budget cuts off on time.
- A deliberately tricked planner tries 8 things outside its keys (no key, a guessed key, a
  leaked key belonging to Zane, 60 days ahead, the middle of the night, deleting an event
  Deputy didn't create, asking for inbound authority, asking for a key that never expires).
  8 of 8 refused, each one in Activity as "policy said no, not the AI".
- Undo removes the hold from the calendar; "Revoke everything" kills all keys at once.
- None of the seeded event titles appear anywhere in the journal or the fact store.

**Decided.** Recorded as D1 to D18 in `docs/decisions.md`. The load-bearing ones: the broker
is event-sourced from a hash-chained journal, so nothing changes without an audit record
(D2); keys are broker-held records behind unguessable handles, bound to their holder (D3);
agents, the user and inbound askers each get their own narrow port (D4); actions are a
closed typed vocabulary, so what a planner can't express it can't do (D7); the calendar
adapter never queries titles at all (D9). Phase 3's inbound free/busy was pulled forward in
miniature: it needed one new permit type, one policy function and one event type, and
reused everything else. First evidence for "same broker, other direction".

**Broke, and what it taught.**

- The tests caught a 7-day lifetime cap meant for the assistant's keys also applying to the
  user's own, which would have refused "Let Zane ask" (30 days).
- An independent security review of the broker (a separate agent, given the code, not my
  reasoning) found 13 real problems. The worst: a key with a negative slot size would make the
  broker spin forever while holding its lock, so the kill switch could never run; a calendar
  refresh silently downgraded a secret label; deleting the last line of the log un-revoked a
  key undetected; any holds key could release anyone's hold. All fixed and now tested
  (D18). Lesson: the hard rules were enforced, but the *parameters* of authority weren't
  bounded, and a reference monitor has to distrust the shape of what it is asked to grant,
  not just whether it was granted.
- On the emulator, Undo failed: Android's calendar provider refuses a `where` clause on a
  by-id delete ("Selection not permitted"). The failure was journaled and shown in Activity
  rather than lost, which is the system working as designed. The fix checks Deputy's marker
  with a query, then deletes by id.
- Installing the new build over an old journal that had no head record put the broker into
  its fail-closed state ("the log's head record is missing") and refused everything,
  including an approval tap. That is correct, but it means a real upgrade path needs a
  migration step, not a silent pass.

**Not done yet.** No real Google account tested (D16: on a real phone an invite through
CalendarContract would really be sent). No language model: the planner is scripted (D5). The
Door, notification and Gmail adapters are Phase 1.

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
