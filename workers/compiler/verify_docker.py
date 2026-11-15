#!/usr/bin/env python3
"""Real Docker compile-only acceptance. Missing Docker is a failure, never a skipped/pass result.

Build the repository worker image before running. The 120-second budget covers verification and
cleanup, not image download/build. No learner bytecode, annotation processor, shell or probe program
is executed: only the broker's fixed javac command runs inside containers. docker pause injects a
deadline failure without depending on a particular CPU's compiler speed.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

PROTOCOL = "typeforge-compile-v1"
ISOLATION = "container-per-job-v1"
MAX_RESPONSE = 196_608
VALID = "public class Main { public static <T> T first(java.util.List<T> values) { return values.get(0); } }"
INVALID = 'public class Main { void check() { java.util.List<String> a=java.util.List.of("x"); java.util.List<Object> b=a; } }'
RAW = 'public class Main { void check() { java.util.List a=new java.util.ArrayList(); a.add("x"); } }'


class VerificationFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise VerificationFailure(code)


def heavy_source():
    # Bounded fixed fixture, providing enough work to observe and pause/cancel a live javac container.
    source = "public class Main {" + "".join(f"int m{i}(){{return {i};}}" for i in range(1100)) + "}"
    require(len(source.encode()) <= 32768, "FIXTURE_SOURCE_LIMIT")
    return source


def output_source():
    # A single long type appears in 50 independent incompatibility diagnostics, exceeding 64 KiB.
    name = "LongDiagnosticType" + "A" * 1600
    return f"final class {name} {{}} public class Main {{ static {name} value(){{return null;}} void check(){{" + "".join(f"String v{i}=value();" for i in range(50)) + "}}"


class Verification:
    def __init__(self, image, report):
        self.image = image
        self.report_path = report
        self.started = time.monotonic()
        self.deadline = self.started + 105
        self.root = Path(tempfile.mkdtemp(prefix="typeforge-worker-ci-")).resolve()
        self.token = secrets.token_urlsafe(48)
        self.broker = None
        self.executor = ThreadPoolExecutor(max_workers=2)
        self.jobs = []
        self.url = None
        self.docker_ready = False
        self.report = {"schemaVersion": 1, "status": "FAILED", "requiresDocker": True,
                       "scope": "Actual container compile-only protocol, observed isolation/resource configuration, cancellation, deadline and bounded diagnostics; not an escape or model-quality audit.",
                       "checks": [], "cleanup": {"status": "NOT_STARTED"}}

    def remaining(self, maximum=10):
        left = self.deadline - time.monotonic()
        require(left > 0, "VERIFICATION_DEADLINE")
        return min(maximum, left)

    def command(self, args, maximum=8, check=True):
        try:
            result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=self.remaining(maximum), check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise VerificationFailure("DOCKER_COMMAND_UNAVAILABLE_OR_TIMEOUT") from error
        require(len(result.stdout) <= 1_048_576, "DOCKER_METADATA_LIMIT")
        if check:
            require(result.returncode == 0, "DOCKER_COMMAND_FAILED")
        return result

    def owned_containers(self):
        # All source mounts, including the startup readiness container, live under our exclusive TMPDIR.
        result = self.command(["docker", "ps", "-aq", "--filter", "name=typeforge-"], maximum=2)
        ids = result.stdout.decode().split()
        require(len(ids) <= 64 and all(re.fullmatch(r"[a-f0-9]{12,64}", value) for value in ids), "CONTAINER_INVENTORY_LIMIT")
        if not ids:
            return []
        result = self.command(["docker", "inspect", *ids], maximum=2, check=False)
        # A --rm container can disappear between ps and inspect; inspect still returns a JSON list.
        try:
            containers = json.loads(result.stdout)
        except (ValueError, UnicodeDecodeError) as error:
            raise VerificationFailure("CONTAINER_INSPECTION_FAILED") from error
        owned = []
        for container in containers:
            for mount in container.get("Mounts", []):
                source = mount.get("Source", "")
                if mount.get("Destination") == "/input" and source and Path(source).resolve().is_relative_to(self.root):
                    owned.append(container)
                    break
        return owned

    def http(self, path, body=None, expected=200, raw=None):
        data = raw if raw is not None else None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.url + path, data=data, headers={"Authorization": "Bearer " + self.token,
                          "Content-Type": "application/json", "Accept": "application/json"}, method="GET" if data is None else "POST")
        try:
            with urlopen(request, timeout=self.remaining(45)) as response:
                code, value = response.status, response.read(MAX_RESPONSE + 1)
                media_type = response.headers.get("Content-Type", "")
        except HTTPError as error:
            code, value = error.code, error.read(MAX_RESPONSE + 1)
            media_type = error.headers.get("Content-Type", "")
        require(code == expected, "UNEXPECTED_HTTP_STATUS")
        require(media_type.startswith("application/json"), "UNEXPECTED_RESPONSE_MEDIA_TYPE")
        require(len(value) <= MAX_RESPONSE and self.token.encode() not in value, "UNBOUNDED_OR_SECRET_RESPONSE")
        return json.loads(value)

    def request(self, source, checks=None):
        value = {"protocol": PROTOCOL, "jobId": str(uuid.uuid4()), "leaseToken": str(uuid.uuid4()),
                 "source": source, "sourceHash": hashlib.sha256(source.encode("utf-8")).hexdigest(), "checks": checks or []}
        self.jobs.append(value)
        return value

    def compile(self, request):
        value = self.http("/v1/compile", request)
        require(value.get("protocol") == PROTOCOL and value.get("isolation") == ISOLATION, "PROTOCOL_OR_ISOLATION_MISMATCH")
        for key in ("jobId", "leaseToken", "sourceHash"):
            require(value.get(key) == request[key], "RESULT_IDENTITY_MISMATCH")
        require(value.get("jdkVersion") == self.report["jdkVersion"], "JDK_CHANGED")
        require(isinstance(value.get("diagnostics"), list) and len(value["diagnostics"]) <= 100, "DIAGNOSTIC_COUNT_LIMIT")
        require(isinstance(value.get("checks"), list) and len(value["checks"]) <= 8, "CHECK_COUNT_LIMIT")
        require(isinstance(value.get("outputTruncated"), bool), "TRUNCATION_FLAG_MISSING")
        for diagnostic in value["diagnostics"]:
            require(len(diagnostic.get("message", "")) <= 1000, "DIAGNOSTIC_TEXT_LIMIT")
            require(self.token not in diagnostic.get("message", ""), "DECODED_SECRET_RESPONSE")
        return value

    def case(self, name, operation):
        start = time.monotonic()
        data = operation() or {}
        require(not self.owned_containers(), "CONTAINER_LEAK_AFTER_CASE")
        self.report["checks"].append({"name": name, "status": "PASSED", "durationMillis": round((time.monotonic() - start) * 1000), **data})

    def start(self):
        require(shutil.which("docker") is not None, "DOCKER_UNAVAILABLE")
        require(sys.platform.startswith("linux"), "LINUX_DOCKER_REQUIRED")
        # The production broker intentionally uses the dedicated host's normal Docker context.
        require(not os.environ.get("DOCKER_HOST"), "DEDICATED_DEFAULT_DOCKER_CONTEXT_REQUIRED")
        info = json.loads(self.command(["docker", "info", "--format", "{{json .}}"], maximum=10).stdout)
        self.docker_ready = True
        required = ("MemoryLimit", "SwapLimit", "CpuCfsQuota", "CpuCfsPeriod", "PidsLimit")
        require(all(info.get(key) is True for key in required), "HOST_RESOURCE_LIMITS_UNSUPPORTED")
        self.report["hostLimitsSupported"] = {key: True for key in required}
        self.report["dockerServerVersion"] = str(info.get("ServerVersion", "unknown"))[:64]
        image = self.command(["docker", "image", "inspect", "--format", "{{.Id}}", self.image]).stdout.decode().strip()
        require(bool(re.fullmatch(r"sha256:[a-f0-9]{64}", image)), "PINNED_WORKER_IMAGE_REQUIRED")
        self.report["imageId"] = image
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        self.url = f"http://127.0.0.1:{port}"
        environment = dict(os.environ, TYPEFORGE_COMPILER_TOKEN=self.token, TYPEFORGE_COMPILER_IMAGE=image,
                           TYPEFORGE_COMPILER_PORT=str(port), TYPEFORGE_COMPILER_BIND="127.0.0.1", TMPDIR=str(self.root))
        self.broker = subprocess.Popen([sys.executable, str(Path(__file__).with_name("server.py"))],
                                      env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL, start_new_session=True)
        ready_by = time.monotonic() + 22
        while time.monotonic() < ready_by:
            require(self.broker.poll() is None, "BROKER_STARTUP_FAILED")
            try:
                health = self.http("/health")
                require(health.get("ready") is True and health.get("protocol") == PROTOCOL and health.get("isolation") == ISOLATION, "BROKER_HEALTH_MISMATCH")
                require(health.get("imageId") == image and re.fullmatch(r"javac 17[.0-9+_a-zA-Z-]*", health.get("jdkVersion", "")), "BROKER_IMAGE_OR_JDK_MISMATCH")
                self.report["jdkVersion"] = health["jdkVersion"]
                return
            except (URLError, ConnectionError, TimeoutError):
                time.sleep(0.1)
        raise VerificationFailure("BROKER_READINESS_TIMEOUT")

    def verify_policy(self, container):
        host, config = container["HostConfig"], container["Config"]
        require(container.get("State", {}).get("Running") is True, "NO_RUNNING_CONTAINER")
        require(config.get("User") == "65534:65534" and host.get("Privileged") is False, "CONTAINER_USER_POLICY")
        require(host.get("ReadonlyRootfs") is True and host.get("NetworkMode") == "none" and host.get("IpcMode") == "none", "FILESYSTEM_NETWORK_IPC_POLICY")
        require("ALL" in host.get("CapDrop", []) and not host.get("CapAdd"), "CAPABILITY_POLICY")
        require(any(value.startswith("no-new-privileges") for value in host.get("SecurityOpt", [])), "PRIVILEGE_ESCALATION_POLICY")
        require(host.get("PidsLimit") == 64 and host.get("Memory") == 192 * 1024 * 1024 and host.get("MemorySwap") == 192 * 1024 * 1024 and host.get("NanoCpus") == 500_000_000, "RESOURCE_POLICY")
        tmpfs = host.get("Tmpfs", {}).get("/tmp", "")
        require(all(value in tmpfs.split(",") for value in ("noexec", "nosuid", "nodev", "size=64m")), "TMPFS_POLICY")
        mounts = container.get("Mounts", [])
        binds = [mount for mount in mounts if mount.get("Type") == "bind"]
        require(len(binds) == 1 and binds[0].get("Destination") == "/input" and binds[0].get("RW") is False, "HOST_MOUNT_POLICY")
        require(all(mount.get("Type") == "bind" or mount.get("Type") == "tmpfs" and mount.get("Destination") == "/tmp" for mount in mounts), "UNADMITTED_MOUNT")
        command = config.get("Cmd", [])
        require(command and command[0] == "javac" and all(flag in command for flag in ("-proc:none", "-Werror", "-Xlint:all", "/input/empty")), "COMPILE_ONLY_POLICY")
        require(self.token not in json.dumps(container), "CONTAINER_CREDENTIAL_LEAK")
        self.report["observedContainerPolicy"] = {"nonRoot": True, "network": "none", "rootFilesystem": "read-only",
            "capabilitiesDropped": "ALL", "noNewPrivileges": True, "memoryBytes": host["Memory"], "pids": host["PidsLimit"],
            "nanoCpus": host["NanoCpus"], "readonlySourceMount": True, "annotationProcessing": "disabled", "execution": "javac-only"}

    def live(self, future):
        end = time.monotonic() + 5
        while time.monotonic() < end:
            for container in self.owned_containers():
                if container.get("State", {}).get("Running"):
                    return container
            require(not future.done(), "FIXTURE_FINISHED_BEFORE_FAULT_INJECTION")
            time.sleep(0.025)
        raise VerificationFailure("RUNNING_CONTAINER_NOT_OBSERVED")

    def outcome(self, source, expected):
        result = self.compile(self.request(source))
        require(result.get("status") == expected, "UNEXPECTED_COMPILE_OUTCOME")
        if expected == "COMPILE_ERROR":
            require(any(d.get("severity") == "ERROR" for d in result["diagnostics"]), "MISSING_COMPILER_DIAGNOSTICS")
        return {"outcome": result["status"], "diagnosticCount": len(result["diagnostics"])}

    def hidden(self):
        checks = [{"id": "check0", "source": "static void verify(){ Integer x=Main.first(java.util.List.of(1)); }"},
                  {"id": "check1", "source": "static void verify(){ String x=Main.first(java.util.List.of(1)); }"}]
        result = self.compile(self.request(VALID, checks))
        require(result.get("status") == "SUCCEEDED" and len(result["checks"]) == 2 and not result["diagnostics"], "INCOMPLETE_HIDDEN_CONTRACT")
        by_id = {check["id"]: check for check in result["checks"]}
        require(by_id["check0"]["status"] == "SUCCEEDED" and not by_id["check0"]["diagnosticCodes"], "POSITIVE_CONTRACT_FAILED")
        require(by_id["check1"]["status"] == "COMPILE_ERROR" and by_id["check1"]["harnessOnly"] is True and "compiler.err.prob.found.req" in by_id["check1"]["diagnosticCodes"], "NEGATIVE_CONTRACT_FAILED")
        require("static void verify" not in json.dumps(result), "HIDDEN_SOURCE_EXPOSED")
        return {"positive": "SUCCEEDED", "negative": "COMPILE_ERROR", "negativeLocation": "harness-only"}

    def request_bounds(self):
        self.http("/v1/compile", self.request("字" * 11000), expected=400)
        self.http("/v1/compile", expected=400, raw=b" " * 131073)
        request = self.request(VALID, [{"id": "../../escape", "source": "void check(){}"}])
        self.http("/v1/compile", request, expected=400)
        return {"utf8Limit": "REJECTED", "bodyLimit": "REJECTED", "pathInjection": "REJECTED"}

    def cancellation(self):
        request = self.request(heavy_source())
        future = self.executor.submit(self.compile, request)
        container = self.live(future)
        self.verify_policy(container)
        result = self.http("/v1/cancel/" + request["jobId"], {"leaseToken": request["leaseToken"]})
        require(result.get("cancelRequested") is True, "CANCEL_NOT_ADMITTED")
        require(future.result(timeout=self.remaining(12))["status"] == "CANCELLED", "CANCELLATION_DID_NOT_WIN")
        return {"outcome": "CANCELLED", "actualRunningContainerObserved": True}

    def deadline_test(self):
        future = self.executor.submit(self.compile, self.request(heavy_source()))
        container = self.live(future)
        self.command(["docker", "pause", container["Id"]], maximum=2)
        require(future.result(timeout=self.remaining(12))["status"] == "TIMED_OUT", "WORKER_DEADLINE_NOT_ENFORCED")
        return {"outcome": "TIMED_OUT", "faultInjection": "paused-javac-container"}

    def output_bounds(self):
        result = self.compile(self.request(output_source()))
        require(result["status"] == "RESOURCE_LIMIT" and result["outputTruncated"] is True, "OUTPUT_LIMIT_NOT_ENFORCED")
        return {"outcome": "RESOURCE_LIMIT", "outputTruncated": True, "diagnosticCount": len(result["diagnostics"])}

    def cleanup(self):
        self.deadline = min(self.started + 120, time.monotonic() + 15)
        self.executor.shutdown(wait=False, cancel_futures=True)
        if self.broker is not None and self.broker.poll() is None:
            # Request-handler threads launch their own CLI children; inspect every thread in our broker.
            children = set()
            try:
                for task in Path(f"/proc/{self.broker.pid}/task").iterdir():
                    try:
                        children.update((task / "children").read_text().split())
                    except OSError:
                        pass
            except OSError:
                pass
            self.broker.terminate()
            for pid in children:
                try:
                    os.kill(int(pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                self.broker.wait(timeout=self.remaining(2))
            except subprocess.TimeoutExpired:
                self.broker.kill()
                self.broker.wait(timeout=self.remaining(1))
        if self.docker_ready:
            owned = self.owned_containers()
            if owned:
                self.command(["docker", "rm", "-f", *[container["Id"] for container in owned]], maximum=5)
            remaining = self.owned_containers()
            require(not remaining, "OWNED_CONTAINER_CLEANUP_FAILED")
        shutil.rmtree(self.root)
        self.report["cleanup"] = {"status": "COMPLETED", "ownedContainersRemaining": 0, "brokerStopped": self.broker is None or self.broker.poll() is not None}

    def run(self):
        def stop(_signal, _frame):
            raise VerificationFailure("VERIFICATION_INTERRUPTED_OR_DEADLINE")
        signal.signal(signal.SIGALRM, stop)
        signal.signal(signal.SIGTERM, stop)
        signal.alarm(105)
        try:
            self.start()
            self.case("valid-generics", lambda: self.outcome(VALID, "SUCCEEDED"))
            self.case("invalid-generics", lambda: self.outcome(INVALID, "COMPILE_ERROR"))
            self.case("hidden-positive-and-negative-contract", self.hidden)
            self.case("raw-types-strict-lint", lambda: self.outcome(RAW, "COMPILE_ERROR"))
            self.case("request-boundaries", self.request_bounds)
            self.case("live-isolation-policy-and-cancellation", self.cancellation)
            self.case("actual-worker-deadline", self.deadline_test)
            self.case("actual-output-boundary", self.output_bounds)
            self.report["status"] = "PASSED"
        except BaseException as error:
            self.report["failureCode"] = str(error) if isinstance(error, VerificationFailure) else "UNEXPECTED_" + type(error).__name__.upper()
        finally:
            signal.alarm(0)
            try:
                self.cleanup()
            except BaseException:
                self.report["status"] = "FAILED"
                self.report["cleanup"] = {"status": "FAILED", "failureCode": "CLEANUP_NOT_CONFIRMED"}
            self.report["durationMillis"] = round((time.monotonic() - self.started) * 1000)
            self.report_path.parent.mkdir(parents=True, exist_ok=True)
            self.report_path.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Compiler Docker verification: {self.report['status']}; {len(self.report['checks'])} completed checks; cleanup {self.report['cleanup']['status']}")
        return 0 if self.report["status"] == "PASSED" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="typeforge-compiler:jdk17", help="Prebuilt repository compiler image; never pulled by verification")
    parser.add_argument("--report", type=Path, default=Path(".work/compiler-docker-verification.json"))
    arguments = parser.parse_args()
    return Verification(arguments.image, arguments.report).run()


if __name__ == "__main__":
    raise SystemExit(main())
