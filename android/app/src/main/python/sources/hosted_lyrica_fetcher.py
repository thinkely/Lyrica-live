"""
sources/hosted_lyrica_fetcher.py — Fallback client for hosted Lyrica API instances.
"""

import httpx
from .base_fetcher import BaseFetcher, build_result

_DEFAULT_HOSTED_URL = "https://lyrica.vercel.app/api/lyrics"
_UA = "LyricaLive/1.0"


class HostedLyricaFetcher(BaseFetcher):
    source_name = "hosted_lyrica"

    async def fetch(
        self,
        artist: str,
        song: str,
        base_url: str | None = None,
        timestamps: bool = True,
        **kwargs
    ) -> dict | None:
        url = base_url or _DEFAULT_HOSTED_URL
        client_kwargs = {
            "timeout": httpx.Timeout(connect=5.0, read=12.0, write=5.0, pool=2.0),
            "headers": {"User-Agent": _UA},
            "follow_redirects": True,
        }

        async with httpx.AsyncClient(**client_kwargs) as client:
            try:
                resp = await client.get(
                    url,
                    params={
                        "artist": artist,
                        "song": song,
                        "timestamps": "true" if timestamps else "false",
                    }
                )
                if resp.status_code != 200:
                    return None

                data = resp.json()
                timed_lyrics = data.get("timed_lyrics") or []
                lyrics = data.get("lyrics") or ""
                if not lyrics and not timed_lyrics:
                    return None

                return build_result(
                    source="hosted_lyrica",
                    artist=data.get("artist") or artist,
                    title=data.get("title") or song,
                    lyrics=lyrics,
                    timed_lyrics=timed_lyrics,
                    sync_precision="LINE" if timed_lyrics else "NONE",
                    album=data.get("album"),
                    duration=data.get("duration"),
                )
            except Exception:
                return None
