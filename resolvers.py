import html as html_lib
import json
import re
from typing import Dict
import httpx
from config import APP_LABELS, REGION_COUNTRY, REGION_LANG
from db import get_cached_gti, save_cached_gti

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
}


def resolve_session(session: Dict) -> Dict:
    pkg = session.get("package", "")
    meta = session.get("metadata", {})
    extras = session.get("extras", {})
    pos_sec = max(0, int(session.get("position_ms", 0)) // 1000)
    dur_ms = int(meta.get("android.media.metadata.DURATION", -1))
    dur_sec = dur_ms // 1000 if dur_ms > 0 else 0

    media_id = (
        meta.get("android.media.metadata.MEDIA_ID")
        or meta.get("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID")
        or extras.get("com.amazon.media.MEDIA_ID")
        or ""
    )

    base = {
        "app_pkg": pkg,
        "app_name": APP_LABELS.get(pkg, pkg.split(".")[-1].capitalize()),
        "state": state_label(session.get("state", 0)),
        "position_sec": pos_sec,
        "duration_sec": dur_sec,
        "media_id": str(media_id),
    }

    if str(media_id).startswith("amzn1.dv.gti."):
        info = resolve_prime_gti(str(media_id))
        base.update(info)
    else:
        info = resolve_generic_meta(meta, pkg, str(media_id))
        base.update(info)

    base["media_key"] = f"{pkg}:{base.get('series_title') or base.get('title')}:{base.get('episode') or media_id}".lower()
    return base


def state_label(code: int) -> str:
    return {1: "STOPPED", 2: "PAUSED", 3: "PLAYING", 6: "BUFFERING"}.get(code, "IDLE")


def resolve_prime_gti(gti: str) -> Dict:
    cached = get_cached_gti(gti)
    if cached:
        return json.loads(cached)

    info = fetch_prime_detail(gti)
    if info.get("series_title") or info.get("title"):
        meta = enrich_open_source_meta(info.get("series_title") or info.get("title", ""))
        if meta.get("poster_url") and not info.get("poster_url"):
            info["poster_url"] = meta["poster_url"]
        if meta.get("genres"):
            info["genres"] = meta["genres"]
        if meta.get("runtime_min"):
            info["runtime_min"] = meta["runtime_min"]
        if meta.get("duration_sec") and not info.get("duration_sec"):
            info["duration_sec"] = meta["duration_sec"]
        save_cached_gti(gti, json.dumps(info))
    return info


def fetch_prime_detail(gti: str) -> Dict:
    result = {
        "title": gti,
        "series_title": "",
        "season": None,
        "episode": None,
        "synopsis": "",
        "poster_url": "",
        "genres": "Drama",
        "runtime_min": 60,
        "next_gti": "",
    }
    try:
        r = httpx.get(f"https://www.primevideo.com/detail/{gti}", follow_redirects=True, headers=HEADERS, timeout=8.0)
        parse_prime_html(r.text, gti, result)
    except Exception:
        pass
    return result


def parse_prime_html(text: str, gti: str, out: Dict) -> None:
    parent_m = re.search(r'"parentTitle"\s*:\s*"([^"]+)"', text)
    season_m = re.search(r'"seasonNumber"\s*:\s*(\d+)', text)
    if parent_m:
        out["series_title"] = clean_str(parent_m.group(1))
    if season_m:
        out["season"] = int(season_m.group(1))

    if not out["series_title"]:
        t_m = re.search(r"<title>Prime Video:\s*(.*?)</title>", text)
        if t_m:
            raw_t = clean_str(t_m.group(1))
            out["series_title"] = re.sub(r"\s*-\s*Season\s*\d+.*", "", raw_t)

    ep_pattern = re.escape(gti) + r'":\{"title":"([^"]+)","synopsis":"([^"]+)"'
    ep_m = re.search(ep_pattern, text)
    if ep_m:
        out["title"] = clean_str(ep_m.group(1))
        out["synopsis"] = clean_str(ep_m.group(2))

    seq_pattern = re.escape(gti) + r'","isLaunched":true,"link":"[^"]+","sequenceNumber":(\d+)'
    seq_m = re.search(seq_pattern, text)
    if seq_m:
        out["episode"] = int(seq_m.group(1))
        next_seq = out["episode"] + 1
        next_pat = r'"gti":"(amzn1\.dv\.gti\.[^"]+)","isLaunched":true,"link":"[^"]+","sequenceNumber":' + str(next_seq)
        next_m = re.search(next_pat, text)
        if next_m:
            out["next_gti"] = next_m.group(1)

    if not out["title"] or out["title"] == gti:
        out["title"] = out["series_title"] or "Prime Video Playback"


def resolve_generic_meta(meta: Dict, pkg: str, media_id: str) -> Dict:
    title = (
        meta.get("android.media.metadata.TITLE")
        or meta.get("android.media.metadata.DISPLAY_TITLE")
        or ""
    )
    subtitle = (
        meta.get("android.media.metadata.ARTIST")
        or meta.get("android.media.metadata.DISPLAY_SUBTITLE")
        or meta.get("android.media.metadata.ALBUM")
        or ""
    )
    art = (
        meta.get("android.media.metadata.ALBUM_ART_URI")
        or meta.get("android.media.metadata.ART_URI")
        or meta.get("android.media.metadata.DISPLAY_ICON_URI")
        or ""
    )
    info = {
        "title": title or subtitle or APP_LABELS.get(pkg, "Active Stream"),
        "series_title": subtitle if title else "",
        "season": None,
        "episode": None,
        "synopsis": "",
        "poster_url": art,
        "genres": "",
        "runtime_min": 45,
    }
    if title:
        os_meta = enrich_open_source_meta(title)
        info["poster_url"] = info["poster_url"] or os_meta.get("poster_url", "")
        info["synopsis"] = info["synopsis"] or os_meta.get("synopsis", "")
        info["genres"] = os_meta.get("genres", "")
        info["runtime_min"] = os_meta.get("runtime_min", 45)
    return info


def enrich_open_source_meta(query: str) -> Dict:
    if not query:
        return {}
    cache_key = "jw_imdb_v3:" + query.strip().lower()
    cached = get_cached_gti(cache_key)
    if cached:
        try:
            return json.loads(cached)
        except Exception:
            pass
    jw = enrich_from_justwatch(query)
    if not jw.get("poster_url"):
        imdb = enrich_from_imdb(query)
        if imdb.get("poster_url"):
            jw["poster_url"] = imdb["poster_url"]
    if jw.get("poster_url"):
        save_cached_gti(cache_key, json.dumps(jw))
    return jw


def enrich_from_justwatch(query: str) -> Dict:
    gql = (
        "query GetTitle($searchQuery: String!, $country: Country!, $language: Language!) {"
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 1) {"
        "    edges { node {"
        "      objectType"
        "      content(country: $country, language: $language) {"
        "        title originalReleaseYear runtime shortDescription"
        "        genres { shortName } scoring { imdbScore } posterUrl"
        "      }"
        "      offers(country: $country, platform: ANDROID_TV) {"
        "        monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)"
        "        package { clearName }"
        "      }"
        "    }}"
        "  }"
        "}"
    )
    try:
        r = httpx.post(
            "https://apis.justwatch.com/graphql",
            json={"query": gql, "variables": {"searchQuery": query, "country": REGION_COUNTRY, "language": REGION_LANG}},
            headers=HEADERS,
            timeout=4.0,
        )
        edges = (((r.json().get("data") or {}).get("popularTitles") or {}).get("edges")) or []
        if not edges:
            return {}
        return parse_justwatch_node(edges[0].get("node") or {})
    except Exception:
        return {}


def fetch_justwatch_similar(seed_title: str) -> list:
    gql = (
        "query GetSimilar($searchQuery: String!, $country: Country!, $language: Language!) {"
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 1) {"
        "    edges { node { ... on MovieOrShow {"
        "      similarTitles(country: $country) {"
        "        objectType"
        "        content(country: $country, language: $language) {"
        "          title originalReleaseYear runtime shortDescription"
        "          genres { shortName } scoring { imdbScore } posterUrl"
        "        }"
        "        offers(country: $country, platform: ANDROID_TV) {"
        "          monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)"
        "          package { clearName }"
        "        }"
        "      }"
        "    }}}"
        "  }"
        "}"
    )
    try:
        r = httpx.post(
            "https://apis.justwatch.com/graphql",
            json={"query": gql, "variables": {"searchQuery": seed_title, "country": REGION_COUNTRY, "language": REGION_LANG}},
            headers=HEADERS,
            timeout=4.5,
        )
        edges = (((r.json().get("data") or {}).get("popularTitles") or {}).get("edges")) or []
        if not edges:
            return []
        sim = (edges[0].get("node") or {}).get("similarTitles") or []
        return [parse_justwatch_node(n) for n in sim if n]
    except Exception:
        return []


def parse_justwatch_node(node: Dict) -> Dict:
    c = node.get("content") or {}
    obj_type = node.get("objectType", "SHOW")
    imdb = ((c.get("scoring") or {}).get("imdbScore")) or 0
    poster = c.get("posterUrl") or ""
    if poster:
        poster = "https://images.justwatch.com" + poster.replace("{profile}", "s332").replace("{format}", "jpg")
    g_map = {"scf": "Sci-Fi", "trl": "Thriller", "drm": "Drama", "cmy": "Comedy", "act": "Action", "hor": "Horror", "crm": "Crime", "mys": "Mystery", "rma": "Romance"}
    genres = ", ".join(g_map[g["shortName"]] for g in (c.get("genres") or []) if g.get("shortName") in g_map) or "Drama"
    offers = node.get("offers") or []
    flat = next((o for o in offers if o.get("monetizationType") == "FLATRATE"), offers[0] if offers else {})
    app = ((flat.get("package") or {}).get("clearName")) or "Prime Video"
    if "Hotstar" in app:
        app = "Disney+ Hotstar"
    elif "Amazon Prime" in app:
        app = "Prime Video"
    elif app == "Apple TV":
        app = "Apple TV+"
    return {
        "title": c.get("title", ""),
        "year": c.get("originalReleaseYear") or 2024,
        "type": "Movie" if obj_type == "MOVIE" else "Series",
        "runtime_min": int(c.get("runtime") or (115 if obj_type == "MOVIE" else 48)),
        "synopsis": c.get("shortDescription", ""),
        "rating": f"★ {imdb:.1f}" if imdb else "★ 8.1",
        "poster_url": poster,
        "genres": genres,
        "app": app,
        "launch_uri": flat.get("deeplinkURL") or flat.get("standardWebURL") or "",
    }


def enrich_from_tvmaze(query: str) -> Dict:
    if not query:
        return {}
    try:
        r = httpx.get(
            "https://api.tvmaze.com/singlesearch/shows",
            params={"q": query},
            timeout=4.0,
        )
        if r.status_code != 200:
            return {}
        data = r.json()
        exact = (data.get("name") or "").strip().lower() == query.strip().lower()
        img = ((data.get("image") or {}).get("medium") or "") if exact else ""
        summary = re.sub(r"<[^>]+>", "", data.get("summary") or "").strip() if exact else ""
        genres = ", ".join(data.get("genres") or []) if exact else ""
        runtime_min = int(data.get("averageRuntime") or data.get("runtime") or 50)
        rating_val = (data.get("rating") or {}).get("average") if exact else None
        return {
            "poster_url": img,
            "synopsis": summary,
            "genres": genres,
            "runtime_min": runtime_min,
            "duration_sec": runtime_min * 60,
            "rating": f"★ {rating_val}" if rating_val else "★ 8.3",
        }
    except Exception:
        return {}


def enrich_from_imdb(query: str) -> Dict:
    if not query:
        return {}
    try:
        clean = re.sub(r"[^a-z0-9]", "_", query.lower().strip()).strip("_")
        if not clean:
            return {}
        url = f"https://v3.sg.media-imdb.com/suggestion/{clean[0]}/{clean}.json"
        r = httpx.get(url, headers=HEADERS, timeout=4.0)
        if r.status_code != 200:
            return {}
        items = r.json().get("d") or []
        for item in items:
            img = (item.get("i") or {}).get("imageUrl") or ""
            if img:
                return {"poster_url": img}
    except Exception:
        pass
    return {}


def clean_str(val: str) -> str:
    unescaped = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), val)
    return html_lib.unescape(unescaped)
