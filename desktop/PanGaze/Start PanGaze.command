#!/bin/bash
# Double-click to start PanGaze. Close this window (or press Ctrl+C) to stop it.
cd "$(dirname "$0")"
# already running in the background (installed with "Install PanGaze (background).command")? just open it
if curl -s -m 2 http://localhost:8899/__xp_ping 2>/dev/null | grep -q xp-proxy; then
  echo "PanGaze is already running. Opening it in your browser…"
  open http://localhost:8899; exit 0
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is needed. Run this in Terminal, then try again:  xcode-select --install"
  read -n 1 -s -r -p "Press any key to close…"; exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1 && [ ! -x /opt/homebrew/bin/ffmpeg ] && [ ! -x /usr/local/bin/ffmpeg ]; then
  echo "Tip: install ffmpeg (brew install ffmpeg) so films with Dolby/DTS sound play with sound in the browser."
fi
python3 server.py --lan "$@"
