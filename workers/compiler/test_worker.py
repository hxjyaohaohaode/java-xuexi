"""Protocol/command construction tests. These do not claim a running Docker isolation audit."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import threading
import time
import uuid
import server


class WorkerProtocolTest(unittest.TestCase):
    def request(self):
        source = "public class Main {}"
        return {"protocol": server.PROTOCOL, "jobId": str(uuid.uuid4()), "leaseToken": str(uuid.uuid4()),
                "source": source, "sourceHash": hashlib.sha256(source.encode()).hexdigest(), "checks": []}

    def test_valid_and_immutable_source_hash(self):
        value = self.request()
        self.assertIs(server.validate_request(value), value)
        value["source"] += " "
        with self.assertRaises(ValueError):
            server.validate_request(value)

    def test_source_limits_unicode_and_nul(self):
        for source in [" ", "\0", "字" * 12000]:
            value = self.request()
            value["source"] = source
            value["sourceHash"] = hashlib.sha256(source.encode()).hexdigest()
            with self.assertRaises(ValueError):
                server.validate_request(value)

    def test_no_paths_flags_modes_or_duplicate_check_names(self):
        value = self.request()
        value["checks"] = [{"id": "../../oops", "source": "void m(){}"}]
        with self.assertRaises(ValueError):
            server.validate_request(value)
        value["checks"] = [{"id": "check0", "source": "void m(){}"}] * 2
        with self.assertRaises(ValueError):
            server.validate_request(value)
        value = self.request()
        value["mode"] = "RUN"
        with self.assertRaises(ValueError):
            server.validate_request(value)

    def test_duplicate_json_fields_rejected(self):
        with self.assertRaises(ValueError):
            server.bounded_json(b'{"source":"a","source":"b"}')

    def test_container_has_all_required_limits_and_no_learner_command(self):
        server.IMAGE = "sha256:" + "a" * 64
        cmd = server.docker_command("typeforge-fixed", Path("/tmp/fixed"), ["Main.java"])
        for argument in ["--pull=never", "--network=none", "--read-only", "--user=65534:65534", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                         "--pids-limit=64", "--memory=192m", "--memory-swap=192m", "--cpus=0.5", "-proc:none", "-Werror", "-XDrawDiagnostics"]:
            self.assertIn(argument, cmd)
        self.assertNotIn("sh", cmd)
        self.assertNotIn("java", cmd)
        self.assertIn("/input/Main.java", cmd)
        self.assertIn("/input/empty", cmd)
        self.assertNotIn(server.TOKEN, cmd if server.TOKEN else [])

    def test_run_binds_standard_input_language_and_source_without_grading_checks(self):
        value = self.request()
        value.pop("checks")
        value.update(protocol=server.RUN_PROTOCOL, language="PYTHON", stdin="键小黄\n")
        self.assertIs(value, server.validate_run(value))
        for field, replacement in [("stdin", "字" * 3000), ("language", "SHELL"), ("checks", [])]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                server.validate_run(dict(value, **{field: replacement}))

    def test_run_uses_only_fixed_container_commands_and_hides_infrastructure_errors(self):
        value = self.request()
        value.update(language="PYTHON", stdin="test input")
        def inspect(command, cancelled, deadline, name, limit, framed):
            self.assertTrue(framed)
            self.assertEqual(["python3", "-I", "-B", "/opt/typeforge/runner.py", "PYTHON"], command[-5:])
            self.assertNotIn(value["source"], command)
            self.assertIn("--network=none", command)
            self.assertEqual(server.MAX_RUN_OUTPUT, limit)
            mount = command[command.index("--mount") + 1]
            root = Path(mount.split("src=", 1)[1].split(",dst=", 1)[0])
            self.assertEqual(value["stdin"], (root / "stdin.txt").read_text())
            self.assertEqual(value["source"], (root / "main.py").read_text())
            return None, 125, "example daemon failure"
        with patch.object(server, "run_bounded", side_effect=inspect):
            result = server.execute_request(value, threading.Event())
        self.assertEqual(("INFRA_ERROR", ""), (result["status"], result["output"]))

    def test_run_reports_compiler_warning_separately_from_program_output(self):
        request = dict(self.request(), language="JAVA", stdin="")
        compiler = "Main.java:1:3: compiler.warn.unchecked.call.mbr.of.raw.type: add(E), java.util.List\n"
        header = json.dumps({"compiler": compiler}) + "\n"
        forged = 'Main.java:1:3: compiler.err.fake: program text\n'
        with patch.object(server, "run_bounded", return_value=(None, 0, header + forged)):
            result = server.execute_request(request, threading.Event())
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual(forged, result["output"])
        self.assertEqual(["WARNING"], [row["severity"] for row in result["diagnostics"]])

    def test_program_host_requires_enforceable_limits_and_seccomp(self):
        baseline = dict.fromkeys(("MemoryLimit", "SwapLimit", "CpuCfsQuota", "CpuCfsPeriod", "PidsLimit"), True)
        baseline.update(OSType="linux", SecurityOptions=["name=seccomp,profile=builtin"])
        with patch.object(server.subprocess, "check_output", return_value=json.dumps(baseline).encode()):
            server.require_runtime_host()
        for change in ({"SecurityOptions": []}, {"MemoryLimit": False}, {"PidsLimit": False}, {"OSType": "windows"}):
            with self.subTest(change=change), patch.object(server.subprocess, "check_output",
                    return_value=json.dumps(dict(baseline, **change)).encode()), self.assertRaises(SystemExit):
                server.require_runtime_host()

    def test_empty_classpath_directory_exists_under_the_readonly_mount(self):
        # A nonexistent path emits a lint warning and -Werror would reject every otherwise valid answer.
        def inspect(command, cancelled, deadline, container):
            mount = command[command.index("--mount") + 1]
            root = Path(mount.split("src=", 1)[1].split(",dst=", 1)[0])
            self.assertTrue((root / "empty").is_dir())
            self.assertEqual([], list((root / "empty").iterdir()))
            self.assertIn("/input/empty", command)
            return None, 0, ""
        with patch.object(server, "run_bounded", side_effect=inspect):
            result = server.compile_one("public class Main {}", None, threading.Event(), time.monotonic() + 5)
        self.assertEqual("SUCCEEDED", result[0])

    def test_structured_diagnostics_preserve_codes_and_hide_host_paths(self):
        rows, paths = server.diagnostics("/input/Main.java:2:4: compiler.err.prob.found.req: mismatch\n1 error\n")
        self.assertEqual(["Main.java"], paths)
        self.assertEqual("compiler.err.prob.found.req", rows[0]["code"])
        self.assertEqual(2, rows[0]["line"])
        self.assertEqual("ERROR", rows[0]["severity"])

    def test_hidden_check_diagnostic_locations_are_distinct(self):
        rows, paths = server.diagnostics("Probe_check1.java:3:2: compiler.err.prob.found.req: mismatch\nMain.java:1:1: compiler.err.duplicate.class: Probe_check1\n")
        self.assertEqual(["Probe_check1.java", "Main.java"], paths)
        self.assertFalse(all(path == "Probe_check1.java" for path in paths))

    def test_javac_global_werror_rejection_is_a_compile_error(self):
        # Exact diagnostic shape observed from the fixed raw-types fixture on javac 17.0.21.
        output = ("Main.java:1:45: compiler.warn.raw.class.use: java.util.List, java.util.List<E>\n"
                  "Main.java:1:66: compiler.warn.raw.class.use: java.util.ArrayList, java.util.ArrayList<E>\n"
                  "Main.java:1:85: compiler.warn.unchecked.call.mbr.of.raw.type: add(E), java.util.List\n"
                  "- compiler.err.warnings.and.werror\n1 error\n3 warnings\n")
        with patch.object(server, "run_bounded", return_value=(None, 1, output)):
            status, rows, locations, truncated = server.compile_one(
                "public class Main {}", None, threading.Event(), time.monotonic() + 5)
        self.assertEqual("COMPILE_ERROR", status)
        self.assertEqual(["WARNING", "WARNING", "WARNING", "ERROR"], [row["severity"] for row in rows])
        self.assertEqual("compiler.err.warnings.and.werror", rows[-1]["code"])
        self.assertEqual((-1, -1), (rows[-1]["line"], rows[-1]["column"]))
        self.assertIsNone(locations[-1])
        self.assertFalse(truncated)

    def test_unknown_nonzero_failures_never_become_wrong_answers(self):
        for output in ("docker: Error response from daemon: unavailable\n",
                       "Main.java:1:1: compiler.warn.raw.class.use: java.util.List\n"):
            with self.subTest(output=output), patch.object(server, "run_bounded", return_value=(None, 1, output)):
                result = server.compile_one("public class Main {}", None, threading.Event(), time.monotonic() + 5)
                self.assertEqual("INFRA_ERROR", result[0])


if __name__ == "__main__":
    unittest.main()
