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


if __name__ == "__main__":
    unittest.main()
