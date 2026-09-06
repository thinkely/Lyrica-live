"""
sources package initialization.
"""

from .base_fetcher import BaseFetcher, build_result, parse_lrc
from .lrclib_fetcher import LRCLIBFetcher
from .lrcmux_fetcher import LrcmuxFetcher
from .genius_fetcher import GeniusFetcher
from .apple_music_fetcher import AppleMusicFetcher
from .hosted_lyrica_fetcher import HostedLyricaFetcher

__all__ = [
    "BaseFetcher",
    "build_result",
    "parse_lrc",
    "LRCLIBFetcher",
    "LrcmuxFetcher",
    "GeniusFetcher",
    "AppleMusicFetcher",
    "HostedLyricaFetcher",
]
