# In-game tests

Tests for SideStairs against a real Paper 26.3 server. `bot/mcbot.py` is a minimal Minecraft 26.3 client (protocol 777, copied from ShulkerAnywhere's tests). It places and breaks stairs with the same packets a player's client sends: use item on a block with a click position, start and abort breaking, and pick block. Everything is checked with vanilla commands over RCON (`execute if block`, `data get entity`), so no helper plugin is needed.

The tests cover:

- placing sideways: clicking the right or left part of the spot, facing north or east, leaning against a block; the display's stair and rotation, the barrier, the record, the item used up
- without a stick in the off-hand, stairs are placed normally
- survival breaking times against vanilla: oak by hand (3 s), stone with a diamond pickaxe, cobblestone by hand (10 s, no drop), Haste II, Mining Fatigue I; drops and pickaxe damage
- releasing the button or looking away stops breaking, and starting again starts from zero
- creative breaking: instant, no drop
- `/kill` of the display and `/setblock` over the barrier, then `/sidestairs repair`; placing over a stale record
- a waterlogged sideways stair keeps its water when broken
- pick block, placing a block on top of a sideways stair
- six stairs survive a restart with one display each

## Setup

You need Python 3.10+ and a JDK 21+.

1. Build the plugin (`mvn package` in the project root).
2. Make a server folder, by default `tests/server/`, with:
   - the Paper 26.3 jar, named `paper.jar` (or set `SS_SERVER_JAR`)
   - `eula.txt` with `eula=true`: you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) yourself
   - `server.properties`:
     ```properties
     online-mode=false
     server-ip=127.0.0.1
     server-port=25599
     enable-rcon=true
     rcon.port=25598
     rcon.password=<a random password>
     white-list=false
     level-type=minecraft\:flat
     generate-structures=false
     spawn-protection=0
     difficulty=peaceful
     view-distance=4
     simulation-distance=4
     allow-flight=true
     network-compression-threshold=-1
     max-tick-time=-1
     ```
   - `rcon.pw` holding the same RCON password
   - `plugins/` with `side-stairs-<version>.jar`
3. Run `python bot/tests.py`, or `python bot/tests.py t05 t18` for some tests. The script starts and stops the server itself. Set `SS_JAVA` if `java` is not on the path, and `SS_SERVER_DIR` for another server folder. With `SS_ATTACH=1` it uses a server that is already running (the restart test is skipped then).

The server only listens on 127.0.0.1 and runs in offline mode; never point these scripts at a real server.

## Updating for a new Minecraft version

The packet ids in `mcbot.py` follow the registration order in the server's `GameProtocols`, `ConfigurationProtocols` and `LoginProtocols` classes, and `PROTOCOL` comes from `version.json` in the server jar. A new version usually moves some ids; check them before trusting a green run.
