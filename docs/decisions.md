# Decisions

The foundational choices behind the Phase 0 build, with the reasoning and what would make
each one worth revisiting. Code comments cite these as D1, D2, and so on. Newest decisions
go at the end; a decision that is replaced keeps its entry, marked superseded.

Status key: **firm** (load-bearing, change deliberately), **default** (a reasonable pick
that unblocked work; change freely), **assumption** (something believed true but not
verified).

---

## D1. Kotlin; the broker is a pure Kotlin/JVM module (firm)

`broker/` has no Android imports and depends only on the Kotlin stdlib and kotlinx
(coroutines, serialization, datetime). `app/` is the Android host.

- The reference monitor is unit-tested on a laptop in seconds (26 tests, ~3 s), which is
  where the hard rules are actually proven.
- Kotlin Multiplatform was considered and deferred (see the 2026-10-31 log): Deputy's
  adapters are Android-only by nature, and the likely second platform (a relay server) is
  JVM anyway. The core avoids `java.*` types in its API so a later move is cheap. The two
  JVM-only spots are SHA-256 (`java.security`) and file storage (`java.io`), both isolated.
- Toolchain copied from a known-good project on this machine: AGP 9.4.1, Kotlin 2.4.21,
  Gradle 9.8.2, Compose BOM 2026.09, minSdk 29, targetSdk 37.

## D2. The broker is event-sourced from a hash-chained journal (firm)

Every state change (key minted, use spent, key revoked, kill switch, approval asked and
answered, action done or refused, facts refreshed, inbound question answered) is an
`Event` appended to the journal. Broker state is a fold over the journal; it is never
written any other way.

- "Nothing changes without an audit record" is true by construction rather than by
  discipline. There is no code path that mutates a grant without journaling, because there
  is nowhere else to mutate it.
- The Activity screen is a projection of the same journal, so what the person reads is what
  the broker acted on.
- Each line is `seq<TAB>prevHash<TAB>hash<TAB>eventJson`, with
  `hash = sha256(seq|prevHash|eventJson)` over the exact stored text. Verification never
  re-encodes, so adding fields to events later does not break old chains.
- On load, a broken chain (edited, deleted or reordered entry) puts the broker in a
  **fail-closed** state: every outbound action and every user operation is refused with
  `INTEGRITY`, and every inbound question gets "not available", until a person looks. Tested.
- Tamper *evidence*, not tamper *proof*: anyone who can rewrite the whole file can rebuild
  the chain. Anchoring the head hash somewhere the attacker can't reach (a signed
  checkpoint in the Android Keystore, or a periodic hash published to a second device) is
  the upgrade path.
- Cost: replay on startup. Trivial at Phase 0 sizes; snapshots or compaction can come later.

## D3. Tokens are broker-held records behind unguessable handles (firm for Phase 0)

A grant (`Grant`) lives only in the broker. The holder receives a 128-bit random handle
(`g_…`) and nothing else. Every proposal must present a handle; the broker looks it up and
checks the holder, status, budget and permits.

- No cryptographic tokens yet: while authority never leaves the device, a signature proves
  nothing the lookup doesn't. Biscuit-style signed, attenuable tokens come in when
  authority crosses a boundary: guest passes, Deputy-to-Deputy, the relay.
- **Non-delegable by holder binding**: a handle presented by anyone but its holder is
  refused (`NOT_HOLDER`), even if it leaks. Tested with a leaked inbound handle.
- Not ambient: there is no "the assistant has calendar access". Each call names the key it
  acts under, and a key covers only its permits.

## D4. Three ports, bound by the host: AgentPort, UserPort, InboundPort (firm)

The broker exposes authority through three narrow interfaces:

- `AgentPort` (bound to one principal at creation): see the catalog, list its own keys,
  request a key, propose an action, await a decision. It cannot approve, revoke or mint.
- `UserPort`: approve, decline, mint, revoke, revoke all, undo, refresh. Only the UI holds it.
- `InboundPort`: answer a typed question from a named asker.

The principal is fixed when the host creates the port; the code holding the port cannot
choose it. In-process, this separation is a type-level boundary, not an OS one: the planner
and the UI share a process, so reflection could cross it. That is acceptable while the only
planner is local scripted code; once the planner is a cloud model, the boundary is the
network and the wire format (D7). Moving the broker into its own process or APK is a
Phase 1+ option if a local model ever runs untrusted code.

The debug-only adb driver can reach the agent and inbound ports but deliberately not the
user port: approvals stay behind a real tap even in testing. It is guarded by the `DUMP`
permission, which only the adb shell holds.

## D5. Phase 0's planner is a scripted stand-in holding only an AgentPort (default)

