# Deputy: design and reasoning

This document holds the "why" behind the decisions summarised in `CLAUDE.md`. It is
reference material, read it when a decision comes up, not every session.

## 1. The larger question

How do operating systems evolve once AI agents are first-class actors on them? The
goal of this project is to develop grounded hypotheses about that, not just to ship
an app. The method is build-led on purpose: reading and synthesis alone produce
circular conclusions (an LLM proposes concepts drawn from its training, then
"discovers" those same concepts in the literature). Building against a real phone
breaks the circularity, because the hardware does not care what was in the training
data.

## 2. The concept stack

Four ideas, which are one layered stack rather than four separate bets:

- **Remove the pixel detour.** Apps take structured intent, render it to pixels for
  a human, and agents then scrape those pixels to recover the intent that already
  existed. The evolution is apps exposing capability directly (typed) instead of
  only as a rendered surface. Android's AppFunctions and Apple's App Intents are the
  platform vendors building exactly this.
- **Govern authority with object-capability tokens, not ambient permissions.**
  Agents need scoped, delegable, revocable, time-boxed, budgeted authority, not a
  standing "has the payments permission." This is the Biscuit / macaroon /
  attenuation lineage, now showing up in IETF agent-token drafts.
- **Protect the trusted context from untrusted data (information-flow control).**
  The confused-deputy problem; prompt injection is just a confused-deputy attack.
  Google DeepMind's CaMeL is the worked implementation: a privileged planner that
  never sees untrusted content, a quarantined model that does, provenance labels,
  and a deterministic interpreter enforcing policy.
- **Rehost the agent as a first-class OS principal.** Agent as a scheduled,
  sandboxed, auditable actor; process-vs-task as the unit of execution;
  continuations; OS-wide provenance. This is the AIOS / AOHP / AgntOS research
  territory, and it is the half Deputy deliberately does not touch (see section 7).

The one-line thesis the build sharpened: the OS's role is shifting from protecting
apps from each other with the user trusted, to brokering scoped, revocable
authority between a user's agent and apps that do not trust that agent. The binding
constraint is app-developer willingness to expose capability, not agent capability.

## 3. Why the ledger is the product

The agent barely needs a screen. Trust is won or lost in the permission ledger (the
Keyring), the activity log, and the confirmation prompts. So those are the surfaces
to design first and best. The chat box is almost incidental.

## 4. Architecture decisions

- **Broker as sole credential holder.** Because the Android sandbox already
  guarantees no other app can read the broker's store, "only the broker holds keys
  and everything routes through it" is real, discretionary enforcement without any
  OS modification. The agent gets attenuated tokens, never the real credential.
- **Dual-model split.** The small on-device model is the natural fit for the
  quarantined role: it reads the private, hostile content (which is also the content
  you least want sent to a server), and its only output is a labelled value in a
  typed slot. Fooling it yields a wrong meeting time, not your secrets. The cloud
  model plans but is blind to untrusted content. This costs some capability (CaMeL
  reported roughly 77% vs 84% task success); that gap is the price of provable
  injection resistance, and what flows up to the planner is itself a tunable policy.
- **Adapters quarantine fragility.** A capability is a typed function
  (`openGarage(token)`); how it reaches the world is a swappable adapter behind it.
  The agent never knows or cares whether the adapter calls a clean API or drives a
  UI. When an app later exposes a real API, you swap the adapter and nothing above
  it changes.

## 5. The scenarios (what the UX has to prove)

Single-user (the wedge, and what belongs in the OS):
1. Notification triage that cannot be phished (secrets labelled, cannot leave).
2. Spending with a budget (capped single-use card; the bank enforces the cap).
3. Scheduling without leaking your calendar (free/busy out, titles withheld).
4. Bulk cleanup with honest undo (reversibility classes, real undo).
5. A guest pass (scoped, revocable authority handed to a person).
6. The kill switch (revoke everything at once).

