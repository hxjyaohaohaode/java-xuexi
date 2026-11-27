#!/usr/bin/env python3
"""Trusted compiler broker. Untrusted source is only passed to a fresh restricted Docker container.

Run this on a dedicated worker host. Never place Docker socket access in the API service.
Both teaching languages run only inside the dedicated image.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL = "typeforge-compile-v1"
ISOLATION = "container-per-job-v1"
MAX_BODY = 131_072
MAX_OUTPUT = 65_536
MAX_SOURCE = 32_768
MAX_STDIN = 8192
MAX_RUN_OUTPUT = 16384
MAX_RUN_HEADER = 32768
RUN_PROTOCOL = "typeforge-run-v1"
EXECUTION_ENABLED = False
PYTHON_VERSION = ""
SLOTS = threading.BoundedSemaphore(2)
RUNNING: dict[tuple[str, str], threading.Event] = {}
RUNNING_LOCK = threading.Lock()
TOKEN = ""
IMAGE = ""
JDK_VERSION = ""
# javac -XDrawDiagnostics prefixes diagnostics without a source position with "- ".
# In particular, -Werror emits its rejecting ERROR this way after positioned warnings.
DIAGNOSTIC = re.compile(r"^(?:(?:/input/)?([^:]+\.java):(\d+):(\d+): |- )?(compiler\.(err|warn|note)\.[A-Za-z0-9_.]+)(?:: (.*))?$")


def unique_json(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


def bounded_json(raw: bytes):
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json)


def validate_request(value):
    if not isinstance(value, dict) or set(value) != {"protocol", "jobId", "leaseToken", "source", "sourceHash", "checks"}:
        raise ValueError("invalid request shape")
    if value["protocol"] != PROTOCOL:
        raise ValueError("unsupported protocol")
    for key in ("jobId", "leaseToken"):
        if not isinstance(value[key], str) or str(uuid.UUID(value[key])) != value[key]:
            raise ValueError("invalid task identity")
    source = value["source"]
    if not isinstance(source, str) or not source.strip() or len(source.encode("utf-8")) > MAX_SOURCE or "\0" in source:
        raise ValueError("invalid source size")
    if value["sourceHash"] != hashlib.sha256(source.encode("utf-8")).hexdigest():
        raise ValueError("source hash mismatch")
    if not isinstance(value["checks"], list) or len(value["checks"]) > 8:
        raise ValueError("too many checks")
    seen = set()
    for check in value["checks"]:
        if not isinstance(check, dict) or set(check) != {"id", "source"}:
            raise ValueError("invalid check")
        if not isinstance(check["id"], str) or not re.fullmatch(r"check[0-7]", check["id"]) or check["id"] in seen:
            raise ValueError("invalid check identity")
        seen.add(check["id"])
        if not isinstance(check["source"], str) or not check["source"].strip() or len(check["source"].encode("utf-8")) > 8192 or "\0" in check["source"]:
            raise ValueError("invalid check source")
    return value


def sandbox_command(name: str, directory: Path) -> list[str]:
    # The immutable image id is resolved once at startup. No command/flag/path comes from JSON.
    return ["docker", "run", "--rm", "--pull=never", "--name", name, "--network=none", "--log-driver=none", "--read-only", "--user=65534:65534",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=64", "--memory=192m", "--memory-swap=192m",
            "--cpus=0.5", "--ulimit=nofile=128:128", "--ulimit=fsize=16777216:16777216", "--ipc=none",
            "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777", "--mount", f"type=bind,src={directory},dst=/input,readonly",
            "--workdir=/tmp", IMAGE]


def docker_command(name: str, directory: Path, files: list[str]) -> list[str]:
    return sandbox_command(name, directory) + ["javac", "-J-Xmx96m", "-J-XX:ActiveProcessorCount=1", "-J-Duser.language=en",
            "-J-Duser.country=US", "-J-Dfile.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8", "-proc:none",
            "-Xlint:all", "-Werror", "-XDrawDiagnostics", "-Xmaxerrs", "50", "-Xmaxwarns", "50", "-implicit:none",
            "-sourcepath", "/input", "-classpath", "/input/empty", "-d", "/tmp", *["/input/" + name for name in files]]


def remove_container(name: str):
    try:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass


def kill_process_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass  # Completion can race the cancellation request.


def run_bounded(command: list[str], cancel: threading.Event, deadline: float, container: str, output_limit=MAX_OUTPUT, framed=False):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               start_new_session=True, env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": os.environ.get("HOME", "/tmp")})
    output = bytearray()
    state = None
    byte_limit = MAX_RUN_HEADER if framed else output_limit
    reading_header = framed
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if cancel.is_set():
                    state = "CANCELLED"
                    break
                if time.monotonic() >= deadline:
                    state = "TIMED_OUT"
                    break
                for key, _ in selector.select(timeout=0.05):
                    part = os.read(key.fd, 8192)
                    if not part:
                        selector.unregister(key.fileobj)
                        continue
                    if reading_header:
                        newline = part.find(b"\n")
                        if newline >= 0:
                            header_size = len(output) + newline + 1
                            if header_size > MAX_RUN_HEADER:
                                state = "INFRA_ERROR"
                                break
                            byte_limit = header_size + output_limit
                            reading_header = False
                    if len(output) + len(part) > byte_limit:
                        output.extend(part[:byte_limit - len(output)])
                        state = "INFRA_ERROR" if reading_header else "RESOURCE_LIMIT"
                        break
                    output.extend(part)
                if state:
                    break
        if state:
            kill_process_group(process)
        try:
            returncode = process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            kill_process_group(process)
            returncode = process.wait(timeout=3)
            state = state or "TIMED_OUT"
        return state, returncode, output.decode("utf-8", errors="replace")
    finally:
        if process.poll() is None:
            kill_process_group(process)
        if process.stdout:
            process.stdout.close()
        remove_container(container)


def diagnostics(output: str):
    rows = []
    files = []
    for line in output.splitlines():
        match = DIAGNOSTIC.fullmatch(line.strip())
        if not match:
            continue
        file, row, column, code, category, message = match.groups()
        files.append(file)
        rows.append({"code": code, "severity": {"err": "ERROR", "warn": "WARNING", "note": "NOTE"}[category],
                     "line": int(row) if row else -1, "column": int(column) if column else -1,
                     "message": (message or code).replace("/input/", "").replace("/tmp/", "")[:1000]})
    return rows[:100], files[:100]


def compile_one(source: str, check, cancelled: threading.Event, job_deadline: float):
    with tempfile.TemporaryDirectory(prefix="typeforge-compile-") as temporary:
        folder = Path(temporary)
        folder.chmod(0o755)
        (folder / "empty").mkdir(mode=0o555)
        (folder / "Main.java").write_text(source, encoding="utf-8")
        (folder / "Main.java").chmod(0o444)
        files = ["Main.java"]
        harness = None
        if check:
            harness = "Probe_" + check["id"] + ".java"
            (folder / harness).write_text("final class Probe_" + check["id"] + " {\n" + check["source"] + "\n}\n", encoding="utf-8")
            (folder / harness).chmod(0o444)
            files.append(harness)
        name = "typeforge-compile-" + uuid.uuid4().hex
        try:
            state, code, output = run_bounded(docker_command(name, folder, files), cancelled,
                                             min(job_deadline, time.monotonic() + 6), name)
        except OSError:
            return "INFRA_ERROR", [], [], False
        rows, locations = diagnostics(output)
        if state:
            return state, rows, locations, len(output.encode("utf-8")) >= MAX_OUTPUT
        if code == 0:
            if any(row["severity"] != "NOTE" for row in rows):
                return "INFRA_ERROR", rows, locations, False
            return "SUCCEEDED", rows, locations, False
        if code in (-9, 137):
            return "RESOURCE_LIMIT", rows, locations, False
        # Docker errors, bad invocation and unknown compiler crashes must never become a wrong answer.
        if code != 1 or not any(row["severity"] == "ERROR" for row in rows):
            return "INFRA_ERROR", rows, locations, False
        return "COMPILE_ERROR", rows, locations, False


def compile_request(request, cancelled: threading.Event):
    deadline = time.monotonic() + 38
    status, rows, _, truncated = compile_one(request["source"], None, cancelled, deadline)
    result = {"protocol": PROTOCOL, "isolation": ISOLATION, "jobId": request["jobId"], "leaseToken": request["leaseToken"],
              "sourceHash": request["sourceHash"], "jdkVersion": JDK_VERSION, "status": status,
              "diagnostics": rows, "outputTruncated": truncated, "checks": []}
    if status != "SUCCEEDED":
        return result
    for check in request["checks"]:
        state, hidden_rows, locations, cut = compile_one(request["source"], check, cancelled, deadline)
        errors = [row["code"] for row in hidden_rows if row["severity"] == "ERROR"]
        error_locations = [file for row, file in zip(hidden_rows, locations) if row["severity"] == "ERROR"]
        result["checks"].append({"id": check["id"], "status": state, "diagnosticCodes": errors,
                                 "harnessOnly": bool(error_locations) and all(file == "Probe_" + check["id"] + ".java" for file in error_locations)})
        if cut or state not in {"SUCCEEDED", "COMPILE_ERROR"}:
            # Return a terminal infrastructure state, not an apparently complete success with missing checks.
            result["status"] = state if state not in {"SUCCEEDED", "COMPILE_ERROR"} else "RESOURCE_LIMIT"
            result["outputTruncated"] = cut
            break
    return result


def validate_run(value):
    if not isinstance(value, dict) or set(value) != {"protocol", "jobId", "leaseToken", "source", "sourceHash", "language", "stdin"}:
        raise ValueError("invalid run request")
    if value["protocol"] != RUN_PROTOCOL or value["language"] not in ("JAVA", "PYTHON"):
        raise ValueError("unsupported runtime")
    common = {key: value[key] for key in ("jobId", "leaseToken", "source", "sourceHash")}
    validate_request(dict(common, protocol=PROTOCOL, checks=[]))
    stdin = value["stdin"]
    if not isinstance(stdin, str) or "\0" in stdin or len(stdin.encode("utf-8")) > MAX_STDIN:
        raise ValueError("invalid stdin")
    return value


def execute_request(request, cancelled):
    language = request["language"]
    result = {"protocol": RUN_PROTOCOL, "isolation": ISOLATION, "jobId": request["jobId"],
              "leaseToken": request["leaseToken"], "sourceHash": request["sourceHash"], "language": language,
              "stdinHash": hashlib.sha256(request["stdin"].encode()).hexdigest(),
              "jdkVersion": JDK_VERSION, "runtimeVersion": PYTHON_VERSION if language == "PYTHON" else JDK_VERSION,
              "diagnostics": [], "checks": [], "output": "", "outputTruncated": False, "status": "INFRA_ERROR"}
    with tempfile.TemporaryDirectory(prefix="typeforge-run-") as temporary:
        folder = Path(temporary)
        folder.chmod(0o755)
        (folder / "empty").mkdir(mode=0o555)
        for name, content in (("Main.java" if language == "JAVA" else "main.py", request["source"]),
                              ("stdin.txt", request["stdin"])):
            path = folder / name
            path.write_bytes(content.encode("utf-8"))
            path.chmod(0o444)
        name = "typeforge-run-" + uuid.uuid4().hex
        command = sandbox_command(name, folder) + ["timeout", "--signal=KILL", "18s", "python3", "-I", "-B", "/opt/typeforge/runner.py", language]
        state, code, output = run_bounded(command, cancelled, time.monotonic() + 12, name, MAX_RUN_OUTPUT, framed=True)
    result["status"] = state or {0: "SUCCEEDED", 10: "RUNTIME_ERROR", 11: "COMPILE_ERROR",
                                12: "RESOURCE_LIMIT", 137: "RESOURCE_LIMIT"}.get(code, "INFRA_ERROR")
    # Docker invocation errors can contain host information. Only public program output is exposed.
    if result["status"] == "INFRA_ERROR":
        return result
    header, newline, body = output.partition("\n")
    if not newline and result["status"] in {"TIMED_OUT", "CANCELLED", "RESOURCE_LIMIT"}:
        return result  # The process may have been stopped before compilation completed.
    try:
        info = json.loads(header)
        if not isinstance(info, dict) or set(info) != {"compiler"} or not isinstance(info["compiler"], str):
            raise ValueError("invalid runtime header")
    except (ValueError, TypeError):
        result["status"] = "INFRA_ERROR"
        return result
    result["output"] = body
    result["outputTruncated"] = state == "RESOURCE_LIMIT"
    rows = diagnostics(info["compiler"])[0]
    if result["status"] == "COMPILE_ERROR" and not any(row["severity"] == "ERROR" for row in rows):
        rows.append({"code": "source.compile.error", "severity": "ERROR", "line": -1, "column": -1,
                     "message": (info["compiler"] or "源码未通过编译，请查看解释器输出。")[:1000]})
    result["diagnostics"] = rows
    return result


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_):
        pass  # URLs, authentication tokens and learner source are never access-logged.

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def authorized(self):
        return hmac.compare_digest(self.headers.get("Authorization", "").encode("utf-8"), ("Bearer " + TOKEN).encode("utf-8"))

    def body(self):
        if self.headers.get("Transfer-Encoding") or not self.headers.get("Content-Type", "").startswith("application/json"):
            raise ValueError("invalid encoding")
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BODY:
            raise ValueError("invalid body size")
        return bounded_json(self.rfile.read(length))

    def do_GET(self):
        if not self.authorized():
            return self.reply(401, {"error": "UNAUTHORIZED"})
        if self.path != "/health":
            return self.reply(404, {"error": "NOT_FOUND"})
        self.reply(200, {"protocol": PROTOCOL, "isolation": ISOLATION, "ready": True, "imageId": IMAGE, "jdkVersion": JDK_VERSION,
                         "executionEnabled": EXECUTION_ENABLED, "pythonVersion": PYTHON_VERSION})

    def do_POST(self):
        if not self.authorized():
            return self.reply(401, {"error": "UNAUTHORIZED"})
        try:
            value = self.body()
        except (ValueError, UnicodeDecodeError, OSError, RecursionError):
            return self.reply(400, {"error": "INVALID_REQUEST"})
        if self.path.startswith("/v1/cancel/"):
            job = self.path.removeprefix("/v1/cancel/")
            try:
                if not isinstance(value, dict) or set(value) != {"leaseToken"} or str(uuid.UUID(job)) != job:
                    raise ValueError("invalid cancellation")
                lease = str(uuid.UUID(value["leaseToken"]))
            except (ValueError, TypeError, AttributeError):
                return self.reply(400, {"error": "INVALID_REQUEST"})
            with RUNNING_LOCK:
                event = RUNNING.get((job, lease))
                if event:
                    event.set()
            return self.reply(200, {"cancelRequested": bool(event)})
        execution = self.path == "/v1/run"
        if execution and not EXECUTION_ENABLED:
            return self.reply(503, {"error": "EXECUTION_DISABLED"})
        if not execution and self.path != "/v1/compile":
            return self.reply(404, {"error": "NOT_FOUND"})
        try:
            request = validate_run(value) if execution else validate_request(value)
        except (ValueError, TypeError, AttributeError):
            return self.reply(400, {"error": "INVALID_REQUEST"})
        if not SLOTS.acquire(blocking=False):
            return self.reply(429, {"error": "WORKER_BUSY"})
        key = request["jobId"], request["leaseToken"]
        cancelled = threading.Event()
        try:
            with RUNNING_LOCK:
                if key in RUNNING:
                    return self.reply(409, {"error": "DUPLICATE_LEASE"})
                RUNNING[key] = cancelled
            self.reply(200, execute_request(request, cancelled) if execution else compile_request(request, cancelled))
        except Exception:
            self.reply(503, {"error": "WORKER_FAILURE"})
        finally:
            with RUNNING_LOCK:
                if RUNNING.get(key) is cancelled:
                    del RUNNING[key]
            SLOTS.release()


class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16
    allow_reuse_address = True
    connections = threading.BoundedSemaphore(16)

    def process_request(self, request, client_address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()


def require_runtime_host():
    try:
        raw = subprocess.check_output(["docker", "info", "--format", "{{json .}}"], timeout=10)
        info = json.loads(raw)
    except (OSError, subprocess.SubprocessError, ValueError):
        raise SystemExit("Docker host readiness check failed") from None
    limits = ("MemoryLimit", "SwapLimit", "CpuCfsQuota", "CpuCfsPeriod", "PidsLimit")
    if (info.get("OSType") != "linux" or not all(info.get(key) is True for key in limits) or
        not any(option.startswith("name=seccomp") for option in info.get("SecurityOptions", []))):
        raise SystemExit("Program execution requires a Linux Docker host with cgroup limits and seccomp")


def main():
    global TOKEN, IMAGE, JDK_VERSION, PYTHON_VERSION, EXECUTION_ENABLED
    EXECUTION_ENABLED = os.environ.get("TYPEFORGE_EXECUTION_ENABLED", "").lower() == "true"
    TOKEN = os.environ.get("TYPEFORGE_COMPILER_TOKEN", "")
    if len(TOKEN) < 32 or any(ord(char) < 32 or ord(char) == 127 for char in TOKEN):
        raise SystemExit("Set TYPEFORGE_COMPILER_TOKEN to at least 32 random characters")
    if not shutil.which("docker"):
        raise SystemExit("Docker is required; compiling on the broker host is forbidden")
    if EXECUTION_ENABLED:
        require_runtime_host()
    image = os.environ.get("TYPEFORGE_COMPILER_IMAGE", "typeforge-compiler:jdk17")
    try:
        IMAGE = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", image], text=True, timeout=10).strip()
    except (OSError, subprocess.SubprocessError):
        raise SystemExit("Build and inspect the dedicated compiler image before starting") from None
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", IMAGE):
        raise SystemExit("A locally pinned immutable compiler image id is required")
    # Fail closed if the container policy cannot launch. This is a readiness check, not an escape audit.
    with tempfile.TemporaryDirectory(prefix="typeforge-check-") as temporary:
        path = Path(temporary)
        path.chmod(0o755)
        command = docker_command("typeforge-check-" + uuid.uuid4().hex, path, [])
        command = command[:command.index("javac") + 1] + ["-version"]
        state, code, version = run_bounded(command, threading.Event(), time.monotonic() + 15, command[command.index("--name") + 1])
        if state or code or not re.fullmatch(r"javac 17[.0-9+_a-zA-Z-]*\s*", version):
            raise SystemExit("Restricted JDK17 container readiness check failed")
        JDK_VERSION = version.strip()
        if EXECUTION_ENABLED:
            name = "typeforge-check-" + uuid.uuid4().hex
            state, code, version = run_bounded(sandbox_command(name, path) + ["python3", "-I", "-B", "-c",
                "import sys; import pathlib; assert pathlib.Path('/opt/typeforge/runner.py').is_file(); print('Python', sys.version.split()[0])"],
                threading.Event(), time.monotonic() + 15, name)
            if state or code or not re.fullmatch(r"Python 3[.][0-9.]+\s*", version):
                raise SystemExit("Restricted Python container readiness check failed")
            PYTHON_VERSION = version.strip()
    host = os.environ.get("TYPEFORGE_COMPILER_BIND", "127.0.0.1")
    port = int(os.environ.get("TYPEFORGE_COMPILER_PORT", "8091"))
    print(f"Compiler broker ready on {host}:{port}; {JDK_VERSION}; {IMAGE}", flush=True)
    def shutdown(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, shutdown)
    with BoundedServer((host, port), Handler) as server:
        try:
            server.serve_forever(poll_interval=0.25)
        except KeyboardInterrupt:
            pass
        finally:
            with RUNNING_LOCK:
                for cancelled in RUNNING.values():
                    cancelled.set()
            # Let request handlers remove their own containers before leaving the broker process.
            deadline = time.monotonic() + 6
            while time.monotonic() < deadline:
                with RUNNING_LOCK:
                    if not RUNNING:
                        break
                time.sleep(0.05)


if __name__ == "__main__":
    main()
