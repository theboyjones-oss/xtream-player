"""
PanGaze caster: sends video from the Mac/PC to a Chromecast, Google TV or Nvidia Shield.

Browsers like Firefox can't cast, so server.py does it instead, speaking the Cast protocol
directly (Python standard library only):
  - finds Cast devices on the network with mDNS (_googlecast._tcp.local)
  - opens a TLS connection to the device on port 8009
  - starts Google's Default Media Receiver and tells it which address to play
The device then fetches the video from PanGaze on this computer (through /proxy), so the
provider is only ever talking to this computer.
"""
import json
import socket
import ssl
import struct
import threading
import time

NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
NS_MEDIA = "urn:x-cast:com.google.cast.media"
DEFAULT_RECEIVER = "CC1AD845"           # Google's Default Media Receiver
SENDER = "sender-pangaze"


class CastError(Exception):
    pass


# ---------------- the Cast message format (a tiny protobuf, written by hand) ----------------
def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _str_field(no, s):
    b = s.encode("utf-8")
    return _varint(no << 3 | 2) + _varint(len(b)) + b


def _int_field(no, v):
    return _varint(no << 3) + _varint(v)


def encode(src, dst, ns, payload):
    body = (_int_field(1, 0) + _str_field(2, src) + _str_field(3, dst) + _str_field(4, ns)
            + _int_field(5, 0) + _str_field(6, json.dumps(payload, separators=(",", ":"))))
    return struct.pack(">I", len(body)) + body


def _read_varint(buf, i):
    shift = n = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, i
        shift += 7


def decode(buf):
    """-> dict of field number -> value (strings decoded)."""
    out, i = {}, 0
    while i < len(buf):
        key, i = _read_varint(buf, i)
        no, wt = key >> 3, key & 7
        if wt == 0:
            out[no], i = _read_varint(buf, i)
        elif wt == 2:
            ln, i = _read_varint(buf, i)
            raw = buf[i:i + ln]
            i += ln
            try:
                out[no] = raw.decode("utf-8")
            except UnicodeDecodeError:
                out[no] = raw
        else:
            break
    return out


# ---------------- one conversation with a device ----------------
class CastConn:
    def __init__(self, host, port=8009, timeout=8):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE          # Cast devices use their own certificates
        raw = socket.create_connection((host, port), timeout=timeout)
        self.sock = ctx.wrap_socket(raw)
        self.sock.settimeout(timeout)
        self.req = 0
        self.connected = set()
        self.connect("receiver-0")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def send(self, dst, ns, payload):
        self.sock.sendall(encode(SENDER, dst, ns, payload))

    def connect(self, dst):
        if dst not in self.connected:
            self.send(dst, NS_CONN, {"type": "CONNECT", "origin": {}})
            self.connected.add(dst)

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise CastError("The TV closed the connection")
            buf += chunk
        return buf

    def recv(self):
        ln = struct.unpack(">I", self._recv_exact(4))[0]
        if ln > 1 << 20:
            raise CastError("Unexpected reply from the TV")
        m = decode(self._recv_exact(ln))
        ns, src, data = m.get(4, ""), m.get(2, ""), m.get(6, "")
        try:
            data = json.loads(data) if isinstance(data, str) and data else {}
        except ValueError:
            data = {}
        if ns == NS_HEARTBEAT and data.get("type") == "PING":
            self.send(src, NS_HEARTBEAT, {"type": "PONG"})
        return ns, src, data

    def request(self, dst, ns, payload, timeout=10, accept=None):
        """Send with a requestId and wait for the reply to it (or any message accept() likes)."""
        self.req += 1
        rid = self.req
        payload = dict(payload, requestId=rid)
        self.send(dst, ns, payload)
        end = time.time() + timeout
        while time.time() < end:
            self.sock.settimeout(max(0.2, end - time.time()))
            try:
                rns, src, data = self.recv()
            except socket.timeout:
                break
            if rns == NS_HEARTBEAT:
                continue
            if data.get("requestId") == rid or (accept and accept(rns, data)):
                return data
        raise CastError("The TV didn't answer in time")

    # ----- receiver (the TV itself) -----
    def receiver_status(self):
        return self.request("receiver-0", NS_RECEIVER, {"type": "GET_STATUS"}).get("status", {})

    @staticmethod
    def _app(status):
        for a in status.get("applications") or []:
            if a.get("appId") == DEFAULT_RECEIVER:
                return a
        return None

    def launch(self):
        app = self._app(self.receiver_status())
        if app and app.get("transportId"):
            return app
        self.request("receiver-0", NS_RECEIVER, {"type": "LAUNCH", "appId": DEFAULT_RECEIVER}, timeout=20,
                     accept=lambda ns, d: ns == NS_RECEIVER and self._app(d.get("status", {})) is not None)
        for _ in range(20):                      # the player may take a moment to report its transport
            app = self._app(self.receiver_status())
            if app and app.get("transportId"):
                return app
            time.sleep(0.5)
        raise CastError("The TV didn't start its video player")

    # ----- media (the player app on the TV) -----
    def media_status(self, tid):
        self.connect(tid)
        st = self.request(tid, NS_MEDIA, {"type": "GET_STATUS"}).get("status") or []
        return st[0] if st else None


