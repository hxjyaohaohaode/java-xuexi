"""Offline regression tests for evidence reuse; never invoke real Maven/npm or services."""
import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile

from verification_inputs import (VerificationFailure, capture_dist, capture_inputs,
                                 java_test_inventory, require_current_inputs,
                                 require_unchanged, validate_java_summaries, write_report)
import verify_frontend
import verify_java


class VerificationInputsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.put(".gitignore", ".work/\nbackend/target/\nfrontend/dist/\nfrontend/test-results/\n")
        for name in ("verification_inputs.py", "verify_java.py", "verify_frontend.py"):
            self.put("scripts/" + name, "# Isolated gate input fixture\n")
        self.put("backend/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>fixture</version></project>')
        self.put("backend/src/main/java/example/App.java", "package example; class App {}")
        self.put("backend/src/test/java/example/SimpleTest.java",
                 "package example; class SimpleTest { @Test void currentCase() {} }")
        self.put("frontend/src/index.ts", "export const current = true;\n")
        self.put("frontend/src/index.test.ts", "it('current case', () => {});\n")
        self.put("frontend/package.json", '{"scripts":{"check":"fixture","build":"fixture"}}')
        self.put("docs/guide.md", "current guide\n")

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def report(self, name):
        return json.loads((self.root / name).read_text())

    def frontend_outputs(self):
        self.put("frontend/dist/index.html", "<main>current build</main>")
        unit = {"success": True, "numTotalTestSuites": 1, "numPassedTestSuites": 1,
                "numFailedTestSuites": 0, "numTotalTests": 1, "numPassedTests": 1,
                "numFailedTests": 0, "numPendingTests": 0,
                "testResults": [{"name": str(self.root / "frontend/src/index.test.ts"),
                                 "status": "passed", "assertionResults": [
                                     {"fullName": "current case", "status": "passed", "failureMessages": []}]}]}
        self.put("frontend/test-results/unit-report.json", json.dumps(unit))
        return unit

    def frontend_proof(self):
        self.frontend_outputs()
        proof = {"result": "PASS", "inputsUnchanged": True,
                 "verificationInputs": capture_inputs("frontend", verify_frontend.EXTRA_FILES, self.root),
                 "dist": capture_dist(self.root)}
        write_report(self.root / verify_frontend.REPORT, proof)
        return proof

    def java_outputs(self, missing_case=False):
        jar = verify_java.packaged_jar(self.root)
        jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("BOOT-INF/classes/static/index.html", (self.root / "frontend/dist/index.html").read_bytes())
        case = "" if missing_case else '<testcase name="currentCase"/>'
        self.put("backend/target/surefire-reports/TEST-example.SimpleTest.xml",
                 '<testsuite name="example.SimpleTest" tests="' + ("0" if missing_case else "1") +
                 '" failures="0" errors="0" skipped="0" time="0.01">' + case + '</testsuite>')
        return jar

    def test_changed_source_rejects_old_passing_proof(self):
        before = capture_inputs("backend", root=self.root)
        proof = {"result": "PASS", "inputsUnchanged": True, "verificationInputs": before}
        require_current_inputs(proof, "backend", root=self.root)
        self.put("backend/src/main/java/example/App.java", "package example; class App { int changed; }")
        with self.assertRaisesRegex(VerificationFailure, "different inputs"):
            require_current_inputs(proof, "backend", root=self.root)
        with self.assertRaisesRegex(VerificationFailure, "changed during execution"):
            require_unchanged(before, self.root)

    def test_final_documentation_refresh_only_invalidates_static_scope(self):
        backend = capture_inputs("backend", root=self.root)
        frontend = capture_inputs("frontend", root=self.root)
        static = capture_inputs("static", root=self.root)
        self.put("docs/guide.md", "updated current handoff\n")
        self.assertTrue(require_unchanged(backend, self.root))
        self.assertTrue(require_unchanged(frontend, self.root))
        with self.assertRaises(VerificationFailure):
            require_unchanged(static, self.root)

    def test_new_test_file_invalidates_backend_inputs(self):
        before = capture_inputs("backend", root=self.root)
        self.put("backend/src/test/java/example/AddedTest.java", "package example; class AddedTest { @Test void added() {} }")
        with self.assertRaises(VerificationFailure):
            require_unchanged(before, self.root)

    def test_junit_inventory_counts_annotations_and_all_parameter_invocations(self):
        self.put("backend/src/test/java/example/ParameterizedTest.java", '''package example;
class ParameterizedTest {
    @Test @Timeout(3) void ordinary() {}
    @ParameterizedTest @ValueSource(strings={"first", "second, still one"}) void values(String value) {}
    @ParameterizedTest @MethodSource("cases") void method(String value, int count) {}
    static Stream<Arguments> cases() { return Stream.of(Arguments.of("a", 1), Arguments.of("b", 2)); }
}''')
        inventory = java_test_inventory(self.root)
        self.assertEqual(inventory["example.ParameterizedTest"], {
            "ordinary": {"kind": "Test", "invocations": 1},
            "values": {"kind": "ParameterizedTest", "invocations": 2},
            "method": {"kind": "ParameterizedTest", "invocations": 2}})
        suites = [{"name": "example.SimpleTest", "tests": 1, "failures": 0, "errors": 0, "skipped": 0,
                   "testCases": ["currentCase"]},
                  {"name": "example.ParameterizedTest", "tests": 5, "failures": 0, "errors": 0, "skipped": 0,
                   "testCases": ["ordinary", "values(String)[1]", "values(String)[2]",
                                 "method(String, int)[1]", "method(String, int)[2]"]}]
        self.assertEqual(validate_java_summaries(suites, inventory)["tests"], 6)
        with self.subTest("missing suite"), self.assertRaises(VerificationFailure):
            validate_java_summaries(suites[:1], inventory)
        missing = copy.deepcopy(suites)
        missing[1]["testCases"].pop()
        missing[1]["tests"] -= 1
        with self.subTest("missing actual case"), self.assertRaises(VerificationFailure):
            validate_java_summaries(missing, inventory)
        missing = copy.deepcopy(suites)
        missing[1]["testCases"][-1] = "method(String, int)[3]"
        with self.subTest("missing invocation replaced by another index"), self.assertRaises(VerificationFailure):
            validate_java_summaries(missing, inventory)
        skipped = copy.deepcopy(suites)
        skipped[0]["skipped"] = 1
        with self.subTest("skipped current case"), self.assertRaises(VerificationFailure):
            validate_java_summaries(skipped, inventory)

    def test_unknown_dynamic_provider_is_rejected_instead_of_omitted(self):
        self.put("backend/src/test/java/example/DynamicTest.java",
                 'package example; class DynamicTest { @ParameterizedTest @CsvSource({"a,b"}) void unknown(String a, String b) {} }')
        with self.assertRaisesRegex(VerificationFailure, "Unsupported parameterized provider"):
            java_test_inventory(self.root)

    def test_frontend_noop_cannot_resign_existing_green_outputs(self):
        self.frontend_proof()
        called = []
        def noop(command, **kwargs):
            called.append(command)
            return SimpleNamespace(returncode=0)
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_frontend.run_gate(sys.executable, self.root, noop)
        self.assertEqual(result, 1)
        self.assertEqual(len(called), 2)
        report = self.report(verify_frontend.REPORT)
        self.assertEqual(report["result"], "FAIL")
        self.assertIn("No fresh frontend unit report", report["error"])
        self.assertFalse((self.root / "frontend/dist").exists())

    def test_frontend_changed_input_during_actual_runner_is_rejected(self):
        def produces_then_changes(command, **kwargs):
            self.frontend_outputs()
            if command[-1] == "build":
                self.put("frontend/src/index.ts", "export const changedWhileBuilding = true;")
            return SimpleNamespace(returncode=0)
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_frontend.run_gate(sys.executable, self.root, produces_then_changes)
        self.assertEqual(result, 1)
        self.assertIn("changed during execution", self.report(verify_frontend.REPORT)["error"])

    def test_java_noop_cannot_resign_existing_green_jar_and_xml(self):
        self.frontend_proof()
        old = self.java_outputs()
        write_report(self.root / verify_java.REPORT, {"result": "PASS", "jarSha256": "old"})
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_java.run_gate(sys.executable, self.root, lambda *a, **kw: SimpleNamespace(returncode=0))
        self.assertEqual(result, 1)
        self.assertEqual(self.report(verify_java.REPORT)["result"], "FAIL")
        self.assertIn("No fresh packaged JAR", self.report(verify_java.REPORT)["error"])
        self.assertFalse(old.exists())
        self.assertEqual(list((self.root / "backend/target/surefire-reports").glob("TEST-*.xml")), [])

    def test_java_runner_missing_current_case_cannot_create_passing_proof(self):
        self.frontend_proof()
        def incomplete(*args, **kwargs):
            self.java_outputs(missing_case=True)
            return SimpleNamespace(returncode=0)
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_java.run_gate(sys.executable, self.root, incomplete)
        self.assertEqual(result, 1)
        self.assertIn("missing, duplicate, failed or skipped", self.report(verify_java.REPORT)["error"])

    def test_java_runner_complete_current_outputs_are_required(self):
        self.frontend_proof()
        def complete(*args, **kwargs):
            self.java_outputs()
            return SimpleNamespace(returncode=0)
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_java.run_gate(sys.executable, self.root, complete)
        self.assertEqual(result, 0)
        report = self.report(verify_java.REPORT)
        self.assertEqual(report["tests"], 1)
        self.assertEqual(report["suites"][0]["testCases"], ["currentCase"])
        require_current_inputs(report, "backend", verify_java.EXTRA_FILES, self.root)

    def test_java_runner_input_change_rejects_even_fresh_complete_outputs(self):
        self.frontend_proof()
        def changed(*args, **kwargs):
            self.java_outputs()
            self.put("backend/src/main/java/example/App.java", "package example; class App { int changed; }")
            return SimpleNamespace(returncode=0)
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_java.run_gate(sys.executable, self.root, changed)
        self.assertEqual(result, 1)
        self.assertIn("changed during execution", self.report(verify_java.REPORT)["error"])


if __name__ == "__main__":
    unittest.main()
