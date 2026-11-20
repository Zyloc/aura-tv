import os
from pathlib import Path
from typing import Dict

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "aura_watch.db"


def load_dotenv_file(env_path: Path) -> None:
    if not env_path.is_file():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        os.environ.setdefault(key.strip(), val.strip().strip("'\""))


load_dotenv_file(BASE_DIR / ".env")

TV_IP = os.getenv("AURA_TV_IP", "127.0.0.1")
TV_PORT = int(os.getenv("AURA_TV_PORT", "8765"))
TV_PROBE_URL = f"http://{TV_IP}:{TV_PORT}"

HOST_URL = os.getenv("AURA_HOST_URL", "")
PHONE_URL = os.getenv("AURA_PHONE_URL", "")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
REGION_COUNTRY = os.getenv("AURA_REGION_COUNTRY", "US").strip().upper() or "US"
REGION_LANG = os.getenv("AURA_REGION_LANG", "en").strip().lower() or "en"

DEFAULT_PROFILE = os.getenv("AURA_DEFAULT_PROFILE", "member1")
PROFILE_LABELS: Dict[str, str] = {
    "member1": os.getenv("AURA_MEMBER1_NAME", "Member 1"),
    "partner": os.getenv("AURA_PARTNER_NAME", "Partner"),
    "member3": os.getenv("AURA_MEMBER3_NAME", "Member 3"),
    "kids": os.getenv("AURA_KIDS_NAME", "Kids"),
    "family": os.getenv("AURA_FAMILY_NAME", "Family"),
}

GEMINI_MODELS = [
    "gemini-flash-lite-latest",
    "gemini-flash-latest",
]

APP_LABELS = {
    "com.amazon.amazonvideo.livingroom": "Prime Video",
    "com.netflix.ninja": "Netflix",
    "org.smarttube.stable": "SmartTube",
    "com.google.android.youtube.tv": "YouTube",
    "in.startv.hotstar": "Disney+ Hotstar",
    "com.apple.atve.androidtv.appletv": "Apple TV+",
    "tv.airtel.xstream.tvapp": "Airtel Xstream",
    "com.stremio.one": "Stremio",
    "com.graymatrix.did": "ZEE5",
    "com.mxtech.videoplayer.television": "MX Player",
    "org.videolan.vlc": "VLC",
    "com.jio.media.stb.ondemand": "JioCinema",
    "com.sonyliv": "SonyLIV",
}
