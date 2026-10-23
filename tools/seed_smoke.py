#!/usr/bin/env python3
"""Offline first-install smoke test for the actual release seed and foreground JVM."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from self_release import digest, verify_directory


def smoke(directory, java="java", scratch=None):
    directory = Path(directory).resolve(strict=True)
    metadata = verify_directory(directory)
    executable = shutil.which(java)
    if executable is None:
        raise ValueError("Java executable was not found")
    executable = str(Path(executable).resolve(strict=True))
    scratch = Path(scratch or Path.cwd() / "work" / "seed-smoke-tmp").resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="seed smoke-", dir=scratch) as temporary:
        base = Path(temporary).resolve(strict=True)
        if base.parent != scratch:
            raise ValueError("Smoke fixture escaped its scratch directory")
        game = base / "instance with spaces"
        mods = game / "mods"
        mods.mkdir(parents=True)
        artifacts = {a["component"]: a for a in metadata["artifacts"]}
        bootstrap_source = directory / artifacts["bootstrap"]["fileName"]
        companion = mods / artifacts["companion"]["fileName"]
        shutil.copy2(directory / companion.name, companion)
        home = game / ".folkways-updater"
        if home.exists():
            raise ValueError("Smoke requires an empty first-install fixture")
        environment = dict(os.environ)
        # Exercise persisted defaults without inheriting supervisor/JVM injection.
        for key in tuple(environment):
            if key.startswith("FOLKWAYS_") or key.upper() in {
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"
            }:
                del environment[key]
        if os.name == "nt":
            powershell = shutil.which("pwsh") or shutil.which("powershell")
            if powershell is None:
                raise ValueError("Windows seed smoke requires PowerShell")
            wrapper = base / "invoke seed.ps1"
            wrapper.write_text("""$ErrorActionPreference = 'Stop'
$gameCommand = [string[]]@($env:FOLKWAYS_SMOKE_JAVA, '-version')
& $env:FOLKWAYS_SMOKE_SEED -GameDirectory $env:FOLKWAYS_SMOKE_GAME -BootstrapJar $env:FOLKWAYS_SMOKE_BOOTSTRAP -CompanionJar $env:FOLKWAYS_SMOKE_COMPANION -Java $env:FOLKWAYS_SMOKE_JAVA -GameCommand $gameCommand
exit $LASTEXITCODE
""", encoding="utf-8")
            environment.update({"FOLKWAYS_SMOKE_JAVA": executable, "FOLKWAYS_SMOKE_GAME": str(game),
                                "FOLKWAYS_SMOKE_SEED": str(directory / "launch.ps1"),
                                "FOLKWAYS_SMOKE_BOOTSTRAP": str(bootstrap_source),
                                "FOLKWAYS_SMOKE_COMPANION": str(companion)})
            command = [powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(wrapper)]
            platform = "Windows/PowerShell"
        else:
            bash = shutil.which("bash")
            if bash is None or shutil.which("flock") is None:
                raise ValueError("Linux seed smoke requires bash and flock")
            command = [bash, str(directory / "launch.sh"), "--game-dir", str(game),
                       "--bootstrap-jar", str(bootstrap_source), "--companion-jar", str(companion),
                       "--java", executable, "--", executable, "-version"]
            platform = "Linux/bash+flock"
        result = subprocess.run(command, cwd=game, env=environment, capture_output=True, text=True, timeout=90)
        output = result.stdout + result.stderr
        if result.returncode != 0:
            raise RuntimeError(f"Seed exited {result.returncode}:\n{output}")
        if "version" not in output.lower() or "local" not in output.lower():
            raise ValueError("Foreground JVM or local-development protection was not observed")
        config = home / "config.json"
        if not config.is_file() or json.loads(config.read_text(encoding="utf-8")).get("localDevelopment") is not True:
            raise ValueError("First install did not retain localDevelopment=true")
        state = json.loads((home / "self-state.json").read_text(encoding="utf-8"))
        if set(state["owned"]) != {"bootstrap", "companion"} or state.get("localBuild") is not False:
            raise ValueError("CI-shaped updater artifacts were not explicitly adopted")
        if state["version"] != metadata["version"] or state["highest"] != metadata["sequence"]:
            raise ValueError("Adopted build identity differs from the prepared release")
        targets = {"bootstrap": home / "bin" / "folkways-bootstrap.jar", "companion": companion}
        for component, target in targets.items():
            artifact = artifacts[component]
            owned = state["owned"][component]
            if (digest(target) != artifact["sha256"] or target.stat().st_size != artifact["size"]
                    or owned["sha256"] != artifact["sha256"] or owned["size"] != artifact["size"]
                    or game / owned["path"] != target):
                raise ValueError("Default development mode changed updater JARs or ownership")
        runtime = home / "runtime"
        if not runtime.is_dir() or any(runtime.iterdir()):
            raise ValueError("Seed left an active runtime directory")
        for name in ("child.json", "self-journal.json", "self-pending", "self-candidate", "pending", "candidate"):
            if (home / name).exists():
                raise ValueError("Unexpected launch/update residue: " + name)
        verify_directory(directory)
        return {"passed": True, "platform": platform, "version": metadata["version"],
                "sequence": metadata["sequence"], "foreground": "java -version", "localDevelopment": True,
                "adopted": sorted(state["owned"]), "jarHashesUnchanged": True,
                "runtimeDirectories": 0, "childBarrier": False, "networkRequired": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("dist"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--scratch", type=Path)
    args = parser.parse_args()
    print(json.dumps(smoke(args.directory, args.java, args.scratch), indent=2))


if __name__ == "__main__":
    main()
