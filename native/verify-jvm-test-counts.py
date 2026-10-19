#!/usr/bin/env python3
"""Enforce the Phase H JVM test-count acceptance criteria from Gradle JUnit XML."""

from pathlib import Path
import xml.etree.ElementTree as ET

# // PROVISIONAL - pending GEN-PERF-1
ZERO = 0
# // PROVISIONAL - pending GEN-PERF-1
APP_TEST_MINIMUM = 156
# // PROVISIONAL - pending GEN-PERF-1
SAFETY_TEST_EXPECTED = 17


def summarize(directory: Path) -> dict[str, int]:
    totals = {name: ZERO for name in ("tests", "failures", "errors", "skipped")}
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"No JUnit XML reports found in {directory}")
    for report in reports:
        root = ET.parse(report).getroot()
        for name in totals:
            totals[name] += int(root.attrib.get(name, str(ZERO)))
    return totals


app = summarize(Path("app/build/test-results/testDebugUnitTest"))
safety = summarize(Path("safety/build/test-results/test"))
print(f"app: {app}")
print(f"safety: {safety}")

if app["tests"] < APP_TEST_MINIMUM:
    raise SystemExit(f"App tests {app['tests']} are below the required minimum {APP_TEST_MINIMUM}")
if safety["tests"] != SAFETY_TEST_EXPECTED:
    raise SystemExit(f"Safety tests {safety['tests']} do not equal {SAFETY_TEST_EXPECTED}")
for module, totals in (("app", app), ("safety", safety)):
    if any(totals[name] != ZERO for name in ("failures", "errors", "skipped")):
        raise SystemExit(f"{module} JUnit reports contain failures, errors, or skipped tests: {totals}")

print("Phase H JVM test-count acceptance criteria passed.")
