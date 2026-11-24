# Changes

## 0.42.0.55-research — 2026-11-24

- Button press animation, and the green flash on tap is gone: the green overlay was the Android WebView's default tap highlight (our CSS never set a green `:active` background) — `-webkit-tap-highlight-color: transparent` turns it off, and every tappable now has one short press animation (scale .95–.96 + slight dim, 0.12 s). Rail buttons, tiles, chips, search-go, media buttons, keys, dark buttons, web-view buttons, segmented buttons, island pill.
- Fixed left rail: the icon rail is pinned like Android Auto's sidebar (sticky, full height) — only the content column scrolls.
- Responsive layout: the right column is now fluid (`minmax(280px,348px)` instead of a fixed 348px) and a breakpoint around 1100px stacks it below the main content, so the weather/date/speed cards stay inside at any screen size. The ≥1180px car layout (3-column photo layout) is unchanged at car size.
- Smoother scrolling: `scroll-behavior: smooth` on the page and the local views, `-webkit-overflow-scrolling: touch`. The clock tick and media progress updates already touch single elements by id, so no full-page layout per frame. This does NOT fix the scroll jelly — that is the video pipeline frame rate (the known bottleneck), and page-side work cannot fix it.
- App/web view chrome removed: the web view's top bar (Home / Back / URL box / Close) and the description paragraphs under each local page (including Navigation) are gone — they ate space. The rail Home button is the way back (the rail stays visible in the web view). The native ⌂ Home overlay for the dashboard browser stays. `setUrlBar()` is now a guarded no-op.
- YouTube is the real page now: the tile opens https://www.youtube.com as a full page in the dashboard browser (top-level navigation, mobile user agent, bridge removed first), not the one-video IFrame embed. The parked-only confirm still gates it once per session. The embed player, its 4-video picker and all IFrame code are removed. In the pure browser preview (no native bridge) it falls back to the frame.
- Ferrostar map fixed — the lavender blank was not a broken map: MapLibre's demo style has no streets at all, just country shapes, and Cambodia is painted lavender, so a working map at zoom 13 over Phnom Penh looked exactly like the screenshot. The style is now OpenFreeMap Liberty (real streets + labels, no key, verified: 111 layers, 23 road layers). The bundle was rebuilt from the same vite config (2,345,659 bytes, sha256 `69c0fb1446eddab68f87df61e028ae8e7358def0f7963bba3d66181d986775c5`).
- Navigation page: the status line (`#navStatus`) moved above the map so it is always visible, and the map is resized once more after layout (`requestAnimationFrame`) in case it started at 0 size. The error reporting (ferrostar-error → status line) is unchanged.
- Auto-mute on connect (new toggle in Advanced options, default ON): when a dashboard or mirror session starts, the phone's media volume is saved and set to 0; when the session ends — stop, disconnect, drop, error, any exit path — it is restored. Lite is excluded (the phone is already silent there). While muted, phone apps play no sound either. Diagnostics export shows the toggle state.
- Semi-auto screen share (mirror only): when a mirror-mode session authenticates and no capture grant exists, the app opens the system capture dialog by itself — one tap on the dialog instead of finding the Prepare button. If the app is in the background when the session authenticates, the request waits until the app is resumed. The dialog still needs one tap every time (Android privacy gate); zero-tap auto-share is impossible. Dashboard video needs no capture, so this is mirror only.
- Known limits: mirror-mode car sound still needs a car test to confirm the audio capture does not follow the speaker volume; scroll jelly is the video frame rate, not the page; voice guidance unproven in this WebView; Valhalla demo server fair-use limits (FOSSGIS `X-Client-Id` + notification still pending); search untypeable in the car. Before the car test, check the fixed map in the phone preview first (the nav view should show Phnom Penh streets there); route + maneuver card still need the parked-car test.

## 0.42.0.54-research — 2026-11-24

