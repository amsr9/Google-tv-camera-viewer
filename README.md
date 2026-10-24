# Home Cameras — Google TV viewer for Hikvision NVRs

A small Google TV / Android TV app that connects directly to a Hikvision NVR on your
home network and shows every camera.

- **Finds your cameras automatically** (names included) through the NVR's ISAPI interface
- **Grid view**: 1, 4, 9 or 16 cameras per screen, using the light sub-stream
- **Full screen**: press OK on a camera to view it live in full 4K quality (VLC player); ◀ ▶ switches camera
- **Arrange cameras**: hold OK on a camera, move it with the arrows, OK to place it (order is saved)
- **Layouts** (top-bar *Layout* button or Settings): *1 big + 5 small* (every tile exactly 16:9: nothing trimmed, big camera in 4K), or equal tiles with the whole picture, stretched, or filled
- **Pages**: press ◀ ▶ past the edge of the grid (or Channel Up/Down) to see more cameras
- **Auto-cycle** through pages every N seconds (optional)
- **Reconnects by itself** when a camera or the network drops
- **Pop-up on detection**: when a person / vehicle / motion is detected, that camera jumps to full screen with a chime, then returns to the grid (Settings → Alerts)
- **Events**: top-bar *Events* lists recent detections; select one to watch the recording from just before it
- **Playback**: full screen → ▼ → *Playback*, pick day/hour/minute; ◀ ▶ 30 s, ▼ ▲ 10 min, OK pause, hold OK to pick another time
- **Digital zoom**: full screen → ▼ → *Zoom*; OK zooms 2×/3×/4×, arrows move, Back exits
- **Start with the TV**: Settings → *Open the cameras when the TV starts* (needs "Display over other apps")
- **NVR health**: a red warning if a camera goes offline, the hard drive fails, or the NVR stops answering
- Stays on your local network; nothing goes through Hikvision's cloud

---

## 1. Prepare the NVR (once)

1. Open the NVR's web page in a browser (e.g. `http://192.168.1.64`).
2. **Give it a fixed IP** (Configuration → Network → Basic → TCP/IP, untick DHCP).
3. **Enable ISAPI** (Configuration → Network → Advanced → Integration Protocol →
   enable *Hikvision-CGI / ISAPI*, authentication *digest*). The exact menu varies by firmware.
   If you can't find it, the app still works — just enter the number of cameras manually.
4. **Create a viewer account** (Configuration → System → User Management → Add,
   level *Operator* or *User*, with *Live View* permission for all channels).
5. Ports: the defaults are **80** (HTTP) and **554** (RTSP). Check them under
   Configuration → Network → Basic → Port.

## 2. Build the APK (no Android Studio needed)

**Option A — GitHub (easiest from any computer):**
1. Create a free GitHub account and a new **private** repository.
2. Upload all files from this folder to the repository (keep the folder structure,
   including the hidden `.github` folder). On the web: *Add file → Upload files*,
   drag the whole folder contents in.
   If the Actions tab later shows no workflow, the hidden folder was skipped (Mac and
   Windows hide folders starting with a dot): use *Add file → Create new file*, name it
   `.github/workflows/build.yml` and paste in the contents of that file from this zip.
3. Open the repository's **Actions** tab. The *Build APK* job runs automatically
   (about 3–5 minutes). Click the finished run and download **home-cameras-apk**.
   Unzip it to get `home-cameras.apk`.

**Option B — Android Studio:** open this folder, wait for the sync to finish, then
*Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

## 3. Install on the Google TV

1. On the TV: **Settings → System → About**, scroll to **Android TV OS build** and press OK
   **7 times** until it says you're a developer.
2. Install **Downloader** (by AFTVnews) from the TV's Play Store.
3. **Settings → Apps → Security & restrictions → Unknown sources** → allow Downloader.
4. Get the APK onto the TV, whichever is easiest:
   - **Direct link (simplest, needs a public repository):** in Downloader, type
     `https://github.com/<your-username>/<repo-name>/releases/latest/download/home-cameras.apk`
     The code contains no passwords (you type those on the TV), so a public repository is safe.
   - **Android phone:** install *Send Files to TV* (by Yablio) on the phone and TV and
     send `home-cameras.apk` from the downloaded artifact.
   - **Computer:** `adb connect <TV-IP>` then `adb install home-cameras.apk`.
5. Choose Install. **Home Cameras** appears in your apps.

## 4. First run

The settings screen opens. Enter the NVR IP, username and password, press
**Test connection** — you should see your list of cameras — then **Save**.

| Remote button | Camera grid | Full screen | Settings |
|---|---|---|---|
| Arrows | Move between cameras | ◀ ▶ previous / next camera | ▲ ▼ move between items |
| OK | Open camera full screen | Back to all cameras | Type in a field / next choice |
| Hold OK (1 s) | Pick up the camera to move it: arrows move it, OK places it, Back cancels | — | — |
| ◀ ▶ on a choice | — | — | Change the option |
| ◀ ▶ at the grid edge, Channel ± | Previous / next page | Previous / next camera | — |
| ▲ from the top row | Reach **Layout** / **Refresh** / **Settings** (the bar hides after 6 s; any key shows it) | — | — |
| Menu / Settings key | Open Settings | — | — |
| Back | From the top bar: back to cameras. Otherwise press twice to close | Back to all cameras | Cancel (asks first if you changed something) |

## Pop-ups and Events need one NVR setting

The NVR only reports a detection to the app if that event has **Notify Surveillance Center** ticked.
In the NVR web page: **Configuration → Event** → for each camera's *Motion Detection* (and/or
*Intrusion*, *Line Crossing*) → **Linkage Action** → tick **Notify Surveillance Center** → Save.

## Troubleshooting

- **"Wrong username or password"** — Hikvision locks an account for ~30 minutes after
  several failed logins. Fix the password, wait, then try again.
- **"No cameras found"** — ISAPI is probably disabled. Enable it, or set
  *Number of cameras* in Settings.
- **Tiles say "Offline · retrying"** — check RTSP port 554 and that the user has
  live-view permission. Test on a computer with VLC:
  `rtsp://user:pass@192.168.1.64:554/Streaming/Channels/102`
- **Video stutters with many cameras** — TV boxes can decode only a limited number of
  streams at once. Keep 4 per screen (grid size 2), and in the NVR set each camera's
  **sub-stream** to H.264, 640×360, 10–15 fps.
- **Black tiles on H.265+ cameras** — set the sub-stream to H.264 (H.265+ is a
  Hikvision-specific mode some decoders don't handle).

## Stream URLs used

`rtsp://<user>:<pass>@<nvr>:554/Streaming/Channels/<channel>0<stream>`
— stream `1` = main (full screen), `2` = sub (grid). Example: camera 3 sub-stream = `302`.

## Security notes

- Credentials are stored privately on the TV (app-only storage).
- The app talks only to the NVR's local address. **Don't port-forward the NVR** to the
  internet; use a VPN (e.g. Tailscale/WireGuard) if you ever need remote access.
