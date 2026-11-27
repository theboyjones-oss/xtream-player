PANGAZE: quick start (macOS)
============================
Everything you watch, in one place.


1. Unzip this folder somewhere permanent, e.g. ~/Applications/PanGaze
2. Double-click "Start PanGaze.command"
   - First time only: macOS may block it. Right-click it > Open > Open.
   - If asked to install developer tools (for python3), accept, then run it again.
3. Your browser opens http://localhost:8899. Sign in with your Xtream server, username and password.

NO TERMINAL WINDOW (recommended): double-click "Install PanGaze (background).command" once.
PanGaze then runs quietly in the background and starts by itself when you log in, so you just
open http://localhost:8899. The installer also puts a PanGaze app (with icon) in the Applications folder
in your home folder: it opens PanGaze in Firefox (or your default browser). Drag it onto the Dock to keep it there.
After downloading an update, run the installer again: it copies the new version and keeps your data.
To go back, double-click "Uninstall PanGaze (background).command".

Watch on your phone/tablet: the Terminal window shows a "On your Wi-Fi" address
(e.g. http://192.168.1.20:8899). Open it on any device on the same Wi-Fi.
The first time, the device asks for your PanGaze code (see "THE PANGAZE CODE" below).
Keep the Mac awake and the Terminal window open while watching.

WHY THE SERVER?  Xtream providers don't allow browser pages to fetch from them
directly (CORS). server.py relays the API, EPG and video through your Mac.
It needs only the Python 3 that comes with macOS's developer tools.

Terminal options (instead of the .command file):
  python3 server.py              this Mac only
  python3 server.py --lan        allow other devices on your Wi-Fi
  python3 server.py --port 9000  use a different port
  python3 server.py --ua "IPTVSmartersPro"   if your provider rejects the player
  python3 server.py --insecure   if an https:// provider gives SSL errors
  python3 server.py --allow-host my-mac.tailnet.ts.net   also answer to this address name

THE PANGAZE CODE (keeps other people and websites out)
Each new phone, tablet, TV or browser needs your Mac's PanGaze code once (it looks like ABCD-EFGH).
Find it on the Mac in Settings > Phone & TV (and in the Terminal window). That screen also has a
QR code that connects a phone straight away, and a "New code" button that disconnects every other
device. In the Android app, enter it in Settings > Sync between devices, next to the Mac's address.
Signing in with your PanGaze account also connects a device. The code is kept in xp-lankey.json.
PanGaze also refuses requests started by other websites, and only answers to local addresses
(localhost, 192.168.x.x, names ending .local). Use --allow-host for anything else, e.g. Tailscale.

PANGAZE ON WINDOWS (10 or 11)
=============================
1. Install Python 3 from https://www.python.org/downloads/ . On the first installer screen,
   tick "Add python.exe to PATH". (If you skip this, PanGaze still finds it via the "py" launcher.)
2. Unzip this folder somewhere permanent, e.g. Documents\PanGaze. (Don't run it from inside the zip.)
3. Double-click "Start PanGaze (Windows).bat". A black PanGaze window opens and your browser shows
   http://localhost:8899. Keep that window open while watching (minimise it if you like).
   - "Windows protected your PC": click More info > Run anyway (first time only).
   - Windows Firewall asks about Python: tick Private networks and Allow, so your phone and TV can connect.

NO BLACK WINDOW (recommended): double-click "Install PanGaze on Windows (background).bat" once.
PanGaze then runs hidden, starts by itself when you sign in to Windows, and gets a PanGaze icon on
your Desktop and in the Start menu. After downloading an update, run it again: your data is kept.
To remove it: "Uninstall PanGaze on Windows.bat". A log is kept in %LOCALAPPDATA%\PanGaze\PanGaze.log.

Sound fix on Windows: in PanGaze, Settings > Sound fix > "Download ffmpeg for me". It fetches the
Windows build from gyan.dev (linked from ffmpeg.org) into %LOCALAPPDATA%\PanGaze\bin.
Open in VLC: install VLC from videolan.org and PanGaze opens it for you.
Everything else (accounts, phone remote, recording, Debrid, themes) works the same as on a Mac;
where these notes say "Mac", read "PC".

PANGAZE ACCOUNT (copy everything to a new device)
Settings > PanGaze account > Create an account (on the Mac). On another device, choose "Sign in with
your PanGaze account" and pick what to copy: TV logins, settings, profiles/favourites/history,
Debrid & Trakt, My Media. The account lives on your Mac (xp-pgaccounts.json); passwords are stored
only as a secure hash. Changes on each signed-in device are saved to the account automatically.

FILMS WITH NO SOUND (Dolby / DTS)
Many films (especially from Debrid) use cinema sound that browsers can't play. PanGaze spots the
silence and converts the sound on your Mac as you watch. This needs ffmpeg, set up once:
  Easiest: in PanGaze on the Mac, Settings > Sound fix > "Download ffmpeg for me" (no Terminal).
  It downloads a signed build from ffmpeg.martin-riedl.de into ~/Library/Application Support/PanGaze/bin.
  Or: brew install ffmpeg    (Homebrew: https://brew.sh)

FEATURES
- Live TV: categories, search, favourites, recently watched, now/next on every channel
- EPG: full schedule per channel (day tabs) + grid TV guide (up to 7 days back)
- Catch-up: tap any past programme marked PLAY (channels with the ⟲ icon),
  or "Start over" on what's on now. "Back to live" returns to the live stream.
- Movies and Series: posters, info, seasons/episodes, resume where you left off
- Fallback buttons to open any stream in IINA or VLC
- Keyboard: Up/Down = change channel, F = fullscreen, M = mute, Space = pause, Esc = close

NEW IN THIS VERSION
- Home screen: Continue watching (films/episodes you're part-way through), your channels,
  reminders and recordings.
- Next episode: when an episode ends, the next one plays after a 10-second countdown.
- Reminders: 🔔 next to any future programme (schedule, guide, search). You get a pop-up
  with "Watch" when it starts (and a desktop notification if you allow them).
- Search everything: type in the search box (Enter in Live/Movies/Series) to search channels,
  programmes, films and series at once. "Search more channels' guides" loads more guide data.
- Mini player: keep watching in a small window while you browse. "⧉ Pop out" uses your
  browser's picture-in-picture.
- Multi-view: watch 2 or 4 channels at once; click a screen to hear it. (Needs enough
  connections on your account.)
- Audio & subtitles picker, 😴 sleep timer, ⓘ stream info (or press i).
- Channel numbers: type a number (e.g. 101) to jump to that channel, with keyboard or remote.
- Channel lists: ⋯ next to a channel to hide it or add it to your own groups (e.g. "Sport").
  Settings > Channel lists to reorder favourites, hide categories and unhide channels.
- Parental lock: Settings > Parental lock. Set a PIN and tick categories to lock
  ("Tick all adult categories" does it for you). Kids profiles never show locked content,
  and leaving a kids profile needs the PIN.
- Profiles: the round avatar button. Each person gets their own favourites, history,
  progress, reminders and groups. "Who's watching?" appears when there's more than one.
- Sync between devices: everything above syncs through server.py. Browsers opened from your
  Mac sync automatically. In the Android app, enter your Mac's address and PanGaze code in Settings > Sync.
- Series recording: in the Guide, OK/click a programme > "Record every episode". The Mac
  checks the guide every 15 minutes and schedules new episodes on that channel.
- M3U playlists: on the sign-in screen choose "M3U playlist". Paste the playlist URL and an
  optional XMLTV guide URL (.xml or .xml.gz). Catch-up works when the playlist includes
  catchup / catchup-source information.
- Backup: Settings > Backup to export or restore everything (it includes your logins,
  so keep the file private).

RADIO
The Radio tab lists internet radio stations from the free Radio Browser directory
(radio-browser.info): Popular in the UK, Top worldwide, genres, and search (type in the
search box). Tap ☆ to save a station to your favourites. Favourites appear first in the Radio
tab and on Home, and sync between your devices. A player bar keeps the station playing while you
browse; starting a TV channel stops the radio.

NEW: ALL-IN-ONE FEATURES
- App player (Android / Google TV): films, series, Debrid, recordings and My Media play in Google's
  ExoPlayer inside the app: MKV, 4K, HDR, Dolby Vision, and Dolby / DTS sound passed to your TV or
  soundbar. Settings > Video player (also: use it for live TV too, or the old web player).
  "Skip intro" and "Next episode" buttons appear in it; progress, Trakt and Continue watching all work.
- Backup provider: Settings > Backup provider. If a channel won't play, the same channel is played
  from your second Xtream login automatically. Films that fail offer "Find on Debrid".
- One search for everything: channels, programmes, films, series, Debrid, My Media and podcasts.
- Home > "On now for you" (channels like the ones you watch) and "Trending films".
- Google TV home screen: "Continue watching" from the app appears in the TV's own home screen row.
- Voice search: the 🎤 button (or the remote's search button) on Google TV.
- Trakt: Settings > Trakt. Create a free app at trakt.tv/oauth/applications/new (Redirect URI
  urn:ietf:wg:oauth:2.0:oob), paste its Client ID + Secret, then approve the code on your phone.
  Marks what you watch as watched, and shows your watchlist and "Up next".
- Phone & TV (Settings > Phone & TV, or "Set up with your phone" on the TV's sign-in screen):
  scan the QR code with your phone to get a remote with a keyboard, and to send your login and
  debrid key to the TV. Enter the TV's address + code on your phone/Mac to get "📺 Play on TV".
  On a Mac the player must be started with the Start file (--lan) so phones can reach it.
- Cast: "📡 Cast" sends the video to a Chromecast / Google TV / Nvidia Shield from any browser,
  Firefox included (caster.py does the casting). With live TV, "Keep playing here too" lets the Mac and
  the TV watch together on ONE IPTV connection: the Mac fetches each piece once and shares it.
- My Media tab: Plex (sign in with a code), Jellyfin (server + login) and folders on your Mac
  (added on the Mac in My Media > Sources; your phone and TV can then watch them over Wi-Fi).
- Downloads (Android app): "⬇ Download" under the player saves a film/episode for watching
  offline; find them in My Media > Downloads. In a browser it downloads the file instead.
- Podcasts: Radio > 🎙 Podcasts. Top UK podcasts, search, subscribe, resume where you left off.
- Skip intro: while watching an episode press "⏭ Set skip intro" once per series; choose a button
  or automatic skipping.
- Kids profile: big, simple screens with only children's channels and films (when your provider
  labels them), no Guide / Multi-view / Debrid / Recordings, settings locked.
- Colours: Settings > Colour and Background (black for OLED screens).
- Debrid banner: the featured title's trailer plays quietly behind it (Addons & debrid to turn off).

DEBRID (like Nuvio / Stremio)
The Debrid tab is a film & series hub that uses Stremio addons:
- Cinemeta (added for you) supplies the catalogues, posters, details and episode lists.
  OpenSubtitles (added for you) supplies subtitles.
- Sources come from Stremio addons you add yourself: ⚙︎ Addons & debrid > paste the
  addon's link (…/manifest.json; stremio:// links work too). The app doesn't include any.
- Debrid service: choose Real-Debrid, TorBox or Premiumize and paste your API key
  (Test connection checks it). Torrent-only sources are then turned into a fast stream
  through your account. ⚡ Instant = cached, ready now; ⏳ Not cached = your service has to
  download it first (it's added to your downloads, so try again later).
- ▶ Play picks the best instant source (up to the quality you set), or choose one under
  ☰ Sources. Filters: 4K / 1080p / 720p / ⚡ Instant. ↗ opens a source in VLC / another app.
- Continue watching, ＋ Library, "Next episode" (keeps the same release where it can),
  subtitles from your addons (🔊 Audio & subs > More subtitles).
- Sound: browsers can't play DTS / TrueHD (or Dolby Digital in desktop browsers). Those sources
  are marked ⚠️ — use ↗, or set "Play in" to VLC / Other player for big 4K files.
- Your debrid settings and library sync to your other devices when sync is on, so you can set
  it up on the Mac or phone instead of typing a key with the TV remote.
- Only stream content you have the rights to watch.

MAKING RECORDINGS SMALLER (compression)
Recordings tab > "Make recordings smaller":
  Balanced  H.264, roughly half the size, plays everywhere
  Small     HEVC (H.265), roughly a third of the size
  Smallest  HEVC capped at 720p, roughly a quarter of the size
Needs ffmpeg (Terminal: brew install ffmpeg). Compression runs after each recording, one
at a time in the background, and the original is only replaced if the new file is complete and
smaller. On a Mac you can choose "Fast: use the Mac's video chip" (much quicker, files a bit
bigger). Already-recorded files have a "🗜 Make smaller" button. In a test, a 46 MB recording
came out at 18 MB (Small).

CATCH-UP LIST
On channels with catch-up, a purple "⟲ Catch-up" button sits on the video (and under it).
It opens a list of earlier programmes by day. Pick one to play it, or "Live" to go back.
On a TV remote: press ◀ while watching live full screen.

RECORDING
- ● Record (under the player) records what's on now until the programme ends.
- ● Rec next to any programme in the schedule, or OK on a future programme in the Guide,
  schedules it. ⬇ Save next to a past programme saves it from catch-up.
- Recordings tab: watch, stop, cancel, delete, show in Finder.
- Recordings are saved to Movies/Xtream Recordings on your Mac (changeable in the Recordings tab).
  If ffmpeg is installed (brew install ffmpeg) they are converted to .mp4, otherwise .ts.
- GOOGLE DRIVE: install Google Drive for desktop (google.com/drive/download) and sign in.
  Restart the player, then in the Recordings tab set "Save to: Google Drive". Finished
  recordings go into My Drive/Xtream Recordings and upload automatically.
  There's also a "Copy to Google Drive" button on each recording.
- Scheduled recordings need the Terminal window open and the Mac awake
  (System Settings > Displays/Battery: prevent sleep, or run: caffeinate -i python3 server.py --lan).
- Recording uses one of your provider connections. If your plan allows only 1,
  you can't watch a different channel while it records.

TV MODE (big screen + remote)
Turns on automatically on smart TVs / TV boxes. Otherwise use the "TV mode" button on the
sign-in screen, Settings > TV mode, or open http://<address>:8899/?tv=1
Arrows move · OK selects · Back goes back · OK on the playing channel = full screen
Full screen: Up/Down change channel, Left/Right skip (catch-up & movies), OK pause/info.

IF CATCH-UP PLAYS THE WRONG PROGRAMME OR FAILS
Settings (⚙) > Catch-up: try the other URL style, switch time zone (Server/UTC/device),
or set an offset in hours. The settings show an example of the time being requested.

IF PLAYBACK IS SLOW TO START OR JERKY
- Settings > Live stream format: "Automatic (fastest start)" is the default. It uses MPEG-TS
  for live TV (it starts fastest) and HLS for catch-up (so you can skip around).
- Settings > Buffer: "Large" is smoother on slow or busy Wi-Fi, and starts a little slower.
- Google TV: Settings > Match TV refresh rate keeps UK 50 Hz programmes judder-free. The screen
  goes black for a second when it switches, like other TV apps.
- Live TV reconnects by itself if your provider drops the stream.
- Press i (or the "Stream info" button) to see resolution, frame rate, buffer, dropped frames
  and how long the channel took to start.
- If you watch on a TV or phone through the Mac, a wired (Ethernet) connection for the Mac or
  TV helps a lot.

IF A LIVE CHANNEL WON'T PLAY
Settings > Live stream format: switch HLS <-> MPEG-TS. The player also tries the
other format automatically once. MKV/AVI movies may need IINA or VLC.

ANDROID PHONE WITHOUT THE MAC (no app build needed)
1. Install "Termux" from F-Droid (f-droid.org); the Play Store version is outdated.
2. Download xtream-player.zip on the phone, then in Termux run:
     termux-setup-storage
     pkg install -y python unzip
     unzip -o ~/storage/downloads/xtream-player.zip -d ~
     python ~/xtream-player/server.py --no-open
3. In Chrome open http://localhost:8899, then menu > Add to Home screen.
Next time: open Termux, run the last command, then tap the home-screen icon.
(The proper Android app is in PanGaze-Android.zip.)
