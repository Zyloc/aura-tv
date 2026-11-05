from contextlib import asynccontextmanager
from typing import Any, Dict
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel
import httpx
from config import BASE_DIR, DEFAULT_PROFILE, PROFILE_LABELS, TV_PROBE_URL
from db import (
    get_active_filters,
    get_profile_recos,
    get_setting,
    get_voice_session,
    init_db,
    list_manually_watched,
    list_watch_history,
    mark_title_watched,
    set_setting,
    toggle_active_filter,
)
from recommender import handle_conversational_turn
from interceptor import (
    beam_overlay_to_tv,
    ingest_remote_sync,
    launch_on_tv,
    open_native_tv_ui,
    poll_tv_state,
    push_sync_to_tv,
)

STATIC_DIR = BASE_DIR / "static"
_AUTO_BEAMED_KEYS = set()


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="Aura TV Host & Sync Server", lifespan=lifespan)


class LaunchBody(BaseModel):
    uri: str
    pkg: str = ""


class BeamBody(BaseModel):
    badge: str = "Aura · Up Next"
    title: str
    reason: str = ""
    sec: int = 8


@app.get("/api/state")
def api_state(profile: str = "", refresh: str = "0"):
    if profile:
        set_setting("active_profile", profile)
    state = poll_tv_state(force_refresh=(refresh == "1"))
    maybe_auto_beam(state)
    return state


@app.post("/api/chat")
async def api_chat(request: Request):
    try:
        body: Dict[str, Any] = await request.json()
    except Exception:
        body = {}
    prof = body.get("profile") or get_setting("active_profile", DEFAULT_PROFILE)
    msg = body.get("message") or ""
    reset = bool(body.get("reset", False))
    return handle_conversational_turn(prof, msg, reset)


@app.get("/api/watched")
@app.post("/api/watched")
async def api_watched(request: Request, title: str = "", watched: int = 1):
    item: Dict[str, Any] = {}
    if request.method == "POST":
        try:
            item = await request.json()
        except Exception:
            item = {}
    t = title or item.get("title") or ""
    w = bool(watched) if "watched" not in item else bool(item.get("watched"))
    item["title"] = t
    mark_title_watched(item, w)
    try:
        httpx.get(f"{TV_PROBE_URL}/api/watched", params={"title": t, "watched": "1" if w else "0"}, timeout=3.0)
    except Exception:
        pass
    return {"ok": True, "watched": w}


@app.post("/api/sync")
async def api_sync(request: Request):
    try:
        body: Dict[str, Any] = await request.json()
    except Exception:
        body = {}
    prof = get_setting("active_profile", DEFAULT_PROFILE)
    ingested = ingest_remote_sync(body, prof)
    prof = get_setting("active_profile", DEFAULT_PROFILE)
    return {
        "ok": True,
        "ingested": ingested,
        "active_profile": prof,
        "profile_labels": PROFILE_LABELS,
        "active_filters": get_active_filters(prof),
        "watched_items": list_manually_watched(),
        "voice_session": get_voice_session(prof),
        "history": list_watch_history(limit=500),
        "recommendations": get_profile_recos(prof),
    }


@app.get("/api/toggle-filter")
def api_toggle_filter(filter: str = ""):
    prof = get_setting("active_profile", DEFAULT_PROFILE)
    active = toggle_active_filter(prof, filter)
    push_sync_to_tv()
    return {"ok": True, "active_filters": active}


@app.get("/api/set-profile")
def api_set_profile(profile: str = DEFAULT_PROFILE):
    set_setting("active_profile", profile)
    push_sync_to_tv()
    return {"ok": True, "active_profile": profile}


@app.get("/api/set-server")
def api_set_server(mode: str = "auto"):
    set_setting("server_mode", mode)
    push_sync_to_tv()
    return {"ok": True, "server_mode": mode}


@app.get("/launch")
def get_launch(uri: str = "", pkg: str = ""):
    return {"ok": launch_on_tv(uri, pkg)}


@app.post("/api/launch")
def post_launch(body: LaunchBody):
    return {"ok": launch_on_tv(body.uri, body.pkg)}


@app.get("/overlay")
def get_overlay(badge: str = "Aura · Up Next", title: str = "", reason: str = "", sec: int = 8):
    return {"ok": beam_overlay_to_tv(badge, title, reason, sec)}


@app.post("/api/beam")
def post_beam(body: BeamBody):
    return {"ok": beam_overlay_to_tv(body.badge, body.title, body.reason, body.sec)}


@app.get("/open-ui")
def get_open_ui():
    return {"ok": open_native_tv_ui()}


def maybe_auto_beam(state: dict) -> None:
    np = state.get("now_playing") or {}
    dur = np.get("duration_sec") or 0
    pos = np.get("position_sec") or 0
    key = np.get("media_key") or ""
    recos = state.get("recommendations") or []
    if dur > 300 and pos / dur >= 0.90 and key and key not in _AUTO_BEAMED_KEYS and recos:
        _AUTO_BEAMED_KEYS.add(key)
        top = recos[0]
        beam_overlay_to_tv(top.get("badge", "Up Next"), top.get("title", ""), top.get("reason", ""), 10)


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/manifest.json")
def manifest():
    return FileResponse(STATIC_DIR / "manifest.json", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript")


@app.get("/icon.svg")
def icon():
    return FileResponse(STATIC_DIR / "icon.svg", media_type="image/svg+xml")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=7070)
