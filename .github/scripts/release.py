"""Build checks and release publishing for Tonim's PlexonKeys project. Standard library only."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def versions():
    entries = json.loads(Path("releases/versions.json").read_text())
    require(isinstance(entries, list) and entries, "The release manifest must contain versions")
    seen = set()
    for entry in entries:
        version = entry["version"]
        require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "Use a semantic version")
        require(version not in seen, "Duplicate release version")
        seen.add(version)
        require(re.fullmatch(r"[0-9a-f]{40}", entry["commit"]), "Pin each release to a full commit SHA")
        require(type(entry["tests"]) is int and entry["tests"] > 0, "Expected tests must be positive")
        require(type(entry["latest"]) is bool, "latest must be a boolean")
        require(Path(f"releases/{version}.md").is_file(), "Release notes are missing")
    require(sum(entry["latest"] for entry in entries) == 1, "Choose exactly one latest release")
    return entries


def selected():
    return next(entry for entry in versions() if entry["version"] == os.environ["RELEASE_VERSION"])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    entry = selected()
    source = Path("source")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    require(commit == entry["commit"], "Checked-out source does not match the release manifest")
    pom = ET.parse(source / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    require(pom.findtext("m:version", namespaces=ns) == entry["version"], "POM version mismatch")
    require(pom.findtext("m:developers/m:developer/m:id", namespaces=ns) == "ZpkDxGames", "Creator metadata is missing")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in (source / "target/surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    require(totals["tests"] == entry["tests"], f"Unexpected test count: {totals}")
    require(not any(totals[key] for key in ("failures", "errors", "skipped")), f"Tests did not all pass: {totals}")
    jar = source / "target" / f"PlexonKeys-{entry['version']}.jar"
    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip() is None, "Corrupt JAR")
        metadata = archive.read("plugin.yml").decode()
        require(f"version: '{entry['version']}'" in metadata, "Packaged version mismatch")
        require("author: Tonim (ZpkDxGames)" in metadata, "Packaged creator credit is missing")
        require("api-version: '26.2'" in metadata, "Unexpected Paper target")
        require("Created by Tonim (ZpkDxGames)" in archive.read("config.yml").decode(), "Config creator credit is missing")
        require("org/sqlite/JDBC.class" in archive.namelist(), "SQLite is not bundled")
        require(not any(name.startswith(("org/junit/", "org/mockito/", "org/mockbukkit/", "org/bukkit/"))
                        for name in archive.namelist()), "Test or server classes must not be bundled")
    (jar.parent / "SHA256SUMS.txt").write_text(f"{digest(jar)}  {jar.name}\n", encoding="ascii")
    print(f"Verified {jar.name}: {totals['tests']} passing tests; SHA-256 {digest(jar)}")


def gh(*args, check=True):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or "GitHub CLI failed")
    return result


def release_info(tag):
    # Listing with repository write access includes drafts, even before a tag exists.
    page = 1
    while True:
        result = gh("api", f"repos/{os.environ['GH_REPO']}/releases?per_page=100&page={page}")
        releases = json.loads(result.stdout)
        for release in releases:
            if release["tag_name"] == tag:
                return release
        if len(releases) < 100:
            return None
        page += 1


def verify_assets(release, files):
    require(release is not None, "The expected release could not be read")
    assets = {asset["name"]: asset for asset in release["assets"]}
    for file in files:
        asset = assets.get(file.name)
        require(asset is not None and asset["state"] == "uploaded", f"Missing uploaded asset: {file.name}")
        require(asset["size"] == file.stat().st_size, f"Wrong asset size: {file.name}")
        require(asset.get("digest") == "sha256:" + digest(file), f"Wrong asset checksum: {file.name}")


def publish():
    entry = selected()
    repo = os.environ["GH_REPO"]
    require(repo == "ZpkDxGames/PlexonKeys", "Publishing is restricted to the PlexonKeys repository")
    tag = "v" + entry["version"]
    jar = Path("dist") / f"PlexonKeys-{entry['version']}.jar"
    sums = Path("dist/SHA256SUMS.txt")
    require(sums.read_text() == f"{digest(jar)}  {jar.name}\n", "Downloaded artifact failed its checksum")
    notes = Path(f"releases/{entry['version']}.md")
    release = release_info(tag)
    if release is not None:
        require(release["target_commitish"] == entry["commit"], "Existing release targets different source; refusing to change it")
        if not release["draft"]:
            verify_assets(release, (jar, sums))
            print(f"Preserved existing published release: {release['html_url']}")
            return
        require(release["body"].strip() == notes.read_text().strip(), "Existing draft has different notes; refusing to overwrite it")
    else:
        gh("release", "create", tag, "--repo", repo, "--target", entry["commit"], "--draft",
           "--title", "PlexonKeys " + entry["version"], "--notes-file", str(notes))
    # Only drafts can reach this step. Never overwrite assets of a published release.
    gh("release", "upload", tag, str(jar), str(sums), "--repo", repo, "--clobber")
    verify_assets(release_info(tag), (jar, sums))
    gh("release", "edit", tag, "--repo", repo, "--draft=false", "--latest=" + str(entry["latest"]).lower())
    release = release_info(tag)
    require(release is not None and not release["draft"], "Release was not published")
    verify_assets(release, (jar, sums))
    print(f"Published {release['html_url']}")


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "plan":
        matrix = json.dumps({"include": versions()}, separators=(",", ":"))
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("matrix=" + matrix + "\n")
    elif command == "verify":
        verify()
    elif command == "publish":
        publish()
    else:
        raise ValueError("Expected plan, verify, or publish")
