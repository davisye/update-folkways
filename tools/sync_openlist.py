#!/usr/bin/env python3
"""Synchronize two verified nightly link sets into one existing OpenList UrlTree.

Only small official JSON responses and four bytes per changed asset are read.
The default is a dry run. Credentials are sent exclusively to the fixed loopback API.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

REPOSITORIES = ("IzakyL/folkways", "davisye/folkways")
MODULES = {"core", "vanilla", "create", "modulargolems", "cookery"}
API = "https://api.github.com"
MIRROR = "https://github.davisye.cn/_github/github.com/"
LOCAL = "http://127.0.0.1:5244"
PUBLIC = "https://pan.davisye.cn"
STORAGE_ID = 5
MOUNT = "/Guest/folkways"
MAX_JSON = 4 * 1024 * 1024
ASSET = re.compile(r"folkways-(core|vanilla|create|modulargolems|cookery)-neoforge-1\.21\.1-([A-Za-z0-9._+\-]+)\.jar")


class SyncError(Exception):
    """Messages are fixed codes, never raw HTTP bodies, URLs or credentials."""


class RateLimited(SyncError):
    def __init__(self, until):
        super().__init__("github-rate-limited")
        self.until = until


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def integer(value, minimum=1):
    return type(value) is int and minimum <= value < 2**63


def validate_release(repository, release):
    if repository not in REPOSITORIES or not isinstance(release, dict):
        raise SyncError("invalid-release")
    if (not integer(release.get("id")) or release.get("tag_name") != "nightly" or release.get("draft") is not False
            or not re.fullmatch(r"[0-9a-f]{40}", release.get("target_commitish", ""))
            or release.get("url") != f"{API}/repos/{repository}/releases/{release['id']}"):
        raise SyncError("unverified-release-identity")
    raw = release.get("assets")
    if not isinstance(raw, list) or len(raw) > 100:
        raise SyncError("invalid-release-assets")
    assets, versions = {}, set()
    for item in raw:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise SyncError("invalid-release-asset")
        name = item["name"]
        if name.endswith(("-sources.jar", "-api.jar", "-dev.jar")):
            continue
        match = ASSET.fullmatch(name)
        if not match:
            continue
        module, version = match.groups()
        url = f"https://github.com/{repository}/releases/download/nightly/{name}"
        if (module in assets or not integer(item.get("id")) or item.get("state") != "uploaded"
                or not integer(item.get("size"), 4) or item['size'] > 64 * 1024 * 1024
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", item.get("digest") or "")
                or item.get("browser_download_url") != url):
            raise SyncError("incomplete-or-invalid-assets")
        assets[module] = {key: item[key] for key in ("id", "name", "size", "digest")}
        assets[module].update(url=MIRROR + repository + "/releases/download/nightly/" + name,
                              updated_at=item.get("updated_at"))
        versions.add(version)
    if set(assets) != MODULES or len(versions) != 1:
        raise SyncError("incomplete-or-mixed-release")
    return {"id": release["id"], "commit": release["target_commitish"],
            "assets": sorted(assets.values(), key=lambda a: a["name"])}


def render_assets(assets):
    return [a['name'] + ':' + str(a['size']) + ':' + a['url'] for a in assets]


def replace_nightly(text, repository, lines):
    """Replace precisely one owner/repository/MC/nightly subtree; preserve other text."""
    if repository not in REPOSITORIES or not isinstance(text, str) or len(text.encode()) > MAX_JSON:
        raise SyncError("invalid-link-tree")
    target = (*repository.split('/'), '1.21.1', 'nightly')
    existing = text.splitlines(keepends=True)
    directories, stack = {}, []
    for index, raw in enumerate(existing):
        line = raw.rstrip('\r\n')
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(' '))
        if '\t' in line[:indent + 1] or indent % 2:
            raise SyncError("unsupported-link-tree-indentation")
        level = indent // 2
        if level > len(stack):
            raise SyncError("invalid-link-tree-depth")
        stack = stack[:level]
        value = line.strip()
        if value.endswith(':') and '://' not in value:
            path = (*stack, value[:-1])
            if path in directories:
                raise SyncError("duplicate-link-tree-directory")
            directories[path] = index
            stack.append(value[:-1])
    replacement = ''.join('        ' + value + '\n' for value in lines)
    if target in directories:
        start = directories[target] + 1
        end = start
        while end < len(existing):
            raw = existing[end]
            if raw.strip() and (len(raw) - len(raw.lstrip(' '))) <= 6:
                break
            end += 1
        # Keep unchanged branches byte-for-byte, including their existing line endings.
        if [x.strip() for x in existing[start:end] if x.strip()] == lines:
            return text
        return ''.join(existing[:start]) + replacement + ''.join(existing[end:])
    depth = max((len(path) for path in directories if target[:len(path)] == path), default=0)
    if depth:
        start = directories[target[:depth]] + 1
        while start < len(existing):
            raw = existing[start]
            if raw.strip() and len(raw) - len(raw.lstrip(' ')) <= (depth - 1) * 2:
                break
            start += 1
    else:
        start = len(existing)
    new = ''.join('  ' * level + target[level] + ':\n' for level in range(depth, 4)) + replacement
    prefix = ''.join(existing[:start])
    return prefix + ('\n' if prefix and not prefix.endswith('\n') else '') + new + ''.join(existing[start:])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    def __init__(self, token, opener=None, now=time.time):
        if not token or any(c in token for c in '\r\n'):
            raise SyncError("invalid-local-credential")
        self.token, self.now = token, now
        # Never forward an Authorization header via an HTTP redirect or environment proxy.
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def _json(self, url, data=None, local=False):
        headers = {'User-Agent': 'folkways-openlist-sync/1', 'Accept': 'application/json'}
        if local:
            if not url.startswith(LOCAL + '/api/'):
                raise SyncError("invalid-local-api")
            headers['Authorization'] = self.token
        body = None
        if data is not None:
            headers['Content-Type'] = 'application/json'
            body = json.dumps(data).encode()
        request = urllib.request.Request(url, data=body, headers=headers)
        try:
            with self.opener.open(request, timeout=15) as response:
                if response.status != 200 or 'application/json' not in response.headers.get('Content-Type', '').lower():
                    raise SyncError("invalid-api-response")
                raw = response.read(MAX_JSON + 1)
                if len(raw) > MAX_JSON:
                    raise SyncError("metadata-exceeds-limit")
                value = json.loads(raw)
        except urllib.error.HTTPError as error:
            if url.startswith(API + '/') and error.code in (403, 429):
                until = self.now() + 60
                with contextlib.suppress(ValueError, TypeError):
                    if error.headers.get('X-RateLimit-Remaining') == '0':
                        until = max(until, float(error.headers['X-RateLimit-Reset']) + 5)
                    elif error.headers.get('Retry-After'):
                        until = max(until, self.now() + min(3600, float(error.headers['Retry-After'])))
                raise RateLimited(until) from None
            raise SyncError('api-http-' + str(error.code)) from None
        except (OSError, ValueError):
            raise SyncError("api-network-or-json-failure") from None
        if local or url.startswith(PUBLIC + '/'):
            if not isinstance(value, dict) or value.get('code') != 200:
                raise SyncError("openlist-api-failure")
            return value.get('data')
        return value

    def github(self, path):
        if not any(path.startswith('/repos/' + repo + '/') for repo in REPOSITORIES):
            raise SyncError("unapproved-official-api")
        return self._json(API + path)

    def storage(self):
        return self._json(LOCAL + '/api/admin/storage/get?id=5', local=True)

    def update_storage(self, storage):
        validate_storage(storage)
        return self._json(LOCAL + '/api/admin/storage/update', storage, local=True)

    def probe(self, asset):
        if not asset['url'].startswith(MIRROR) or '?' in asset['url']:
            raise SyncError("unapproved-mirror")
        req = urllib.request.Request(asset['url'], headers={'User-Agent': 'folkways-bootstrap/0.1', 'Range': 'bytes=0-3'})
        try:
            with self.opener.open(req, timeout=20) as response:
                if (response.status != 206 or response.headers.get('Content-Range') != 'bytes 0-3/' + str(asset['size'])
                        or response.read(5) != b'PK\x03\x04'):
                    raise SyncError("mirror-range-verification-failed")
        except urllib.error.HTTPError as error:
            raise SyncError('mirror-http-' + str(error.code)) from None
        except OSError:
            raise SyncError("mirror-network-failure") from None

    def verify_public(self, repository, assets):
        path = '/folkways/' + repository + '/1.21.1/nightly'
        self._json(LOCAL + '/api/fs/list', {'path': '/Guest' + path, 'password': '',
                   'page': 1, 'per_page': 0, 'refresh': True}, local=True)
        listed = self._json(PUBLIC + '/api/fs/list', {'path': path, 'password': '', 'page': 1, 'per_page': 0, 'refresh': False})
        content = listed.get('content') if isinstance(listed, dict) else None
        if (not isinstance(content, list) or len(content) != 5
                or {x.get('name'): x.get('size') for x in content} != {a['name']: a['size'] for a in assets}
                or any(x.get('is_dir') for x in content)):
            raise SyncError("public-directory-readback-failed")
        for asset in assets:
            data = self._json(PUBLIC + '/api/fs/get', {'path': path + '/' + asset['name'], 'password': ''})
            if not isinstance(data, dict) or data.get('raw_url') != asset['url'] or not data.get('sign'):
                raise SyncError("public-link-readback-failed")
            url = PUBLIC + '/d/Guest' + path + '/' + asset['name'] + '?' + urllib.parse.urlencode({'sign': data['sign']})
            try:
                with self.opener.open(urllib.request.Request(url, headers={'User-Agent': 'folkways-bootstrap/0.1'}), timeout=15):
                    raise SyncError("public-redirect-missing")
            except urllib.error.HTTPError as error:
                if error.code != 302 or error.headers.get('Location') != asset['url']:
                    raise SyncError("public-redirect-readback-failed") from None
            except OSError:
                raise SyncError("public-redirect-network-failure") from None


def validate_storage(storage):
    if (not isinstance(storage, dict) or storage.get('id') != STORAGE_ID or storage.get('mount_path') != MOUNT
            or storage.get('driver') != 'UrlTree' or storage.get('disabled')
            or storage.get('web_proxy') or storage.get('webdav_policy') != '302_redirect' or storage.get('down_proxy_url')):
        raise SyncError("storage-identity-or-policy-changed")
    try:
        addition = json.loads(storage['addition'])
        if (not isinstance(addition['url_structure'], str) or len(addition['url_structure'].encode()) > MAX_JSON
                or addition.get('head_size') or addition.get('writable')):
            raise SyncError("unsupported-storage-addition")
        return addition
    except (KeyError, TypeError, ValueError):
        raise SyncError("invalid-storage-addition") from None


def atomic_json(path, value):
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n'); output.flush(); os.fsync(output.fileno())
    os.replace(tmp, path)
    if os.name == 'posix':
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def read_json(path, default=None):
    if not path.exists():
        return copy.deepcopy(default)
    if path.is_symlink() or path.stat().st_size > 4 * MAX_JSON:
        raise SyncError("unsafe-state-file")
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (ValueError, OSError):
        raise SyncError("invalid-state-file") from None


def verify_ci(client, repository, snapshot):
    root = '/repos/' + repository
    ref = client.github(root + '/git/ref/tags/nightly').get('object', {})
    for _ in range(4):
        if ref.get('type') == 'commit' and ref.get('sha') == snapshot['commit']:
            break
        if ref.get('type') != 'tag' or not re.fullmatch('[0-9a-f]{40}', ref.get('sha', '')):
            raise SyncError("tag-commit-mismatch")
        ref = client.github(root + '/git/tags/' + ref['sha']).get('object', {})
    else:
        raise SyncError("tag-commit-mismatch")
    path = root + '/actions/workflows/build.yml/runs?' + urllib.parse.urlencode({
        'head_sha': snapshot['commit'], 'event': 'push', 'status': 'success', 'per_page': 20})
    runs = client.github(path).get('workflow_runs')
    if not isinstance(runs, list) or len(runs) > 20:
        raise SyncError("invalid-ci-response")
    valid = [run for run in runs if isinstance(run, dict) and run.get('head_sha') == snapshot['commit']
             and run.get('status') == 'completed' and run.get('conclusion') == 'success' and run.get('event') == 'push'
             and run.get('path') == '.github/workflows/build.yml' and run.get('head_branch') in ('master', 'main', 'nightly')
             and (run.get('repository') or {}).get('full_name') == repository
             and (run.get('head_repository') or {}).get('full_name') == repository
             and integer(run.get('id')) and integer(run.get('run_number')) and integer(run.get('run_attempt'))
             and run['run_attempt'] < 1000 and run['run_number'] < 2**53]
    if not valid:
        raise SyncError("successful-publishing-ci-unavailable")
    return max(run['run_number'] * 1000 + run['run_attempt'] for run in valid)


class SyncEngine:
    def __init__(self, client, state_dir, now=time.time):
        self.client, self.root, self.now = client, Path(state_dir), now

    def _state(self):
        state = read_json(self.root / 'state.json', {'schema': 1, 'sources': {}})
        if not isinstance(state, dict) or state.get('schema') != 1 or not isinstance(state.get('sources'), dict):
            raise SyncError("invalid-sync-state")
        for repo, item in state['sources'].items():
            if (repo not in REPOSITORIES or not isinstance(item, dict) or not integer(item.get('sequence'))
                    or not re.fullmatch('[0-9a-f]{64}', item.get('fingerprint', ''))):
                raise SyncError("invalid-sync-state")
        return state

    def _recover(self):
        journal_path = self.root / 'journal.json'
        journal = read_json(journal_path)
        if journal is None:
            return False
        if (not isinstance(journal, dict) or journal.get('schema') != 1
                or 'beforeState' not in journal or not isinstance(journal.get('afterState'), dict)
                or journal['afterState'].get('schema') != 1 or not isinstance(journal['afterState'].get('sources'), dict)):
            raise SyncError("invalid-recovery-journal")
        before, desired = journal['before'], journal['desiredAddition']
        original = validate_storage(before)
        current = self.client.storage()
        present = validate_storage(current)
        actual_state = read_json(self.root / 'state.json')
        if actual_state == journal['afterState']:
            # State is the commit marker and is written only after public readback succeeds.
            if present != desired:
                raise SyncError("committed-storage-changed-before-recovery")
            journal_path.unlink()
            self._prune_backups()
            return True
        if actual_state != journal['beforeState']:
            raise SyncError("recovery-blocked-by-state-change")
        if present == desired:
            # Preserve latest unrelated fields; no blind replay of an old complete storage row.
            restored = copy.deepcopy(current)
            restored['addition'] = json.dumps(original, ensure_ascii=False)
            self.client.update_storage(restored)
            if validate_storage(self.client.storage()) != original:
                raise SyncError("recovery-readback-failed")
        elif present != original:
            raise SyncError("recovery-blocked-by-manual-change")
        journal_path.unlink()
        self._prune_backups()
        return True

    def _backup(self, storage):
        name = 'storage-5-' + dt.datetime.fromtimestamp(self.now(), dt.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ') + '.json'
        backup = self.root / 'backups' / name
        if backup.exists():
            raise SyncError("backup-name-conflict")
        atomic_json(backup, storage)
        return name

    def _save_state(self, state, expected):
        if read_json(self.root / 'state.json') != expected:
            raise SyncError("sync-state-changed-during-run")
        atomic_json(self.root / 'state.json', state)

    def _prune_backups(self):
        if (self.root / 'journal.json').exists():
            return
        for backup in sorted((self.root / 'backups').glob('storage-5-*.json'))[:-5]:
            backup.unlink()

    def run(self, execute=False):
        if (self.root / 'journal.json').exists():
            if not execute:
                raise SyncError("pending-recovery-requires-execute")
            self._recover()
        raw_state = read_json(self.root / 'state.json')
        state = self._state()
        before = self.client.storage()
        addition = validate_storage(before)
        tree = addition['url_structure']
        next_state = copy.deepcopy(state)
        accepted, results = {}, {}
        for repository in REPOSITORIES:
            try:
                if next_state.get('githubBackoffUntil', 0) > self.now():
                    results[repository] = 'github-backoff'
                    continue
                endpoint = '/repos/' + repository + '/releases/tags/nightly'
                snapshot = validate_release(repository, self.client.github(endpoint))
                fingerprint = digest(snapshot)
                prior = state['sources'].get(repository)
                if prior and prior['fingerprint'] == fingerprint:
                    sequence = prior['sequence']
                else:
                    sequence = verify_ci(self.client, repository, snapshot)
                    if prior and (sequence < prior['sequence'] or sequence == prior['sequence']):
                        raise SyncError("release-rollback-or-changed-ci-build")
                    for asset in snapshot['assets']:
                        self.client.probe(asset)
                    if validate_release(repository, self.client.github(endpoint)) != snapshot:
                        raise SyncError("release-changed-during-verification")
                tree = replace_nightly(tree, repository, render_assets(snapshot['assets']))
                accepted[repository] = snapshot['assets']
                next_state['sources'][repository] = {'fingerprint': fingerprint, 'sequence': sequence,
                    'commit': snapshot['commit'], 'releaseId': snapshot['id']}
                results[repository] = 'verified' if not prior or prior['fingerprint'] != fingerprint else 'unchanged'
            except RateLimited as error:
                next_state['githubBackoffUntil'] = max(self.now() + 60, error.until)
                results[repository] = str(error)
            except SyncError as error:
                results[repository] = str(error)
            except Exception:
                results[repository] = 'source-verification-failed'
        next_state.update(lastRun=int(self.now()), lastResults=results)
        changed = tree != addition['url_structure']
        report = {'dryRun': not execute, 'changed': changed, 'sources': results,
                  'acceptedSources': list(accepted), 'storageId': STORAGE_ID}
        if not execute:
            return report
        if read_json(self.root / 'state.json') != raw_state:
            raise SyncError("sync-state-changed-during-run")
        if not accepted:
            self._save_state(next_state, raw_state)
            return report
        if self.client.storage() != before:
            raise SyncError("storage-changed-during-sync")
        if changed:
            desired = copy.deepcopy(addition)
            desired['url_structure'] = tree
            backup = self._backup(before)
            atomic_json(self.root / 'journal.json', {'schema': 1, 'before': before,
                        'desiredAddition': desired, 'backup': backup, 'beforeState': raw_state, 'afterState': next_state})
            updated = copy.deepcopy(before)
            updated['addition'] = json.dumps(desired, ensure_ascii=False)
            try:
                self.client.update_storage(updated)
                readback = self.client.storage()
                if (readback.get('status') != 'work' or validate_storage(readback) != desired
                        or any(readback.get(k) != before[k] for k in before if k not in ('addition', 'modified', 'status'))):
                    raise SyncError("storage-write-readback-failed")
                for repository, assets in accepted.items():
                    self.client.verify_public(repository, assets)
                final_storage = self.client.storage()
                if final_storage.get('status') != 'work' or validate_storage(final_storage) != desired:
                    raise SyncError("storage-changed-during-public-verification")
                self._save_state(next_state, raw_state)
                (self.root / 'journal.json').unlink()
            except Exception:
                self._recover()
                raise SyncError("update-failed-recovery-completed") from None
        else:
            # First run establishes trust without rewriting an already-correct directory.
            if any(results[repo] == 'verified' for repo in accepted):
                for repository, assets in accepted.items():
                    self.client.verify_public(repository, assets)
            self._save_state(next_state, raw_state)
        self._prune_backups()
        return report


@contextlib.contextmanager
def exclusive(root):
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    fd = os.open(root / 'sync.lock', os.O_RDWR | os.O_CREAT, 0o600)
    try:
        if os.name == 'posix':
            import fcntl
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SyncError("sync-already-running") from None
        else:
            import msvcrt
            os.write(fd, b'0'); os.lseek(fd, 0, os.SEEK_SET)
            try:
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            except OSError:
                raise SyncError("sync-already-running") from None
        yield
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, default=Path('/var/lib/folkways-openlist-sync'))
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--execute', action='store_true', help='apply changes; otherwise read-only dry run')
    parser.add_argument('--status', action='store_true', help='show local last-run metadata without using a credential')
    args = parser.parse_args()
    try:
        if args.status:
            print(json.dumps(read_json(args.state_dir / 'state.json', {}), ensure_ascii=False, sort_keys=True))
            return 0
        if args.token_file is None or not args.token_file.is_file() or args.token_file.stat().st_size > 16384:
            raise SyncError("local-credential-file-required")
        client = Client(args.token_file.read_text(encoding='utf-8').strip())
        engine = SyncEngine(client, args.state_dir)
        with exclusive(args.state_dir) if args.execute else contextlib.nullcontext():
            result = engine.run(execute=args.execute)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0 if len(result['acceptedSources']) == 2 else 1
    except SyncError as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False))
        return 1
    except Exception:
        # Exception reprs may include private proxy, path or credential details.
        print(json.dumps({'error': 'unexpected-sync-failure'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
