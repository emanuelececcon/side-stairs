"""SideStairs tests against a local Paper 26.3 server.

A bot (a minimal real client) places and breaks stairs with the same packets a player's client sends; everything is
checked with vanilla commands over RCON.

    python tests.py            run everything (starts and stops the server itself)
    python tests.py t05 t11    run the tests whose names start with these
    SS_ATTACH=1 python tests.py ...   use a server that is already running (the restart test is skipped)
"""
import math
import os
import re
import sys
import time
import traceback

from mcbot import Bot, START_DESTROY, ABORT_DESTROY
from server import Server

NAME = 'Tester'
GROUND = -61          # top block of the flat world
Y = GROUND + 1        # where stairs go
UP, NORTH, SOUTH, WEST, EAST = 1, 2, 3, 4, 5
results = []
server = None
bot = None


def log(*args):
    print(*args, flush=True)


def check(cond, what):
    if not cond:
        raise AssertionError(what)


def cmd(text):
    return server.cmd(text)


def passed(text):
    """An 'execute if ...' command: its count, 0 when it failed."""
    reply = cmd(text)
    m = re.search(r'Test passed(?:, count: (\d+))?', reply)
    if not m:
        return 0
    return int(m.group(1)) if m.group(1) else 1


def is_block(x, y, z, block):
    return passed('execute if block %d %d %d %s' % (x, y, z, block)) > 0


def displays(x, y, z):
    return passed('execute if entity @e[type=block_display,x=%.1f,y=%.1f,z=%.1f,distance=..0.6]' % (x + 0.5, y + 0.5, z + 0.5))


def display_data(x, y, z, path):
    return cmd('data get entity @e[type=block_display,x=%.1f,y=%.1f,z=%.1f,distance=..0.6,limit=1] %s'
               % (x + 0.5, y + 0.5, z + 0.5, path))


def dropped(item, x, y, z):
    total = 0
    reply = cmd('execute as @e[type=item,x=%.1f,y=%.1f,z=%.1f,distance=..3] run data get entity @s Item'
                % (x + 0.5, y + 0.5, z + 0.5))
    for m in re.finditer(r'count: (\d+), id: "minecraft:([a-z_]+)"', reply):
        if m.group(2) == item:
            total += int(m.group(1))
    if total == 0:
        for m in re.finditer(r'id: "minecraft:([a-z_]+)"[^}]*?count: (\d+)', reply):
            if m.group(1) == item:
                total += int(m.group(2))
    return total


def slot_number(slot, path):
    reply = cmd('data get entity %s Inventory[{Slot:%db}].%s' % (NAME, slot, path))
    m = re.search(r'entity data: (-?\d+)', reply)
    return int(m.group(1)) if m else None


def status_number(pattern):
    m = re.search(pattern, cmd('sidestairs status'))
    return int(m.group(1)) if m else None


def loaded_count():
    return status_number(r'Sideways stairs in loaded chunks: (\d+)')


def quaternion(side):
    s = math.sqrt(0.5)
    return {'east': (0, 0, s, s), 'west': (0, 0, -s, s), 'north': (s, 0, 0, s), 'south': (-s, 0, 0, s)}[side]


def rotation(x, y, z):
    reply = display_data(x, y, z, 'transformation.left_rotation')
    nums = [float(v) for v in re.findall(r'(-?[\d.]+(?:E-?\d+)?)f', reply)]
    check(len(nums) == 4, 'no rotation: ' + reply)
    return nums


def check_side(x, y, z, side):
    got = rotation(x, y, z)
    want = quaternion(side)
    check(all(abs(a - b) < 1e-3 for a, b in zip(got, want)), 'rotation %s, expected %s (%s)' % (got, want, side))


def reset(x1=-6, z1=-6, x2=12, z2=12):
    cmd('gamemode survival %s' % NAME)
    cmd('effect clear %s' % NAME)
    cmd('fill %d %d %d %d %d %d air' % (x1, Y, z1, x2, Y + 4, z2))
    cmd('fill %d %d %d %d %d %d grass_block' % (x1, GROUND, z1, x2, GROUND, z2))
    cmd('kill @e[type=item]')
    cmd('clear %s' % NAME)
    cmd('sidestairs repair')


def give(slot, item, count=1):
    cmd('item replace entity %s %s with %s %d' % (NAME, slot, item, count))


