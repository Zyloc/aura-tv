# Aura TV

**An open-source, local-first AI launcher for Android TV.**

Aura TV replaces your ad-filled stock TV home screen with a calm, ultra-light launcher that remembers what you watch across apps, learns what you like and skip, refreshes recommendations automatically, and lets you talk to your TV — from spoiler-free *"Catch me up"* recaps to *"Play it"* voice discovery.

https://github.com/user-attachments/assets/d993c287-e06e-4972-ada3-58f4b044038e

---

## Key Features

- **Calm, Ad-Free Android TV Launcher** — Unifies your installed streaming apps (Netflix, Prime Video, Apple TV+, Disney+, Max, Hulu, YouTube, and regional apps worldwide) in one fast home screen.
- **Intelligent On-Device Memory** — Naturally remembers what you watch across apps via Android TV's `MediaSession` & `UsageStats` APIs, plus your ratings, skips, and spoken preferences — stored locally in SQLite on your TV.
- **Auto-Refreshing Taste Curation** — Combines your watch history, likes/skips, and live cross-OTT trending into *Tonight’s Picks* and *For You* rails with a 4-refresh / 48-hour no-repeat cooldown.
- **Live Spoiler-Free "Catch Me Up"** — Because Aura knows the exact episode and minute you stopped on, ask *"Catch me up"* for a live translucent lower-third recap right over your screen with zero spoilers.
- **Conversational AI Companion** — Ditch the TV keyboard. Ask for a mood, compare picks, ask follow-ups, remember preferences (*"I love slow-burn sci-fi"*), and say *"Play it"* to deep-link straight into the native app.
- **Local-First & Flexible AI** — Zero ads, zero telemetry. Bring your own **Gemini API key** (or connect a local/OpenAI-compatible LLM via your companion host).
- **Ultra-Light Native Footprint** — Zero bloated frameworks; pure native Android Java designed to run effortlessly on any Android TV or streaming stick.

---

## Setup

### Option A: 60-Second Install (Pre-Built APK — No Build Required)

