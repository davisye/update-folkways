import copy
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from unittest.mock import patch


SCRIPT = Path(__file__).parents[2] / "tools" / "sync_openlist.py"
REPOSITORIES = ("IzakyL/folkways", "davisye/folkways")
MODULES = ("core", "vanilla", "create", "modulargolems", "cookery")
SPEC = importlib.util.spec_from_file_location("sync_openlist", SCRIPT)
sync = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sync
SPEC.loader.exec_module(sync)

TREE = ("OtherOwner:\r\n  archive:\r\n    keep.jar:4:https://example.invalid/keep\r\n"
        "IzakyL:\r\n  folkways:\r\n    1.20.1:\r\n      nightly:\r\n        old-mc.jar:4:https://example.invalid/mc\r\n"
        "    1.21.1:\r\n      release:\r\n        stable.jar:4:https://example.invalid/release\r\n"
        "      nightly:\r\n        stale-upstream.jar:4:https://example.invalid/upstream\r\n"
        "      alpha:\r\n        alpha.jar:4:https://example.invalid/alpha\r\n"
        "  another-repo:\r\n    1.21.1:\r\n      nightly:\r\n        sibling.jar:4:https://example.invalid/sibling\r\n"
        "davisye:\r\n  folkways:\r\n    1.21.1:\r\n      nightly:\r\n        stale-fork.jar:4:https://example.invalid/fork\r\n")


def storage(tree=TREE):
    return {"id": 5, "mount_path": "/Guest/folkways", "driver": "UrlTree", "disabled": False,
            "web_proxy": False, "webdav_policy": "302_redirect", "down_proxy_url": "", "status": "work",
            "remark": "preserve this row field", "addition": json.dumps({"url_structure": tree, "head_size": False, "writable": False, "custom": "retain"})}


def tree_of(client):
    return json.loads(client.current["addition"])["url_structure"]


def release(repository, number=14, version="0.1.0-nightly"):
    commit = hashlib.sha256(f"{repository}:{number}".encode()).hexdigest()[:40]
    assets = []
    for index, module in enumerate(MODULES, 1):
        name = f"folkways-{module}-neoforge-1.21.1-{version}.jar"
        payload = f"{repository}:{number}:{module}".encode()
        assets.append({"id": number * 100 + index, "name": name, "size": len(payload),
                       "digest": "sha256:" + hashlib.sha256(payload).hexdigest(), "state": "uploaded",
                       "updated_at": "2026-10-24T00:00:00Z",
                       "browser_download_url": f"https://github.com/{repository}/releases/download/nightly/{name}"})
    return {"id": 1000 + number, "url": f"https://api.github.com/repos/{repository}/releases/{1000 + number}",
            "tag_name": "nightly", "target_commitish": commit, "draft": False, "prerelease": True,
            "updated_at": "2026-10-24T00:00:00Z", "assets": assets}


def run(repository, snapshot, number=14):
    return {"id": 10000 + number, "run_number": number, "run_attempt": 1,
            "status": "completed", "conclusion": "success", "event": "push",
            "path": ".github/workflows/build.yml", "head_sha": snapshot["target_commitish"], "head_branch": "master",
            "repository": {"full_name": repository}, "head_repository": {"full_name": repository}}


class SimulatedCrash(BaseException):
    pass


class FakeClient:
    def __init__(self, storage):
        self.current = copy.deepcopy(storage)
        self.releases = {repository: release(repository) for repository in REPOSITORIES}
        self.runs = {repository: run(repository, self.releases[repository]) for repository in REPOSITORIES}
        self.updates = []
        self.probes = []
        self.public_checks = []
        self.github_reads = []
        self.storage_reads = 0
        self.before_storage = None
        self.after_update = None
        self.public_error = None

    def github(self, path):
        self.github_reads.append(path)
        repository = next(repository for repository in REPOSITORIES if f"/repos/{repository}/" in path)
        if path.endswith("/releases/tags/nightly"):
            return copy.deepcopy(self.releases[repository])
        if "/commits/" in path:
            return {"sha": self.releases[repository]["target_commitish"]}
        if path.endswith("/git/ref/tags/nightly"):
            return {"object": {"type": "commit", "sha": self.releases[repository]["target_commitish"]}}
        if "/actions/" in path:
            return {"workflow_runs": [copy.deepcopy(self.runs[repository])]}
        raise AssertionError(f"Unexpected GitHub request: {path}")

    def storage(self):
        self.storage_reads += 1
        if self.before_storage is not None:
            self.before_storage(self)
        return copy.deepcopy(self.current)

    def update_storage(self, storage):
        self.updates.append(copy.deepcopy(storage))
        self.current = copy.deepcopy(storage)
        if self.after_update is not None:
            self.after_update(self)

    def probe(self, asset):
        self.probes.append(copy.deepcopy(asset))

    def verify_public(self, repository, assets):
        self.public_checks.append((repository, copy.deepcopy(assets)))
        if self.public_error is not None:
            raise self.public_error

    def publish(self, repository, number):
        self.releases[repository] = release(repository, number, f"0.1.0-nightly.{number}")
        self.runs[repository] = run(repository, self.releases[repository], number)


