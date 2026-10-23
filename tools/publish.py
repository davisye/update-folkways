#!/usr/bin/env python3
"""Sign per-JAR releases by default; optional ZIP packaging. No production HTTP writes."""
from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
from urllib.parse import quote, urlparse
import zipfile

REPOSITORIES = {"IzakyL/folkways", "davisye/folkways"}
CHANNELS = {"release", "rc", "beta", "alpha", "nightly"}
MODULES = {"core", "vanilla", "create", "modulargolems", "cookery"}
MAX_FILE = 100 * 1024 * 1024
MAX_BUNDLE = 128 * 1024 * 1024
MAX_MANIFEST = 1024 * 1024
HELPER = Path(__file__).with_name("ManifestCrypto.java")


def scratch():
    root = Path.cwd() / "work" / "publisher-tmp"
    root.mkdir(parents=True, exist_ok=True)
    return root


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_name(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,159}", value) or value in {".", ".."}:
        raise ValueError("Unsafe name")
    return value


def reparse(path):
    path = Path(path)
    try:
        stat = path.lstat()
    except FileNotFoundError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def https_url(value):
    p = urlparse(value)
    if p.scheme != "https" or not p.hostname or p.username or p.password or p.fragment:
        raise ValueError("Expected credential-free HTTPS URL")
    return value


def crypto(*args):
    # subprocess argv avoids shells and never logs private key contents.
    subprocess.run(["java", "--source", "21", str(HELPER), *map(str, args)], check=True,
                   stdout=subprocess.DEVNULL)


def signature_payload(envelope):
    if set(envelope) != {"keyId", "payload", "signature"}:
        raise ValueError("Unknown envelope fields")
    safe_name(envelope["keyId"])
    payload = base64.b64decode(envelope["payload"], validate=True)
    if len(payload) > MAX_MANIFEST:
        raise ValueError("Manifest too large")
    return payload


def verify_signature(envelope, public_key):
    payload = signature_payload(envelope)
    with tempfile.TemporaryDirectory(prefix="folkways-sign-", dir=scratch()) as t:
        t = Path(t)
        (t / "payload").write_bytes(payload)
        (t / "signature").write_text(envelope["signature"], encoding="ascii")
        crypto("verify", public_key, t / "payload", t / "signature")
    return json.loads(payload)


def validate_manifest(m):
    if m["schema"] != 1 or m["repository"] not in REPOSITORIES:
        raise ValueError("Unknown schema or repository")
    if m["minecraft"] != "1.21.1" or m["loader"] != "neoforge" or m["channel"] not in CHANNELS:
        raise ValueError("Incompatible game, loader or channel")
    if not re.fullmatch("[a-f0-9]{40}", m["buildId"]):
        raise ValueError("buildId must be full lowercase commit SHA")
    if type(m["sequence"]) is not int or not 1 <= m["sequence"] <= 2**63 - 1:
        raise ValueError("sequence must be positive signed long")
    safe_name(m["version"])
    b = m.get("bundle")
    if "bundle" in m and b is None:
        raise ValueError("bundle must be absent or an object")
    if b is not None:
        https_url(b["url"])
        for u in b.get("mirrors", []):
            https_url(u)
        if not re.fullmatch("[a-f0-9]{64}", b["sha256"]) or not 0 < b["size"] <= MAX_BUNDLE:
            raise ValueError("Invalid bundle digest or size")
    seen, filenames = set(), set()
    for a in m["components"]:
        if a["module"] not in MODULES or a["module"] in seen:
            raise ValueError("Unknown or duplicate module")
        safe_name(a["file"])
        if not a["file"].endswith(".jar") or a["file"].casefold() in filenames:
            raise ValueError("Duplicate or invalid JAR name")
        if not 0 < a["size"] <= MAX_FILE or not re.fullmatch("[a-f0-9]{64}", a["sha256"]):
            raise ValueError("Invalid component digest or size")
        if b is None or "url" in a:
            https_url(a["url"])
        for u in a.get("mirrors", []):
            https_url(u)
        seen.add(a["module"])
        filenames.add(a["file"].casefold())
    if seen != MODULES:
        raise ValueError("A published manifest must contain all five modules from the same build")


