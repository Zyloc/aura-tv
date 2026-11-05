# Aura TV — Conversational AI Concierge & Media Interceptor for Android TV

Aura TV is a native Android TV app (`com.aura.tvprobe`) and optional companion Host/Phone sync server (`FastAPI`) that:
- Intercepts what you watch on your Android TV via `MediaSessionManager`, `UsageStatsManager`, and `TvProvider` (`watch_next_program` / `preview_program`).
- Provides a **Material Design 3 + IMDb-style** interface right on your TV with high-contrast D-pad navigation.
- Supports **multi-turn conversational voice/text search** (`VoiceConcierge`) and **persistent filter chips** (`Movies`, `Series`, `<30m`, `Sci-Fi`, `Horror/Thriller`, `Comedy`, `Drama`, `Action`, `Disney+ Hotstar`, `Netflix`, `Prime Video`).
- Launches titles directly inside their **native Android TV streaming apps** (`in.startv.hotstar`, `com.netflix.ninja`, `com.amazon.amazonvideo.livingroom`, `com.apple.atve.androidtv.appletv`, `com.sonyliv`, `com.graymatrix.did`, `tv.airtel.xstream.tvapp`).
- Runs **100% standalone on the TV** with local SQLite storage (`aura_tv_local.db`) and a 1-hour smart recommendation cache, while syncing bi-directionally with the companion Host Server whenever online.

---

## Quick Start

### 1. Configure `.env`
Copy `.env.example` to `.env` and fill in your Gemini API key and Android TV IP address:

```bash
cp .env.example .env
```

Edit `.env`:
```env
GEMINI_API_KEY=your_gemini_api_key_here
AURA_TV_IP=192.168.1.100
AURA_TV_PORT=8765
AURA_HOST_URL=http://192.168.1.20:7070
AURA_DEFAULT_PROFILE=member1
AURA_MEMBER1_NAME=Member 1
AURA_PARTNER_NAME=Partner
AURA_MEMBER3_NAME=Member 3
```

---

### 2. Build & Install the Android TV App
Make sure **ADB Debugging** (Network ADB on port `5555`) is enabled on your Android TV, then run:

```bash
chmod +x scripts/build_apk.sh scripts/deploy_tv.sh
./scripts/deploy_tv.sh
```

What `build_apk.sh` and `deploy_tv.sh` do:
1. Bundle your `.env` into an untracked APK asset (`assets/aura.env`) so the TV app works standalone even when your computer is off.
2. Compile and sign `probe/aura-probe.apk` using your local Android SDK (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).
3. Install the APK onto your TV, grant `READ_TV_LISTINGS`, `RECORD_AUDIO`, and `MediaSession` listener permissions, and launch `AuraActivity`.

You can also update the TV's API key or host URL remotely at any time without rebuilding:
```bash
curl "http://<AURA_TV_IP>:8765/api/config?gemini_api_key=<YOUR_KEY>&mac_host=http://<HOST_IP>:7070"
```

---

### 3. Run the Optional Companion Host Server (Mac / Linux / Phone)
The TV app runs its own local server on port `8765`, and you can also run the companion FastAPI server on port `7070` for cross-device web control and backup sync:

```bash
pip install -r requirements.txt
python3 app.py
```

Open `http://localhost:7070` (or `http://<AURA_TV_IP>:8765` directly against the TV).

---

## Project Structure

- `probe/` — Native Android TV application (`com.aura.tvprobe`)
  - `AuraActivity.java` — Material Design 3 Dark + IMDb Gold D-pad UI, persistent filters, conversational session flow, and household profile dropdown
  - `AiRecommender.java` — Gemini 3 Flash-Lite recommendation engine + IMDb Suggestion & TVMaze poster/metadata resolver
  - `VoiceConcierge.java` — Multi-turn conversational recommender with watched-item exclusion and persistent filter enforcement
  - `AuraDb.java` — Local SQLite database (`aura_tv_local.db`) with 1-hour smart cache and native streaming package resolver
  - `TvOverlayManager.java` — Native Android TV deep-link & package launcher + "Up Next" toast overlay
  - `MediaProbeService.java` — Background `NotificationListenerService` + embedded HTTP JSON API on `:8765`
  - `SyncManager.java` — Bi-directional state sync between TV and Host server
- `app.py`, `interceptor.py`, `recommender.py`, `resolvers.py`, `db.py`, `config.py` — Companion FastAPI Host & Sync Server
- `scripts/build_apk.sh` & `scripts/deploy_tv.sh` — Automated APK build and Android TV deployment scripts
