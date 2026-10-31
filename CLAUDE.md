# Deputy

Deputy is a personal, build-led exploration of how phone operating systems evolve
for AI agents. Concretely, it is a trust-and-authority layer that sits between an
AI assistant and the apps and services on an Android phone. The assistant proposes
actions; Deputy, which is plain deterministic code, decides what actually happens,
using scoped, revocable, expiring capability tokens.

Status: Phase 0, hobby prototype, single user. Public repo from the first commit.

## Core principle

Models propose, code disposes. Every action an agent takes flows through the
broker, which is the only component that holds real credentials and the only one
that can act. The broker's enforcement is deterministic code, never an LLM.

## Architecture

- **Broker**: holds credentials, mints and checks capability tokens, enforces flow
  policy, writes the audit log, owns the kill switch. This is the reference monitor
  and the heart of the system.
- **Capability tokens**: scoped, budgeted, time-boxed, revocable, and
  non-delegable by default. Each one renders as a single human-readable sentence in
  the Keyring.
- **Keyring**: the product's main surface. Lists active authority in plain words,
  offers one-tap revoke, and a revoke-all kill switch.
- **Activity**: append-only log of what was done, refused, or asked about, written
  for a person to read. Inbound questions (Phase 3) are logged here too: who asked,
  what, and what was answered.
- **Fact store**: the typed record of what Deputy knows about the user, written only
  by the broker. Every fact carries a provenance label and a freshness time. The
  four labels, in descending trust: *attested* (a third party signed it: an mDL,
  a bank's KYC result), *observed* (read from a service through an adapter),
  *inferred* (a model derived it), *asserted* (the user said so). Adapters write
  into the fact store from Phase 0 rather than caching privately; it is also the
  only thing the inbound surface answers from.
- **Dual-model split (CaMeL-style)**: a cloud planner that sees only the user's
  request plus the capability catalog and never touches untrusted content; an
  on-device model that reads untrusted content (email, texts, notifications) into
  typed slots and holds no authority. The broker sits between them.
- **Adapters**: how a capability reaches the world, behind a typed function.
  Tiered best to worst: on-device API, cloud API, prefill deep link, UI driving
  (pixels). Fragility is quarantined inside the adapter; the agent only ever sees
  the typed function.

## Hard rules (do not violate)

- **No headless actuation of a physical lock.** The garage / Door adapter embeds
  the real unlock button (via AppWidgetHost) and requires a genuine human tap. Do
  not extract or cache a PendingIntent, synthesize a touch, or otherwise fire a
  physical unlock with no human gesture. Physical, irreversible actions keep a
  human in the loop by design. This is the project's own thesis applied to itself.
- **The broker is deterministic code.** Never put an LLM in the enforcement path;
  that rebuilds the vulnerability the project exists to contain.
- **Models never hold raw credentials.** The planner never sees untrusted content
  or secrets.
- **Secrets carry a label and never leak.** One-time codes and the like may never
  flow into an outbound message or up to the cloud planner.
- **Interoperability work stays within the user's own authorized access.** No
  hunting for auth bypasses, and nothing that would function as an exploit for
  someone without legitimate access.
- **Inbound questions are answered by the broker, from the fact store, from a fixed
  typed query vocabulary.** The planner never sees or answers an inbound question;
  models only work on the ingest side. Open-ended natural-language questions about
  the user are not a supported input.

## Action risk tiers

Every capability declares one: reversible (just does it), compensable (undoable
with effort), or permanent (asks first). Permanent and costly actions get an
explicit confirmation; reversible ones run freely. Minimise how often Deputy asks,
or people tap "yes" on reflex and the protection evaporates.

## Build order

- **Phase 0**: broker (token store, policy check, audit log) + Keyring + kill
  switch + one real adapter, end to end.
- **Phase 1**: a second on-device adapter (notification triage) and a cloud-API
  adapter (calendar or Gmail), then the dual-model split once there is something to
  plan over.
- **Phase 2**: the capability-catalog pipeline (scan, label, verify, promote) and
  payment-prefill adapters plus capped-card authority.
- **Phase 3**: the inbound surface. Other parties (services, other people's
  Deputies) ask typed questions about the user; the broker answers deterministically
  from the fact store under inbound grants that appear in the Keyring like any other
  authority. First capability: free/busy. See `docs/design.md` section 10.

See `docs/design.md` for the reasoning behind all of this, and `docs/log.md` for
the running build log.

## Conventions

- Language and build system: TBD, fill in at the first real commit.
- Keep the build log in `docs/log.md` current: what was built, what broke, what it
  taught. That log plus the working prototype is the actual deliverable of this
  project, more than the code itself.
