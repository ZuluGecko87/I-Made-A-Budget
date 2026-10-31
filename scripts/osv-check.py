#!/usr/bin/env python3
"""Checks Gradle dependencies against the OSV vulnerability database (CVEs and GitHub advisories).

Reads `gradle dependencies` / `buildEnvironment` output on stdin, queries https://osv.dev for
every group:artifact:version, prints what it finds and exits 1 if anything is vulnerable.

    ./gradlew -q buildEnvironment :composeApp:dependencies | python3 scripts/osv-check.py
"""
import json
import re
import sys
import urllib.request

text = sys.stdin.read()
if "FAILED" in text:
    sys.exit("Some dependencies didn't resolve (FAILED in the Gradle output), so they can't be checked.")
deps = set()
for m in re.finditer(r"([\w.-]+):([\w.-]+):(\d[^\s()]*)(?: -> (\d[^\s()]*))?", text):
    group, artifact, requested, resolved = m.groups()
    deps.add(f"{group}:{artifact}:{resolved or requested}")
deps = sorted(deps)
if not deps:
    sys.exit("No dependencies found in the input.")

queries = [{"package": {"ecosystem": "Maven", "name": d.rsplit(":", 1)[0]}, "version": d.rsplit(":", 1)[1]} for d in deps]
results = []
for i in range(0, len(queries), 500):
    req = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries[i:i + 500]}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        results += json.load(r)["results"]

found = [(d, [v["id"] for v in r.get("vulns", [])]) for d, r in zip(deps, results) if r.get("vulns")]
print(f"Checked {len(deps)} dependencies against OSV: {len(found)} with known vulnerabilities.")
for d, ids in found:
    print(f"  {d}: {', '.join(ids)}")
sys.exit(1 if found else 0)
