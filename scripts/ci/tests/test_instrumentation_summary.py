#!/usr/bin/env python3
"""Exercise the instrumentation summary script against representative JUnit XML."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ci/instrumentation_summary.py"
FIXTURES = Path(__file__).parent / "fixtures/instrumentation"


class InstrumentationSummaryTests(unittest.TestCase):
    def run_fixture(self, name: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(FIXTURES / name), "--summary", str(summary)],
                capture_output=True,
                text=True,
                check=False,
            )
            return result, summary.read_text(encoding="utf-8") if summary.exists() else ""

    def test_all_passed(self) -> None:
        result, summary = self.run_fixture("all-passed")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("- Executed: 2", summary)
        self.assertIn("- Passed: 2", summary)
        self.assertIn("- Failed: 0", summary)
        self.assertIn("- Skipped: 0", summary)

    def test_one_failure_fails_and_names_the_test(self) -> None:
        result, summary = self.run_fixture("one-failure")
        self.assertEqual(result.returncode, 1)
        self.assertIn("OneFailureTest#fails", summary)
        self.assertIn("- Failed: 1", summary)

    def test_live_tests_are_counted_as_skipped(self) -> None:
        result, summary = self.run_fixture("live-tests-skipped")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("- Executed: 1", summary)
        self.assertIn("- Passed: 1", summary)
        self.assertIn("- Skipped: 2", summary)

    def test_zero_executed_tests_fails(self) -> None:
        result, summary = self.run_fixture("no-tests")
        self.assertEqual(result.returncode, 1)
        self.assertIn("- Executed: 0", summary)
        self.assertIn("- Skipped: 0", summary)


if __name__ == "__main__":
    unittest.main()
