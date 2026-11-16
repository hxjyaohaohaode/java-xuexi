"""Offline orchestration regressions; these fixtures are not browser or backend evidence."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock


_spec = importlib.util.spec_from_file_location(
    "typeforge_browser_runtime_under_test", Path(__file__).with_name("browser_runtime.py")
)
runtime = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(runtime)
verification_inputs = sys.modules[runtime.capture_inputs.__module__]


class Response:
    def __init__(self, status, body=None):
        self.status = status
        self.body = json.dumps(body or {}).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, limit):
        return self.body[:limit]


class Process:
    def __init__(self, on_stop=None):
        self.returncode = None
        self.terminated = False
        self.on_stop = on_stop

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True

    def wait(self, timeout):
        self.returncode = 0
        if self.on_stop:
            callback, self.on_stop = self.on_stop, None
            callback()
        return self.returncode


class BrowserRuntimeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="typeforge-browser-gate-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.jar = self.root / "backend" / "target" / "fixture.jar"
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b"offline immutable application fixture")
        self.original_hash = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.source = self.root / "frontend" / "src" / "index.ts"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("export const fixture = 'original';\n", encoding="utf-8")
        (self.root / "scripts").mkdir()
        for script in ("browser_runtime.py", "verification_inputs.py"):
            (self.root / "scripts" / script).write_text("# Offline source inventory fixture\n", encoding="utf-8")
        (self.root / ".gitignore").write_text(".work/\nbackend/target/\nfrontend/test-results/\n", encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def change_jar(self):
        self.jar.write_bytes(b"a different packaged application")

    def run_gate(self, *, driver="browser", before_first_start=None, first_stop=None,
                 after_driver=None, log_close_failure=False, roles=("LEARNER",),
                 write_driver_report=True, driver_status="PASS", stale_report=False):
        launches, registrations = [], []
        real_open = Path.open
        real_run, real_popen = subprocess.run, subprocess.Popen
        report_name = "browser-report.json" if driver == "browser" else "live-dom-report.json"
        driver_report = self.root / "frontend" / "test-results" / report_name
        if stale_report:
            driver_report.parent.mkdir(parents=True, exist_ok=True)
            driver_report.write_text(json.dumps({"status": "PASS", "scope": "stale fixture"}), encoding="utf-8")

        @contextlib.contextmanager
        def log_context(path, *args, **kwargs):
            with real_open(path, *args, **kwargs) as handle:
                if before_first_start:
                    before_first_start()
                yield handle
            if log_close_failure:
                raise OSError("Offline fixture: log finalization failed after driver completion")

        def open_path(path, *args, **kwargs):
            if path.name == f"{driver}-server.log" and args and args[0] == "w":
                return log_context(path, *args, **kwargs)
            return real_open(path, *args, **kwargs)

        def launch(command, **kwargs):
            # The genuine input-manifest helper may inspect only this temporary Git inventory.
            if command[0] == "git":
                self.assertEqual(self.root, Path(kwargs["cwd"]))
                return real_popen(command, **kwargs)
            self.assertEqual("offline-java", command[0])
            process = Process(first_stop if not launches else None)
            launches.append({"command": list(command), "env": kwargs["env"].copy(),
                             "cwd": kwargs["cwd"], "process": process})
            return process

        def request_fixture(request, **_):
            if isinstance(request, str) and request.endswith("/api/v1/auth/csrf"):
                return Response(200, {"headerName": "X-CSRF-TOKEN", "token": "offline-csrf-token"})
            if isinstance(request, runtime.urllib.request.Request) and request.full_url.endswith("/api/v1/auth/register"):
                registrations.append({"method": request.method, "body": json.loads(request.data),
                                      "headers": {k.lower(): v for k, v in request.header_items()}})
                return Response(201, {"roles": list(roles)})
            raise AssertionError(f"Unexpected offline HTTP request: {request}")

        def run_driver(command, **kwargs):
            if write_driver_report:
                driver_report.write_text(
                    json.dumps({"status": driver_status, "scope": "offline process fixture only"}), encoding="utf-8"
                )
            if after_driver:
                after_driver()
            return subprocess.CompletedProcess(command, 0)

        driver_call = mock.Mock(side_effect=run_driver)

        def run_process(command, **kwargs):
            if command[0] == "git":
                self.assertEqual(self.root, Path(kwargs["cwd"]))
                return real_run(command, **kwargs)
            return driver_call(command, **kwargs)

        inherited = {name: "must-not-reach-the-application" for name in (
            "TYPEFORGE_TUTOR_PROVIDER_URL", "TYPEFORGE_TUTOR_API_KEY", "TYPEFORGE_TUTOR_MODEL",
            "TYPEFORGE_EDITOR_USERS", "TYPEFORGE_ADMIN_USERS", "TYPEFORGE_TEST_EDITOR_AUTHOR",
            "TYPEFORGE_TEST_EDITOR_REVIEWER", "TYPEFORGE_TEST_EDITOR_PASSWORD", "TYPEFORGE_TEST_ADMIN_USERNAME")}
        socket = mock.MagicMock()
        socket.__enter__.return_value = socket
        socket.getsockname.return_value = ("127.0.0.1", 37123)
        opener = mock.Mock()
        opener.open.side_effect = request_fixture
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(runtime, "__file__", str(self.root / "scripts" / "browser_runtime.py")))
            stack.enter_context(mock.patch.object(verification_inputs, "ROOT", self.root))
            stack.enter_context(mock.patch.object(sys, "argv", ["browser_runtime.py", "--jar", str(self.jar),
                "--java", "offline-java", "--driver", driver, "--startup-timeout", "1", "--test-timeout", "1"]))
            stack.enter_context(mock.patch.dict(os.environ, inherited, clear=True))
            stack.enter_context(mock.patch.object(Path, "open", open_path))
            stack.enter_context(mock.patch.object(runtime.socket, "socket", return_value=socket))
            stack.enter_context(mock.patch.object(runtime.urllib.request, "urlopen", return_value=Response(200)))
            stack.enter_context(mock.patch.object(runtime.urllib.request, "build_opener", return_value=opener))
            stack.enter_context(mock.patch.object(runtime.subprocess, "Popen", side_effect=launch))
            stack.enter_context(mock.patch.object(runtime.subprocess, "run", side_effect=run_process))
            stack.enter_context(mock.patch.object(runtime.shutil, "which", return_value="offline-npm"))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            result = runtime.main()

        report_path = self.root / "frontend" / "test-results" / f"{driver}-runtime-report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(result, report["exitCode"])
        self.assertEqual([], list((self.root / ".work").iterdir()), "Disposable application database must be removed")
        for item in launches:
            self.assertTrue(item["process"].terminated, "Every started backend must be stopped")
            self.assertIsNotNone(item["process"].poll())
        return types.SimpleNamespace(result=result, report=report, launches=launches,
                                     registrations=registrations, driver=driver_call,
                                     inherited=inherited, driver_report=driver_report)

    def assert_changed_package_rejected(self, outcome):
        self.assertNotEqual(0, outcome.result)
        self.assertEqual("FAIL", outcome.report["status"])
        self.assertFalse(outcome.report["jarUnchanged"])
        self.assertEqual(self.original_hash, outcome.report["jarSha256"])
        self.assertIn("Packaged application changed", outcome.report["error"])

    def test_changed_jar_is_rejected_before_first_boot(self):
        outcome = self.run_gate(before_first_start=self.change_jar)
        self.assert_changed_package_rejected(outcome)
        self.assertEqual([], outcome.launches)
        outcome.driver.assert_not_called()

    def test_changed_jar_between_boots_does_not_start_second_backend(self):
        outcome = self.run_gate(first_stop=self.change_jar)
        self.assert_changed_package_rejected(outcome)
        self.assertEqual(1, len(outcome.launches))
        self.assertEqual(3, len(outcome.registrations))
        outcome.driver.assert_not_called()

    def test_successful_driver_cannot_pass_after_jar_changes(self):
        outcome = self.run_gate(after_driver=self.change_jar)
        self.assert_changed_package_rejected(outcome)
        self.assertEqual(2, len(outcome.launches))
        outcome.driver.assert_called_once()

    def test_exception_after_successful_driver_forces_nonzero_exit(self):
        outcome = self.run_gate(log_close_failure=True)
        outcome.driver.assert_called_once()
        self.assertNotEqual(0, outcome.result)
        self.assertEqual("FAIL", outcome.report["status"])
        self.assertTrue(outcome.report["jarUnchanged"])
        self.assertIn("log finalization failed after driver completion", outcome.report["error"])

    def test_registration_with_privileged_role_cannot_reach_second_boot(self):
        outcome = self.run_gate(roles=("LEARNER", "EDITOR"))
        self.assertNotEqual(0, outcome.result)
        self.assertEqual("FAIL", outcome.report["status"])
        self.assertTrue(outcome.report["jarUnchanged"])
        self.assertIn("ordinary learner boundary", outcome.report["error"])
        self.assertEqual(1, len(outcome.launches))
        self.assertEqual(1, len(outcome.registrations))
        outcome.driver.assert_not_called()

    def test_zero_driver_exit_without_new_report_cannot_reuse_old_pass(self):
        for driver in ("browser", "dom"):
            with self.subTest(driver=driver):
                outcome = self.run_gate(driver=driver, write_driver_report=False, stale_report=True)
                outcome.driver.assert_called_once()
                self.assertNotEqual(0, outcome.result)
                self.assertEqual("FAIL", outcome.report["status"])
                self.assertIn("fresh complete PASS report", outcome.report["error"])
                self.assertFalse(outcome.driver_report.exists(), "The previous driver's report must be removed")
                self.assertNotIn("driverReportSha256", outcome.report)

    def test_fresh_failed_driver_report_is_rejected_despite_zero_exit(self):
        outcome = self.run_gate(driver_status="FAIL")
        self.assertNotEqual(0, outcome.result)
        self.assertEqual("FAIL", outcome.report["status"])
        self.assertIn("fresh complete PASS report", outcome.report["error"])
        self.assertNotIn("driverReportSha256", outcome.report)

    def test_changed_frontend_inputs_reject_success_with_unchanged_jar(self):
        before = hashlib.sha256(self.source.read_bytes()).hexdigest()
        outcome = self.run_gate(after_driver=lambda: self.source.write_text(
            "export const fixture = 'changed during execution';\n", encoding="utf-8"))
        self.assertNotEqual(0, outcome.result)
        self.assertEqual("FAIL", outcome.report["status"])
        self.assertTrue(outcome.report["jarUnchanged"])
        self.assertFalse(outcome.report["inputsUnchanged"])
        self.assertIn("Verification inputs changed during execution: frontend", outcome.report["error"])
        self.assertEqual(before, outcome.report["verificationInputs"]["files"]["frontend/src/index.ts"])
        self.assertNotEqual(verification_inputs.capture_inputs("frontend", ("scripts/browser_runtime.py",), root=self.root),
                            outcome.report["verificationInputs"])

    def test_both_drivers_keep_two_boot_authorization_and_current_hash(self):
        for driver in ("browser", "dom"):
            with self.subTest(driver=driver):
                outcome = self.run_gate(driver=driver)
                self.assertEqual(0, outcome.result)
                self.assertEqual("PASS", outcome.report["status"])
                self.assertTrue(outcome.report["jarUnchanged"])
                self.assertTrue(outcome.report["inputsUnchanged"])
                self.assertEqual(hashlib.sha256(self.jar.read_bytes()).hexdigest(), outcome.report["jarSha256"])
                self.assertEqual(hashlib.sha256(outcome.driver_report.read_bytes()).hexdigest(), outcome.report["driverReportSha256"])
                self.assertEqual(verification_inputs.capture_inputs("frontend", ("scripts/browser_runtime.py",), root=self.root),
                                 outcome.report["verificationInputs"])
                self.assertEqual(2, outcome.report["editorialFixtureRegistered"])
                self.assertEqual(1, outcome.report["administratorFixtureRegistered"])
                self.assertEqual(2, len(outcome.launches))
                first, second = outcome.launches
                self.assertEqual(first["cwd"], second["cwd"])
                self.assertIn("--typeforge.bootstrap.editor-users=", first["command"])
                self.assertIn("--typeforge.bootstrap.admin-users=", first["command"])
                self.assertEqual(3, len(outcome.registrations))
                users = [registration["body"]["username"] for registration in outcome.registrations]
                self.assertEqual(3, len(set(users)))
                self.assertIn("--typeforge.bootstrap.editor-users=" + ",".join(users[:2]), second["command"])
                self.assertIn("--typeforge.bootstrap.admin-users=" + users[2], second["command"])
                for registration in outcome.registrations:
                    self.assertEqual("POST", registration["method"])
                    self.assertEqual({"username", "password"}, set(registration["body"]))
                    self.assertEqual("offline-csrf-token", registration["headers"]["x-csrf-token"])
                for launch in outcome.launches:
                    self.assertTrue(set(outcome.inherited).isdisjoint(launch["env"]))
                    self.assertIn("--typeforge.compiler.enabled=false", launch["command"])
                outcome.driver.assert_called_once()
                driver_environment = outcome.driver.call_args.kwargs["env"]
                self.assertEqual(users[0], driver_environment["TYPEFORGE_TEST_EDITOR_AUTHOR"])
                self.assertEqual(users[1], driver_environment["TYPEFORGE_TEST_EDITOR_REVIEWER"])
                self.assertEqual(users[2], driver_environment["TYPEFORGE_TEST_ADMIN_USERNAME"])
                self.assertEqual(outcome.registrations[0]["body"]["password"], driver_environment["TYPEFORGE_TEST_EDITOR_PASSWORD"])
                expected_command = ["node", "tests/browser.mjs"] if driver == "browser" else ["offline-npm", "run", "test:live"]
                self.assertEqual(expected_command, outcome.driver.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
