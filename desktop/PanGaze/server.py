#!/usr/bin/env python3
"""
PanGaze: local server + streaming proxy.

Serves xtream-player.html and relays requests to your Xtream provider so the
browser isn't blocked by CORS. Rewrites HLS playlists so every segment also
goes through the proxy. Uses only the Python standard library.

    python3 server.py                 # http://localhost:8899 (this Mac only)
    python3 server.py --lan           # also reachable from phones/TVs on your Wi-Fi
    python3 server.py --port 9000 --ua "IPTVSmartersPro" --insecure
"""
import argparse
import http.client
import http.server
import os
import re
import socket
import ssl
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import time
import json
import mimetypes
import shutil
import hashlib
import hmac
import secrets
import subprocess

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recorder as recorder_mod
try:
    import caster as caster_mod        # casting to Chromecast / Google TV / Shield (caster.py, next to this file)
except Exception as _e:                # missing or broken: PanGaze still runs, just without casting
    caster_mod = None
    print("  Note: casting is off (%s). Put caster.py next to server.py to cast to a TV." % _e)

HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER_FILE = os.path.join(HERE, "xtream-player.html")
CHUNK = 64 * 1024
URI_ATTR = re.compile(r'URI="([^"]+)"')
PASS_HEADERS = ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")

ARGS = None
SSL_CTX = None
REC = None


def make_ssl_context(insecure):
    if insecure:
        return ssl._create_unverified_context()
    try:  # python.org builds on macOS often lack a CA bundle; use certifi if present
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


def proxied(u, suffix=""):
    return "/proxy?url=" + urllib.parse.quote(u, safe="") + "&h=1" + suffix


# ---------------- security: pairing code, trusted devices, safe origins ----------------
# The Mac itself (and pages it served to itself) is trusted. Any other device on the Wi-Fi needs
# the PanGaze code once ("pairing"). Requests started by other websites are refused, so a web page
# you happen to visit can't use PanGaze behind your back.
LANKEY_FILE = os.path.join(HERE, "xp-lankey.json")
LANKEY_LOCK = threading.Lock()
LANKEY = None
CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"     # no 0/O, 1/I/L: easy to read and type on a TV
PAIR_FAILS = {}                                       # ip -> [wrong tries, locked-until]
APP_ORIGINS = ("https://appassets.androidplatform.net",)   # the Android app's page
# open to everyone: the page itself, help, the phone-remote page and the "is PanGaze here?" check
OPEN_PATHS = ("/", "/index.html", "/xtream-player.html", "/help.html", "/__xp_ping", "/favicon.ico", "/remote")


def norm_code(s):
    return re.sub(r"[^A-Z0-9]", "", str(s or "").upper())


def fmt_code(k):
    return k[:4] + "-" + k[4:]


def lan_key(reset=False):
    """The PanGaze code (8 letters/numbers). Made on first run and kept in xp-lankey.json."""
    global LANKEY
    with LANKEY_LOCK:
        if LANKEY and not reset:
            return LANKEY
        if not reset:
            try:
                with open(LANKEY_FILE, "r", encoding="utf-8") as f:
                    k = norm_code(json.load(f).get("key"))
                if len(k) == 8:
                    LANKEY = k
                    return k
            except Exception:
                pass
        k = "".join(secrets.choice(CODE_CHARS) for _ in range(8))
        tmp = LANKEY_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"key": k, "created": time.time()}, f)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, LANKEY_FILE)
        LANKEY = k
        return k


def key_matches(k):
    k = norm_code(k)
    return len(k) == 8 and hmac.compare_digest(k, lan_key())


def _netloc(u):
    try:
        return urllib.parse.urlsplit(u).netloc.lower()
    except ValueError:
        return ""


def _is_private_name(name):
    """localhost, private/loopback IPs, single-word names and home-network names (.local, .lan, …)."""
    import ipaddress
    name = (name or "").strip("[]").lower()
    if not name:
        return False
    try:
        ip = ipaddress.ip_address(name)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        pass
    if name in (ARGS.allow_host if ARGS and ARGS.allow_host else []):
        return True
    return "." not in name or name.endswith((".local", ".localhost", ".lan", ".home", ".home.arpa", ".internal"))


def host_ok(host):
    """Stops "DNS rebinding": another website pointing its own name at this Mac to reach PanGaze."""
    h = (host or "").strip().lower()
    if not h:
        return True
    name = h[1:h.find("]")] if h.startswith("[") and "]" in h else h.rsplit(":", 1)[0]
    return _is_private_name(name)


def friendly_origin(origin):
    """Pages that may read PanGaze's answers from another address: the Android app, and PanGaze
    pages on this network (they still need the code)."""
    if not origin or origin == "null":
        return False
    if origin in APP_ORIGINS:
        return True
    try:
        return _is_private_name(urllib.parse.urlsplit(origin).hostname)
    except ValueError:
        return False


# ---------------- VPN check (the green/red dot on Home) ----------------
# Asks the computer which network connection it would use to reach the internet. If that's a VPN
# tunnel, streams fetched by PanGaze go through the VPN.
VPN_IFACE = re.compile(r"^(utun|ppp|ipsec|tun|tap|wg|tailscale|nordlynx|proton|mullvad|zt)", re.I)
VPN_WORDS = re.compile(r"vpn|wireguard|wintun|tap-windows|openvpn|tunnel|nordlynx|proton|mullvad|express|"
                       r"surfshark|cyberghost|private internet|windscribe|anyconnect|fortinet|globalprotect|"
                       r"zscaler|tailscale|zerotier|hotspot shield|ipvanish|pangp", re.I)
VPN_CACHE = {"at": 0.0, "res": None}
CASTER = caster_mod.Caster() if caster_mod else None
VPN_LOCK = threading.Lock()


def _run(cmd, timeout=6):
    flags = 0x08000000 if IS_WIN else 0      # CREATE_NO_WINDOW: no console flash on Windows
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, creationflags=flags).stdout or ""


def vpn_status():
    """{"vpn": True/False/None, "iface": name}. Cached for 10 seconds."""
    with VPN_LOCK:
        if time.time() - VPN_CACHE["at"] < 10 and VPN_CACHE["res"]:
            return VPN_CACHE["res"]
    res = {"vpn": None, "iface": ""}
    try:
        if sys.platform == "darwin":
            m = re.search(r"interface:\s*(\S+)", _run(["route", "-n", "get", "1.1.1.1"]))
            if m:
                res = {"vpn": bool(VPN_IFACE.match(m.group(1))), "iface": m.group(1)}
        elif IS_WIN:
            ps = ("$r=Find-NetRoute -RemoteIPAddress 1.1.1.1 -ErrorAction Stop|Select-Object -First 1;"
                  "$a=Get-NetAdapter -InterfaceIndex $r.InterfaceIndex;$a.Name+'|'+$a.InterfaceDescription")
            out = _run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps], 10).strip()
            if "|" in out:
                name, desc = out.split("|", 1)
                res = {"vpn": bool(VPN_WORDS.search(name + " " + desc)), "iface": desc or name}
        else:
            m = re.search(r"\bdev\s+(\S+)", _run(["ip", "route", "get", "1.1.1.1"]))
            if m:
                res = {"vpn": bool(VPN_IFACE.match(m.group(1))), "iface": m.group(1)}
    except Exception:
        pass
    with VPN_LOCK:
        VPN_CACHE.update(at=time.time(), res=res)
    return res