class SyncOpenListTests(unittest.TestCase):
    def temporary(self):
        root = Path.cwd() / "work" / "publisher-tmp"
        root.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=root)

    def test_both_release_name_variants_are_a_complete_unique_five_jar_snapshot(self):
        for repository, version in zip(REPOSITORIES, ("0.1.0", "0.1.0-nightly")):
            snapshot = sync.validate_release(repository, release(repository, version=version))
            self.assertEqual(len(snapshot["assets"]), 5)
            self.assertTrue(all(asset["url"].startswith(sync.MIRROR + repository + "/") for asset in snapshot["assets"]))
            self.assertEqual(len({asset["name"] for asset in snapshot["assets"]}), 5)

    def test_missing_duplicate_mixed_and_unauthenticated_assets_are_rejected(self):
        for change in (lambda r: r["assets"].pop(), lambda r: r["assets"].append(copy.deepcopy(r["assets"][0])),
                       lambda r: r["assets"][0].update(digest=None), lambda r: r["assets"][0].update(size=3),
                       lambda r: r.update(target_commitish="master"), lambda r: r.update(draft=True),
                       lambda r: r["assets"][0].update(browser_download_url="https://evil.invalid/download.jar")):
            snapshot = release(REPOSITORIES[0]); change(snapshot)
            with self.subTest(snapshot=snapshot), self.assertRaises(sync.SyncError):
                sync.validate_release(REPOSITORIES[0], snapshot)
        with self.assertRaises(sync.SyncError):
            sync.validate_release("another/repository", release(REPOSITORIES[0]))
        mixed = release(REPOSITORIES[0]); mixed["assets"][0]["name"] = mixed["assets"][0]["name"].replace("0.1.0-nightly", "0.2.0-nightly")
        mixed["assets"][0]["browser_download_url"] = f"https://github.com/{REPOSITORIES[0]}/releases/download/nightly/" + mixed["assets"][0]["name"]
        with self.assertRaisesRegex(sync.SyncError, "mixed"):
            sync.validate_release(REPOSITORIES[0], mixed)

    def test_replace_nightly_preserves_other_owner_repository_channel_and_game_version(self):
        result = sync.replace_nightly(TREE, REPOSITORIES[0], ["new.jar:8:https://example.invalid/new"])
        self.assertNotIn("stale-upstream.jar", result)
        for unchanged in ("OtherOwner:\r\n  archive:\r\n    keep.jar:4:https://example.invalid/keep\r\n",
                          "    1.20.1:\r\n      nightly:\r\n        old-mc.jar:4:https://example.invalid/mc\r\n",
                          "      release:\r\n        stable.jar:4:https://example.invalid/release\r\n",
                          "      alpha:\r\n        alpha.jar:4:https://example.invalid/alpha\r\n",
                          "        sibling.jar:4:https://example.invalid/sibling\r\n", "        stale-fork.jar:4:https://example.invalid/fork\r\n"):
            self.assertIn(unchanged, result)
        self.assertEqual(sync.replace_nightly(result, REPOSITORIES[0], ["new.jar:8:https://example.invalid/new"]), result)

    def test_missing_source_tree_is_created_without_replacing_sibling_branches(self):
        original = "davisye:\n  different:\n    keep.jar:4:https://example.invalid/keep\n"
        result = sync.replace_nightly(original, REPOSITORIES[1], ["new.jar:4:https://example.invalid/new"])
        self.assertIn(original, result); self.assertIn("  folkways:\n    1.21.1:\n      nightly:\n", result)

    def test_dry_run_does_not_create_or_mutate_any_state_file_or_storage_row(self):
        with self.temporary() as temporary:
            root = Path(temporary) / "not-created"; client = FakeClient(storage()); before = copy.deepcopy(client.current)
            report = sync.SyncEngine(client, root, now=lambda: 1000).run()
            self.assertTrue(report["changed"]); self.assertFalse(root.exists()); self.assertEqual(client.current, before); self.assertEqual(client.updates, [])
            root.mkdir(); sentinel = root / "operator-note"; sentinel.write_bytes(b"unchanged")
            sync.SyncEngine(client, root, now=lambda: 1000).run()
            self.assertEqual({p.name: p.read_bytes() for p in root.iterdir()}, {"operator-note": b"unchanged"})

    def test_success_updates_both_sources_atomically_and_preserves_unrelated_storage_fields(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); report = sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertEqual(set(report["acceptedSources"]), set(REPOSITORIES)); self.assertEqual(len(client.updates), 1); self.assertEqual(len(client.probes), 10)
            self.assertEqual(client.current["remark"], "preserve this row field"); self.assertEqual(json.loads(client.current["addition"])["custom"], "retain")
            self.assertNotIn("stale-upstream.jar", tree_of(client)); self.assertNotIn("stale-fork.jar", tree_of(client)); self.assertIn("stable.jar", tree_of(client))
            state = json.loads((root / "state.json").read_text()); self.assertEqual(set(state["sources"]), set(REPOSITORIES)); self.assertFalse((root / "journal.json").exists())

    def test_invalid_ci_blocks_only_its_own_source_and_cannot_overwrite_other_directories(self):
        with self.temporary() as temporary:
            client = FakeClient(storage()); client.runs[REPOSITORIES[0]]["status"] = "in_progress"
            report = sync.SyncEngine(client, Path(temporary), now=lambda: 1000).run(execute=True)
            self.assertEqual(report["acceptedSources"], [REPOSITORIES[1]]); self.assertIn("stale-upstream.jar", tree_of(client)); self.assertNotIn("stale-fork.jar", tree_of(client))
            self.assertIn("stable.jar", tree_of(client)); self.assertEqual(len(client.probes), 5)

    def test_source_high_water_is_independent_and_reused_or_older_builds_are_rejected(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); client.publish(REPOSITORIES[0], 114); client.publish(REPOSITORIES[1], 14)
            engine = sync.SyncEngine(client, root, now=lambda: 1000); engine.run(execute=True); before = copy.deepcopy(client.current)
            state = json.loads((root / "state.json").read_text()); self.assertEqual(state["sources"][REPOSITORIES[0]]["sequence"], 114001); self.assertEqual(state["sources"][REPOSITORIES[1]]["sequence"], 14001)
            client.releases[REPOSITORIES[0]]["assets"][0]["id"] += 1
            client.publish(REPOSITORIES[1], 13); report = engine.run(execute=True)
            self.assertEqual(report["acceptedSources"], []); self.assertEqual(client.current, before)
            self.assertTrue(all(value == "release-rollback-or-changed-ci-build" for value in report["sources"].values()))

    def test_unchanged_snapshot_does_not_rewrite_storage_probe_assets_or_recheck_ci(self):
        with self.temporary() as temporary:
            client = FakeClient(storage()); engine = sync.SyncEngine(client, Path(temporary), now=lambda: 1000); engine.run(execute=True)
            updates, probes, checks = len(client.updates), len(client.probes), len(client.public_checks); client.github_reads.clear()
            report = engine.run(execute=True); self.assertFalse(report["changed"]); self.assertEqual(len(client.updates), updates); self.assertEqual(len(client.probes), probes); self.assertEqual(len(client.public_checks), checks)
            self.assertEqual(len(client.github_reads), 2); self.assertTrue(all(path.endswith("/releases/tags/nightly") for path in client.github_reads))

    def test_manual_concurrent_storage_edit_is_never_overwritten(self):
        with self.temporary() as temporary:
            client = FakeClient(storage())
            def edit(value):
                if value.storage_reads == 2:
                    addition = json.loads(value.current["addition"]); addition["url_structure"] += "manual-owner:\n  preserve:\n"; value.current["addition"] = json.dumps(addition)
            client.before_storage = edit
            with self.assertRaisesRegex(sync.SyncError, "storage-changed"):
                sync.SyncEngine(client, Path(temporary), now=lambda: 1000).run(execute=True)
            self.assertEqual(client.updates, []); self.assertIn("manual-owner", tree_of(client)); self.assertFalse((Path(temporary) / "journal.json").exists())

    def test_public_verification_failure_rolls_back_only_the_original_addition(self):
        with self.temporary() as temporary:
            client = FakeClient(storage()); before = copy.deepcopy(client.current); client.public_error = sync.SyncError("simulated-public-failure")
            with self.assertRaisesRegex(sync.SyncError, "recovery-completed"):
                sync.SyncEngine(client, Path(temporary), now=lambda: 1000).run(execute=True)
            self.assertEqual(json.loads(client.current["addition"]), json.loads(before["addition"])); self.assertEqual(len(client.updates), 2)
            self.assertFalse((Path(temporary) / "journal.json").exists())

    def test_rollback_preserves_an_unrelated_storage_field_changed_after_write(self):
        with self.temporary() as temporary:
            client = FakeClient(storage()); before = tree_of(client)
            def operator_edit(value): value.after_update = None; value.current["remark"] = "operator change after write"
            client.after_update = operator_edit
            with self.assertRaisesRegex(sync.SyncError, "recovery-completed"):
                sync.SyncEngine(client, Path(temporary), now=lambda: 1000).run(execute=True)
            self.assertEqual(tree_of(client), before); self.assertEqual(client.current["remark"], "operator change after write")

    def test_crash_after_storage_update_recovers_before_accepting_another_plan(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); original = tree_of(client)
            def crash(value): value.after_update = None; raise SimulatedCrash()
            client.after_update = crash
            with self.assertRaises(SimulatedCrash): sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertTrue((root / "journal.json").exists())
            for repository in REPOSITORIES: client.releases[repository]["assets"].pop()
            sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertEqual(tree_of(client), original); self.assertFalse((root / "journal.json").exists())

    def test_recovery_refuses_to_erase_a_manual_change_made_after_crash(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage())
            def crash(value): value.after_update = None; raise SimulatedCrash()
            client.after_update = crash
            with self.assertRaises(SimulatedCrash): sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            changed = json.loads(client.current["addition"]); changed["url_structure"] += "manual-owner:\n  preserve:\n"; client.current["addition"] = json.dumps(changed); updates = len(client.updates)
            with self.assertRaisesRegex(sync.SyncError, "manual-change"):
                sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertEqual(len(client.updates), updates); self.assertIn("manual-owner", tree_of(client)); self.assertTrue((root / "journal.json").exists())

    def test_committed_state_with_leftover_journal_is_cleaned_without_storage_rollback(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); original = sync.atomic_json
            def crash_after_commit(path, value):
                original(path, value)
                if path.name == "state.json": raise SimulatedCrash()
            with patch.object(sync, "atomic_json", side_effect=crash_after_commit), self.assertRaises(SimulatedCrash):
                sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            accepted = copy.deepcopy(client.current); updates = len(client.updates)
            self.assertTrue((root / "journal.json").exists()); self.assertTrue((root / "state.json").exists())
            report = sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertFalse(report["changed"]); self.assertEqual(client.current, accepted); self.assertEqual(len(client.updates), updates); self.assertFalse((root / "journal.json").exists())

    def test_backups_remain_bounded_after_repeated_renamed_nightly_assets(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage())
            for number in range(14, 22):
                for repository in REPOSITORIES: client.publish(repository, number)
                sync.SyncEngine(client, root, now=lambda n=number: 1000 + n).run(execute=True)
            self.assertEqual(len(list((root / "backups").glob("storage-5-*.json"))), 5); self.assertEqual(len(client.updates), 8)
            self.assertFalse((root / "journal.json").exists())
            self.assertIn("0.1.0-nightly.21.jar", tree_of(client)); self.assertNotIn("0.1.0-nightly.20.jar", tree_of(client))

    def test_update_reports_failure_after_effective_write_rolls_back_without_advancing_state(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            original_storage = copy.deepcopy(client.current); original_state = (root / "state.json").read_bytes(); updates = len(client.updates)
            for repository in REPOSITORIES: client.publish(repository, 15)
            def lost_response(value): value.after_update = None; raise sync.SyncError("simulated-update-response-failed")
            client.after_update = lost_response
            with self.assertRaisesRegex(sync.SyncError, "recovery-completed"):
                sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertEqual(json.loads(client.current["addition"]), json.loads(original_storage["addition"])); self.assertEqual(len(client.updates), updates + 2)
            self.assertEqual((root / "state.json").read_bytes(), original_state); self.assertFalse((root / "journal.json").exists())

    def test_recovery_blocks_when_local_state_was_changed_to_neither_before_nor_after(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage())
            def crash(value): value.after_update = None; raise SimulatedCrash()
            client.after_update = crash
            with self.assertRaises(SimulatedCrash): sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            manual_state = {"schema": 1, "sources": {}, "operator": "manual state edit"}; (root / "state.json").write_text(json.dumps(manual_state))
            before_state = (root / "state.json").read_bytes(); before_storage = copy.deepcopy(client.current); updates = len(client.updates)
            with self.assertRaisesRegex(sync.SyncError, "recovery-blocked-by-state-change"):
                sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertEqual((root / "state.json").read_bytes(), before_state); self.assertEqual(client.current, before_storage); self.assertEqual(len(client.updates), updates)
            self.assertTrue((root / "journal.json").exists())

    def test_committed_state_cannot_finalize_when_storage_was_manually_restored_to_original(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); original_storage = copy.deepcopy(client.current); original = sync.atomic_json
            def crash_after_commit(path, value):
                original(path, value)
                if path.name == "state.json": raise SimulatedCrash()
            with patch.object(sync, "atomic_json", side_effect=crash_after_commit), self.assertRaises(SimulatedCrash):
                sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            client.current["addition"] = original_storage["addition"]; committed_state = (root / "state.json").read_bytes(); updates = len(client.updates)
            with self.assertRaisesRegex(sync.SyncError, "committed-storage-changed-before-recovery"):
                sync.SyncEngine(client, root, now=lambda: 1001).run(execute=True)
            self.assertEqual(client.current, original_storage); self.assertEqual((root / "state.json").read_bytes(), committed_state); self.assertEqual(len(client.updates), updates)
            self.assertTrue((root / "journal.json").exists())

    def test_global_rate_limit_backoff_is_persisted_and_skips_other_source_and_later_runs(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); normal_github = client.github
            def limited(path): client.github_reads.append(path); raise sync.RateLimited(1300)
            client.github = limited
            report = sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertEqual(report["sources"][REPOSITORIES[0]], "github-rate-limited"); self.assertEqual(report["sources"][REPOSITORIES[1]], "github-backoff")
            self.assertEqual(len(client.github_reads), 1); self.assertEqual(json.loads((root / "state.json").read_text())["githubBackoffUntil"], 1300)
            client.github = normal_github; report = sync.SyncEngine(client, root, now=lambda: 1299).run(execute=True)
            self.assertTrue(all(value == "github-backoff" for value in report["sources"].values())); self.assertEqual(len(client.github_reads), 1); self.assertEqual(client.updates, [])
            report = sync.SyncEngine(client, root, now=lambda: 1300).run(execute=True)
            self.assertEqual(set(report["acceptedSources"]), set(REPOSITORIES)); self.assertGreater(len(client.github_reads), 1); self.assertEqual(len(client.updates), 1)

    def test_operator_state_edit_during_public_readback_is_retained_with_conflict_journal(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); normal_verify = client.verify_public
            manual_state = {"schema": 1, "sources": {}, "operatorNote": "edited while network verification was running"}
            def verify_and_edit(repository, assets):
                normal_verify(repository, assets); (root / "state.json").write_text(json.dumps(manual_state))
            client.verify_public = verify_and_edit
            with self.assertRaisesRegex(sync.SyncError, "state-change"):
                sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertEqual(json.loads((root / "state.json").read_text()), manual_state); self.assertTrue((root / "journal.json").exists())
            self.assertEqual(len(client.updates), 1, "Do not overwrite storage again while local state ownership is ambiguous")
            self.assertNotIn("stale-upstream.jar", tree_of(client)); self.assertNotIn("stale-fork.jar", tree_of(client))

    def test_operator_storage_edit_during_public_readback_is_not_rolled_back_or_committed(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage()); normal_verify = client.verify_public
            def verify_and_edit(repository, assets):
                normal_verify(repository, assets); addition = json.loads(client.current["addition"])
                if "manual-owner:" not in addition["url_structure"]:
                    addition["url_structure"] += "manual-owner:\n  preserve:\n"
                client.current["addition"] = json.dumps(addition)
            client.verify_public = verify_and_edit
            with self.assertRaisesRegex(sync.SyncError, "manual-change"):
                sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertIn("manual-owner:", tree_of(client)); self.assertEqual(len(client.updates), 1)
            self.assertFalse((root / "state.json").exists()); self.assertTrue((root / "journal.json").exists())

    def test_first_unchanged_directory_trust_commit_preserves_operator_state_during_public_readback(self):
        with self.temporary() as temporary:
            root = Path(temporary); tree = TREE
            for repository in REPOSITORIES:
                snapshot = sync.validate_release(repository, release(repository)); tree = sync.replace_nightly(tree, repository, sync.render_assets(snapshot["assets"]))
            client = FakeClient(storage(tree)); normal_verify = client.verify_public; before = copy.deepcopy(client.current)
            manual_state = {"schema": 1, "sources": {}, "operatorNote": "unchanged-path concurrent edit"}
            def verify_and_edit(repository, assets):
                normal_verify(repository, assets); (root / "state.json").write_text(json.dumps(manual_state))
            client.verify_public = verify_and_edit
            with self.assertRaisesRegex(sync.SyncError, "sync-state-changed"):
                sync.SyncEngine(client, root, now=lambda: 1000).run(execute=True)
            self.assertEqual(json.loads((root / "state.json").read_text()), manual_state); self.assertEqual(client.current, before); self.assertEqual(client.updates, [])
            self.assertFalse((root / "journal.json").exists())


    def test_backups_remain_bounded_when_every_public_readback_fails(self):
        with self.temporary() as temporary:
            root = Path(temporary); client = FakeClient(storage())
            client.public_error = sync.SyncError("simulated-public-failure")
            original = copy.deepcopy(client.current)
            for number in range(8):
                with self.assertRaisesRegex(sync.SyncError, "recovery-completed"):
                    sync.SyncEngine(client, root, now=lambda n=number: 1000 + n).run(execute=True)
            self.assertEqual(client.current, original)
            self.assertFalse((root / "journal.json").exists())
            self.assertEqual(len(list((root / "backups").glob("storage-5-*.json"))), 5)


class Response(io.BytesIO):
    def __init__(self, data, status=200, headers=None):
        super().__init__(data); self.status = status; self.headers = headers or {"Content-Type": "application/json"}


class ClientBoundaryTests(unittest.TestCase):
    def test_public_verification_refreshes_admin_path_and_reads_guest_path_without_leaking_signed_queries(self):
        repository = REPOSITORIES[1]; assets = sync.validate_release(repository, release(repository))["assets"]; calls = []
        path = "/folkways/" + repository + "/1.21.1/nightly"; signature = "private-signature/+?="
        class Opener:
            def open(self, request, timeout):
                calls.append(request); url = urllib.parse.urlsplit(request.full_url)
                if request.full_url == sync.LOCAL + "/api/fs/list": return Response(b'{"code":200,"data":{"content":[]}}')
                if request.full_url == sync.PUBLIC + "/api/fs/list":
                    content = [{"name": asset["name"], "size": asset["size"], "is_dir": False} for asset in assets]
                    return Response(json.dumps({"code": 200, "data": {"content": content}}).encode())
                if request.full_url == sync.PUBLIC + "/api/fs/get":
                    body = json.loads(request.data); asset = next(asset for asset in assets if body["path"].endswith("/" + asset["name"]))
                    return Response(json.dumps({"code": 200, "data": {"raw_url": asset["url"], "sign": signature}}).encode())
                if url.path.startswith("/d/Guest" + path + "/"):
                    asset = next(asset for asset in assets if url.path.endswith("/" + asset["name"]))
                    raise urllib.error.HTTPError(request.full_url, 302, "redirect", {"Location": asset["url"]}, None)
                raise AssertionError("Unexpected verification endpoint")
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            sync.Client("LOCAL-ADMIN-SECRET", opener=Opener()).verify_public(repository, assets)
        self.assertEqual(len(calls), 12); self.assertEqual(calls[0].get_header("Authorization"), "LOCAL-ADMIN-SECRET")
        self.assertEqual(json.loads(calls[0].data)["path"], "/Guest" + path); self.assertIs(json.loads(calls[0].data)["refresh"], True)
        self.assertEqual(json.loads(calls[1].data)["path"], path); self.assertIs(json.loads(calls[1].data)["refresh"], False)
        signed = [request for request in calls if urllib.parse.urlsplit(request.full_url).path.startswith("/d/")]; self.assertEqual(len(signed), 5)
        for request in calls[1:]:
            self.assertIsNone(request.get_header("Authorization")); self.assertIsNone(request.get_header("Cookie")); self.assertNotIn("LOCAL-ADMIN-SECRET", request.full_url)
        for request in signed:
            url = urllib.parse.urlsplit(request.full_url); self.assertTrue(url.path.startswith("/d/Guest" + path + "/")); self.assertEqual(urllib.parse.parse_qs(url.query), {"sign": [signature]})
        self.assertEqual(output.getvalue(), ""); self.assertEqual(errors.getvalue(), "")

    def test_github_rate_limit_uses_a_bounded_retry_time_without_exposing_response_body(self):
        class Opener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError(request.full_url, 429, "private body must not appear", {"Retry-After": "120"}, None)
        with self.assertRaises(sync.RateLimited) as failure:
            sync.Client("LOCAL-SECRET", opener=Opener(), now=lambda: 1000).github("/repos/IzakyL/folkways/releases/tags/nightly")
        self.assertEqual(failure.exception.until, 1120); self.assertEqual(str(failure.exception), "github-rate-limited")

    def test_local_token_is_never_sent_to_github_or_mirror_requests(self):
        calls = []
        class Opener:
            def open(self, request, timeout):
                calls.append(request)
                if request.full_url.startswith(sync.LOCAL): return Response(json.dumps({"code": 200, "data": storage()}).encode())
                if request.full_url.startswith(sync.MIRROR): return Response(b"PK\x03\x04", 206, {"Content-Range": "bytes 0-3/20"})
                return Response(b'{"example":true}')
        client = sync.Client("SECRET-LOCAL-TOKEN", opener=Opener()); client.storage(); client.github("/repos/IzakyL/folkways/releases/tags/nightly")
        client.probe({"url": sync.MIRROR + "IzakyL/folkways/releases/download/nightly/test.jar", "size": 20})
        self.assertEqual(calls[0].get_header("Authorization"), "SECRET-LOCAL-TOKEN")
        for request in calls[1:]:
            self.assertIsNone(request.get_header("Authorization")); self.assertIsNone(request.get_header("Cookie")); self.assertNotIn("SECRET-LOCAL-TOKEN", repr(request.header_items()))
        self.assertEqual(calls[2].get_header("Range"), "bytes=0-3")
        with self.assertRaises(sync.SyncError): client._json("https://evil.invalid/api/", local=True)
        self.assertIsNone(sync.NoRedirect().redirect_request(None, None, None, None, None, None))

    def test_html_or_wrong_binary_range_is_not_accepted_as_a_release_probe(self):
        for response in (Response(b"PK\x03\x04", 200), Response(b"<htm", 206, {"Content-Range": "bytes 0-3/20"}), Response(b"PK\x03\x04", 206, {"Content-Range": "bytes 0-3/21"})):
            opener = type("Opener", (), {"open": lambda self, request, timeout, r=response: r})()
            with self.subTest(response=response), self.assertRaises(sync.SyncError):
                sync.Client("LOCAL", opener=opener).probe({"url": sync.MIRROR + "IzakyL/folkways/releases/download/nightly/a.jar", "size": 20})


if __name__ == "__main__":
    unittest.main()
