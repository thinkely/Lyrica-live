"""
Unit tests for embedded Lyrica Python bridge and normalizers.
"""

import unittest
import json
import sys
import os

# Add parent directory to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from normalizer import normalize_text, generate_stable_track_key, calculate_duration_bucket
from sources.base_fetcher import parse_lrc, build_result
import lyrica_bridge


class TestNormalizer(unittest.TestCase):
    def test_normalize_artist_and_title(self):
        title = "Blinding Lights (Remastered 2020) [feat. Rosalia]"
        norm = normalize_text(title)
        self.assertEqual(norm, "blinding lights")

        artist = "The Weeknd feat. Daft Punk"
        self.assertEqual(normalize_text(artist), "the weeknd")

    def test_duration_bucket(self):
        # 123400ms = 123.4s -> bucket 120s
        self.assertEqual(calculate_duration_bucket(123400), 120)
        # 124800ms = 124.8s -> bucket 120s
        self.assertEqual(calculate_duration_bucket(124800), 120)
        # 127100ms = 127.1s -> bucket 125s
        self.assertEqual(calculate_duration_bucket(127100), 125)

    def test_stable_track_key_consistency(self):
        key1 = generate_stable_track_key("The Weeknd", "Blinding Lights", 200000)
        key2 = generate_stable_track_key("the weeknd (feat. someone)", "Blinding Lights (Remastered)", 202000)
        self.assertEqual(key1, key2)


class TestLrcParser(unittest.TestCase):
    def test_lrc_parsing(self):
        sample_lrc = """[00:12.50] Yeah, I've been tryna call
[00:15.80] I've been on my own for long enough
[00:20.10] Maybe you can show me how to love"""
        parsed = parse_lrc(sample_lrc, total_duration_ms=60000)
        self.assertEqual(len(parsed), 3)
        self.assertEqual(parsed[0]["text"], "Yeah, I've been tryna call")
        self.assertEqual(parsed[0]["start_time"], 12500)
        self.assertEqual(parsed[0]["end_time"], 15800)
        self.assertEqual(parsed[1]["start_time"], 15800)
        self.assertEqual(parsed[1]["end_time"], 20100)
        self.assertEqual(parsed[2]["start_time"], 20100)
        self.assertEqual(parsed[2]["end_time"], 60000)


class TestLyricaBridge(unittest.TestCase):
    def test_normalize_track_bridge(self):
        res_json = lyrica_bridge.normalize_track("Coldplay feat. BTS", "My Universe (Official Video)", duration_ms=228000)
        res = json.loads(res_json)
        self.assertEqual(res["normalized_artist"], "coldplay")
        self.assertEqual(res["normalized_title"], "my universe")
        self.assertTrue("stable_key" in res)
        self.assertGreater(len(res["stable_key"]), 10)


if __name__ == "__main__":
    unittest.main()
