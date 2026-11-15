#!/usr/bin/env python3
"""Trusted compiler broker. Untrusted source is only passed to a fresh restricted Docker container.

Run this on a dedicated worker host. Never place Docker socket access in the API service.
There is deliberately no host-javac or execute-user-bytecode fallback.
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
SLOTS = threading.BoundedSemaphore(2)
RUNNING: dict[tuple[str, str], threading.Event] = {}
RUNNING_LOCK = threading.Lock()
TOKEN = ""
IMAGE = ""
JDK_VERSION = ""
DIAGNOSTIC = re.compile(r"^(?:(?:/input/)?([^:]+\.java):(\d+):(\d+): )?(compiler\.(err|warn|note)\.[A-Za-z0-9_.]+)(?:: (.*))?$")


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


def docker_command(name: str, directory: Path, files: list[str]) -> list[str]:
    # The immutable image id is resolved once at startup. No command/flag/path comes from JSON.
    return ["docker", "run", "--rm", "--pull=never", "--name", name, "--network=none", "--read-only", "--user=65534:65534",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=64", "--memory=192m", "--memory-swap=192m",
            "--cpus=0.5", "--ulimit=nofile=128:128", "--ulimit=fsize=16777216:16777216", "--ipc=none",
            "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777", "--mount", f"type=bind,src={directory},dst=/input,readonly",
            "--workdir=/tmp", IMAGE, "javac", "-J-Xmx96m", "-J-XX:ActiveProcessorCount=1", "-J-Duser.language=en",
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


def run_bounded(command: list[str], cancel: threading.Event, deadline: float, container: str):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               start_new_session=True, env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": os.environ.get("HOME", "/tmp")})
    output = bytearray()
    state = None
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
                    elif len(output) + len(part) > MAX_OUTPUT:
                        output.extend(part[:MAX_OUTPUT - len(output)])
                        state = "RESOURCE_LIMIT"
                        break
                    else:
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
        self.reply(200, {"protocol": PROTOCOL, "isolation": ISOLATION, "ready": True, "imageId": IMAGE, "jdkVersion": JDK_VERSION})

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
        if self.path != "/v1/compile":
            return self.reply(404, {"error": "NOT_FOUND"})
        try:
            request = validate_request(value)
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
            self.reply(200, compile_request(request, cancelled))
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


def main():
    global TOKEN, IMAGE, JDK_VERSION
    TOKEN = os.environ.get("TYPEFORGE_COMPILER_TOKEN", "")
    if len(TOKEN) < 32 or any(ord(char) < 32 or ord(char) == 127 for char in TOKEN):
        raise SystemExit("Set TYPEFORGE_COMPILER_TOKEN to at least 32 random characters")
    if not shutil.which("docker"):
        raise SystemExit("Docker is required; compiling on the broker host is forbidden")
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
    host = os.environ.get("TYPEFORGE_COMPILER_BIND", "127.0.0.1")
    port = int(os.environ.get("TYPEFORGE_COMPILER_PORT", "8091"))
    print(f"Compiler broker ready on {host}:{port}; {JDK_VERSION}; {IMAGE}", flush=True)
    BoundedServer((host, port), Handler).serve_forever(poll_interval=0.25)


if __name__ == "__main__":
    main()
