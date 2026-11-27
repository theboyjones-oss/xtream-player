#!/bin/bash
# Double-click to make PanGaze run quietly in the background on this Mac.
# It starts by itself when you log in, with no Terminal window, so you can
# just open http://localhost:8899 in your browser (or the PanGaze Dock icon).
# Run it again after downloading an update: it copies the new version and keeps your data.

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Library/Application Support/PanGaze"
LABEL="org.pangaze.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/PanGaze.log"
URL="http://localhost:8899"

pause() { read -n 1 -s -r -p "Press any key to close this window…"; echo; }
ping_ok() { curl -s -m 2 "$URL/__xp_ping" 2>/dev/null | grep -q xp-proxy; }

echo ""
echo "  Installing PanGaze to run in the background…"
echo ""

# 1. Python 3 (comes with Apple's free developer tools)
PY="$(command -v python3)"
if [ -z "$PY" ] || ! "$PY" -c 'import sys' >/dev/null 2>&1; then
  echo "  Python 3 is needed first. A window may have opened offering to install"
  echo "  Apple's developer tools: click Install, wait for it to finish, then"
  echo "  double-click this file again."
  pause; exit 1
fi

# 2. Stop an older background copy, then make sure nothing else is using the port
launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1
sleep 1
if ping_ok; then
  echo "  PanGaze is already running in another Terminal window."
  echo "  Close that window (or press Ctrl+C in it), then press any key here to carry on."
  pause
  if ping_ok; then echo "  It's still running. Close it and run this installer again."; pause; exit 1; fi
fi

# 3. Copy the app to a place macOS lets background programs read
mkdir -p "$DEST" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
for f in xtream-player.html server.py recorder.py caster.py help.html remote.html README.txt; do
  [ -f "$SRC/$f" ] && cp -f "$SRC/$f" "$DEST/$f"
done
# your saved data (sync, My Media folders, recordings list) moves across the first time only
for f in "$SRC"/xp-*.json; do
  [ -e "$f" ] || continue
  [ -e "$DEST/$(basename "$f")" ] || cp -f "$f" "$DEST/"
done

# 3b. The PanGaze app icon (opens PanGaze in Firefox, or your default browser) goes in Applications
if [ -d "$SRC/PanGaze.app" ]; then
  mkdir -p "$HOME/Applications"
  rm -rf "$HOME/Applications/PanGaze.app"
  cp -R "$SRC/PanGaze.app" "$HOME/Applications/PanGaze.app"
  xattr -dr com.apple.quarantine "$HOME/Applications/PanGaze.app" >/dev/null 2>&1
  touch "$HOME/Applications/PanGaze.app"
fi

# 4. Tell macOS to start it at login and keep it running
"$PY" - "$PLIST" "$PY" "$DEST" "$LOG" "$LABEL" <<'EOF'
import plistlib, sys
plist, py, dest, log, label = sys.argv[1:6]
with open(plist, "wb") as f:
    plistlib.dump({
        "Label": label,
        "ProgramArguments": [py, dest + "/server.py", "--lan", "--no-open"],
        "WorkingDirectory": dest,
        "RunAtLoad": True,
        "KeepAlive": True,
        "ProcessType": "Interactive",
        "StandardOutPath": log,
        "StandardErrorPath": log,
        "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
    }, f)
EOF
launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null || launchctl load -w "$PLIST" 2>/dev/null

# 5. Wait for it to come up, then open it
for i in $(seq 1 20); do ping_ok && break; sleep 0.5; done
if ping_ok; then
  echo "  ✅ Done. PanGaze is now running in the background and will start"
  echo "     by itself whenever you log in."
  echo ""
  echo "  Open it any time at:  $URL"
  if [ -d "$HOME/Applications/PanGaze.app" ]; then
    echo ""
    echo "  📌 A PanGaze app is now in your Applications folder (the one in your"
    echo "     home folder). It opens PanGaze in Firefox, or your default browser"
    echo "     if Firefox isn't installed. A Finder window is showing it now:"
    echo "     drag the PanGaze icon onto your Dock to keep it there."
    open -R "$HOME/Applications/PanGaze.app" >/dev/null 2>&1
  fi
  echo ""
  echo "  You can close this window. To remove the background version later,"
  echo "  double-click \"Uninstall PanGaze (background).command\"."
  open "$URL"
else
  echo "  ⚠️  PanGaze didn't start. The log may say why:"
  echo "     $LOG"
  tail -n 15 "$LOG" 2>/dev/null
fi
echo ""
pause
