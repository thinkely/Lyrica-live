"""
normalizer.py — Metadata normalization and stable track key generation.
"""

import re
import unicodedata
import hashlib

# Regex to strip featuring artists, remastered, acoustic, live tags, parenthetical years/editions
_FEAT_RE = re.compile(
    r"\s*[\(\[\{]?(?:feat\.?|featuring|ft\.?|with)\s+[^()\[\]{}]+[\)\]\}]?",
    re.IGNORECASE
)
_EXTRA_TAGS_RE = re.compile(
    r"\s*[\(\[\{][^()\[\]{}]*(?:remaster|deluxe|bonus|edit|official|lyric video|live|acoustic|mono|stereo|anniversary|edition|version|audio|\b\d{4}\b)[^()\[\]{}]*[\)\]\}]",
    re.IGNORECASE
)
_SPECIAL_CHARS_RE = re.compile(r"[^\w\s]", re.UNICODE)

_WHITESPACE_RE = re.compile(r"\s+")


def normalize_text(text: str | None) -> str:
    """Normalize text by converting to lowercase, unaccenting, and stripping extra metadata."""
    if not text:
        return ""
    # Normalize unicode (decompose accented chars)
    normalized = unicodedata.normalize("NFKD", str(text))
    # Encode/decode to strip non-ASCII or keep clean unicode
    clean = "".join(c for c in normalized if not unicodedata.combining(c))
    
    # Strip feat / remastered
    clean = _FEAT_RE.sub("", clean)
    clean = _EXTRA_TAGS_RE.sub("", clean)
    
    # Remove punctuation
    clean = _SPECIAL_CHARS_RE.sub(" ", clean)
    # Collapse whitespace
    clean = _WHITESPACE_RE.sub(" ", clean).strip().lower()
    return clean


def calculate_duration_bucket(duration_ms: int | float | None, bucket_seconds: int = 5) -> int:
    """Bucket duration into 5-second intervals to absorb minor playback duration discrepancies."""
    if not duration_ms or duration_ms <= 0:
        return 0
    duration_sec = int(duration_ms / 1000)
    return (duration_sec // bucket_seconds) * bucket_seconds


def generate_stable_track_key(artist: str | None, title: str | None, duration_ms: int | float | None = None) -> str:
    """
    Generate a deterministic track key based on normalized metadata.
    Prevents redundant fetches during active playback.
    """
    norm_artist = normalize_text(artist)
    norm_title = normalize_text(title)
    dur_bucket = calculate_duration_bucket(duration_ms)
    
    raw_key = f"{norm_artist}:::{norm_title}:::{dur_bucket}"
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()