`StubPlanner` routes a few requests by keyword. It is deliberately not a model: the claim
under test is that the broker doesn't care who proposes. Swapping in an LLM changes what
proposes, not what is allowed. It includes a "try to break the rules" scenario that behaves
like a compromised planner (no key, guessed key, leaked key, out-of-scope ranges,
releasing someone else's event, asking for inbound or never-expiring authority); every
attempt is refused and journaled.

## D6. Adapters are typed ports; the broker is their only caller (firm)

`CalendarAdapter` has four functions over typed values (`readBusy`, `placeHold`,
`releaseHold`, `createEvent`). Adapters never see grants, principals or proposals. Their
internal references (calendar event ids) are stored by the broker and never returned to a
proposer, which gets Deputy's own hold ids instead.

## D7. Actions and outcomes are a closed, serializable vocabulary (firm)

`Action` is a sealed type whose serial names are capability ids (`calendar.place_hold`).
There is no free-text action and no "do anything" escape hatch. What a planner cannot
express, it cannot do: there is no action for opening the garage or reading event titles.
`Outcome` and `GrantRequest` are serializable too, so the same types are the wire format
for a remote planner's tool calls and, later, Deputy-to-Deputy messages.

## D8. Wording is rendered deterministically in the core (default)

Key sentences ("Can see when you're busy, not what."), chips, activity narration and
approval cards are pure functions in `broker/present`. The UI only lays them out.
Testable, consistent between Keyring and Activity, and if the wording is ever wrong it is
wrong in one place. Localisation is out of scope for Phase 0.

## D9. Minimisation by construction in the calendar adapter (firm)

`readBusy` projects only begin, end, availability, status and self-attendee status. Titles,
descriptions, locations and attendees are never queried, so no bug above the adapter can
leak them. Checked on the emulator: none of the seeded titles appear in the journal or the
fact store. Events marked free, cancelled, or declined by the user don't count as busy.
Deputy's own events carry a marker, and `releaseHold` deletes only events that carry it.

## D10. Facts: one current value per key, history in the journal (default)

The fact store holds the latest observation per key (`calendar.busy`), with provenance
(*observed*), sensitivity (*private*), source and freshness (15 minutes). Values are not
copied into the journal; the journal records that a refresh happened and how many items it
had. A stale, missing or too-narrow fact is re-read on demand. Placing or releasing a hold
marks the fact stale. Both outbound slot-finding and inbound answers read from the fact
store, never from the adapter directly.

## D11. Information-flow labels are on facts; secrets never reach a sink (default)

Every fact has a sensitivity (`PUBLIC`, `PRIVATE`, `SECRET`). `FlowGuard` refuses to derive
a planner result or an inbound answer from a `SECRET` fact. In Phase 0 nothing is secret
yet (one-time codes arrive with notification triage in Phase 1); the rule exists and is
tested now so Phase 1 extends it instead of retrofitting it.

## D12. Risk tiers decide confirmation; approval re-checks (firm)

Reversible and compensable actions run if the key allows them. Permanent ones (sending an
invite) return `AwaitingUser`; the person sees a card rendered from the typed action and
approves or declines. Approval re-runs the full policy check, so a key revoked or used up
while the person was deciding cannot be resurrected by tapping yes. Approvals expire after
15 minutes. A permit can be marked `preapproved` (the mockup's "Auto for contacts") to
skip asking, which is how "minimise how often Deputy asks" will be tuned.

## D13. Undo is the user's authority, journaled like anything else (default)

Undoing a hold is a `ReleaseHold` executed by the user principal with no key, marked
`undoOf` the original entry. It can happen once. Only holds are undoable in Phase 0.

## D14. Inbound runs through the same grants, policy style and journal (firm, experimental)

Phase 3 was pulled forward in miniature to test the "same broker, other direction" claim.
An inbound key is an ordinary `Grant` with `direction = INBOUND` and an
`InboundFreeBusy` permit (weekdays 18:00 to 21:00, 14 days ahead, slots up to 2 h, 6
answered questions per rolling week). The asker gets `FREE`, `BUSY`, or `NOT_AVAILABLE`;
every decline, for whatever reason, is the same `NOT_AVAILABLE`, and the reason is
journaled for the user only. Declines don't spend the budget. Only the user can mint
inbound keys; an assistant asking for one is refused. Inbound keys cannot act.

What it reused: `Grant`, `GrantRecord` status and expiry, revoke and kill switch, the
journal, fact freshness, the flow guard, Keyring rendering. What it needed new: one permit
type, one policy function (`authorizeInbound`), one event type, and a rolling-window
budget count. That is the first evidence for design.md section 10's claim.

## D15. Storage: app-private files, no backup (default)

The journal (`journal.tsv`) and facts (`facts.json`) live in app-private storage, which
the Android sandbox keeps from other apps. `allowBackup=false` keeps them out of cloud
backups. Facts are written atomically (temp file and rename); journal appends are synced to
disk. SQLite was considered and not needed: the journal is append-only text and the fact
store is a handful of values.

## D16. Holds are tentative events in the primary writable calendar (assumption)

Holds are written as `AVAILABILITY_TENTATIVE` events titled "Hold: …" in the user's primary
writable calendar. Assumed fine for a single user. On a real phone with Google Calendar,
writes through CalendarContract sync up to the account, so a hold is visible on other
devices, and an invite with attendees would really be sent by Google. That has not been
tested on a real account yet; the emulator uses a local calendar, where nothing leaves the
device.

## D17. Time is local-date in, instants out (default)

Planners speak in local dates and hours (`from`, `to`, `earliestHour`), because that is how
people ask. Everything stored is an absolute instant. The broker's time zone is the
phone's at startup.
