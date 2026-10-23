#!/usr/bin/env python3
"""Exercise the released Mod's embedded helper with isolated, offline JVMs."""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import zipfile

from self_release import HELPER, digest, verify_directory

ENTRYPOINT = 'dev.davisye.folkways.update.core.DropInUpdater$Helper'


def wait_process_exit(pid, timeout=20):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE, no mutation rights.
        if not handle:
            if ctypes.get_last_error() == 87:  # PID has already disappeared.
                return
            raise OSError(ctypes.get_last_error(), 'Cannot inspect smoke helper termination')
        try:
            if kernel.WaitForSingleObject(handle, int(timeout * 1000)) != 0:
                raise TimeoutError('Post-exit helper did not terminate')
        finally:
            kernel.CloseHandle(handle)
        return
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        stat = Path('/proc') / str(pid) / 'stat'
        if Path('/proc').is_dir():
            if not stat.exists():
                return
            try:
                if stat.read_text().rsplit(')', 1)[1].split()[0] == 'Z':
                    return
            except FileNotFoundError:
                return
        else:
            try:
                os.kill(pid, 0)  # POSIX existence query; never used on Windows.
            except ProcessLookupError:
                return
        time.sleep(0.05)
    raise TimeoutError('Post-exit helper did not terminate')


def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError('Missing or unsafe smoke result: ' + str(path))
    return json.loads(path.read_text(encoding='utf-8'))


def probe(java, helper, game, companion, environment):
    encoded_game = base64.urlsafe_b64encode(str(game).encode('utf-8')).decode().rstrip('=')
    encoded_companion = base64.urlsafe_b64encode(str(companion).encode('utf-8')).decode().rstrip('=')
    command = [java, '-cp', helper.name, ENTRYPOINT, '--probe-b64', encoded_game, encoded_companion]
    process = subprocess.Popen(command, cwd=helper.parent, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding='utf-8', errors='replace')
    try:
        output, errors = process.communicate(timeout=60)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise TimeoutError('Drop-in parent probe did not exit') from None
    if process.returncode != 0:
        raise RuntimeError(f'Drop-in probe exited {process.returncode}:\n{output}\n{errors}')
    report = json.loads(output)
    if not isinstance(report, dict):
        raise ValueError('Parent probe did not report one helper handshake')
    if (report.get('ready') is not True or report.get('parentPid') != process.pid
            or type(report.get('helperPid')) is not int or report['helperPid'] <= 0
            or report['helperPid'] == process.pid or not report.get('helperStart')):
        raise ValueError('Independent helper handshake identity was not established')
    relative = Path(report['runtime'])
    root = (game / '.folkways-updater/dropin-runtime').resolve(strict=True)
    runtime = (game / relative).resolve(strict=True)
    if relative.is_absolute() or runtime.parent != root or not runtime.name.startswith('helper-'):
        raise ValueError('Helper runtime escaped the isolated fixture')
    owner = read_json(runtime / 'owner.json')
    intent = read_json(runtime / 'intent.json')
    if (owner['pid'] != report['helperPid'] or owner['start'] != report['helperStart']
            or owner['parentPid'] != process.pid or owner['parentStart'] != intent['parentStart']
            or owner['parentStart'] != report.get('parentStart')
            or owner['token'] != intent['token']):
        raise ValueError('Persisted helper identity differs from the live handshake')
    result_path = runtime / 'result.json'
    deadline = time.monotonic() + 30
    while not result_path.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    result = read_json(result_path)
    if (result.get('success') is not True or result.get('parentPid') != process.pid
            or result.get('helperPid') != report['helperPid'] or result.get('parentStart') != owner['parentStart']
            or type(result.get('finishedMillis')) is not int or result['finishedMillis'] <= 0):
        raise ValueError('Post-exit helper did not record successful offline completion: ' + str(result))
    wait_process_exit(report['helperPid'])
    return runtime


def smoke(directory, java='java', scratch=None):
    directory = Path(directory).resolve(strict=True)
    metadata = verify_directory(directory)
    executable = shutil.which(java)
    if executable is None:
        raise ValueError('Java executable was not found')
    executable = str(Path(executable).resolve(strict=True))
    scratch = Path(scratch or Path.cwd() / 'work/dropin-smoke-tmp').resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    base = Path(tempfile.mkdtemp(prefix='dropin smoke-', dir=scratch)).resolve(strict=True)
    if base.parent != scratch:
        raise ValueError('Smoke fixture escaped its scratch directory')
    try:
        game = base / 'instance with spaces-中文'
        mods = game / 'mods'
        mods.mkdir(parents=True)
        artifact = metadata['artifacts'][0]
        companion = mods / artifact['fileName']
        shutil.copy2(directory / companion.name, companion)
        helper = base / 'embedded helper.jar'
        with zipfile.ZipFile(companion) as z:
            helper.write_bytes(z.read(HELPER))
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith('FOLKWAYS_') and key.upper() not in
                       {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'}}
        first = probe(executable, helper, game, companion, environment)
        home = game / '.folkways-updater'
        config = read_json(home / 'config.json')
        if config.get('localDevelopment') is not False or config.get('selfUpdateEnabled') is not True:
            raise ValueError('Published drop-in default did not enable managed self-updates')
        state = read_json(home / 'dropin-state.json')
        owned = state['owned']
        identity = ('version', 'commit', 'runId', 'runNumber', 'runAttempt', 'sequence', 'channel', 'minecraft')
        if (state.get('localBuild') is not False or state['highest'] != metadata['sequence']
                or any(state['build'].get(key) != metadata[key] for key in identity)
                or game / owned['path'] != companion or owned['sha256'] != artifact['sha256']
                or owned['size'] != artifact['size']):
            raise ValueError('First startup did not automatically adopt the published Mod build')
        second = probe(executable, helper, game, companion, environment)
        if first.exists() or first == second:
            raise ValueError('Second startup did not clean the proven-dead first helper')
        remaining = list((home / 'dropin-runtime').iterdir())
        if remaining != [second]:
            raise ValueError('Helper runtime grew beyond the last completed generation')
        if digest(companion) != artifact['sha256'] or companion.stat().st_size != artifact['size']:
            raise ValueError('Offline no-update probe changed the installed Mod JAR')
        for name in ('dropin-journal.json', 'journal.json', 'self-journal.json', 'child.json'):
            if (home / name).exists():
                raise ValueError('Unexpected launch/update residue: ' + name)
        verify_directory(directory)
        result = {'passed': True, 'platform': os.name, 'version': metadata['version'],
                  'sequence': metadata['sequence'], 'publicJars': 1, 'embeddedHelper': True,
                  'localDevelopment': False, 'selfUpdateEnabled': True, 'postExitHelperRuns': 2,
                  'automaticallyAdopted': True,
                  'jarHashUnchanged': True, 'previousHelperCleaned': True, 'networkRequired': False}
    except Exception as error:
        raise RuntimeError(f'Smoke fixture retained for diagnosis: {base}\n{error}') from error
    # Only this newly generated, canonical child directory is recursively removed.
    if base.parent != scratch:
        raise ValueError('Refusing cleanup outside the smoke scratch directory')
    shutil.rmtree(base)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('dist'))
    parser.add_argument('--java', default='java')
    parser.add_argument('--scratch', type=Path)
    args = parser.parse_args()
    print(json.dumps(smoke(args.directory, args.java, args.scratch), indent=2))


if __name__ == '__main__':
    main()