- Fixed the dashboard not loading in the preview ("not found"): the app asked the local server for `/carhome/dashboard.html`, but the server's root already is `carhome/`, so it looked for `carhome/carhome/dashboard.html` and answered 404. The dashboard URL is now `/dashboard.html`; the Ferrostar bundle resolves the same way (it is a relative reference from the page).

## 0.42.0.53-research — 2026-11-24

- Local HTTP server for the dashboard: the page is no longer a `file://` page. A tiny server inside the app (`DashboardHttpServer`, standard library only, no new dependency) serves `assets/carhome/` over `http://127.0.0.1:18765` (next ports up to +10 if it is busy, loopback only, GET + HEAD, no listing). This is the fix for both preview-test failures: a `file://` page sends no HTTP `Referer`, which is why YouTube refused the embed with error 153, and the Ferrostar ES module never loaded from `file://` — with a real http origin the embed carries a Referer and the module loads like a normal web page. The server starts when the dashboard WebView is created and stops when the dashboard closes (reference-counted, so a second dashboard or a double create cannot leak it or break it); if every port is busy the `file://` page is the fallback.
- Google Maps tile removed: the Maps tile is gone from the app grid and the start page, and the Google Maps embed code is gone with it. The destination search and the category chips (ATM, Restaurants, Hotels, Gas stations) now open the OpenStreetMap navigation view and search there (Nominatim) — tap a result to route to it. The rail navigation button and the home navigation card open the same view.
- Ferrostar navigation kept: the live turn-by-turn view from build 52 stays exactly as it was (Valhalla demo server, preset chips, big maneuver card, voice guidance) — the local server is what should make its module load this time. `ferrostar-bundle.js` stays in the app's files, now loaded over http.
- YouTube unchanged: same IFrame API player, same 4-video picker, same parked-only confirm — only the page origin changed, which is what the player needed.
- Known limits: voice guidance is unproven in this WebView; the Valhalla demo server is a courtesy service with fair-use limits — FOSSGIS asks published apps to notify them and send an identifying header (follow-up, not built yet); search is untypeable in the car (chips and presets work; typing works in the phone preview).

## 0.42.0.52-research — 2026-11-23

- Preview top bar gone: the phone preview no longer has the native Back / Preview / Close row taking space — the WebView fills the whole screen. One ✕ exit button sits at the bottom of the dashboard page's left rail and only appears in the phone preview (the bridge reports `isPreview`); in the car there is no exit button. Android back still finishes the preview.
- YouTube on the IFrame API: the player is now a real YouTube player object instead of raw iframe src swaps — it reports its real play state (Playing / Paused / Buffering / Ended) and says why when a video fails (not found, embedding blocked by the owner, …). Same 4-video picker and the same parked-only confirm.
- Live navigation with Ferrostar: the limited OpenStreetMap embed tile is replaced by a real turn-by-turn navigation view — Ferrostar (open source, BSD) bundled into the dashboard's own files, routing on the free keyless Valhalla demo server, MapLibre map, GPS fed by the phone bridge (no WebView geolocation prompt). Preset destination chips (Airport, Central Market, Riverside, Independence Monument) plus Nominatim search, and a big car-sized next-maneuver card (next turn, distance, remaining distance + ETA) fed by `onTripStateChange`. Voice guidance is on (web Speech Synthesis — unproven in this WebView, see limits). Google Maps tile stays for browsing.
- Dashboard browser: sites that refuse to load inside a frame (Instagram, Uber, TikTok) can now open as the WebView's main document — the bridge is removed first (`removeJavascriptInterface`), so it is never exposed to a third-party page, and a small native ⌂ Home overlay (car dashboard and phone preview) brings the dashboard back and re-adds the bridge. The fallback page's "Try loading here again" is now "Open in dashboard browser". Login and typing work in the phone preview; in the car there is still no keyboard.
- Dynamic Island media pill: when music starts, a small white pill slides in at the top (track + play/pause) instead of the Media page opening by itself — tap it to expand to a mini-player with seek, it auto-shrinks ~10 s after a pause, and it never navigates away. The full Media page stays reachable from the rail music icon or the pill.
- Weather page, Google-Weather-style: the weather tile now opens a full page from one Open-Meteo call — Now hero (big temperature, condition, feels-like, today high/low), 24-hour strip, 10-day list with tap-for-detail, and condition cards (wind, humidity, UV, pressure, sunrise/sunset). Data: Open-Meteo, no key.
- Known limits: voice guidance is unproven in this WebView; the Ferrostar bundle loads as an ES module from a file:// page (needs the `allowUniversalAccessFromFileURLs` setting, already on since build 50 — unproven on the device until the preview test); search is untypeable in the car (chips and presets work; typing works in the phone preview); frame-refusing sites use the dashboard browser instead of the iframe; the Valhalla demo server is a courtesy service with fair-use limits — FOSSGIS asks published apps to notify them and send an identifying header (follow-up, not built yet).

