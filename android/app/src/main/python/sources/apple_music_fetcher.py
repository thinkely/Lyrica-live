"""
sources/apple_music_fetcher.py — Apple Music provider with TTML line/word/syllable timing parser.
"""

import re
import xml.etree.ElementTree as ET
import httpx
from .base_fetcher import BaseFetcher, build_result

_AMP_BASE_URL = "https://amp-api.music.apple.com/v1"
_PUBLIC_BASE_URL = "https://api.music.apple.com/v1"

# Standard time format parser for TTML: "00:01.234" or "01:23.456" or "12.34s"
_TIME_RE = re.compile(r"(?:(?:(\d+):)?(\d+):)?(\d+(?:\.\d+)?)")

def _parse_ttml_time(time_str: str | None) -> int:
    if not time_str:
        return 0
    time_str = time_str.strip().rstrip("s")
    parts = time_str.split(":")
    try:
        if len(parts) == 3: # hh:mm:ss.ms
            return int((int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])) * 1000)
        elif len(parts) == 2: # mm:ss.ms
            return int((int(parts[0]) * 60 + float(parts[1])) * 1000)
        else:
            return int(float(parts[0]) * 1000)
    except Exception:
        return 0


class AppleMusicFetcher(BaseFetcher):
    source_name = "apple_music"

    async def fetch(
        self,
        artist: str,
        song: str,
        developer_token: str | None = None,
        user_token: str | None = None,
        storefront: str = "us",
        timestamps: bool = True,
        **kwargs
    ) -> dict | None:
        if not developer_token:
            return None

        headers = {
            "Origin": "https://music.apple.com",
            "Referer": "https://music.apple.com/",
            "Authorization": f"Bearer {developer_token}",
            "User-Agent": "LyricaLive/1.0",
        }
        if user_token:
            headers["Music-User-Token"] = user_token

        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0), follow_redirects=True) as client:
                # Search track ID
                search_url = f"{_PUBLIC_BASE_URL}/catalog/{storefront}/search"
                resp = await client.get(
                    search_url,
                    params={"term": f"{artist} {song}", "types": "songs", "limit": 5},
                    headers=headers
                )
                if resp.status_code != 200:
                    return None

                songs = resp.json().get("results", {}).get("songs", {}).get("data", [])
                if not songs:
                    return None

                song_obj = songs[0]
                song_id = song_obj.get("id")
                song_attrs = song_obj.get("attributes", {})

                # Fetch syllable lyrics TTML
                lyrics_url = f"{_AMP_BASE_URL}/catalog/{storefront}/songs/{song_id}/syllable-lyrics"
                lyrics_resp = await client.get(
                    lyrics_url,
                    params={"syllabus": "true", "word": "true", "timestamps": "true"},
                    headers=headers
                )

                if lyrics_resp.status_code != 200:
                    # Fallback to standard lyrics endpoint
                    lyrics_url = f"{_AMP_BASE_URL}/catalog/{storefront}/songs/{song_id}/lyrics"
                    lyrics_resp = await client.get(lyrics_url, headers=headers)
                    if lyrics_resp.status_code != 200:
                        return None

                data = lyrics_resp.json().get("data", [])
                if not data:
                    return None

                ttml_content = data[0].get("attributes", {}).get("ttml")
                if not ttml_content:
                    return None

                return self._parse_ttml(ttml_content, song_attrs.get("artistName") or artist, song_attrs.get("name") or song, song_attrs)

        except Exception:
            return None

    def _parse_ttml(self, ttml_xml: str, fallback_artist: str, fallback_song: str, attrs: dict) -> dict | None:
        try:
            # Strip namespaces for simple parsing
            clean_xml = re.sub(r'\sxmlns(:\w+)?="[^"]+"', '', ttml_xml, count=0)
            root = ET.fromstring(clean_xml)

            timed_lines = []
            plain_lines = []
            has_syllable = False
            has_word = False

            for p_idx, p in enumerate(root.iter("p")):
                begin_attr = p.attrib.get("begin")
                end_attr = p.attrib.get("end")
                line_start = _parse_ttml_time(begin_attr)
                line_end = _parse_ttml_time(end_attr) if end_attr else (line_start + 4000)

                line_words = []
                # Check for spans (words / syllables)
                spans = list(p.iter("span"))
                if spans:
                    for s in spans:
                        span_text = "".join(s.itertext()).strip()
                        if not span_text:
                            continue
                        s_begin = _parse_ttml_time(s.attrib.get("begin")) or line_start
                        s_end = _parse_ttml_time(s.attrib.get("end")) or (s_begin + 400)
                        
                        # Syllable or word
                        syllables = []
                        if s.attrib.get("role") == "syllable" or s.attrib.get("type") == "syllable":
                            has_syllable = True
                            syllables.append({"text": span_text, "start_time": s_begin, "end_time": s_end})
                        else:
                            has_word = True

                        line_words.append({
                            "text": span_text,
                            "start_time": s_begin,
                            "end_time": s_end,
                            "syllables": syllables
                        })

                full_text = "".join(p.itertext()).strip()
                if full_text:
                    plain_lines.append(full_text)
                    timed_lines.append({
                        "id": f"am_{p_idx}",
                        "text": full_text,
                        "start_time": line_start,
                        "end_time": line_end,
                        "words": line_words
                    })

            if not plain_lines:
                return None

            precision = "SYLLABLE" if has_syllable else ("WORD" if has_word else "LINE")

            return build_result(
                source="apple_music",
                artist=attrs.get("artistName") or fallback_artist,
                title=attrs.get("name") or fallback_song,
                album=attrs.get("albumName"),
                duration=attrs.get("durationInMillis"),
                isrc=attrs.get("isrc"),
                lyrics="\n".join(plain_lines),
                timed_lyrics=timed_lines,
                sync_precision=precision,
            )
        except Exception:
            return None
