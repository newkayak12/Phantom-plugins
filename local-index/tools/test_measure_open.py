# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import json
import tempfile
import unittest
from pathlib import Path

from measure_open import parse_time, summarize


def write(d, name, doc):
    (d / name).write_text(json.dumps(doc))


class SummarizeTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        self.end = "2026-11-18T10:00:15Z"
        write(self.dir, "diagnostic-1.json", {
            "type": "Scanning",
            "projectIndexingActivityHistory": {"times": {
                "updatingStart": "2026-11-18T10:00:01Z", "updatingEnd": "2026-11-18T10:00:03Z",
                "totalWallTimeWithPauses": 2_000_000_000, "wallTimeOnPause": 0}}})
        self._write_dumb()

    def _write_dumb(self):
        write(self.dir, "diagnostic-2.json", {
            "type": "DumbIndexing",
            "projectIndexingActivityHistory": {
                "times": {
                    "updatingStart": "2026-11-18T10:00:05Z", "updatingEnd": self.end,
                    "totalWallTimeWithPauses": 9_000_000_000, "wallTimeOnPause": 1_000_000_000,
                    "contentLoadingVisibleTime": 3_000_000_000},
                "fileProviderStatistics": [
                    {"providerName": "Module 'a'", "totalNumberOfIndexedFiles": 50,
                     "totalNumberOfFilesFullyIndexedByExtensions": 40},
                    {"providerName": "SDK JDK 21", "totalNumberOfIndexedFiles": 0,
                     "totalNumberOfFilesFullyIndexedByExtensions": 0},
                    {"providerName": "Library 'Gradle: x'", "totalNumberOfIndexedFiles": 0,
                     "totalNumberOfFilesFullyIndexedByExtensions": 0}],
                "totalStatsPerIndexer": [
                    {"indexId": "Stubs", "partOfTotalIndexingTime": {"part": 50, "total": 100}},
                    {"indexId": "Other", "partOfTotalIndexingTime": {"part": 50, "total": 100}}]}})

    def test_summarize(self):
        s = summarize(self.dir, t0=parse_time("2026-11-18T10:00:00Z"))
        self.assertEqual(s["contentIndexingMs"], 8000)
        self.assertEqual(s["scanningMs"], 2000)
        self.assertEqual(s["contentLoadingMs"], 3000)
        self.assertEqual(s["openToSmartMs"], 15000)
        self.assertEqual(s["filesFromCache"], 40)
        self.assertEqual(s["nonModuleFilesIndexed"], {"SDK JDK 21": 0, "Library 'Gradle: x'": 0})
        self.assertAlmostEqual(s["indexerShare"]["Stubs"], 0.5)

    def test_zone_suffix_is_accepted(self):
        self.end = "2026-11-18T10:00:15Z[UTC]"
        self._write_dumb()
        s = summarize(self.dir, t0=parse_time("2026-11-18T10:00:00Z"))
        self.assertEqual(s["openToSmartMs"], 15000)


if __name__ == "__main__":
    unittest.main()
