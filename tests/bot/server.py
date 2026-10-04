"""Local test server control: start, RCON commands, stop.

Environment:
  SS_JAVA        java executable (default: java)
  SS_SERVER_DIR  server folder (default: tests/server)
  SS_SERVER_JAR  server jar inside that folder (default: paper.jar)
The folder needs rcon.pw, a text file holding the RCON password from server.properties.
"""
import os
import socket
import struct
import subprocess
import time

JAVA = os.environ.get('SS_JAVA', 'java')
DIR = os.environ.get('SS_SERVER_DIR', os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                                   'server'))
JAR = os.environ.get('SS_SERVER_JAR', 'paper.jar')


class Rcon:
    def __init__(self, password, port=25598):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=120)
        self.req = 0
        self._packet(3, password)
        rid, _, _ = self._read()
        if rid == -1:
            raise RuntimeError('rcon auth failed')

    def _packet(self, kind, body):
        self.req += 1
        data = struct.pack('<ii', self.req, kind) + body.encode('utf-8') + b'\x00\x00'
        self.sock.sendall(struct.pack('<i', len(data)) + data)
        return self.req

    def _read(self):
        size = struct.unpack('<i', self._exact(4))[0]
        data = self._exact(size)
        rid, kind = struct.unpack('<ii', data[:8])
        return rid, kind, data[8:-2].decode('utf-8', 'replace')

    def _exact(self, n):
        data = b''
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError('rcon closed')
            data += chunk
        return data

    def command(self, text):
        # Vanilla RCON reads one packet per socket read, so never send two at once.
        rid = self._packet(2, text)
        while True:
            got, _, body = self._read()
            if got == rid:
                return body

    def close(self):
        self.sock.close()


class Server:
    def __init__(self, log=print):
        self.log = log
        self.proc = None
        self.rcon = None
        with open(os.path.join(DIR, 'rcon.pw')) as f:
            self.password = f.read().strip()
        self.logfile = os.path.join(DIR, 'console.log')

    def start(self, timeout=300):
        out = open(self.logfile, 'a', encoding='utf-8', errors='replace')
        out.write('\n===== start %s =====\n' % time.strftime('%H:%M:%S'))
        out.flush()
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == 'nt' else 0
        start_size = os.path.getsize(self.logfile)
        self.proc = subprocess.Popen([JAVA, '-Xms1G', '-Xmx3G', '-jar', JAR, '--nogui'], cwd=DIR,
                                     stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError('server exited with %s' % self.proc.returncode)
            if 'For help, type' in self.console_since(start_size):
                break
            time.sleep(1)
        else:
            raise TimeoutError('server did not start')
        for _ in range(30):
            try:
                self.rcon = Rcon(self.password)
                break
            except OSError:
                time.sleep(1)
        self.log('server up')

    def cmd(self, text):
        return self.rcon.command(text)

    def stop(self, timeout=180):
        try:
            self.rcon.command('stop')
        except Exception:
            pass
        try:
            self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.kill()
        self.rcon = None
        self.log('server stopped')

    def kill(self):
        if os.name == 'nt':
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(self.proc.pid)], capture_output=True)
        else:
            self.proc.kill()
        self.proc.wait(30)
        self.rcon = None
        self.log('server killed')

    def console_since(self, mark):
        with open(self.logfile, encoding='utf-8', errors='replace') as f:
            f.seek(mark)
            return f.read()

    def console_mark(self):
        return os.path.getsize(self.logfile)
