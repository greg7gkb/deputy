# Deputy

A trust-and-authority layer between an AI assistant and the apps on an Android
phone. The assistant proposes; Deputy decides, using scoped, revocable, expiring
capability tokens. The visible product is a permission ledger (the Keyring), not a
chat box.

This is a personal, build-led exploration of a larger question: how do operating
systems evolve once AI agents are first-class actors on them? Deputy probes the
userland half of that question (authority, trust, and the interface between an
agent and apps it does not control), and is designed to extend naturally into the
kernel-level half (a forked, agent-native OS) later.

## Why

Today's phone assistants are either too weak to be useful or hold the keys to your
whole account. Deputy's bet is that the missing piece is not agent capability but
*governed* capability: narrow, auditable, revocable authority, with the human kept
in the loop for anything irreversible.

## Repo layout

```
CLAUDE.md          Durable project context, loaded every Claude Code session
README.md          This file
docs/
  design.md        Full reasoning: thesis, concept stack, strategy, findings
  log.md           Running build log (what was built, what broke, what it taught)
tools/
  deputy_scan.sh   Read-only Android capability scanner (intent filters,
                   deep links, App Actions) used to seed the adapter catalog
broker/            (Phase 0) the reference monitor: tokens, policy, audit, kill switch
keyring/           (Phase 0) the permission-ledger UI
adapters/          (Phase 1+) one typed module per capability
```

## Status

Phase 0, single-user prototype. See `CLAUDE.md` for the build order and the hard
rules (notably: no headless actuation of a physical lock).

## Suggested first commit

```
git init
git add .
git commit -m "Deputy: project context, design, and capability scanner

Seed the repo with the durable context (CLAUDE.md), the full design
reasoning (docs/design.md), and the read-only capability scanner.
No runtime code yet; Phase 0 (broker + Keyring + kill switch) is next.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
