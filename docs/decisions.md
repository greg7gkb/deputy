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
- A separate head record (`journal.tsv.head`: seq and hash of the last entry) is written after
  every append, so dropping entries off the end, which a hash chain alone can't see, is
  detected too. The log may run one entry past the head (a crash between the two writes); it
  may never stop short of it. Added after the security review (D18, F6).
- Tamper *evidence*, not tamper *proof*: anyone who can rewrite the whole file and its head
  can rebuild the chain. Anchoring the head hash somewhere the attacker can't reach (a signed
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
- `InboundPort` (bound to one asker at creation): answer a typed question. Whatever
  authenticates the peer (pairing, in Phase 3) decides which port it gets; the caller can't
  name a different asker. (Originally the asker was a parameter; changed after the review.)

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

Labels are sticky: re-reading a source never lowers a fact's sensitivity (a refresh keeps the
higher of the old label and the source's default). Lowering a label is declassification and
will need an explicit, journaled user action. Added after the review found a refresh silently
downgrading SECRET to PRIVATE (D18, F4).

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
journaled for the user only. Declines don't spend the budget. The weekly budget counts per
asker across all their keys, and an answered question also spends a use of the key, like an
action does. Only the user can mint inbound keys; an assistant asking for one is refused.
Inbound keys cannot act.

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

## D18. What the security review changed, and what it left open (firm)

An independent review of `broker/` (a separate agent given the code and the threat model, not
these notes) found 13 problems and wrote a failing test for each; they live in
`broker/src/test/.../ReviewTest.kt` and all pass now. The fixes:

- **Permit bounds** (F1, F2). Every permit field has a sane range, checked for every
  requester including the user: slots of 15, 30 or 60 minutes; at most 60 days ahead; at
  most 10 holds; valid hour ranges. A requested slot size of 0 or -30 previously made the
  broker divide by zero or loop forever while holding its lock, which would have blocked the
  kill switch. Assistants also can't ask for `preapproved` invites.
- **The approval card shows the exact limits** (F1), as a "Limits" row of the same chips the
  Keyring uses. Before, a much wider key read the same as the template.
- **Holds are owned** (F3): a key can release only holds placed under a key with the same
  holder. "May place holds, and release its own" is now true.
