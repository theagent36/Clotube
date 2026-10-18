# Clotube

**Clotube** is a high-performance Android media streaming application architected for uncompressed, high-bitrate 4K/60fps video playback directly from Google Drive, bypassing Google's transcoded web player limitations.

---

## Architecture & Core Features

- **Direct Binary Streaming Mode**: Bypasses Google Drive web transcoder limits (which clamp streams to 720p/1080p 30fps) by retrieving raw video streams directly using `alt=media&supportsAllDrives=true&acknowledgeAbuse=true`.
- **OAuth Redirect Preservation**: Custom OkHttp network interceptor injects `Authorization: Bearer <token>` and `User-Agent: Clotube/1.0`, ensuring authentication credentials are preserved across HTTP 302 cross-domain redirects to Google Cloud storage nodes.
- **4K Tuned Buffer Engine**: Configured with an optimized `DefaultLoadControl` (15s min buffer, 45s max buffer, 2.5s initial buffer, 5s rebuffer) to prevent stutter on 30–60+ Mbps streams.
- **Persistent LRU Cache**: Media3 `SimpleCache` with a 500MB least-recently-used disk cache in `cacheDir/clotube_media_cache`.
- **Dark Cinema Theme**: Tailored Material Design 3 palette with Deep Background (`#0B0C10`), Electric Blue (`#2F80ED`), and Neon Teal (`#00E5FF`).
- **Stats for Nerds HUD**: Live diagnostics overlay tracking real-time FPS, live bitrate, dropped frames, decoder pipelines, and buffer health.
- **Dual-Layer Scrubber**: Interactive scrubber showing active playback position (Electric Blue) alongside buffered chunk progress (Neon Teal).

---

## Technical Stack

| Component | Specification |
| :--- | :--- |
| **OS Target** | Android 15 (API 35) |
| **Minimum SDK** | Android 8.0 Oreo (API 26) |
| **Language & Runtime** | Kotlin 2.1.0 • Java 21 LTS |
| **UI Framework** | Jetpack Compose (BOM 2024.10.00) • Material Design 3 |
| **Media Player** | LibVLC |
| **Networking** | OkHttp 4.12.0 • User-Agent: `Clotube/1.0` |
| **Image Loading** | Coil Compose 2.7.0 |
| **Google Identity** | AndroidX Credentials 1.3.0 • Google ID 1.1.1 • Drive API v3 |

---

## Building the Project

Compile the debug APK:
```bash
./gradlew assembleDebug
```

The resulting artifact is generated at:
`app/build/outputs/apk/debug/app-debug.apk`
