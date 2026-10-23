import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import dropin_smoke as smoke
import self_release as release


class FakeClock:
    def __init__(self, wall_ms, advance_wall=True):
        self.wall_ms = wall_ms
        self.elapsed = 0
        self.advance_wall = advance_wall
        self.sleeps = []

    def sleep(self, duration):
        self.sleeps.append(duration)
        self.elapsed += duration
        if self.advance_wall:
            self.wall_ms += round(duration * 1000)

    def monotonic(self):
        return self.elapsed

    def time_ns(self):
        return self.wall_ms * 1_000_000


class NextJvmBoundaryTests(unittest.TestCase):
    def wait(self, clock, marker=None, timeout=5):
        marker = marker if marker is not None else {'finishedMillis': 1_000_000, 'clockAnomaly': False}
        with patch.object(smoke, 'read_json', return_value=marker), \
                patch.object(smoke.time, 'monotonic', side_effect=clock.monotonic), \
                patch.object(smoke.time, 'time_ns', side_effect=clock.time_ns), \
                patch.object(smoke.time, 'sleep', side_effect=clock.sleep):
            return smoke.wait_for_new_jvm(Path('fixture'), timeout)

    def test_waits_past_actual_commit_and_process_precision_before_the_next_start(self):
        clock = FakeClock(1_000_010)
        result = self.wait(clock)
        self.assertGreater(clock.wall_ms, 1_000_000 + smoke.PROCESS_START_MARGIN_MS)
        # Even a start reported one full second earlier remains after commit.
        self.assertGreater(clock.wall_ms - 1000, result['finishedMillis'])
        self.assertGreaterEqual(result['waitedMillis'], 1090)
        self.assertTrue(clock.sleeps)
        self.assertTrue(all(0 < delay <= 0.05 for delay in clock.sleeps))

    def test_exact_boundary_still_waits_but_an_old_install_does_not(self):
        exact = FakeClock(1_000_000 + smoke.PROCESS_START_MARGIN_MS)
        self.wait(exact)
        self.assertTrue(exact.sleeps)
        old = FakeClock(1_002_000)
        self.assertEqual(self.wait(old)['waitedMillis'], 0)
        self.assertEqual(old.sleeps, [])

    def test_stalled_wall_clock_has_a_bounded_monotonic_deadline(self):
        clock = FakeClock(1_000_010, advance_wall=False)
        with self.assertRaisesRegex(TimeoutError, 'clock boundary'):
            self.wait(clock, timeout=0.2)
        self.assertAlmostEqual(clock.elapsed, 0.2)
        self.assertLessEqual(len(clock.sleeps), 5)

    def test_invalid_future_or_anomalous_marker_fails_without_sleep_or_probe_retry(self):
        for marker in ({'finishedMillis': True, 'clockAnomaly': False},
                       {'finishedMillis': 0, 'clockAnomaly': False},
                       {'finishedMillis': 1_000_000, 'clockAnomaly': True},
                       {'finishedMillis': 1_000_000},
                       {'finishedMillis': 1_000_001, 'clockAnomaly': False}, None):
            with self.subTest(marker=marker):
                clock = FakeClock(1_000_000)
                with patch.object(smoke, 'read_json', return_value=marker), \
                        patch.object(smoke.time, 'sleep', side_effect=clock.sleep), \
                        patch.object(smoke.time, 'time_ns', side_effect=clock.time_ns):
                    with self.assertRaises(ValueError):
                        smoke.wait_for_new_jvm(Path('fixture'))
                self.assertEqual(clock.sleeps, [])

    def test_wall_clock_rollback_is_not_hidden_by_waiting(self):
        with patch.object(smoke, 'read_json', return_value={'finishedMillis': 1_000_000, 'clockAnomaly': False}), \
                patch.object(smoke.time, 'time_ns', side_effect=[1_000_010_000_000, 1_000_009_000_000]), \
                patch.object(smoke.time, 'sleep') as sleeper:
            with self.assertRaisesRegex(ValueError, 'backwards'):
                smoke.wait_for_new_jvm(Path('fixture'))
            sleeper.assert_not_called()


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
