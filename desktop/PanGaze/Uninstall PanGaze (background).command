#!/bin/bash
# Double-click to stop PanGaze running in the background and stop it starting at login.
# Your data stays in ~/Library/Application Support/PanGaze in case you reinstall.
LABEL="org.pangaze.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || launchctl unload -w "$PLIST" >/dev/null 2>&1
rm -f "$PLIST"
rm -rf "$HOME/Applications/PanGaze.app"
echo ""
echo "  ✅ PanGaze no longer runs in the background."
echo "     You can still start it by hand with \"Start PanGaze.command\"."
echo "     Your saved data is kept in: ~/Library/Application Support/PanGaze"
echo ""
read -n 1 -s -r -p "Press any key to close this window…"; echo
