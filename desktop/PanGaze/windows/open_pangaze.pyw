"""PanGaze desktop icon (Windows): makes sure PanGaze is running, then opens it
in Firefox, or the default browser if Firefox isn't installed. No console window."""
import os
import subprocess
import sys
import time
import urllib.request
import webbrowser

URL = "http://localhost:8899"
HERE = os.path.dirname(os.path.abspath(__file__))


def up():
    try:
        return b"xp-proxy" in urllib.request.urlopen(URL + "/__xp_ping", timeout=2).read()
    except Exception:
        return False


def message(text):
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, text, "PanGaze", 0x30)
    except Exception:
        pass


if not up():
    server = os.path.join(HERE, "server.py")
    if os.path.exists(server):
        pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
        if not os.path.exists(pyw):
            pyw = sys.executable
        flags = (0x00000008 | 0x00000200 | 0x08000000) if os.name == "nt" else 0   # detached, own group, no window
        subprocess.Popen([pyw, server, "--lan", "--no-open", "--log", os.path.join(HERE, "PanGaze.log")],
                         cwd=HERE, creationflags=flags, close_fds=True)
        for _ in range(40):
            if up():
                break
            time.sleep(0.5)
    if not up():
        message("PanGaze couldn't start.\n\nRun 'Install PanGaze on Windows (background).bat' from the PanGaze folder, "
                "or start it with 'Start PanGaze (Windows).bat'.")
        sys.exit(1)

firefox = None
for base in (os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)"), os.environ.get("LOCALAPPDATA")):
    if base:
        c = os.path.join(base, "Mozilla Firefox", "firefox.exe")
        if os.path.exists(c):
            firefox = c
            break
if firefox:
    subprocess.Popen([firefox, URL])
else:
    webbrowser.open(URL)
