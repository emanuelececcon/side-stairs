"""Minimal Minecraft Java 26.3 (protocol 777) client for testing SideStairs on a local offline server.

It behaves like a modified client: every packet can be sent at any time, several in one TCP write so the
server handles them back to back in the same tick.
"""
import hashlib
import io
import socket
import struct
import threading
import time
import uuid
import zlib

PROTOCOL = 777

# Serverbound play ids (registration order in GameProtocols.SERVERBOUND_TEMPLATE, 26.3 build 18)
SB = {
    'ACCEPT_TELEPORTATION': 0, 'ATTACK': 1, 'CHAT_COMMAND': 7, 'CHUNK_BATCH_RECEIVED': 11, 'CLIENT_COMMAND': 12,
    'CLIENT_TICK_END': 13, 'CONTAINER_CLICK': 18, 'CONTAINER_CLOSE': 19, 'INTERACT': 26, 'KEEP_ALIVE': 28,
    'MOVE_PLAYER_STATUS_ONLY': 33, 'PICK_ITEM_FROM_BLOCK': 36, 'PLAYER_ACTION': 41, 'PLAYER_LOADED': 44, 'PONG': 45,
    'SET_CARRIED_ITEM': 54, 'SET_CREATIVE_MODE_SLOT': 57, 'USE_ITEM_ON': 66, 'USE_ITEM': 67,
}
# Clientbound play ids (bundle delimiter is 0)
CB = {
    'CHUNK_BATCH_FINISHED': 11, 'CONTAINER_CLOSE': 17, 'CONTAINER_SET_CONTENT': 18, 'CONTAINER_SET_SLOT': 20,
    'DISCONNECT': 32, 'KEEP_ALIVE': 45, 'LOGIN': 50, 'OPEN_SCREEN': 60, 'PING': 62, 'PLAYER_COMBAT_KILL': 69,
    'PLAYER_POSITION': 73, 'RESPAWN': 84, 'SET_CURSOR_ITEM': 98, 'SET_HEALTH': 106, 'SET_HELD_SLOT': 107,
    'SYSTEM_CHAT': 124, 'START_CONFIGURATION': 120,
}
# ContainerInput ids
PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL = range(7)
# Player actions (26.x added CHANGE_DESTROY_DIRECTION as the second one)
START_DESTROY, CHANGE_DESTROY_DIRECTION, ABORT_DESTROY, STOP_DESTROY, DROP_ALL_ITEMS, DROP_ITEM, RELEASE_USE,     SWAP_OFFHAND, STAB = range(9)


def varint(value):
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def string(text):
    data = text.encode('utf-8')
    return varint(len(data)) + data


def offline_uuid(name):
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode('utf-8')).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def block_pos(x, y, z):
    return struct.pack('>Q', ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF))


class Reader:
    def __init__(self, data):
        self.buf = io.BytesIO(data)

    def varint(self):
        result = 0
        for shift in range(0, 35, 7):
            byte = self.buf.read(1)[0]
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
        if result & 0x80000000:
            result -= 1 << 32
        return result

    def read(self, n):
        return self.buf.read(n)

    def string(self):
        return self.read(self.varint()).decode('utf-8')

    def long(self):
        return struct.unpack('>q', self.read(8))[0]

    def int(self):
        return struct.unpack('>i', self.read(4))[0]

    def short(self):
        return struct.unpack('>h', self.read(2))[0]

    def double(self):
        return struct.unpack('>d', self.read(8))[0]

    def float(self):
        return struct.unpack('>f', self.read(4))[0]

    def rest(self):
        return self.buf.read()


