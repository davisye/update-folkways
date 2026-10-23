import io
import json
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import dropin_smoke as smoke
import self_release as release


class DropInSmokeFixtureTests(unittest.TestCase):
    def metadata(self, channel='nightly'):
        return {'schema': 1, 'repository': release.REPOSITORY, 'minecraft': '1.21.1',
                'version': '1.2.3', 'commit': 'a' * 40, 'runId': '123', 'runNumber': 9,
                'runAttempt': 1, 'sequence': 9001, 'channel': channel}

    @staticmethod
    def jar(identity, component):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as z:
            z.writestr(release.BUILD_INFO, release.canonical(dict(identity, component=component)))
            z.writestr('dev/davisye/Bytecode.class', b'\xca\xfe\xba\xbe\0\0\0Aactual fixture bytecode')
            if component == 'bootstrap':
                z.writestr('META-INF/MANIFEST.MF', 'Main-Class: dev.davisye.folkways.update.core.Bootstrap\r\n')
                z.writestr('com/google/gson/Gson.class', b'gson fixture')
                z.writestr(release.HELPER_CLASS, b'\xca\xfe\xba\xbe\0\0\0Ahelper fixture bytecode')
            else:
                z.writestr('META-INF/neoforge.mods.toml',
                           '[[mods]]\nmodId="folkways_updater"\nversion="' + identity['version'] + '"\n')
                z.writestr(release.HELPER, DropInSmokeFixtureTests.jar(identity, 'bootstrap'))
        return stream.getvalue()

    def test_previous_identity_moves_back_one_run_or_attempt_without_editing_input(self):
        current = self.metadata()
        before = smoke.previous_identity(current)
        self.assertEqual((before['runNumber'], before['runAttempt'], before['sequence']), (8, 1, 8001))
        current.update(runAttempt=4, sequence=9004)
        retry = smoke.previous_identity(current)
        self.assertEqual((retry['runNumber'], retry['runAttempt'], retry['sequence']), (9, 3, 9003))
        self.assertEqual(current['version'], '1.2.3')
        self.assertEqual(current['runAttempt'], 4)

    def test_first_sequence_cannot_fabricate_an_older_published_baseline(self):
        current = self.metadata()
        current.update(runNumber=1, sequence=1001)
        with self.assertRaisesRegex(ValueError, 'first workflow run'):
            smoke.previous_identity(current)

    def test_rebase_preserves_real_class_bytes_and_binds_both_jar_identities(self):
        current = self.metadata()
        original = self.jar(current, 'companion')
        before = smoke.previous_identity(current)
        changed = smoke.rewrite_identity(original, before, 'companion')
        self.assertNotEqual(original, changed)
        with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(io.BytesIO(changed)) as new:
            release.verify_archive(new, 'companion', before)
            self.assertEqual(new.read('dev/davisye/Bytecode.class'), old.read('dev/davisye/Bytecode.class'))
            self.assertEqual(json.loads(old.read(release.BUILD_INFO))['version'], '1.2.3')
            with zipfile.ZipFile(io.BytesIO(new.read(release.HELPER))) as helper:
                self.assertEqual(json.loads(helper.read(release.BUILD_INFO))['sequence'], 8001)
                self.assertEqual(helper.read(release.HELPER_CLASS), b'\xca\xfe\xba\xbe\0\0\0Ahelper fixture bytecode')

    def test_queued_proof_binds_nightly_and_stable_to_the_complete_ci_identity(self):
        for channel, tag, branch in [('nightly', 'nightly', 'main'), ('release', 'v1.2.3', 'v1.2.3')]:
            with self.subTest(channel=channel):
                current = self.metadata(channel)
                name = 'folkways-updater-neoforge-1.21.1-1.2.3.jar'
                artifact = {'fileName': name, 'size': 456, 'sha256': 'b' * 64,
                            'url': f'https://github.com/{release.REPOSITORY}/releases/download/{tag}/{name}'}
                proof = smoke.queued_proof(current, artifact)
                self.assertEqual(proof['release']['assets'], [proof['asset']])
                self.assertEqual(proof['asset']['digest'], 'sha256:' + artifact['sha256'])
                self.assertEqual(proof['build'], dict(current, component='companion'))
                self.assertEqual(proof['run']['head_sha'], current['commit'])
                self.assertEqual(proof['run']['run_number'] * 1000 + proof['run']['run_attempt'], current['sequence'])
                self.assertEqual(proof['run']['head_branch'], branch)
                self.assertEqual(proof['run']['path'], release.WORKFLOW)


if __name__ == '__main__':
    unittest.main()
