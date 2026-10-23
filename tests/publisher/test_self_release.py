import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

SPEC = importlib.util.spec_from_file_location("self_release", Path(__file__).parents[2] / "tools/self_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SelfReleaseTests(unittest.TestCase):
    def setUp(self):
        scratch = Path.cwd() / "work" / "release-test-tmp"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self, tag=False):
        context = release.release_context("v1.2.3" if tag else "main", "tag" if tag else "branch", "123", "9", "1")
        m = {"schema": 1, "repository": release.REPOSITORY, "minecraft": "1.21.1", "version": context["version"],
             "channel": context["channel"], "commit": "a" * 40, "runId": "123", "runNumber": 9, "runAttempt": 1, "sequence": 9001}
        for component, folder, name in (
            ("bootstrap", "updater-core", f"folkways-bootstrap-{m['version']}-standalone.jar"),
            ("companion", "companion", f"folkways-updater-neoforge-1.21.1-{m['version']}.jar")
        ):
            path = self.root / folder / "build/libs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "w") as z:
                z.writestr(release.BUILD_INFO, json.dumps({**m, "component": component}))
                z.writestr("META-INF/MANIFEST.MF", "Main-Class: dev.davisye.folkways.update.core.Bootstrap\r\n")
                z.writestr("dev/davisye/Test.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x41")
                if component == "companion":
                    z.writestr("META-INF/neoforge.mods.toml", f'[[mods]]\nmodId="folkways_updater"\nversion="{m["version"]}"\n')
        for name in release.SEEDS:
            (self.root / "updater-core" / name).write_text("seed fixture", encoding="utf-8")
        out = self.root / "dist"
        result = release.prepare(self.root, out, context, "a" * 40, "123", "1")
        return out, result

    def test_version_and_monotonic_id(self):
        first = release.release_context("main", "branch", "123", "9", "1")
        retry = release.release_context("main", "branch", "123", "9", "2")
        self.assertEqual(first["version"], "0.1.0-nightly.9.1")
        self.assertGreater(retry["sequence"], first["sequence"])
        self.assertEqual(release.release_context("v1.2.3", "tag", "124", "10", "1")["version"], "1.2.3")
        with self.assertRaises(ValueError):
            release.release_context("vbad;code", "tag", "124", "10", "1")

    def test_prepare_and_verify_exact_six_assets(self):
        out, m = self.fixture()
        self.assertEqual(len(list(out.iterdir())), 6)
        self.assertEqual(release.verify_directory(out), m)
        self.assertEqual({a["component"] for a in m["artifacts"]}, {"bootstrap", "companion"})
        self.assertTrue(all(a["url"].startswith("https://github.com/davisye/update-folkways/releases/download/nightly/") for a in m["artifacts"]))

    def test_changed_jar_is_rejected(self):
        out, m = self.fixture()
        (out / m["artifacts"][0]["fileName"]).write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash/size"):
            release.verify_directory(out)

    def test_seed_checksum_is_verified(self):
        out, _ = self.fixture()
        (out / "launch.sh").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum"):
            release.verify_directory(out)

    def test_wrong_source_url_is_rejected(self):
        _, m = self.fixture()
        m["artifacts"][0]["url"] = "https://evil.example/bootstrap.jar"
        with self.assertRaisesRegex(ValueError, "canonical"):
            release.validate_metadata(m)

    def test_wrong_workflow_is_rejected(self):
        _, m = self.fixture()
        m["workflow"] = ".github/workflows/untrusted.yml"
        with self.assertRaisesRegex(ValueError, "workflow"):
            release.validate_metadata(m)

    def test_artifact_over_client_limit_is_rejected(self):
        _, m = self.fixture()
        m["artifacts"][0]["size"] = release.MAX_JAR + 1
        with self.assertRaisesRegex(ValueError, "size"):
            release.validate_metadata(m)

    def test_pr_cannot_publish_before_any_network_call(self):
        out, m = self.fixture()
        class Never:
            def api(self, *args, **kwargs):
                raise AssertionError("PR publication reached network")
        with self.assertRaisesRegex(ValueError, "trusted"):
            release.publish(out, {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": release.REPOSITORY}, Never())

    def test_stable_tag_outside_main_is_rejected(self):
        out, m = self.fixture(tag=True)
        class Fake:
            def api(self, path, *args, **kwargs):
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": "a" * 40, "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": "b" * 40}}
                if path.startswith("compare/"):
                    return {"status": "diverged"}
                raise AssertionError("Write reached before trusted ancestry")
        env = {"GITHUB_EVENT_NAME": "push", "GITHUB_REPOSITORY": release.REPOSITORY,
               "GITHUB_REF": "refs/tags/v1.2.3", "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_NUMBER": "9", "GITHUB_RUN_ATTEMPT": "1"}
        with self.assertRaisesRegex(ValueError, "main history"):
            release.publish(out, env, Fake())

    def environment(self, m):
        return {"GITHUB_EVENT_NAME": "push", "GITHUB_REPOSITORY": release.REPOSITORY,
                "GITHUB_REF": "refs/heads/main" if m["channel"] == "nightly" else "refs/tags/v" + m["version"],
                "GITHUB_SHA": m["commit"], "GITHUB_RUN_ID": m["runId"], "GITHUB_RUN_ATTEMPT": str(m["runAttempt"]), "GITHUB_RUN_NUMBER": str(m["runNumber"])}

    def test_nightly_publishes_exact_six_files_metadata_last(self):
        out, m = self.fixture()
        class Fake:
            def __init__(self):
                self.assets, self.uploads, self.final = [], [], None

            def api(self, path, method="GET", data=None, binary=None):
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": m["commit"]}}
                if path in {"releases/tags/nightly", "git/ref/tags/nightly"}:
                    return None
                if path == "releases?per_page=100&page=1":
                    return []
                if path == "git/refs" and method == "POST":
                    return {"ref": data["ref"]}
                if path == "releases" and method == "POST":
                    return {"id": 1}
                if path == "releases/1/assets?per_page=100":
                    return self.assets
                if path.startswith("releases/1/assets?name="):
                    name = parse_qs(urlsplit(path).query)["name"][0]
                    asset = {"id": len(self.assets) + 1, "name": name, "size": len(binary),
                             "digest": "sha256:" + release.hashlib.sha256(binary).hexdigest()}
                    self.assets.append(asset)
                    self.uploads.append(name)
                    return asset
                if path == "releases/1" and method == "PATCH":
                    self.final = data
                    return data
                raise AssertionError((method, path))
        api = Fake()
        result = release.publish(out, self.environment(m), api)
        self.assertTrue(result["published"])
        self.assertEqual(len(api.assets), 6)
        self.assertEqual(api.uploads[-1], release.METADATA)
        self.assertFalse(api.final["draft"])

    def test_old_main_run_cannot_write_nightly(self):
        out, m = self.fixture()
        class Fake:
            def api(self, path, method="GET", **kwargs):
                if method != "GET":
                    raise AssertionError("Old main run attempted write")
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": "b" * 40}}
                raise AssertionError(path)
        result = release.publish(out, self.environment(m), Fake())
        self.assertFalse(result["published"])
        self.assertEqual(result["reason"], "newer main commit exists")

    def test_uploaded_digest_failure_does_not_publish_draft(self):
        out, m = self.fixture()
        class Fake:
            def __init__(self):
                self.assets = []
                self.final = None

            def api(self, path, method="GET", data=None, binary=None):
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": m["commit"]}}
                if path in {"releases/tags/nightly", "git/ref/tags/nightly"}:
                    return None
                if path == "releases?per_page=100&page=1":
                    return []
                if path == "git/refs":
                    return {}
                if path == "releases" and method == "POST":
                    return {"id": 1}
                if path == "releases/1/assets?per_page=100":
                    return self.assets
                if path.startswith("releases/1/assets?name="):
                    name = parse_qs(urlsplit(path).query)["name"][0]
                    a = {"id": len(self.assets) + 1, "name": name, "size": len(binary), "digest": "sha256:" + "0" * 64}
                    self.assets.append(a)
                    return a
                if path == "releases/1" and method == "PATCH":
                    self.final = data
                    raise AssertionError("Unverified assets were published")
                raise AssertionError((method, path))
        api = Fake()
        with patch.object(release.time, "sleep"), self.assertRaisesRegex(ValueError, "digest verification"):
            release.publish(out, self.environment(m), api)
        self.assertIsNone(api.final)

    def test_existing_newer_nightly_cannot_be_overwritten(self):
        out, m = self.fixture()
        old = json.loads(json.dumps(m))
        old["runId"], old["runNumber"], old["sequence"] = "124", 10, 10001
        class Fake:
            def api(self, path, method="GET", **kwargs):
                if method != "GET":
                    raise AssertionError("Stale nightly attempted write")
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": m["commit"]}}
                if path == "releases/tags/nightly":
                    return {"assets": [{"name": release.METADATA}]}
                raise AssertionError(path)
        with patch.object(release, "read_public_asset", return_value=old):
            result = release.publish(out, self.environment(m), Fake())
        self.assertFalse(result["published"])

    def test_failed_initial_nightly_draft_does_not_require_public_download(self):
        out, m = self.fixture()
        class Fake:
            def __init__(self):
                self.assets, self.uploads, self.final = [], [], None

            def api(self, path, method="GET", data=None, binary=None):
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": m["commit"]}}
                if path == "releases/tags/nightly":
                    return None  # Actual by-tag API returns 404 for a draft.
                if path == "releases?per_page=100&page=1":
                    return [{"tag_name": "nightly", "draft": True, "target_commitish": m["commit"], "id": 1}]
                if path == "git/ref/tags/nightly":
                    return {"ref": "refs/tags/nightly"}
                if path == "git/refs/tags/nightly" and method == "PATCH":
                    return data
                if path == "releases/1/assets?per_page=100":
                    return self.assets
                if path.startswith("releases/1/assets?name="):
                    name = parse_qs(urlsplit(path).query)["name"][0]
                    asset = {"id": len(self.assets) + 1, "name": name, "size": len(binary),
                             "digest": "sha256:" + release.hashlib.sha256(binary).hexdigest()}
                    self.assets.append(asset)
                    self.uploads.append(name)
                    return asset
                if path == "releases/1" and method == "PATCH":
                    self.final = data
                    return data
                raise AssertionError((method, path))
        api = Fake()
        with patch.object(release, "read_public_asset", side_effect=AssertionError("Private draft read attempted")):
            result = release.publish(out, self.environment(m), api)
        self.assertTrue(result["published"])
        self.assertEqual(len(api.uploads), 6)
        self.assertEqual(api.uploads[-1], release.METADATA)
        self.assertFalse(api.final["draft"])

    def test_draft_recovery_enumerates_all_pages(self):
        draft = {"id": 1, "tag_name": "nightly", "draft": True, "target_commitish": "a" * 40}
        class Fake:
            def api(self, path):
                if path == "releases/tags/nightly":
                    return None
                if path == "releases?per_page=100&page=1":
                    return [{"tag_name": "other"}] * 100
                if path == "releases?per_page=100&page=2":
                    return [draft]
                raise AssertionError(path)
        self.assertEqual(release.find_release(Fake(), "nightly", "a" * 40), draft)

    def test_draft_recovery_rejects_duplicate_tag_across_pages(self):
        draft = {"id": 1, "tag_name": "nightly", "draft": True, "target_commitish": "a" * 40}
        class Fake:
            def api(self, path):
                if path == "releases/tags/nightly":
                    return None
                if path == "releases?per_page=100&page=1":
                    return [draft] + [{"tag_name": "other"}] * 99
                if path == "releases?per_page=100&page=2":
                    return [{**draft, "id": 2}]
                raise AssertionError(path)
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            release.find_release(Fake(), "nightly", "a" * 40)

    def test_draft_recovery_rejects_different_commit(self):
        class Fake:
            def api(self, path):
                if path == "releases/tags/nightly":
                    return None
                if path == "releases?per_page=100&page=1":
                    return [{"id": 1, "tag_name": "nightly", "draft": True, "target_commitish": "b" * 40}]
                raise AssertionError(path)
        with self.assertRaisesRegex(ValueError, "exact build commit"):
            release.find_release(Fake(), "nightly", "a" * 40)

    def test_published_stable_tag_is_immutable(self):
        out, m = self.fixture(tag=True)
        class Fake:
            def api(self, path, method="GET", **kwargs):
                if method != "GET":
                    raise AssertionError("Stable archive overwrite attempted")
                if path.startswith("actions/runs/"):
                    return {"event": "push", "head_sha": m["commit"], "path": release.WORKFLOW, "run_number": 9,
                            "repository": {"full_name": release.REPOSITORY}, "head_repository": {"full_name": release.REPOSITORY}}
                if path == "branches/main":
                    return {"commit": {"sha": m["commit"]}}
                if path.startswith("compare/"):
                    return {"status": "identical"}
                if path.startswith("releases/tags/"):
                    return {"draft": False, "target_commitish": m["commit"]}
                raise AssertionError(path)
        with self.assertRaisesRegex(FileExistsError, "immutable"):
            release.publish(out, self.environment(m), Fake())


if __name__ == "__main__":
    unittest.main()
