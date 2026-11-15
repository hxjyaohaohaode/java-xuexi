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