def stand(x, z, yaw, pitch=30.0):
    cmd('tp %s %.2f %d %.2f %.1f %.1f' % (NAME, x, Y, z, yaw, pitch))
    time.sleep(0.3)
    bot.on_ground(True)
    time.sleep(0.1)


def look_at(px, pz, bx, by, bz):
    """Stand at (px, Y, pz) looking at the centre of a block."""
    ex, ey, ez = px, Y + 1.62, pz
    dx, dy, dz = bx + 0.5 - ex, by + 0.5 - ey, bz + 0.5 - ez
    yaw = -math.degrees(math.atan2(dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    stand(px, pz, yaw, pitch)


def place(target, face_block, face, cursor, yaw, stand_at, stair='oak_stairs', stick=True):
    give('hotbar.0', stair, 16)
    if stick:
        give('weapon.offhand', 'stick')
    else:
        cmd('item replace entity %s weapon.offhand with air' % NAME)
    bot.set_held(0)
    stand(stand_at[0], stand_at[1], yaw)
    bot.use_item_on(face_block, face, 0, cursor)
    time.sleep(0.4)


def mine_time(pos, face=UP, limit=15.0):
    """Starts breaking and returns the seconds until the barrier is gone (None if it stays for `limit`)."""
    x, y, z = pos
    bot.destroy(START_DESTROY, pos, face)
    start = time.time()
    while time.time() - start < limit:
        if not is_block(x, y, z, 'barrier'):
            return time.time() - start
        time.sleep(0.05)
    bot.destroy(ABORT_DESTROY, pos, face)
    return None


# ---------------------------------------------------------------- tests

def t01_place_right():
    reset()
    # Looking north from z=3.5; click the right (east) part of the ground under (0, Y, 1).
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    check(is_block(0, Y, 1, 'barrier[waterlogged=false]'), 'no barrier')
    check(displays(0, Y, 1) == 1, 'displays: %d' % displays(0, Y, 1))
    state = display_data(0, Y, 1, 'block_state')
    # Facing north, bottom, straight, dry is the default state, which prints as the bare name.
    check(state.rstrip().endswith('"minecraft:oak_stairs"')
          or ('facing: "north"' in state and 'half: "bottom"' in state and 'shape: "straight"' in state), state)
    check_side(0, Y, 1, 'east')
    check(loaded_count() == 1, 'records: %s' % loaded_count())
    check(slot_number(0, 'count') == 15, 'stair not used up: %s' % slot_number(0, 'count'))


def t02_place_left():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.2, 1.0, 0.5), 180.0, (0.5, 3.5))
    check(is_block(0, Y, 1, 'barrier'), 'no barrier')
    check_side(0, Y, 1, 'west')


def t03_place_facing_east_against_block():
    reset()
    cmd('setblock 3 %d 0 stone' % Y)
    # Looking east from x=0.5; click the north face of a stone block south of (3, Y, -1): leans against it.
    place((3, Y, -1), (3, Y, 0), NORTH, (0.5, 0.5, 0.0), -90.0, (0.5, -0.5))
    check(is_block(3, Y, -1, 'barrier'), 'no barrier')
    state = display_data(3, Y, -1, 'block_state')
    check('facing: "east"' in state, state)
    check_side(3, Y, -1, 'south')


def t04_no_stick_is_vanilla():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5), stick=False)
    check(is_block(0, Y, 1, 'oak_stairs[facing=north]'), 'not a normal stair')
    check(displays(0, Y, 1) == 0, 'display on a normal stair')
    check(loaded_count() == 0, 'record for a normal stair')


def t05_survival_break_by_hand():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('clear %s' % NAME)
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1))
    log('    oak stairs by hand: %.2f s (vanilla 3.00 s)' % (took or -1))
    check(took is not None and 2.6 <= took <= 3.9, 'took %s' % took)
    time.sleep(0.3)
    check(is_block(0, Y, 1, 'air'), 'not air')
    check(displays(0, Y, 1) == 0, 'display left')
    check(loaded_count() == 0, 'record left')
    check(dropped('oak_stairs', 0, Y, 1) == 1, 'drop: %d' % dropped('oak_stairs', 0, Y, 1))


