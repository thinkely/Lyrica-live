# Lyrica Live (Android)

> **Local-first Android companion app that detects currently playing music via Android's MediaSession framework and displays real-time synchronized lyrics in a persistent notification and distraction-free lock screen experience.**

---

## 🎧 Overview

Lyrica Live pairs native Android media monitoring with an embedded **Lyrica Python lyrics engine** (via [Chaquopy](https://chaquo.com/chaquopy/)) to deliver fast, synchronized lyrics directly on your device.

```
Music Application (Spotify, Apple Music, YT Music, local players)
       ↓
Android MediaSession
       ↓
LyricaMediaSessionListenerService (NotificationListenerService)
       ↓
MediaSessionMonitor
       ↓
TrackIdentityResolver  →  [StableTrackKey]
       ↓
LocalLyricsCache (L1 Memory / L2 Disk)
       │
       ├── HIT  → Lyrics displayed immediately (<5ms)
       │
       └── MISS
              ↓
        ProviderRouter
       (Eligibility → CircuitBreaker → RateLimiter → AdaptiveRanker)
              ↓
        Chaquopy Bridge (Python Core)
              ↓
    [LRCLIB / LRCMux / Apple Music / Genius / Hosted Lyrica]
              ↓
       LyricsSyncEngine (Line, Word, Syllable precision)
              ↓
   ┌───────────────────────┴───────────────────────┐
   ↓                                               ↓
LyricsNotificationManager                  FullLyricsActivity
(Live Notification & Lock Screen)        (Auto-scroll & Tap-to-Seek)
```

---

## ✨ Features (v1.0 Frozen Scope)

- **Native MediaSession Monitoring**: Automatically detects playing music across Spotify, Apple Music, YouTube Music, local players, and more.
- **Stable Track Identity**: Deduplicates metadata variations and prevents redundant fetches during continuous playback.
- **Embedded Python Lyrics Core**: Runs local provider fetchers without background HTTP/Flask server overhead.
- **Tiered Provider Network**:
  - **LRCLIB**: Fast synchronized line-level lyrics.
  - **LRCMux**: High-quality word-level Musixmatch synchronized lyrics.
  - **Apple Music**: TTML syllable/word/line precision lyrics (with authenticated developer/user tokens).
  - **Genius**: Plain lyrics support with user API token.
  - **Hosted Lyrica**: Configurable fallback server.
- **Adaptive Provider Ranking & Circuit Breaker**: Tracks EWMA latency and failure rates; trips failing providers to avoid latency spikes.
- **Rate Limiting & Aggressive Local Caching**: Protects community APIs and enables offline lyrics for previously played tracks.
- **Tap-to-Seek Support**: Tapping any lyric line in the notification or full screen view seeks the active music player.
- **Privacy-First & Secure**: Keystore-backed AES-256-GCM encrypted token storage. Zero tracking.

---

## 🛠️ Project Structure

```
Lyrica-Live/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/live/lyrica/app/
│   │   │   │   ├── core/
│   │   │   │   │   ├── cache/          # LocalLyricsCache (L1/L2 disk)
│   │   │   │   │   ├── engine/         # LyricsSyncEngine (Line/Word/Syllable)
│   │   │   │   │   ├── logging/        # LyricaLogger (auto-redacting)
│   │   │   │   │   ├── mediasession/   # ListenerService & Monitor
│   │   │   │   │   ├── model/          # LyricsDocument & Track models
│   │   │   │   │   ├── notification/   # LyricsNotificationManager
│   │   │   │   │   ├── providers/      # ProviderRouter, Ranker, Breaker, Bridge
│   │   │   │   │   └── resolver/       # TrackIdentityResolver
│   │   │   │   ├── security/           # SecureTokenStorage (Keystore AES)
│   │   │   │   └── ui/                 # MainActivity & FullLyricsActivity
│   │   │   ├── python/                 # Embedded Lyrica Python Engine
│   │   │   │   ├── sources/            # lrclib, lrcmux, genius, apple_music, hosted
│   │   │   │   ├── normalizer.py       # Metadata normalizer & tag cleaner
│   │   │   │   ├── lyrica_bridge.py    # Direct Chaquopy entrypoint
│   │   │   │   └── tests/              # Python test suite
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── .github/workflows/
│   └── build-apk.yml                   # CI: automated APK compilation & test runner
├── FUTURE.md                           # Post-V1 roadmap
└── README.md
```

---

## 🚀 Building the Project

### Local Python Unit Tests
```bash
python -m unittest discover -s android/app/src/main/python/tests
```

### Build APK via Gradle
```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
The output APK is generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Automated CI / GitHub Actions
Pushing to the repository or opening a PR triggers `.github/workflows/build-apk.yml`, running tests and compiling the debug APK artifact for download.
