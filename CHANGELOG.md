# Changes

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
