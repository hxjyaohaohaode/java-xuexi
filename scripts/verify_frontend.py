#!/usr/bin/env python3
"""Actually run the complete frontend checks/build and bind fresh outputs to unchanged inputs."""
from pathlib import Path
import argparse
import datetime
import json
import os
import shutil
import subprocess
import tempfile
import time

from verification_inputs import (VerificationFailure, capture_dist, capture_inputs, require,
                                 require_unchanged, write_report)

ROOT = Path(__file__).resolve().parents[1]
EXTRA_FILES = ("scripts/verify_frontend.py",)
REPORT = ".work/frontend-build-verification.json"


def validate_unit_report(unit, root):
    expected = {path.relative_to(root).as_posix() for suffix in ("*.test.ts", "*.test.tsx")
                for path in (root / "frontend/src").rglob(suffix)}
    actual = set()
    summaries = []
    for item in unit.get("testResults", []):
        path = Path(item["name"]).resolve()
        require(path.is_relative_to(root.resolve()), "Unit report names a test outside this repository")
        name = path.relative_to(root.resolve()).as_posix()
        cases = item.get("assertionResults", [])
        require(name not in actual and item.get("status") == "passed" and bool(cases),
                "Unit test file is duplicated, empty or failed: " + name)
        require(all(case.get("status") == "passed" and not case.get("failureMessages") for case in cases),
                "Unit test file contains failed, skipped or pending cases: " + name)
        names = [case["fullName"] for case in cases]
        require(len(set(names)) == len(names), "Unit test file has ambiguous duplicate case names: " + name)
        actual.add(name)
        summaries.append({"name": name, "tests": len(cases), "caseNames": names})
    total = sum(item["tests"] for item in summaries)
    require(expected and actual == expected, "Unit result files differ from the complete current src/**/*.test inventory")
    require(unit.get("success") is True and unit.get("numTotalTests") == unit.get("numPassedTests") == total
            and unit.get("numFailedTests") == unit.get("numPendingTests") == 0,
            "Frontend unit summary does not describe an entirely passing current run")
    return summaries


def run_gate(npm="npm", root=None, runner=None):
    root = Path(root or ROOT).resolve()
    runner = runner or subprocess.run
    destination = root / REPORT
    report = {"result": "FAIL", "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "Actual npm run check followed by npm run build; prior reports/dist cannot be reused"}
    write_report(destination, report)  # A killed or incomplete attempt must invalidate an older PASS.
    started = time.monotonic()
    try:
        before = capture_inputs("frontend", EXTRA_FILES, root)
        report["verificationInputs"] = before
        unit_path = root / "frontend/test-results/unit-report.json"
        work = Path(tempfile.mkdtemp(prefix="frontend-verification-", dir=root / ".work"))
        work.chmod(0o700)
        if unit_path.exists():
            unit_path.replace(work / "previous-unit-report.json")
        if (root / "frontend/dist").exists():
            shutil.move(str(root / "frontend/dist"), str(work / "previous-dist"))
        executable = shutil.which(npm) or (str(Path(npm).resolve()) if Path(npm).is_file() else None)
        require(executable is not None, "npm executable was not found")
        env = os.environ.copy()
        env["CI"] = "true"
        with (work / "build.log").open("w", encoding="utf-8") as log:
            for script in ("check", "build"):
                result = runner([executable, "run", script], cwd=root / "frontend", env=env,
                                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=600)
                require(result.returncode == 0, "Actual frontend " + script + " command failed; inspect the private build log")
        require(unit_path.is_file(), "No fresh frontend unit report was produced")
        unit = json.loads(unit_path.read_text(encoding="utf-8"))
        summaries = validate_unit_report(unit, root)
        dist = capture_dist(root)
        report.update({"inputsUnchanged": require_unchanged(before, root), "result": "PASS",
                       "unitSummary": {key: unit[key] for key in ("numTotalTestSuites", "numPassedTestSuites", "numFailedTestSuites", "numTotalTests", "numPassedTests", "numFailedTests", "numPendingTests", "success")},
                       "unitFiles": summaries, "dist": dist,
                       "commands": ["npm run check", "npm run build"]})
    except Exception as failure:
        report["error"] = str(failure) if isinstance(failure, VerificationFailure) else type(failure).__name__
    report["durationSeconds"] = round(time.monotonic() - started, 3)
    write_report(destination, report)
    print(json.dumps({key: value for key, value in report.items() if key not in {"verificationInputs", "dist", "unitFiles"}}, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npm", default="npm")
    return run_gate(parser.parse_args().npm)


if __name__ == "__main__":
    raise SystemExit(main())
