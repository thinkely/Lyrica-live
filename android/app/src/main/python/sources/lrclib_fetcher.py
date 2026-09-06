"""
sources/lrclib_fetcher.py — LRCLIB provider for synchronized and plain lyrics.
"""

import httpx
from .base_fetcher import BaseFetcher, build_result, parse_lrc

_LRCLIB_SEARCH = "https://lrclib.net/api/search"
_LRCLIB_GET = "https://lrclib.net/api/get"
_UA = "LyricaLive/1.0 (https://github.com/thinkely/Lyrica)"


class LRCLIBFetcher(BaseFetcher):
    source_name = "lrclib"

    async def fetch(self, artist: str, song: str, timestamps: bool = True, album: str | None = None, duration: int | None = None, **kwargs) -> dict | None:
        client_kwargs = {
            "timeout": httpx.Timeout(connect=5.0, read=10.0, write=5.0, pool=2.0),
            "headers": {"User-Agent": _UA},
            "follow_redirects": True,
        }

        async with httpx.AsyncClient(**client_kwargs) as client:
            try:
                # Direct get attempt if duration is known
                if duration and duration > 0:
                    dur_seconds = int(duration / 1000)
                    resp = await client.get(
                        _LRCLIB_GET,
                        params={
                            "track_name": song,
                            "artist_name": artist,
                            "album_name": album or "",
                            "duration": dur_seconds,
                        },
                    )
                    if resp.status_code == 200:
                        data = resp.json()
                        result = self._process_lrclib_data(data, artist, song, timestamps)
                        if result:
                            return result

                # Fallback to search endpoint
                search_resp = await client.get(
                    _LRCLIB_SEARCH,
                    params={"track_name": song, "artist_name": artist},
                )
                if search_resp.status_code != 200:
                    return None

                results = search_resp.json()
                if not results or not isinstance(results, list):
                    return None

                best_match = results[0]
                return self._process_lrclib_data(best_match, artist, song, timestamps)

            except Exception:
                return None

    def _process_lrclib_data(self, data: dict, fallback_artist: str, fallback_song: str, timestamps: bool) -> dict | None:
        synced_lrc = data.get("syncedLyrics")
        plain_lrc = data.get("plainLyrics")
        duration_sec = data.get("duration")
        duration_ms = int(duration_sec * 1000) if duration_sec else None

        timed_lyrics = None
        sync_precision = "NONE"

        if timestamps and synced_lrc:
            timed_lyrics = parse_lrc(synced_lrc, duration_ms)
            if timed_lyrics:
                sync_precision = "LINE"

        final_lyrics = synced_lrc if (timestamps and synced_lrc) else (plain_lrc or synced_lrc)
        if not final_lyrics:
            return None

        return build_result(
            source="lrclib",
            artist=data.get("artistName") or fallback_artist,
            title=data.get("trackName") or fallback_song,
            album=data.get("albumName"),
            duration=duration_ms,
            lyrics=final_lyrics,
            timed_lyrics=timed_lyrics,
            sync_precision=sync_precision,
            instrumental=data.get("instrumental", False),
        )
