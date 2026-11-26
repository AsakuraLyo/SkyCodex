---
name: github-backup
description: SkyCodex source + backups are on GitHub (public repo AsakuraLyo/SkyCodex); how to refresh and restore after the 2026-11-26 Windows reinstall
metadata:
  node_type: memory
  type: reference
  originSessionId: 92c4fb2e-69c0-46a7-bf14-5676e569bcf4
  modified: 2026-11-26T07:20:29.505Z
---

Public repo: https://github.com/AsakuraLyo/SkyCodex (pushed 2026-11-26, branch main). Local clone = `E:\MC\dev\SkyCodex-0.1.0-beta.2\source`.

- `backup/claude-memory`, `backup/scratch`, `backup/server-memory` are copies refreshed by `backup\sync.ps1`; restore steps in `backup\RESTORE.md`.
- `.gitignore` excludes build/, dist/, .gradle/, run/, run-modpack/ and every `skycodex-connection.json` (bridge token) — never commit those.
- The user planned a Windows reinstall right after pushing; Git and GitHub CLI were installed with winget and gh logged in as AsakuraLyo (will need reinstall + `gh auth login` again).

**Why:** the user reinstalls Windows; C: (this memory, transcripts) is wiped, E: normally survives.
**How to apply:** after reinstall, restore memory from `backup/claude-memory`; before big changes run sync.ps1 + commit + push. Related: [[skycodex-handoff]]
