#!/usr/bin/env python3
"""Read-only GitHub -> verified input JARs + provenance for offline signing."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.error
import urllib.request
from urllib.parse import quote, urlparse

REPOSITORIES = {"IzakyL/folkways", "davisye/folkways"}
MODULES = {"core", "vanilla", "create", "modulargolems", "cookery"}
PROXY = "https://github.davisye.cn"
MAX_METADATA = 1024 * 1024
MAX_JAR = 100 * 1024 * 1024
PATTERN = re.compile(r"folkways-(core|vanilla|create|modulargolems|cookery)-neoforge-1\.21\.1-.+\.jar")


def routes(url, mirror_first=False):
    p = urlparse(url)
    if p.scheme != "https" or p.hostname not in {"github.com", "api.github.com"} or p.username or p.password:
        raise ValueError("Only public official GitHub endpoints are accepted")
    mirror = PROXY + "/_github/" + p.hostname + p.path + ("?" + p.query if p.query else "")
    return [mirror, url] if mirror_first else [url, mirror]


def read(url, maximum, mirror_first=False, expected_sha=None):
    errors = []
    for route in routes(url, mirror_first):
        try:
            req = urllib.request.Request(route, headers={"User-Agent": "update-folkways-release-fetch/0.1", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(req, timeout=20) as response:
                final = urlparse(response.url)
                host = final.hostname or ""
                if final.scheme != "https" or not (host in {"github.com", "api.github.com", "github.davisye.cn"} or host.endswith(".githubusercontent.com")):
                    raise ValueError("Redirect left approved HTTPS origins")
                data = response.read(maximum + 1)
                if len(data) > maximum:
                    raise ValueError("Response exceeds size limit")
                if expected_sha is not None and (len(data) != maximum or hashlib.sha256(data).hexdigest() != expected_sha):
                    raise ValueError("Response digest or size mismatch")
                if expected_sha is None:
                    json.loads(data)
                return data
        except (OSError, ValueError) as error:
            # Do not print redirect URLs with signed queries or request metadata.
            errors.append(type(error).__name__)
    raise OSError("All GitHub routes failed: " + ", ".join(errors))


def metadata(url, mirror_first):
    return json.loads(read(url, MAX_METADATA, mirror_first))


def snapshot(release):
    return (release["id"], release["tag_name"], release["target_commitish"], release["updated_at"],
            tuple(sorted((a["id"], a["name"], a["size"], a.get("digest"), a["updated_at"])
                         for a in release["assets"])))


def commit_for_release(repository, release, mirror_first):
    target = release["target_commitish"]
    ref = metadata("https://api.github.com/repos/" + repository + "/git/ref/tags/" + quote(release["tag_name"], safe=""), mirror_first)["object"]
    for _ in range(5):
        if ref["type"] == "commit" and re.fullmatch("[a-f0-9]{40}", ref["sha"]):
            if re.fullmatch("[a-f0-9]{40}", target) and target != ref["sha"]:
                raise ValueError("Release target and tag commit differ")
            return ref["sha"]
        if ref["type"] != "tag" or not re.fullmatch("[a-f0-9]{40}", ref["sha"]):
            break
        ref = metadata("https://api.github.com/repos/" + repository + "/git/tags/" + ref["sha"], mirror_first)["object"]
    raise ValueError("Release tag does not resolve to one full commit SHA")


def fetch(repository, tag, output, mirror_first=False):
    if repository not in REPOSITORIES:
        raise ValueError("Unsupported source repository")
    if not tag or len(tag) > 160 or any(c in tag for c in "\r\n"):
        raise ValueError("Invalid release tag")
    endpoint = "https://api.github.com/repos/" + repository + "/releases/tags/" + quote(tag, safe="")
    before = metadata(endpoint, mirror_first)
    if before["draft"] or before["tag_name"] != tag:
        raise ValueError("Not a published matching release")
    commit = commit_for_release(repository, before, mirror_first)
    assets = []
    seen = set()
    for asset in before["assets"]:
        if asset["name"].endswith(("-sources.jar", "-api.jar")):
            continue
        match = PATTERN.fullmatch(asset["name"])
        if not match:
            continue
        module = match.group(1)
        if module in seen:
            raise ValueError("Duplicate component in GitHub release")
        seen.add(module)
        if asset["state"] != "uploaded" or type(asset["size"]) is not int or not 0 < asset["size"] <= MAX_JAR:
            raise ValueError("Incomplete or oversized GitHub asset")
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", asset.get("digest") or ""):
            raise ValueError("GitHub asset lacks SHA256 digest; refusing to create trusted input")
        expected_url = "https://github.com/" + repository + "/releases/download/" + quote(tag, safe="") + "/" + quote(asset["name"], safe="")
        if asset["browser_download_url"] != expected_url:
            raise ValueError("Asset download URL is outside requested repository/tag")
        assets.append(asset)
    if seen != MODULES:
        raise ValueError("Release must contain all five Folkways 1.21.1 modules")
    output = Path(output)
    if output.exists():
        raise FileExistsError("Output must not exist")
    output.mkdir(parents=True)
    try:
        jar_dir = output / "jars"
        jar_dir.mkdir()
        files = {}
        for asset in sorted(assets, key=lambda a: a["name"]):
            data = read(asset["browser_download_url"], asset["size"], mirror_first, asset["digest"][7:])
            digest = hashlib.sha256(data).hexdigest()
            if len(data) != asset["size"] or digest != asset["digest"][7:]:
                raise ValueError("Downloaded asset digest or size mismatch")
            (jar_dir / asset["name"]).write_bytes(data)
            files[asset["name"]] = {"sha256": digest, "size": len(data)}
        after = metadata(endpoint, mirror_first)
        after_commit = commit_for_release(repository, after, mirror_first)
        if snapshot(before) != snapshot(after) or commit != after_commit:
            raise ValueError("Release changed during download; discard and retry whole build")
        provenance = {"repository": repository, "buildId": commit, "minecraft": "1.21.1", "files": files}
        (output / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
        (output / "release-snapshot.json").write_text(json.dumps({"releaseId": before["id"], "tag": tag,
            "updatedAt": before["updated_at"], "buildId": commit,
            "assets": [{k: a[k] for k in ("id", "name", "digest", "size", "updated_at")} for a in assets]}, indent=2), encoding="utf-8")
        return provenance
    except BaseException:
        # Output was created exclusively by this invocation.
        shutil.rmtree(output)
        raise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repository", choices=sorted(REPOSITORIES), required=True)
    p.add_argument("--tag", default="nightly")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--mirror-first", action="store_true")
    args = p.parse_args()
    print(json.dumps(fetch(args.repository, args.tag, args.output, args.mirror_first), indent=2))


if __name__ == "__main__":
    main()
