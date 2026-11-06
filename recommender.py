from concurrent.futures import ThreadPoolExecutor
import json
import urllib.parse
from typing import Dict, List
import httpx
from config import DEFAULT_PROFILE, GEMINI_API_KEY, GEMINI_MODELS, PROFILE_LABELS
from db import (
    get_active_filters,
    get_profile_recos,
    get_setting,
    get_voice_session,
    get_watched_keys,
    is_reco_cache_fresh,
    list_manually_watched,
    list_watch_history,
    normalize_title_key,
    resolve_app_package,
    save_profile_recos,
    save_voice_session,
    set_setting,
)
from resolvers import enrich_open_source_meta, fetch_justwatch_similar


def get_recommendations_for_profile(
    profile: str, current: Dict, history: List[Dict], force_refresh: bool = False
) -> List[Dict]:
    if not force_refresh and is_reco_cache_fresh(profile):
        cached = get_profile_recos(profile)
        if cached:
            return cached

    raw_picks = fetch_gemini_picks(profile, current, history) if GEMINI_API_KEY else []
    if not raw_picks:
        return get_profile_recos(profile)

    with ThreadPoolExecutor(max_workers=8) as pool:
        cards = list(pool.map(enrich_pick, raw_picks[:14]))
    if cards:
        save_profile_recos(profile, cards)
    return cards


def fetch_gemini_picks(profile: str, current: Dict, history: List[Dict]) -> List[Dict]:
    prompt = build_gemini_prompt(profile, current, history)
    for model in GEMINI_MODELS:
        picks = call_gemini_model(model, prompt)
        if picks:
            return picks
    return []


def build_gemini_prompt(profile: str, current: Dict, history: List[Dict]) -> str:
    seen = []
    loved = []
    disliked = []
    for w in list_manually_watched():
        t = (w.get("series_title") or w.get("title") or "").strip()
        if t and t not in seen:
            seen.append(t)
        r = int(w.get("user_rating") or 0)
        if r >= 7:
            loved.append(f"{t} ({r}/10)")
        elif 0 < r <= 5:
            disliked.append(f"{t} ({r}/10)")

    for h in history:
        name = (h.get("series_title") or h.get("title") or "").strip()
        if name and name not in seen:
            seen.append(name)
        if len(seen) >= 45:
            break

    now_name = current.get("series_title") or current.get("title") or ""
    profile_name = PROFILE_LABELS.get(profile, profile)
    custom_taste = get_setting(f"profile_taste_{profile}", "")
    default_taste = get_setting(
        "default_guide_ai",
        "Critically acclaimed released titles (IMDb 7.5+), gripping storytelling, streaming in India",
    )
    active_filters = get_active_filters(profile)

    return (
        f"You are Aura, an AI TV & movie recommendation engine.\n"
        f"Active Household Profile: '{profile_name}' (id: {profile}).\n"
        f"1. Personal Guide AI (for {profile_name}): {custom_taste or '(Use Default Guide AI and watch history)'}\n"
        f"2. Default Guide AI (Global Baseline): {default_taste}\n"
        f"Highly Rated by User (1-10 scale, recommend more like these): {loved}\n"
        f"Low Rated by User (1-10 scale, avoid similar style): {disliked}\n"
        f"Active Filters: {active_filters}\n"
        f"Currently watching on TV: '{now_name}'.\n"
        f"Already Watched (STRICT EXCLUSION — DO NOT recommend): {seen}.\n"
        "Current Date: November 2026. STRICT RULE: ONLY recommend modern released shows or movies from 2019 to 2026 (prioritizing 2021-2026, IMDb >= 7.4) currently streaming on subscription OTT in India. NEVER recommend old 1980s/1990s/2000s/early-2010s titles and NEVER recommend unreleased 2027+ titles.\n"
        "Return ONLY a JSON array of 15 objects with keys: "
        "'title', 'app' (Prime Video, Netflix, Disney+ Hotstar, Apple TV+, SonyLIV, or ZEE5), "
        "'genre' (comma-separated genres), "
        "'runtime_min' (integer minutes), "
        "and 'reason' (1-2 specific sentences explaining why it fits their taste)."
    )


