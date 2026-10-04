# SideStairs

A Paper 26.3 plugin that places stairs on their side. Hold a stick in your off-hand while you place a stair and it goes on its side: a block with one vertical quarter missing, as if the stair had been tipped over onto its side. Vanilla stairs can only face north, east, south or west, upright or upside-down. Players don't need any mods.

## Placing

1. Put a **stick** in your off-hand (the item is configurable).
2. Place any stair from your main hand as usual.

- The stair's tall back goes on the far side, away from you, like a normal stair.
- The click picks the side, the way the upper or lower half of a face picks an upside-down or upright stair. Click the right part of the spot and the solid half goes right, so the missing quarter is the one nearest you on the left. Click the left part and it is the other way round.
- Clicking the side of a block next to the spot leans the stair against that block.
- To cut a far corner, place the stair from the other side.

Without a stick in the off-hand, stairs are placed normally.

## Breaking

- **Survival:** hit it as usual. It takes as long as the stair takes in vanilla (tool, Efficiency, Haste, Mining Fatigue, water and jumping all count), with the stair's hit sounds and crumbs. The stair drops if the tool is right (a pickaxe for stone stairs, anything for wood). The tool takes damage, and the break shows up in statistics.
- **Creative:** one click, as usual.
- **Middle click** picks the stair, not the barrier.

## How it works

Vanilla has no block state for a stair on its side, so SideStairs draws it with a **block display entity**. The client draws it like any other entity, so no resource pack or mod is needed. The position itself holds an invisible **barrier**:

- it gives full-block collision: you can stand on a sideways stair, but you can't walk into its missing corner
- nothing else can be placed into it; pistons, explosions, the Wither, the Ender Dragon and Endermen can't move or break it
- it can be waterlogged, like the stair

Each sideways stair is also recorded in its chunk's data, so it survives restarts. That record is what counts. When a chunk loads, its displays are checked against the records: a display killed with `/kill` is drawn again, and a record whose barrier was replaced (by `/fill` or WorldEdit) is dropped along with its display.

Limits:
- The collision is a full block. No vanilla block has the shape of a sideways stair.
- A display is lit as a whole by the light in its block, and doesn't get the soft shadows of real blocks, so it can look a little flatter than the blocks around it.
- Sideways stairs don't connect into corner shapes.
- WorldEdit copy/paste is not supported: a paste brings only the barrier, without a record for the new position. A display copied along with `-e` is removed when its chunk loads, because there is no record for it.
- Others hear a stone sound when a sideways stair is placed, because the server plays the barrier's place sound. The player placing it hears the stair.

## Commands and permissions

| Command | Permission | Default | |
|---|---|---|---|
| `/sidestairs status` | `sidestairs.admin` | op | Trigger items, stairs in loaded chunks, totals since start |
| `/sidestairs repair` | `sidestairs.admin` | op | Checks displays and records in every loaded chunk |
| `/sidestairs reload` | `sidestairs.admin` | op | Reloads config.yml and updates existing displays |
| (place a stair with a stick in the off-hand) | `sidestairs.place` | everyone | Places it sideways |

## Configuration

`plugins/SideStairs/config.yml`:

```yaml
# Hold one of these items in your off-hand while placing a stair (from the main hand) to place it sideways.
trigger-items:
  - stick

# How far away sideways stairs are drawn, as a multiple of 64 blocks (vanilla display entities use 1.0).
# The server's display tracking range (128 blocks by default in spigot.yml) is the upper limit.
view-range: 2.0
```

## Removing the plugin

Without the plugin, sideways stairs stay as invisible barriers with a display that nothing manages. Break them first, or remove them with commands: `/kill @e[type=block_display]` (careful if other plugins use displays) and `/fill ... air replace barrier`.

## Building and testing

`mvn package` with JDK 21+; the jar is in `target/`. `tests/` has in-game tests: a bot places and breaks sideways stairs on a local Paper 26.3 server (see `tests/README.md`).

## License

MIT