def verify_package(package, public_key):
    package = Path(package)
    envelope_bytes = (package / "manifest.json").read_bytes()
    if len(envelope_bytes) > MAX_MANIFEST:
        raise ValueError("Envelope too large")
    m = verify_signature(json.loads(envelope_bytes), public_key)
    validate_manifest(m)
    if "bundle" not in m:
        # Binary bytes were checked before signing; this command validates the
        # metadata only and never claims to re-download remote assets.
        return m
    bundle = package / "bundle.zip"
    if bundle.stat().st_size != m["bundle"]["size"] or sha(bundle) != m["bundle"]["sha256"]:
        raise ValueError("Bundle digest mismatch")
    with zipfile.ZipFile(bundle) as z:
        infos = z.infolist()
        expected = {a["file"]: a for a in m["components"]}
        if len(infos) != len(expected) or set(z.namelist()) != set(expected):
            raise ValueError("Bundle does not exactly match manifest")
        for info in infos:
            a = expected[info.filename]
            if info.is_dir() or info.file_size != a["size"] or info.file_size > MAX_FILE:
                raise ValueError("ZIP size mismatch")
            data = z.read(info)
            if hashlib.sha256(data).hexdigest() != a["sha256"]:
                raise ValueError("Component digest mismatch")
            jar_metadata(data, a["module"])
    return m


def jar_metadata(data, module):
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        info = z.getinfo("META-INF/neoforge.mods.toml")
        if info.file_size > MAX_MANIFEST:
            raise ValueError("Oversized mod metadata")
        mod = tomllib.loads(z.read(info).decode("utf-8"))
        ids = {x["modId"] for x in mod["mods"]}
        if ids != {"folkways_" + module}:
            raise ValueError("JAR module id mismatch")
        return mod["mods"][0]["version"]


def validated_inputs(args):
    if args.repository not in REPOSITORIES or args.channel not in CHANNELS:
        raise ValueError("Unsupported source or channel")
    provenance = json.loads(args.provenance.read_text(encoding="utf-8"))
    if (provenance["repository"], provenance["buildId"], provenance["minecraft"]) != (
        args.repository, args.build_id, "1.21.1"
    ):
        raise ValueError("Provenance source/build mismatch")
    jars = sorted(p for p in args.jar_dir.glob("*.jar") if not p.name.endswith("-sources.jar"))
    if {p.name for p in jars} != set(provenance["files"]):
        raise ValueError("Provenance file set differs from build output")
    artifacts, versions = [], set()
    for p in jars:
        if reparse(p) or p.stat().st_size > MAX_FILE:
            raise ValueError("Invalid JAR path or size")
        match = re.fullmatch(r"folkways-(core|vanilla|create|modulargolems|cookery)-neoforge-1\.21\.1-.+\.jar", p.name)
        if not match:
            raise ValueError("Unexpected build JAR name")
        module = match.group(1)
        a = {"module": module, "file": p.name, "sha256": sha(p), "size": p.stat().st_size}
        if {k: a[k] for k in ("sha256", "size")} != provenance["files"][p.name]:
            raise ValueError("JAR does not match provenance")
        versions.add(jar_metadata(p.read_bytes(), module))
        artifacts.append(a)
    if len(versions) != 1:
        raise ValueError("Mixed component versions")
    if sum(a["size"] for a in artifacts) >= MAX_BUNDLE:
        raise ValueError("Build exceeds update size limit")
    return jars, artifacts


def manifest_metadata(args, components):
    return {"schema": 1, "repository": args.repository, "minecraft": "1.21.1", "loader": "neoforge",
            "channel": args.channel, "buildId": args.build_id, "sequence": args.sequence,
            "version": args.version,
            "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
            "components": components}