1. Download **`aura-tv-v1.0.0.apk`** from **[GitHub Releases](https://github.com/Zyloc/aura-tv/releases/latest)** and sideload it onto your Android TV (using the *Downloader* app, USB, or `adb install -r aura-tv-v1.0.0.apk`).
2. Open **Aura TV** → `☰` → **Phone Remote (QR)** and scan with your phone.
3. Tap the **Setup** tab on your phone and paste your **Gemini API key** (or local **Ollama** URL) — no TV keyboard typing required!

*(Optional: for hardware mic interception inside Netflix/Prime/Hotstar and setting Aura as your default launcher on locked OEM TVs, run the 1-time ADB permission commands listed on the Release page.)*

---

### Option B: Build from Source

#### Prerequisites

- **Android TV / Google TV** device with **Developer Options → Network ADB Debugging** enabled
- **Java 11+** and **Android SDK** (`build-tools`, `platforms`, `platform-tools` / `adb`)
- A **Gemini API key** (free tier works — get one at [Google AI Studio](https://aistudio.google.com/)) or a local LLM endpoint
- *(Optional)* Python 3.10+ if you want to run the companion web/phone sync server

---

### 1. Clone & Configure `.env`

```bash
git clone https://github.com/Zyloc/aura-tv.git
cd aura-tv
cp .env.example .env
```

Edit `.env`:

```env
# AI Provider (Required for conversational search, recaps & smart curation)
GEMINI_API_KEY=your_gemini_api_key_here

# Your Android TV's local IP address (Settings -> Network on your TV)
AURA_TV_IP=192.168.1.100
AURA_TV_PORT=8765

# Optional: Override auto-detected 2-letter country code (e.g. US, GB, IN, JP, DE)
# Leave unset to auto-detect from your TV's timezone/locale
# AURA_REGION=US

# Optional: Companion Host / Phone Server URL (TV runs 100% standalone without this)
AURA_HOST_URL=http://192.168.1.20:7070

# Household Profile Display Names
AURA_DEFAULT_PROFILE=member1
AURA_MEMBER1_NAME=Member 1
AURA_PARTNER_NAME=Partner
AURA_MEMBER3_NAME=Member 3
AURA_KIDS_NAME=Kids
AURA_FAMILY_NAME=Family
```

---

### 2. Build & Deploy to Your Android TV

```bash
chmod +x scripts/build_apk.sh scripts/deploy_tv.sh
./scripts/deploy_tv.sh
```

What `deploy_tv.sh` does:
1. Bundles your `.env` into a temporary untracked APK asset (`assets/aura.env`) so the TV works standalone even when your computer is off.
2. Compiles and signs `probe/aura-probe.apk` using your local Android SDK (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).
3. Connects to `<AURA_TV_IP>:5555`, installs the APK, grants `READ_TV_LISTINGS`, `RECORD_AUDIO`, `GET_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, and `NotificationListener` permissions, and sets Aura TV as the home launcher.

> **Build APK only (without deploying):**
> ```bash
> ./scripts/build_apk.sh
> ```
> **Update API key or settings at runtime (no rebuild needed):**
> ```bash
> curl "http://<AURA_TV_IP>:8765/api/config?gemini_api_key=<YOUR_KEY>&region_country=US"
> ```

---

### 3. Optional: Run the Companion Host Server

Aura TV runs **100% standalone** on your TV (with an embedded HTTP server & QR phone remote on port `8765`). If you also want a desktop/phone dashboard and backup sync on port `7070`:

```bash
pip install -r requirements.txt
python3 app.py
```

Open `http://localhost:7070` — or scan the QR code inside Aura TV's Settings to open the built-in phone remote directly at `http://<AURA_TV_IP>:8765`.

---

## How to Use

- **Home Screen & Moods** — Browse *Tonight’s Picks*, your installed apps, *Continue Watching*, and auto-refreshing personalized rails. Use mood/duration filters or hit **Refresh** for a completely fresh slate.
- **Talk to Aura (Voice & Search)** — Open the Aura orb with your remote or phone companion and speak naturally:
  - *"Dark sci-fi like Severance under an hour"*
  - *"Tell me more about the second one"* → *"Play it"*
  - *"What's trending on Netflix right now?"*
  - *"Remember I love slow-burn mysteries and skip horror"*
  - *"Surprise me with something light tonight"*
- **Catch Me Up (Spoiler-Free Recaps)** — While returning to a show you paused days ago, ask *"Catch me up"* to get a calm, lower-third recap up to your exact episode and minute.
- **Rate & Refine** — Mark shows as watched, rate what you loved/skipped, or switch household profiles anytime from the top bar.

---

## Project Structure

```text
aura-tv/
├── probe/                          # Native Android TV Launcher (com.aura.tvprobe)
│   ├── src/com/aura/tvprobe/
│   │   ├── app/                    # Activity, MediaProbeService, DeepLinkLauncher, Executors
│   │   ├── db/                     # Local SQLite store, UserEngagementLedger, UserMemoryStore
│   │   ├── engine/                 # Agent orchestrator, tool registry, TasteCuratedSlateEngine,
│   │   │                           # LiveTrendingCollector, RegionProviderRegistry, JustWatchGraph
│   │   ├── net/                    # Embedded HTTP API (:8765), mDNS discovery, SyncManager
│   │   └── ui/                     # D-pad Home, Search Overlay, Lower-Third Subtitles, QR Remote
│   └── test/                       # 148 unit & integration tests (JUnit 4)
├── scripts/
│   ├── build_apk.sh                # Pure Android SDK build (no Gradle required)
│   ├── deploy_tv.sh                # ADB install, permission grants & home-launcher setup
│   └── run_tests.sh                # Compiles APK + runs all 148 unit & integration tests
├── app.py / recommender.py / ...   # Optional FastAPI companion & backup sync server
└── .env.example                    # Environment configuration template
```

---

## Contributing

Contributions, bug reports, regional provider mappings, and local-LLM adapters are very welcome!

### Development Workflow

1. Fork the repo and create a feature branch (`git checkout -b feat/your-feature`).
2. Keep changes **modular and focused** — follow SOLID principles, avoid hardcoding personal/region assumptions, and keep Java methods small and readable.
3. Add unit or integration tests in `probe/test/` covering any new behavior or bug fix.
4. Run the full build and test suite before opening a PR:

```bash
bash scripts/run_tests.sh
```

All **148+ tests** must pass with zero regressions and `probe/aura-probe.apk` must compile cleanly.

### Good First Contributions

- Adding streaming app package IDs & deep-link templates for additional regions in `RegionProviderRegistry.java` and `MediaDeepLinkLauncher.java`
- Adding OpenAI-compatible / Ollama local LLM client support alongside `GeminiToolClient.java`
- Improving D-pad focus ergonomics or adding localized UI strings

### Pull Request Checklist

- [ ] `bash scripts/run_tests.sh` passes locally
- [ ] No API keys, personal IPs, or `.env` files committed
- [ ] Region-neutral defaults (uses `RegionProviderRegistry` instead of hardcoded countries/apps)

---

## License

See [LICENSE](LICENSE).
