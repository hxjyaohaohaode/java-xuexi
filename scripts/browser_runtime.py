#!/usr/bin/env python3
"""Launch an isolated real demo jar, run the frontend driver, retain logs, and always clean up.

No server responses are mocked. A blocked/missing browser exits nonzero; it is never a skipped pass.
Run from any directory: python3 scripts/browser_runtime.py --jar backend/target/typeforge-backend-0.5.0-SNAPSHOT.jar
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default=None)
    parser.add_argument("--driver", choices=("browser", "dom"), default="browser")
    parser.add_argument("--startup-timeout", type=int, default=90)
    parser.add_argument("--test-timeout", type=int, default=360)
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
    log_path = output / f"{args.driver}-server.log"
    base = f"http://127.0.0.1:{port}"
    db = (workspace / "database").as_posix()
    env = os.environ.copy()
    # The integration suite deliberately uses EVIDENCE_ONLY, never a paid/external model.
    for key in ("TYPEFORGE_TUTOR_PROVIDER_URL", "TYPEFORGE_TUTOR_API_KEY", "TYPEFORGE_TUTOR_MODEL"):
        env.pop(key, None)
    env["TYPEFORGE_BASE_URL"] = base
    env["TYPEFORGE_SECURE_COOKIE"] = "false"
    command = [java, "-Xmx512m", "-jar", str(jar), "--spring.profiles.active=demo",
               f"--spring.datasource.url=jdbc:h2:file:{db};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
               "--typeforge.tutor.provider-url=", "--typeforge.tutor.api-key=", "--typeforge.tutor.model=", "--typeforge.bootstrap.editor-users=", "--typeforge.bootstrap.admin-users=",
               "--spring.datasource.username=sa", "--spring.datasource.password=", f"--server.port={port}", "--server.address=127.0.0.1"]
    report = {"driver": args.driver, "jar": jar.name, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "baseUrl": base, "status": "STARTING", "log": log_path.name}
    process = None
    result = 1
    try:
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=workspace, env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + args.startup_timeout
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Backend exited before readiness")
                try:
                    with urllib.request.urlopen(base + "/api/v1/health", timeout=2) as response:
                        if response.status == 200:
                            break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    time.sleep(0.25)
            else:
                raise RuntimeError("Backend readiness timeout")
            print(f"Real demo backend ready for {args.driver} integration at {base}", flush=True)
            driver = ["node", "tests/browser.mjs"] if args.driver == "browser" else [shutil.which("npm") or "npm", "run", "test:live"]
            try:
                completed = subprocess.run(driver, cwd=root / "frontend", env=env, timeout=args.test_timeout)
                result = completed.returncode
                report["status"] = "PASS" if result == 0 else "FAIL"
            except subprocess.TimeoutExpired:
                report["status"] = "TIMEOUT"
                report["error"] = "Frontend driver exceeded the bounded test timeout"
                result = 124
    except Exception as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        print(f"Integration failed: {error}", file=sys.stderr)
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
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
