#!/usr/bin/env python3
"""Safely reclaim RF Lab's private GitHub Actions *artifact* storage.

Only removes old APK upload-artifacts. Keeps the newest two per artifact name,
and excludes artifacts created in the last 20 minutes. GitHub Releases (the
user-facing test APK downloads), run logs, repository code and cache are NOT
modified. Requires Actions write permission in the GitHub Actions token.
"""
from __future__ import annotations
from collections import Counter
from datetime import datetime, timezone, timedelta
import json
import os
import sys
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GITHUB_TOKEN"]
BASE = "https://api.github.com/repos/" + REPO
KEEP = max(1, int(os.environ.get("CLEANUP_KEEP_LATEST_PER_NAME", "2")))
MIN_AGE = max(10, int(os.environ.get("CLEANUP_MIN_AGE_MINUTES", "20")))
CUTOFF = datetime.now(timezone.utc) - timedelta(minutes=MIN_AGE)
SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY")
DRY = os.environ.get("CLEANUP_DRY_RUN", "false").lower() == "true"

def api(method: str, route: str):
    req = Request(
        BASE + route,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + TOKEN,
            "User-Agent": "RF-Lab-actions-artifact-retention",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    for attempt in range(4):
        try:
            with urlopen(req, timeout=35) as rsp:
                body = rsp.read()
                return json.loads(body) if body else None
        except HTTPError as err:
            details = err.read().decode("utf-8", "replace")[:400]
            if err.code not in (429, 502, 503) or attempt == 3:
                raise RuntimeError(
                    "GitHub API " + method + " " + route + " HTTP " +
                    str(err.code) + ": " + details) from err
            time.sleep(2 ** attempt)
        except URLError:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)

def list_artifacts() -> list[dict]:
    artifacts = []
    for page in range(1, 101):
        chunk = api("GET", "/actions/artifacts?per_page=100&page=" +
                    str(page))["artifacts"]
        artifacts.extend(chunk)
        if len(chunk) < 100:
            break
    else:
        raise RuntimeError("More than 10,000 artifacts: refusing partial scan")
    return artifacts

def is_generated_apk(artifact: dict) -> bool:
    name = artifact.get("name", "").lower()
    # Deliberately narrow deletion scope: don't remove future user data,
    # scan exports, raw measurements, debugging bundles or unknown artifacts.
    return "apk" in name and ("rf-lab" in name or "openvins" in name)

def gb(n: int) -> str:
    return f"{n / (1024 ** 3):.2f} GiB"

def created_at(artifact: dict) -> datetime:
    return datetime.fromisoformat(artifact["created_at"].replace("Z", "+00:00"))

def main():
    entries = list_artifacts()
    total_before = sum(int(a.get("size_in_bytes", 0)) for a in entries)
    grouped: dict[str, list[dict]] = {}
    for item in entries:
        if is_generated_apk(item):
            grouped.setdefault(item["name"], []).append(item)
    doomed = []
    retained = []
    for name, items in sorted(grouped.items()):
        items.sort(key=lambda a: (a["created_at"], a["id"]), reverse=True)
        for ix, item in enumerate(items):
            if (ix < KEEP or created_at(item) > CUTOFF):
                retained.append(item)
            else:
                doomed.append(item)

    doomed.sort(key=lambda a: (a["created_at"], a["id"]))
    candidate_bytes = sum(a["size_in_bytes"] for a in doomed)
    print("Repository:", REPO, flush=True)
    print("Artifacts before:", len(entries), gb(total_before), flush=True)
    print("Temporary Android APKs:", sum(map(len, grouped.values())),
          "across", len(grouped), "names", flush=True)
    print("Protect latest", KEEP, "per artifact name and recent",
          MIN_AGE, "minutes", flush=True)
    print("To remove:", len(doomed), gb(candidate_bytes), flush=True)
    for name, items in sorted(grouped.items()):
        sizes = sum(int(a.get("size_in_bytes", 0)) for a in items)
        print("   ", name, ":", len(items), "artifacts,", gb(sizes), flush=True)

    if not doomed:
        print("Nothing to clean.")
        return

    deleted = 0
    freed = 0
    errors = []
    for index, art in enumerate(doomed, 1):
        if DRY:
            print("DRY RUN", art["id"], art["name"])
            continue
        try:
            api("DELETE", "/actions/artifacts/" + str(art["id"]))
            deleted += 1
            freed += art["size_in_bytes"]
        except Exception as e:
            errors.append(str(e))
            print("Deletion error:", e, flush=True)
            # Abort on permission error instead of silently claiming success.
            if "HTTP 403" in str(e) or "HTTP 401" in str(e):
                break
        if index % 25 == 0:
            print("Progress:", index, "/", len(doomed),
                  "deleted", deleted, "reclaimed", gb(freed), flush=True)
        time.sleep(0.09)

    after = list_artifacts()
    total_after = sum(int(a.get("size_in_bytes", 0)) for a in after)
    rows = [
        "## GitHub Actions artifact storage cleanup",
        "",
        "- Repository: `" + REPO + "`",
        "- Artifacts before: **" + str(len(entries)) + "**, " +
            gb(total_before),
        "- Artifacts deleted: **" + str(deleted) + "**",
        "- Space reclaimed (reported file sizes): **" + gb(freed) + "**",
        "- Artifacts after: **" + str(len(after)) + "**, " +
            gb(total_after),
        "- Retained newest " + str(KEEP) + " of every temporary APK type",
        "- **Untouched:** GitHub Releases, source code, GitHub Actions job logs, caches and non-APK artifacts",
        "- Note: GitHub's billed storage usage may update with a delay",
    ]
    if errors:
        rows.append("- Errors: " + str(len(errors)))
        rows.extend("  - " + x for x in errors[:5])
    result = "\n".join(rows) + "\n"
    print(result, flush=True)
    if SUMMARY:
        with open(SUMMARY, "a", encoding="utf-8") as f:
            f.write(result)
    if errors:
        sys.exit(1)

if __name__ == "__main__":
    main()
