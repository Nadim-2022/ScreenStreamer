# Autel controller screen streamer → go2rtc

Streams the Autel Smart Controller screen (the whole Autel Explorer/Enterprise UI,
including the live drone video and the map/OSD overlays) to a go2rtc server over RTSP.

No MSDK, no reimplementation of the flight app.

```
Controller screen ──MediaProjection──> MediaCodec H.264 ──RTSP push──> go2rtc ──> WebRTC/HLS/RTSP viewers
```

## 1. Server side

Drop `go2rtc.yaml` next to the go2rtc binary and start it. The important part:

```yaml
streams:
  drone_screen:      # empty source = ingest target
```

go2rtc only accepts a push into a stream that already exists in the config, so this
empty entry is mandatory. Restart go2rtc after editing.

Verify the ingest endpoint from a laptop before you touch the controller:

```bash
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -c:v libx264 -tune zerolatency \
       -f rtsp -rtsp_transport tcp rtsp://SERVER_IP:8554/drone_screen
```

Then open `http://SERVER_IP:1984/stream.html?src=drone_screen`. If that works, the
server is fine and any later problem is on the Android side.

## 2. Build the app

Open the project in Android Studio and build, or:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To install on the controller: enable Developer options → USB debugging on it, plug in
USB-C, `adb devices`, then `adb install`. Some Autel firmware hides Developer options —
tap the build number seven times in Settings → About.

## 3. Fly

1. Launch **Drone Screen Streamer**, enter `rtsp://SERVER_IP:8554/drone_screen`.
2. Pick a quality preset, tap **Start**, accept the screen-capture dialog.
3. Switch to the Autel app and fly. The stream keeps running from the foreground service.
4. Watch at `http://SERVER_IP:1984/stream.html?src=drone_screen`.

Settings persist, so on later flights it's launch → Start → accept.

## Things that will bite you

**Uplink.** The controller talks to the drone over its own radio, so Wi-Fi is free for
the uplink — but the Smart Controller has no cellular modem. In the field you tether it
to a phone hotspot. Budget your bitrate for the *uplink*, which on LTE is usually
1–5 Mbps and highly variable. Start at the 720p preset; 1080p is for LAN testing.

**Latency.** Screen capture adds roughly 150–400 ms on top of whatever latency the Autel
app already has showing you the drone feed. Viewing via go2rtc's WebRTC output adds
another ~100 ms; MSE and HLS add much more. This is fine for a ground observer, not for
flying from the remote view.

**Packet loss.** RTSP over a lossy LTE link degrades badly — you'll get macroblocking
that never recovers until the next keyframe. If this bothers you, switch to SRT, which
retransmits lost packets. RootEncoder supports SRT natively (change the URL to
`srt://host:port`), but go2rtc does not ingest SRT — its ingest list is flv, mjpeg,
mpegts, rtmp, rtsp and webrtc. So you'd run MediaMTX as the SRT receiver and let go2rtc
pull from it, or run `srt-live-transmit` to bridge SRT → RTSP into go2rtc.

**A black stream.** If the Autel app marks its video surface as DRM-secure, MediaProjection
captures the UI but the video area comes out black. Test this early — take a normal
screenshot while the drone feed is live. If the screenshot shows the feed, capture will
too. Autel is unlikely to flag it, but confirm before you rely on it.

**Battery and heat.** Continuous 1080p encode plus Wi-Fi uplink is a real drain on the
controller during a long flight. The 720p preset is noticeably kinder.

**Android 14+.** The foreground service must already be running with the
`mediaProjection` type before `getMediaProjection()` is called, and consent is required
per capture session — you cannot silently resume after a stop. The code handles the
ordering; just don't move the `startForeground` call.

## Alternatives worth knowing

- **WHIP instead of RTSP.** go2rtc accepts WebRTC ingest at
  `http://SERVER_IP:1984/api/webrtc?dst=drone_screen`. Lowest latency and it traverses
  NAT without port forwarding, but you'd swap RootEncoder for a libwebrtc build with
  `ScreenCapturerAndroid`, which is a much heavier dependency.
- **RTMP.** Also supported as ingest; slightly more forgiving through restrictive
  networks than RTSP. Just change the URL scheme — `GenericStream` handles it.

## API drift

RootEncoder's API changed shape around 2.4 (`DisplayBase` → `StreamBase`/`GenericStream`
with pluggable sources). This project targets 2.7.5. If a symbol doesn't resolve after a
version bump, check the wiki: https://github.com/pedroSG94/RootEncoder/wiki
