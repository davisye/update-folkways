import argparse
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import shutil
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("publish", Path(__file__).parents[2] / "tools" / "publish.py")
pub = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pub)


class PublisherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(dir=pub.scratch())
        cls.base = Path(cls.temp.name)
        cls.private, cls.public = cls.base / "private.key", cls.base / "public.key"
        pub.crypto("generate", cls.private, cls.public)
        cls.jars = cls.base / "jars"
        cls.jars.mkdir()
        files = {}
        for module in sorted(pub.MODULES):
            p = cls.jars / f"folkways-{module}-neoforge-1.21.1-0.1.0.jar"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr("META-INF/neoforge.mods.toml", f'[[mods]]\nmodId="folkways_{module}"\nversion="0.1.0"\n')
                z.writestr("content", module)
            files[p.name] = {"sha256": pub.sha(p), "size": p.stat().st_size}
        cls.prov = cls.base / "provenance.json"
        cls.prov.write_text(json.dumps({"repository": "IzakyL/folkways", "buildId": "a" * 40,
                                      "minecraft": "1.21.1", "files": files}), encoding="utf-8")
        cls.release_snapshot = cls.base / "release-snapshot.json"
        cls.release_snapshot.write_text(json.dumps({"releaseId": 100, "tag": "nightly", "buildId": "a" * 40,
            "assets": [{"id": i, "name": name, "digest": "sha256:" + data["sha256"], "size": data["size"]}
                       for i, (name, data) in enumerate(files.items(), 1)]}), encoding="utf-8")
        cls.package = cls.base / "package"
        cls.args = argparse.Namespace(repository="IzakyL/folkways", channel="nightly", build_id="a" * 40,
                                     sequence=10, version="nightly", jar_dir=cls.jars, provenance=cls.prov,
                                     output=cls.package, private_key=cls.private, public_key=cls.public,
                                     key_id="test", bundle_url="https://pan.example/d/folkways/bundle.zip", bundle_mirror=[])
        cls.manifest = pub.pack(cls.args)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_signed_bundle_roundtrip(self):
        m = pub.verify_package(self.package, self.public)
        self.assertEqual(m["buildId"], "a" * 40)
        self.assertEqual(len(m["components"]), 5)

    def test_tampered_signature_rejected(self):
        e = json.loads((self.package / "manifest.json").read_bytes())
        e["payload"] = base64.b64encode(b'{}').decode()
        with self.assertRaises(Exception):
            pub.verify_signature(e, self.public)

    def test_bundle_tamper_rejected(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            p = Path(t)
            (p / "manifest.json").write_bytes((self.package / "manifest.json").read_bytes())
            (p / "bundle.zip").write_bytes(b"bad")
            with self.assertRaisesRegex(ValueError, "Bundle digest"):
                pub.verify_package(p, self.public)

    def test_provenance_mixed_build_rejected(self):
        args = argparse.Namespace(**vars(self.args))
        args.build_id = "b" * 40
        with self.assertRaisesRegex(ValueError, "Provenance"):
            pub.pack(args)

    def test_duplicate_component_rejected(self):
        m = json.loads(json.dumps(self.manifest))
        m["components"].append(m["components"][0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            pub.validate_manifest(m)

    def test_path_traversal_rejected(self):
        m = json.loads(json.dumps(self.manifest))
        m["components"][0]["file"] = "../escape.jar"
        with self.assertRaises(ValueError):
            pub.validate_manifest(m)

    def test_nightly_publish_bounded_and_stale_rejected(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            result = pub.publish_local(self.package, self.public, Path(t))
            dest = Path(result["destination"])
            self.assertEqual({p.name for p in dest.iterdir()}, {"bundle.zip", "manifest.json"})
            with self.assertRaisesRegex(ValueError, "Stale"):
                pub.publish_local(self.package, self.public, Path(t))

    def test_openlist_plan_performs_no_network_write(self):
        result = pub.plan(self.package, self.public, "folkways")
        self.assertFalse(result["productionWritesImplemented"])
        self.assertEqual(result["destination"], "/folkways/IzakyL/folkways/1.21.1/nightly")
        self.assertEqual(result["operations"][1]["local"], "bundle.zip")
        self.assertEqual(result["operations"][3]["local"], "manifest.json")

    def test_interrupted_first_archive_recovered_and_same_package_retried(self):
        args = argparse.Namespace(**vars(self.args))
        args.channel = "alpha"
        args.version = "0.1.0-alpha.1"
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            args.output = t / "archive-package"
            pub.pack(args)
            root = t / "mirror"
            root.mkdir()
            original = pub.os.replace

            def fail_manifest(src, dst):
                if Path(src).name == "manifest.json.next":
                    raise OSError("simulated process interruption")
                return original(src, dst)

            with patch.object(pub.os, "replace", side_effect=fail_manifest):
                with self.assertRaisesRegex(OSError, "interruption"):
                    pub.publish_local(args.output, self.public, root)
            result = pub.publish_local(args.output, self.public, root)
            dest = Path(result["destination"])
            self.assertEqual({x.name for x in dest.iterdir()}, {"bundle.zip", "manifest.json"})
            repeated = pub.publish_local(args.output, self.public, root)
            self.assertTrue(repeated["idempotent"])
            self.assertFalse(repeated["latestUpdated"])

    def test_interrupted_nightly_switch_restores_old_pair_before_retry(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            root = t / "mirror"
            root.mkdir()
            result = pub.publish_local(self.package, self.public, root)
            dest = Path(result["destination"])
            new_jars = t / "jars"
            shutil.copytree(self.jars, new_jars)
            core = next(new_jars.glob("folkways-core-*.jar"))
            with zipfile.ZipFile(core, "a") as z:
                z.writestr("new-build", "new-build-content")
            prov = json.loads(self.prov.read_text(encoding="utf-8"))
            prov["buildId"] = "b" * 40
            prov["files"] = {p.name: {"sha256": pub.sha(p), "size": p.stat().st_size} for p in new_jars.glob("*.jar")}
            prov_file = t / "provenance.json"
            prov_file.write_text(json.dumps(prov), encoding="utf-8")
            args = argparse.Namespace(**vars(self.args))
            args.sequence = 11
            args.build_id = "b" * 40
            args.jar_dir = new_jars
            args.provenance = prov_file
            args.output = t / "new-package"
            pub.pack(args)
            original = pub.os.replace

            def fail_manifest(src, dst):
                if Path(src).name == "manifest.json.next":
                    raise OSError("simulated process interruption")
                return original(src, dst)

            with patch.object(pub.os, "replace", side_effect=fail_manifest):
                with self.assertRaises(OSError):
                    pub.publish_local(args.output, self.public, root)
            with self.assertRaisesRegex(ValueError, "Bundle digest mismatch"):
                pub.verify_package(dest, self.public)
            rel = pub.relative_destination(self.manifest)
            state = root / ".publisher" / hashlib.sha256(str(rel).encode()).hexdigest()
            pub.recover_publication(dest, state, self.public)
            self.assertEqual(pub.verify_package(dest, self.public)["sequence"], 10)
            result = pub.publish_local(args.output, self.public, root)
            self.assertEqual(pub.verify_package(Path(result["destination"]), self.public)["sequence"], 11)
            self.assertEqual({p.name for p in dest.iterdir()}, {"bundle.zip", "manifest.json"})

    def make_release(self, temp, version, sequence, suffix):
        args = argparse.Namespace(**vars(self.args))
        args.channel = "release"
        args.version = version
        args.sequence = sequence
        args.output = temp / suffix
        args.bundle_url = f"https://pan.example/d/folkways/IzakyL/folkways/1.21.1/release/{version}/bundle.zip"
        pub.pack(args)
        return args.output

    def test_release_latest_keeps_highest_sequence_and_archives_all_versions(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            root = t / "mirror"
            root.mkdir()
            first = self.make_release(t, "0.1.1", 11, "first")
            second = self.make_release(t, "0.1.2", 12, "second")
            older = self.make_release(t, "0.1.0", 5, "older")
            pub.publish_local(first, self.public, root)
            newest = pub.publish_local(second, self.public, root)
            self.assertTrue(newest["latestUpdated"])
            old = pub.publish_local(older, self.public, root)
            self.assertFalse(old["latestUpdated"])
            channel = root / "IzakyL" / "folkways" / "1.21.1" / "release"
            latest = pub.verify_signature(json.loads((channel / "latest.json").read_bytes()), self.public)
            self.assertEqual(latest["sequence"], 12)
            self.assertEqual((channel / "latest.json").read_bytes(), (second / "manifest.json").read_bytes())
            for version in ("0.1.0", "0.1.1", "0.1.2"):
                self.assertEqual({p.name for p in (channel / version).iterdir()}, {"bundle.zip", "manifest.json"})

    def test_release_same_version_different_package_never_overwrites(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            root = t / "mirror"
            root.mkdir()
            first = self.make_release(t, "0.1.1", 11, "first")
            different = self.make_release(t, "0.1.1", 12, "different")
            dest = Path(pub.publish_local(first, self.public, root)["destination"])
            before = (dest / "manifest.json").read_bytes()
            with self.assertRaisesRegex(FileExistsError, "immutable"):
                pub.publish_local(different, self.public, root)
            self.assertEqual((dest / "manifest.json").read_bytes(), before)

    def test_latest_failure_after_archive_commit_allows_idempotent_retry(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            root = t / "mirror"
            root.mkdir()
            package = self.make_release(t, "0.1.1", 11, "release")
            original = pub.os.replace

            def fail_latest(src, dst):
                if Path(src).name == "latest.json.next":
                    raise OSError("simulated latest failure")
                return original(src, dst)

            with patch.object(pub.os, "replace", side_effect=fail_latest):
                with self.assertRaisesRegex(OSError, "latest failure"):
                    pub.publish_local(package, self.public, root)
            result = pub.publish_local(package, self.public, root)
            self.assertTrue(result["idempotent"])
            self.assertTrue(result["latestUpdated"])
            latest = Path(result["destination"]).parent / "latest.json"
            self.assertEqual(latest.read_bytes(), (package / "manifest.json").read_bytes())

    def test_release_plan_includes_signed_latest_after_archive_verification(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            package = self.make_release(Path(t), "0.1.1", 11, "release")
            result = pub.plan(package, self.public, "folkways")
            self.assertEqual(len(result["operations"]), 7)
            self.assertEqual(result["operations"][5]["local"], "manifest.json")
            self.assertTrue(result["operations"][6]["path"].endswith("/release/latest.json"))

    def make_per_jar(self, temp, channel="nightly", version="nightly", sequence=20):
        args = argparse.Namespace(**vars(self.args))
        args.channel, args.version, args.sequence = channel, version, sequence
        args.output = temp / "per-jar"
        args.tag = "nightly" if channel == "nightly" else "v" + version
        args.release_snapshot = temp / "release-snapshot.json"
        snapshot = json.loads(self.release_snapshot.read_text(encoding="utf-8"))
        snapshot["tag"] = args.tag
        args.release_snapshot.write_text(json.dumps(snapshot), encoding="utf-8")
        manifest = pub.sign_release(args)
        return args, manifest

    def test_per_jar_signed_roundtrip_has_no_zip_and_canonical_urls(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            args, manifest = self.make_per_jar(Path(t))
            self.assertNotIn("bundle", manifest)
            self.assertEqual({p.name for p in args.output.iterdir()}, {"manifest.json"})
            verified = pub.verify_package(args.output, self.public)
            self.assertEqual(verified, manifest)
            for component in manifest["components"]:
                self.assertEqual(component["url"], "https://github.com/IzakyL/folkways/releases/download/nightly/" + component["file"])

    def test_per_jar_missing_or_insecure_url_rejected(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            _, manifest = self.make_per_jar(Path(t))
            missing = json.loads(json.dumps(manifest))
            del missing["components"][0]["url"]
            with self.assertRaises(KeyError):
                pub.validate_manifest(missing)
            manifest["components"][0]["url"] = "http://github.com/asset.jar"
            with self.assertRaisesRegex(ValueError, "HTTPS"):
                pub.validate_manifest(manifest)

    def test_per_jar_signing_refuses_unmatched_snapshot_digest(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            args = argparse.Namespace(**vars(self.args))
            args.output = t / "bad"
            args.tag = "nightly"
            args.release_snapshot = t / "release-snapshot.json"
            snapshot = json.loads(self.release_snapshot.read_text(encoding="utf-8"))
            snapshot["assets"][0]["digest"] = "sha256:" + "0" * 64
            args.release_snapshot.write_text(json.dumps(snapshot), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "snapshot digest"):
                pub.sign_release(args)
            self.assertFalse(args.output.exists())

    def test_per_jar_plan_uploads_only_small_manifest(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            args, _ = self.make_per_jar(Path(t))
            result = pub.plan(args.output, self.public, "folkways")
            self.assertEqual(result["distribution"], "per-jar")
            uploads = [op for op in result["operations"] if op["method"] == "PUT"]
            self.assertTrue(uploads)
            self.assertTrue(all(op["local"] == "manifest.json" for op in uploads))
            self.assertEqual(len([op for op in result["operations"] if "url" in op]), 5)

    def test_per_jar_local_publication_has_single_manifest_and_signed_latest(self):
        with tempfile.TemporaryDirectory(dir=pub.scratch()) as t:
            t = Path(t)
            args, _ = self.make_per_jar(t, channel="release", version="0.1.1")
            root = t / "mirror"
            root.mkdir()
            result = pub.publish_local(args.output, self.public, root)
            dest = Path(result["destination"])
            self.assertEqual({p.name for p in dest.iterdir()}, {"manifest.json"})
            self.assertEqual((dest.parent / "latest.json").read_bytes(), (dest / "manifest.json").read_bytes())
            self.assertTrue(pub.publish_local(args.output, self.public, root)["idempotent"])


if __name__ == "__main__":
    unittest.main()
