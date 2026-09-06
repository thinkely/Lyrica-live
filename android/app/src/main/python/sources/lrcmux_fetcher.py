"""
sources/lrcmux_fetcher.py — LRCMux provider for line and word-level synced lyrics.
"""

import httpx
from .base_fetcher import BaseFetcher, build_result

_LRCMUX_API_URL = "https://api.lrcmux.dev/get"
_UA = "LyricaLive/1.0 (https://github.com/thinkely/Lyrica)"


class LrcmuxFetcher(BaseFetcher):
    source_name = "lrcmux"

    async def fetch(self, artist: str, song: str, timestamps: bool = True, word_level: bool = True, **kwargs) -> dict | None:
        client_kwargs = {
            "timeout": httpx.Timeout(connect=5.0, read=12.0, write=5.0, pool=2.0),
            "headers": {"User-Agent": _UA},
            "follow_redirects": True,
        }

        params = {
            "artist": artist,
            "title": song,
            "sources": "musixmatch",
            "format": "json",
        }
        if timestamps:
            params["level"] = "word" if word_level else "line"

        async with httpx.AsyncClient(**client_kwargs) as client:
            try:
                resp = await client.get(_LRCMUX_API_URL, params=params)
                if resp.status_code != 200:
                    return None

                data = resp.json()
                lines_data = data.get("lines") or []
                if not lines_data:
                    return None

                plain_lines = []
                timed_lines = []
                has_word_sync = False

                for i, line in enumerate(lines_data):
                    text = line.get("text") or ""
                    plain_lines.append(text)
                    start = line.get("start")
                    end = line.get("end")

                    if start is not None:
                        words = []
                        raw_words = line.get("words")
                        if raw_words and isinstance(raw_words, list):
                            has_word_sync = True
                            for w in raw_words:
                                words.append({
                                    "text": w.get("text", ""),
                                    "start_time": w.get("start", start),
                                    "end_time": w.get("end", end or (start + 500)),
                                    "syllables": []
                                })

                        timed_line = {
                            "text": text,
                            "start_time": start,
                            "end_time": end if end is not None else (start + 4000),
                            "id": f"lrc_{i}",
                            "words": words
                        }
                        timed_lines.append(timed_line)

                plain_lyrics = "\n".join(plain_lines)
                if not plain_lyrics.strip():
                    return None

                track_info = data.get("track") or {}
                meta = data.get("meta") or {}
                precision = "WORD" if (has_word_sync and word_level) else ("LINE" if timed_lines else "NONE")

                return build_result(
                    source="lrcmux",
                    artist=track_info.get("artist") or artist,
                    title=track_info.get("title") or song,
                    album=track_info.get("album"),
                    duration=track_info.get("duration"),
                    isrc=track_info.get("isrc"),
                    lyrics=plain_lyrics,
                    timed_lyrics=timed_lines if timestamps else None,
                    sync_precision=precision,
                )
            except Exception:
                return None
