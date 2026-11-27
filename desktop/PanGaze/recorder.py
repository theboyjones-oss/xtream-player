"""
PanGaze: recorder (used by server.py).

Records live channels, scheduled programmes and catch-up programmes to disk.
Finished recordings can be remuxed to MP4 (if ffmpeg is installed) and copied into
your Google Drive folder (Google Drive for desktop), which then syncs them to Drive.
"""
import base64
import glob
import queue
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.join(HERE, "xp-recordings.json")
CHUNK = 64 * 1024
DRIVE_SUBFOLDER = "Xtream Recordings"


def platform_name():
    if "com.termux" in os.environ.get("PREFIX", ""):
        return "termux"
    if sys.platform == "darwin":
        return "mac"
    if sys.platform.startswith("win"):
        return "windows"
    return "linux"


def default_dir():
    home = os.path.expanduser("~")
    for cand in (os.path.join(home, "Movies"), os.path.join(home, "storage", "movies"), os.path.join(home, "Videos")):
        if os.path.isdir(cand):
            return os.path.join(cand, "Xtream Recordings")
    return os.path.join(HERE, "recordings")


def google_drive_root():
    """Finds the 'My Drive' folder created by Google Drive for desktop (macOS / Windows)."""
    cands = []
    cs = os.path.expanduser("~/Library/CloudStorage")
    for acct in sorted(glob.glob(os.path.join(cs, "GoogleDrive-*"))):
        names = ["My Drive", "Mijn Drive", "Meine Ablage", "Mon Drive", "Mi unidad", "Il mio Drive", "Meu Drive"]
        cands += [os.path.join(acct, n) for n in names]
        try:
            cands += [os.path.join(acct, n) for n in os.listdir(acct)
                      if not n.startswith(".") and n not in ("Shared drives", "Other computers", ".shortcut-targets-by-id")]
        except OSError:
            pass
    cands += ["/Volumes/GoogleDrive/My Drive", os.path.expanduser("~/Google Drive/My Drive"),
              os.path.expanduser("~/Google Drive"), "G:\\My Drive"]
    for c in cands:
        if os.path.isdir(c):
            return c
    return None


# On Windows, run ffmpeg without popping up a console window (PanGaze may be running in the background)
NOWIN = {"creationflags": 0x08000000} if sys.platform.startswith("win") else {}


def ffmpeg_home():
    """Where PanGaze keeps its own copy of ffmpeg (downloaded from inside the app)."""
    if sys.platform == "darwin":
        return os.path.join(os.path.expanduser("~"), "Library", "Application Support", "PanGaze", "bin")
    if sys.platform.startswith("win"):
        return os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "PanGaze", "bin")
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin")


def find_ffmpeg():
    own = os.path.join(ffmpeg_home(), "ffmpeg.exe" if sys.platform.startswith("win") else "ffmpeg")
    if os.path.isfile(own) and os.access(own, os.X_OK):
        return own
    p = shutil.which("ffmpeg")
    if p:
        return p
    for c in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/data/data/com.termux/files/usr/bin/ffmpeg"):
        if os.path.exists(c):
            return c
    return None


LEVELS = ("balanced", "small", "smallest")
_HW = None


