"""
sources/base_fetcher.py — Base class and canonical data builders for lyrics fetchers.
"""

import re
from datetime import datetime, timezone
import httpx

_SHARED_CLIENT: httpx.AsyncClient | None = None

def get_http_client() -> httpx.AsyncClient:
    global _SHARED_CLIENT
    if _SHARED_CLIENT is None or _SHARED_CLIENT.is_closed:
        _SHARED_CLIENT = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=5.0, read=12.0, write=5.0, pool=3.0),
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                "Accept-Language": "en-US,en;q=0.9",
            },
            follow_redirects=True,
        )
    return _SHARED_CLIENT


def build_result(
    source: str,
    artist: str,
    title: str,
    lyrics: str | None = None,
    timed_lyrics: list | None = None,
    sync_precision: str = "NONE",  # NONE, LINE, WORD, SYLLABLE
    album: str | None = None,
    duration: int | None = None,
    isrc: str | None = None,
    **extra,
) -> dict:
    """Canonical response shape for Lyrica Live."""
    has_timestamps = bool(timed_lyrics and len(timed_lyrics) > 0)
    if has_timestamps and sync_precision == "NONE":
        sync_precision = "LINE"
        
    result = {
        "source": source,
        "artist": artist,
        "title": title,
        "album": album,
        "duration": duration,
        "isrc": isrc,
        "lyrics": lyrics or "",
        "timed_lyrics": timed_lyrics or [],
        "hasTimestamps": has_timestamps,
        "syncPrecision": sync_precision,
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
    }
    result.update(extra)
    return result


# Standard LRC line regex: [mm:ss.xx] Lyric text
_LRC_RE = re.compile(r"\[(\d{2}):(\d{2}\.?\d{0,3})\](.*)")

def parse_lrc(lrc_text: str, total_duration_ms: int | None = None) -> list:
    """Parse LRC timestamped lyrics into list of timed line dicts."""
    if not lrc_text:
        return []
    lines = lrc_text.splitlines()
    parsed = []
    for line in lines:
        line_str = line.strip()
        m = _LRC_RE.match(line_str)
        if not m:
            continue
        mins, secs = m.group(1), m.group(2).replace("..", ".")
        text = m.group(3).strip()
        try:
            start_ms = int((int(mins) * 60 + float(secs)) * 1000)
        except ValueError:
            continue
        if text or len(parsed) > 0:
            parsed.append({
                "text": text,
                "start_time": start_ms,
                "end_time": None,
                "words": []
            })

    # Fill end_times from next line's start
    for i, entry in enumerate(parsed):
        if i + 1 < len(parsed):
            entry["end_time"] = parsed[i + 1]["start_time"]
        else:
            entry["end_time"] = (
                total_duration_ms if (total_duration_ms and total_duration_ms > entry["start_time"])
                else entry["start_time"] + 4000
            )
        entry["id"] = f"lrc_{i}"

    return parsed


class BaseFetcher:
    source_name: str = "unknown"

    async def fetch(self, artist: str, song: str, timestamps: bool = True, **kwargs) -> dict | None:
        raise NotImplementedError
