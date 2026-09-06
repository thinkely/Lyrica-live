"""
sources/genius_fetcher.py — Genius provider for plain lyrics with user-provided token support.
"""

import re
import html
import httpx
from .base_fetcher import BaseFetcher, build_result

_API_BASE = "https://api.genius.com"
_CONTAINER_RE = re.compile(r'<div[^>]*data-lyrics-container="true"[^>]*>(.*?)</div>', re.DOTALL)
_TAG_RE = re.compile(r'<[^>]+>')
_BR_RE = re.compile(r'<br\s*/?>', re.IGNORECASE)
_CONTRIBUTOR_RE = re.compile(r"^\d+\s+Contributors?.*?Lyrics\n", re.DOTALL)
_EMBED_RE = re.compile(r"\d*\s*Embed\s*$", re.IGNORECASE)

_BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/126.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://genius.com/",
}


def _clean_genius_lyrics(raw: str) -> str:
    cleaned = _CONTRIBUTOR_RE.sub("", raw)
    cleaned = _EMBED_RE.sub("", cleaned)
    return cleaned.strip()


def _parse_genius_html(html_text: str) -> str | None:
    matches = _CONTAINER_RE.findall(html_text)
    if not matches:
        return None
    sections = []
    for div_content in matches:
        text_with_newlines = _BR_RE.sub("\n", div_content)
        clean_text = _TAG_RE.sub("", text_with_newlines)
        clean_text = html.unescape(clean_text).strip()
        if clean_text:
            sections.append(clean_text)
    raw = "\n\n".join(sections)
    return _clean_genius_lyrics(raw) or None


class GeniusFetcher(BaseFetcher):
    source_name = "genius"

    async def fetch(self, artist: str, song: str, token: str | None = None, **kwargs) -> dict | None:
        if not token:
            return None

        headers_api = {
            "Authorization": f"Bearer {token}",
            "User-Agent": "LyricaLive/1.0",
        }

        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(8.0), follow_redirects=True) as client:
                search_resp = await client.get(
                    f"{_API_BASE}/search",
                    params={"q": f"{song} {artist}"},
                    headers=headers_api,
                )
                if search_resp.status_code != 200:
                    return None

                hits = search_resp.json().get("response", {}).get("hits", [])
                song_hit = None
                artist_lower = artist.lower()
                for h in hits:
                    if h.get("type") != "song":
                        continue
                    res = h.get("result", {})
                    hit_artist = res.get("primary_artist", {}).get("name", "").lower()
                    if artist_lower in hit_artist or hit_artist in artist_lower:
                        song_hit = res
                        break

                if not song_hit and hits:
                    song_hit = hits[0].get("result", {})

                if not song_hit:
                    return None

                song_url = song_hit.get("url")
                if not song_url:
                    return None

                page_resp = await client.get(song_url, headers=_BROWSER_HEADERS)
                if page_resp.status_code != 200:
                    return None

                plain_lyrics = _parse_genius_html(page_resp.text)
                if not plain_lyrics:
                    return None

                return build_result(
                    source="genius",
                    artist=song_hit.get("primary_artist", {}).get("name") or artist,
                    title=song_hit.get("title") or song,
                    lyrics=plain_lyrics,
                    timed_lyrics=None,
                    sync_precision="NONE",
                )
        except Exception:
            return None
