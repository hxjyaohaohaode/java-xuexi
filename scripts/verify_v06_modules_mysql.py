#!/usr/bin/env python3
"""Run the v0.6 ingestion, evaluation, TEXT, Notebook and personal courseware study service/HTTP regressions on real MySQL 8.4.

Requires MYSQL_TEST_CONNECTION to explicitly mark a disposable named loopback database.
Fresh XML reports must contain every current @Test case and actual JDBC engine proof.
Raw Maven logs and XML properties remain local; only sanitized evidence is emitted.
"""
from pathlib import Path
import argparse
import datetime
import json
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from verify_transaction_isolation import GateFailure, mysql_environment
from verification_inputs import (VerificationFailure, capture_inputs, java_test_inventory,
                                 require_unchanged, validate_java_summaries, write_report)

ROOT = Path(__file__).resolve().parents[1]
SUITES = ("BatchImportIntegrationTest", "ContentComparisonIntegrationTest",
          "EvaluationWorkbenchIntegrationTest", "TextLearningIntegrationTest", "NotebookInteractionIntegrationTest",
          "CoursewareStudyIntegrationTest")
PREFIX = "TYPEFORGE_PRIVATE_MODULE_EVIDENCE "
REPORT = ROOT / ".work/mysql-v06-modules-verification.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn")
    args = parser.parse_args()
    started = time.monotonic()
    report = {"result": "FAIL", "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "Actual MySQL 8.4 service, SQL and authenticated HTTP regression for bounded imports, revision diffs, private evaluations, TEXT grading, six Notebook blocks, personal courseware-to-slide production and admin aggregates; compiler replies in this gate are protocol fixtures, not Docker execution evidence"}
    write_report(REPORT, report)
    try:
        before = capture_inputs("backend", ("scripts/verify_v06_modules_mysql.py", "scripts/verify_transaction_isolation.py"))
        report["verificationInputs"] = before
        env = mysql_environment()
        env["TYPEFORGE_MYSQL_V06_TEST"] = "true"
        executable = shutil.which(args.maven)
        if executable is None and Path(args.maven).is_file():
            executable = str(Path(args.maven).resolve())
        if executable is None:
            raise GateFailure("Maven executable was not found")
        work = ROOT / ".work/mysql-v06-modules"
        work.mkdir(parents=True, exist_ok=True)
        work.chmod(0o700)
        inventory = java_test_inventory()
        for suite in SUITES:
            if "io.typeforge." + suite not in inventory:
                raise GateFailure("Cannot identify the complete explicit test set: " + suite)
            (ROOT / ("backend/target/surefire-reports/TEST-io.typeforge." + suite + ".xml")).unlink(missing_ok=True)
        with (work / "maven.log").open("w", encoding="utf-8") as output:
            result = subprocess.run([executable, "-B", "-ntp", "-f", str(ROOT / "backend/pom.xml"),
                                     "-Dtest=" + ",".join(SUITES), "-DfailIfNoTests=true", "test"],
                                    cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=900)
        report["mavenExitCode"] = result.returncode
        results = []
        for suite in SUITES:
            path = ROOT / ("backend/target/surefire-reports/TEST-io.typeforge." + suite + ".xml")
            if not path.is_file():
                raise GateFailure("Missing fresh test report for " + suite)
            xml = ET.parse(path).getroot()
            counts = {key: int(xml.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            names = [node.attrib["name"] for node in xml.iter("testcase")]
            proofs = []
            for node in xml.iter("system-out"):
                for line in (node.text or "").splitlines():
                    if line.strip().startswith(PREFIX):
                        proof = json.loads(line.strip()[len(PREFIX):])
                        proofs.append({key: proof.get(key) for key in ("suite", "engine", "version", "database")})
            validate_java_summaries([{"name": "io.typeforge." + suite, **counts, "testCases": names}],
                                    {"io.typeforge." + suite: inventory["io.typeforge." + suite]})
            if not any(proof.get("suite") == suite and proof.get("engine") == "MySQL" and str(proof.get("version", "")).startswith("8.4.") for proof in proofs):
                raise GateFailure("No actual MySQL 8.4 JDBC proof for " + suite)
            results.append({"suite": suite, **counts, "engineProof": proofs, "caseNames": names})
        if result.returncode:
            raise GateFailure("Maven execution failed")
        report.update({"result": "PASS", "inputsUnchanged": require_unchanged(before), "tests": sum(item["tests"] for item in results), "suites": results})
    except Exception as failure:
        report["error"] = str(failure) if isinstance(failure, (GateFailure, VerificationFailure)) else type(failure).__name__
    report["durationSeconds"] = round(time.monotonic() - started, 3)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    write_report(REPORT, report)
    print(json.dumps({key: value for key, value in report.items() if key != "verificationInputs"}, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
