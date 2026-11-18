# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
"""Append local Gradle JUnit results to the GitHub Actions job summary."""

import argparse
import glob
import html
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def escape(value):
    value = html.escape(" ".join(value.split()))
    return re.sub(r"([\\`*_{}\[\]()#+.!|>~-])", r"\\\1", value)


def tag(element):
    return element.tag.rsplit("}", 1)[-1]


def totals(element):
    suites = [child for child in element if tag(child) in {"testsuite", "testsuites"}]
    if suites:
        counts = [totals(suite) for suite in suites]
        return tuple(sum(count[index] for count in counts) for index in range(4))
    if tag(element) not in {"testsuite", "testsuites"}:
        raise ValueError("Expected a JUnit testsuite or testsuites root")
    cases = [child for child in element if tag(child) == "testcase"]
    defaults = [len(cases)] + [sum(any(tag(child) == kind for child in case) for case in cases)
                                   for kind in ("failure", "error", "skipped")]
    counts = tuple(int(element.get(key, defaults[index]))
                   for index, key in enumerate(("tests", "failures", "errors", "skipped")))
    if any(count < 0 for count in counts) or sum(counts[1:]) > counts[0]:
        raise ValueError("Invalid JUnit counts")
    return counts


def summarize(module, outcome, coverage=None, reports_pattern=None):
    reports = (sorted(Path(path) for path in glob.glob(reports_pattern, recursive=True)) if reports_pattern
               else sorted((Path(module) / "build/test-results/test").glob("TEST-*.xml")))
    lines = [f"## {escape(Path(module).name)} test results", "",
             f"Test step outcome: **{escape(outcome)}**."]
    counts = [0, 0, 0, 0]
    failures = []
    invalid = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
            current = totals(root)
        except (ET.ParseError, OSError, ValueError):
            invalid += 1
            continue
        counts = [left + right for left, right in zip(counts, current)]
        for case in root.iter():
            if tag(case) == "testcase" and any(tag(child) in {"failure", "error"} for child in case):
                if len(failures) < 10:
                    failures.append(escape(f"{case.get('classname', '')}.{case.get('name', '(unnamed)')}")[:500])
    if not reports:
        status = "Tests not run" if outcome in {"skipped", "cancelled", "not-run"} else "No JUnit reports produced"
        lines += [f"**{status}; no passing result can be inferred.**"]
    else:
        lines += ["", "| Tests | Failed | Errors | Skipped |", "| ---: | ---: | ---: | ---: |",
                  "| " + " | ".join(map(str, counts)) + " |"]
        if invalid:
            lines += [f"**{invalid} unreadable or invalid report(s); totals are incomplete.**"]
        if counts[0] == 0:
            lines += ["**No executed tests recorded; no passing result can be inferred.**"]
        if failures:
            lines += ["", "Failure examples (up to 10):"] + [f"- {failure}" for failure in failures]
    if coverage:
        try:
            root = ET.parse(coverage).getroot()
            counter = next(child for child in root if tag(child) == "counter" and child.get("type") == "LINE")
            covered, missed = int(counter.attrib["covered"]), int(counter.attrib["missed"])
            total = covered + missed
            if min(covered, missed) < 0 or total == 0:
                raise ValueError("No measured lines")
            lines += ["", f"Line coverage: {covered}/{total} ({covered / total:.2%})."]
        except (ET.ParseError, OSError, ValueError, KeyError, StopIteration):
            lines += ["", "Line coverage unavailable; see the coverage check and report artifact."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module")
    parser.add_argument("--outcome", required=True)
    parser.add_argument("--coverage")
    parser.add_argument("--reports", help="Override the default Gradle TEST-*.xml glob")
    args = parser.parse_args()
    summary = summarize(args.module, args.outcome, args.coverage, args.reports)
    print(summary)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
        output.write(summary)