def t06_abort_keeps_the_stair():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('clear %s' % NAME)
    look_at(0.5, 3.5, 0, Y, 1)
    bot.destroy(START_DESTROY, (0, Y, 1))
    time.sleep(1.5)
    bot.destroy(ABORT_DESTROY, (0, Y, 1))
    time.sleep(3.0)
    check(is_block(0, Y, 1, 'barrier'), 'broken after abort')
    check(status_number(r'being mined now: (\d+)') == 0, 'still mining')
    # Starting again starts from zero.
    took = mine_time((0, Y, 1))
    check(took is not None and took >= 2.6, 'second try took %s' % took)


def t07_looking_away_stops():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('clear %s' % NAME)
    look_at(0.5, 3.5, 0, Y, 1)
    bot.destroy(START_DESTROY, (0, Y, 1))
    time.sleep(0.5)
    stand(0.5, 3.5, 0.0, 0.0)  # turn round
    time.sleep(4.0)
    check(is_block(0, Y, 1, 'barrier'), 'broken while looking away')
    bot.destroy(ABORT_DESTROY, (0, Y, 1))


def t08_pickaxe_on_stone():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5), stair='stone_stairs')
    cmd('clear %s' % NAME)
    give('hotbar.0', 'diamond_pickaxe')
    bot.set_held(0)
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1))
    log('    stone stairs, diamond pickaxe: %.2f s (vanilla 0.40 s)' % (took or -1))
    check(took is not None and took <= 1.2, 'took %s' % took)
    time.sleep(0.3)
    check(dropped('stone_stairs', 0, Y, 1) == 1, 'no drop')
    check(slot_number(0, 'components."minecraft:damage"') == 1, 'pickaxe damage: %s' % slot_number(0, 'components."minecraft:damage"'))


def t09_stone_by_hand_drops_nothing():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5), stair='cobblestone_stairs')
    cmd('clear %s' % NAME)
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1), limit=15)
    log('    cobblestone stairs by hand: %.2f s (vanilla 10.00 s)' % (took or -1))
    check(took is not None and 9.5 <= took <= 11.5, 'took %s' % took)
    time.sleep(0.3)
    check(dropped('cobblestone_stairs', 0, Y, 1) == 0, 'dropped without a pickaxe')
    check(loaded_count() == 0, 'record left')


def t10_haste_and_fatigue():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('clear %s' % NAME)
    cmd('effect give %s haste 60 1' % NAME)  # Haste II: x1.4
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1))
    log('    oak stairs by hand with Haste II: %.2f s (vanilla 2.15 s)' % (took or -1))
    check(took is not None and 1.8 <= took <= 2.9, 'took %s' % took)
    cmd('effect clear %s' % NAME)
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('clear %s' % NAME)
    cmd('effect give %s mining_fatigue 60 0' % NAME)  # Mining Fatigue I: x0.3, 10 s
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1), limit=15)
    log('    oak stairs by hand with Mining Fatigue I: %.2f s (vanilla 10.00 s)' % (took or -1))
    check(took is not None and 9.5 <= took <= 11.5, 'took %s' % took)
    cmd('effect clear %s' % NAME)


def t11_creative_break():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('gamemode creative %s' % NAME)
    time.sleep(0.3)
    look_at(0.5, 3.5, 0, Y, 1)
    bot.destroy(START_DESTROY, (0, Y, 1))
    time.sleep(0.5)
    check(is_block(0, Y, 1, 'air'), 'not broken in creative')
    check(displays(0, Y, 1) == 0, 'display left')
    check(loaded_count() == 0, 'record left')
    check(dropped('oak_stairs', 0, Y, 1) == 0, 'dropped in creative')


def t12_repair_after_kill():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('kill @e[type=block_display]')
    time.sleep(0.2)
    check(displays(0, Y, 1) == 0, 'display not killed')
    cmd('sidestairs repair')
    check(displays(0, Y, 1) == 1, 'display not redrawn')
    check_side(0, Y, 1, 'east')
    cmd('sidestairs repair')
    check(displays(0, Y, 1) == 1, 'second repair made a duplicate')


def t13_repair_after_setblock():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('setblock 0 %d 1 stone' % Y)
    cmd('sidestairs repair')
    check(displays(0, Y, 1) == 0, 'display left over stone')
    check(loaded_count() == 0, 'record left over stone')