## 0.42.0.51-research — 2026-11-23

- New web dashboard replaces the native dashboard: the light widget layout from the design (left icon rail, destination search + chips, app grid, big clock, GPS speed gauge, date, weather, Now Playing). It is a single local page (`assets/carhome/dashboard.html`, no server) shown on the car screen by one WebView.
- Phone bridge: the dashboard reads live phone data through a native bridge that only the local dashboard page can see — battery %, charging, Wi-Fi, mobile, Bluetooth, GPS speed, the track playing on the phone (title, artist, album art, position) with play / pause / skip / seek, and the Maps or Waze navigation instruction. When music starts playing, the Media page opens by itself. The bridge never touches the car session.
- GPS speed: the gauge uses the phone's GPS (new location permission, asked once from Advanced options or when the preview opens; without it the gauge shows --). Trip time, distance and average speed accumulate from GPS while driving and reset from the dashboard.
- YouTube stays parked-only: video tiles ask once per dashboard session to confirm the car is parked, in the page itself. Sites that refuse to load inside a frame (TikTok, Instagram, X, …) offer to open the real app on the phone instead.
- Preview on the phone now shows the new dashboard (same page, same bridge, same GPS speed) — Advanced options, "Preview the new dashboard on this phone (no car needed)". The old Apps launcher page is superseded by the dashboard's app grid; its files stay in the repo unused.
- Known limits: the search box has no keyboard on the car (the chips work by tap; typing works in the phone preview); fullscreen video is not supported yet (videos play inline); a Maps link that leaves the embed view can show an error inside the frame instead of opening the Maps app.

## 0.42.0.50-research — 2026-11-23

- Dashboard web apps fixed after the first preview test: YouTube now loads with a TV user agent, so youtube.com/tv shows the TV interface instead of redirecting to youtube.com; Google Maps stays in the WebView (its redirect to the Maps app is rewritten to the plain web address); the weather tile is now a live Open-Meteo card for Phnom Penh instead of the plain-text wttr.in page.
- Maps is now two tiles: a Leaflet + OpenStreetMap page centred on Phnom Penh (no account, no key, drag to pan, + / − to zoom) and Google Maps.
- Preview top bar: the note is shortened to "Preview" so it no longer wraps in portrait.

## 0.42.0.49-research — 2026-11-23

- Dashboard Apps launcher: a "▦ Apps" button on the dashboard opens a web launcher on the car screen, with tiles for YouTube, Maps, and Weather. Tap a tile and that web app opens full-screen; Home returns to the launcher, Close returns to the dashboard. YouTube asks once per session to confirm the car is parked.
- Preview on the phone: Advanced options has "Preview the dashboard web apps on this phone (no car needed)" — the same launcher and the same WebView settings, shown on the phone so it can be tried without the car. Touch, keyboard, and pinch work in the preview; on the car, taps and drag-scroll work, with no keyboard and no pinch.
- The launcher is a single local page (`assets/carhome/index.html`, no server); the WebView has JavaScript and storage on and no bridge to the phone, so the pages cannot touch anything on the phone. Spotify is not a tile yet — it needs a login typed in, and there is no keyboard on the car. Sound is the usual dashboard deal: the car's Bluetooth by default, AAC via CarLink if "Prepare audio via AionLink" was tapped.