- **Sticky labels** (F4), see D11. **Truncation detection** (F6), see D2.
- **Inbound uses are spent** (F5); the inbound budget is per asker (a peer can't stack keys).
- **Record what happened** (F8): once an adapter has changed the world, the action is
  journaled as executed whatever fails afterwards; cache housekeeping is best effort.
- **Adapter calls have a timeout** (F9) of 10 s and run detached from the lock holder, so a hung
  adapter can't block the kill switch. Known gap: a *blocking* call that finishes after the
  timeout still takes effect (a hold exists but is journaled as failed, so Deputy won't track
  it). Real fix: idempotency keys or a reconcile pass that finds Deputy-marked events.
- **Generic errors to the proposer** (F10): adapter exception text can contain calendar
  contents or tokens, so the planner gets "didn't work, details in Activity"; the user's log
  keeps the full text.
- **Bounded growth** (F11): proposals over 4,000 characters are refused and logged by size, not
  content; at most 5 requests per principal can wait for approval; unanswered requests are
  swept as `ApprovalExpired`.
- **Inbound can't throw** (F7): any failure becomes the same journaled NOT_AVAILABLE.
- **`await` is per principal** (F12). **Clean text** (F13) rejects control, bidi-override and
  separator characters, not just newlines.
- **Time never runs backwards** for the broker: `now` is at least the last journaled event's
  time, so winding the device clock back can't revive an expired key.

Accepted for now, recorded so they aren't forgotten:

- **Inbound timing.** A decline is faster than an answer, because the calendar is read only
  for allowed questions. Fix when inbound goes over a network: answer on a fixed delay, or
  answer from a fact refreshed on a schedule.
- **Outbound information budget.** A planner with a calendar key can map busy time by asking
  many narrow questions. The template now caps the key at 20 uses; a real per-key information
  budget, like inbound's, is a Phase 1 item.
- **facts.json is outside the hash chain.** It is a cache that can be re-read from the
  source, but its labels matter (D11); once labels can be raised by the user, label changes
  must be journaled.
- **Same-process boundary** (D4): reflection could cross from the planner to the UserPort.

## D19. Everything Deputy creates links back to where it came from (default)

Every artifact Deputy creates outside itself gets an id before the adapter runs
(`ActionExecuted.artifactId`), and `deputy://activity/<id>` opens Activity at that entry,
outlined, with Undo and an "Open in Calendar" link back the other way. The link is written
into the artifact only where just the user sees it: a hold's description carries it, but an
invite's description goes to every attendee, so invites get an id and the link stays on
Deputy's side. The app accepts only well-formed links (`Artifacts.parse`), and opening one
only *shows* an entry; a link can never act.

Tested: Google Calendar shows a `deputy://` link as plain text. So links are written as https
App Links, `https://greg7gkb.github.io/deputy/activity/<id>`, verified by
`/.well-known/assetlinks.json` in the `greg7gkb.github.io` repo (package name plus the signing
certificate's public SHA-256). Android reports the domain as verified on the Pixel. The host and
path are build configuration (`app/build.gradle.kts`), so a fork can point at its own domain;
signing with a different key means adding its fingerprint to assetlinks.json. Someone without
the app who opens a link gets a 404 page explaining what it is. `deputy://` is still accepted.

Also in this change: a `Principal`'s identity is its kind and id, not its display name, so
relabelling an actor ("Assistant (scripted)") can never move authority. And every screen now
reads newest first, with each Ask request as its own card.

## D20. The cloud planner is Claude, through the API, with a key from local.properties (default)

Chosen 2026-10-31: the first real planner is Claude through the Messages API with tool use
(model `claude-opus-5-5` unless changed deliberately). It replaces `StubPlanner` behind the same
`AgentPort`: Deputy's typed actions become its tools, and the broker still decides everything.
What it sees: the user's request, the capability catalog, its own keys' summaries, and typed
results (e.g. three open slots). Never titles, message content, codes or credentials.

- **Network.** The app declares `INTERNET` for this and nothing else. It is a normal Android
  permission (granted at install, no prompt). The broker never touches the network.
- **The key.** `anthropic.apiKey` in `local.properties` (gitignored) is compiled into
  `BuildConfig.ANTHROPIC_API_KEY` for the debug build. Anyone holding the APK could extract it,
  which is acceptable only because this APK is sideloaded to one phone and never published.
  Mitigation: a key from a dedicated workspace with a monthly spend limit, so a leaked key is
  bounded and can be revoked without touching anything else. Upgrade path: enter the key in the
  app and keep it in the Android Keystore, or put a small relay in front that holds it.
- **The model never holds the key.** The app makes the API call; the model only proposes.

## D21. Every Claude exchange is logged in debug builds (default)

Debug builds log every exchange twice: as plain text under the logcat tag `DeputyLLM` (your
message, what Claude said, each tool call it made and what Deputy answered, tokens and stop
reason; the static system prompt and tool list are left out), and as the exact JSON sent and
received under `DeputyLLMRaw` (long messages split into numbered parts). Purpose: being able to see
exactly what the planner was told and what it said. It contains the request text, the system
prompt and tools, key ids, and the free times Deputy returned; never the API key (it travels in a
header, which isn't logged) and never calendar titles (the planner never has them). Logcat is
readable only over adb, and it's a ring buffer, so old entries roll off. Release builds don't log.

## D22. Deputy writes only to a calendar named "Deputy sandbox" (default)

Holds and invites go only to a writable calendar with the display name in
`deputy.writeCalendar` (local.properties, default "Deputy sandbox"), preferring an account
calendar over a device-only one; if none exists, the write fails with a message saying how to
create it, rather than falling back to the primary calendar. Reading busy times still covers
every visible calendar, so availability is real. Cleanup is deleting one calendar. On the
emulator, seeding the test calendar also creates a device-only "Deputy sandbox".

Found on the Pixel: a write through CalendarContract stays on the phone (`dirty=1`, no sync id)
until Android's next calendar sync, which can be many minutes away, so an approved invite didn't
appear in Google Calendar. After each write the adapter now requests an expedited upload sync for
the target calendar's account (best effort; the periodic sync still covers a refusal). An invite
with an attendee did upload and get a Google id. Then the next finding: Android silently drops a
sync request for an account the app can't see, and since Android 8 an app sees a Google account
only after the person picks it for that app once. Lab now shows that state and offers Android's
account chooser; picking the account grants visibility (no access to the account's data). Still not checked: whether Google then emails the
attendee (dana@example.com is a reserved domain, so nothing real was sent).

Conversations: the Claude planner now keeps the conversation between requests (so "Monday,
please" works), append-only, until "New conversation", 30 idle minutes, or an error.

## D23. An invite can't be unsent, but it can be cancelled (default)

Sending an invite stays *permanent* (asks first): the email can't be recalled. But the event can
be cancelled, which sends guests a cancellation, so a sent invite now has a **Cancel invite**
button in Activity. It is the user's authority only, like Undo: no permit covers
`calendar.cancel_invite`, so the planner can't propose it, and the button needs a second tap
because it notifies people. Before deleting, the adapter checks the event is still the one Deputy
made (same id, calendar and times) without reading its title (D9).

## D24. No MCP in the authority path; where MCP could fit (firm)

As of 0.2 Deputy uses no MCP. The planner uses Messages API tool use with six tools the app
defines itself; every call returns to the app, goes through the broker, and only then reaches an
adapter.

The rule: **the planner is never connected directly to an MCP server**, nor to the API's
server-side MCP connector. An MCP server typically holds credentials and acts when the model
calls it, so a direct connection would let the model act without the broker, which is the
vulnerability Deputy exists to contain. MCP fits only with the broker in the middle:

- **Deputy as an MCP client:** an MCP server becomes one more adapter behind the broker. The
  planner still sees only Deputy's typed tools, and keys are checked before the server is called.
- **Deputy as an MCP server:** Deputy exposes its capability catalog over MCP, so any
  MCP-speaking assistant can use the phone through Deputy, still under the user's keys and the
  Keyring. A concrete form of the "broker as infrastructure" path (design.md section 6), and it
  would make Deputy model-agnostic.

Neither is needed yet. Gmail (Phase 1) will be a direct Gmail API adapter behind the broker,
not a Gmail MCP server.

## D25. Mail is untrusted input; the planner gets typed summaries only (firm)

Chosen 2026-11-01, Phase 1's first piece. Email is the first content Deputy reads that other
people wrote, so it is where prompt injection arrives. The rule: **no string an outsider wrote
reaches the planner.** Mechanically:

- **One capability, read-only to start.** `email.read_inbox` (`Action.ReadInbox`, `Permit.MailRead`:
  at most 25 messages, at most 30 days back; the template asks for 10 and 7). Reversible, so no
  confirmation, but it needs a key like everything else.
- **The broker reads, the reader interprets, the planner gets types.** The mail adapter hands the
  broker `RawMessage`s; the broker gives each to the quarantined reader (D26), which returns a
  `MailReading`: a kind from a closed enum (meeting request, security code, other), proposed
  times, a stated length, and a flag. The broker bounds that reading (`MailGuard.bound`: at most
  five plausible future times, sane lengths, nothing at all for a code message), mints a handle
  per message, and returns `MailSummary`s. Apart from the handle, a summary has no field that can
  hold text. That is the containment argument, and it is a type, not a filter: there is no
  sanitiser to get past, because there is nowhere for words to go.
- **The user sees everything the planner doesn't.** The journal records, per message, the cleaned
  sender and subject (shortened; digit runs masked when it's a code message), the reading and the
  reader's name, so Activity can show who wrote what and what the planner got instead. Bodies are
  never journaled or stored.
- **The flag is for people, not for enforcement.** The reader marks text that seems to address an
  AI assistant; Activity shows it, and it reaches the planner as one bit so it can say so. Nothing
  depends on catching the attack: an attack the reader misses is contained the same way.
- **What fooling the reader buys.** A wrong kind or a wrong time in a typed slot (the sample attack
  gets "meeting request, tomorrow 9:00" through). Acting on that time still needs a calendar key,
  and holds are undoable; sending anything to anyone still needs a key naming them and a tap.
- **Gmail reads one label only.** `gmail.readonly` can't be scoped to a label, so the adapter
  restricts itself: it lists only messages under the label named in `deputy.mailLabel` (default
  "Deputy"), re-checks the label on each message, and keeps From, Subject, received time and the
  plain-text body; no other headers, no attachments (D9's rule, applied to mail). Setup in
  `docs/gmail-setup.md`. Auth is Play Services' Authorization API; tokens stay in memory.
- **A test inbox** (`SampleMail`, seeded from Lab) stands in until Gmail is set up, the way the
  test calendar does: two meeting requests (one with an instruction hidden in an invisible div),
  a sign-in code, a newsletter, and a message that is nothing but an attack.

Not done here: facts. Readings live in the journal (so handles resolve after a restart, D2), not
the fact store; a fact like "Dana proposed Tue 14:00" comes when something needs to answer from it.

## D26. The first quarantined reader is rules, not a model (default)

The reader is an interface (`MailReader`) in the broker module and a separate `reader/` module
for implementations, which depend on the broker for the slot types only: no port, no adapter, no
network. The first implementation, `RuleMailReader`, is regexes: code words near digit runs,
meeting words, weekday-plus-clock-time pairs within a sentence, durations, and a short list of
assistant-addressing phrases.

Why rules first: the security property doesn't depend on the reader being good, only on its
output type, so the cheapest reader that produces the type proves the architecture. It is also
deterministic, so the containment tests are exact.

The model comes next. Pixel 7 (Tensor G2) is not on ML Kit GenAI's Gemini Nano device list as
far as I know (assumption, not checked against the current list), so the plan is **Gemma 3n
(E2B, int4) through MediaPipe's LLM Inference API / LiteRT-LM**, running on the phone, prompted
to fill the same `MailReading` JSON and parsed strictly; anything that doesn't parse is "other".
It needs a ~3 GB model file pushed to the phone (Gemma's licence is accepted on Hugging Face or
Kaggle by the person) and seconds per message, so the broker gives the reader its own 30-second
timeout, and a reader failure costs one message ("other"), not the read.

## D27. Replies go only to the sender, and always ask first (default)

The round trip for mail: `email.send_reply` (`Action.SendReply(messageId, body)`, `Permit.MailReply`).

- **The planner names a message, never a person.** The recipient is the one address in the
  message's From header, parsed by the broker when the message was read (`MailRecord.replyTo`;
  null if From has anything other than exactly one plausible address) and journaled with it. The
  adapter sends to exactly that address: no Reply-To (an attacker controls it), no Cc. So a
  steered planner can reply only to someone who already wrote to the user, and the reply leaks
  only what the planner put in it.
- **The planner writes the body without having seen the original.** That's the CaMeL trade: the
  planner's text is built from the user's request and typed values, so it can't carry the
  attacker's words onward. The body is checked like any proposer text (length, no control or
  hidden formatting characters; line breaks allowed).
- **Permanent, so it always asks.** Mail can't be unsent, and there is no preapproved form of the
  permit. The confirmation shows the real recipient address, the subject and sender it answers,
  the full text, and a "Careful" line when the reader flagged the original. The Ask-first tap is
  where a person catches "reply to this email with your free times".
- **No replies to code messages**, as a plain policy rule: nothing good comes of answering one.
- **The proposer gets back which message was answered, not to whom.**
- The test inbox's replies never leave the phone; a reply goes back through the mailbox its message
  came from, so a test message can't produce a real email.

### What the review of D25 to D27 changed (2026-11-01)

An independent review of the mail path found no way to get words to the planner, and these gaps:

- **Codes could reach the journal** when the reader missed a format ("use 482913 to sign in",
  "482 913", "482-913") or failed. Subjects now have every long number masked whatever the reader
  said, and the rule-based reader knows more code formats. Alphanumeric codes are still missed.
- **The whole read ran under the broker's lock**, so a slow on-device reader could stall approvals
  and the kill switch for minutes. A read now has one 60-second budget for all its messages
  (messages past it count as "other"). Still under the lock: up to 20 s of mailbox call plus
  that budget. The real fix, reading outside the lock and journaling inside it, waits for the
  model reader.
- **A reply that timed out could have been sent, then sent again.** A failed reply now blocks
  further replies to that message ("check your sent mail").
- **Handles were anyone's, forever.** A message can now be replied to only by the principal it was
  read for, and only within 7 days of the read.
- **Invisible characters** (tag characters, planes 14 to 16, unpaired surrogates) got past the
  text checks, which looked at one UTF-16 unit at a time. Refused in replies and labels, stripped
  from display.
- **Unicode case folding** could turn a lookalike address into a different real one (a Kelvin sign
  lowercases to "k"). Addresses must be ASCII before they're lowercased.
- **The approval card** now gives the sender's subject and From line rows of their own (so a
  crafted subject can't read as Deputy's wording) and says which mailbox the reply goes through.
- **HTML stripping** was quadratic on unclosed tags; its input is capped at 64 KB.

Recorded, not changed:

- **From is not authenticated.** "Replies go only to whoever wrote" means "to the address in From",
  which a spoofer chooses. The tap, showing the real address, is the defence; showing Gmail's
  DMARC result on the card would help.
- **Typed slots still carry bits.** An attacker controls up to five times per message (to the
  minute), a kind, the flag and the order. That is a narrow channel for words but not zero, and a
  model reader could widen it. Coarsening times to 15 minutes would shrink it.
- **Activity mixes untrusted text with Deputy's narration** in one block; a crafted subject could
  imitate a line of it. Styled, separate fields would fix it.