def rewrite_playlist(text, base, suffix=""):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            if 'URI="' in s:
                s = URI_ATTR.sub(
                    lambda m: m.group(0) if m.group(1).startswith("data:")
                    else 'URI="%s"' % proxied(urllib.parse.urljoin(base, m.group(1)), suffix), s)
            out.append(s)
        else:
            out.append(proxied(urllib.parse.urljoin(base, s), suffix))
    return "\n".join(out) + "\n"


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "PanGaze/1.0"

    def setup(self):
        super().setup()
        try:
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass

    def log_message(self, fmt, *args):
        if ARGS.verbose:
            sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    def cors(self):
        # only the Android app and PanGaze pages on this network may read answers from another
        # address. Ordinary websites get no permission header, so their pages can't read anything.
        origin = self.headers.get("Origin")
        if origin and (friendly_origin(origin) or getattr(self, "key_ok", False)
                       or _netloc(origin) == (self.headers.get("Host") or "").lower()):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Access-Control-Allow-Headers", "Range, Content-Type, X-PanGaze-Key")
            self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, X-PanGaze-Pair")
        self.send_header("Vary", "Origin")

    # ---------- who is asking? ----------
    def is_local(self):
        """This Mac itself (loopback, or its own Wi-Fi address)."""
        ip = self.client_address[0]
        if ip in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
            return True
        try:
            return ip == self.request.getsockname()[0]
        except OSError:
            return False

    def foreign(self):
        """True when another website started this request (not a page PanGaze served)."""
        host = (self.headers.get("Host") or "").lower()
        origin = self.headers.get("Origin")
        if origin is not None:
            return _netloc(origin) != host
        sfs = (self.headers.get("Sec-Fetch-Site") or "").lower()
        if sfs:
            return sfs == "cross-site"
        ref = self.headers.get("Referer")       # older browsers without Sec-Fetch-Site
        return bool(ref) and _netloc(ref) != host

    def presented_key(self, q):
        k = self.headers.get("X-PanGaze-Key") or (q or {}).get("k")
        if k:
            return k
        if not self.foreign():                  # the cookie only counts on PanGaze's own pages
            for part in (self.headers.get("Cookie") or "").split(";"):
                name, _, val = part.strip().partition("=")
                if name == "pgk":
                    return val
        return ""

    def trusted(self, q=None):
        if key_matches(self.presented_key(q)):
            self.key_ok = True
            return True
        return self.is_local() and not self.foreign()

    def gate(self, path, q):
        """Runs before every request. Returns True if it may go ahead (otherwise it has answered)."""
        if not host_ok(self.headers.get("Host")):
            self.send_text(403, "PanGaze only answers on your own network.")
            return False
        if path in OPEN_PATHS or path == "/pair" or path.startswith("/pair/"):
            return True
        if path.startswith("/account/") or path.startswith("/remote/"):
            # these check their own password / TV code; just refuse other websites
            if self.foreign() and not friendly_origin(self.headers.get("Origin")):
                self.send_text(403, "Not allowed from another website")
                return False
            return True
        if self.trusted(q):
            return True
        if self.foreign() and not friendly_origin(self.headers.get("Origin")) and not (q or {}).get("k"):
            self.send_text(403, "Not allowed from another website")
            return False
        body = json.dumps({"ok": False, "error": "pair",
                           "message": "This device needs the PanGaze code from your %s once." % host_word()}).encode()
        self.send_response(401)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-PanGaze-Pair", "1")
        self.cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        return False

    # ---------- casting to a Chromecast / Google TV / Nvidia Shield: /cast/* ----------
    def cast(self, action, q):
        if CASTER is None:
            return self.send_json({"ok": False, "error": "Casting needs caster.py next to server.py on your %s. "
                                   "Re-run Install PanGaze (background) from the new download." % host_word()}, 501)
        body = {}
        if self.command == "POST":
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}
            except ValueError:
                return self.send_json({"ok": False, "error": "Bad request"}, 400)
        a = dict(q, **body)
        try:
            if action == "devices":
                devs = CASTER.scan(force=a.get("refresh") in ("1", 1, True), iface_ip=lan_ip())
                return self.send_json({"ok": True, "devices": devs, "lan": bool(ARGS.lan)})
            dev = CASTER.device(a.get("id"), a.get("host"))
            if action == "play":
                if not ARGS.lan:
                    return self.send_json({"ok": False, "error": "PanGaze needs phone & TV access for casting. "
                                           "Start it with the Start file, or run: python3 server.py --lan"}, 400)
                ip = lan_ip()
                if not ip:
                    return self.send_json({"ok": False, "error": "This %s isn't on a network" % host_word()}, 400)
                src = str(a.get("url") or "")
                base = "http://%s:%d" % (ip, ARGS.port)
                if src.startswith(("http://", "https://")):
                    play = base + "/proxy?url=" + urllib.parse.quote(src, safe="")
                elif src.startswith("/"):
                    play = base + src
                else:
                    return self.send_json({"ok": False, "error": "Nothing to cast"}, 400)
                play += ("&" if "?" in play else "?") + "k=" + lan_key()      # the TV needs the PanGaze code
                CASTER.play(dev, play, title=a.get("title") or "", image=a.get("image") or "",
                            subtitle=a.get("sub") or "", live=bool(a.get("live")),
                            content_type=a.get("ctype") or None, start=a.get("start") or 0)
                return self.send_json({"ok": True, "device": dev})
            if action == "stop":
                CASTER.stop(dev)
                return self.send_json({"ok": True})
            if action == "status":
                st = CASTER.status(dev)
                return self.send_json(dict(st, ok=True, shared=dict(HLS_STATS)))
            if action == "control":
                CASTER.control(dev, a.get("action"), a.get("level"))
                return self.send_json({"ok": True})
            return self.send_text(404, "Not found")
        except Exception as e:
            if caster_mod is None or not isinstance(e, (caster_mod.CastError, OSError, ssl.SSLError)):
                raise
            if not isinstance(e, caster_mod.CastError):
                return self.send_json({"ok": False, "error": "Couldn't reach the TV (%s). Is it switched on?" % e.__class__.__name__}, 502)
            return self.send_json({"ok": False, "error": str(e)}, 502)

    # ---------- pairing: /pair/status, /pair/join?code=, /pair/code, /pair/reset ----------
    def pair(self, action, q):
        if action == "status":
            return self.send_json({"ok": True, "paired": self.trusted(q), "local": self.is_local() and not self.foreign(),
                                   "lan": bool(ARGS.lan)})
        if action == "join":
            if self.foreign() and not friendly_origin(self.headers.get("Origin")):
                return self.send_text(403, "Not allowed from another website")
            ip = self.client_address[0]
            with LANKEY_LOCK:
                fails = PAIR_FAILS.get(ip, [0, 0])
            if fails[1] > time.time():
                return self.send_json({"ok": False, "error": "Too many wrong codes. Wait 5 minutes and try again."}, 429)
            if not key_matches(q.get("code")):
                with LANKEY_LOCK:
                    fails[0] += 1
                    if fails[0] >= 8:
                        fails = [0, time.time() + 300]
                    PAIR_FAILS[ip] = fails
                time.sleep(0.5)
                return self.send_json({"ok": False, "error": "That code isn't right. Check it on your %s." % host_word()}, 401)
            with LANKEY_LOCK:
                PAIR_FAILS.pop(ip, None)
            return self.send_json({"ok": True, "key": lan_key()}, cookie=lan_key())
        if action == "code":
            if not self.trusted(q):
                return self.send_json({"ok": False, "error": "pair"}, 401)
            ip = lan_ip() if ARGS.lan else None
            k = lan_key()
            return self.send_json({"ok": True, "code": fmt_code(k), "key": k, "local": self.is_local(),
                                   "lan": ("%s:%d" % (ip, ARGS.port)) if ip else None,
                                   "url": ("http://%s:%d/#pair=%s" % (ip, ARGS.port, fmt_code(k))) if ip else None})
        if action == "reset":
            if not (self.is_local() and not self.foreign()) or self.command != "POST":
                return self.send_json({"ok": False, "error": "Do this on the %s itself" % host_word()}, 403)
            k = lan_key(reset=True)
            print("  New PanGaze code: %s (other devices need it again)" % fmt_code(k))
            return self.send_json({"ok": True, "code": fmt_code(k), "key": k}, cookie=k)
        return self.send_text(404, "Not found")

    def send_text(self, code, text, ctype="text/plain; charset=utf-8"):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_OPTIONS(self):
        if not host_ok(self.headers.get("Host")):
            return self.send_text(403, "PanGaze only answers on your own network.")
        q = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).items()}
        self.key_ok = key_matches(q.get("k"))
        self.send_response(204)
        self.cors()
        if friendly_origin(self.headers.get("Origin")) or self.key_ok:
            self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
        if not self.gate(parsed.path, q):
            return
        if parsed.path.startswith("/pair/"):
            return self.pair(parsed.path[6:], q)
        if parsed.path.startswith("/account/"):
            return self.account(parsed.path[9:], "POST")
        if parsed.path.startswith("/cast/"):
            return self.cast(parsed.path[6:], q)
        if parsed.path == "/audiofix/upload":
            return self.ffmpeg_upload()
        if parsed.path != "/sync":
            return self.send_text(404, "Not found")
        key = (urllib.parse.parse_qs(parsed.query).get("key") or [""])[0]
        n = content_length(self.headers)
        if not key or n <= 0 or n > 8 * 1024 * 1024:
            return self.send_json({"ok": False, "error": "bad request"}, 400)
        try:
            doc = json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return self.send_json({"ok": False, "error": "bad json"}, 400)
        with SYNC_LOCK:
            store = load_sync()
            rev = sync_rev(store.get(key)) + 1
            store[key] = {"doc": doc, "t": time.time(), "rev": rev}
            save_sync()
            SYNC_CHANGED.notify_all()           # other devices waiting in /sync/wait hear about it now
        self.send_json({"ok": True, "rev": rev})

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if not self.gate(path, {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}):
            return
        if path.startswith("/pair/"):
            return self.pair(path[6:], {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path in ("/", "/index.html", "/xtream-player.html"):
            return self.serve_player()
        if path == "/help.html":
            try:
                with open(os.path.join(HERE, "help.html"), "rb") as f:
                    body = f.read()
            except OSError:
                return self.send_text(404, "help.html not found next to server.py")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            return self.wfile.write(body) if self.command != "HEAD" else None
        if path == "/sync":
            qs = urllib.parse.parse_qs(parsed.query)
            key, have = (qs.get("key") or [""])[0], (qs.get("rev") or [""])[0]
            with SYNC_LOCK:
                ent = load_sync().get(key)
                rev = sync_rev(ent)
                if have and have == str(rev) and rev:
                    return self.send_json({"ok": True, "same": True, "rev": rev})      # nothing new: tiny reply
                return self.send_json({"ok": True, "doc": ent["doc"] if ent else None, "rev": rev})
        if path == "/sync/wait":
            # held open until one of these keys changes (or ~25 s pass), so devices update straight away
            qs = urllib.parse.parse_qs(parsed.query)
            keys = [k for k in (qs.get("keys") or [""])[0].split("\n") if k][:8]
            revs = (qs.get("revs") or [""])[0].split(",")
            want = {k: (int(revs[i]) if i < len(revs) and revs[i].isdigit() else -1) for i, k in enumerate(keys)}
            end = time.time() + min(25.0, max(1.0, float((qs.get("t") or ["25"])[0] or 25)))
            with SYNC_LOCK:
                while True:
                    store = load_sync()
                    changed = [k for k, r in want.items() if sync_rev(store.get(k)) != r]
                    left = end - time.time()
                    if changed or left <= 0:
                        break
                    SYNC_CHANGED.wait(left)
                now = {k: sync_rev(store.get(k)) for k in keys}
            return self.send_json({"ok": True, "changed": changed, "revs": now})
        if path.startswith("/account/"):
            return self.account(path[9:], "GET", {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/__xp_ping":
            return self.send_text(200, "xp-proxy")
        if path.startswith("/cast/"):
            return self.cast(path[6:], {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/vpn":
            return self.send_json(dict(vpn_status(), ok=True))
        if path == "/proxy":
            qs = urllib.parse.parse_qs(parsed.query)
            return self.proxy((qs.get("url") or [""])[0], (qs.get("k") or [""])[0], (qs.get("h") or [""])[0] == "1")
        if path == "/xhr":
            return self.xhr({k: v[0] for k, v in urllib.parse.parse_qs(parsed.query, keep_blank_values=True).items()})
        if path.startswith("/rec/"):
            return self.rec_api(path[5:], {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/remote" or path.startswith("/remote/"):
            return self.remote(path, {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path.startswith("/media/"):
            return self.media(path[7:], {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/openext":
            return self.open_external({k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/audiofix" or path.startswith("/audiofix/"):
            return self.audiofix(path[10:], {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()})
        if path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        self.send_text(404, "Not found")

    def send_json(self, obj, code=200, cookie=None):
        if not cookie:
            return self.send_text(code, json.dumps(obj), "application/json; charset=utf-8")
        # pairing worked: remember it in this browser (sent only on PanGaze's own pages)
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Set-Cookie", "pgk=%s; Path=/; Max-Age=315360000; SameSite=Strict; HttpOnly" % cookie)
        self.cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def rec_api(self, action, q):
        try:
            if action == "status":
                return self.send_json(REC.status())
            if action == "add":
                return self.send_json({"ok": True, "item": REC.add(json.loads(q.get("data", "{}")))})
            if action == "stop":
                return self.send_json({"ok": REC.stop(q.get("id", ""))})
            if action == "delete":
                return self.send_json({"ok": REC.delete(q.get("id", ""))})
            if action == "config":
                return self.send_json(REC.configure(q))
            if action == "compress":
                return self.send_json({"ok": True, "item": REC.compress_now(q.get("id", ""), q.get("level", ""))})
            if action == "compress_cancel":
                return self.send_json({"ok": REC.cancel_compress(q.get("id", ""))})
            if action == "rule_add":
                return self.send_json({"ok": True, "rule": REC.add_rule(json.loads(q.get("data", "{}")))})
            if action == "rule_del":
                return self.send_json({"ok": REC.del_rule(q.get("id", ""))})
            if action == "reveal":
                return self.send_json({"ok": REC.reveal(q.get("id", ""))})
            if action == "drive":
                return self.send_json({"ok": True, "path": REC.copy_to_drive(q.get("id", ""))})
            if action == "file":
                return self.serve_file(REC.file_for(q.get("id", "")))
        except Exception as e:
            return self.send_json({"ok": False, "error": str(e)}, 400)
        self.send_json({"ok": False, "error": "unknown action"}, 404)

    # ---------- phone remote / pairing ----------
    def remote(self, path, q):
        pin = q.get("t", "")
        if path == "/remote":
            try:
                with open(os.path.join(HERE, "remote.html"), "rb") as f:
                    body = f.read()
            except OSError:
                return self.send_text(500, "remote.html not found next to server.py")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            return self.wfile.write(body) if self.command != "HEAD" else None
        if path == "/remote/register":
            if not re.fullmatch(r"\d{6}", pin):
                return self.send_json({"ok": False}, 400)
            with REMOTE_COND:
                REMOTE.setdefault(pin, [])
            ip = lan_ip() if ARGS.lan else None
            return self.send_json({"ok": True, "addr": "%s:%d" % (ip or "localhost", ARGS.port), "local": not ARGS.lan})
        if path == "/remote/cmd":
            with REMOTE_COND:
                if pin not in REMOTE:
                    return self.send_text(403, "Wrong code")
                try:
                    cmd = json.loads(q.get("d") or "{}")
                except ValueError:
                    return self.send_text(400, "Bad command")
                if cmd.get("c") != "ping":
                    REMOTE[pin] = (REMOTE[pin] + [cmd])[-20:]
                    REMOTE_COND.notify_all()
            return self.send_json({"ok": True})
        if path == "/remote/poll":
            deadline = time.time() + 25
            with REMOTE_COND:
                if pin not in REMOTE:
                    return self.send_text(403, "Not registered")
                while not REMOTE[pin] and time.time() < deadline:
                    REMOTE_COND.wait(deadline - time.time())
                out, REMOTE[pin] = REMOTE[pin], []
            return self.send_json(out)
        return self.send_text(404, "Not found")

    # ---------- PanGaze accounts: one account holds your settings, logins and history for every device ----------
    def account(self, action, method, q=None):
        body = {}
        if method == "POST":
            n = content_length(self.headers)
            if n < 0 or n > 16 * 1024 * 1024:
                return self.send_json({"ok": False, "error": "Too big"}, 413)
            try:
                body = json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}
            except Exception:
                return self.send_json({"ok": False, "error": "bad json"}, 400)
        else:
            body = q or {}
        trusted = self.trusted(q)
        if action == "create" and not trusted:
            return self.send_json({"ok": False, "error": "Create your PanGaze account on your %s, or connect this device "
                                   "with the PanGaze code first (Settings > Phone & TV on the %s)." % (host_word(), host_word())}, 403)
        try:
            code, out = account_action(action, body, self.client_address[0])
        except Exception as e:
            code, out = 500, {"ok": False, "error": str(e)}
        if action in ("create", "login") and code == 200:
            # signing in with your PanGaze account also connects this device (no code to type)
            out["pair"] = lan_key()
            return self.send_json(out, code, cookie=lan_key())
        return self.send_json(out, code)

    # the person downloaded ffmpeg in their browser and chose the file: install that copy
    def ffmpeg_upload(self):
        import tempfile
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return self.send_json({"ok": False, "error": "Do this on the Mac itself"}, 403)
        n = content_length(self.headers)
        if n <= 0 or n > 500 * 1024 * 1024:
            return self.send_json({"ok": False, "error": "That file is empty or too big"}, 400)
        fd, tmp = tempfile.mkstemp()
        try:
            with os.fdopen(fd, "wb") as out:
                left = n
                while left > 0:
                    chunk = self.rfile.read(min(CHUNK, left))
                    if not chunk:
                        break
                    out.write(chunk)
                    left -= len(chunk)
            ffmpeg_place(tmp)
            return self.send_json({"ok": True})
        except Exception as e:
            return self.send_json({"ok": False, "error": str(e)})
        finally:
            try:
                os.remove(tmp)
            except Exception:
                pass

    # ---------- Open a film in VLC on this Mac (browsers can't start VLC themselves) ----------
    def open_external(self, q):
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return self.send_json({"ok": False, "error": "remote"}, 403)
        url = q.get("url") or ""
        if url.startswith("/"):
            url = "http://127.0.0.1:%d%s" % (self.server.server_address[1], url)
        if not re.match(r"^https?://", url):
            return self.send_json({"ok": False, "error": "badurl"}, 400)
        try:
            if sys.platform == "darwin":
                if subprocess.run(["open", "-Ra", "VLC"], capture_output=True, timeout=10).returncode != 0:
                    return self.send_json({"ok": False, "error": "notinstalled"})
                subprocess.Popen(["open", "-a", "VLC", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                vlc = shutil.which("vlc") or next((c for c in (r"C:\Program Files\VideoLAN\VLC\vlc.exe",
                                                                r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe") if os.path.exists(c)), None)
                if not vlc:
                    return self.send_json({"ok": False, "error": "notinstalled"})
                subprocess.Popen([vlc, url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as e:
            return self.send_json({"ok": False, "error": str(e)})
        return self.send_json({"ok": True})

    # ---------- Sound fix: films whose audio the browser can't play (AC3, E-AC3, DTS, TrueHD) ----------
    # The picture still streams straight to the browser. This converts only the soundtrack to stereo AAC,
    # starting at the requested second, and the page plays it in step with the picture.
    def audiofix(self, action, q):
        if action == "status":
            return self.send_json(afx_status())
        if action in ("install", "download"):
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self.send_json({"ok": False, "error": "Install ffmpeg on the Mac itself"}, 403)
            return self.send_json(afx_install() if action == "install" else afx_download())
        if action:
            return self.send_text(404, "Not found")
        ff = recorder_mod.find_ffmpeg()
        url = q.get("url") or ""
        if not ff:
            return self.send_text(503, "ffmpeg is not installed")
        if url.startswith("/"):
            url = "http://127.0.0.1:%d%s" % (self.server.server_address[1], url)
        if not re.match(r"^https?://", url):
            return self.send_text(400, "Bad url")
        try:
            t = max(0.0, float(q.get("t") or 0))
        except ValueError:
            t = 0.0
        cmd = [ff, "-hide_banner", "-loglevel", "error", "-nostdin"]
        if not url.startswith("http://127.0.0.1"):
            cmd += ["-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"]
            if ARGS.insecure:
                cmd += ["-tls_verify", "0"]
        cmd += ["-ss", "%.3f" % t, "-i", url, "-vn", "-sn", "-dn",
                "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000", "-f", "adts", "pipe:1"]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0, **recorder_mod.NOWIN)
        except OSError as e:
            return self.send_text(500, "Couldn't start ffmpeg: %s" % e)
        self.close_connection = True
        try:
            first = proc.stdout.read(4096)
            if not first:
                err = (proc.stderr.read() or b"").decode("utf-8", "replace").strip().splitlines()
                return self.send_text(502, "ffmpeg couldn't read this film: " + (err[-1] if err else "no sound found"))
            self.send_response(200)
            self.send_header("Content-Type", "audio/aac")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.cors()
            self.end_headers()
            self.wfile.write(first)
            while True:
                chunk = proc.stdout.read(CHUNK)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout):
            pass  # the page stopped, seeked or closed the film
        finally:
            try:
                proc.kill()
            except Exception:
                pass
            try:
                proc.wait(timeout=5)
            except Exception:
                pass

    # ---------- My Media: folders on this Mac ----------
    def media(self, action, q):
        roots = load_media()
        if action == "roots":
            return self.send_json({"roots": [{"name": os.path.basename(r.rstrip("/\\")) or r, "path": r} for r in roots]})
        if action == "config":
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self.send_text(403, "Folders can only be added on the Mac itself")
            if q.get("add"):
                p = os.path.realpath(os.path.expanduser(q["add"]))
                if not os.path.isdir(p):
                    return self.send_text(400, "That folder doesn't exist: %s" % p)
                if p not in roots:
                    roots.append(p)
            if q.get("remove"):
                roots = [r for r in roots if r != q["remove"]]
            save_media(roots)
            return self.send_json({"ok": True})
        target = os.path.realpath(q.get("path", ""))
        allowed = any(target == r or target.startswith(r.rstrip("/\\") + os.sep) for r in roots)
        if action == "search":
            needle = q.get("q", "").lower()
            hits = []
            for r in roots:
                for dp, dns, fns in os.walk(r):
                    dns[:] = [d for d in dns if not d.startswith(".")]
                    for fn in fns:
                        if needle in fn.lower() and MEDIA_EXT.search(fn):
                            hits.append({"name": fn, "path": os.path.join(dp, fn), "dir": os.path.basename(dp)})
                    if len(hits) >= 40:
                        break
            return self.send_json({"files": hits[:40]})
        if not allowed:
            return self.send_text(403, "Not in a shared folder")
        if action == "list":
            dirs, files = [], []
            try:
                for e in sorted(os.scandir(target), key=lambda e: e.name.lower()):
                    if e.name.startswith("."):
                        continue
                    if e.is_dir():
                        dirs.append({"name": e.name, "path": e.path})
                    elif MEDIA_EXT.search(e.name):
                        files.append({"name": e.name, "path": e.path, "size": e.stat().st_size})
            except OSError as ex:
                return self.send_text(500, str(ex))
            return self.send_json({"dirs": dirs, "files": files})
        if action == "file":
            if not os.path.isfile(target):
                return self.send_text(404, "Not found")
            return self.serve_file(target, mimetypes.guess_type(target)[0] or "video/x-matroska")
        return self.send_text(404, "Not found")

    def serve_file(self, path, ctype=None):
        if not path:
            return self.send_text(404, "Recording not found")
        size = os.path.getsize(path)
        ctype = ctype or ("video/mp4" if path.lower().endswith(".mp4") else "video/mp2t")
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            else:
                start = max(0, size - int(m.group(2)))
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.cors()
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    c = f.read(min(CHUNK, left))
                    if not c:
                        break
                    self.wfile.write(c)
                    left -= len(c)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def serve_player(self):
        try:
            with open(PLAYER_FILE, "rb") as f:
                body = f.read()
        except OSError:
            return self.send_text(500, "xtream-player.html not found next to server.py")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def xhr(self, q):
        """Relays an API call (method, headers, body) for the Debrid tab: debrid services don't always allow browsers."""
        url = q.get("url", "")
        method = (q.get("m") or "GET").upper()
        if not url.startswith(("http://", "https://")) or method not in ("GET", "POST", "PUT", "DELETE"):
            return self.send_text(400, "Bad request")
        try:
            hdrs = json.loads(q.get("h") or "{}")
        except Exception:
            hdrs = {}
        hdrs = {str(k): str(v) for k, v in hdrs.items()
                if str(k).lower() not in ("host", "content-length", "connection", "cookie", "origin", "referer")}
        hdrs.setdefault("User-Agent", "XtreamPlayer/1.0")
        hdrs.setdefault("Accept", "application/json")
        body = q["b"].encode("utf-8") if "b" in q else None
        req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
        try:
            resp = urllib.request.urlopen(req, timeout=30, context=SSL_CTX if url.startswith("https") else None)
            code, data, ctype = resp.status, resp.read(16 * 1024 * 1024), resp.headers.get("Content-Type")
        except urllib.error.HTTPError as e:
            code, data, ctype = e.code, e.read(1024 * 1024), e.headers.get("Content-Type")
        except Exception as e:
            return self.send_text(502, "Could not reach %s: %s" % (urllib.parse.urlparse(url).hostname, e))
        self.send_response(code)
        self.send_header("Content-Type", ctype or "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.cors()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_hls(self, c, code_key, rng=None):
        body = c["body"]
        ctype = c["ctype"] or "application/octet-stream"
        status = c["status"]
        if c["playlist"] and body.lstrip().startswith(b"#EXTM3U"):
            # a player that sent the code in the address (Chromecast, TV) needs it on every segment too
            suffix = "&k=" + urllib.parse.quote(norm_code(code_key)) if code_key and key_matches(code_key) else ""
            body = rewrite_playlist(body.decode("utf-8", "replace"), c["final"], suffix).encode("utf-8")
            ctype = "application/vnd.apple.mpegurl"
        extra = {}
        if rng and status == 200 and not c["playlist"]:
            # players often ask for "bytes=0-" (all of it) or a part: answer from the shared copy
            size = len(body)
            start = int(rng.group(1))
            end = min(int(rng.group(2)), size - 1) if rng.group(2) else size - 1
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.cors()
                self.end_headers()
                return
            body = body[start:end + 1]
            status = 206
            extra = {"Content-Range": "bytes %d-%d/%d" % (start, end, size), "Accept-Ranges": "bytes"}
        self.send_response(status)
        for h, v in extra.items():
            self.send_header(h, v)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.cors()
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    def proxy(self, url, code_key="", hint=False):
        if not url.startswith(("http://", "https://")):
            return self.send_text(400, "Bad or missing url parameter")
        # HLS playlists and their pieces are shared between screens watching the same thing
        rng = self.headers.get("Range")
        rng_m = re.match(r"^bytes=(\d+)-(\d*)$", rng.strip()) if rng else None
        if self.command in ("GET", "HEAD") and (not rng or rng_m) and (
                hint or urllib.parse.urlsplit(url).path.lower().endswith(".m3u8")):
            c = hls_get(url)
            if c is not None:
                return self.send_hls(c, code_key, rng_m)
        headers = {"User-Agent": ARGS.ua, "Accept": "*/*"}
        if self.headers.get("Range"):
            headers["Range"] = self.headers["Range"]
        try:
            resp, conn, key, final_url = upstream(url, headers)
        except ssl.SSLError as e:
            return self.send_text(502, "SSL error talking to provider (%s). Try: python3 server.py --insecure" % e)
        except Exception as e:
            return self.send_text(502, "Could not reach provider: %s" % e)

        done = False
        try:
            status = resp.status
            ctype = resp.getheader("Content-Type", "") or ""
            is_playlist = ("mpegurl" in ctype.lower()
                           or urllib.parse.urlparse(final_url).path.lower().endswith(".m3u8"))

            if is_playlist and status < 400:
                raw = resp.read()
                done = True
                if raw.lstrip().startswith(b"#EXTM3U"):
                    # a player that sent the code in the address (Chromecast, TV) needs it on every segment too
                    suffix = "&k=" + urllib.parse.quote(norm_code(code_key)) if code_key and key_matches(code_key) else ""
                    raw = rewrite_playlist(raw.decode("utf-8", "replace"), final_url, suffix).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-store")
                self.cors()
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(raw)
                return

            self.send_response(status)
            for h in PASS_HEADERS:
                v = resp.getheader(h)
                if v:
                    self.send_header(h, v)
            if not ctype:
                self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Cache-Control", "no-store")
            self.cors()
            self.end_headers()
            if self.command == "HEAD":
                return
            # read1 forwards whatever has arrived straight away, instead of waiting for a full chunk
            read = resp.read1 if hasattr(resp, "read1") else resp.read
            while True:
                chunk = read(CHUNK)
                if not chunk:
                    done = True
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # player closed the connection (channel change, seek, stop)
        except socket.timeout:
            pass
        except Exception as e:
            if ARGS.verbose:
                sys.stderr.write("proxy error: %r\n" % e)
        finally:
            release_conn(key, conn, resp, done)


# ---------------- one provider connection for several screens ----------------
# Live HLS is a playlist that refreshes every few seconds plus short video pieces ("segments").
# When the Mac and a TV (e.g. casting to an Nvidia Shield) watch the same channel, each piece is
# fetched from the provider once and handed to both, and the playlist is fetched at most once a
# second. The provider sees one viewer, so a one-connection line is enough for both screens.
HLS = {}                # upstream URL -> {"at", "status", "ctype", "body", "final", "playlist"}
HLS_WAIT = {}           # upstream URL -> Event, while one request fetches it for everyone
HLS_LOCK = threading.Lock()
HLS_PLAYLIST_TTL = 1.0
HLS_SEGMENT_TTL = 150   # long enough for a Chromecast running 30+ seconds behind
HLS_MAX_BYTES = 400 * 1024 * 1024
HLS_STATS = {"upstream": 0, "shared": 0}


def _hls_trim():
    total = sum(len(v["body"]) for v in HLS.values())
    if total <= HLS_MAX_BYTES:
        return
    for k, v in sorted(HLS.items(), key=lambda kv: kv[1]["at"]):
        total -= len(v["body"])
        del HLS[k]
        if total <= HLS_MAX_BYTES * 0.8:
            break


def hls_get(url):
    """Fetch a playlist or segment once for every screen asking for it. None = fetch it normally."""
    is_pl = urllib.parse.urlsplit(url).path.lower().endswith(".m3u8")
    ttl = HLS_PLAYLIST_TTL if is_pl else HLS_SEGMENT_TTL
    for _ in range(2):
        with HLS_LOCK:
            now = time.time()
            for k in [k for k, v in HLS.items() if now - v["at"] > (HLS_PLAYLIST_TTL if v["playlist"] else HLS_SEGMENT_TTL)]:
                del HLS[k]
            c = HLS.get(url)
            if c:
                HLS_STATS["shared"] += 1
                return c
            ev = HLS_WAIT.get(url)
            leader = ev is None
            if leader:
                ev = HLS_WAIT[url] = threading.Event()
        if leader:
            break
        ev.wait(ARGS.timeout + 5)             # someone else is fetching it right now: use theirs
    try:
        headers = {"User-Agent": ARGS.ua, "Accept": "*/*"}
        resp, conn, pkey, final_url = upstream(url, headers)
        done = False
        try:
            body = resp.read()
            done = True
            HLS_STATS["upstream"] += 1
            ctype = resp.getheader("Content-Type", "") or ""
            c = {"at": time.time(), "status": resp.status, "ctype": ctype, "body": body, "final": final_url,
                 "playlist": body.lstrip().startswith(b"#EXTM3U") or "mpegurl" in ctype.lower()}
        finally:
            release_conn(pkey, conn, resp, done)
        if c["status"] < 400 and len(body) < 64 * 1024 * 1024:
            if not c["playlist"]:
                c["at"] = time.time() if not is_pl else c["at"]
            with HLS_LOCK:
                HLS[url] = c
                _hls_trim()
        return c
    except Exception:
        return None
    finally:
        with HLS_LOCK:
            HLS_WAIT.pop(url, None)
        ev.set()


# ---------------- upstream connections: reused (keep-alive) and redirect-aware ----------------
POOL = {}
POOL_LOCK = threading.Lock()
REDIRECTS = {}          # original URL -> (final URL, expiry); Xtream redirects every live request
REDIRECT_TTL = 45


def _pool_key(u):
    return (u.scheme, u.hostname, u.port or (443 if u.scheme == "https" else 80))


def _get_conn(key):
    with POOL_LOCK:
        lst = POOL.get(key) or []
        while lst:
            conn, t = lst.pop()
            if time.time() - t < 20:
                return conn, True
            try:
                conn.close()
            except Exception:
                pass
    scheme, host, port = key
    if scheme == "https":
        return http.client.HTTPSConnection(host, port, timeout=ARGS.timeout, context=SSL_CTX), False
    return http.client.HTTPConnection(host, port, timeout=ARGS.timeout), False


def release_conn(key, conn, resp, fully_read):
    """Keeps the connection for the next request if the response was read to the end."""
    try:
        if fully_read and not resp.will_close:
            with POOL_LOCK:
                lst = POOL.setdefault(key, [])
                if len(lst) < 8:
                    lst.append((conn, time.time()))
                    return
        conn.close()
    except Exception:
        pass


def _request(url, headers):
    u = urllib.parse.urlsplit(url)
    key = _pool_key(u)
    path = (u.path or "/") + ("?" + u.query if u.query else "")
    for attempt in range(2):
        conn, reused = _get_conn(key)
        try:
            conn.request("GET", path, headers=headers)
            return conn.getresponse(), conn, key
        except (http.client.RemoteDisconnected, http.client.BadStatusLine, ConnectionResetError,
                BrokenPipeError, ConnectionAbortedError):
            conn.close()
            if not reused or attempt:
                raise   # a fresh connection failed: give up; a stale reused one: try once more
    raise ConnectionError("could not connect")


def upstream(url, headers):
    cached = REDIRECTS.get(url)
    use_cache = bool(cached and cached[1] > time.time())
    target = cached[0] if use_cache else url
    for attempt in range(2):
        cur, hops = target, 0
        while True:
            resp, conn, key = _request(cur, headers)
            loc = resp.getheader("Location")
            if resp.status in (301, 302, 303, 307, 308) and loc and hops < 8:
                resp.read()
                release_conn(key, conn, resp, True)
                cur = urllib.parse.urljoin(cur, loc)
                hops += 1
                continue
            break
        if use_cache and attempt == 0 and resp.status >= 400:
            resp.read()
            release_conn(key, conn, resp, True)
            REDIRECTS.pop(url, None)      # the remembered address expired: ask the provider again
            target, use_cache = url, False
            continue
        if cur != url and resp.status < 400 and not use_cache:
            REDIRECTS[url] = (cur, time.time() + REDIRECT_TTL)
            if len(REDIRECTS) > 500:
                REDIRECTS.clear()
        return resp, conn, key, cur
    return resp, conn, key, cur


SYNC_FILE = os.path.join(HERE, "xp-sync.json")
SYNC_LOCK = threading.Lock()
SYNC_CHANGED = threading.Condition(SYNC_LOCK)   # wakes devices waiting in /sync/wait
SYNC_MEM = None                                  # kept in memory; written to disk shortly after a change
SYNC_DIRTY = threading.Event()


def load_sync():
    """The whole sync store (call with SYNC_LOCK held). Read from disk once, then kept in memory."""
    global SYNC_MEM
    if SYNC_MEM is None:
        try:
            with open(SYNC_FILE, "r", encoding="utf-8") as f:
                SYNC_MEM = json.load(f)
        except Exception:
            SYNC_MEM = {}
    return SYNC_MEM


def save_sync(store=None):
    """Mark the store changed; a background thread writes it to disk (at most about once a second)."""
    SYNC_DIRTY.set()


def _sync_writer():
    while True:
        SYNC_DIRTY.wait()
        time.sleep(0.8)                          # gather a burst of changes into one write
        SYNC_DIRTY.clear()
        with SYNC_LOCK:
            data = json.dumps(SYNC_MEM or {})
        try:
            tmp = SYNC_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(data)
            os.replace(tmp, SYNC_FILE)
        except OSError as e:
            print("  Couldn't save sync data: %s" % e)


threading.Thread(target=_sync_writer, daemon=True, name="sync-writer").start()


def sync_rev(ent):
    return int(ent.get("rev") or 0) if ent else 0


# ----- PanGaze accounts -----
ACCT_FILE = os.path.join(HERE, "xp-pgaccounts.json")
ACCT_LOCK = threading.Lock()
ACCT_FAILS = {}          # name -> [fail count, locked-until]


def content_length(headers):
    try:
        return int(headers.get("Content-Length") or 0)
    except ValueError:
        return -1


def acct_load():
    try:
        with open(ACCT_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"users": {}}


def acct_save(db):
    tmp = ACCT_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(db, f)
    os.replace(tmp, ACCT_FILE)
    try:
        os.chmod(ACCT_FILE, 0o600)
    except Exception:
        pass


def acct_hash(pw, salt, iters=200000):
    return hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), bytes.fromhex(salt), iters).hex()


def acct_tok_hash(tok):
    return hashlib.sha256(tok.encode("utf-8")).hexdigest()


def acct_user_by_token(db, tok):
    if not tok:
        return None, None
    h = acct_tok_hash(tok)
    for key, u in db["users"].items():
        if h in u.get("tokens", {}):
            return key, u
    return None, None


def acct_summary(u):
    return {"name": u["name"], "saved": u.get("saved"), "keys": len(u.get("data", {})),
            "devices": sorted({t.get("device") or "Device" for t in u.get("tokens", {}).values()})}


def account_action(action, b, ip):
    name = str(b.get("name") or "").strip()
    key = name.lower()
    pw = str(b.get("pass") or "")
    device = str(b.get("device") or "Device")[:60]
    if action == "where":
        lan = lan_ip()
        port = ARGS.port if ARGS else 8899
        out = {"ok": True, "lan": ("%s:%d" % (lan, port)) if lan else None, "os": host_os(),
               "listening": bool(ARGS and ARGS.lan)}
        if ip in ("127.0.0.1", "::1"):
            out["firewall"] = firewall_state()
        return 200, out
    with ACCT_LOCK:
        db = acct_load()
        if action in ("create", "login"):
            if not re.match(r"^[A-Za-z0-9 ._@'-]{2,40}$", name):
                return 400, {"ok": False, "error": "Choose a name of 2–40 letters or numbers"}
            if len(pw) < 4:
                return 400, {"ok": False, "error": "The password needs at least 4 characters"}
            if action == "create":
                if key in db["users"]:
                    return 409, {"ok": False, "error": "That name is already taken on this Mac"}
                salt = secrets.token_hex(16)
                db["users"][key] = {"name": name, "salt": salt, "hash": acct_hash(pw, salt), "created": time.time(),
                                    "tokens": {}, "data": {}, "saved": None}
            else:
                fails = ACCT_FAILS.get(key, [0, 0])
                if fails[1] > time.time():
                    return 429, {"ok": False, "error": "Too many wrong passwords. Try again in a minute."}
                u = db["users"].get(key)
                if not u or not hmac.compare_digest(acct_hash(pw, u["salt"]), u["hash"]):
                    fails[0] += 1
                    if fails[0] >= 5:
                        fails = [0, time.time() + 60]
                    ACCT_FAILS[key] = fails
                    time.sleep(0.6)
                    return 401, {"ok": False, "error": "That name or password isn't right"}
                ACCT_FAILS.pop(key, None)
            u = db["users"][key]
            tok = secrets.token_urlsafe(32)
            u.setdefault("tokens", {})[acct_tok_hash(tok)] = {"device": device, "created": time.time(), "last": time.time()}
            acct_save(db)
            return 200, dict(acct_summary(u), ok=True, token=tok)
        ukey, u = acct_user_by_token(db, str(b.get("token") or ""))
        if not u:
            return 401, {"ok": False, "error": "signedout"}
        u["tokens"][acct_tok_hash(str(b.get("token")))]["last"] = time.time()
        if action == "info":
            return 200, dict(acct_summary(u), ok=True)
        if action == "data":
            return 200, dict(acct_summary(u), ok=True, data=u.get("data", {}))
        if action == "save":
            changes = b.get("changes") or {}
            if not isinstance(changes, dict):
                return 400, {"ok": False, "error": "bad changes"}
            data = u.setdefault("data", {})
            n = 0
            for k, e in changes.items():
                if not (isinstance(k, str) and k.startswith("xp.") and isinstance(e, dict)):
                    continue
                t = float(e.get("t") or 0)
                if k in data and float(data[k].get("t") or 0) > t:
                    continue        # the account already has a newer copy from another device
                if e.get("v") is None:
                    data.pop(k, None)
                else:
                    data[k] = {"v": str(e["v"]), "t": t, "dev": device}
                n += 1
            if n:
                u["saved"] = {"t": time.time(), "dev": device}
            acct_save(db)
            return 200, dict(acct_summary(u), ok=True, saved_n=n)
        if action == "logout":
            u["tokens"].pop(acct_tok_hash(str(b.get("token"))), None)
            acct_save(db)
            return 200, {"ok": True}
        if action == "delete":
            if not hmac.compare_digest(acct_hash(pw, u["salt"]), u["hash"]):
                time.sleep(0.6)
                return 401, {"ok": False, "error": "That password isn't right"}
            db["users"].pop(ukey, None)
            acct_save(db)
            return 200, {"ok": True}
        return 404, {"ok": False, "error": "Not found"}


AFX_INSTALL = {"state": "idle", "log": ""}


def find_brew():
    for c in (shutil.which("brew"), "/opt/homebrew/bin/brew", "/usr/local/bin/brew"):
        if c and os.path.exists(c):
            return c
    return None


def afx_status():
    return {"ffmpeg": bool(recorder_mod.find_ffmpeg()), "brew": bool(find_brew()), "mac": sys.platform == "darwin",
            "os": host_os(), "setup": sys.platform == "darwin" or IS_WIN,
            "install": AFX_INSTALL["state"], "log": AFX_INSTALL["log"][-600:], "pct": AFX_INSTALL.get("pct"),
            "arch": mac_arch(), "url": ffmpeg_url()}


IS_WIN = sys.platform.startswith("win")


def host_word():
    return {"darwin": "Mac"}.get(sys.platform, "PC" if IS_WIN else "computer")


def host_os():
    return "mac" if sys.platform == "darwin" else "windows" if IS_WIN else "linux"


def mac_arch():
    if sys.platform != "darwin":
        return None
    try:   # ask the hardware, so an Intel copy of Python on an Apple Silicon Mac still gets the right build
        if subprocess.run(["sysctl", "-n", "hw.optional.arm64"], capture_output=True, text=True, timeout=5).stdout.strip() == "1":
            return "arm64"
    except Exception:
        pass
    return "amd64"


def ffmpeg_url():
    if os.environ.get("PANGAZE_FFMPEG_URL"):
        return os.environ["PANGAZE_FFMPEG_URL"]
    if IS_WIN:
        # gyan.dev release "essentials" build for 64-bit Windows (linked from ffmpeg.org)
        return "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
    # signed, notarised static builds for Apple Silicon and Intel Macs (ffmpeg.martin-riedl.de)
    return "https://ffmpeg.martin-riedl.de/redirect/latest/macos/%s/release/ffmpeg.zip" % (mac_arch() or "arm64")


def ffmpeg_place(path):
    """Take a downloaded ffmpeg (the .zip or the program itself), put it where PanGaze looks, and check it runs."""
    import zipfile
    dest_dir = recorder_mod.ffmpeg_home()
    os.makedirs(dest_dir, exist_ok=True)
    exe = "ffmpeg.exe" if IS_WIN else "ffmpeg"
    dest = os.path.join(dest_dir, exe)
    tmp = os.path.join(dest_dir, "ffmpeg-new.exe" if IS_WIN else "ffmpeg.new")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if os.path.basename(n).lower() == exe and not n.endswith("/")]
            if not names:
                raise ValueError("That zip doesn't contain ffmpeg")
            with z.open(names[0]) as src, open(tmp, "wb") as out:
                shutil.copyfileobj(src, out)
    else:
        shutil.copyfile(path, tmp)
    os.chmod(tmp, 0o755)
    if sys.platform == "darwin":
        subprocess.run(["xattr", "-d", "com.apple.quarantine", tmp], capture_output=True)
    try:
        r = subprocess.run([tmp, "-hide_banner", "-version"], capture_output=True, text=True, timeout=30, **recorder_mod.NOWIN)
        ok = r.returncode == 0 and "ffmpeg version" in (r.stdout or "")
    except (OSError, subprocess.SubprocessError):
        ok = False
    if not ok:
        os.remove(tmp)
        raise ValueError("That file isn't a working ffmpeg for this computer")
    os.replace(tmp, dest)
    return dest


def afx_download():
    if recorder_mod.find_ffmpeg():
        return {"ok": True, "state": "done"}
    if AFX_INSTALL["state"] == "running":
        return {"ok": True, "state": "running"}
    AFX_INSTALL.update(state="running", log="Downloading ffmpeg…", pct=0)

    def run():
        import tempfile
        url = ffmpeg_url()
        fd, tmp = tempfile.mkstemp(suffix=".zip")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "PanGaze/1.0"})
            with urllib.request.urlopen(req, timeout=60, context=SSL_CTX if url.startswith("https") else None) as resp, os.fdopen(fd, "wb") as out:
                total = int(resp.headers.get("Content-Length") or 0)
                got = 0
                while True:
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
                    got += len(chunk)
                    AFX_INSTALL["pct"] = int(got * 100 / total) if total else None
                    AFX_INSTALL["log"] = "Downloading ffmpeg… %d MB" % (got // (1024 * 1024))
            AFX_INSTALL["log"] = "Setting up ffmpeg…"
            ffmpeg_place(tmp)
            AFX_INSTALL.update(state="done", log="ffmpeg is ready", pct=100)
        except Exception as e:
            AFX_INSTALL.update(state="failed", log="Download failed: %s" % e)
        finally:
            try:
                os.remove(tmp)
            except Exception:
                pass
    threading.Thread(target=run, daemon=True).start()
    return {"ok": True, "state": "running"}


def afx_install():
    if recorder_mod.find_ffmpeg():
        return {"ok": True, "state": "done"}
    brew = find_brew()
    if not brew:
        return {"ok": False, "error": "nobrew"}
    if AFX_INSTALL["state"] == "running":
        return {"ok": True, "state": "running"}
    AFX_INSTALL.update(state="running", log="")

    def run():
        try:
            p = subprocess.Popen([brew, "install", "ffmpeg"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 env=dict(os.environ, HOMEBREW_NO_INSTALL_CLEANUP="1", NONINTERACTIVE="1"))
            for line in iter(p.stdout.readline, b""):
                AFX_INSTALL["log"] = (AFX_INSTALL["log"] + line.decode("utf-8", "replace"))[-4000:]
            p.wait()
            AFX_INSTALL["state"] = "done" if recorder_mod.find_ffmpeg() else "failed"
        except Exception as e:
            AFX_INSTALL["log"] += "\n%s" % e
            AFX_INSTALL["state"] = "failed"
    threading.Thread(target=run, daemon=True).start()
    return {"ok": True, "state": "running"}


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        # a browser tab closing mid-request is normal: don't fill the Terminal with error traces
        if isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout, TimeoutError)):
            return
        super().handle_error(request, client_address)


REMOTE = {}
REMOTE_COND = threading.Condition()
MEDIA_FILE = os.path.join(HERE, "xp-media.json")
MEDIA_EXT = re.compile(r"\.(mkv|mp4|m4v|avi|mov|wmv|webm|ts|m2ts|mpg|mpeg|flv)$", re.I)
mimetypes.add_type("video/x-matroska", ".mkv")


def load_media():
    try:
        with open(MEDIA_FILE, encoding="utf-8") as f:
            return [r for r in json.load(f).get("roots", []) if isinstance(r, str)]
    except (OSError, ValueError):
        return []


def save_media(roots):
    with open(MEDIA_FILE, "w", encoding="utf-8") as f:
        json.dump({"roots": roots}, f, indent=1)


def firewall_state():
    """macOS only: is the firewall on, and is it keeping other devices out of PanGaze?"""
    if sys.platform != "darwin":
        return None
    fw = "/usr/libexec/ApplicationFirewall/socketfilterfw"
    if not os.path.exists(fw):
        return None

    def ask(*a):
        try:
            return subprocess.run([fw] + list(a), capture_output=True, text=True, timeout=5).stdout
        except (OSError, subprocess.SubprocessError):
            return ""
    on = "enabled" in ask("--getglobalstate").lower()
    if not on:
        return {"on": False}
    t = ask("--getblockall").lower()
    blockall = "enabled" in t and "disabled" not in t
    exe = os.path.realpath(sys.executable)
    app = ask("--getappblocked", exe).lower()
    return {"on": True, "blockall": blockall, "blocked": "is blocked" in app or "block incoming" in app,
            "allowed": "is permitted" in app or "allow incoming" in app, "python": exe}


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return None
    finally:
        s.close()


def main():
    global ARGS, SSL_CTX
    p = argparse.ArgumentParser(description="PanGaze local server")
    p.add_argument("--port", type=int, default=8899)
    p.add_argument("--lan", action="store_true", help="listen on your local network (phones, tablets, TVs)")
    p.add_argument("--ua", default="VLC/3.0.20 LibVLC/3.0.20", help="User-Agent sent to the provider")
    p.add_argument("--timeout", type=float, default=20)
    p.add_argument("--insecure", action="store_true", help="skip HTTPS certificate checks for the provider")
    p.add_argument("--no-open", action="store_true", help="don't open the browser")
    p.add_argument("--verbose", action="store_true")
    p.add_argument("--log", help="write messages to this file (used when running in the background)")
    p.add_argument("--allow-host", action="append", default=[], metavar="NAME",
                   help="also answer to this address name, e.g. a Tailscale name (repeat for more)")
    ARGS = p.parse_args()
    ARGS.allow_host = [h.lower() for h in ARGS.allow_host]
    if ARGS.log or sys.stdout is None:      # Windows pythonw has no console at all
        try:
            out = open(ARGS.log or os.devnull, "a", encoding="utf-8", buffering=1)
        except OSError:
            out = open(os.devnull, "w")
        sys.stdout = sys.stderr = out
    elif hasattr(sys.stdout, "reconfigure"):
        try:                                # Windows consoles: print ✓, emoji and accents safely
            sys.stdout.reconfigure(errors="replace"); sys.stderr.reconfigure(errors="replace")
        except Exception:
            pass
    SSL_CTX = make_ssl_context(ARGS.insecure)
    global REC
    REC = recorder_mod.Recorder(ARGS.ua, SSL_CTX, ARGS.timeout)

    host = "0.0.0.0" if ARGS.lan else "127.0.0.1"
    try:
        httpd = Server((host, ARGS.port), Handler)
    except OSError as e:
        print("Could not start on port %d (%s). Is it already running? Try --port 9000" % (ARGS.port, e))
        sys.exit(1)

    local = "http://localhost:%d" % ARGS.port
    print("\n  PanGaze is running")
    print("  On this %s:%s%s" % ("Mac" if sys.platform == "darwin" else "PC", "   " if sys.platform == "darwin" else "    ", local))
    if ARGS.lan:
        ip = lan_ip()
        if ip:
            print("  On your Wi-Fi: http://%s:%d   (phones, tablets, smart TVs)" % (ip, ARGS.port))
            print("  First time on a phone/TV: http://%s:%d/#pair=%s" % (ip, ARGS.port, fmt_code(lan_key())))
        print("  PanGaze code:  %s   (new phones, tablets and TVs ask for it once)" % fmt_code(lan_key()))
    st = REC.status()
    print("  Recordings:    %s" % st["dir"])
    if st["drive"]["available"]:
        print("  Google Drive:  found (%s)" % st["drive"]["path"])
    print("\n  Keep this window open (and the computer awake) for scheduled recordings.")
    print("  Press Ctrl+C to stop.\n")
    if not ARGS.no_open:
        threading.Timer(0.8, lambda: webbrowser.open(local)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
