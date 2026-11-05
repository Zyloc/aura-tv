import json
import re
import sqlite3
import time
from typing import Dict, List, Optional
from config import DB_PATH, DEFAULT_PROFILE

RECO_TTL_SEC = 3600


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def normalize_key(raw: str) -> str:
    if not raw:
        return "unknown"
    return re.sub(r"[^a-z0-9:._-]", "", raw.strip().lower())


def init_db() -> None:
    with get_conn() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS watch_history (
                media_key TEXT PRIMARY KEY,
                profile_id TEXT DEFAULT 'member1',
                app_pkg TEXT,
                app_name TEXT,
                title TEXT,
                series_title TEXT,
                season INTEGER,
                episode INTEGER,
                synopsis TEXT,
                poster_url TEXT,
                genres TEXT,
                runtime_min INTEGER DEFAULT 45,
                launch_uri TEXT,
                position_sec INTEGER,
                duration_sec INTEGER,
                first_seen INTEGER,
                last_seen INTEGER
            )
            """
        )
        for col_sql in (
            "ALTER TABLE watch_history ADD COLUMN profile_id TEXT DEFAULT 'member1'",
            "ALTER TABLE watch_history ADD COLUMN runtime_min INTEGER DEFAULT 45",
            "ALTER TABLE watch_history ADD COLUMN launch_uri TEXT DEFAULT ''",
        ):
            try:
                conn.execute(col_sql)
            except Exception:
                pass
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS profile_recos (
                profile_id TEXT PRIMARY KEY,
                payload_json TEXT,
                updated_at INTEGER
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS watched_items (
                title_key TEXT PRIMARY KEY,
                payload_json TEXT,
                updated_at INTEGER
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS kv_settings (
                k TEXT PRIMARY KEY,
                v TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS gti_cache (
                gti TEXT PRIMARY KEY,
                payload_json TEXT,
                updated_at INTEGER
            )
            """
        )


def set_setting(k: str, v: str) -> None:
    with get_conn() as conn:
        conn.execute("INSERT OR REPLACE INTO kv_settings (k, v) VALUES (?, ?)", (k, v))


def get_setting(k: str, default: str = "") -> str:
    with get_conn() as conn:
        row = conn.execute("SELECT v FROM kv_settings WHERE k = ?", (k,)).fetchone()
        return row["v"] if row else default


