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
  ux/screens.html  Screen designs, rev 2 (open in a browser)
  Deputy app screens - UX protos.pdf
                   Original screen designs, 2026-10-28
broker/            (Phase 0) the reference monitor: tokens, policy, audit, kill switch
keyring/           (Phase 0) the permission-ledger UI
adapters/          (Phase 0+) one typed module per capability; calendar first
```

The empirical scans of the phone's installed apps (capability scanner, APK
composition analysis, and their outputs) live in a sibling folder,
`~/code/deputy-scan/`, outside this repo: they hold pulled third-party APKs and
package dumps that do not belong in a public repo. Findings are summarised in
`docs/design.md` section 8.

## Status

Phase 0, single-user prototype, no runtime code yet. The first adapter is the
on-device calendar. See `CLAUDE.md` for the build order and the hard rules
(notably: no headless actuation of a physical lock).
