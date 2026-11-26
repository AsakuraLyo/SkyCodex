---
name: nethersteel-line
description: Layout and gotchas of the nethersteel/steel production line built south of the main factory on the SkyExplorer server (2026-11-26)
metadata:
  node_type: memory
  type: project
  originSessionId: 92c4fb2e-69c0-46a7-bf14-5676e569bcf4
  modified: 2026-11-25T16:41:42.265Z
---

Built 2026-11-26 south of the main factory (user forbids building west = houses, north = subway). Verified end to end: 7 scrap + 28 steel -> 56 nethersteel ingots.

- Coal in: hopper (74,72,497) facing down -> crushing wheels (73,71,497)+(75,71,497), controller (74,71,497) -> hopper (74,70,497) -> hopper (73,70,497) -> steel basin (72,70,497). Iron also goes into hopper (73,70,497).
- Steel out: basin faces south -> depot (72,69,498) -> hopper (72,68,498) -> chest (73,68,498) (floor dug).
- Nethersteel in: chest (69,71,497) -> hopper -> basin (70,70,497) on creative-cake seething burner. Out: depot (70,69,498) -> hopper (70,68,498) -> chest (71,68,498).
- Wheel direction: gearshift (76,71,496) powered by lever (76,71,495) reverses both wheels; gearshift (74,71,496) must stay UNpowered.
- Recipes: steel = coal_dust + iron (unheated mix); coal dust ONLY from crushing wheels (millstone makes dye). nethersteel = 1 scrap + 4 steel -> 8 (superheated). Quest wants 64 nethersteel blocks = 72 scrap.
- User preferences: smelting/smoking/washing -> use their fan (鼓风机) lines; spare items -> main storage input barrel (71,70,473); Create parts can be stonecut from 万能机械 boxes.
- Hostile player 2500xianzhi killed the character at the line on 2026-11-26 (keepInventory on).

**Why:** next session needs the layout and the non-obvious rules (basin output, wheel direction).
**How to apply:** feed coal/iron/scrap at these positions; check chests (73,68,498)/(71,68,498). Related: [[skycodex-handoff]]