def hw_encoders():
    """Hardware video encoders ffmpeg can use on this computer (Apple VideoToolbox)."""
    global _HW
    if _HW is None:
        _HW = {"available": False, "h264": False, "hevc": False}
        ff = find_ffmpeg()
        if ff and sys.platform == "darwin":
            try:
                out = subprocess.run([ff, "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=20, **NOWIN).stdout
                _HW["h264"] = "h264_videotoolbox" in out
                _HW["hevc"] = "hevc_videotoolbox" in out
                _HW["available"] = _HW["h264"] or _HW["hevc"]
            except Exception:
                pass
    return _HW


def find_ffprobe():
    ff = find_ffmpeg()
    if ff:
        cand = os.path.join(os.path.dirname(ff), "ffprobe")
        if os.path.exists(cand):
            return cand
    return shutil.which("ffprobe")


def probe_duration(path):
    fp = find_ffprobe()
    if not fp:
        return None
    try:
        out = subprocess.run([fp, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=60, **NOWIN).stdout.strip()
        d = float(out)
        return d if d > 0 else None
    except Exception:
        return None


def encode_args(level, use_hw, src_bytes, dur):
    """ffmpeg options for each size level. Software encoders give the best size for the quality;
    the Mac's hardware encoder is much faster but needs a bitrate target."""
    deint = "yadif=deint=interlaced"
    vf = deint + (",scale=-2:min(720\\,ih)" if level == "smallest" else "")
    audio = {"balanced": ["-c:a", "aac", "-b:a", "128k"], "small": ["-c:a", "aac", "-b:a", "112k"],
             "smallest": ["-c:a", "aac", "-b:a", "96k", "-ac", "2"]}[level]
    maps = ["-map", "0:v:0", "-map", "0:a?", "-sn", "-dn"]
    hw = hw_encoders() if use_hw else {"available": False}
    if hw.get("available"):
        src_kbps = max(500, src_bytes * 8 / max(1, dur) / 1000 - 160)
        factor = {"balanced": 0.55, "small": 0.4, "smallest": 0.28}[level]
        kbps = int(max(600, src_kbps * factor))
        codec = "hevc_videotoolbox" if level != "balanced" and hw.get("hevc") else "h264_videotoolbox"
        v = ["-c:v", codec, "-b:v", "%dk" % kbps, "-maxrate", "%dk" % int(kbps * 1.5), "-bufsize", "%dk" % (kbps * 2), "-allow_sw", "1"]
        if codec.startswith("hevc"):
            v += ["-tag:v", "hvc1"]
    elif level == "balanced":
        v = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23"]
    else:
        v = ["-c:v", "libx265", "-preset", "veryfast", "-crf", "26" if level == "small" else "28", "-tag:v", "hvc1",
             "-x265-params", "log-level=error"]
    return maps + ["-vf", vf, "-pix_fmt", "yuv420p"] + v + audio


def safe_name(s):
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " ", s or "").strip()
    return re.sub(r"\s+", " ", s)[:120] or "Recording"


class Recorder:
    def __init__(self, user_agent, ssl_ctx, timeout=20):
        self.ua = user_agent
        self.ssl = ssl_ctx
        self.timeout = timeout
        self.lock = threading.RLock()
        self.stops = {}  # id -> threading.Event
        self.cfg = {"dir": "", "mode": "local", "convert": True, "compress": "off", "hw": False, "keep_original": False}
        self.cq = queue.Queue()
        self._cproc = {}  # id -> running ffmpeg process
        self.items = []
        self.rules = []
        self._rules_checked = 0
        self._load()
        # recordings that were running when the server stopped
        for it in self.items:
            if it["status"] in ("recording", "processing"):
                it["status"] = "stopped" if it.get("bytes") else "failed"
                it.setdefault("error", "Server was stopped during the recording")
        for it in self.items:  # compressions interrupted by a restart start again
            c = it.get("compress")
            if c and c.get("state") in ("queued", "running"):
                c["state"] = "queued"
                c["progress"] = 0
                self.cq.put(it["id"])
        threading.Thread(target=self._scheduler, daemon=True).start()
        threading.Thread(target=self._compress_worker, daemon=True).start()

    # ---------------- persistence ----------------
    def _load(self):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                d = json.load(f)
            self.cfg.update(d.get("config", {}))
            self.items = d.get("items", [])
            self.rules = d.get("rules", [])
        except Exception:
            pass

    def _save(self):
        with self.lock:
            data = json.loads(json.dumps({"config": self.cfg, "items": self.items, "rules": self.rules}))
        tmp = "%s.%d.tmp" % (STATE_FILE, threading.get_ident())
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=1)
            os.replace(tmp, STATE_FILE)
        except Exception as e:
            sys.stderr.write("could not save recordings state: %s\n" % e)

    # ---------------- config / status ----------------
    def rec_dir(self):
        d = self.cfg.get("dir") or default_dir()
        os.makedirs(d, exist_ok=True)
        return d

    def status(self):
        drive = google_drive_root()
        with self.lock:
            items = [dict(i) for i in self.items]
        for i in items:  # live size for running recordings
            if i["status"] == "recording" and i.get("path") and os.path.exists(i["path"]):
                i["bytes"] = os.path.getsize(i["path"])
            i.pop("url", None)
            i.pop("alt", None)
        return {
            "ok": True, "platform": platform_name(), "now": time.time(),
            "dir": self.cfg.get("dir") or default_dir(),
            "mode": self.cfg.get("mode", "local"), "convert": bool(self.cfg.get("convert", True)),
            "ffmpeg": bool(find_ffmpeg()),
            "compress": self.cfg.get("compress", "off"), "hw": bool(self.cfg.get("hw")), "keep_original": bool(self.cfg.get("keep_original")),
            "compressor": {"available": bool(find_ffmpeg()), "engine": "ffmpeg", "hw": hw_encoders()["available"]},
            "drive": {"available": bool(drive), "path": os.path.join(drive, DRIVE_SUBFOLDER) if drive else None},
            "items": items,
            "rules": [{k: v for k, v in r.items() if k in ("id", "title", "channel", "stream_id", "created", "last_check", "error")} for r in self.rules],
        }

    def configure(self, q):
        with self.lock:
            if "dir" in q:
                d = os.path.expanduser(q["dir"].strip())
                if d:
                    os.makedirs(d, exist_ok=True)
                self.cfg["dir"] = d
            if q.get("mode") in ("local", "gdrive"):
                self.cfg["mode"] = q["mode"]
            if "convert" in q:
                self.cfg["convert"] = q["convert"] in ("1", "true", "yes")
            if q.get("compress") in LEVELS or q.get("compress") == "off":
                self.cfg["compress"] = q["compress"]
            if "hw" in q:
                self.cfg["hw"] = q["hw"] in ("1", "true", "yes")
            if "keep_original" in q:
                self.cfg["keep_original"] = q["keep_original"] in ("1", "true", "yes")
        self._save()
        return self.status()

    # ---------------- items ----------------
    def add(self, data):
        now = time.time()
        it = {
            "id": uuid.uuid4().hex[:12],
            "title": str(data.get("title") or "Recording")[:200],
            "channel": str(data.get("channel") or "")[:120],
            "stream_id": data.get("stream_id"),
            "prog_start": data.get("prog_start"),
            "kind": data.get("kind") if data.get("kind") in ("live", "catchup") else "live",
            "url": data["url"], "alt": data.get("alt"),
            "start": float(data.get("start") or now),
            "stop": float(data["stop"]) if data.get("stop") else None,
            "duration": data.get("duration"),
            "status": "scheduled", "bytes": 0, "created": now,
        }
        if not str(it["url"]).startswith(("http://", "https://")):
            raise ValueError("bad url")
        if it["kind"] == "live" and (not it["stop"] or it["stop"] <= now):
            raise ValueError("This programme has already finished")
        with self.lock:
            self.items.append(it)
        self._save()
        return it

    def find(self, rid):
        with self.lock:
            return next((i for i in self.items if i["id"] == rid), None)

    def stop(self, rid):
        it = self.find(rid)
        if not it:
            return False
        if it["status"] == "scheduled":
            with self.lock:
                it["status"] = "cancelled"
                self.items.remove(it)
                for r in self.rules:
                    if r["id"] == it.get("rule"):
                        r.setdefault("skip", []).append(it.get("prog_start"))
            self._save()
            return True
        ev = self.stops.get(rid)
        if ev:
            ev.set()
        return True

    def delete(self, rid):
        it = self.find(rid)
        if not it:
            return False
        if it["status"] in ("recording", "processing"):
            self.stop(rid)
            time.sleep(0.5)
        self.cancel_compress(rid)
        p = it.get("path")
        if p and os.path.isfile(p) and self._is_ours(p):
            try:
                os.remove(p)
            except OSError:
                pass
        with self.lock:
            if it in self.items:
                self.items.remove(it)
        self._save()
        return True

    def _is_ours(self, p):
        p = os.path.realpath(p)
        roots = [os.path.realpath(self.rec_dir())]
        drive = google_drive_root()
        if drive:
            roots.append(os.path.realpath(os.path.join(drive, DRIVE_SUBFOLDER)))
        return any(p.startswith(r + os.sep) for r in roots)

    def file_for(self, rid):
        it = self.find(rid)
        p = it and it.get("path")
        return p if p and os.path.isfile(p) and self._is_ours(p) else None

    def reveal(self, rid):
        p = self.file_for(rid)
        if not p:
            return False
        if sys.platform == "darwin":
            subprocess.Popen(["open", "-R", p])
        elif sys.platform.startswith("win"):
            subprocess.Popen(["explorer", "/select,", p])
        else:
            subprocess.Popen(["xdg-open", os.path.dirname(p)])
        return True

    def copy_to_drive(self, rid):
        it = self.find(rid)
        p = self.file_for(rid)
        drive = google_drive_root()
        if not (it and p and drive):
            raise ValueError("Google Drive folder not found. Install Google Drive for desktop and sign in.")
        dest_dir = os.path.join(drive, DRIVE_SUBFOLDER)
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, os.path.basename(p))
        shutil.copy2(p, dest)
        with self.lock:
            it["drive"] = dest
        self._save()
        return dest

    # ---------------- series recording rules ----------------
    def add_rule(self, data):
        for k in ("title", "epg_url", "ts_url"):
            if not data.get(k):
                raise ValueError("missing " + k)
        r = {"id": uuid.uuid4().hex[:12], "title": str(data["title"])[:200], "channel": str(data.get("channel", ""))[:120],
             "stream_id": data.get("stream_id"), "epg_url": data["epg_url"], "ts_url": data["ts_url"],
             "m3u8_url": data.get("m3u8_url"), "pre": float(data.get("pre", 60)), "post": float(data.get("post", 180)),
             "created": time.time(), "skip": []}
        with self.lock:
            self.rules.append(r)
        self._save()
        threading.Thread(target=self._check_rule, args=(r,), daemon=True).start()
        return r

    def del_rule(self, rid):
        with self.lock:
            r = next((x for x in self.rules if x["id"] == rid), None)
            if not r:
                return False
            self.rules.remove(r)
            for it in [i for i in self.items if i.get("rule") == rid and i["status"] == "scheduled"]:
                self.items.remove(it)
        self._save()
        return True

    @staticmethod
    def _b64(s):
        if not s:
            return ""
        try:
            out = base64.b64decode(s, validate=True).decode("utf-8")
            return out if out.isprintable() or "\n" in out else s
        except Exception:
            return s

    def _check_rule(self, r):
        try:
            resp = self._open(r["epg_url"])
            data = json.loads(resp.read().decode("utf-8", "replace"))
            resp.close()
            now = time.time()
            want = r["title"].strip().lower()
            added = 0
            for e in (data or {}).get("epg_listings", []) or []:
                title = self._b64(e.get("title"))
                try:
                    start = float(e.get("start_timestamp"))
                    stop = float(e.get("stop_timestamp") or e.get("end_timestamp"))
                except (TypeError, ValueError):
                    continue
                if stop <= now or start > now + 8 * 86400 or title.strip().lower() != want:
                    continue
                with self.lock:
                    if start in r.get("skip", []) or any(
                            str(i.get("stream_id")) == str(r.get("stream_id")) and i.get("prog_start") == start for i in self.items):
                        continue
                new = self.add({"title": title, "channel": r["channel"], "stream_id": r.get("stream_id"), "prog_start": start,
                          "kind": "live", "url": r["ts_url"], "alt": r.get("m3u8_url"),
                          "start": max(now, start - r.get("pre", 60)), "stop": stop + r.get("post", 180)})
                with self.lock:
                    new["rule"] = r["id"]
                added += 1
            with self.lock:
                r["last_check"] = now
                r.pop("error", None)
            if added:
                self._save()
        except Exception as e:
            with self.lock:
                r["error"] = "Could not read the guide: %s" % e
                r["last_check"] = time.time()

    # ---------------- scheduler ----------------
    def _scheduler(self):
        while True:
            now = time.time()
            if now - self._rules_checked > 900:
                self._rules_checked = now
                for r in list(self.rules):
                    self._check_rule(r)
                self._save()
            with self.lock:
                due = [i for i in self.items if i["status"] == "scheduled" and i["start"] <= now]
            for it in due:
                if it["kind"] == "live" and it["stop"] and it["stop"] <= now:
                    with self.lock:
                        it["status"] = "missed"
                    self._save()
                    continue
                with self.lock:
                    it["status"] = "recording"
                    it["started"] = now
                ev = threading.Event()
                self.stops[it["id"]] = ev
                threading.Thread(target=self._run, args=(it, ev), daemon=True).start()
            if due:
                self._save()
            time.sleep(2)

    def _run(self, it, ev):
        t0 = time.localtime(float(it.get("prog_start") or it["start"]))
        base = "%s %s - %s" % (time.strftime("%Y-%m-%d %H%M", t0), safe_name(it["channel"]), safe_name(it["title"]))
        d = self.rec_dir()
        path = os.path.join(d, base + ".ts")
        n = 2
        while os.path.exists(path):
            path = os.path.join(d, "%s (%d).ts" % (base, n))
            n += 1
        with self.lock:
            it["path"] = path
            it["file"] = os.path.basename(path)
        self._save()
        err = None
        try:
            with open(path, "ab") as f:
                self._record(it, f, ev)
        except Exception as e:
            err = str(e)
        size = os.path.getsize(path) if os.path.exists(path) else 0
        with self.lock:
            it["bytes"] = size
            it["ended"] = time.time()
        if size < 50 * 1024:
            with self.lock:
                it["status"] = "failed"
                it["error"] = err or "No video was received (channel offline or connection limit reached?)"
            if os.path.exists(path) and size == 0:
                os.remove(path)
            self._save()
            self.stops.pop(it["id"], None)
            return
        with self.lock:
            it["status"] = "processing"
            if err:
                it["error"] = err
        self._save()
        self._finish(it)
        self.stops.pop(it["id"], None)

    def _finish(self, it):
        path = it["path"]
        ff = find_ffmpeg()
        if ff and self.cfg.get("convert", True):
            mp4 = os.path.splitext(path)[0] + ".mp4"
            try:
                r = subprocess.run([ff, "-y", "-loglevel", "error", "-fflags", "+genpts", "-i", path,
                                    "-map", "0:v:0?", "-map", "0:a?", "-c", "copy", "-movflags", "+faststart", mp4],
                                   capture_output=True, timeout=3600, **NOWIN)
                if r.returncode == 0 and os.path.getsize(mp4) > 0.5 * os.path.getsize(path):
                    os.remove(path)
                    path = mp4
                elif os.path.exists(mp4):
                    os.remove(mp4)
            except Exception:
                pass
        level = self.cfg.get("compress", "off")
        will_compress = level in LEVELS and ff
        if not will_compress:
            path = self._to_drive(it, path)
        with self.lock:
            it["path"] = path
            it["file"] = os.path.basename(path)
            it["bytes"] = os.path.getsize(path)
            if it["status"] == "processing":
                it["status"] = "stopped" if it.get("user_stopped") else "done"
            if will_compress:
                it["compress"] = {"state": "queued", "level": level, "progress": 0}
        self._save()
        if will_compress:
            self.cq.put(it["id"])

    def _to_drive(self, it, path):
        if self.cfg.get("mode") != "gdrive" or it.get("drive"):
            return path
        drive = google_drive_root()
        if not drive:
            it["error"] = "Google Drive folder not found, so the recording was kept on this computer"
            return path
        try:
            dest_dir = os.path.join(drive, DRIVE_SUBFOLDER)
            os.makedirs(dest_dir, exist_ok=True)
            dest = os.path.join(dest_dir, os.path.basename(path))
            shutil.move(path, dest)
            it["drive"] = dest
            return dest
        except Exception as e:
            it["error"] = "Could not move to Google Drive: %s" % e
            return path

    # ---------------- compression ----------------
    def compress_now(self, rid, level):
        it = self.find(rid)
        if not it or not self.file_for(rid):
            raise ValueError("Recording not found")
        if not find_ffmpeg():
            raise ValueError("Compression needs ffmpeg (Terminal: brew install ffmpeg)")
        if level not in LEVELS:
            level = self.cfg.get("compress") if self.cfg.get("compress") in LEVELS else "small"
        c = it.get("compress") or {}
        if c.get("state") in ("queued", "running"):
            return it
        with self.lock:
            it["compress"] = {"state": "queued", "level": level, "progress": 0, "manual": True}
        self._save()
        self.cq.put(rid)
        return it

    def cancel_compress(self, rid):
        it = self.find(rid)
        if not it or not it.get("compress"):
            return False
        with self.lock:
            if it["compress"].get("state") in ("queued", "running"):
                it["compress"]["state"] = "cancelled"
        p = self._cproc.get(rid)
        if p:
            try:
                p.kill()
            except Exception:
                pass
        self._save()
        return True

    def _compress_worker(self):
        while True:
            rid = self.cq.get()
            it = self.find(rid)
            if not it or (it.get("compress") or {}).get("state") != "queued":
                continue
            try:
                self._compress(it)
            except Exception as e:
                with self.lock:
                    it["compress"].update(state="failed", error=str(e))
            if not it.get("compress", {}).get("manual"):
                with self.lock:
                    if it.get("path") and os.path.exists(it["path"]):
                        it["path"] = self._to_drive(it, it["path"])
                        it["file"] = os.path.basename(it["path"])
            self._save()

    def _compress(self, it):
        ff, src = find_ffmpeg(), it.get("path")
        c = it["compress"]
        if not (ff and src and os.path.isfile(src)):
            c.update(state="failed", error="File or ffmpeg missing")
            return
        level = c.get("level", "small")
        before = os.path.getsize(src)
        dur = probe_duration(src) or max(1.0, (it.get("ended") or 0) - (it.get("started") or it.get("start") or 0))
        base = os.path.splitext(src)[0]
        tmp = base + ".compressing.mp4"
        args = encode_args(level, self.cfg.get("hw"), before, dur)
        cmd = [ff, "-y", "-hide_banner", "-nostdin", "-loglevel", "error", "-fflags", "+genpts", "-i", src] + args + \
              ["-movflags", "+faststart", "-progress", "pipe:1", "-nostats", tmp]
        with self.lock:
            c.update(state="running", progress=0, before=before, started=time.time())
        self._save()
        kw = dict(NOWIN)
        if os.name == "posix":
            kw["preexec_fn"] = lambda: os.nice(10)  # keep the computer responsive
        elif sys.platform.startswith("win"):
            kw["creationflags"] = 0x08000000 | 0x00004000   # no window, below-normal priority
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **kw)
        self._cproc[it["id"]] = proc
        last = 0
        for line in proc.stdout:
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                try:
                    t = int(line.split("=", 1)[1]) / 1e6
                    with self.lock:
                        c["progress"] = round(min(0.99, max(0.0, t / dur)), 3)
                    if time.time() - last > 10:
                        self._save()
                        last = time.time()
                except ValueError:
                    pass
        err = proc.stderr.read()[-400:]
        rc = proc.wait()
        self._cproc.pop(it["id"], None)
        if c.get("state") == "cancelled":
            if os.path.exists(tmp):
                os.remove(tmp)
            return
        ok = rc == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 1024
        if ok:
            new_dur = probe_duration(tmp)
            if new_dur and dur and new_dur < 0.9 * dur:
                ok, why = False, "the compressed file came out shorter than the original"
            elif os.path.getsize(tmp) >= before * 0.97:
                ok, why = False, "already compact, so the original was kept"
        else:
            why = (err.strip().splitlines() or ["ffmpeg failed"])[-1]
        if not ok:
            if os.path.exists(tmp):
                os.remove(tmp)
            with self.lock:
                c.update(state="skipped" if "compact" in why else "failed", error=why, progress=0)
            return
        final = base + ".mp4"
        if self.cfg.get("keep_original"):
            if os.path.abspath(final) == os.path.abspath(src):
                final = base + " (small).mp4"
        else:
            os.remove(src)
        os.replace(tmp, final)
        after = os.path.getsize(final)
        with self.lock:
            it["path"] = final
            it["file"] = os.path.basename(final)
            it["bytes"] = after
            c.update(state="done", progress=1, after=after, finished=time.time())
            c.pop("error", None)

    # ---------------- recording ----------------
    def _open(self, url):
        req = urllib.request.Request(url, headers={"User-Agent": self.ua, "Accept": "*/*", "Connection": "close"})
        return urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl)

    def _record(self, it, f, ev):
        live = it["kind"] == "live"
        until = (it["stop"] or 0) if live else None
        errors = 0
        use_hls = False
        urls = [it["url"]] + ([it["alt"]] if it.get("alt") else [])
        while not ev.is_set():
            if live and time.time() >= until:
                break
            url = urls[1] if (use_hls and len(urls) > 1) else urls[0]
            try:
                done = self._fetch(url, f, it, ev, until)
                if done or not live:
                    break
            except _NotTs:
                if len(urls) > 1 and not use_hls:
                    use_hls = True
                    continue
                raise RuntimeError("The provider did not send a video stream")
            except urllib.error.HTTPError as e:
                errors += 1
                if errors == 1 and len(urls) > 1 and not use_hls:
                    use_hls = True
                    continue
                if errors > 30 or (not live and errors > 5):
                    raise RuntimeError("Provider returned HTTP %s" % e.code)
            except Exception:
                errors += 1
                if errors > 30 or (not live and errors > 5):
                    raise
            if not ev.is_set():
                ev.wait(3)  # reconnect after a drop
        if ev.is_set():
            with self.lock:
                it["user_stopped"] = True

    def _fetch(self, url, f, it, ev, until):
        """Returns True when the recording is complete."""
        resp = self._open(url)
        try:
            first = resp.read(CHUNK)
            head = first.lstrip()[:7]
            if head == b"#EXTM3U":
                return self._hls(resp.geturl(), first + resp.read(), f, it, ev, until)
            if not first or first[0] != 0x47:
                raise _NotTs()
            f.write(first)
            last_save = time.time()
            while not ev.is_set():
                if until and time.time() >= until:
                    return True
                chunk = resp.read(CHUNK)
                if not chunk:
                    return until is None  # live stream ended early -> reconnect
                f.write(chunk)
                if time.time() - last_save > 5:
                    f.flush()
                    with self.lock:
                        it["bytes"] = f.tell()
                    last_save = time.time()
            return True
        finally:
            resp.close()

    def _hls(self, url, body, f, it, ev, until):
        seen = []
        seen_set = set()
        while not ev.is_set():
            text = body.decode("utf-8", "replace") if body is not None else None
            if text is None:
                r = self._open(url)
                url = r.geturl()
                text = r.read().decode("utf-8", "replace")
                r.close()
            body = None
            lines = [l.strip() for l in text.splitlines() if l.strip()]
            if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):  # master playlist: take the first variant
                for i, l in enumerate(lines):
                    if l.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines):
                        url = urllib.parse.urljoin(url, lines[i + 1])
                        break
                continue
            if any(l.startswith("#EXT-X-KEY") and "METHOD=NONE" not in l for l in lines):
                raise RuntimeError("This stream is encrypted and can't be recorded")
            target = 6.0
            for l in lines:
                if l.startswith("#EXT-X-TARGETDURATION:"):
                    try:
                        target = float(l.split(":", 1)[1])
                    except ValueError:
                        pass
            segs = [urllib.parse.urljoin(url, l) for l in lines if not l.startswith("#")]
            for s in segs:
                if ev.is_set() or (until and time.time() >= until):
                    return True
                if s in seen_set:
                    continue
                r = self._open(s)
                try:
                    while True:
                        c = r.read(CHUNK)
                        if not c:
                            break
                        f.write(c)
                finally:
                    r.close()
                seen.append(s)
                seen_set.add(s)
                if len(seen) > 3000:
                    seen_set.discard(seen.pop(0))
                with self.lock:
                    it["bytes"] = f.tell()
            if "#EXT-X-ENDLIST" in lines:
                return True
            if until and time.time() >= until:
                return True
            ev.wait(max(1.0, target / 2))
        return True


class _NotTs(Exception):
    pass
