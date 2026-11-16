#!/usr/bin/env python3
"""Launch an isolated real demo jar, run the frontend driver, retain logs, and always clean up.

No server responses are mocked. A blocked/missing browser exits nonzero; it is never a skipped pass.
Run from any directory: python3 scripts/browser_runtime.py --jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar
"""
from __future__ import annotations
import argparse
import hashlib
import http.cookiejar
import json
import os
import secrets
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from verification_inputs import capture_inputs, require_unchanged


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default=None)
    parser.add_argument("--driver", choices=("browser", "dom"), default="browser")
    parser.add_argument("--startup-timeout", type=int, default=90)
    parser.add_argument("--test-timeout", type=int, default=600)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error(f"Jar not found: {jar}")
    java = args.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else shutil.which("java"))
    if args.java and ("/" in args.java or "\\" in args.java):
        java = str(Path(args.java).resolve())
    if not java:
        parser.error("Java 17+ is required; pass --java or set JAVA_HOME")
    if args.startup_timeout < 1 or args.test_timeout < 1:
        parser.error("Timeouts must be positive")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    run_id = uuid.uuid4().hex
    workspace = root / ".work" / f"frontend-{args.driver}-{run_id}"
    workspace.mkdir(parents=True)
    output = root / "frontend" / "test-results"
    output.mkdir(parents=True, exist_ok=True)
    driver_report = output / ("browser-report.json" if args.driver == "browser" else "live-dom-report.json")
    driver_report.unlink(missing_ok=True)
    verification_inputs = capture_inputs("frontend", extra_files=("scripts/browser_runtime.py",))
    log_path = output / f"{args.driver}-server.log"
    base = f"http://127.0.0.1:{port}"
    db = (workspace / "database").as_posix()
    env = os.environ.copy()
    # The integration suite deliberately uses EVIDENCE_ONLY, never a paid/external model.
    for key in ("TYPEFORGE_TUTOR_PROVIDER_URL", "TYPEFORGE_TUTOR_API_KEY", "TYPEFORGE_TUTOR_MODEL",
                "TYPEFORGE_EDITOR_USERS", "TYPEFORGE_ADMIN_USERS", "TYPEFORGE_TEST_EDITOR_AUTHOR",
                "TYPEFORGE_TEST_EDITOR_REVIEWER", "TYPEFORGE_TEST_EDITOR_PASSWORD", "TYPEFORGE_TEST_ADMIN_USERNAME"):
        env.pop(key, None)
    env["TYPEFORGE_BASE_URL"] = base
    env["TYPEFORGE_SECURE_COOKIE"] = "false"
    # Bootstrap only permits previously registered accounts. Register on a first empty-role launch,
    # then restart the same disposable database with two editors and a separate administrator.
    author = "verify-author-" + run_id[:16]
    reviewer = "verify-reviewer-" + run_id[:16]
    administrator = "verify-admin-" + run_id[:16]
    fixture_password = secrets.token_urlsafe(24) + "Aa7!"
    command = [java, "-Xmx512m", "-jar", str(jar), "--spring.profiles.active=demo",
               f"--spring.datasource.url=jdbc:h2:file:{db};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;DEFAULT_NULL_ORDERING=HIGH",
               "--typeforge.tutor.provider-url=", "--typeforge.tutor.api-key=", "--typeforge.tutor.model=", "--typeforge.compiler.enabled=false", "--typeforge.bootstrap.editor-users=", "--typeforge.bootstrap.admin-users=",
               "--spring.datasource.username=sa", "--spring.datasource.password=", f"--server.port={port}", "--server.address=127.0.0.1"]
    report = {"driver": args.driver, "jar": jar.name, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "baseUrl": base, "status": "STARTING", "log": log_path.name,
              "verificationInputs": verification_inputs,
              "editorialFixture": "Two random editors and one separate administrator, real registration/session/startup authorization, disposable database; no production review or student contribution claim"}
    process = None
    result = 1
    def require_unchanged_jar():
        if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != report["jarSha256"]:
            raise RuntimeError("Packaged application changed during integration; rebuild and rerun against one immutable JAR")

    def await_readiness(application):
        deadline = time.monotonic() + args.startup_timeout
        while time.monotonic() < deadline:
            if application.poll() is not None:
                raise RuntimeError("Backend exited before readiness")
            try:
                with urllib.request.urlopen(base + "/api/v1/health", timeout=2) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                time.sleep(0.25)
        raise RuntimeError("Backend readiness timeout")

    def stop(application):
        if application and application.poll() is None:
            application.terminate()
            try:
                application.wait(timeout=10)
            except subprocess.TimeoutExpired:
                application.kill()
                application.wait(timeout=5)

    def register_fixture(username):
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        with client.open(base + "/api/v1/auth/csrf", timeout=8) as response:
            csrf = json.loads(response.read(65536))
        request = urllib.request.Request(base + "/api/v1/auth/register", method="POST",
                    data=json.dumps({"username": username, "password": fixture_password}).encode(),
                    headers={"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
        with client.open(request, timeout=10) as response:
            registered = json.loads(response.read(65536))
            if response.status != 201 or set(registered.get("roles", [])) != {"LEARNER"}:
                raise RuntimeError("Test-only registration did not retain the ordinary learner boundary")

    try:
        with log_path.open("w", encoding="utf-8") as log:
            require_unchanged_jar()
            process = subprocess.Popen(command, cwd=workspace, env=env, stdout=log, stderr=subprocess.STDOUT)
            await_readiness(process)
            register_fixture(author)
            register_fixture(reviewer)
            register_fixture(administrator)
            stop(process)
            command = ["--typeforge.bootstrap.editor-users="+author+","+reviewer if value == "--typeforge.bootstrap.editor-users=" else value for value in command]
            command = ["--typeforge.bootstrap.admin-users="+administrator if value == "--typeforge.bootstrap.admin-users=" else value for value in command]
            require_unchanged_jar()
            process = subprocess.Popen(command, cwd=workspace, env=env, stdout=log, stderr=subprocess.STDOUT)
            await_readiness(process)
            report["editorialFixtureRegistered"] = 2
            report["administratorFixtureRegistered"] = 1
            print(f"Real demo backend ready for {args.driver} integration at {base}", flush=True)
            driver = ["node", "tests/browser.mjs"] if args.driver == "browser" else [shutil.which("npm") or "npm", "run", "test:live"]
            driver_env = env.copy()
            driver_env["TYPEFORGE_TEST_EDITOR_AUTHOR"] = author
            driver_env["TYPEFORGE_TEST_EDITOR_REVIEWER"] = reviewer
            driver_env["TYPEFORGE_TEST_EDITOR_PASSWORD"] = fixture_password
            driver_env["TYPEFORGE_TEST_ADMIN_USERNAME"] = administrator
            try:
                completed = subprocess.run(driver, cwd=root / "frontend", env=driver_env, timeout=args.test_timeout)
                result = completed.returncode
                if result == 0:
                    if not driver_report.is_file() or json.loads(driver_report.read_text(encoding="utf-8")).get("status") != "PASS":
                        raise RuntimeError("Frontend driver exited without a fresh complete PASS report")
                    report["driverReportSha256"] = hashlib.sha256(driver_report.read_bytes()).hexdigest()
                report["status"] = "PASS" if result == 0 else "FAIL"
            except subprocess.TimeoutExpired:
                report["status"] = "TIMEOUT"
                report["error"] = "Frontend driver exceeded the bounded test timeout"
                result = 124
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        result = 1
        print(f"Integration failed: {error}", file=sys.stderr)
    finally:
        stop(process)
        try:
            require_unchanged_jar()
            report["jarUnchanged"] = True
        except Exception as error:
            report["status"] = "FAIL"
            report["error"] = str(error)
            report["jarUnchanged"] = False
            result = 1
        try:
            require_unchanged(verification_inputs)
            report["inputsUnchanged"] = True
        except Exception as error:
            report["status"] = "FAIL"
            report["error"] = str(error)
            report["inputsUnchanged"] = False
            result = 1
        report["exitCode"] = result
        (output / f"{args.driver}-runtime-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # Temporary student records are test-owned and removed; logs and reports remain reviewable.
        shutil.rmtree(workspace, ignore_errors=True)
    if result:
        print("Backend log tail:", file=sys.stderr)
        if log_path.exists():
            print("\n".join(log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-60:]), file=sys.stderr)
    return result


if __name__ == "__main__":
    raise SystemExit(main())
