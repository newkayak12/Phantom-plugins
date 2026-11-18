# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
"""Measure how long Phantom needs to index a freshly opened git worktree or clone.

Reads the indexing diagnostics Phantom dumps (needs -Dintellij.indexes.diagnostics.dump=true).
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DUMP_OPTION = "-Dintellij.indexes.diagnostics.dump=true"
QUIET_SECONDS = 60
FIRST_DUMP_TIMEOUT = 600
MEDIAN_KEYS = ("contentIndexingMs", "contentLoadingMs", "scanningMs", "openToSmartMs", "filesFromCache")


def parse_time(text):
    """ISO zoned time as written by the diagnostics; a trailing [Zone] is dropped."""
    text = re.sub(r"\[.*\]$", "", text)
    # Python < 3.11 accepts only 3 or 6 fraction digits; the JVM may emit up to 9.
    text = re.sub(r"\.(\d+)", lambda m: "." + m.group(1)[:6].ljust(6, "0"), text, count=1)
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return datetime.fromisoformat(text)


def _ms(nanos):
    return nanos // 1_000_000


def summarize(project_dir, t0):
    content = scanning = loading = 0
    last_end = None
    files_from_cache = 0
    non_module = {}
    part, total = {}, {}
    for f in sorted(Path(project_dir).glob("diagnostic-*.json")):
        doc = json.loads(f.read_text())
        hist = doc["projectIndexingActivityHistory"]
        times = hist["times"]
        if parse_time(times["updatingStart"]) < t0:
            continue  # left over from an earlier run
        wall = times["totalWallTimeWithPauses"] - times["wallTimeOnPause"]
        end = parse_time(times["updatingEnd"])
        last_end = end if last_end is None or end > last_end else last_end
        if doc["type"] == "Scanning":
            scanning += wall
            continue
        content += wall
        loading += times.get("contentLoadingVisibleTime", 0)
        for p in hist.get("fileProviderStatistics", []):
            files_from_cache += p.get("totalNumberOfFilesFullyIndexedByExtensions", 0)
            name = p["providerName"]
            if not name.startswith("Module"):
                non_module[name] = non_module.get(name, 0) + p.get("totalNumberOfIndexedFiles", 0)
        for s in hist.get("totalStatsPerIndexer", []):
            share = s["partOfTotalIndexingTime"]
            part[s["indexId"]] = part.get(s["indexId"], 0) + share["part"]
            total[s["indexId"]] = total.get(s["indexId"], 0) + share["total"]
    return {
        "contentIndexingMs": _ms(content),
        "scanningMs": _ms(scanning),
        "contentLoadingMs": _ms(loading),
        "openToSmartMs": round((last_end - t0).total_seconds() * 1000) if last_end else None,
        "filesFromCache": files_from_cache,
        "nonModuleFilesIndexed": non_module,
        "indexerShare": {k: part[k] / total[k] if total[k] else 0.0 for k in part},
    }


def medians(runs):
    out = {}
    for k in MEDIAN_KEYS:
        values = [r[k] for r in runs if r[k] is not None]
        out[k] = statistics.median(values) if values else None
    out["nonModuleAllZero"] = all(v == 0 for r in runs for v in r["nonModuleFilesIndexed"].values())
    return out


def _diag_files(logs, dir_name):
    root = Path(logs) / "indexing-diagnostic"
    return {p for d in root.glob(dir_name + ".*") for p in d.glob("diagnostic-*.json")}


def _project_dir(logs, dir_name):
    root = Path(logs) / "indexing-diagnostic"
    dirs = [d for d in root.glob(dir_name + ".*") if d.is_dir()]
    return max(dirs, key=lambda d: d.stat().st_mtime) if dirs else None


def _wait_quiet(logs, dir_name, before):
    """Wait for the first new diagnostic JSON, then until none has appeared for QUIET_SECONDS."""
    deadline = time.time() + FIRST_DUMP_TIMEOUT
    while _diag_files(logs, dir_name) <= before:
        if time.time() > deadline:
            sys.exit(f"No new indexing diagnostics for {dir_name} after {FIRST_DUMP_TIMEOUT} s. "
                     "Is the project open and the dump option active?")
        time.sleep(2)
    seen = _diag_files(logs, dir_name)
    last_change = time.time()
    while time.time() - last_change < QUIET_SECONDS:
        time.sleep(2)
        now = _diag_files(logs, dir_name)
        if now != seen:
            seen, last_change = now, time.time()


def _check_vmoptions(config):
    f = Path(config).expanduser() / "idea.vmoptions"
    if not f.exists() or DUMP_OPTION not in f.read_text().split():
        sys.exit(f"Missing {DUMP_OPTION} in {f}.\n"
                 "Add it via Help > Edit Custom VM Options and restart Phantom.")


def run_once(args, n):
    parent = Path(args.parent).expanduser()
    parent.mkdir(parents=True, exist_ok=True)
    target = parent / f"{args.label}-{n}"
    if args.mode == "clone":
        subprocess.run(["git", "clone", "--local", args.repo, str(target)], check=True)
    else:
        subprocess.run(["git", "-C", args.repo, "worktree", "add", str(target), "HEAD"], check=True)
    logs = Path(args.logs).expanduser()
    try:
        before = _diag_files(logs, target.name)
        t0 = datetime.now(timezone.utc)
        subprocess.run(["open", "-a", args.app, str(target)], check=True)
        _wait_quiet(logs, target.name, before)
        result = summarize(_project_dir(logs, target.name), t0)
        print(f"run {n}: {json.dumps(result)}")
        input("Close that project window, then press Enter... ")
        return result
    finally:
        if args.mode == "clone":
            subprocess.run(["rm", "-rf", str(target)])
        else:
            subprocess.run(["git", "-C", args.repo, "worktree", "remove", "--force", str(target)])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--mode", choices=["worktree", "clone"], default="worktree")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--app", default="/Applications/Phantom IDEA.app")
    ap.add_argument("--logs", default="~/Library/Logs/Phantom/PhantomIDEA")
    ap.add_argument("--config", default="~/Library/Application Support/Phantom/PhantomIDEA")
    ap.add_argument("--parent", default="~/li-measure")
    ap.add_argument("--label", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    args.repo = str(Path(args.repo).expanduser().resolve())
    _check_vmoptions(args.config)
    print(f"Add {Path(args.parent).expanduser()} to Settings > Build > Trusted Locations once, "
          "so the trust dialog does not skew timing.")
    runs = [run_once(args, n) for n in range(1, args.runs + 1)]
    out = {"label": args.label, "mode": args.mode, "runs": runs, "medians": medians(runs)}
    Path(args.out).expanduser().parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).expanduser().write_text(json.dumps(out, indent=2))
    print(json.dumps(out["medians"], indent=2))


if __name__ == "__main__":
    main()
