"""Offline verifier safeguards only. These tests do NOT execute or validate Docker isolation."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import verify_docker


class VerifierSafeguardsTest(unittest.TestCase):
    def test_fixed_sources_stay_within_the_actual_admission_limit(self):
        for source in (verify_docker.VALID, verify_docker.INVALID, verify_docker.RAW,
                       verify_docker.heavy_source(), verify_docker.output_source()):
            self.assertLessEqual(len(source.encode("utf-8")), 32768)
            self.assertNotIn("public static void main", source)

    def test_missing_docker_is_a_hard_failure_not_a_skip(self):
        verifier = object.__new__(verify_docker.Verification)
        with patch.object(verify_docker.shutil, "which", return_value=None):
            with self.assertRaisesRegex(verify_docker.VerificationFailure, "^DOCKER_UNAVAILABLE$"):
                verifier.start()

    def test_cleanup_inventory_never_claims_other_tasks_or_prefix_collisions(self):
        with tempfile.TemporaryDirectory() as root:
            verifier = object.__new__(verify_docker.Verification)
            verifier.root = Path(root).resolve()
            ids = ["a" * 12, "b" * 12, "c" * 12, "d" * 12]
            rows = [
                {"Id": ids[0], "Mounts": [{"Destination": "/input", "Source": str(verifier.root / "compile-one")}]},
                {"Id": ids[1], "Mounts": [{"Destination": "/input", "Source": str(verifier.root) + "-different-task"}]},
                {"Id": ids[2], "Mounts": [{"Destination": "/input", "Source": "/tmp/unrelated-worker-task"}]},
                {"Id": ids[3], "Mounts": [{"Destination": "/unrelated", "Source": str(verifier.root / "not-an-input")}]},
            ]
            results = [subprocess.CompletedProcess([], 0, "\n".join(ids).encode(), b""),
                       subprocess.CompletedProcess([], 0, json.dumps(rows).encode(), b"")]
            with patch.object(verifier, "command", side_effect=results):
                self.assertEqual([ids[0]], [item["Id"] for item in verifier.owned_containers()])


if __name__ == "__main__":
    unittest.main()