## 0.42.0.48-research — 2026-11-18

- Reduce bass clipping (toggle in Advanced options): the audio is attenuated by 3 dB before AAC encoding, on both the mirror and Android Auto paths, so heavy bass no longer breaks up on the car's CarLink sound. Flip it on during a bass-heavy song and compare — the toggle itself is the test; turn the car volume up a notch to compensate.
- Check for updates: Advanced options checks GitHub Releases for a newer build (also once a day when the app opens, never during a drive) and shows "Update available" with a link to the release page. Nothing downloads or installs by itself.
- Dashboard: the media card now shows the album art and a track progress bar.

## 0.42.0.47-research — 2026-11-17

- Auto-reconnect after a drop: if a live session ends unexpectedly (network error, the car closes it, or the P2P link drops), the app retries by itself with QUICK (no code), max 3 attempts (right away, +15 s, +45 s), then falls back to the car watch. Never on a user Stop.
- Diagnostics export shows "Auto-reconnect: attempt N of 3 / idle".

## 0.42.0.46-research — 2026-11-17

- Rebrand to AionLink Open X: new name and logo (original A mark with the X blended into the road, a small i60 SUV, and the chain link over the car), new launcher icon on all densities. Package name unchanged (`org.aionlink.open.lab`), so settings and QUICK keys are kept.

## 0.42.0.45-research — 2026-11-17

- The car watch survives the car ending a session, a missed auto-scan, and the 10-minute alert cooldown; it restarts itself in all of those cases.
- AAC audio bitrate raised from 128 kbps to 256 kbps.
- Advanced options offers an "Allow full-screen alerts" button when the permission is not granted, and the diagnostics export shows the last car-found alert.

## 0.42.0.44-research — 2026-11-16

- The car watch restarts itself when the app opens while idle, when a session ends, and after an app update (it used to stay dead until the toggle was touched again).

## 0.42.0.43-research — 2026-11-16

- Every build now carries its version in the file name (`aionlink-open-x-0.42.0.<code>-research.apk`) so builds can be tracked.

## 0.42.0-research — 2026-11-10–16

- Simplified home and advanced screens; OpenHeadunit Lite as the Android Auto path.
- Quick reconnect without the code (QUICK): the first connection saves a long-term key for the car, later connections skip the code dialog. Proven in the car on 2026-11-16 — the i60 accepts QUICK.
- Car watch and projection mode picker: the app watches in the background for the car and notifies when it is near; the home screen picks Mirror, Dashboard, or Lite for the next connection (Lite default).
- Steering-wheel media keys: the car's UIBC key events go over the bridge to Android Auto (mirror/dashboard: to the phone's media session). The car was later found to send no presses at all (2026-11-17) — a car firmware limitation, not an app gap.
- Full-screen alert: when the watch finds the car, the notification opens the app by itself (needs the full-screen alert permission and the screen off or locked).
- The code dialog only appears on the PIN path, and closing it no longer kills a live QUICK session.
- The 5277 head-unit server probe was removed: it parked the Android Auto server and broke video (0 frames) on the next Lite connection.
- Wi-Fi lock held while the car link is up, to reduce P2P drops.
- Full-bitrate video in car mode (11 Mbps instead of the 3.9 Mbps relay bitrate).
- "Keep 30 fps when hot" toggle: at thermal SEVERE the bitrate still halves, but the frame rate stays at 30 fps.

## 0.41.0-research — 2026-11-08

- With Open Headunit as the source, Android Auto sound reaches AionLink through the bridge (PCM pipe) and is sent to the car in AAC; it no longer plays on the phone and needs no audio capture.
- The local test reports the KiB received through the bridge, the AAC frames, and the peak.
- Requires Open Headunit with `patches/open-headunit/bridge-audio.patch`. Verified only on the phone, without the AION.

