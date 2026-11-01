# Phase 0: what it set out to prove, and what it proved

Closed 2026-10-31 at version 0.3. One page; the details are in `decisions.md` (D1 to D24) and
the day-by-day record is in `log.md`.

## The claim

An AI assistant can be useful on a real phone **without being trusted**. The model only
*proposes*. A small piece of deterministic code, the broker, holds the real access, checks every
proposal against permissions the person granted, asks before anything irreversible, and writes
everything down.

## What is now demonstrated, on a Pixel 7 with a real model and a real calendar

1. **The model can't act on its own.** Claude (`claude-sonnet-5-5`, Messages API tool use)
   reaches the calendar only through the broker's `AgentPort`. The planner log shows exactly
   what crossed: the request, tool calls, and the derived values Deputy returned.
2. **Authority is narrow, legible and revocable.** Keys with scope, expiry and use budgets,
   each rendered as one sentence in the Keyring; per-key revoke; a kill switch.
3. **A misbehaving planner is contained.** A scripted planner tries eight things outside its
   keys; all eight are refused and logged. An independent review of the broker found thirteen
   real holes (unbounded permit fields, label downgrade, log truncation, others), all fixed and
   tested (D18). 51 JVM tests.
4. **A well-behaved planner asks rather than overreaches.** Claude requests keys it needs and
   the person decides; asked to invite someone new, it asks for a new key naming them.
5. **Minimisation by construction.** The calendar adapter never queries titles; the planner
   only ever sees free times. (The value of this is less "privacy from Claude" than "nothing
   the planner doesn't need".)
6. **Ask first for the irreversible, undo for the rest.** Invites wait for a tap; holds can be
   undone, and sent invites cancelled, by the person only.
7. **The same broker answers questions about the user.** Inbound free/busy for a simulated
   friend reuses grants, policy, journal and Keyring with one new permit type (D14).

Plus the parts that make it usable: links from created items back to their Activity entry
(https App Links), follow-up conversations, a sandbox calendar for writes, and readable logs
of every LLM exchange.

## What the build taught that reading wouldn't have

- Android 15 hides one-time codes, and all SMS content, from third-party notification listeners.
- Calendar writes sit on the phone until a sync; Android silently drops sync requests from an
  app that can't see the account, and an app sees a Google account only after the person picks
  it once.
- Google Calendar doesn't make custom-scheme links tappable; verified https App Links work.
- With a real model, the meaningful protection against scope creep is often the *new key
  request* the person sees, not a refusal.

## What Phase 0 did not prove

**Containment of untrusted input.** So far the planner has read only the person's own
requests, so nothing hostile ever reached it. The threat the project exists for is prompt
injection: an email, invite or message that tries to steer the assistant. The design's answer
is the dual-model split: an on-device model reads untrusted content into typed values and holds
no authority; the cloud planner never sees that content; the broker sits between them.

That is Phase 1's first job: Gmail as the first untrusted source (Android doesn't redact email
the way it does SMS), and a demonstration that a malicious email provably can't make the
assistant do anything, with Activity showing why.

## Why this matters even if you already trust your assistant

Deputy's value is not keeping data from the model. It is bounding what can happen through the
model, whoever manages to steer it: anyone who can get text in front of it (most of a calendar
and an inbox is written by other people), a compromised account or key, or the model on a bad
day. The bounds are code you can read, not model judgment, and they matter more as agents act
unattended, on costlier or physical things, and alongside other agents.