def write_signed_manifest(m, args):
    validate_manifest(m)
    with tempfile.TemporaryDirectory(prefix="folkways-sign-", dir=scratch()) as t:
        t = Path(t)
        data = canonical(m)
        (t / "payload").write_bytes(data)
        crypto("sign", args.private_key, t / "payload", t / "signature")
        envelope = {"keyId": safe_name(args.key_id), "payload": base64.b64encode(data).decode("ascii"),
                    "signature": (t / "signature").read_text(encoding="ascii")}
    (args.output / "manifest.json").write_bytes(canonical(envelope))


def sign_release(args):
    _, components = validated_inputs(args)
    snapshot_path = args.release_snapshot or args.provenance.with_name("release-snapshot.json")
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    if (snapshot["buildId"] != args.build_id or snapshot["tag"] != args.tag
            or type(snapshot["releaseId"]) is not int or snapshot["releaseId"] <= 0):
        raise ValueError("Release snapshot source/build/tag mismatch")
    if args.channel == "nightly" and args.tag != "nightly":
        raise ValueError("Nightly manifests must use the nightly release tag")
    if args.channel != "nightly" and args.tag == "nightly":
        raise ValueError("Archived channels require a non-nightly release tag")
    if not args.tag or len(args.tag) > 160 or any(c in args.tag for c in "\r\n"):
        raise ValueError("Invalid release tag")
    assets = snapshot["assets"]
    expected = {a["file"]: a for a in components}
    if len(assets) != len(expected) or {a["name"] for a in assets} != set(expected):
        raise ValueError("Release snapshot component set differs")
    for asset in assets:
        a = expected[asset["name"]]
        if (asset["digest"] != "sha256:" + a["sha256"] or asset["size"] != a["size"]
                or type(asset["id"]) is not int or asset["id"] <= 0):
            raise ValueError("Release snapshot digest/size differs from verified input")
        a["url"] = ("https://github.com/" + args.repository + "/releases/download/"
                    + quote(args.tag, safe="") + "/" + quote(a["file"], safe=""))
    if args.output.exists():
        raise FileExistsError("Output already exists; never overwrite a signed release")
    args.output.mkdir(parents=True)
    try:
        m = manifest_metadata(args, components)
        write_signed_manifest(m, args)
        verify_package(args.output, args.public_key)
        return m
    except BaseException:
        shutil.rmtree(args.output)
        raise