## 0.40.0-research — 2026-11-07

- Captured audio reports its level every 5 s or when it goes silent, to tell whether Open Headunit sound reaches the car.
- Not verified with the AION; not installed: the shared signing key is missing on this PC.

## 0.35.0-research — 2026-11-06

- Car mode: AionLink's own panel (clock, music with controls, Maps/Waze navigation) sent to the car without screen capture, for use with the phone off.
- Car touch straight to the panel; foreground service during the session.
- Button to open Android Auto with Open Headunit (self mode) and bring it to the car in mirror mode.

## 0.34.0-research — 2026-11-06

- Sharper picture: encodes at the resolution the car requests (1920x960) with proportional bitrate (11 Mbps).
- “Image quality” button to fall back to the compatible 1280x720 mode if the car needs it.
- The screen-off probe runs in a foreground service, at 1920x960, with battery and temperature.
- Optional signing with `android/keystore.properties` and a race fix when closing RTSP.

## 0.28.0-research — 2026-11-03

- Own launcher with the apps the owner chooses, opened when AionLink is tapped in the car; can be turned off to share the screen only.
- Back and home buttons over the shared screen, for use from the car touch.
- Optional landscape during projection, restored when it ends or after an unexpected close.
- Still screen sharing: the phone must be on and unlocked. Not verified with the AION.

## 0.27.0-research — 2026-11-03

- The accessibility button warns if touch is inactive, and the log shows whether touches arrive with no active service.
- The probe compares the virtual-display view with direct drawing, with the phone locked.
- 0.26 confirmed on the AION about 30 frames/s and better streaming, as the owner reports; 0.27 not verified with the AION.

## 0.26.0-research — 2026-11-03

- The encoder is limited to the negotiated fps and requests low latency, to avoid the frame queue in the car.
- Optional minimum brightness during projection, restored when it ends or after an unexpected close.
- Diagnostics add frames/s, slow writes, per-session battery, and screen on/off.
- Local probe to tell whether an own display stays alive with the phone locked.
- 0.25 confirmed on the AION projection and touch, as the owner reports; 0.26 not verified with the AION.

## 0.25.0-research — 2026-11-02

- `AppStateChanged` is sent as `SEND_SYNC`, the only type the car's parser accepts; it used to go as `SEND`.
- The log adds the car's ACKs and the sent `AppStateChanged` with its fields.
- 0.24 confirmed on the AION the "AionLink" icon and methods 4 and 30 when tapped, followed by a close in about 65 ms; 0.25 not verified with the AION.

## 0.24.0-research — 2026-11-02

- Unsolicited notices from 0.23 removed: the AION closed the session 84 ms after `AppStateChanged`.
- With automatic AUTH, the connection does not start if capture is not ready, and each capture permission is good for a single connection.
- 0.24 not verified with the AION.

## 0.23.0-research — 2026-11-02

- After the car reads the app details, the phone sends once `AppStateChanged` (STARTED, show) and `NotifyMirrorState` (BACK_TO_FOREGROUND).
- The app uses id 2; `AppStateChanged` leaves the timestamp at 0, because the SDK player compares it with the video time.
- 0.22 confirmed on the AION mode 4 and reading the app list, but no picture; 0.23 not verified with the AION.

## 0.22.0-research — 2026-11-02

- AUTH also declares mode 4 (`APP_FULLSCREEN_CAST`), the only one the AION reports, due to the "integrated desktop screen casting" error.
- CONTROL answers the app list, its details, and `InvokeApp` with a single own app representing the phone mirror.
- 0.21 confirmed on the AION the error text and `workModes=4`; 0.22 not verified with the AION.

## 0.21.0-research — 2026-11-02

