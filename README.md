# Aura TV

**An open-source, local-first AI launcher for Android TV.**

Aura TV replaces your ad-filled stock TV home screen with a calm, ultra-light launcher that remembers what you watch across apps, learns what you like and skip, refreshes recommendations automatically, and lets you talk to your TV — from spoiler-free *"Catch me up"* recaps to *"Play it"* voice discovery.

https://github.com/user-attachments/assets/d993c287-e06e-4972-ada3-58f4b044038e

---

## Key Features

- **Calm, Ad-Free Android TV Launcher** — Unifies your installed streaming apps (Netflix, Prime Video, Apple TV+, Disney+, Max, Hulu, YouTube, and regional apps worldwide) in one clean home screen with a streamlined sidebar and instant HDMI/profile switching.
- **Intelligent On-Device Memory** — Remembers what you watch across apps via Android TV's `MediaSession` & `UsageStats` APIs, plus your ratings, skips, and spoken preferences — stored locally in SQLite on your TV.
- **Auto-Refreshing Taste Curation & Mood Morphing** — Combines your watch history, likes/skips, and live cross-OTT trending into *Tonight’s Picks* and *For You* rails with a 4-refresh / 48-hour no-repeat cooldown. Say *"Make tonight cozy"* or *"Switch to dark thrillers"* to dynamically morph your entire home screen on the fly.
- **Movie Night Blend (Multi-Profile Mixer)** — Watching with a partner, family, or friends? Blend two or more household taste profiles in one click or voice prompt to generate a consensus slate everyone will enjoy.
- **Live Spoiler-Free "Catch Me Up" & Episode Vibe Check** — Because Aura knows the exact episode and minute you stopped on, ask *"Catch me up"* for a translucent lower-third recap right over your screen with zero spoilers, or ask *"Is this episode slow or intense?"* before pressing play.
- **4-Tab Phone QR Companion (No App Required)** — Scan the TV's QR code from any phone on your Wi-Fi to unlock a mobile web companion with **Co-Pilot** (voice/text search, live recap, mood morph, Movie Night Blend), **Remote** (D-pad, playback, volume, HDMI), **Discover** (instant app launcher, quick rating, RAM Boost), and **Setup** (paste API keys & switch AI providers at runtime).
- **Runtime AI Provider Switching (Cloud, Local, or Hybrid)** — Zero ads, zero telemetry. Switch live between **Gemini Cloud**, **Local LLM Only (Ollama / LM Studio)**, or **Auto (Local → Cloud Fallback)** right from TV Settings or your Phone Companion — no rebuild or `.env` editing needed.
- **Ultra-Light Native Footprint** — Zero bloated frameworks; pure native Android Java (~10 MB RAM, <1% idle CPU) engineered to fly on any Android TV or streaming stick.

---

## Which LLM Provider Should I Pick?

Aura TV lets you switch AI engines **at runtime** inside **TV → Aura Settings → 5. AI Provider** or **Phone QR Companion → Setup tab**:

| Mode | Best For | Speed & Quality | Privacy / Offline | Requirements |
| :--- | :--- | :--- | :--- | :--- |
| **`Gemini Cloud`** *(Recommended)* | Daily living-room use, best tool-calling, nuanced recaps, Movie Night Blend, & home mood morphing | **Fast (~1–2s) & highest accuracy** across all 17 AI tools | Cloud API (free tier available, zero telemetry from Aura) | Free Gemini API key from [Google AI Studio](https://aistudio.google.com/) |
| **`Auto (Local → Cloud)`** | Users with a laptop/NAS running Ollama or LM Studio on LAN who want local-first speed with cloud safety net | Local speed when host is awake; seamless Gemini fallback if host sleeps | Local LAN first; falls back to Gemini only if local endpoint is unreachable | Local LLM URL + Gemini API key |
| **`Local LLM Only`** | 100% offline / air-gapped LAN privacy with zero cloud calls | Depends on host GPU/RAM (`qwen2.5:3b` or `llama3.2:3b` recommended for tool-calling) | **100% local LAN** — never sends prompts to Google | Ollama (`OLLAMA_HOST=0.0.0.0:11434`) or LM Studio OpenAI-compatible server on your LAN |

> **Tip for Ollama / LM Studio users:** Use a tool-calling capable model such as **`qwen2.5:3b`**, **`qwen2.5:7b`**, or **`llama3.2:3b`**. When exposing Ollama on macOS/Linux to your TV over Wi-Fi, start it with `OLLAMA_HOST=0.0.0.0:11434 ollama serve` and enter `http://<YOUR_PC_IP>:11434/v1` in the Phone Companion **Setup** tab.

---

## Setup

### Option A: 60-Second Install (Pre-Built APK — No Build Required)

1. Download **`aura-tv-v1.0.0.apk`** from **[GitHub Releases](https://github.com/Zyloc/aura-tv/releases/latest)** and sideload it onto your Android TV (using the *Downloader* app, USB, or `adb install -r aura-tv-v1.0.0.apk`).
2. Open **Aura TV** → `☰` → **Phone Remote (QR)** and scan with your phone.
3. Open the **Setup** tab on your phone, choose your **AI Provider** (`Gemini Cloud`, `Auto`, or `Local LLM Only`), and paste your **Gemini API key** or **Local LLM URL** — zero TV keyboard typing required!

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
> **Update API key, AI provider mode, or settings at runtime (no rebuild needed):**
> ```bash
> curl "http://<AURA_TV_IP>:8765/api/config?llm_provider=gemini&gemini_api_key=<YOUR_KEY>&region_country=US"
> # Or switch to Local LLM Only (Ollama / LM Studio):
> curl "http://<AURA_TV_IP>:8765/api/config?llm_provider=local&local_llm_url=http://<PC_IP>:11434/v1&local_llm_model=qwen2.5:3b"
> ```

---

### 3. Optional: Run the Companion Host Server

Aura TV runs **100% standalone** on your TV (with an embedded HTTP server & 4-tab QR Phone Companion on port `8765`). If you also want a desktop/phone dashboard and backup sync on port `7070`:

```bash
pip install -r requirements.txt
python3 app.py
```

Open `http://localhost:7070` — or scan the QR code inside Aura TV's sidebar to open the built-in 4-tab Phone Companion directly at `http://<AURA_TV_IP>:8765`.

---

## How to Use

- **Home Screen & Dynamic Mood Morphing** — Browse *Tonight’s Picks*, your installed apps, *Continue Watching*, and auto-refreshing personalized rails. Say *"Make tonight cozy"* or *"Switch to mind-bending sci-fi"* to reshape the home screen on the fly, or hit **Refresh** for a fresh slate.
- **Talk to Aura (Voice & Search)** — Open the Aura orb from the sidebar, remote, or Phone Companion and speak naturally:
  - *"Dark sci-fi like Severance under an hour"*
  - *"Tell me more about the second one"* → *"Play it"*
  - *"Blend my taste with Partner for movie night"*
  - *"What's trending on Netflix right now?"*
  - *"Remember I love slow-burn mysteries and skip horror"*
  - *"Surprise me with something light tonight"*
- **Catch Me Up & Episode Vibe Check** — Returning to a show you paused days ago? Ask *"Catch me up"* for a calm, lower-third recap up to your exact episode and minute, or ask *"What's the vibe of this episode?"* for a spoiler-free mood check.
- **4-Tab Phone QR Companion** — Scan the QR code in Aura's sidebar to use your phone as a **Co-Pilot** (voice/text AI, recaps, blend, mood morph), **Remote** (D-pad, media keys, volume, HDMI), **Discover** (launch TV apps, quick-rate titles, 1-tap RAM Boost), and **Setup** (switch AI providers & save keys at runtime).
- **Rate, Blend & Switch Profiles** — Mark shows as watched, rate what you loved/skipped, blend profiles for **Movie Night**, or switch household profiles anytime from the left sidebar.

---

## Project Structure

```text
aura-tv/
├── probe/                          # Native Android TV Launcher (com.aura.tvprobe)
│   ├── src/com/aura/tvprobe/
│   │   ├── agent/                  # 17-tool Agent orchestrator, GeminiToolClient, LocalLlmClient,
│   │   │                           # BlendProfilesTool, MorphHomeMoodTool, ExplainShowTool, etc.
│   │   ├── app/                    # AuraActivity, DeepLinkLauncher, Executors, SystemResourceMonitor
│   │   ├── db/                     # Local SQLite store, UserEngagementLedger, UserMemoryStore
│   │   ├── engine/                 # TasteCuratedSlateEngine, HouseholdBlendEngine,
│   │   │                           # LiveTrendingCollector, RegionProviderRegistry, JustWatchGraph
│   │   ├── service/                # MediaProbeService (:8765), PhoneCompanionController, SyncManager
│   │   └── ui/                     # D-pad Home, Sidebar, Settings, Lower-Third Subtitles, 4-Tab WebUI
│   └── test/                       # 155+ unit & integration tests (JUnit 4)
├── scripts/
│   ├── build_apk.sh                # Pure Android SDK build (no Gradle required)
│   ├── deploy_tv.sh                # ADB install, permission grants & home-launcher setup
│   └── run_tests.sh                # Compiles APK + runs all 155+ unit & integration tests
├── app.py / recommender.py / ...   # Optional FastAPI companion & backup sync server
└── .env.example                    # Environment configuration template
```

---

## Contributing

Contributions, bug reports, regional provider mappings, and new AI tools are very welcome!

### Development Workflow

1. Fork the repo and create a feature branch (`git checkout -b feat/your-feature`).
2. Keep changes **modular and focused** — follow SOLID principles, avoid hardcoding personal/region assumptions, and keep Java methods small and readable.
3. Add unit or integration tests in `probe/test/` covering any new behavior or bug fix.
4. Run the full build and test suite before opening a PR:

```bash
bash scripts/run_tests.sh
```

All **155+ tests** must pass with zero regressions and `probe/aura-probe.apk` must compile cleanly.

### Good First Contributions

- Adding streaming app package IDs & deep-link templates for additional regions in `RegionProviderRegistry.java` and `MediaDeepLinkLauncher.java`
- Expanding local LLM prompt templates & tool-calling adapters in `LocalLlmClient.java`
- Improving D-pad focus ergonomics or adding localized UI strings

### Pull Request Checklist

- [ ] `bash scripts/run_tests.sh` passes locally
- [ ] No API keys, personal IPs, or `.env` files committed
- [ ] Region-neutral defaults (uses `RegionProviderRegistry` instead of hardcoded countries/apps)

---

## License

See [LICENSE](LICENSE).