def t14_place_over_stale_record():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    cmd('setblock 0 %d 1 air' % Y)
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5), stick=False)
    check(is_block(0, Y, 1, 'oak_stairs'), 'no stair')
    check(displays(0, Y, 1) == 0, 'old display left')
    check(loaded_count() == 0, 'old record left')


def t15_waterlogged():
    reset()
    cmd('setblock 0 %d 1 water' % Y)
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    check(is_block(0, Y, 1, 'barrier[waterlogged=true]'), 'barrier not waterlogged')
    cmd('clear %s' % NAME)
    look_at(0.5, 3.5, 0, Y, 1)
    took = mine_time((0, Y, 1))
    check(took is not None, 'not broken')
    time.sleep(0.3)
    check(is_block(0, Y, 1, 'water'), 'water gone')


def t16_pick_block():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5), stair='birch_stairs')
    cmd('clear %s' % NAME)
    give('hotbar.0', 'dirt')
    give('hotbar.5', 'birch_stairs', 3)
    bot.set_held(0)
    look_at(0.5, 3.5, 0, Y, 1)
    bot.pick_block((0, Y, 1))
    time.sleep(0.4)
    reply = cmd('data get entity %s SelectedItemSlot' % NAME)
    check(reply.rstrip().endswith('5'), 'selected: ' + reply)


def t17_place_against_it():
    reset()
    place((0, Y, 1), (0, GROUND, 1), UP, (0.8, 1.0, 0.5), 180.0, (0.5, 3.5))
    give('hotbar.0', 'dirt', 4)
    bot.set_held(0)
    look_at(0.5, 3.5, 0, Y, 1)
    bot.use_item_on((0, Y, 1), UP, 0, (0.5, 1.0, 0.5))
    time.sleep(0.4)
    check(is_block(0, Y + 1, 1, 'dirt'), 'could not place on top of it')
    check(is_block(0, Y, 1, 'barrier'), 'barrier changed')


def t18_many_and_restart():
    reset()
    spots = []
    for i, (cx, side) in enumerate([(0.8, 'east'), (0.2, 'west')] * 3):
        x = -4 + i * 2
        place((x, Y, 1), (x, GROUND, 1), UP, (cx, 1.0, 0.5), 180.0, (x + 0.5, 3.5))
        spots.append((x, side))
    check(loaded_count() == 6, 'records: %s' % loaded_count())
    if os.environ.get('SS_ATTACH'):
        log('    (restart skipped)')
        return
    restart()
    for x, side in spots:
        check(is_block(x, Y, 1, 'barrier'), 'barrier gone at %d' % x)
        check(displays(x, Y, 1) == 1, 'displays at %d: %d' % (x, displays(x, Y, 1)))
        check_side(x, Y, 1, side)
    check(loaded_count() == 6, 'records after restart: %s' % loaded_count())


# ---------------------------------------------------------------- runner

def connect():
    global bot
    bot = Bot(NAME, log=log)
    bot.connect()
    time.sleep(1.0)


def restart():
    global bot
    server.stop()
    server.start()
    connect()
    time.sleep(2.0)


def main():
    global server
    wanted = sys.argv[1:]
    tests = [(name, fn) for name, fn in sorted(globals().items()) if re.match(r't\d\d_', name) and callable(fn)]
    if wanted:
        tests = [(n, f) for n, f in tests if any(n.startswith(w) for w in wanted)]
    server = Server(log=log)
    attach = bool(os.environ.get('SS_ATTACH'))
    if attach:
        from server import Rcon
        server.rcon = Rcon(server.password)
    else:
        server.start()
    try:
        connect()
        cmd('gamerule doDaylightCycle false')
        cmd('gamerule doMobSpawning false')
        for name, fn in tests:
            log('%s ...' % name)
            try:
                fn()
                results.append((name, True, ''))
                log('  ok')
            except Exception as e:  # noqa
                results.append((name, False, repr(e)))
                log('  FAIL %r' % e)
                traceback.print_exc()
                if not bot.connected:
                    connect()
    finally:
        if not attach:
            server.stop()
    ok = sum(1 for _, good, _ in results if good)
    log('\n%d/%d passed' % (ok, len(results)))
    for name, good, err in results:
        if not good:
            log('  FAIL %s: %s' % (name, err))
    sys.exit(0 if ok == len(results) else 1)


if __name__ == '__main__':
    main()
