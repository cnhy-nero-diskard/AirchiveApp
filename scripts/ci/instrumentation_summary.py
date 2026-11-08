#!/usr/bin/env python3
"""Summarize Android instrumentation JUnit XML and fail on failures or no tests."""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def single_line(value: str) -> str:
    return " ".join(value.split())


def markdown_code(value: str) -> str:
    return value.replace("\\", "\\\\").replace("`", "\\`")


def testcase_name(testcase: ET.Element) -> str:
    classname = single_line(testcase.get("classname", "unknown class"))
    name = single_line(testcase.get("name", "unknown test"))
    return f"{classname}#{name}"


def summarize(results_dir: Path) -> tuple[int, int, int, int, list[str], int]:
    files = sorted(results_dir.rglob("*.xml")) if results_dir.exists() else []
    passed = 0
    failed = 0
    skipped = 0
    failures: list[str] = []

    for report in files:
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            raise ValueError(f"Cannot parse JUnit report {report}: {error}") from error

        for testcase in root.iter("testcase"):
            failure_nodes = [node for node in testcase if node.tag in {"failure", "error"}]
            if failure_nodes:
                failed += 1
                details = single_line(failure_nodes[0].get("message", ""))
                failures.append(f"{testcase_name(testcase)} — {details}".rstrip(" —"))
            elif any(node.tag == "skipped" for node in testcase):
                skipped += 1
            else:
                passed += 1

    return passed + failed, passed, failed, skipped, failures, len(files)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--summary", type=Path, default=os.environ.get("GITHUB_STEP_SUMMARY"))
    args = parser.parse_args()

    try:
        executed, passed, failed, skipped, failures, file_count = summarize(args.results_dir)
    except ValueError as error:
        message = f"Instrumentation results are invalid: {error}"
        print(f"::error::{message}", file=sys.stderr)
        return 1

    lines = [
        "## Instrumentation test results",
        "",
        f"- Executed: {executed}",
        f"- Passed: {passed}",
        f"- Failed: {failed}",
        f"- Skipped: {skipped}",
        f"- JUnit XML files: {file_count}",
    ]
    if failures:
        lines.extend(["", "### Failed tests", ""])
        lines.extend("- `" + markdown_code(failure) + "`" for failure in failures)
    summary = "\n".join(lines) + "\n"

    if args.summary:
        try:
            with args.summary.open("a", encoding="utf-8") as output:
                output.write(summary)
        except OSError as error:
            print(f"::error::Cannot write job summary {args.summary}: {error}", file=sys.stderr)
            return 1
    else:
        print(summary, end="")

    print(
        f"Instrumentation totals: executed={executed}, passed={passed}, "
        f"failed={failed}, skipped={skipped}"
    )
    if failed:
        for failure in failures:
            print(f"::error::Instrumentation test failed: {failure}", file=sys.stderr)
    if failed or executed == 0:
        if executed == 0:
            print("::error::Instrumentation run executed zero tests", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
