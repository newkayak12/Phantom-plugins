#!/usr/bin/env python3
"""
Builds one plugin and records the release in .phantom/marketplace.json:

    python3 tools/release.py db            # build, test, update marketplace.json
    python3 tools/release.py db --publish  # also create the GitHub release <dir>-<version> with the zip

The version comes from <dir>/build.gradle.kts. The release tag is `<dir>-<version>` and the asset is the zip that
`buildPlugin` produces, so the marketplace entry's "download" is `<dir>-<version>/<dir>-<version>.zip`.
Commit and push marketplace.json after publishing: Phantom reads it from the default branch.
A plugin shipping copyleft libraries (those tools/attach-sources.sh knows) also gets their sources attached, as NOTICE
promises, and publishing fails unless the release then holds the zip and every one of those files.
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
    subprocess.run([sys.executable, str(ROOT / "tools" / "check-plugin-notices.py"), str(zip_path)], cwd=ROOT, check=True)
    print(f"{entry['id']} {version}: {zip_path.relative_to(ROOT)}")

    if publish:
        # The source files NOTICE promises for this release; attach-sources.sh fails for a plugin without copyleft libraries
        attach = ["bash", str(ROOT / "tools" / "attach-sources.sh"), tag]
        listed = subprocess.run(attach + ["--dry-run"], cwd=ROOT, capture_output=True, text=True)
        sources = {line.strip().rsplit("/", 1)[1] for line in listed.stdout.splitlines() if line.startswith("  ")} \
            if listed.returncode == 0 else set()
        notes = f"{entry['name']} {version}"
        # create, not upload: an existing release (another build of the same version) is never replaced
        subprocess.run(["gh", "release", "create", tag, str(zip_path), "--title", notes, "--notes", notes], cwd=ROOT, check=True)
        if sources:
            subprocess.run(attach, cwd=ROOT, check=True)
        view = subprocess.run(["gh", "release", "view", tag, "--json", "assets", "-q", ".assets[].name"],
                              cwd=ROOT, capture_output=True, text=True, check=True)
        missing = ({zip_path.name} | sources) - set(view.stdout.split())
        if missing:
            sys.exit(f"release {tag} is missing {', '.join(sorted(missing))}")
        print("Released. Commit and push .phantom/marketplace.json so Phantom sees the new version.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], "--publish" in sys.argv[2:])
