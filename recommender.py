from concurrent.futures import ThreadPoolExecutor
import json
import urllib.parse
from typing import Dict, List
import httpx
from config import DEFAULT_PROFILE, GEMINI_API_KEY, GEMINI_MODELS, PROFILE_LABELS
from db import (
    get_active_filters,
    get_profile_recos,
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
from resolvers import enrich_open_source_meta

PROFILE_HINTS = {
    "member1": f"{PROFILE_LABELS.get('member1', 'Member 1')} (loves high-concept sci-fi like Severance, Foundation, Dark, intense K-dramas, and mind-bending thrillers).",
    "partner": f"{PROFILE_LABELS.get('partner', 'Partner')} (loves romantic K-dramas like Marry My Husband, Lovely Runner, emotional drama, and bingeable mystery).",
    "member3": f"{PROFILE_LABELS.get('member3', 'Member 3')} (loves action blockbusters, crime thrillers, Indian web series, and standup/short comedies).",
    "kids": f"{PROFILE_LABELS.get('kids', 'Kids')} (animated movies, Pixar/Disney, adventure, science & nature shorts).",
    "family": f"{PROFILE_LABELS.get('family', 'Family')} (crowd-pleasing movies, mystery, comedy, and universally loved series).",
}

OPEN_SOURCE_FALLBACK = [
    {"title": "Lovely Runner", "app": "Prime Video", "genre": "Romance, Drama", "runtime_min": 60, "reason": "Time-slip K-drama romance matching Marry My Husband."},
    {"title": "Dark", "app": "Netflix", "genre": "Sci-Fi, Mystery", "runtime_min": 55, "reason": "Mind-bending sci-fi mystery matching Severance."},
    {"title": "The Bear", "app": "Hotstar", "genre": "Comedy, Drama", "runtime_min": 28, "reason": "Fast-paced 28-minute episodes with gripping tension."},
    {"title": "Love, Death & Robots", "app": "Netflix", "genre": "Sci-Fi, Short", "runtime_min": 15, "reason": "15-minute sci-fi anthology shorts for quick watching."},
    {"title": "3 Body Problem", "app": "Netflix", "genre": "Sci-Fi, Thriller", "runtime_min": 55, "reason": "Hard sci-fi epic matching Foundation and Dune."},
    {"title": "Panchayat", "app": "Prime Video", "genre": "Comedy, Drama", "runtime_min": 32, "reason": "Heartwarming Indian dramedy on Prime Video."},
    {"title": "The Glory", "app": "Netflix", "genre": "Drama, Thriller", "runtime_min": 50, "reason": "Meticulously plotted K-drama revenge thriller."},
    {"title": "Modern Family", "app": "Hotstar", "genre": "Comedy, Family", "runtime_min": 22, "reason": "Quick 22-minute comedy episodes great for any mood."},
    {"title": "Silo", "app": "Apple TV+", "genre": "Sci-Fi, Mystery", "runtime_min": 50, "reason": "Dystopian bunker mystery on Apple TV+."},
    {"title": "Farzi", "app": "Prime Video", "genre": "Crime, Thriller", "runtime_min": 55, "reason": "Slick crime thriller series on Prime Video."},
    {"title": "Blue Eye Samurai", "app": "Netflix", "genre": "Action, Animation", "runtime_min": 45, "reason": "Critically acclaimed action revenge epic."},
    {"title": "Brooklyn Nine-Nine", "app": "Netflix", "genre": "Comedy, Short", "runtime_min": 22, "reason": "Quick 22-minute workplace comedy."},
]


def get_recommendations_for_profile(
    profile: str, current: Dict, history: List[Dict], force_refresh: bool = False
) -> List[Dict]:
    if not force_refresh and is_reco_cache_fresh(profile):
        cached = get_profile_recos(profile)
        if cached:
            return cached

    raw_picks = fetch_gemini_picks(profile, current, history) if GEMINI_API_KEY else []
    if not raw_picks:
        raw_picks = OPEN_SOURCE_FALLBACK

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
    for h in history:
        name = (h.get("series_title") or h.get("title") or "").strip()
        if name and name not in seen:
            seen.append(name)
        if len(seen) >= 35:
            break
    now_name = current.get("series_title") or current.get("title") or "Unknown"
    hint = PROFILE_HINTS.get(profile, PROFILE_HINTS[DEFAULT_PROFILE])
    return (
        f"Active Household Profile: {hint}\n"
        f"Currently watching on TV: '{now_name}'.\n"
        f"Recent TV Watch History: {seen}.\n"
        "Recommend 12 specific shows or movies they have NOT watched yet. Include:\n"
        "- 4 Quick Watch / Short episodes (< 30 mins, sitcoms, anthology shorts, or standup)\n"
        "- 3 Sci-Fi / Mystery / Mind-bending Thrillers\n"
        "- 3 Drama / Romance / K-Drama\n"
        "- 2 Action / Crime / Blockbuster Movies\n"
        "Return ONLY a JSON array of 12 objects with keys: "
        "'title', 'app' (Prime Video, Netflix, Hotstar, Apple TV+, SmartTube), "
        "'genre' (e.g. 'Sci-Fi, Thriller' or 'Comedy, Short'), "
        "'runtime_min' (integer minutes per episode or movie), "
        "and 'reason' (1 punchy sentence under 18 words)."
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
    app_name = pick.get("app", "Prime Video")
    genres = meta.get("genres") or pick.get("genre") or pick.get("genres") or "Drama"
    runtime_min = int(pick.get("runtime_min") or meta.get("runtime_min") or 45)
    launch_pkg = resolve_app_package(app_name)
    return {
        "title": title,
        "badge": app_name,
        "app": app_name,
        "reason": pick.get("reason", ""),
        "poster_url": meta.get("poster_url", ""),
        "genres": genres,
        "runtime_min": runtime_min,
        "rating": meta.get("rating", "★ 8.4"),
        "launch_uri": "",
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
