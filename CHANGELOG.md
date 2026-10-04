# Changelog

## 1.0.0 - 2026-10-04
- First release.
- Hold a stick (configurable) in the off-hand while placing a stair: it is placed on its side, drawn by a block display entity over an invisible, waterloggable barrier that gives full-block collision. The back goes away from the player, and the clicked side gets the solid half.
- Survival breaking takes the vanilla time for that stair (tool, enchantments, effects, water, ground), with hit sounds and crumbs; drops follow vanilla's tool rules, tools take damage. Creative breaks with one click. Pick block gives the stair.
- Stairs are recorded in the chunk data. Displays are checked when chunks load: missing ones are drawn again, and records whose barrier is gone are dropped.
- In-game tests in `tests/`: a bot places and breaks sideways stairs on a local Paper server (18 tests).
- `/sidestairs status | repair | reload`, permissions `sidestairs.admin` (op) and `sidestairs.place` (everyone).
