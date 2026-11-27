# PanGaze for Android

> **This repo holds both versions of PanGaze.** The Android / Google TV app is at the top level (GitHub builds `PanGaze.apk` from it on every push: [download the latest](https://github.com/theboyjones-oss/xtream-player/releases/download/latest/PanGaze.apk)). The Mac / Windows version (Firefox + `server.py`) is in [`desktop/PanGaze`](desktop/PanGaze).


_Everything you watch, in one place._

The same player as the Mac version, built as a real Android app. The CORS proxy is
built into the app, so there's no server to run: install it, sign in, watch.

Live TV · full EPG + TV guide · catch-up / start over · movies & series · favourites ·
resume · fullscreen landscape · "Other player" hands any stream to VLC / MX Player.

## Get the APK: easiest way (GitHub builds it for you, ~5 min, no software needed)

1. Sign in at github.com (free account) and create a **new repository**, e.g. `xtream-player`.
   Make it **Private** if you prefer.
2. On the empty repo page click **"uploading an existing file"**, drag in **everything inside
   this folder** (including the hidden `.github` folder, see note below) and click **Commit changes**.
3. Open the **Actions** tab. The "Build APK" job starts by itself (green tick in ~3–4 min).
4. Open the repo's **Releases** (right-hand side) → **PanGaze (latest build)** →
   tap **PanGaze.apk** on your phone to download it.

> Mac Finder hides `.github`. Press **Cmd + Shift + .** in Finder to show it before dragging,
> or upload `.github/workflows/build-apk.yml` separately via **Add file → Create new file**
> (type the path `.github/workflows/build-apk.yml` and paste its contents).

Every time you change a file in the repo, a fresh APK is built automatically.

## Or build it on your Mac

Install **Android Studio**, choose **Open**, pick this folder, wait for it to sync, then
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK lands in
`app/build/outputs/apk/`. (Terminal alternative: `./gradlew assembleRelease`.)

## Install on the phone

1. Tap the downloaded **PanGaze.apk**.
2. Android will ask to allow installs from your browser/Files app: **Settings → Allow from this source**.
3. Tap **Install**. You may see a Play Protect warning because the app isn't from the Play Store;
   choose **More details → Install anyway**.

## Install on a TV (Android TV, Google TV, Fire TV Stick)

The same APK works on TVs. It opens in **TV mode** automatically: big text, and you drive it
with the remote.

1. On the TV, install the free **Downloader** app (by AFTVnews) from the TV's app store.
2. Fire TV: **Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On**.
   Android/Google TV: **Settings → Apps → Security → Unknown sources → Downloader → On**.
   (If Developer options is missing on Fire TV: Settings → My Fire TV → About → click the device name 7 times.)
3. In Downloader, type the address of your APK, e.g.
   `https://github.com/<your-name>/xtream-player/releases/latest/download/PanGaze.apk`
   (this works if the repo is **public**; for a private repo, use "Send Files to TV" from your phone instead).
4. Install. The app appears in the TV's app row as **PanGaze**.

**Remote controls in TV mode**
- Arrows move, **OK** selects, **Back** goes back.
- OK on a channel plays it in the preview. OK again goes **full screen**.
- Full screen, live: **▲ ▼ or CH+/CH−** change channel, OK shows what's on now and next.
- Full screen, catch-up / movies / episodes: **◀ back 10 s, ▶ forward 30 s**, OK or ⏯ pauses.
- In the guide: arrows move through programmes. OK on a past ⟲ programme plays catch-up,
  OK on what's on now plays live.

## What's in the app

Home screen with Continue watching, reminders 🔔, next-episode autoplay, search everything,
mini player and picture-in-picture (⧉ Pop out), multi-view (2 or 4 channels), audio & subtitle
picker, sleep timer, channel number entry, channel groups and hiding, parental lock with PIN,
profiles (including kids profiles), backup/restore, series recording and M3U playlists with
XMLTV guides. Everything works with a TV remote too.

**Sync with your Mac:** In **Settings → Sync between devices**, enter the address your Mac's
Terminal window shows under "On your Wi-Fi" (e.g. `192.168.1.20:8899`), and the **PanGaze code**
shown on the Mac in Settings → Phone & TV (e.g. `ABCD-EFGH`; it's needed once per device). On a TV you
can send both from your phone: Phone & TV → scan → **Set up TV** → "Your Mac or PC". Favourites, progress,
reminders, profiles and the parental lock then sync between the Mac, your phone and the TV.
Start the Mac player with "Start PanGaze.command", which allows Wi-Fi devices.

**Series recording on Android** checks the guide for new episodes when you open the app and
while a recording is running. For fully automatic series recording, let the Mac do it.

## Radio

The **Radio** tab streams internet radio stations from the free Radio Browser directory. Tap
☆ to save favourites; they sync with your other devices. Radio may stop if Android puts the app
to sleep with the screen off; keep the app open, or use picture-in-picture, for long listening.

## Making recordings smaller

Recordings tab → **Make recordings smaller** (Balanced H.264, Small HEVC, Smallest). The phone's
own video chip re-encodes each finished recording, and the notification shows progress. HEVC is
used when your phone supports it. The original is only replaced if the new file is complete and
smaller, so a phone that can't do it just keeps the original. Long recordings take a while, so
keep the phone charging.

## Catch-up list

Channels with catch-up show a purple **⟲ Catch-up** button on the video. It opens a list of
earlier programmes; tap one to play it. On a TV remote, press **◀** while watching live.

## Recording (and saving to Google Drive)

- **● Record** under the player records what's on now until the programme ends.
- **● Rec** next to a programme in the schedule (or the Guide) sets a recording. **⬇ Save** saves a
  past programme from catch-up.
- Recordings carry on with the app closed. A notification shows while it's recording.
  Allow notifications when asked.
- When a recording finishes, the notification has a **Save to Google Drive** button. The
  **Recordings** tab has the same button, plus Play, Other player and Delete.
- Files are stored on the phone in *Android/data/org.xtreamplayer.app/files/Movies/Xtream Recordings*.
- On some phones (Samsung, Xiaomi…) set **Settings → Apps → PanGaze → Battery → Unrestricted**
  so scheduled recordings aren't stopped.

## Updating the player

The player page lives in `app/src/main/assets/index.html`, the same file as `xtream-player.html`
in the Mac version. Replace it and rebuild to update the app.

## Notes
- Android 7.0 or newer.
- Tap the fullscreen button on the video to go landscape. The phone's back button leaves fullscreen,
  closes pop-ups, then returns to Live TV.
- The screen stays awake while the app is open.
- If your provider refuses the connection, it may want a different player identity: change
  `PROVIDER_UA` in `MainActivity.java` (e.g. to `IPTVSmartersPro`) and rebuild.
