#!/usr/bin/env python3
"""Re-run account closure and private assets against explicitly task-owned real MySQL 8.4."""
from pathlib import Path
import argparse
import datetime
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

from verify_transaction_isolation import GateFailure, mysql_environment

ROOT = Path(__file__).resolve().parents[1]
SUITES = {"AccountLifecycleIntegrationTest": 14, "PrivateAssetsIntegrationTest": 8}
PREFIX = "TYPEFORGE_PRIVATE_MODULE_EVIDENCE "
REPORT = ROOT / ".work/mysql-account-assets-verification.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn")
    args = parser.parse_args()
    report = {"result": "FAIL", "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "Explicit real-MySQL account lifecycle and private asset HTTP/SQL regression; no external media or model provider claim"}
    try:
        env = mysql_environment()
        env["TYPEFORGE_MYSQL_ACCOUNT_TEST"] = "true"
        executable = shutil.which(args.maven)
        if executable is None and Path(args.maven).is_file():
            executable = str(Path(args.maven).resolve())
        if executable is None:
            raise GateFailure("Maven executable was not found")
        work = ROOT / ".work/mysql-private-modules"
        work.mkdir(parents=True, exist_ok=True)
        for suite in SUITES:
            (ROOT / ("backend/target/surefire-reports/TEST-io.typeforge." + suite + ".xml")).unlink(missing_ok=True)
        with (work / "maven.log").open("w", encoding="utf-8") as output:
            result = subprocess.run([executable, "-B", "-ntp", "-f", str(ROOT / "backend/pom.xml"),
                                     "-Dtest=" + ",".join(SUITES), "-DfailIfNoTests=true", "test"],
                                    cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=900)
        report["mavenExitCode"] = result.returncode
        results = []
        for suite, expected in SUITES.items():
            path = ROOT / ("backend/target/surefire-reports/TEST-io.typeforge." + suite + ".xml")
            if not path.is_file():
                raise GateFailure("Missing fresh test report for " + suite)
            xml = ET.parse(path).getroot()
            counts = {key: int(xml.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            proofs = []
            for node in xml.iter("system-out"):
                for line in (node.text or "").splitlines():
                    if line.strip().startswith(PREFIX):
                        proofs.append(json.loads(line.strip()[len(PREFIX):]))
            if counts["tests"] != expected or any(counts[key] for key in ("failures", "errors", "skipped")):
                raise GateFailure("Private module test count or outcome differs from the required gate: " + suite)
            if not any(proof.get("suite") == suite and proof.get("engine") == "MySQL" and str(proof.get("version", "")).startswith("8.4.") for proof in proofs):
                raise GateFailure("No actual MySQL 8.4 JDBC proof for " + suite)
            results.append({"suite": suite, **counts, "engineProof": proofs, "caseNames": [node.attrib["name"] for node in xml.iter("testcase")]})
        if result.returncode:
            raise GateFailure("Private module Maven execution failed")
        report.update({"result": "PASS", "tests": sum(item["tests"] for item in results), "suites": results})
    except Exception as failure:
        report["error"] = str(failure) if isinstance(failure, GateFailure) else type(failure).__name__
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
