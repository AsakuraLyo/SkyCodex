---
name: remote-control-procedure
description: "How to fully operate the user's Minecraft remotely (F8, pause menu, joining the server) with computer-use, since the user controls it from another device"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 92c4fb2e-69c0-46a7-bf14-5676e569bcf4
  modified: 2026-11-24T23:20:18.652Z
---

The user (2026-11-25) runs SkyCodex remotely from another device and asked me to fully operate the PC: press F8, leave menus, get back into the game. Do it myself instead of asking them to press F8.

**Why:** they are not at the PC; every "please press F8" blocks the work.

**How to apply:**
- computer-use access: request `java.exe` (Minecraft window, tier full) and `plain craft launcher 2.exe`. Name "Minecraft"/"java"/"OpenJDK..." do not resolve.
- Always `mcp__computer-use__open_application` "Java" first (otherwise keys go to the desktop shell), then `key`.
- F8 toggles: read `mc_status.enabled` first and press `f8` only when it is false; verify after.
- Pause menu (`screen=PauseScreen`): key `Escape` returns to the game.
- Title screen -> 多人游戏 -> double-click the only server "Minecraft服务器 SkyExplorer 0.2demo" (window maximized; take a screenshot for coordinates).
- Heartbeat: control drops after 60 s without requests; start `E:\MC\dev\scratch\keepalive.mjs` detached (Start-Process node keepalive.mjs <minutes>) after enabling. It stops itself when control turns off.
- In-game right-click with mouse is not where the cursor is but the crosshair; clicking inside GUIs is safe, clicking in the world is not.
- Keep-awake request was denied by the app's auto-mode classifier; a locked/sleeping PC cannot be operated (lock screen is protected). The user has to allow it or set Windows sleep/lock to never.
- PvP: auto-defence already killed player Ericluuuu once (2026-11-25); report such events.