# ---------------- finding devices (mDNS) ----------------
def _dns_name(buf, i):
    labels, jumped, end = [], False, None
    for _ in range(64):
        ln = buf[i]
        if ln == 0:
            i += 1
            break
        if ln & 0xC0 == 0xC0:                    # compression pointer
            if not jumped:
                end = i + 2
            i = ((ln & 0x3F) << 8) | buf[i + 1]
            jumped = True
            continue
        labels.append(buf[i + 1:i + 1 + ln].decode("utf-8", "replace"))
        i += 1 + ln
    return ".".join(labels), (end if jumped else i)


def _parse_mdns(buf):
    """-> list of (name, type, rdata-info) for every record in an mDNS packet."""
    out = []
    qd, an, ns, ar = struct.unpack(">4H", buf[4:12])
    i = 12
    for _ in range(qd):
        _, i = _dns_name(buf, i)
        i += 4
    for _ in range(an + ns + ar):
        name, i = _dns_name(buf, i)
        rtype, _cls, _ttl, rdlen = struct.unpack(">HHIH", buf[i:i + 10])
        i += 10
        rd = buf[i:i + rdlen]
        if rtype == 12:                          # PTR
            out.append((name, 12, _dns_name(buf, i)[0]))
        elif rtype == 33:                        # SRV
            port = struct.unpack(">H", rd[4:6])[0]
            out.append((name, 33, (port, _dns_name(buf, i + 6)[0])))
        elif rtype == 16:                        # TXT
            kv, j = {}, 0
            while j < len(rd):
                ln = rd[j]
                s = rd[j + 1:j + 1 + ln].decode("utf-8", "replace")
                j += 1 + ln
                if "=" in s:
                    k, v = s.split("=", 1)
                    kv[k] = v
            out.append((name, 16, kv))
        elif rtype == 1 and rdlen == 4:          # A
            out.append((name, 1, socket.inet_ntoa(rd)))
        i += rdlen
    return out


def discover(timeout=2.5, iface_ip=None):
    """Find Cast devices. -> [{"id", "name", "model", "host", "port"}]"""
    q = struct.pack(">6H", 0, 0, 1, 0, 0, 0)
    for part in "_googlecast._tcp.local".split("."):
        q += bytes([len(part)]) + part.encode()
    q += b"\x00" + struct.pack(">HH", 12, 1)    # PTR, IN
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        if iface_ip:
            try:
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface_ip))
            except OSError:
                pass
        s.bind(("", 0))
        found = {}
        end = time.time() + timeout
        sent = 0
        while time.time() < end:
            if sent < 2 and time.time() > end - timeout + sent * 0.8:
                s.sendto(q, ("224.0.0.251", 5353))      # ask twice in case the first is missed
                sent += 1
            s.settimeout(0.3)
            try:
                buf, addr = s.recvfrom(9000)
            except socket.timeout:
                continue
            try:
                recs = _parse_mdns(buf)
            except Exception:
                continue
            srv, txt, a = {}, {}, {}
            inst = [r[2] for r in recs if r[1] == 12 and r[0].lower().startswith("_googlecast._tcp")]
            for name, t, data in recs:
                if t == 33:
                    srv[name] = data
                elif t == 16:
                    txt[name] = data
                elif t == 1:
                    a[name] = data
            for n in inst or list(srv):
                port, target = srv.get(n, (8009, ""))
                info = txt.get(n, {})
                dev_id = info.get("id") or n
                found[dev_id] = {"id": dev_id, "name": info.get("fn") or n.split(".")[0],
                                 "model": info.get("md", ""), "host": a.get(target) or addr[0], "port": port}
        return sorted(found.values(), key=lambda d: d["name"].lower())
    finally:
        s.close()