def upsert_watch_item(item: Dict) -> None:
    now = int(time.time())
    raw_key = item.get("media_key") or f"{item.get('app_pkg','')}:{item.get('series_title','')}:{item.get('title','')}"
    mkey = normalize_key(raw_key)
    dur_sec = int(item.get("duration_sec") or 0)
    runtime_min = int(item.get("runtime_min") or (max(1, dur_sec // 60) if dur_sec > 0 else 45))
    with get_conn() as conn:
        conn.execute(
            """
            INSERT INTO watch_history (
                media_key, profile_id, app_pkg, app_name, title, series_title,
                season, episode, synopsis, poster_url, genres, runtime_min,
                launch_uri, position_sec, duration_sec, first_seen, last_seen
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(media_key) DO UPDATE SET
                position_sec = CASE WHEN excluded.position_sec > 0 THEN excluded.position_sec ELSE watch_history.position_sec END,
                duration_sec = CASE WHEN excluded.duration_sec > 0 THEN excluded.duration_sec ELSE watch_history.duration_sec END,
                runtime_min = CASE WHEN excluded.runtime_min > 0 THEN excluded.runtime_min ELSE watch_history.runtime_min END,
                synopsis = CASE WHEN excluded.synopsis != '' THEN excluded.synopsis ELSE watch_history.synopsis END,
                poster_url = CASE WHEN excluded.poster_url != '' THEN excluded.poster_url ELSE watch_history.poster_url END,
                genres = CASE WHEN excluded.genres != '' THEN excluded.genres ELSE watch_history.genres END,
                last_seen = excluded.last_seen
            """,
            (
                mkey,
                item.get("profile_id", DEFAULT_PROFILE),
                item.get("app_pkg", ""),
                item.get("app_name", ""),
                item.get("title", ""),
                item.get("series_title", ""),
                item.get("season") or 0,
                item.get("episode") or 0,
                item.get("synopsis", ""),
                item.get("poster_url", ""),
                item.get("genres", ""),
                runtime_min,
                item.get("launch_uri", ""),
                int(item.get("position_sec") or 0),
                dur_sec,
                now,
                int(item.get("last_seen") or now),
            ),
        )


def list_watch_history(limit: int = 1000) -> List[Dict]:
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT * FROM watch_history ORDER BY last_seen DESC LIMIT ?", (limit,)
        ).fetchall()
        return [dict(r) for r in rows]


def resolve_app_package(app_or_pkg: str) -> str:
    if not app_or_pkg:
        return ""
    s = app_or_pkg.strip().lower()
    if "." in s and " " not in s:
        return app_or_pkg.strip()
    if "hotstar" in s or "disney" in s or "jio" in s:
        return "in.startv.hotstar"
    if "netflix" in s:
        return "com.netflix.ninja"
    if "prime" in s or "amazon" in s:
        return "com.amazon.amazonvideo.livingroom"
    if "apple" in s:
        return "com.apple.atve.androidtv.appletv"
    if "sonyliv" in s or "sony" in s:
        return "com.sonyliv"
    if "zee5" in s or "zee" in s:
        return "com.graymatrix.did"
    if "airtel" in s or "xstream" in s:
        return "tv.airtel.xstream.tvapp"
    if "stremio" in s:
        return "com.stremio.one"
    if "mx" in s:
        return "com.mxtech.videoplayer.television"
    if "youtube" in s or "smarttube" in s:
        return "org.smarttube.stable"
    return ""


def filter_out_watched(items: List[Dict]) -> List[Dict]:
    watched = get_watched_keys()
    out = []
    for x in items or []:
        if not isinstance(x, dict):
            continue
        title = x.get("series_title") or x.get("title") or ""
        if normalize_title_key(title) in watched:
            continue
        app_val = x.get("app") or x.get("badge") or ""
        pkg = x.get("launch_pkg") or ""
        if not pkg or pkg == "org.smarttube.stable":
            resolved = resolve_app_package(app_val)
            if resolved:
                x["launch_pkg"] = resolved
        if (x.get("launch_uri") or "").startswith("https://www.youtube.com/results") and x.get("launch_pkg") != "org.smarttube.stable":
            x["launch_uri"] = ""
        out.append(x)
    return out


def save_profile_recos(profile_id: str, recos: List[Dict]) -> None:
    cleaned = filter_out_watched(recos)
    with get_conn() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO profile_recos (profile_id, payload_json, updated_at) VALUES (?, ?, ?)",
            (profile_id, json.dumps(cleaned), int(time.time())),
        )


def normalize_title_key(title: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (title or "").strip().lower())


def mark_title_watched(item: Dict, watched: bool = True) -> None:
    title = item.get("series_title") or item.get("title") or ""
    key = normalize_title_key(title)
    if not key:
        return
    with get_conn() as conn:
        if not watched:
            conn.execute("DELETE FROM watched_items WHERE title_key = ?", (key,))
            return
        copy = dict(item)
        copy["watched"] = True
        copy["state"] = "WATCHED ✓"
        conn.execute(
            "INSERT OR REPLACE INTO watched_items (title_key, payload_json, updated_at) VALUES (?, ?, ?)",
            (key, json.dumps(copy), int(time.time())),
        )


def get_watched_keys() -> set:
    with get_conn() as conn:
        rows = conn.execute("SELECT title_key FROM watched_items").fetchall()
        return {r["title_key"] for r in rows}


def list_manually_watched() -> List[Dict]:
    with get_conn() as conn:
        rows = conn.execute("SELECT payload_json FROM watched_items ORDER BY updated_at DESC").fetchall()
        return [json.loads(r["payload_json"]) for r in rows]


def get_active_filters(profile_id: str) -> List[str]:
    raw = get_setting(f"filters_{profile_id}", "[]")
    try:
        arr = json.loads(raw)
        return [str(x) for x in arr if x] if isinstance(arr, list) else []
    except Exception:
        return []


def set_active_filters(profile_id: str, filters: List[str]) -> List[str]:
    cleaned = [str(x).strip() for x in (filters or []) if str(x).strip()]
    set_setting(f"filters_{profile_id}", json.dumps(cleaned))
    return cleaned


def toggle_active_filter(profile_id: str, filter_val: str) -> List[str]:
    if not filter_val or filter_val.lower() in ("clear", "all"):
        return set_active_filters(profile_id, [])
    cur = get_active_filters(profile_id)
    if filter_val in cur:
        cur.remove(filter_val)
    else:
        cur.append(filter_val)
    return set_active_filters(profile_id, cur)


def save_voice_session(profile_id: str, session: Dict) -> None:
    if isinstance(session, dict) and "results" in session:
        session = dict(session)
        session["results"] = filter_out_watched(session.get("results") or [])
    set_setting(f"voice_session_{profile_id}", json.dumps(session))


def get_voice_session(profile_id: str) -> Dict:
    raw = get_setting(f"voice_session_{profile_id}", "")
    if raw:
        try:
            sess = json.loads(raw)
            if isinstance(sess, dict):
                sess["results"] = filter_out_watched(sess.get("results") or [])
                return sess
        except Exception:
            pass
    return {
        "reply": "Use voice or type above to search, or toggle persistent filters.",
        "turns": [],
        "results": [],
        "follow_ups": [
            "Sci-Fi movie (no Marvel/DC)",
            "Terrifying horror under 2h",
            "Mind-bending thriller",
            "Feel-good comedy",
        ],
    }


def get_profile_recos(profile_id: str) -> List[Dict]:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT payload_json FROM profile_recos WHERE profile_id = ?", (profile_id,)
        ).fetchone()
        if not row:
            return []
        try:
            items = json.loads(row["payload_json"])
            return filter_out_watched(items)
        except Exception:
            return []


def get_reco_cache_age_sec(profile_id: str) -> int:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT updated_at FROM profile_recos WHERE profile_id = ?", (profile_id,)
        ).fetchone()
        if not row:
            return 999999
        return max(0, int(time.time()) - int(row["updated_at"]))


def is_reco_cache_fresh(profile_id: str) -> bool:
    return get_reco_cache_age_sec(profile_id) < RECO_TTL_SEC and len(get_profile_recos(profile_id)) > 0


def get_cached_gti(gti: str) -> Optional[str]:
    with get_conn() as conn:
        row = conn.execute("SELECT payload_json FROM gti_cache WHERE gti = ?", (gti,)).fetchone()
        return row["payload_json"] if row else None


def save_cached_gti(gti: str, payload_json: str) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO gti_cache (gti, payload_json, updated_at) VALUES (?, ?, ?)",
            (gti, payload_json, int(time.time())),
        )
