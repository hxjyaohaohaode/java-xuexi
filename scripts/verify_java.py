#!/usr/bin/env python3
"""Run actual clean verify and bind the packaged JAR plus every current JUnit case to its inputs."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

from verification_inputs import (VerificationFailure, capture_dist, capture_inputs, clean_maven_environment,
                                 java_test_inventory, read_java_summaries, require, require_current_inputs,
                                 require_unchanged, validate_java_summaries, write_report)

ROOT = Path(__file__).resolve().parents[1]
EXTRA_FILES = ("scripts/verify_java.py",)
REPORT = ".work/java-build-verification.json"


def packaged_jar(root):
    pom = ET.parse(root / "backend/pom.xml").getroot()
    version = pom.find("{http://maven.apache.org/POM/4.0.0}version").text
    require(version and "/" not in version and "\\" not in version, "Invalid packaged application version")
    return root / "backend/target" / ("typeforge-backend-" + version + ".jar")


def validate_packaged_dist(jar, dist, root):
    expected = {"BOOT-INF/classes/static/" + name.removeprefix("frontend/dist/"): digest
                for name, digest in dist["files"].items()}
    with zipfile.ZipFile(jar) as archive:
        actual = {name for name in archive.namelist() if name.startswith("BOOT-INF/classes/static/") and not name.endswith("/")}
        require(actual == set(expected), "Packaged frontend contains missing or obsolete files")
        require(all(hashlib.sha256(archive.read(name)).hexdigest() == digest for name, digest in expected.items()),
                "Packaged frontend differs from the verified current frontend build")


def run_gate(maven="mvn", root=None, runner=None):
    root = Path(root or ROOT).resolve()
    runner = runner or subprocess.run
    destination = root / REPORT
    report = {"result": "FAIL", "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "command": "mvn -B -ntp -f backend/pom.xml clean verify", "scope": "Actual complete clean build; current JUnit source methods and every finite parameter invocation required"}
    write_report(destination, report)
    started = time.monotonic()
    try:
        before = capture_inputs("backend", EXTRA_FILES, root)
        report["verificationInputs"] = before
        frontend = json.loads((root / ".work/frontend-build-verification.json").read_text())
        require_current_inputs(frontend, "frontend", ("scripts/verify_frontend.py",), root)
        dist = capture_dist(root)
        require(frontend.get("dist") == dist, "Frontend dist is not the verified current build")
        expected = java_test_inventory(root)
        jar = packaged_jar(root)
        work = Path(tempfile.mkdtemp(prefix="java-verification-", dir=root / ".work"))
        work.chmod(0o700)
        # Do this before invoking Maven as well as requesting clean. A no-op/failing launcher
        # cannot re-sign an earlier green XML/JAR left in target.
        if jar.exists():
            jar.replace(work / "previous.jar")
        for path in (root / "backend/target/surefire-reports").glob("TEST-*.xml"):
            path.replace(work / path.name)
        executable = shutil.which(maven) or (str(Path(maven).resolve()) if Path(maven).is_file() else None)
        require(executable is not None, "Maven executable was not found")
        env = clean_maven_environment()
        with (work / "maven.log").open("w", encoding="utf-8") as log:
            result = runner([executable, "-B", "-ntp", "-f", str(root / "backend/pom.xml"), "clean", "verify"],
                            cwd=root, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
        report["mavenExitCode"] = result.returncode
        require(result.returncode == 0, "Actual Maven clean verify failed; inspect the private Maven log")
        require(jar.is_file(), "No fresh packaged JAR was produced")
        summaries = read_java_summaries(root)
        totals = validate_java_summaries(summaries, expected)
        require(capture_dist(root) == dist, "Frontend dist changed during Maven packaging")
        require_current_inputs(frontend, "frontend", ("scripts/verify_frontend.py",), root)
        validate_packaged_dist(jar, dist, root)
        report.update({"inputsUnchanged": require_unchanged(before, root), "result": "PASS", **totals,
                       "testInventory": expected, "suites": summaries,
                       "jar": jar.relative_to(root).as_posix(), "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                       "frontendDist": dist, "frontendInputDigest": frontend["verificationInputs"]["digest"]})
    except Exception as failure:
        report["error"] = str(failure) if isinstance(failure, VerificationFailure) else type(failure).__name__
    report["durationSeconds"] = round(time.monotonic() - started, 3)
    write_report(destination, report)
    print(json.dumps({key: value for key, value in report.items() if key not in {"verificationInputs", "frontendDist", "testInventory", "suites"}}, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn")
    return run_gate(parser.parse_args().maven)


if __name__ == "__main__":
    raise SystemExit(main())