# ---------------- what server.py calls ----------------
class Caster:
    def __init__(self):
        self.lock = threading.Lock()
        self.devices = []
        self.scanned = 0.0
        self.now = None            # {"dev": device, "title", "live", "url", "since"}

    def scan(self, force=False, iface_ip=None):
        if force or time.time() - self.scanned > 60 or not self.devices:
            devs = discover(iface_ip=iface_ip)
            with self.lock:
                if devs or force:
                    self.devices = devs
                self.scanned = time.time()
        return self.devices

    def device(self, dev_id=None, host=None):
        for d in self.devices:
            if d["id"] == dev_id or (host and d["host"] == host):
                return d
        if host:
            return {"id": host, "name": host, "model": "", "host": host, "port": 8009}
        raise CastError("That TV wasn't found. Search again.")

    def play(self, dev, url, title="", image="", subtitle="", live=False, content_type=None, start=0):
        content_type = content_type or ("application/x-mpegURL" if ".m3u8" in url.split("?")[0].lower() or "m3u8" in url.lower() else "video/mp4")
        media = {"contentId": url, "contentType": content_type, "streamType": "LIVE" if live else "BUFFERED",
                 "metadata": {"metadataType": 0, "title": title or "PanGaze", "subtitle": subtitle or ""}}
        if image:
            media["metadata"]["images"] = [{"url": image}]
        c = CastConn(dev["host"], dev.get("port") or 8009)
        try:
            app = c.launch()
            tid = app["transportId"]
            c.connect(tid)
            msg = {"type": "LOAD", "media": media, "autoplay": True}
            if start and not live:
                msg["currentTime"] = float(start)
            res = c.request(tid, NS_MEDIA, msg, timeout=25,
                            accept=lambda ns, d: ns == NS_MEDIA and d.get("type") in ("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST"))
            if res.get("type") != "MEDIA_STATUS":
                raise CastError("The TV couldn't play this (%s)" % (res.get("reason") or res.get("type", "error")).lower().replace("_", " "))
        finally:
            c.close()
        with self.lock:
            self.now = {"dev": dev, "title": title, "live": live, "url": url, "since": time.time()}
        return True

    def stop(self, dev):
        c = CastConn(dev["host"], dev.get("port") or 8009)
        try:
            app = c._app(c.receiver_status())
            if app:
                c.request("receiver-0", NS_RECEIVER, {"type": "STOP", "sessionId": app["sessionId"]})
        finally:
            c.close()
        with self.lock:
            if self.now and self.now["dev"]["id"] == dev["id"]:
                self.now = None

    def status(self, dev):
        c = CastConn(dev["host"], dev.get("port") or 8009, timeout=5)
        try:
            st = c.receiver_status()
            app = c._app(st)
            out = {"app": bool(app), "volume": (st.get("volume") or {}).get("level")}
            if app and app.get("transportId"):
                m = c.media_status(app["transportId"])
                if m:
                    out.update(state=m.get("playerState"), idle=m.get("idleReason"),
                               title=((m.get("media") or {}).get("metadata") or {}).get("title"))
            return out
        finally:
            c.close()

    def control(self, dev, action, level=None):
        c = CastConn(dev["host"], dev.get("port") or 8009)
        try:
            if action == "volume":
                c.request("receiver-0", NS_RECEIVER, {"type": "SET_VOLUME", "volume": {"level": max(0.0, min(1.0, float(level)))}})
                return
            app = c._app(c.receiver_status())
            if not app:
                raise CastError("Nothing is playing on that TV")
            m = c.media_status(app["transportId"])
            if not m:
                raise CastError("Nothing is playing on that TV")
            c.request(app["transportId"], NS_MEDIA, {"type": "PAUSE" if action == "pause" else "PLAY",
                                                     "mediaSessionId": m["mediaSessionId"]})
        finally:
            c.close()
