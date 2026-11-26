---
name: skycodex-handoff
description: SkyCodex (MC 1.21.1 NeoForge client mod + Node MCP bridge) — where the handoff doc lives and where work stood when taken over on 2026-11-24
metadata:
  node_type: memory
  type: project
  originSessionId: 92c4fb2e-69c0-46a7-bf14-5676e569bcf4
  modified: 2026-11-24T09:45:31.513Z
---

SkyCodex project was handed over from a previous Codex/Claude session on 2026-11-24. Handoff doc: `E:\MC\dev\handoff-stage\HANDOFF.md` (also inside `E:\MC\SkyCodex-handoff-2026-11-24.zip`). Dev source of truth: `E:\MC\dev\SkyCodex-0.1.0-beta.2\source`. JDK/Node are not on PATH — set them per HANDOFF.md §2 before each command. User talks Chinese.

State at takeover (verified 2026-11-24): node tests 7/7 pass, compileJava passes, installed jar in the game instance == build/libs jar. After the handoff zip was made, the previous session wrote `WaystoneTask.java` (list/teleport via reflection + Balm sendToServer) but did NOT wire it into Controller.java or bridge/tools.mjs, and the SelectWaystoneMessage constructor / Balm send API were not yet confirmed with javap. Server memory already migrated to `E:\MC\SkyCodex\memory\p39.ytonidc.com_44800.json` with 10 waystone destinations. The character was killed by another player (PvP) at ~(110,74,496) during a tour; a `safe.mjs` helper in `E:\MC\dev\scratch` stops jobs on any damage.

Update 2026-11-24 (later): mc_waystone finished in code (Controller + tools.mjs wired, javap-confirmed API, build + node tests pass, 33 tools) but NOT tested in game and jar NOT installed; user chose to test later because their game was running and RAM was short for the 10 GB dev client. Bridge deliberately not synced to E:\MC\SkyCodex until the jar is installed. Remaining steps are in source\HANDOFF.md §7 item 1.

User decision 2026-11-24: on damage or enemies the character must NOT stop — it should fight back automatically, against mobs AND players who attack first (user explicitly chose to include players). Not implemented yet (needs a new jar: pause job, fight, resume, like auto-eat). Until then scratch scripts only log danger and stop below 35% health. 正太岛 and 出生岛 production lines were surveyed and named in server memory the same day.

**Why:** next session needs to know the waystone work is half-done and that the server has hostile players.
**How to apply:** continue from HANDOFF.md "下一步" item 1 (mc_waystone); only replace the jar when the user has closed the game; don't rerun tests that already passed.
