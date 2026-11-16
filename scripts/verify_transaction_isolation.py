#!/usr/bin/env python3
"""Run exactly the two Spring TransactionTemplate isolation tests on real MySQL.

Default Maven suites keep TransactionIsolationTest on isolated H2. This explicit
gate reads only MYSQL_TEST_CONNECTION with purpose=typeforge-isolated-verification,
passes its credentials through environment variables, and selects the real MySQL
Flyway migrations through DynamicPropertySource in that test class.

Local example (start MySQL and Maven in the same execution environment):
  python .work/mysql-validation/with_mysql.py python scripts/verify_transaction_isolation.py --maven .work/mvn
CI: prepare_ci_mysql.py first, then python scripts/verify_transaction_isolation.py

The fixed sanitized report is .work/mysql-isolation-verification.json. Maven output
and the previous XML report stay in a private temporary directory and are not CI
artifacts. No database is dropped or truncated; fixtures delete only their own UUIDs.
"""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from verification_inputs import (VerificationFailure, capture_inputs, clean_maven_environment,
                                 require_unchanged, write_report)


ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "io.typeforge.TransactionIsolationTest"
CASES = {
    "transactionTemplateUsesReadCommittedJdbcConnection",
    "readAfterOwnerLockSeesCommittedWorkspaceChange",
}
PROOF_PREFIX = "TYPEFORGE_ISOLATION_EVIDENCE "
REPORT = ROOT / ".work/mysql-isolation-verification.json"


class GateFailure(RuntimeError):
    pass


def mysql_environment():
    path = os.environ.get("MYSQL_TEST_CONNECTION")
    if not path:
        raise GateFailure("Explicit MYSQL_TEST_CONNECTION is required for the real MySQL isolation gate")
    config = json.loads(Path(path).read_text(encoding="utf-8"))
    if config.get("purpose") != "typeforge-isolated-verification":
        raise GateFailure("Connection must explicitly identify an isolated verification environment")
    host = config.get("host")
    database = config.get("runtimeDatabase", "")
    if host not in ("127.0.0.1", "localhost", "::1") or not re.fullmatch(r"typeforge_(?:runtime|smoke_[a-z0-9_]{1,48})", database):
        raise GateFailure("Only a reserved loopback runtime-test database is allowed")
    if database == config.get("schemaCheckDatabase"):
        raise GateFailure("Isolation regression must not use the schema-constraint fixture database")
    port = int(config["port"])
    if not 1 <= port <= 65535 or not config.get("username") or not config.get("password"):
        raise GateFailure("Incomplete private MySQL test configuration")
    env = clean_maven_environment(native_mysql=True)
    for name in list(env):
        if name.startswith("SPRING_DATASOURCE_") or name in ("SPRING_PROFILES_ACTIVE", "SPRING_APPLICATION_JSON"):
            del env[name]
    jdbc_host = "[" + host + "]" if ":" in host else host
    env["TYPEFORGE_MYSQL_ISOLATION_TEST"] = "true"
    env["TYPEFORGE_DB_URL"] = f"jdbc:mysql://{jdbc_host}:{port}/{database}?connectionTimeZone=UTC&sslMode=REQUIRED"
    env["TYPEFORGE_DB_USER"] = config["username"]
    env["TYPEFORGE_DB_PASSWORD"] = config["password"]
    return env


def read_result(xml_path):
    if not xml_path.is_file():
        raise GateFailure("Targeted Maven execution did not produce a new TransactionIsolationTest report")
    suite = ET.parse(xml_path).getroot()
    summary = {field: int(suite.attrib.get(field, "0")) for field in ("tests", "failures", "errors", "skipped")}
    cases = [case.attrib["name"] for case in suite.iter("testcase")]
    summary["caseNames"] = cases
    proofs = {}
    for output in suite.iter("system-out"):
        for line in (output.text or "").splitlines():
            if line.strip().startswith(PROOF_PREFIX):
                proof = json.loads(line.strip()[len(PROOF_PREFIX):])
                proofs[proof["check"]] = proof
    connection = proofs.get("connection-isolation", {})
    handoff = proofs.get("read-after-owner-lock", {})
    summary["engine"] = connection.get("engine", "UNCONFIRMED")
    summary["engineVersion"] = connection.get("version")
    # Select only non-sensitive, observed evidence; never copy raw XML properties or logs.
    summary["evidence"] = {
        "jdbcIsolation": connection.get("jdbcIsolation"),
        "autoCommit": connection.get("autoCommit"),
        "serverIsolation": connection.get("serverIsolation"),
        "initialRead": handoff.get("initial"),
        "afterOwnerLock": handoff.get("afterLock"),
        "repeatableReadControl": handoff.get("repeatableReadControl"),
        "writerCommitted": handoff.get("writerCommitted"),
    }
    valid = (summary["tests"] == 2 and summary["failures"] == 0 and summary["errors"] == 0
             and summary["skipped"] == 0 and set(cases) == CASES and len(cases) == 2
             and connection.get("engine") == "MySQL" and str(connection.get("version", "")).startswith("8.4.")
             and connection.get("jdbcIsolation") == 2 and connection.get("autoCommit") is False
             and connection.get("serverIsolation") == "READ-COMMITTED"
             and handoff.get("engine") == "MySQL" and handoff.get("jdbcIsolation") == 2
             and handoff.get("initial") == "before" and handoff.get("afterLock") == "after"
             and handoff.get("repeatableReadControl") == "before" and handoff.get("writerCommitted") is True)
    return summary, valid


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--maven", default="mvn", help="Maven executable or task-local launcher, for example .work/mvn")
    args = parser.parse_args()
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="mysql-isolation-", dir=ROOT / ".work"))
    work.chmod(0o700)
    report = {"result": "FAIL", "execution": "targeted-real-mysql", "testClass": TEST_CLASS,
              "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "engine": "UNCONFIRMED", "caseNames": []}
    write_report(REPORT, report)
    started = time.monotonic()
    xml_path = ROOT / "backend/target/surefire-reports" / ("TEST-" + TEST_CLASS + ".xml")
    try:
        before = capture_inputs("backend", ("scripts/verify_transaction_isolation.py",))
        report["verificationInputs"] = before
        env = mysql_environment()
        executable = shutil.which(args.maven)
        if executable is None and Path(args.maven).is_file():
            executable = str(Path(args.maven).resolve())
        if executable is None:
            raise GateFailure("Maven executable not found; supply --maven for a local launcher")
        # A cached successful H2/full-suite report must never pass this explicit MySQL gate.
        if xml_path.exists():
            xml_path.replace(work / "previous-junit.xml")
        command = [executable, "-B", "-ntp", "-f", str(ROOT / "backend/pom.xml"),
                   "-Dtest=TransactionIsolationTest", "-DfailIfNoTests=true", "test"]
        with (work / "maven.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, timeout=240)
        report["mavenExitCode"] = completed.returncode
        summary, valid = read_result(xml_path)
        report.update(summary)
        if completed.returncode != 0 or not valid:
            raise GateFailure("Targeted isolation checks failed or did not supply actual MySQL READ COMMITTED and REPEATABLE READ control evidence")
        report["inputsUnchanged"] = require_unchanged(before)
        report["result"] = "PASS"
    except Exception as failure:
        report["error"] = str(failure) if isinstance(failure, (GateFailure, VerificationFailure)) else type(failure).__name__
    finally:
        report["durationSeconds"] = round(time.monotonic() - started, 3)
        write_report(REPORT, report)
    print(json.dumps({key: value for key, value in report.items() if key != "verificationInputs"}, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
