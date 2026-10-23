import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
import zipfile

SPEC = importlib.util.spec_from_file_location('self_release', Path(__file__).parents[2] / 'tools/self_release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
CLASS = b'\xca\xfe\xba\xbe\x00\x00\x00\x41'


class FakeAPI:
    def __init__(self, metadata, existing=None, branch=None, compare='identical', bad_digest=False, pages=None):
        self.m, self.existing = metadata, existing
        self.branch = branch or metadata['commit']
        self.compare, self.bad_digest = compare, bad_digest
        self.pages = pages or {}
        self.assets = [dict(a) for a in (existing or {}).get('assets', [])]
        self.writes, self.uploads, self.final, self.created = [], [], None, False

    def api(self, path, method='GET', data=None, binary=None):
        if method != 'GET':
            self.writes.append((method, path))
        if path.startswith('actions/runs/'):
            return {'event': 'push', 'head_sha': self.m['commit'], 'path': release.WORKFLOW,
                    'run_number': self.m['runNumber'], 'repository': {'full_name': release.REPOSITORY},
                    'head_repository': {'full_name': release.REPOSITORY}}
        if path == 'branches/main':
            return {'commit': {'sha': self.branch}}
        if path.startswith('compare/'):
            return {'status': self.compare}
        if path.startswith('releases/tags/'):
            return self.existing if self.existing and not self.existing.get('draft') else None
        if path.startswith('releases?per_page=100&page='):
            page = int(path.rsplit('=', 1)[1])
            return self.pages.get(page, [self.existing] if page == 1 and self.existing else [])
        if path == 'git/ref/tags/nightly':
            return {'ref': 'refs/tags/nightly'} if self.existing else None
        if path in {'git/refs', 'git/refs/tags/nightly'}:
            return data
        if path == 'releases' and method == 'POST':
            self.created = True
            return {'id': 1}
        if path == 'releases/1/assets?per_page=100':
            return list(self.assets)
        if path.startswith('releases/assets/') and method == 'DELETE':
            asset_id = int(path.rsplit('/', 1)[1])
            self.assets = [a for a in self.assets if a['id'] != asset_id]
            return None
        if path.startswith('releases/1/assets?name=') and method == 'POST':
            name = parse_qs(urlsplit(path).query)['name'][0]
            asset = {'id': max((a['id'] for a in self.assets), default=0) + 1, 'name': name, 'size': len(binary),
                     'digest': 'sha256:' + ('0' * 64 if self.bad_digest else release.hashlib.sha256(binary).hexdigest())}
            self.assets.append(asset)
            self.uploads.append(name)
            return asset
        if path == 'releases/1' and method == 'PATCH':
            self.final = data
            return data
        raise AssertionError((method, path))


class SelfReleaseTests(unittest.TestCase):
    def setUp(self):
        scratch = Path.cwd() / 'work/release-test-tmp'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self, tag=False, helper_changes=None, with_helper=True, with_gson=True, helper_class=CLASS):
        context = release.release_context('v1.2.3' if tag else 'main', 'tag' if tag else 'branch', '123', '9', '1')
        m = {'schema': 1, 'repository': release.REPOSITORY, 'minecraft': '1.21.1', 'version': context['version'],
             'channel': context['channel'], 'commit': 'a' * 40, 'runId': '123', 'runNumber': 9, 'runAttempt': 1, 'sequence': 9001}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as helper:
            helper.writestr(release.BUILD_INFO, json.dumps({**m, 'component': 'bootstrap', **(helper_changes or {})}))
            helper.writestr('META-INF/MANIFEST.MF', 'Main-Class: dev.davisye.folkways.update.core.Bootstrap\r\n')
            helper.writestr(release.HELPER_CLASS, helper_class)
            if with_gson:
                helper.writestr('com/google/gson/Gson.class', CLASS)
        path = self.root / 'companion/build/libs' / f"folkways-updater-neoforge-1.21.1-{m['version']}.jar"
        path.parent.mkdir(parents=True)
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr(release.BUILD_INFO, json.dumps({**m, 'component': 'companion'}))
            z.writestr('dev/davisye/Companion.class', CLASS)
            z.writestr('META-INF/neoforge.mods.toml', f'[[mods]]\nmodId="folkways_updater"\nversion="{m["version"]}"\n')
            if with_helper:
                z.writestr(release.HELPER, buffer.getvalue())
        out = self.root / 'dist'
        result = release.prepare(self.root, out, context, 'a' * 40, '123', '1')
        return out, result

    def environment(self, m):
        return {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REPOSITORY': release.REPOSITORY,
                'GITHUB_REF': 'refs/heads/main' if m['channel'] == 'nightly' else 'refs/tags/v' + m['version'],
                'GITHUB_SHA': m['commit'], 'GITHUB_RUN_ID': m['runId'], 'GITHUB_RUN_ATTEMPT': str(m['runAttempt']),
                'GITHUB_RUN_NUMBER': str(m['runNumber'])}

    def test_version_and_monotonic_id(self):
        first = release.release_context('main', 'branch', '123', '9', '1')
        retry = release.release_context('main', 'branch', '123', '9', '2')
        self.assertEqual(first['version'], '0.1.0-nightly.9.1')
        self.assertGreater(retry['sequence'], first['sequence'])
        self.assertEqual(release.release_context('v1.2.3', 'tag', '124', '10', '1')['version'], '1.2.3')
        for bad in ('vbad;code', 'v1.2'):
            with self.assertRaises(ValueError):
                release.release_context(bad, 'tag', '124', '10', '1')

    def test_prepare_one_public_jar_plus_internal_manifest(self):
        out, m = self.fixture()
        self.assertEqual({p.name for p in out.iterdir()}, {release.METADATA, m['artifacts'][0]['fileName']})
        self.assertEqual(release.verify_directory(out), m)
        self.assertEqual([a['component'] for a in m['artifacts']], ['companion'])
        with zipfile.ZipFile(out / m['artifacts'][0]['fileName']) as outer:
            with zipfile.ZipFile(io.BytesIO(outer.read(release.HELPER))) as helper:
                self.assertEqual(json.loads(helper.read(release.BUILD_INFO))['component'], 'bootstrap')
                self.assertIn('com/google/gson/Gson.class', helper.namelist())

    def test_missing_embedded_helper_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'embedded helper'):
            self.fixture(with_helper=False)

    def test_helper_build_must_match_outer_jar(self):
        with self.assertRaisesRegex(ValueError, 'provenance differs'):
            self.fixture(helper_changes={'commit': 'b' * 40})

    def test_helper_must_bundle_gson(self):
        with self.assertRaisesRegex(ValueError, 'include Gson'):
            self.fixture(with_gson=False)

    def test_helper_own_classes_must_target_java21(self):
        with self.assertRaisesRegex(ValueError, 'Java 21'):
            self.fixture(helper_class=CLASS[:6] + b'\x00\x45')

    def test_changed_jar_is_rejected(self):
        out, m = self.fixture()
        (out / m['artifacts'][0]['fileName']).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash/size'):
            release.verify_directory(out)

    def test_dist_cannot_include_old_public_extra_files(self):
        out, _ = self.fixture()
        (out / 'launch.ps1').write_text('old seed')
        with self.assertRaisesRegex(ValueError, 'file set'):
            release.verify_directory(out)

    def test_wrong_source_url_is_rejected(self):
        _, m = self.fixture()
        m['artifacts'][0]['url'] = 'https://evil.example/companion.jar'
        with self.assertRaisesRegex(ValueError, 'canonical'):
            release.validate_metadata(m)

    def test_wrong_workflow_is_rejected(self):
        _, m = self.fixture()
        m['workflow'] = '.github/workflows/untrusted.yml'
        with self.assertRaisesRegex(ValueError, 'workflow'):
            release.validate_metadata(m)

    def test_artifact_over_client_limit_is_rejected(self):
        _, m = self.fixture()
        m['artifacts'][0]['size'] = release.MAX_JAR + 1
        with self.assertRaisesRegex(ValueError, 'size'):
            release.validate_metadata(m)

    def test_public_companion_read_uses_official_digest_and_embedded_identity(self):
        out, m = self.fixture()
        a = m['artifacts'][0]
        payload = (out / a['fileName']).read_bytes()
        asset = {'name': a['fileName'], 'size': a['size'], 'digest': 'sha256:' + a['sha256'], 'browser_download_url': a['url']}
        with patch.object(release.urllib.request, 'urlopen', return_value=io.BytesIO(payload)):
            info = release.read_public_companion(asset)
        self.assertEqual(info['sequence'], m['sequence'])
        self.assertEqual(info['component'], 'companion')

    def test_public_companion_digest_mismatch_is_rejected(self):
        out, m = self.fixture()
        a = m['artifacts'][0]
        asset = {'name': a['fileName'], 'size': a['size'], 'digest': 'sha256:' + '0' * 64, 'browser_download_url': a['url']}
        with patch.object(release.urllib.request, 'urlopen', return_value=io.BytesIO((out / a['fileName']).read_bytes())):
            with self.assertRaisesRegex(ValueError, 'digest/size'):
                release.read_public_companion(asset)

    def test_public_companion_oversize_fails_before_network(self):
        _, m = self.fixture()
        a = m['artifacts'][0]
        with patch.object(release.urllib.request, 'urlopen', side_effect=AssertionError('Unbounded download')):
            with self.assertRaisesRegex(ValueError, 'bounded official'):
                release.read_public_companion({'name': a['fileName'], 'size': release.MAX_JAR + 1, 'digest': 'sha256:' + a['sha256']})

    def test_pr_cannot_publish_before_any_network_call(self):
        out, _ = self.fixture()
        class Never:
            def api(self, *args, **kwargs):
                raise AssertionError('PR publication reached network')
        with self.assertRaisesRegex(ValueError, 'trusted'):
            release.publish(out, {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_REPOSITORY': release.REPOSITORY}, Never())

    def test_stable_tag_outside_main_is_rejected_before_writes(self):
        out, m = self.fixture(tag=True)
        api = FakeAPI(m, compare='diverged')
        with self.assertRaisesRegex(ValueError, 'main history'):
            release.publish(out, self.environment(m), api)
        self.assertEqual(api.writes, [])

    def test_nightly_publishes_only_one_mod_jar(self):
        out, m = self.fixture()
        api = FakeAPI(m)
        result = release.publish(out, self.environment(m), api)
        self.assertTrue(result['published'])
        self.assertEqual(api.uploads, [m['artifacts'][0]['fileName']])
        self.assertNotIn(release.METADATA, api.uploads)
        self.assertFalse(api.final['draft'])

    def test_old_main_run_cannot_write_nightly(self):
        out, m = self.fixture()
        api = FakeAPI(m, branch='b' * 40)
        result = release.publish(out, self.environment(m), api)
        self.assertFalse(result['published'])
        self.assertEqual(api.writes, [])

    def test_uploaded_digest_failure_keeps_draft_and_old_assets(self):
        out, m = self.fixture()
        api = FakeAPI(m, bad_digest=True)
        api.assets = [{'id': 20, 'name': 'old-launch.sh'}]
        with patch.object(release.time, 'sleep'), self.assertRaisesRegex(ValueError, 'digest verification'):
            release.publish(out, self.environment(m), api)
        self.assertIsNone(api.final)
        self.assertIn('old-launch.sh', [a['name'] for a in api.assets])

    def test_newer_nightly_buildinfo_cannot_be_overwritten(self):
        out, m = self.fixture()
        old = {**m, 'runId': '124', 'runNumber': 10, 'sequence': 10001}
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': [{'name': 'folkways-updater-neoforge-1.21.1-old.jar'}]})
        with patch.object(release, 'read_public_companion', return_value=old):
            result = release.publish(out, self.environment(m), api)
        self.assertFalse(result['published'])
        self.assertEqual(api.writes, [])

    def test_nightly_migration_cleans_old_six_assets_only_after_digest_pass(self):
        out, m = self.fixture()
        old_assets = [{'id': n + 1, 'name': name} for n, name in enumerate((
            'folkways-updater-neoforge-1.21.1-0.1.0-nightly.8.1.jar', 'bootstrap-old.jar', 'launch.ps1',
            'launch.sh', release.METADATA, 'checksums.sha256'))]
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': old_assets})
        with patch.object(release, 'read_public_companion', return_value={**m, 'sequence': 8001}):
            release.publish(out, self.environment(m), api)
        self.assertEqual([a['name'] for a in api.assets], [m['artifacts'][0]['fileName']])
        self.assertEqual(len([w for w in api.writes if w[0] == 'DELETE']), 6)

    def test_nightly_ambiguous_companion_is_rejected(self):
        out, m = self.fixture()
        assets = [{'name': f'folkways-updater-neoforge-1.21.1-{n}.jar'} for n in (1, 2)]
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': assets})
        with self.assertRaisesRegex(ValueError, 'unique published companion'):
            release.publish(out, self.environment(m), api)
        self.assertEqual(api.writes, [])

    def test_interrupted_nightly_reuses_only_exact_current_candidate(self):
        out, m = self.fixture()
        current = m['artifacts'][0]
        assets = [{'id': 1, 'name': 'folkways-updater-neoforge-1.21.1-0.1.0-nightly.8.1.jar'},
                  {'id': 2, 'name': current['fileName'], 'size': current['size'], 'digest': 'sha256:' + current['sha256']},
                  {'id': 3, 'name': 'launch.ps1'}]
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': assets})
        with patch.object(release, 'read_public_companion', return_value={**m, 'sequence': 8001}) as read:
            result = release.publish(out, self.environment(m), api)
        self.assertTrue(result['published'])
        read.assert_called_once_with(assets[0])
        self.assertEqual([a['name'] for a in api.assets], [current['fileName']])

    def test_partial_current_candidate_with_wrong_digest_is_not_ignored(self):
        out, m = self.fixture()
        current = m['artifacts'][0]
        assets = [{'id': 1, 'name': 'folkways-updater-neoforge-1.21.1-old.jar'},
                  {'id': 2, 'name': current['fileName'], 'size': current['size'], 'digest': 'sha256:' + '0' * 64}]
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': assets})
        with self.assertRaisesRegex(ValueError, 'unique published companion'):
            release.publish(out, self.environment(m), api)
        self.assertEqual(api.writes, [])

    def test_completed_nightly_is_idempotent_without_writes(self):
        out, m = self.fixture()
        current = m['artifacts'][0]
        asset = {'id': 1, 'name': current['fileName'], 'size': current['size'], 'digest': 'sha256:' + current['sha256']}
        api = FakeAPI(m, {'id': 1, 'draft': False, 'assets': [asset]})
        result = release.publish(out, self.environment(m), api)
        self.assertFalse(result['published'])
        self.assertEqual(api.writes, [])

    def test_failed_draft_recovery_uses_list_and_publishes_one_jar(self):
        out, m = self.fixture()
        draft = {'id': 1, 'tag_name': 'nightly', 'draft': True, 'target_commitish': m['commit'], 'assets': []}
        api = FakeAPI(m, draft)
        with patch.object(release, 'read_public_companion', side_effect=AssertionError('Private draft read attempted')):
            result = release.publish(out, self.environment(m), api)
        self.assertTrue(result['published'])
        self.assertFalse(api.created)
        self.assertEqual(len(api.uploads), 1)

    def test_draft_recovery_enumerates_all_pages(self):
        _, m = self.fixture()
        draft = {'id': 1, 'tag_name': 'nightly', 'draft': True, 'target_commitish': m['commit']}
        api = FakeAPI(m, pages={1: [{'tag_name': 'other'}] * 100, 2: [draft]})
        self.assertEqual(release.find_release(api, 'nightly', m['commit']), draft)

    def test_draft_recovery_rejects_duplicate_tag_across_pages(self):
        _, m = self.fixture()
        draft = {'id': 1, 'tag_name': 'nightly', 'draft': True, 'target_commitish': m['commit']}
        api = FakeAPI(m, pages={1: [draft] + [{'tag_name': 'other'}] * 99, 2: [{**draft, 'id': 2}]})
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            release.find_release(api, 'nightly', m['commit'])
        self.assertEqual(api.writes, [])

    def test_draft_recovery_rejects_different_commit(self):
        _, m = self.fixture()
        draft = {'id': 1, 'tag_name': 'nightly', 'draft': True, 'target_commitish': 'b' * 40}
        api = FakeAPI(m, draft)
        with self.assertRaisesRegex(ValueError, 'exact build commit'):
            release.find_release(api, 'nightly', m['commit'])
        self.assertEqual(api.writes, [])

    def test_published_stable_tag_is_immutable(self):
        out, m = self.fixture(tag=True)
        api = FakeAPI(m, {'id': 1, 'draft': False, 'target_commitish': m['commit']})
        with self.assertRaisesRegex(FileExistsError, 'immutable'):
            release.publish(out, self.environment(m), api)
        self.assertEqual(api.writes, [])


if __name__ == '__main__':
    unittest.main()