- On receiving `disconnect` (method 23) from the car, video and channels stop; it used to be ignored.
- Method 4 (car in foreground) requests a key frame.
- Car configuration request (method 24), with limited export and no Bluetooth address.
- Diagnostics log the car's messages and RTSP requests with timestamps, without content, plus the other M3 parameters.
- 0.20 confirmed on the AION RTP over TCP and `PLAYING`, but no picture; 0.21 not verified with the AION.

## 0.20.0-research — 2026-11-02

- RTSP no longer stalls at M3 when the receiver picks `RTP/AVP/TCP;unicast`.
- Video and audio over TCP: the phone connects to the `client_port` and frames with a 16-bit length.
- `server_port` always as the pair `15550-15551`; interleaved `SETUP` answers 461.
- Diagnostics export `wfd_client_rtp_ports`; not verified with the AION.

## 0.19.0-research — 2026-10-30

- Vehicle touch: UIBC server (4321), observed JSON, and gestures via an accessibility service.
- Phone audio captured (AudioPlaybackCapture) and sent as AAC inside the Wi-Fi Display stream.
- Passive encrypted MEDIA (57229) and SENSOR (57239) listeners; M4 declares UIBC and prefers AAC.
- No LPCM, pinch, or microphone; not verified with the AION.

## 0.18.0-research — 2026-10-30

- Phone CONTROL channel (57219) with bodies encrypted by the session key, heartbeat, and ACK.
- Own Wi-Fi Display source: RTSP M1–M7, MPEG-TS/RTP, and H.264 video from MediaProjection.
- Listeners open before AuthConfirm; button to authorize screen capture.
- No audio, touch (UIBC), or SENSOR; not verified with the AION.

## 0.17.0-research — 2026-10-30

- AUTH request with a positive sequence and workModes 257 observed on Samsung.
- HKDF-SHA256 derivation of the session key and sending of the encrypted AuthConfirm.

## 0.4.0-research — 2026-10-22

- Android USB/AOA module with detection, explicit permission, and descriptor open/close.
- Log of the ICCOA/CarLink accessory observed on Samsung and CarWith, without privileged permissions.
- Original incremental multiplexed reader with limits and six synthetic tests.
- Optional experimental start of RTSP/UIBC channels from static evidence, with no simulated session.
- Distinction between USB power, ADB/charging, host devices, and the AOA accessory.
- USB and Bluetooth available separately; no Bluetooth role imposed that is still unknown.

## 0.3.0-research — 2026-10-22

- Real GATT connection to the owner-selected BLE and service discovery.
- Recognition of UnionShare and metadata of known characteristics, without reading/writing values.
- Timeout, cancellation, protection against stale callbacks, and automatic link close.
- Explicit connect action and inspection result kept separate from the ICCOA session.
- Initial screen probe and Stop button on the S25; 23 automated tests passing.
- Target receiver clarified by the owner: ICCOA CarLink and Huawei Car; not CarbitLink.

## 0.2.0-research — 2026-10-22

- ADB reading of logs retained by UID and analysis of installed Samsung Auto 3.1.04.10.
- Documented OEM failure diagnosis; comparison of Samsung/Xiaomi/OPPO/CarLife routes.
- Static UCar header specification and an own offline reader with CRC and limits.
- BLE hints by observed UUID, without claiming compatibility or a session.
- Export with transport, signal, connectability, allowlisted UUIDs, and timestamps.
- Protection against BLE callbacks from earlier searches; button consistent with an active search.
- 6 Python tests and 14 JVM tests; APK built and updated via ADB on the S25.

## 0.1.0-research — 2026-10-21

- Local SHA-256 inventory, public signature, manifest, resources, DEX, and libraries.
- Apktool/JADX automation with logs and hash-separated outputs.
- Search with path/line/term indexes and metadata comparison.
- ELF inspection via NDK LLVM, without loading libraries.
- Modular Android Kotlin base, StateFlow, tests, Compose, and Bluetooth discovery.
- Unverified AION profile and exportable diagnostics without names or addresses.
- Documentation of limits, evidence, and stationary test procedure.
