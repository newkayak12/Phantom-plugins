#!/usr/bin/env python3
"""
Builds one plugin and records the release in .phantom/marketplace.json:

    python3 tools/release.py db            # build, test, update marketplace.json
    python3 tools/release.py db --publish  # also create the GitHub release <dir>-<version> with the zip

The version comes from <dir>/build.gradle.kts. The release tag is `<dir>-<version>` and the asset is the zip that
`buildPlugin` produces, so the marketplace entry's "download" is `<dir>-<version>/<dir>-<version>.zip`.
Commit and push marketplace.json after publishing: Phantom reads it from the default branch.
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MARKETPLACE = ROOT / ".phantom" / "marketplace.json"


def main(folder: str, publish: bool) -> None:
    build_file = ROOT / folder / "build.gradle.kts"
    version = re.search(r'^version = "([^"]+)"', build_file.read_text(), re.M).group(1)
    tag = f"{folder}-{version}"
    zip_path = ROOT / folder / "build" / "distributions" / f"{tag}.zip"

    marketplace = json.loads(MARKETPLACE.read_text())
    entry = next((p for p in marketplace["plugins"] if p.get("source") == folder), None)
    if entry is None:
        sys.exit(f'{folder} has no entry in {MARKETPLACE.relative_to(ROOT)}; add one with "source": "{folder}"')
    entry["version"] = version
    entry["download"] = f"{tag}/{zip_path.name}"
    MARKETPLACE.write_text(json.dumps(marketplace, indent=2, ensure_ascii=False) + "\n")
    # After marketplace.json: some plugin tests (db) check that it names the version being built
    subprocess.run([str(ROOT / "gradlew"), f":{folder}:test", f":{folder}:buildPlugin"], cwd=ROOT, check=True)
    if not zip_path.exists():
        sys.exit(f"{zip_path} was not built")
    print(f"{entry['id']} {version}: {zip_path.relative_to(ROOT)}")

    if publish:
        notes = f"{entry['name']} {version}"
        subprocess.run(["gh", "release", "create", tag, str(zip_path), "--title", notes, "--notes", notes], cwd=ROOT, check=True)
        print("Released. Commit and push .phantom/marketplace.json so Phantom sees the new version.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], "--publish" in sys.argv[2:])