def call_gemini_model(model: str, prompt: str) -> List[Dict]:
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.65},
        }
        r = httpx.post(url, json=payload, timeout=12.0)
        if r.status_code != 200:
            return []
        text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
        data = json.loads(text)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def enrich_pick(pick: Dict) -> Dict:
    title = pick.get("title", "")
    meta = enrich_open_source_meta(title)
    app_name = meta.get("app") or pick.get("app", "Prime Video")
    genres = meta.get("genres") or pick.get("genre") or pick.get("genres") or "Drama"
    runtime_min = int(meta.get("runtime_min") or pick.get("runtime_min") or 45)
    launch_pkg = resolve_app_package(app_name)
    reason = pick.get("reason") or meta.get("synopsis", "")
    return {
        "title": title,
        "year": meta.get("year", 2024),
        "type": meta.get("type", "Series"),
        "badge": app_name,
        "app": app_name,
        "reason": reason,
        "synopsis": meta.get("synopsis") or reason,
        "poster_url": meta.get("poster_url", ""),
        "genres": genres,
        "runtime_min": runtime_min,
        "rating": meta.get("rating", "★ 8.2"),
        "launch_uri": meta.get("launch_uri", ""),
        "launch_pkg": launch_pkg,
        "trailer_uri": build_smarttube_search(f"{title} official trailer"),
    }


def build_smarttube_search(query: str) -> str:
    q = urllib.parse.quote_plus(query)
    return f"https://www.youtube.com/results?search_query={q}"


def handle_conversational_turn(profile: str, message: str, reset: bool = False) -> Dict:
    if reset or not (message or "").strip():
        set_setting(f"voice_session_{profile}", "")
        return get_voice_session(profile)

    session = get_voice_session(profile)
    turns = list(session.get("turns") or [])
    watched_names = [x.get("title", "") for x in list_manually_watched()]
    for h in list_watch_history(limit=35):
        t = (h.get("series_title") or h.get("title") or "").strip()
        if t and t not in watched_names:
            watched_names.append(t)

    active_filters = get_active_filters(profile)
    conv_str = "\n".join(f"{t.get('role')}: {t.get('text')}" for t in turns)
    prompt = (
        f"You are Aura, a conversational living-room TV & movie concierge for profile '{profile}'.\n"
        f"STRICT EXCLUSION LIST — ALREADY WATCHED (NEVER recommend any of these): {watched_names[:45]}\n"
        f"Active Persistent Filters (MUST honor if non-empty): {active_filters}\n"
        f"Conversation so far:\n{conv_str}\n"
        f"User's new voice request: \"{message.strip()}\"\n\n"
        "Respond with a JSON object containing:\n"
        "1. 'reply': A warm, concise 1-2 sentence conversational response acknowledging their request and active filters.\n"
        "2. 'results': An array of 8 specific shows or movies matching the conversation and filters (excluding all watched titles), each with keys: 'title', 'app' (Prime Video, Netflix, Disney+ Hotstar, Apple TV+, SonyLIV, ZEE5), 'genre', 'runtime_min' (integer), and 'reason' (max 15 words).\n"
        "3. 'follow_ups': An array of 4 short conversational follow-up refinement phrases the user can click next (e.g. 'Make it darker & scarier', 'Only movies under 100 mins', 'More mind-bending twist endings', 'Only on Disney+ Hotstar or Netflix')."
    )

    parsed = None
    for model in GEMINI_MODELS:
        parsed = call_gemini_json_obj(model, prompt)
        if parsed:
            break

    turns.append({"role": "user", "text": message.strip()})
    watched_keys = get_watched_keys()
    if parsed and isinstance(parsed, dict):
        reply = parsed.get("reply") or "Here are tailored picks based on your voice request."
        turns.append({"role": "assistant", "text": reply})
        raw_res = [
            p
            for p in (parsed.get("results") or [])[:8]
            if isinstance(p, dict) and normalize_title_key(p.get("title", "")) not in watched_keys
        ]
        with ThreadPoolExecutor(max_workers=8) as pool:
            enriched = list(pool.map(enrich_pick, raw_res))
        follow_ups = parsed.get("follow_ups") or [
            "🧟 Make it darker & scarier",
            "⏱️ Only movies/episodes under 90 mins",
            "🧠 More mind-bending plot twists",
            "🇮🇳 Only on Netflix or Prime Video India",
        ]
    else:
        reply = f"Matched your request for '{message}' using the open-source TVMaze/IMDb graph."
        turns.append({"role": "assistant", "text": reply})
        enriched = [enrich_pick(p) for p in OPEN_SOURCE_FALLBACK[:8]]
        follow_ups = [
            "🧟 Make it darker & scarier",
            "⏱️ Only movies/episodes under 90 mins",
            "🧠 More mind-bending plot twists",
            "🇮🇳 Only on Netflix or Prime Video India",
        ]

    out = {
        "reply": reply,
        "turns": turns[-8:],
        "results": enriched,
        "follow_ups": follow_ups,
    }
    save_voice_session(profile, out)
    return out


def call_gemini_json_obj(model: str, prompt: str) -> Dict:
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.65},
        }
        r = httpx.post(url, json=payload, timeout=12.0)
        if r.status_code != 200:
            return {}
        text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}
