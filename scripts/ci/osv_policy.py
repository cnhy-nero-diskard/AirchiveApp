#!/usr/bin/env python3
"""Validate OSV exceptions and fail scans for high or critical advisories."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


EXCEPTION_FIELDS = {"id", "reason", "ignoreUntil"}
SEVERITY_RANK = {
    "unknown": 0,
    "low": 1,
    "medium": 2,
    "moderate": 2,
    "high": 3,
    "critical": 4,
}


def _without_comment(line: str) -> str:
    in_string = False
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
        elif char == "\\" and in_string:
            escaped = True
        elif char == '"':
            in_string = not in_string
        elif char == "#" and not in_string:
            return line[:index]
    return line


def parse_exception_config(config_path: Path) -> list[dict[str, Any]]:
    """Parse the deliberately small TOML subset allowed for advisory exceptions.

    OSV-Scanner validates the file as TOML when it loads it. This parser keeps the
    CI policy compatible with the Python 3.9 interpreter on the owner's Mac while
    rejecting broad package overrides and every exception field beyond the three
    required values.
    """
    entries: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    saw_empty_array = False
    try:
        lines = config_path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValueError(str(error)) from error

    for line_number, raw_line in enumerate(lines, start=1):
        line = _without_comment(raw_line).strip()
        if not line:
            continue
        if line == "[[IgnoredVulns]]":
            if saw_empty_array:
                raise ValueError(f"line {line_number}: IgnoredVulns cannot also be an empty array")
            current = {}
            entries.append(current)
            continue
        if line.startswith("["):
            raise ValueError(f"line {line_number}: only [[IgnoredVulns]] tables are allowed")

        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*)\s*=\s*(.*?)\s*", line)
        if match is None:
            raise ValueError(f"line {line_number}: invalid exception setting")
        key, value = match.groups()
        if current is None:
            if key != "IgnoredVulns" or value != "[]" or entries or saw_empty_array:
                raise ValueError(f"line {line_number}: only IgnoredVulns = [] is allowed outside a table")
            saw_empty_array = True
            continue
        if key not in EXCEPTION_FIELDS:
            raise ValueError(f"line {line_number}: unsupported exception field {key!r}")
        if key in current:
            raise ValueError(f"line {line_number}: duplicate exception field {key!r}")
        if key in {"id", "reason"}:
            try:
                parsed_value = json.loads(value)
            except json.JSONDecodeError as error:
                raise ValueError(f"line {line_number}: {key} must be a quoted string") from error
            if not isinstance(parsed_value, str):
                raise ValueError(f"line {line_number}: {key} must be a quoted string")
            current[key] = parsed_value
        elif key == "ignoreUntil":
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                raise ValueError(f"line {line_number}: ignoreUntil must be an ISO date")
            try:
                current[key] = dt.date.fromisoformat(value)
            except ValueError as error:
                raise ValueError(f"line {line_number}: invalid ignoreUntil date") from error

    return entries


def fail(message: str) -> int:
    print(f"::error::{message}", file=sys.stderr)
    return 1


def validate_exceptions(config_path: Path) -> int:
    try:
        entries = parse_exception_config(config_path)
    except ValueError as error:
        return fail(f"Cannot read OSV exception config {config_path}: {error}")

    today = dt.datetime.now(dt.timezone.utc).date()
    seen_ids: set[str] = set()
    for index, entry in enumerate(entries, start=1):
        label = f"IgnoredVulns entry {index}"
        missing = EXCEPTION_FIELDS - set(entry)
        extra = set(entry) - EXCEPTION_FIELDS
        if missing:
            return fail(f"{label} is missing required fields: {', '.join(sorted(missing))}")
        if extra:
            return fail(f"{label} has unsupported fields: {', '.join(sorted(extra))}")

        vuln_id = entry["id"]
        if not isinstance(vuln_id, str) or not vuln_id.strip():
            return fail(f"{label} must have a non-empty advisory id")
        if vuln_id in seen_ids:
            return fail(f"Duplicate OSV exception id: {vuln_id}")
        seen_ids.add(vuln_id)

        reason = entry["reason"]
        if not isinstance(reason, str) or not reason.strip():
            return fail(f"OSV exception {vuln_id} must have a non-empty reason")

        expiry_date = entry["ignoreUntil"]
        if expiry_date <= today:
            return fail(
                f"OSV exception {vuln_id} expired on {expiry_date.isoformat()}"
            )

    print(f"Validated {len(entries)} OSV exception(s) in {config_path}")
    return 0


def cvss_v3_score(vector: str) -> float | None:
    """Calculate a CVSS 3.x base score from an OSV severity vector."""
    if not vector.startswith("CVSS:3."):
        return None
    fields = dict(item.split(":", 1) for item in vector.split("/")[1:] if ":" in item)
    required = {"AV", "AC", "PR", "UI", "S", "C", "I", "A"}
    if not required <= fields.keys():
        return None

    av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.20}.get(fields["AV"])
    ac = {"L": 0.77, "H": 0.44}.get(fields["AC"])
    ui = {"N": 0.85, "R": 0.62}.get(fields["UI"])
    impact_value = {"N": 0.0, "L": 0.22, "H": 0.56}
    c, i, a = (impact_value.get(fields[key]) for key in ("C", "I", "A"))
    if fields["S"] == "U":
        pr = {"N": 0.85, "L": 0.62, "H": 0.27}.get(fields["PR"])
    elif fields["S"] == "C":
        pr = {"N": 0.85, "L": 0.68, "H": 0.50}.get(fields["PR"])
    else:
        return None
    if None in (av, ac, pr, ui, c, i, a):
        return None

    impact_subscore = 1 - (1 - c) * (1 - i) * (1 - a)
    if fields["S"] == "U":
        impact = 6.42 * impact_subscore
    else:
        impact = 7.52 * (impact_subscore - 0.029) - 3.25 * (impact_subscore - 0.02) ** 15
    if impact <= 0:
        return 0.0
    exploitability = 8.22 * av * ac * pr * ui
    score = min(impact + exploitability, 10.0)
    if fields["S"] == "C":
        score = min(1.08 * score, 10.0)
    return math.ceil(score * 10 - 1e-9) / 10


def cvss_v2_score(vector: str) -> float | None:
    """Calculate a CVSS v2 base score from an OSV severity vector."""
    if vector.startswith("CVSS:2.0/"):
        vector = vector.split("/", 1)[1]
    elif vector.startswith("(") and vector.endswith(")"):
        vector = vector[1:-1]
    else:
        return None
    fields = dict(item.split(":", 1) for item in vector.split("/") if ":" in item)
    required = {"AV", "AC", "Au", "C", "I", "A"}
    if not required <= fields.keys():
        return None

    av = {"N": 1.0, "A": 0.646, "L": 0.395}.get(fields["AV"])
    ac = {"L": 0.71, "M": 0.61, "H": 0.35}.get(fields["AC"])
    auth = {"N": 0.704, "S": 0.56, "M": 0.45}.get(fields["Au"])
    impact_value = {"N": 0.0, "P": 0.275, "C": 0.660}
    c, i, a = (impact_value.get(fields[key]) for key in ("C", "I", "A"))
    if None in (av, ac, auth, c, i, a):
        return None

    impact = 10.41 * (1 - (1 - c) * (1 - i) * (1 - a))
    exploitability = 20 * av * ac * auth
    impact_factor = 0 if impact == 0 else 1.176
    base = ((0.6 * impact) + (0.4 * exploitability) - 1.5) * impact_factor
    return math.floor(base * 10 + 0.5) / 10


def severity(vulnerability: dict[str, Any]) -> tuple[str, float | None]:
    """Return the strongest severity declared by OSV, when one can be derived."""
    candidates: list[tuple[str, float | None]] = []

    database_specific = vulnerability.get("database_specific", {})
    database_severity = (
        database_specific.get("severity")
        if isinstance(database_specific, dict)
        else None
    )
    if isinstance(database_severity, str):
        normalized = database_severity.strip().lower()
        if normalized in SEVERITY_RANK:
            candidates.append((normalized, None))

    severity_values = vulnerability.get("severity", [])
    if not isinstance(severity_values, list):
        severity_values = []
    for item in severity_values:
        if not isinstance(item, dict):
            continue
        kind = item.get("type")
        score_value = item.get("score")
        score: float | None = None
        if isinstance(score_value, (int, float)):
            score = float(score_value)
        elif isinstance(score_value, str):
            score = cvss_v3_score(score_value)
            if score is None:
                score = cvss_v2_score(score_value)
            if score is None:
                try:
                    score = float(score_value)
                except ValueError:
                    pass

        if score is None:
            continue
        if kind == "CVSS_V2":
            label = "high" if score >= 7.0 else "medium" if score >= 4.0 else "low"
        else:
            label = "critical" if score >= 9.0 else "high" if score >= 7.0 else "medium" if score >= 4.0 else "low"
        candidates.append((label, score))

    if not candidates:
        return "unknown", None
    return max(candidates, key=lambda item: SEVERITY_RANK[item[0]])


def check_results(report_path: Path, scan_outcome: str) -> int:
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return fail(
            f"OSV-Scanner outcome was {scan_outcome}; could not read a complete "
            f"JSON report at {report_path}: {error}"
        )
    if not isinstance(report, dict) or not isinstance(report.get("results"), list):
        return fail(f"OSV-Scanner report at {report_path} has no results array")

    high_or_critical: list[str] = []
    unknown_severity: list[str] = []
    advisory_count = 0
    for source in report["results"]:
        if not isinstance(source, dict):
            return fail("OSV-Scanner report contains a malformed source entry")
        source_metadata = source.get("source", {})
        if not isinstance(source_metadata, dict):
            return fail("OSV-Scanner report contains malformed source metadata")
        source_path = source_metadata.get("path", "unknown lockfile")
        packages = source.get("packages", [])
        if not isinstance(packages, list):
            return fail(f"OSV-Scanner report has malformed packages for {source_path}")
        for package_entry in packages:
            if not isinstance(package_entry, dict):
                return fail(f"OSV-Scanner report has a malformed package in {source_path}")
            package = package_entry.get("package", {})
            if not isinstance(package, dict):
                return fail(f"OSV-Scanner report has malformed package metadata in {source_path}")
            package_name = package.get("name", "unknown package")
            package_version = package.get("version", "unknown version")
            vulnerabilities = package_entry.get("vulnerabilities", [])
            if not isinstance(vulnerabilities, list):
                return fail(f"OSV-Scanner report has malformed advisories for {package_name}")
            for vulnerability in vulnerabilities:
                if not isinstance(vulnerability, dict) or not isinstance(vulnerability.get("id"), str):
                    return fail(f"OSV-Scanner report has an advisory without an ID for {package_name}")
                advisory_count += 1
                label, score = severity(vulnerability)
                if label not in {"high", "critical"}:
                    if label == "unknown":
                        unknown_severity.append(
                            f"{package_name}@{package_version}: {vulnerability['id']} in {source_path}"
                        )
                    continue
                score_text = f" (CVSS {score:.1f})" if score is not None else ""
                high_or_critical.append(
                    f"{package_name}@{package_version}: {vulnerability['id']} "
                    f"[{label.upper()}{score_text}] in {source_path}"
                )

    if high_or_critical:
        print("High or critical OSV advisories found:", file=sys.stderr)
        for finding in high_or_critical:
            print(f"::error::{finding}", file=sys.stderr)
        return 1

    for finding in unknown_severity:
        print(f"::warning::OSV advisory has no recognized severity: {finding}")
    print(
        f"OSV scan passed: {advisory_count} advisory record(s); "
        "no high or critical advisories."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    validate = commands.add_parser("validate-exceptions")
    validate.add_argument("config", type=Path)

    results = commands.add_parser("check-results")
    results.add_argument("report", type=Path)
    results.add_argument("--scan-outcome", required=True)

    args = parser.parse_args()
    if args.command == "validate-exceptions":
        return validate_exceptions(args.config)
    return check_results(args.report, args.scan_outcome)


if __name__ == "__main__":
    raise SystemExit(main())
