"""
lyrica_bridge.py — Main Python bridge invoked by Android via Chaquopy.
Executes provider fetchers directly without running any Flask or HTTP server daemon.
"""

import asyncio
import json
from normalizer import normalize_text, generate_stable_track_key, calculate_duration_bucket
from sources.lrclib_fetcher import LRCLIBFetcher
from sources.lrcmux_fetcher import LrcmuxFetcher
from sources.genius_fetcher import GeniusFetcher
from sources.apple_music_fetcher import AppleMusicFetcher
from sources.hosted_lyrica_fetcher import HostedLyricaFetcher

_FETCHERS = {
    "lrclib": LRCLIBFetcher(),
    "lrcmux": LrcmuxFetcher(),
    "genius": GeniusFetcher(),
    "apple_music": AppleMusicFetcher(),
    "hosted_lyrica": HostedLyricaFetcher(),
}


def normalize_track(artist: str | None, song: str | None, album: str | None = None, duration_ms: int | None = None) -> str:
    """Normalize track metadata and compute deterministic stable track key."""
    norm_artist = normalize_text(artist)
    norm_title = normalize_text(song)
    dur_bucket = calculate_duration_bucket(duration_ms)
    stable_key = generate_stable_track_key(artist, song, duration_ms)
    
    result = {
        "raw_artist": artist or "",
        "normalized_artist": norm_artist,
        "raw_title": song or "",
        "normalized_title": norm_title,
        "raw_album": album or "",
        "duration_ms": duration_ms or 0,
        "duration_bucket": dur_bucket,
        "stable_key": stable_key,
    }
    return json.dumps(result)


async def _async_fetch_single_provider(
    provider_name: str,
    artist: str,
    song: str,
    album: str | None,
    duration: int | None,
    credentials: dict,
    word_level: bool = True
) -> dict | None:
    fetcher = _FETCHERS.get(provider_name.lower())
    if not fetcher:
        return None

    if provider_name == "lrclib":
        # Always request synced (timed) lyrics only; never plain-text fallback
        return await fetcher.fetch(artist, song, timestamps=True, album=album, duration=duration)
    elif provider_name == "lrcmux":
        # Pass word_level preference from user settings
        return await fetcher.fetch(artist, song, timestamps=True, word_level=word_level)
    elif provider_name == "genius":
        token = credentials.get("genius_token")
        return await fetcher.fetch(artist, song, token=token)
    elif provider_name == "apple_music":
        dev_token = credentials.get("apple_developer_token")
        user_token = credentials.get("apple_user_token")
        storefront = credentials.get("apple_storefront", "us")
        return await fetcher.fetch(
            artist, song,
            developer_token=dev_token,
            user_token=user_token,
            storefront=storefront,
            timestamps=True
        )
    elif provider_name == "hosted_lyrica":
        base_url = credentials.get("hosted_url")
        return await fetcher.fetch(artist, song, base_url=base_url, timestamps=True)
    return None


def fetch_lyrics(
    artist: str,
    song: str,
    album: str | None = None,
    duration_ms: int | None = None,
    provider_name: str = "lrclib",
    credentials_json: str = "{}",
    word_level: bool = True
) -> str:
    """
    Fetch lyrics for a track from a specific provider.
    Only returns synced (timed) lyrics — plain-text results are rejected.
    Returns JSON string of the standardized LyricsDocument or JSON error object.

    Args:
        artist: Artist name
        song: Track title
        album: Album name (optional, improves matching)
        duration_ms: Track duration in milliseconds (optional, improves matching)
        provider_name: Which provider to use ('lrclib', 'lrcmux', etc.)
        credentials_json: JSON string of provider credentials
        word_level: Whether to request word-level sync from providers that support it (LRCMux)
    """
    if not artist or not song:
        return json.dumps({"status": "error", "message": "Missing artist or song"})

    try:
        credentials = json.loads(credentials_json) if credentials_json else {}
    except Exception:
        credentials = {}

    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        result = loop.run_until_complete(
            _async_fetch_single_provider(provider_name, artist, song, album, duration_ms, credentials, word_level)
        )
        loop.close()

        if result:
            # Reject plain-text-only results — we only want synced lyrics
            timed = result.get("timed_lyrics") or []
            if not timed:
                return json.dumps({
                    "status": "not_found",
                    "provider": provider_name,
                    "message": f"No synced lyrics found for {artist} - {song} (plain text omitted)"
                })
            result["status"] = "success"
            return json.dumps(result)
        else:
            return json.dumps({
                "status": "not_found",
                "provider": provider_name,
                "message": f"Lyrics not found for {artist} - {song}"
            })
    except Exception as e:
        return json.dumps({
            "status": "error",
            "provider": provider_name,
            "message": str(e)
        })
