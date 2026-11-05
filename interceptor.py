import time
from typing import Dict, List
import httpx
from config import DEFAULT_PROFILE, PROFILE_LABELS, TV_PROBE_URL
from db import (
    get_active_filters,
    get_profile_recos,
    get_reco_cache_age_sec,
    get_setting,
    get_voice_session,
    list_manually_watched,
    list_watch_history,
    mark_title_watched,
    save_profile_recos,
    save_voice_session,
    set_active_filters,
    set_setting,
    upsert_watch_item,
)
from recommender import get_recommendations_for_profile

_LAST_TV_SYNC_TS = 0.0


def poll_tv_state(force_refresh: bool = False) -> Dict:
    global _LAST_TV_SYNC_TS
    profile = get_setting("active_profile", DEFAULT_PROFILE)
    server_mode = get_setting("server_mode", "auto")

    tv_state = {}
    if server_mode != "phone":
        tv_state = fetch_tv_state(profile, force_refresh)

    now = time.time()
    if tv_state and (force_refresh or (now - _LAST_TV_SYNC_TS) > 45):
        ingest_remote_sync(tv_state, profile)
        _LAST_TV_SYNC_TS = now

    history = list_watch_history(limit=1000)
    now_playing = tv_state.get("now_playing") or (history[0] if history else {})

    recos = get_recommendations_for_profile(profile, now_playing, history, force_refresh)
    if not recos and tv_state.get("recommendations"):
        recos = tv_state["recommendations"]
        save_profile_recos(profile, recos)

    return {
        "online": bool(tv_state),
        "active_profile": profile,
        "profile_labels": PROFILE_LABELS,
        "server_mode": server_mode,
        "host_url": "Host (:7070) ↔ TV (:8765)",
        "cache_age_sec": get_reco_cache_age_sec(profile),
        "foreground_pkg": tv_state.get("foreground_pkg", ""),
        "now_playing": now_playing,
        "recommendations": recos,
        "watched_items": list_manually_watched(),
        "active_filters": get_active_filters(profile),
        "voice_session": get_voice_session(profile),
        "history": history,
        "history_count": len(history),
    }


def fetch_tv_state(profile: str, force_refresh: bool = False) -> Dict:
    try:
        params = {"profile": profile}
        if force_refresh:
            params["refresh"] = "1"
        r = httpx.get(f"{TV_PROBE_URL}/api/state", params=params, timeout=8.0)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return {}


def ingest_remote_sync(payload: Dict, active_profile: str) -> int:
    remote_prof = payload.get("active_profile")
    if remote_prof and remote_prof != active_profile:
        set_setting("active_profile", remote_prof)
        active_profile = remote_prof

    watched_list = payload.get("watched_items") or []
    for w in watched_list:
        if isinstance(w, dict):
            mark_title_watched(w, True)

    filters = payload.get("active_filters")
    if isinstance(filters, list):
        set_active_filters(active_profile, filters)

    voice_sess = payload.get("voice_session")
    if isinstance(voice_sess, dict) and (voice_sess.get("turns") or voice_sess.get("results")):
        save_voice_session(active_profile, voice_sess)

    hist = payload.get("history") or []
    count = 0
    for item in hist:
        if isinstance(item, dict):
            upsert_watch_item(item)
            count += 1

    recos = payload.get("recommendations") or []
    if isinstance(recos, list) and recos and not get_profile_recos(active_profile):
        save_profile_recos(active_profile, recos)
    return count


def push_sync_to_tv() -> Dict:
    profile = get_setting("active_profile", DEFAULT_PROFILE)
    payload = {
        "source": "host",
        "active_profile": profile,
        "profile_labels": PROFILE_LABELS,
        "active_filters": get_active_filters(profile),
        "watched_items": list_manually_watched(),
        "voice_session": get_voice_session(profile),
        "history": list_watch_history(limit=500),
        "recommendations": get_profile_recos(profile),
    }
    try:
        r = httpx.post(f"{TV_PROBE_URL}/api/sync", json=payload, timeout=8.0)
        if r.status_code == 200:
            res = r.json()
            ingest_remote_sync(res, profile)
            return res
    except Exception:
        pass
    return {}


def beam_overlay_to_tv(badge: str, title: str, reason: str, sec: int = 8) -> bool:
    try:
        r = httpx.get(
            f"{TV_PROBE_URL}/overlay",
            params={"badge": badge, "title": title, "reason": reason, "sec": str(sec)},
            timeout=3.0,
        )
        return r.status_code == 200
    except Exception:
        return False


def launch_on_tv(uri: str, pkg: str = "") -> bool:
    try:
        r = httpx.get(
            f"{TV_PROBE_URL}/launch",
            params={"uri": uri, "pkg": pkg},
            timeout=3.0,
        )
        return r.status_code == 200
    except Exception:
        return False


def open_native_tv_ui() -> bool:
    try:
        r = httpx.get(f"{TV_PROBE_URL}/open-ui", timeout=3.0)
        return r.status_code == 200
    except Exception:
        return False
