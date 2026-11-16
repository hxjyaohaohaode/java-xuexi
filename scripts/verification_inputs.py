"""Bind observed verification to its actual source inputs and complete result inventory.

These are reproducibility records, not signatures or an alternative to running a gate.
There is deliberately no command that blesses existing outputs. Build wrappers remove
prior outputs, execute the real tools, and check these manifests before and after.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
GENERATED_EVIDENCE = {"evidence/runtime-check-results.json", "evidence/runtime-review.md"}
ALGORITHM = "SHA-256 of sorted relative path + NUL + file SHA-256 + LF"


class VerificationFailure(RuntimeError):
    """A safe, non-secret explanation of a rejected verification record."""


def require(condition, description):
    if not condition:
        raise VerificationFailure(description)


def _manifest(scope, names, root):
    files = {}
    for name in sorted(set(names)):
        path = root / name
        require(path.is_file() and path.resolve().is_relative_to(root.resolve()),
                "Missing or external verification input: " + name)
        files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(bool(files), "Verification input set is empty: " + scope)
    digest = hashlib.sha256("".join(name + "\0" + value + "\n" for name, value in files.items()).encode()).hexdigest()
    return {"schemaVersion": 1, "scope": scope, "algorithm": ALGORITHM,
            "digest": digest, "fileCount": len(files), "files": files}


def capture_inputs(scope, extra_files=(), root=None):
    root = Path(root or ROOT)
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
    ).decode().split("\0")
    visible = [name for name in names if name and (root / name).is_file()]
    if scope == "static":
        selected = [name for name in visible if name not in GENERATED_EVIDENCE]
    elif scope == "backend":
        selected = [name for name in visible if
                    name.startswith("backend/src/") or name == "backend/pom.xml"
                    or name.startswith((".mvn/", "backend/.mvn/"))
                    or name.startswith(("contracts/", "knowledge/")) and name.endswith(".json")
                    or name in {"agent/roles.json", "agent/tools.json", "database/migrations.lock.json"}
                    or name.startswith("database/") and name.endswith(".sql")]
    elif scope == "frontend":
        selected = [name for name in visible if name.startswith("frontend/")
                    and not name.endswith(".md")]
    elif scope == "schema":
        selected = [name for name in visible if name == "database/migrations.lock.json"
                    or name.startswith("database/") and name.endswith(".sql")]
    elif scope == "compiler":
        selected = [name for name in visible if name.startswith("workers/compiler/")]
    else:
        raise VerificationFailure("Unknown verification input scope: " + scope)
    extras = sorted(set(str(name) for name in extra_files))
    for name in extras:
        require(not Path(name).is_absolute() and ".." not in Path(name).parts,
                "Verification input must be a repository-relative path")
    selected.extend(extras)
    selected.append("scripts/verification_inputs.py")
    result = _manifest(scope, selected, root)
    result["extraFiles"] = extras
    return result


def require_unchanged(before, root=None):
    after = capture_inputs(before["scope"], before.get("extraFiles", ()), root)
    require(before == after, "Verification inputs changed during execution: " + before["scope"])
    return True


def require_current_inputs(report, scope, extra_files=(), root=None):
    require(report.get("result", report.get("status")) == "PASS", "Verification did not pass: " + scope)
    require(report.get("inputsUnchanged") is True, "Verification lacks a completed before/after input check: " + scope)
    current = capture_inputs(scope, extra_files, root)
    require(report.get("verificationInputs") == current,
            "Verification belongs to different inputs; rerun the actual gate: " + scope)
    return current


def capture_dist(root=None):
    root = Path(root or ROOT)
    directory = root / "frontend/dist"
    require((directory / "index.html").is_file(), "Fresh frontend/dist/index.html is required")
    names = [file.relative_to(root).as_posix() for file in directory.rglob("*") if file.is_file()]
    return _manifest("frontend-dist", names, root)


def write_report(path, report):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def clean_maven_environment(environment=None, native_mysql=False):
    """Do not allow inherited skip/filter flags to turn a full gate into a subset."""
    env = dict(os.environ if environment is None else environment)
    for name in list(env):
        if name in {"MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}:
            env.pop(name)
        elif not native_mysql and name.startswith(("TYPEFORGE_", "SPRING_", "MYSQL_TEST_CONNECTION")):
            env.pop(name)
    return env


_JAVA_IGNORED = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')


def _java_mask(text):
    return _JAVA_IGNORED.sub(lambda match: "".join("\n" if char == "\n" else " " for char in match.group()), text)


def _balanced_end(masked, start, opening="(", closing=")"):
    require(start >= 0 and masked[start] == opening, "Unsupported Java test provider syntax")
    depth = 0
    for position in range(start, len(masked)):
        if masked[position] == opening:
            depth += 1
        elif masked[position] == closing:
            depth -= 1
            if depth == 0:
                return position
    raise VerificationFailure("Unbalanced Java test provider")


def _argument_count(raw, masked):
    require(bool(raw.strip()), "A parameterized test must supply actual cases")
    depth = 0
    count = 1
    for char in masked:
        if char in "({[":
            depth += 1
        elif char in ")}]":
            depth -= 1
        elif char == "," and depth == 0:
            count += 1
    require(depth == 0, "Unsupported parameterized test argument structure")
    return count


def java_test_inventory(root=None):
    """Current JUnit methods and exact invocation counts, failing closed on new source forms.

    Supports this repository's @Test, literal @ValueSource and local direct
    Stream.of @MethodSource fixtures. Unknown dynamic/nested providers must receive
    an explicit reviewed inventory implementation instead of silently disappearing.
    """
    root = Path(root or ROOT)
    inventory = {}
    for path in sorted((root / "backend/src/test/java").rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        masked = _java_mask(text)
        require(not re.search(r"@(TestFactory|TestTemplate|RepeatedTest|Nested)\b", masked),
                "Add explicit inventory support for dynamic/nested test: " + path.name)
        tags = list(re.finditer(r"@(Test|ParameterizedTest)\b", masked))
        if not tags:
            continue
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", masked)
        require(package is not None, "Test package is missing: " + path.name)
        name = package.group(1) + "." + path.stem
        methods = {}
        for index, tag in enumerate(tags):
            next_tag = tags[index + 1].start() if index + 1 < len(tags) else len(masked)
            method = re.search(r"\bvoid\s+(\w+)\s*\(", masked[tag.end():next_tag])
            require(method is not None, "Cannot identify test method: " + path.name)
            method_start = tag.end() + method.start()
            method_name = method.group(1)
            require(method_name not in methods, "Overloaded test methods need an explicit inventory: " + name)
            expected = 1
            if tag.group(1) == "ParameterizedTest":
                annotation = text[tag.start():method_start]
                annotation_mask = masked[tag.start():method_start]
                if "@ValueSource" in annotation_mask:
                    start = annotation_mask.find("{", annotation_mask.find("@ValueSource"))
                    end = _balanced_end(annotation_mask, start, "{", "}")
                    expected = _argument_count(annotation[start + 1:end], annotation_mask[start + 1:end])
                else:
                    provider = re.search(r'@MethodSource\s*\(\s*"(\w+)"\s*\)', annotation)
                    require(provider is not None, "Unsupported parameterized provider: " + name + "." + method_name)
                    definition = re.search(r"\b" + re.escape(provider.group(1)) + r"\s*\(\s*\)\s*\{", masked)
                    require(definition is not None, "Local MethodSource is missing: " + name)
                    body_start = masked.index("{", definition.start())
                    body_end = _balanced_end(masked, body_start, "{", "}")
                    body = masked[body_start:body_end]
                    stream = re.search(r"\breturn\s+Stream\.of\s*\(", body)
                    require(stream is not None, "MethodSource must declare direct finite Stream.of cases: " + name)
                    start = body_start + body.index("(", stream.start())
                    end = _balanced_end(masked, start)
                    require(masked[end + 1:body_end].strip() == ";", "Dynamic MethodSource transformation needs explicit inventory: " + name)
                    expected = _argument_count(text[start + 1:end], masked[start + 1:end])
            methods[method_name] = {"kind": tag.group(1), "invocations": expected}
        inventory[name] = methods
    require(bool(inventory), "No current Java tests were discovered")
    return inventory


def validate_java_summaries(summaries, inventory):
    require(len(summaries) == len(inventory) and {item["name"] for item in summaries} == set(inventory),
            "Java result suite set differs from the complete current source inventory")
    for suite in summaries:
        expected = inventory[suite["name"]]
        cases = suite["testCases"]
        require(suite["tests"] == len(cases) == sum(item["invocations"] for item in expected.values())
                and len(set(cases)) == len(cases)
                and all(suite[key] == 0 for key in ("failures", "errors", "skipped")),
                "Java suite has missing, duplicate, failed or skipped cases: " + suite["name"])
        actual = {}
        for case in cases:
            match = re.fullmatch(r"([\w$]+)(?:\([^)]*\))?(?:\[(\d+)\])?", case)
            require(match is not None and match.group(1) in expected, "Unexpected JUnit case: " + suite["name"])
            actual.setdefault(match.group(1), []).append(int(match.group(2)) if match.group(2) else None)
        require(set(actual) == set(expected), "Java suite omitted a current method: " + suite["name"])
        for name, method in expected.items():
            indices = actual[name]
            matching = (indices == [None] if method["kind"] == "Test" else
                        all(isinstance(index, int) for index in indices)
                        and sorted(indices) == list(range(1, method["invocations"] + 1)))
            require(matching,
                    "Parameterized invocations differ from source cases: " + suite["name"] + "." + name)
    return {key: sum(item[key] for item in summaries) for key in ("tests", "failures", "errors", "skipped")}


def read_java_summaries(root=None):
    root = Path(root or ROOT)
    suites = []
    for path in sorted((root / "backend/target/surefire-reports").glob("TEST-*.xml")):
        element = ET.parse(path).getroot()
        suites.append({"name": element.attrib["name"],
                       **{key: int(element.attrib[key]) for key in ("tests", "failures", "errors", "skipped")},
                       "seconds": float(element.attrib["time"]),
                       "testCases": [case.attrib["name"] for case in element.findall("testcase")]})
    return suites
