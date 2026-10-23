#!/usr/bin/env python3
"""Build and verify updater release assets; publish only trusted GitHub push runs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote
import zipfile

REPOSITORY = "davisye/update-folkways"
WORKFLOW = ".github/workflows/ci.yml"
METADATA = "update-metadata.json"
BUILD_INFO = "META-INF/folkways-updater-build.json"
MAX_JAR = 64 * 1024 * 1024
SEEDS = ("launch.ps1", "launch.sh")


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def release_context(ref, ref_type, run_id, run_number, attempt):
    if not all(re.fullmatch(r"[1-9][0-9]*", str(v)) for v in (run_id, run_number, attempt)):
        raise ValueError("Positive GitHub run identifiers required")
    if int(attempt) >= 1000:
        raise ValueError("run_attempt exceeds sequence encoding")
    sequence = int(run_number) * 1000 + int(attempt)
    if sequence > 2**63 - 1:
        raise ValueError("Sequence overflows signed long")
    if ref_type == "tag":
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", ref):
            raise ValueError("Release tags must use vMAJOR.MINOR.PATCH[-prerelease]")
        version, channel, tag = ref[1:], "release", ref
    else:
        version = f"0.1.0-{'nightly' if ref == 'main' else 'ci'}.{run_number}.{attempt}"
        channel, tag = "nightly", "nightly"
    return {"version": version, "channel": channel, "tag": tag, "sequence": sequence, "runNumber": int(run_number)}


def validate_metadata(m):
    if m["schema"] != 1 or m["repository"] != REPOSITORY or m["minecraft"] != "1.21.1":
        raise ValueError("Unknown self-update schema/repository/game")
    if m.get("workflow") != WORKFLOW:
        raise ValueError("Unexpected self-update workflow")
    if m["channel"] not in {"nightly", "release"} or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", m["version"]):
        raise ValueError("Invalid release version/channel")
    if not re.fullmatch("[a-f0-9]{40}", m["commit"]):
        raise ValueError("Expected complete commit SHA")
    if not isinstance(m["runId"], str) or not re.fullmatch("[1-9][0-9]*", m["runId"]):
        raise ValueError("Invalid runId")
    if type(m["runAttempt"]) is not int or not 1 <= m["runAttempt"] < 1000:
        raise ValueError("Invalid runAttempt")
    if type(m["runNumber"]) is not int or m["runNumber"] <= 0:
        raise ValueError("Invalid runNumber")
    if type(m["sequence"]) is not int or m["sequence"] != m["runNumber"] * 1000 + m["runAttempt"] or m["sequence"] > 2**63 - 1:
        raise ValueError("Invalid monotonic sequence")
    tag = "nightly" if m["channel"] == "nightly" else "v" + m["version"]
    expected = {"bootstrap": f"folkways-bootstrap-{m['version']}-standalone.jar",
                "companion": f"folkways-updater-neoforge-1.21.1-{m['version']}.jar"}
    if len(m["artifacts"]) != 2 or {a["component"] for a in m["artifacts"]} != set(expected):
        raise ValueError("Expected exactly bootstrap and companion")
    for a in m["artifacts"]:
        if a["fileName"] != expected[a["component"]] or not re.fullmatch("[a-f0-9]{64}", a["sha256"]):
            raise ValueError("Unexpected artifact name/digest")
        if type(a["size"]) is not int or not 0 < a["size"] <= MAX_JAR:
            raise ValueError("Invalid artifact size")
        url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{quote(a['fileName'], safe='')}"
        if a["url"] != url:
            raise ValueError("Artifact URL left the canonical release")


def verify_jar(path, component, m):
    if path.is_symlink() or not 0 < path.stat().st_size <= MAX_JAR:
        raise ValueError("Invalid JAR file")
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if len(names) != len(set(names)) or names.count(BUILD_INFO) != 1:
            raise ValueError("Duplicate entries or missing build provenance")
        info = json.loads(z.read(BUILD_INFO))
        required = {"schema": 1, "repository": REPOSITORY, "component": component,
                    "version": m["version"], "commit": m["commit"], "runId": m["runId"],
                    "runNumber": m["runNumber"],
                    "runAttempt": m["runAttempt"], "sequence": m["sequence"], "channel": m["channel"],
                    "minecraft": "1.21.1"}
        if any(info.get(k) != v for k, v in required.items()):
            raise ValueError("JAR build provenance differs from release")
        if component == "bootstrap":
            if b"Main-Class: dev.davisye.folkways.update.core.Bootstrap" not in z.read("META-INF/MANIFEST.MF"):
                raise ValueError("Missing executable bootstrap entry point")
        else:
            import tomllib
            mod = tomllib.loads(z.read("META-INF/neoforge.mods.toml").decode("utf-8"))["mods"]
            if len(mod) != 1 or mod[0]["modId"] != "folkways_updater" or mod[0]["version"] != m["version"]:
                raise ValueError("Companion mod ID/version mismatch")
        classes = [n for n in names if n.startswith("dev/davisye/") and n.endswith(".class")]
        if not classes or any(z.read(n)[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(z.read(n)[6:8], "big") != 65 for n in classes):
            raise ValueError("Own classes must target Java 21")


def prepare(root, output, context, commit, run_id, attempt):
    if not re.fullmatch("[a-f0-9]{40}", commit):
        raise ValueError("Expected complete source commit")
    m = {"schema": 1, "repository": REPOSITORY, "minecraft": "1.21.1", "workflow": WORKFLOW,
         "version": context["version"], "channel": context["channel"], "commit": commit,
         "sequence": context["sequence"], "runId": str(run_id), "runAttempt": int(attempt), "artifacts": []}
    m["runNumber"] = context["runNumber"]
    version, tag = m["version"], context["tag"]
    sources = {"bootstrap": root / "updater-core/build/libs" / f"folkways-bootstrap-{version}-standalone.jar",
               "companion": root / "companion/build/libs" / f"folkways-updater-neoforge-1.21.1-{version}.jar"}
    for component, source in sources.items():
        verify_jar(source, component, m)
        m["artifacts"].append({"component": component, "fileName": source.name, "size": source.stat().st_size,
                               "sha256": digest(source), "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{quote(source.name, safe='')}"})
    validate_metadata(m)
    if output.exists():
        raise FileExistsError("Release output already exists")
    output.mkdir(parents=True)
    for source in sources.values():
        shutil.copy2(source, output / source.name)
    for name in SEEDS:
        source = root / "updater-core" / name
        if source.is_symlink() or not 0 < source.stat().st_size <= 128 * 1024:
            raise ValueError("Invalid seed launcher")
        shutil.copy2(source, output / name)
    (output / METADATA).write_bytes(canonical(m))
    checksums = "".join(f"{digest(p)}  {p.name}\n" for p in sorted(output.iterdir()))
    (output / "checksums.sha256").write_text(checksums, encoding="ascii", newline="\n")
    verify_directory(output)
    return m


def verify_directory(directory):
    m = json.loads((directory / METADATA).read_bytes())
    validate_metadata(m)
    expected = {a["fileName"] for a in m["artifacts"]} | set(SEEDS) | {METADATA, "checksums.sha256"}
    if {p.name for p in directory.iterdir()} != expected or any(not p.is_file() or p.is_symlink() for p in directory.iterdir()):
        raise ValueError("Unexpected release file set")
    for a in m["artifacts"]:
        p = directory / a["fileName"]
        if p.stat().st_size != a["size"] or digest(p) != a["sha256"]:
            raise ValueError("Release JAR hash/size mismatch")
        verify_jar(p, a["component"], m)
    lines = (directory / "checksums.sha256").read_text(encoding="ascii").splitlines()
    wanted = {f"{digest(directory / name)}  {name}" for name in expected - {"checksums.sha256"}}
    if len(lines) != len(wanted) or set(lines) != wanted:
        raise ValueError("Release checksum set differs")
    return m


class GitHub:
    def __init__(self, token):
        self.token = token

    def api(self, path, method="GET", data=None, binary=None):
        url = ("https://uploads.github.com" if binary is not None else "https://api.github.com") + f"/repos/{REPOSITORY}/" + path
        headers = {"Authorization": "Bearer " + self.token, "User-Agent": "update-folkways-ci", "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28"}
        body = canonical(data) if data is not None else binary
        if body is not None:
            headers["Content-Type"] = "application/octet-stream" if binary is not None else "application/json"
        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            if method == "GET" and error.code == 404:
                return None
            raise RuntimeError(f"GitHub {method} {path.split('?')[0]} returned HTTP {error.code}") from None


def read_public_asset(asset):
    request = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": "update-folkways-ci"})
    with urllib.request.urlopen(request, timeout=30) as r:
        data = r.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024 or asset.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest() or len(data) != asset["size"]:
        raise ValueError("Existing metadata asset digest mismatch")
    return json.loads(data)


def find_release(api, tag, commit):
    release = api.api("releases/tags/" + quote(tag, safe=""))
    if release:
        return release
    # The by-tag REST endpoint omits drafts. The authorized collection includes
    # them, so inspect every page before reusing an unambiguous failed draft.
    matches = []
    for page in range(1, 101):
        releases = api.api(f"releases?per_page=100&page={page}")
        if not isinstance(releases, list):
            raise ValueError("Cannot enumerate authorized releases for draft recovery")
        matches.extend(r for r in releases if r.get("tag_name") == tag)
        if len(matches) > 1:
            raise ValueError("Ambiguous releases for publication tag")
        if len(releases) < 100:
            break
    else:
        raise ValueError("Release enumeration exceeded the safe pagination limit")
    if not matches:
        return None
    release = matches[0]
    if not release.get("draft") or release.get("target_commitish") != commit:
        raise ValueError("Draft recovery requires the same tag and exact build commit")
    return release


def publish(directory, environment=os.environ, api=None):
    m = verify_directory(directory)
    env = environment
    tag = "nightly" if m["channel"] == "nightly" else "v" + m["version"]
    expected_ref = "refs/heads/main" if tag == "nightly" else "refs/tags/" + tag
    if env.get("GITHUB_EVENT_NAME") != "push" or env.get("GITHUB_REPOSITORY") != REPOSITORY or env.get("GITHUB_REF") != expected_ref:
        raise ValueError("Only trusted main/tag push runs may publish")
    if (env.get("GITHUB_SHA") != m["commit"] or env.get("GITHUB_RUN_ID") != m["runId"]
            or int(env.get("GITHUB_RUN_ATTEMPT", "0")) != m["runAttempt"]
            or int(env.get("GITHUB_RUN_NUMBER", "0")) != m["runNumber"]):
        raise ValueError("Release metadata does not describe this run")
    api = api or GitHub(env["GH_TOKEN"])
    run = api.api("actions/runs/" + m["runId"])
    if (run["event"] != "push" or run["head_sha"] != m["commit"] or run["path"].split("@")[0] != WORKFLOW
            or run["run_number"] != m["runNumber"]
            or run["repository"]["full_name"] != REPOSITORY or run["head_repository"]["full_name"] != REPOSITORY):
        raise ValueError("Unexpected publication workflow provenance")
    branch = api.api("branches/main")
    if tag == "nightly" and branch["commit"]["sha"] != m["commit"]:
        return {"published": False, "reason": "newer main commit exists"}
    if tag != "nightly":
        comparison = api.api("compare/" + m["commit"] + "...main")
        if comparison["status"] not in {"ahead", "identical"}:
            raise ValueError("Stable tag commit must be in trusted main history")
    release = find_release(api, tag, m["commit"])
    if release and tag != "nightly" and (not release["draft"] or release["target_commitish"] != m["commit"]):
        raise FileExistsError("Published stable releases are immutable")
    if release and tag == "nightly" and not release.get("draft", False):
        previous = next((a for a in release["assets"] if a["name"] == METADATA), None)
        if previous:
            old = read_public_asset(previous)
            validate_metadata(old)
            if old["sequence"] >= m["sequence"]:
                return {"published": False, "reason": "nightly sequence already newer or equal"}
    if tag == "nightly":
        ref = api.api("git/ref/tags/nightly")
        if ref:
            api.api("git/refs/tags/nightly", "PATCH", {"sha": m["commit"], "force": True})
        else:
            api.api("git/refs", "POST", {"ref": "refs/tags/nightly", "sha": m["commit"]})
    notes = (f"Updater {m['version']} from `{m['commit']}`.\n\n"
             f"Quality gate: https://github.com/{REPOSITORY}/actions/runs/{m['runId']}\n\n"
             "Download both JARs and your platform's seed launcher. See README for first installation. "
             "checksums.sha256 and update-metadata.json bind all updater artifacts to this build. "
             "No Folkways mod bundle or private signing key is included.\n")
    settings = {"tag_name": tag, "target_commitish": m["commit"], "name": "Nightly" if tag == "nightly" else tag,
                "body": notes, "prerelease": tag == "nightly" or "-" in m["version"]}
    if not release:
        release = api.api("releases", "POST", {**settings, "draft": True})
    release_id = release["id"]
    existing = {a["name"]: a for a in api.api(f"releases/{release_id}/assets?per_page=100")}
    files = sorted(directory.iterdir(), key=lambda p: p.name == METADATA)
    for p in files:
        if p.name in existing:
            api.api(f"releases/assets/{existing[p.name]['id']}", "DELETE")
        api.api(f"releases/{release_id}/assets?name={quote(p.name, safe='')}", "POST", binary=p.read_bytes())
    wanted = {p.name: p for p in files}
    for _ in range(5):
        assets = api.api(f"releases/{release_id}/assets?per_page=100")
        by_name = {a["name"]: a for a in assets}
        if all(name in by_name and by_name[name].get("digest") == "sha256:" + digest(p)
               and by_name[name]["size"] == p.stat().st_size for name, p in wanted.items()):
            break
        time.sleep(2)
    else:
        raise ValueError("Uploaded GitHub asset digest verification failed")
    for asset in assets:
        if asset["name"] not in wanted:
            api.api(f"releases/assets/{asset['id']}", "DELETE")
    api.api(f"releases/{release_id}", "PATCH", {**settings, "draft": False})
    return {"published": True, "tag": tag, "version": m["version"], "sequence": m["sequence"], "assets": sorted(wanted)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("context", "prepare"):
        child = sub.add_parser(name)
        for arg, env in (("ref", "GITHUB_REF_NAME"), ("ref-type", "GITHUB_REF_TYPE"), ("run-id", "GITHUB_RUN_ID"),
                         ("run-number", "GITHUB_RUN_NUMBER"), ("run-attempt", "GITHUB_RUN_ATTEMPT")):
            child.add_argument("--" + arg, default=os.environ.get(env), required=env not in os.environ)
        if name == "context":
            child.add_argument("--github-output", type=Path)
        else:
            child.add_argument("--root", type=Path, default=Path.cwd())
            child.add_argument("--output", type=Path, default=Path("dist"))
            child.add_argument("--commit", default=os.environ.get("GITHUB_SHA"), required="GITHUB_SHA" not in os.environ)
    for name in ("verify", "publish"):
        sub.add_parser(name).add_argument("--directory", type=Path, default=Path("dist"))
    args = parser.parse_args()
    if args.command in {"context", "prepare"}:
        context = release_context(args.ref, args.ref_type, args.run_id, args.run_number, args.run_attempt)
        if args.command == "context":
            result = context
            if args.github_output:
                with args.github_output.open("a", encoding="utf-8") as f:
                    f.writelines(f"{key}={value}\n" for key, value in context.items())
        else:
            result = prepare(args.root, args.output, context, args.commit, args.run_id, args.run_attempt)
    elif args.command == "verify":
        result = verify_directory(args.directory)
    else:
        result = publish(args.directory)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