def pack(args):
    jars, artifacts = validated_inputs(args)
    if args.output.exists():
        raise FileExistsError("Output already exists; never overwrite a package")
    args.output.mkdir(parents=True)
    try:
        bundle = args.output / "bundle.zip"
        with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_STORED) as z:
            for p in jars:
                info = zipfile.ZipInfo(p.name, date_time=(1980, 1, 1, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                z.writestr(info, p.read_bytes())
        m = manifest_metadata(args, artifacts)
        m["bundle"] = {"url": args.bundle_url, "sha256": sha(bundle), "size": bundle.stat().st_size,
                       "mirrors": args.bundle_mirror}
        write_signed_manifest(m, args)
        verify_package(args.output, args.public_key)
        return m
    except BaseException:
        # Only the new output created above; never remove an existing package.
        shutil.rmtree(args.output)
        raise


def relative_destination(m):
    root = Path(m["repository"]) / m["minecraft"] / m["channel"]
    return root if m["channel"] == "nightly" else root / safe_name(m["version"])


def plan(package, public_key, mount):
    m = verify_package(package, public_key)
    path = "/" + "/".join([mount.strip("/"), *relative_destination(m).parts])
    operations = [{"method": "POST", "path": "/api/fs/mkdir", "body": {"path": path}}]
    if "bundle" in m:
        operations.extend([
            {"method": "PUT", "path": "/api/fs/put", "local": "bundle.zip",
             "headers": {"File-Path": quote(path + "/bundle.zip", safe="")}},
            {"method": "GET", "path": "/d" + path + "/bundle.zip", "verify": m["bundle"]}])
    else:
        operations.extend({"method": "GET", "url": a["url"],
                           "verify": {"sha256": a["sha256"], "size": a["size"]},
                           "purpose": "read back existing release asset; never upload a JAR"}
                          for a in m["components"])
    operations.extend([
        {"method": "PUT", "path": "/api/fs/put", "local": "manifest.json",
         "headers": {"File-Path": quote(path + "/manifest.json", safe="")}},
        {"method": "GET", "path": "/d" + path + "/manifest.json", "verify": "signature"}])
    if m["channel"] != "nightly":
        latest_path = path.rsplit("/", 1)[0] + "/latest.json"
        operations.append({"method": "PUT", "path": "/api/fs/put", "local": "manifest.json",
                           "headers": {"File-Path": quote(latest_path, safe="")},
                           "condition": "archive verified and sequence greater than signed latest"})
        operations.append({"method": "GET", "path": "/d" + latest_path, "verify": "signature and sequence"})
    return {"mode": "dry-run", "repository": m["repository"], "sequence": m["sequence"],
            "destination": path, "distribution": "bundle" if "bundle" in m else "per-jar", "operations": operations,
            "requiresBeforeExecute": ["isolated service account limited to target subtree",
                "verified storage overwrite/cache/atomicity capabilities", "external single-writer lock",
                "archive absent or byte-identical (never overwrite)", "channel-level sequence high-water check", "read-back verification"],
            "productionWritesImplemented": False}


@contextlib.contextmanager
def file_lock(path):
    if reparse(path) or reparse(path.parent):
        raise ValueError("Lock path crosses symlink")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as f:
        if f.tell() == 0:
            f.write(b"0")
            f.flush()
        f.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            f.seek(0)
            if os.name == "nt":
                msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(f.fileno(), fcntl.LOCK_UN)


def flush_copy(source, dest):
    with Path(source).open("rb") as src, dest.open("xb") as dst:
        shutil.copyfileobj(src, dst)
        dst.flush()
        os.fsync(dst.fileno())


def recover_publication(dest, state, public_key):
    journal = state / "transaction.json"
    if reparse(journal):
        raise ValueError("Recovery journal crosses symlink")
    if not journal.exists():
        return
    tx = json.loads(journal.read_bytes())
    if set(tx) != {"previous", "sequence", "manifestSha256"} or type(tx["previous"]) is not bool:
        raise ValueError("Invalid publication recovery journal")
    committed = False
    try:
        current = verify_package(dest, public_key)
        committed = current["sequence"] == tx["sequence"] and sha(dest / "manifest.json") == tx["manifestSha256"]
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError):
        pass
    if not committed:
        for name in ("bundle.zip", "manifest.json"):
            target = dest / name
            if reparse(target):
                raise ValueError("Recovery file crosses symlink")
            if tx["previous"]:
                backup = state / (name + ".previous")
                if reparse(backup) or not backup.is_file():
                    raise ValueError("Missing publication recovery backup")
                restored = dest / (name + ".restore")
                if restored.exists():
                    if reparse(restored):
                        raise ValueError("Recovery temporary file crosses symlink")
                    restored.unlink()
                flush_copy(backup, restored)
                os.replace(restored, target)
            elif target.exists():
                target.unlink()
    for name in ("bundle.zip.next", "manifest.json.next"):
        p = dest / name
        if p.exists():
            if reparse(p):
                raise ValueError("Recovery staging file crosses symlink")
            p.unlink()
    for name in ("bundle.zip.previous", "manifest.json.previous"):
        (state / name).unlink(missing_ok=True)
    journal.unlink()
    if not committed and not tx["previous"] and dest.exists() and not any(dest.iterdir()):
        dest.rmdir()


def update_latest(m, archive, public_key):
    if m["channel"] == "nightly":
        return False
    latest = archive.parent / "latest.json"
    staged = archive.parent / "latest.json.next"
    if reparse(latest) or reparse(staged):
        raise ValueError("Latest pointer crosses symlink")
    manifest = archive / "manifest.json"
    if latest.exists():
        previous = verify_signature(json.loads(latest.read_bytes()), public_key)
        validate_manifest(previous)
        if any(previous[k] != m[k] for k in ("repository", "minecraft", "loader", "channel")):
            raise ValueError("Latest source/channel differs")
        if previous["sequence"] > m["sequence"]:
            return False
        if previous["sequence"] == m["sequence"]:
            if latest.read_bytes() != manifest.read_bytes():
                raise ValueError("Latest sequence collision")
            return False
    if staged.exists():
        if not staged.is_file():
            raise ValueError("Invalid latest staging file")
        staged.unlink()
    flush_copy(manifest, staged)
    os.replace(staged, latest)
    if latest.read_bytes() != manifest.read_bytes():
        raise ValueError("Latest pointer read-back mismatch")
    return True


def publish_manifest_locked(m, package, dest, public_key):
    """Publish only signed links; caller holds the channel lock."""
    if dest.exists() and {p.name for p in dest.iterdir()} - {"manifest.json", "manifest.json.next"}:
        raise ValueError("Metadata-only destination contains other files; use an isolated target")
    target = dest / "manifest.json"
    staged = dest / "manifest.json.next"
    if reparse(target) or reparse(staged):
        raise ValueError("Manifest publication crosses symlink")
    incoming = Path(package) / "manifest.json"
    identical = target.exists() and target.read_bytes() == incoming.read_bytes()
    if target.exists() and not identical:
        previous = verify_package(dest, public_key)
        if any(previous[k] != m[k] for k in ("repository", "minecraft", "loader", "channel")):
            raise ValueError("Existing manifest source/channel differs")
        if m["channel"] != "nightly":
            raise FileExistsError("Archived version is immutable; existing manifest differs")
        if m["sequence"] <= previous["sequence"]:
            raise ValueError("Stale publication sequence")
    if not identical:
        dest.mkdir(parents=True, exist_ok=True)
        if staged.exists():
            if not staged.is_file():
                raise ValueError("Invalid manifest staging file")
            staged.unlink()
        flush_copy(incoming, staged)
        os.replace(staged, target)
        verify_package(dest, public_key)
    updated = update_latest(m, dest, public_key)
    return {"destination": str(dest), "sequence": m["sequence"], "files": ["manifest.json"],
            "distribution": "per-jar", "idempotent": identical, "latestUpdated": updated}


def publish_local(package, public_key, root):
    m = verify_package(package, public_key)
    root = Path(root).resolve(strict=True)
    rel = relative_destination(m)
    dest = root / rel
    # Reject links below the explicitly chosen local storage root.
    at = root
    for part in rel.parts:
        at = at / part
        if reparse(at):
            raise ValueError("Publish path crosses symlink")
    if reparse(root / ".publisher"):
        raise ValueError("Lock directory crosses symlink")
    state_id = hashlib.sha256(str(rel).encode()).hexdigest()
    channel_rel = Path(m["repository"]) / m["minecraft"] / m["channel"]
    lock_id = hashlib.sha256(str(channel_rel).encode()).hexdigest()
    with file_lock(root / ".publisher" / (lock_id + ".lock")):
        state = root / ".publisher" / state_id
        if reparse(state):
            raise ValueError("State directory crosses symlink")
        state.mkdir(exist_ok=True)
        recover_publication(dest, state, public_key)
        if reparse(dest / "manifest.json") or reparse(dest / "bundle.zip"):
            raise ValueError("Publication files cross symlinks")
        if "bundle" not in m:
            return publish_manifest_locked(m, package, dest, public_key)
        if m["channel"] != "nightly" and dest.exists():
            verify_package(dest, public_key)
            if (dest / "manifest.json").read_bytes() != (Path(package) / "manifest.json").read_bytes():
                raise FileExistsError("Archived version is immutable; existing package differs")
            updated = update_latest(m, dest, public_key)
            return {"destination": str(dest), "sequence": m["sequence"], "files": ["bundle.zip", "manifest.json"],
                    "idempotent": True, "latestUpdated": updated}
        manifest = dest / "manifest.json"
        if reparse(manifest) or reparse(dest / "bundle.zip"):
            raise ValueError("Publication files cross symlinks")
        if manifest.exists():
            previous = verify_signature(json.loads(manifest.read_bytes()), public_key)
            validate_manifest(previous)
            if previous["repository"] != m["repository"] or previous["channel"] != m["channel"]:
                raise ValueError("Existing source/channel differs")
            if m["sequence"] <= previous["sequence"]:
                raise ValueError("Stale publication sequence")
        previous = manifest.exists()
        for name in ("bundle.zip", "manifest.json"):
            backup = state / (name + ".previous")
            if backup.exists():
                if reparse(backup):
                    raise ValueError("Backup crosses symlink")
                backup.unlink()
            if previous:
                flush_copy(dest / name, backup)
        transaction = canonical({"previous": previous, "sequence": m["sequence"],
                                 "manifestSha256": sha(Path(package) / "manifest.json")})
        journal_next = state / "transaction.json.next"
        if reparse(journal_next):
            raise ValueError("Journal crosses symlink")
        if journal_next.exists():
            journal_next.unlink()
        with journal_next.open("xb") as f:
            f.write(transaction)
            f.flush()
            os.fsync(f.fileno())
        os.replace(journal_next, state / "transaction.json")
        dest.mkdir(parents=True, exist_ok=True)
        # Journal exists before touching public files or creating an archive directory.
        for name in ("bundle.zip.next", "manifest.json.next"):
            p = dest / name
            if p.exists():
                if not p.is_file() or reparse(p):
                    raise ValueError("Invalid staging file")
                p.unlink()
        flush_copy(Path(package) / "bundle.zip", dest / "bundle.zip.next")
        flush_copy(Path(package) / "manifest.json", dest / "manifest.json.next")
        os.replace(dest / "bundle.zip.next", dest / "bundle.zip")
        os.replace(dest / "manifest.json.next", manifest)
        verify_package(dest, public_key)
        recover_publication(dest, state, public_key)
        updated = update_latest(m, dest, public_key)
        return {"destination": str(dest), "sequence": m["sequence"], "files": ["bundle.zip", "manifest.json"],
                "idempotent": False, "latestUpdated": updated}


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    key = sub.add_parser("generate-key")
    key.add_argument("--private-key", type=Path, required=True)
    key.add_argument("--public-key", type=Path, required=True)
    for command in ("manifest", "pack"):
        build = sub.add_parser(command, aliases=["sign-release"] if command == "manifest" else [],
                               help="recommended: sign existing release JAR links" if command == "manifest" else "optional: create a ZIP bundle")
        for name in ("jar-dir", "provenance", "output", "private-key", "public-key"):
            build.add_argument("--" + name, type=Path, required=True)
        for name in ("repository", "build-id", "version", "key-id"):
            build.add_argument("--" + name, required=True)
        build.add_argument("--sequence", type=int, required=True)
        build.add_argument("--channel", choices=sorted(CHANNELS), required=True)
        if command == "pack":
            build.add_argument("--bundle-url", required=True)
            build.add_argument("--bundle-mirror", action="append", default=[])
        else:
            build.add_argument("--tag", default="nightly")
            build.add_argument("--release-snapshot", type=Path,
                               help="defaults to release-snapshot.json beside provenance")
    for name in ("verify", "plan", "publish-local"):
        child = sub.add_parser(name)
        child.add_argument("--package", type=Path, required=True)
        child.add_argument("--public-key", type=Path, required=True)
        if name == "plan":
            child.add_argument("--mount", default="folkways")
        if name == "publish-local":
            child.add_argument("--root", type=Path, required=True)
            child.add_argument("--execute-local", action="store_true", required=True)
    return p


def main():
    args = parser().parse_args()
    if args.command == "generate-key":
        crypto("generate", args.private_key, args.public_key)
        result = {"publicKey": args.public_key.read_text(encoding="ascii"), "privateKeyWritten": str(args.private_key)}
    elif args.command in {"manifest", "sign-release"}:
        result = sign_release(args)
    elif args.command == "pack":
        result = pack(args)
    elif args.command == "verify":
        result = verify_package(args.package, args.public_key)
    elif args.command == "plan":
        result = plan(args.package, args.public_key, args.mount)
    elif args.command == "publish-local":
        result = publish_local(args.package, args.public_key, args.root)
    print(json.dumps(result, indent=2, ensure_ascii=True))


if __name__ == "__main__":
    main()