Multi-user (the potential moat, with a cold-start problem): two Deputies booking
time by exchanging typed requests, and rail-agnostic payment requests ("bill Zane
$20, he pays with whatever he uses"). Requests between people are typed objects, not
natural-language messages, so they cannot carry an injected instruction; they only
come from paired, verified Deputies; non-users get a graceful fallback.

## 6. Strategy

- **Hobby first.** No income pressure means the exploration is not killed early.
  The output that matters (working prototype + honest write-up) is the same for
  every downstream path. Open-source from the first commit, which also keeps
  prior-invention paperwork clean if a job follows.
- **Startup is the weakest path, at least for consumer.** You would be racing the
  platform vendors to build their own feature. The single-user value is exactly what
  an OS vendor can absorb (the "Keyring as a Settings page" / Sherlocking risk; see
  Android's own permission-manager history). What survives absorption is narrow:
  cross-platform reach, neutrality, power users. The defensible version, if any, is
  developer infrastructure (the broker/token/flow layer as a library), which is a
  deliberate multi-year commitment, not a drift.
- **Google path runs through the hobby, not a pitch.** Big companies buy traction
  and people, not decks. A working prototype plus a sharp write-up is the strongest
  possible credential for the Android agent-platform team. The same fact that makes
  the startup risky (this belongs in the OS) makes the Google path coherent.
- **Checkpoints (3 to 6 months):** outsiders want to *use* it, maybe a product;
  devs want the broker as a *library*, maybe infrastructure; a platform person
  reaches out about the write-up, that path opened itself; none of the above but you
  learned a lot, a good hobby outcome. Let the signals set the ambition, not a
  decision made now. The individual-ahead-of-platform window is roughly 12 to 18
  months, a reason to start building soon, not to incorporate.

## 7. Scope boundary and the fork

Deputy is a discretionary, userland layer on a stock OS. It governs only what routes
through it; it cannot do mandatory, OS-wide enforcement, and it cannot make any of
the section-2 "first-class principal" ideas real. That is by design, and the
boundary is useful: the exact thing Deputy cannot do is what a forked, agent-native
OS (built and run in Cuttlefish / AOSP) would exist to do. So the userland probe
hands you a sharpened version of the kernel question rather than a blank page. The
broker's interface stays the same whether apps opt in (stock phone) or the OS forces
them through it (fork), so the userland work is not throwaway; it is the first half.

Caveat to hold: having built one architecture, it is tempting to see every
OS-evolution question through it, and some of what the build teaches is
Android-specific rather than universal. The pixel-detour and capability-token
insights generalise; the exact shape of, say, the widget-hosting constraint does
not.

## 8. Empirical findings from the build (the non-circular part)

- **Only the OS exposes clean on-device APIs.** A scan of ~104 installed
  third-party apps found that every usable on-device capability belonged to Android
  itself (notifications, files, calendar). Not one third-party app in the set
  exposed an on-device API we could call. Every time a third-party app was needed,
  the result was a deep link, a prefill, or UI driving. This reframes the whole
  question as a supply-side one: developer willingness, not agent capability, is the
  constraint.
- **The platform reserves "fire an action" to a real human gesture.** Hosting
  another app's widget (AppWidgetHost, available to a non-system app after a one-time
  user consent) lets you render and display it and let a real tap through, but there
  is no supported API to read out and fire the PendingIntents inside a delivered
  RemoteViews. That is a deliberate Android stance on agent authority, discovered by
  hitting it. It is also why the garage adapter stays at "embed the real button, one
  human tap."
- **App developers' incentives are the real obstacle.** Exposing a typed capability
  risks disintermediation (losing the screen where they monetise), liability, and
  abuse at scale. The capability layer is partly the *answer* to this: a
  budget-capped, single-use, auditable token makes it *safer* for a developer to
  expose a powerful action than handing an agent a full account session. That is
  also the strongest version of the platform pitch: the trust layer is what unlocks
  the developer supply side that AppFunctions needs.

## 9. Open unknowns to de-risk early

- Which card issuer actually offers an API for capped single-use cards (decides
  whether scenario 2's authority piece is real or mocked).
- Whether the on-device model API is available on the target phone (ML Kit GenAI
  Prompt / Gemini Nano on Pixel, or Gemma 3n as the prototyping fallback).
- How the target Android version handles one-time-code redaction for notification
  listeners (how much secret-labelling comes for free).
- The exact shape of any bank's P2P prefill deep link (read locally with jadx on
  your own machine).