class Bot:
    def __init__(self, name, host='127.0.0.1', port=25599, log=print):
        self.name = name
        self.host = host
        self.port = port
        self.log = log
        self.sock = None
        self.compression = -1
        self.state = 'handshake'
        self.lock = threading.Lock()
        self.events = threading.Condition()
        self.open_container = None
        self.state_ids = {}
        self.closed_containers = []
        self.chat = []
        self.health = 20.0
        self.dead = False
        self.position = None
        self.connected = False
        self.disconnect_reason = None
        self.sequence = 1
        self.loaded = False

    # ---- connection ----
    def connect(self, timeout=30):
        self.sock = socket.create_connection((self.host, self.port), timeout=timeout)
        self.sock.settimeout(None)
        self.connected = True
        self.state = 'login'
        self._send_raw(0, varint(PROTOCOL) + string(self.host) + struct.pack('>H', self.port) + varint(2))
        self._send_raw(0, string(self.name) + offline_uuid(self.name).bytes)
        threading.Thread(target=self._read_loop, daemon=True).start()
        self.wait(lambda: self.loaded or not self.connected, timeout, 'join')
        if not self.connected:
            raise RuntimeError('disconnected while joining: %s' % self.disconnect_reason)

    def close_socket(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        self.connected = False

    def _frame(self, packet_id, payload):
        body = varint(packet_id) + payload
        if self.compression >= 0:
            if len(body) >= self.compression:
                compressed = zlib.compress(body)
                body = varint(len(body)) + compressed
            else:
                body = varint(0) + body
        return varint(len(body)) + body

    def _send_raw(self, packet_id, payload):
        with self.lock:
            self.sock.sendall(self._frame(packet_id, payload))

    def send(self, name, payload=b''):
        self._send_raw(SB[name], payload)

    def send_many(self, packets):
        """Several packets in one write: the server handles them one after the other in the same tick."""
        data = b''.join(self._frame(SB[name], payload) for name, payload in packets)
        with self.lock:
            self.sock.sendall(data)

    def _recv_exact(self, n):
        data = b''
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError('closed')
            data += chunk
        return data

    def _read_varint_sock(self):
        result = 0
        for shift in range(0, 35, 7):
            byte = self._recv_exact(1)[0]
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result
        raise ValueError('varint too long')

    def _read_loop(self):
        try:
            while True:
                length = self._read_varint_sock()
                data = self._recv_exact(length)
                if self.compression >= 0:
                    r = Reader(data)
                    uncompressed = r.varint()
                    data = zlib.decompress(r.rest()) if uncompressed else r.rest()
                r = Reader(data)
                packet_id = r.varint()
                self._handle(packet_id, r)
        except (ConnectionError, OSError) as e:
            pass
        except Exception as e:  # noqa
            self.log('bot %s reader error: %r' % (self.name, e))
        finally:
            self.connected = False
            with self.events:
                self.events.notify_all()

    def _handle(self, pid, r):
        if self.state == 'login':
            if pid == 0:
                self.disconnect_reason = r.rest()[:200]
                self.connected = False
            elif pid == 3:
                self.compression = r.varint()
            elif pid == 2:
                self._send_raw(3, b'')  # login acknowledged
                self.state = 'config'
            elif pid == 4:
                tx = r.varint()
                self._send_raw(2, varint(tx) + b'\x00')
            return
        if self.state == 'config':
            if pid == 15:  # select known packs
                count = r.varint()
                packs = [(r.string(), r.string(), r.string()) for _ in range(count)]
                payload = varint(len(packs)) + b''.join(string(a) + string(b) + string(c) for a, b, c in packs)
                self._send_raw(7, payload)
            elif pid == 4:
                self._send_raw(4, r.read(8))
            elif pid == 5:
                self._send_raw(5, r.read(4))
            elif pid == 3:
                self._send_raw(3, b'')
                self.state = 'play'
            elif pid == 20:
                self._send_raw(9, b'')
            elif pid == 2:
                self.disconnect_reason = r.rest()[:200]
                self.connected = False
            return
        # play
        if pid == CB['KEEP_ALIVE']:
            self.send('KEEP_ALIVE', r.read(8))
        elif pid == CB['PING']:
            self.send('PONG', r.read(4))
        elif pid == CB['PLAYER_POSITION']:
            tid = r.varint()
            x, y, z = r.double(), r.double(), r.double()
            r.double(), r.double(), r.double()
            yrot, xrot = r.float(), r.float()
            self.position = (x, y, z)
            self.send('ACCEPT_TELEPORTATION', varint(tid) + struct.pack('>dddff', x, y, z, yrot, xrot))
            if not self.loaded:
                self.send('PLAYER_LOADED')
                self.loaded = True
                with self.events:
                    self.events.notify_all()
        elif pid == CB['CHUNK_BATCH_FINISHED']:
            self.send('CHUNK_BATCH_RECEIVED', struct.pack('>f', 20.0))
        elif pid == CB['OPEN_SCREEN']:
            cid = r.varint()
            with self.events:
                self.open_container = cid
                self.events.notify_all()
        elif pid == CB['CONTAINER_SET_CONTENT'] or pid == CB['CONTAINER_SET_SLOT']:
            cid = r.varint()
            self.state_ids[cid] = r.varint()
        elif pid == CB['CONTAINER_CLOSE']:
            cid = r.varint()
            with self.events:
                self.closed_containers.append(cid)
                if self.open_container == cid:
                    self.open_container = None
                self.events.notify_all()
        elif pid == CB['DISCONNECT']:
            self.disconnect_reason = r.rest()[:300]
            self.connected = False
        elif pid == CB['SYSTEM_CHAT']:
            raw = r.rest()
            text = ''.join(chr(b) if 32 <= b < 127 else ' ' for b in raw)
            with self.events:
                self.chat.append(' '.join(text.split()))
                self.events.notify_all()
        elif pid == CB['SET_HEALTH']:
            self.health = r.float()
            if self.health <= 0:
                self.dead = True
        elif pid == CB['RESPAWN']:
            self.dead = False
            self.loaded = False
        elif pid == CB['START_CONFIGURATION']:
            self.log('server asked to reconfigure; not supported')

    def wait(self, predicate, timeout=10, what='condition'):
        deadline = time.time() + timeout
        with self.events:
            while not predicate():
                left = deadline - time.time()
                if left <= 0:
                    raise TimeoutError('timed out waiting for ' + what)
                self.events.wait(min(left, 0.25))

    # ---- actions ----
    def next_seq(self):
        self.sequence += 1
        return self.sequence

    def use_item_packet(self, hand=0):
        return ('USE_ITEM', varint(hand) + varint(self.next_seq()) + struct.pack('>ff', 0.0, -90.0))

    def use_item(self, hand=0):
        self.send(*self.use_item_packet(hand))

    def set_held(self, slot):
        self.send('SET_CARRIED_ITEM', struct.pack('>h', slot))

    def click_packet(self, container, slot, button, mode):
        state = self.state_ids.get(container, 0)
        payload = (varint(container) + varint(state) + struct.pack('>h', slot) + struct.pack('>b', button)
                   + varint(mode) + varint(0) + b'\x00')
        return ('CONTAINER_CLICK', payload)

    def click(self, container, slot, button, mode):
        self.send(*self.click_packet(container, slot, button, mode))

    def close_packet(self, container):
        return ('CONTAINER_CLOSE', varint(container))

    def close_container(self, container):
        self.send(*self.close_packet(container))

    def action_packet(self, action, pos=(0, 0, 0), face=0):
        return ('PLAYER_ACTION', varint(action) + block_pos(*pos) + struct.pack('>B', face) + varint(self.next_seq()))

    def action(self, action):
        self.send(*self.action_packet(action))

    def use_item_on_packet(self, pos, face=1, hand=0, cursor=(0.5, 1.0, 0.5)):
        x, y, z = pos
        return ('USE_ITEM_ON', varint(hand) + block_pos(x, y, z) + varint(face)
                + struct.pack('>fff', *cursor) + b'\x00\x00' + varint(self.next_seq()))

    def use_item_on(self, pos, face=1, hand=0, cursor=(0.5, 1.0, 0.5)):
        self.send(*self.use_item_on_packet(pos, face, hand, cursor))

    def on_ground(self, value=True):
        self.send('MOVE_PLAYER_STATUS_ONLY', struct.pack('>B', 1 if value else 0))

    def destroy(self, action, pos, face=1):
        self.send(*self.action_packet(action, pos, face))

    def pick_block(self, pos):
        self.send(*self.pick_block_packet(pos))

    def pick_block_packet(self, pos):
        return ('PICK_ITEM_FROM_BLOCK', block_pos(*pos) + b'\x00')

    def respawn(self):
        self.send('CLIENT_COMMAND', varint(0))

    def command(self, text):
        self.send('CHAT_COMMAND', string(text))
