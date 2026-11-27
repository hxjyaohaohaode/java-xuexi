#!/usr/bin/env python3
"""Exercise a packaged runtime over real HTTP, including a process restart.

Build the jar separately, then run:
  python scripts/smoke_runtime.py --jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar
  python .work/mysql-validation/with_mysql.py python scripts/smoke_runtime.py --profile mysql

The default uses a new task-owned H2 file database. MySQL mode requires an explicit
MYSQL_TEST_CONNECTION JSON with purpose="typeforge-isolated-verification" and a
reserved local runtime database. It never creates/drops a remote or user database.
Only demo profile fixtures and synthetic, randomly named learner accounts are used.
Python standard library only; this script neither builds the jar nor starts MySQL.
"""
from pathlib import Path
import argparse
import datetime as dt
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from verification_inputs import capture_inputs, require_unchanged, write_report


ROOT = Path(__file__).resolve().parents[1]
API = "/api/v1"
MAX_RESPONSE = 4 * 1024 * 1024


class SmokeFailure(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, new_url):
        raise SmokeFailure("Unexpected HTTP redirect from the local API")


class Client:
    def __init__(self, smoke, base):
        self.smoke = smoke
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())
        self.csrf_header = None
        self.csrf_token = None

    def request(self, method, path, body=None, expected=(200,), key=None, csrf=True, extra_headers=None):
        if not path.startswith(API + "/"):
            raise SmokeFailure("HTTP smoke requests must use the local API prefix")
        headers = {"Accept": "application/json"}
        if extra_headers:
            headers.update(extra_headers)
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = key
        if method not in ("GET", "HEAD") and csrf and self.csrf_token:
            headers[self.csrf_header] = self.csrf_token
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        started = time.monotonic()
        try:
            response = self.opener.open(request, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.code
            payload = response.read(MAX_RESPONSE + 1)
        if len(payload) > MAX_RESPONSE:
            raise SmokeFailure("API response exceeded smoke-test memory bound")
        self.smoke.report["http"].append({
            "phase": self.smoke.phase, "method": method, "path": path,
            "status": status, "milliseconds": round((time.monotonic() - started) * 1000),
        })
        try:
            result = json.loads(payload) if payload else None
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise SmokeFailure(f"{method} {path} returned non-JSON HTTP {status}") from error
        if status not in expected:
            detail = self.smoke.redact(json.dumps(result, ensure_ascii=False))[:700]
            raise SmokeFailure(f"{method} {path}: expected {expected}, received {status}: {detail}")
        return result

    def download(self, path):
        if not re.fullmatch(r'/api/v1/export-jobs/[a-f0-9-]{36}/download', path):
            raise SmokeFailure('Refusing a download outside the authenticated export API')
        with self.opener.open(self.base + path, timeout=8) as response:
            content = response.read(MAX_RESPONSE + 1)
            if response.status != 200 or len(content) > MAX_RESPONSE:
                raise SmokeFailure('Invalid export download status or size')
            if not response.headers.get('Content-Disposition', '').startswith('attachment;') or response.headers.get('Cache-Control') != 'private, no-store':
                raise SmokeFailure('Export download omitted private attachment headers')
        return content

    def csrf(self):
        token = self.request("GET", API + "/auth/csrf")
        if not isinstance(token, dict) or not token.get("token") or not token.get("headerName"):
            raise SmokeFailure("CSRF endpoint omitted its token or header name")
        self.csrf_header = token["headerName"]
        self.csrf_token = token["token"]
        self.smoke.secrets.add(self.csrf_token)

    def register_and_login(self, username, password):
        self.csrf()
        registered = self.request("POST", API + "/auth/register",
                                  {"username": username, "password": password}, expected=(201,))
        self.request("GET", API + "/auth/session", expected=(401,))
        current = self.login(username, password)
        self.smoke.check("registration and login resolve the same persisted user",
                         current["userId"] == registered["userId"])
        return current

    def login(self, username, password):
        self.csrf()
        result = self.request("POST", API + "/auth/session",
                              {"username": username, "password": password})
        # Login changes the session and rotates CSRF state. Never reuse the old token.
        self.csrf()
        return result


class Application:
    def __init__(self, smoke):
        self.smoke = smoke
        self.process = None
        self.log = None
        self.logs = []

    def start(self):
        if self.process is not None:
            raise SmokeFailure("Attempted to start a second application without stopping the first")
        log_path = self.smoke.work / f"application-{len(self.logs) + 1}.log"
        self.logs.append(log_path)
        self.log = log_path.open("w", encoding="utf-8")
        profiles = "mysql,demo" if self.smoke.args.profile == "mysql" else "demo"
        driver = "com.mysql.cj.jdbc.Driver" if self.smoke.args.profile == "mysql" else "org.h2.Driver"
        migrations = "classpath:database" if self.smoke.args.profile == "mysql" else "classpath:io/typeforge/operations/migrations/h2"
        command = [str(self.smoke.java), "-Xms64m", "-Xmx384m", "-jar", str(self.smoke.jar),
                   "--server.address=127.0.0.1", "--server.port=0",
                   "--spring.profiles.active=" + profiles,
                   "--spring.datasource.url=" + self.smoke.env["TYPEFORGE_DB_URL"],
                   "--spring.datasource.username=" + self.smoke.env["TYPEFORGE_DB_USER"],
                   "--spring.datasource.driver-class-name=" + driver,
                   "--spring.flyway.locations=" + migrations,
                   "--typeforge.events.dispatch-enabled=true", "--typeforge.events.poll-millis=200",
                   "--typeforge.export.dispatch-enabled=true", "--typeforge.export.poll-millis=200",
                   "--typeforge.tutor.provider-url=", "--typeforge.tutor.api-key=", "--typeforge.tutor.model=",
                   "--typeforge.tutor.worker-enabled=true",
                   "--typeforge.bootstrap.editor-users=", "--typeforge.bootstrap.admin-users=",
                   "--server.servlet.session.cookie.secure=false"]
        self.process = subprocess.Popen(command, cwd=self.smoke.work, env=self.smoke.env,
                                        stdin=subprocess.DEVNULL, stdout=self.log,
                                        stderr=subprocess.STDOUT)
        deadline = time.monotonic() + self.smoke.args.startup_timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise SmokeFailure("Application exited during startup; inspect " + str(log_path))
            text = log_path.read_text(encoding="utf-8", errors="replace")
            match = re.search(r"Tomcat started on port (\d+)\b", text)
            if match:
                base = "http://127.0.0.1:" + match.group(1)
                try:
                    health = Client(self.smoke, base).request("GET", API + "/health")
                    if health.get("status") == "UP":
                        self.smoke.check("application answers HTTP health after database initialization", True)
                        return base
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    pass
            time.sleep(.2)
        raise SmokeFailure("Application readiness timed out; inspect " + str(log_path))

    def stop(self):
        if self.process is None:
            return
        process, self.process = self.process, None
        try:
            if process.poll() is not None:
                raise SmokeFailure("Application exited unexpectedly before test shutdown")
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                raise SmokeFailure("Application required SIGKILL instead of graceful shutdown")
        finally:
            if self.log:
                self.log.close()
                self.log = None

    def check_logs(self):
        findings = []
        for path in self.logs:
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if re.search(r"\bERROR\b|Exception in thread|Application run failed", line):
                    findings.append(self.smoke.redact(line)[:700])
        self.smoke.report["applicationLogs"] = [str(path) for path in self.logs]
        self.smoke.report["unexpectedLogErrors"] = findings[:20]
        self.smoke.check("application logs contain no unexpected server or scheduled-worker errors", not findings)


class Smoke:
    def __init__(self, args):
        self.args = args
        self.phase = "prepare"
        self.secrets = set()
        self.env = os.environ.copy()
        (ROOT / ".work").mkdir(exist_ok=True)
        self.work = Path(tempfile.mkdtemp(prefix="runtime-smoke-", dir=ROOT / ".work"))
        self.work.chmod(0o700)
        self.report = {"result": "FAIL", "profile": args.profile, "checks": [], "http": [],
                       "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "workDirectory": str(self.work)}
        self.app = Application(self)
        self.mysql = None

    def redact(self, value):
        for secret in self.secrets:
            if secret:
                value = value.replace(secret, "[REDACTED]")
        return value

    def check(self, name, condition):
        self.report["checks"].append({"phase": self.phase, "check": name,
                                     "result": "PASS" if condition else "FAIL"})
        if not condition:
            raise SmokeFailure(name)

    def prepare(self):
        candidates = sorted((ROOT / "backend/target").glob("typeforge-backend-*.jar"))
        self.jar = self.args.jar.resolve() if self.args.jar else (candidates[-1] if candidates else None)
        if self.jar is None or not self.jar.is_file():
            raise SmokeFailure("Packaged jar not found; run Maven packaging separately before this smoke test")
        executable = self.args.java or (str(Path(os.environ["JAVA_HOME"]) / "bin/java")
                                       if os.environ.get("JAVA_HOME") else shutil.which("java"))
        if not executable:
            raise SmokeFailure("Java executable not found; set JAVA_HOME or --java")
        located = shutil.which(executable)
        self.java = Path(located or executable).resolve()
        if not self.java.is_file():
            raise SmokeFailure("Java executable does not exist")
        self.report["jar"] = str(self.jar)
        self.report["jarSha256"] = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        with zipfile.ZipFile(self.jar) as archive:
            self.fixture = json.loads(archive.read("BOOT-INF/classes/knowledge/runtime-lessons.json"))
        if self.args.profile == "mysql":
            config_path = os.environ.get("MYSQL_TEST_CONNECTION")
            if not config_path:
                raise SmokeFailure("MySQL smoke requires explicit MYSQL_TEST_CONNECTION to an isolated test server")
            config = json.loads(Path(config_path).read_text(encoding="utf-8"))
            if config.get("purpose") != "typeforge-isolated-verification":
                raise SmokeFailure("MySQL smoke requires purpose=typeforge-isolated-verification")
            if config.get("host") not in ("127.0.0.1", "localhost", "::1"):
                raise SmokeFailure("MySQL smoke is restricted to an explicit loopback test server")
            database = config.get("runtimeDatabase", "")
            if not re.fullmatch(r"typeforge_(?:runtime|smoke_[a-z0-9_]{1,48})", database):
                raise SmokeFailure("Refusing a database without a reserved runtime-smoke name")
            if database == config.get("schemaCheckDatabase"):
                raise SmokeFailure("Application smoke must not use the schema constraint-test database")
            port = int(config["port"])
            if not 1 <= port <= 65535:
                raise SmokeFailure("Invalid isolated MySQL port")
            self.mysql = config
            self.secrets.add(config["password"])
            host = config["host"]
            jdbc_host = "[" + host + "]" if ":" in host else host
            self.env["TYPEFORGE_DB_URL"] = (f"jdbc:mysql://{jdbc_host}:{port}/{database}"
                                           "?connectionTimeZone=UTC&sslMode=REQUIRED")
            self.env["TYPEFORGE_DB_USER"] = config["username"]
            self.env["TYPEFORGE_DB_PASSWORD"] = config["password"]
            if config.get("libraryPath"):
                self.env["LD_LIBRARY_PATH"] = config["libraryPath"] + (
                    ":" + self.env["LD_LIBRARY_PATH"] if self.env.get("LD_LIBRARY_PATH") else "")
            version = self.mysql_query("SELECT VERSION();")
            self.check("independent client confirms actual MySQL 8.4 server", bool(re.match(r"8\.4\.", version)))
            self.report["engine"] = "MySQL " + version
            self.report["database"] = database
        else:
            self.env["TYPEFORGE_DB_URL"] = ("jdbc:h2:file:" + str(self.work / "typeforge") +
                                           ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;DEFAULT_NULL_ORDERING=HIGH")
            self.env["TYPEFORGE_DB_USER"] = "sa"
            self.env["TYPEFORGE_DB_PASSWORD"] = ""
            self.report["engine"] = "H2 persistent file (not MySQL)"
        # Avoid inherited Spring overrides silently changing the tested database/profile.
        for name in list(self.env):
            if name.startswith("SPRING_DATASOURCE_") or name in ("SPRING_PROFILES_ACTIVE", "SPRING_APPLICATION_JSON"):
                del self.env[name]
        # Keep the password out of argv while overriding any inherited property file.
        self.env["SPRING_DATASOURCE_PASSWORD"] = self.env["TYPEFORGE_DB_PASSWORD"]

    def mysql_query(self, statement):
        config = self.mysql
        command = [str(Path(config["client"]).resolve()),
                   "--defaults-file=" + str(Path(config["appClientConfig"]).resolve()),
                   "--protocol=TCP", "--host=" + config["host"], "--port=" + str(config["port"]),
                   "--local-infile=0", "--batch", "--raw", "--skip-column-names", config["runtimeDatabase"]]
        result = subprocess.run(command, input=statement, capture_output=True, text=True,
                                encoding="utf-8", env=self.env, timeout=20)
        if result.returncode:
            raise SmokeFailure("Independent MySQL evidence query failed: " + self.redact(result.stderr[-1000:]))
        return result.stdout.strip()

    def wait_for(self, name, action, ready, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = action()
            if ready(value):
                self.check(name, True)
                return value
            if self.app.process is None or self.app.process.poll() is not None:
                raise SmokeFailure("Application exited while awaiting " + name)
            time.sleep(.25)
        raise SmokeFailure("Timed out waiting for " + name)

    def capabilities(self, client):
        capabilities = client.request("GET", API + "/capabilities")
        enabled, unavailable = set(capabilities["enabled"]), set(capabilities["unavailable"])
        self.check("capabilities distinguish working local services from unconfigured model/compiler/media",
                   capabilities["businessReady"] is False and not enabled.intersection(unavailable)
                   and {"CHOICE_GRADING", "LOCAL_EVIDENCE_WORKFLOW", "OUTBOX_PROJECTIONS"} <= enabled
                   and {"FINITE_TYPE_LAB", "NOTEBOOK_ACTIONS", "STATIC_EXPORT", "TUTOR_EVIDENCE_ONLY", "EDITORIAL_PUBLISHING", "STUDY_WORKFLOW", "PRIVATE_ASSETS", "OFFICE_PDF_EXPORT", "ACCOUNT_LIFECYCLE", "LEARNING_ANALYTICS"} <= enabled
                   and {"TEXT_GRADING", "NOTEBOOK_INTERACTIONS", "EVALUATION_WORKBENCH", "BATCH_INGESTION", "CONTENT_REVISION_DIFF"} <= enabled
                   and {"LLM_PLANNING", "LLM_TUTOR", "UNTRUSTED_CODE_EXECUTION", "MEDIA_GENERATION", "COMPILE_ONLY", "CODE_GRADING"} <= unavailable)
        return capabilities

    def verify_private_sql(self, saved):
        if not self.mysql:
            return
        targets = {"app_user": saved["userId"], "learning_workspace": saved["workspaceId"],
                   "exercise_attempt": saved["attempt"]["id"], "learning_artifact": saved["notebook"]["id"],
                   "agent_run": saved["run"]["id"], "evaluation_suite": saved["evaluationSuite"]["id"],
                   "evaluation_run": saved["evaluationRun"]["id"]}
        for table, resource in targets.items():
            identity = str(uuid.UUID(resource))
            count = self.mysql_query(f"SELECT COUNT(*) FROM {table} WHERE id='{identity}';")
            self.check("HTTP-created " + table + " row is present in actual MySQL", count == "1")

    def run_flow(self):
        self.phase = "before-restart"
        base = self.app.start()
        visitor = Client(self, base)
        visitor.request("GET", API + "/workspaces", expected=(401,))
        visitor.request("POST", API + "/auth/register", {"username": "must-not-register", "password": "not-used-123456"},
                        expected=(403,), csrf=False)
        self.check("unauthenticated reads and missing-CSRF writes are denied", True)
        framework = visitor.request("GET", API + "/framework")
        self.check("framework does not claim complete product readiness", framework["businessReady"] is False)
        username = "smoke-" + uuid.uuid4().hex[:20]
        password = secrets.token_urlsafe(24)
        self.secrets.add(password)
        client = Client(self, base)
        session = client.register_and_login(username, password)
        self.check("public identity grants learner role only", session["roles"] == ["LEARNER"])
        capabilities = self.capabilities(client)
        active = self.wait_for("opt-in demo release becomes available",
                               lambda: client.request("GET", API + "/releases/active", expected=(200, 503)),
                               lambda value: bool(value.get("releaseId")))
        release = active["releaseId"]
        query = urllib.parse.urlencode({"releaseId": release})
        concepts = client.request("GET", API + "/concepts?" + query)["items"]
        self.check("published demo contains all 16 versioned concepts with evidence",
                   len(concepts) == 16 and {item["code"] for item in concepts} == {f"G{i:02d}" for i in range(1, 17)}
                   and all(item["releaseId"] == release and item["evidence"] for item in concepts))
        page = client.request("GET", API + "/exercises?" + query + "&limit=100")
        self.check("public exercise DTOs omit answer specifications and hidden grading data",
                   len(page["items"]) == 33 and {cid for item in page["items"] for cid in item["conceptIds"]} == {c["id"] for c in concepts}
                   and all(set(item) == {"id", "revision", "releaseId", "kind", "prompt", "difficulty", "conceptIds", "options", "answerKind", "textPolicy"}
                           and item["answerKind"] == "CHOICE" and item["textPolicy"] is None
                           and all(set(option) == {"id", "text"} for option in item["options"])
                           for item in page["items"]))
        # Read only the packaged demo fixture as a test oracle; no answers are fetched from public HTTP.
        oracle = self.fixture["exercises"][0]
        exercise = next((item for item in page["items"] if item["prompt"] == oracle["prompt"]), None)
        self.check("packaged demo oracle matches an exposed question", exercise is not None)
        wrong = next(option["id"] for option in exercise["options"] if option["id"] not in oracle["correctOptionIds"])
        workspace_input = {"releaseId": release, "title": "Smoke: generics evidence workflow", "guidanceMode": "COLLABORATIVE"}
        workspace_key = "workspace-" + uuid.uuid4().hex
        workspace = client.request("POST", API + "/workspaces", workspace_input, expected=(201,), key=workspace_key)
        workspace_id = workspace["id"]
        replay = client.request("POST", API + "/workspaces", workspace_input, expected=(201,), key=workspace_key)
        self.check("idempotent workspace retry preserves identity and ordered stream", replay == workspace)
        client.request("POST", API + "/workspaces", {**workspace_input, "title": "different input"},
                       expected=(409,), key=workspace_key)
        goal = client.request("POST", API + f"/workspaces/{workspace_id}/goals",
                              {"text": "Explain PECS using versioned Java evidence", "acceptance": ["Reference an actual published Java source"]},
                              expected=(201,), key="goal-" + uuid.uuid4().hex)
        memory = client.request("POST", API + f"/workspaces/{workspace_id}/memories",
                                {"summary": "I prefer a concrete PECS example."}, expected=(201,), key="memory-" + uuid.uuid4().hex)
        self.check("learner preference is labeled user-stated rather than inferred mastery", memory["basis"] == "USER_STATED")
        attempt_input = {"workspaceId": workspace_id, "releaseId": release, "exerciseId": exercise["id"],
                         "exerciseRevision": exercise["revision"], "answer": {"kind": "CHOICE", "optionIds": [wrong]}}
        attempt_key = "attempt-" + uuid.uuid4().hex
        attempt = client.request("POST", API + "/attempts", attempt_input, expected=(201,), key=attempt_key)
        self.check("incorrect first choice is server-graded and version-pinned",
                   attempt["status"] == "GRADED" and attempt["score"] == 0 and attempt["maximum"] > 0
                   and attempt["hintLevel"] == 0 and attempt["jobId"] is None
                   and attempt["workspaceId"] == workspace_id and attempt["releaseId"] == release
                   and attempt["evidenceRef"]["kind"] == "ATTEMPT"
                   and bool(re.fullmatch(r"[a-f0-9]{64}", attempt["evidenceRef"]["contentHash"])))
        repeated = client.request("POST", API + "/attempts", attempt_input, expected=(201,), key=attempt_key)
        self.check("idempotent grading retry does not create another attempt", repeated == attempt)
        progress = self.wait_for("outbox applies independent learning evidence",
                                 lambda: client.request("GET", API + "/me/progress"),
                                 lambda value: not value["rebuilding"] and bool(value["items"]))
        self.check("one wrong family contributes one sample and zero correct weight",
                   sum(item["independentSamples"] for item in progress["items"]) == 1
                   and sum(item["weightedCorrect"] for item in progress["items"]) == 0)
        reviews = client.request("GET", API + "/me/reviews")
        self.check("graded error produces a pending evidence-linked review",
                   any(item["originAttemptId"] == attempt["id"] and item["status"] == "PENDING" for item in reviews))
        practiced = client.request("POST", API + "/attempts", attempt_input, expected=(201,), key="practice-" + uuid.uuid4().hex)
        self.check("repeat practice after feedback is not counted as independent", practiced["hintLevel"] == 4)
        progress = self.wait_for("repeat-practice outbox catches up", lambda: client.request("GET", API + "/me/progress"),
                                 lambda value: not value["rebuilding"])
        self.check("repeating the same exercise family cannot farm mastery samples",
                   sum(item["independentSamples"] for item in progress["items"]) == 1)
        notebook = client.request("POST", API + f"/workspaces/{workspace_id}/notebook-drafts",
                                  {"attemptIds": [attempt["id"]], "title": "Evidence-backed correction notebook"},
                                  expected=(201,), key="notebook-" + uuid.uuid4().hex)
        version = client.request("GET", API + f"/artifacts/{notebook['id']}/revisions?revision=1")
        self.check("notebook persists a draft DSL with actual attempt and knowledge provenance",
                   notebook["kind"] == "NOTEBOOK" and notebook["currentRevision"] == 1
                   and version["reviewStatus"] == "DRAFT" and version["content"]["kind"] == "NOTEBOOK"
                   and any(ref["kind"] == "ATTEMPT" and ref["id"] == attempt["id"] for ref in version["inputRefs"])
                   and any(ref["kind"] == "KNOWLEDGE_CHUNK" for ref in version["inputRefs"])
                   and any(block["attemptId"] == attempt["id"] for block in version["content"]["blocks"]))
        # Append before freezing the context: later tutor/document/export sources must bind
        # this current notebook revision, while the original revision remains immutable.
        origin_block = version["content"]["blocks"][0]
        block_options = client.request("GET", API + f"/notebooks/{notebook['id']}/block-options?" + urllib.parse.urlencode(
            {"artifactRevision": 1, "originBlockId": origin_block["id"]}))
        self.check("notebook discovers a bounded type activity from its actual source block",
                   "TYPE_FLOW" in block_options["availableKinds"] and block_options["typeFlowDefaults"]["operations"])
        interactive_version = client.request("POST", API + f"/notebooks/{notebook['id']}/blocks",
            {"artifactRevision": 1, "originBlockId": origin_block["id"], "kind": "TYPE_FLOW",
             "config": block_options["typeFlowDefaults"]}, expected=(201,), key="notebook-type-" + uuid.uuid4().hex)
        type_block = next(block for block in interactive_version["content"]["blocks"] if block["kind"] == "TYPE_FLOW")
        notebook = client.request("GET", API + f"/artifacts/{notebook['id']}")
        self.check("adding an activity persists a new source revision and preserves the original block",
                   interactive_version["revision"] == notebook["currentRevision"] == 2
                   and interactive_version["content"]["blocks"][0] == origin_block
                   and type_block["config"] == block_options["typeFlowDefaults"])
        initial_state = client.request("GET", API + f"/notebooks/{notebook['id']}/state?artifactRevision=2")
        type_state = next(block for block in initial_state["blocks"] if block["blockId"] == type_block["id"])
        type_input = {"sourceType": type_block["config"]["sourceType"], "targetType": type_block["config"]["targetType"],
                      "operation": type_block["config"]["operations"][0], "prediction": "UNKNOWN"}
        progress_before_type = client.request("GET", API + "/me/progress")
        type_action = client.request("POST", API + f"/notebooks/{notebook['id']}/actions",
            {"artifactRevision": 2, "blockId": type_block["id"], "expectedBlockRevision": type_block["revision"],
             "expectedStateRevision": type_state["stateRevision"], "action": "selectType", "selection": "",
             "answer": None, "typeInput": type_input}, key="notebook-lab-" + uuid.uuid4().hex)
        self.check("notebook type selection persists a real finite laboratory result and increments state",
                   type_action["status"] == "COMPLETED" and type_action["stateRevision"] == 1
                   and type_action["attemptId"] is None and type_action["compileJobId"] is None
                   and type_action["labSession"]["workspaceId"] == workspace_id
                   and type_action["labSession"]["verdict"] in ("ALLOWED", "DENIED")
                   and type_action["labSession"]["predictionCorrect"] is None
                   and client.request("GET", API + "/labs/sessions/" + type_action["labSession"]["id"]) == type_action["labSession"])
        self.check("notebook type exploration does not manufacture mastery evidence",
                   client.request("GET", API + "/me/progress") == progress_before_type)
        search = client.request("GET", API + "/knowledge/search?" + urllib.parse.urlencode({"releaseId": release, "q": "PECS", "topK": 5}))
        self.check("local retrieval returns real versioned sources without inventing an answer",
                   search["items"] and search["insufficientEvidence"] is False
                   and all(item["releaseId"] == release and item["source"]["url"] and item["contentHash"] for item in search["items"]))
        context = client.request("POST", API + f"/workspaces/{workspace_id}/context", expected=(201,))
        self.check("context includes the saved goal, preference and notebook",
                   {"GOAL", "MEMORY", "ARTIFACT"} <= {ref["kind"] for ref in context["references"]})
        run = client.request("POST", API + f"/workspaces/{workspace_id}/evidence-runs",
                             {"goalId": goal["id"], "snapshotId": context["id"], "query": "PECS"},
                             expected=(202,), key="evidence-" + uuid.uuid4().hex)
        run = self.wait_for("registered deterministic evidence workflow completes",
                            lambda: client.request("GET", API + f"/agent-runs/{run['id']}"),
                            lambda value: value["status"] in ("COMPLETED", "FAILED", "PARTIAL", "CANCELLED"))
        self.check("durable steps carry verified outputs and no invented model charges",
                   run["status"] == "COMPLETED" and len(run["steps"]) == 2
                   and all(step["status"] == "SUCCEEDED" and step["outputRefs"] for step in run["steps"])
                   and any(ref["kind"] == "KNOWLEDGE_CHUNK" for step in run["steps"] for ref in step["outputRefs"])
                   and all(run[field] == 0 for field in ("spentMicros", "reservedMicros", "spentTokens", "reservedTokens")))
        unavailable = client.request("POST", API + f"/agent-runs/{run['id']}/commands",
                                     {"kind": "STEER", "expectedPlanRevision": run["planRevision"],
                                      "expectedRunEpoch": run["runEpoch"], "instruction": "Invent an expert answer"}, expected=(503,))
        self.check("unconfigured language-model steering explicitly fails", unavailable.get("code") == "CAPABILITY_UNAVAILABLE")
        unsupported = client.request("POST", API + "/attempts", {**attempt_input, "answer": {"kind": "CODE", "source": "class Demo {}"}},
                                     expected=(400,), key="code-" + uuid.uuid4().hex)
        self.check("code answers cannot be submitted to a selection exercise", unsupported.get("code") == "INVALID_INPUT")
        # v0.4: actual HTTP linkage, persisted on both H2 and MySQL, then compared after restart.
        preference = client.request("GET", API + "/me/preferences")
        preference = client.request("PUT", API + "/me/preferences", {**preference, "theme": "dark", "reducedMotion": True},
                                    extra_headers={"If-Match": '"' + str(preference["revision"]) + '"'})
        self.check("preference CAS persists a new revision", preference["revision"] == 2 and preference["theme"] == "dark")
        lab = client.request("POST", API + "/labs/sessions", {"workspaceId": workspace_id, "releaseId": release,
                             "labType": "ASSIGNMENT", "sourceType": "List<Integer>", "targetType": "List<Number>", "prediction": "ALLOWED"},
                             expected=(201,), key="lab-" + uuid.uuid4().hex)
        self.check("finite laboratory records a real supported type verdict without executing code",
                   lab["verdict"] == "DENIED" and lab["predictionCorrect"] is False and lab["jobId"] is None)
        reading = client.request("POST", API + "/learning-events", {"workspaceId": workspace_id, "releaseId": release,
                                 "conceptId": concepts[0]["id"], "eventType": "READ_COMPLETED"}, expected=(201,), key="reading-" + uuid.uuid4().hex)
        self.check("reading is activity evidence with a server timestamp", reading["evidenceClass"] == "ACTIVITY_NOT_MASTERY" and bool(reading["occurredAt"]))
        block = version["content"]["blocks"][0]
        action = client.request("POST", API + f"/notebooks/{notebook['id']}/actions", {"artifactRevision": interactive_version["revision"], "blockId": block["id"],
                                "expectedBlockRevision": block["revision"], "action": "submitAnswer", "selection": "",
                                "answer": {"kind": "CHOICE", "optionIds": oracle["correctOptionIds"]}}, key="notebook-action-" + uuid.uuid4().hex)
        corrected = client.request("GET", API + "/attempts/" + action["attemptId"])
        self.check("notebook action calls the real grader and keeps repeated-family practice assisted",
                   action["status"] == "COMPLETED" and corrected["score"] == 100 and corrected["hintLevel"] == 4)
        conversation = client.request("POST", API + "/tutor/conversations", {"workspaceId": workspace_id, "releaseId": release, "title": "PECS evidence"},
                                      expected=(201,), key="conversation-" + uuid.uuid4().hex)
        tutor = client.request("POST", API + "/tutor/requests", {"conversationId": conversation["id"], "snapshotId": context["id"],
                               "mode": "EVIDENCE_ONLY", "question": "PECS 泛型通配符", "hintLevel": 1, "attemptId": attempt["id"]},
                               expected=(202,), key="tutor-" + uuid.uuid4().hex)
        tutor = self.wait_for("durable evidence-only tutor completes with real citations",
                              lambda: client.request("GET", API + "/tutor/requests/" + tutor["id"]),
                              lambda value: value["status"] in ("COMPLETED", "FAILED", "CANCELLED"))
        self.check("evidence tutor distinguishes deterministic output from a model call",
                   tutor["status"] == "COMPLETED" and tutor["mode"] == "EVIDENCE_ONLY" and tutor["evidenceRefs"]
                   and tutor["usage"]["reservedTokens"] == 0 and tutor["sourceState"] == "CURRENT")
        document = client.request("POST", API + f"/tutor/requests/{tutor['id']}/document", {"title": "PECS evidence document"},
                                  expected=(201,), key="tutor-document-" + uuid.uuid4().hex)
        document_id = document["artifactId"]
        document_version = client.request("GET", API + f"/artifacts/{document_id}/revisions?revision=1")
        self.check("tutor document remains DRAFT and inherits revocable memory provenance",
                   document_version["reviewStatus"] == "DRAFT" and any(ref["kind"] == "MEMORY" for ref in document_version["inputRefs"]))
        export = client.request("POST", API + f"/artifacts/{document_id}/exports", {"artifactRevision": 1, "format": "HTML"},
                                expected=(202,), key="export-" + uuid.uuid4().hex)
        export = self.wait_for("persistent text export produces a real file",
                               lambda: client.request("GET", API + "/export-jobs/" + export["id"]),
                               lambda value: value["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"))
        self.check("successful export exposes an authenticated file whose bytes match the stored hash",
                   export["status"] == "SUCCEEDED" and export["downloadPath"] is not None
                   and hashlib.sha256(client.download(export["downloadPath"])).hexdigest() == export["contentHash"])
        # Independent workspace keeps this journey's original event cursor assertions exact.
        studio = client.request("POST", API + "/workspaces", {"releaseId": release, "title": "Study and native files", "guidanceMode": "COLLABORATIVE"}, expected=(201,), key="studio-" + uuid.uuid4().hex)
        studio_goal = client.request("POST", API + f"/workspaces/{studio['id']}/goals", {"text": "Explain Java PECS extends super wildcard type safety", "acceptance": ["Use published knowledge and editable drafts"]}, expected=(201,), key="study-goal-" + uuid.uuid4().hex)
        studio_context = client.request("POST", API + f"/workspaces/{studio['id']}/context", expected=(201,))
        study = client.request("POST", API + f"/workspaces/{studio['id']}/agent-runs", {"goalId": studio_goal["id"], "snapshotId": studio_context["id"], "requestedOutputs": ["DOCUMENT", "SLIDES"], "mode": "EVIDENCE_ONLY", "budget": {"maxTokens": 100000, "maxWallMillis": 600000, "maxCostMicros": 1000000, "maxParallel": 3}}, expected=(202,), key="study-" + uuid.uuid4().hex)
        study = self.wait_for("actual study workflow completes planning, specialist, review and outputs", lambda: client.request("GET", API + "/agent-runs/" + study["id"]), lambda value: value["status"] in ("COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "WAITING_USER"))
        self.check("study workflow persists a completed revised DAG without inventing model usage", study["status"] == "COMPLETED" and study["planRevision"] == 2 and study["spentTokens"] == study["reservedTokens"] == 0)
        native_exports = []
        for step_key, file_format in (("output-document", "DOCX"), ("output-slides", "PPTX")):
            source = next(step for step in study["steps"] if step["key"] == step_key)["outputRefs"][0]
            job = client.request("POST", API + f"/artifacts/{source['id']}/exports", {"artifactRevision": source["revision"], "format": file_format}, expected=(202,), key="native-" + uuid.uuid4().hex)
            job = self.wait_for(file_format + " file is actually rendered", lambda: client.request("GET", API + "/export-jobs/" + job["id"]), lambda value: value["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"))
            self.check(file_format + " exposes valid Office ZIP bytes with matching SHA-256", job["status"] == "SUCCEEDED" and job["downloadPath"] is not None and client.download(job["downloadPath"]).startswith(b"PK") and hashlib.sha256(client.download(job["downloadPath"])).hexdigest() == job["contentHash"])
            native_exports.append(job)
        # A user-labelled singleton proves durable retrieval execution, not independent
        # relevance quality: its expected chunk comes from the real published search API.
        progress_before_evaluation = self.wait_for("learning evidence settles before retrieval evaluation",
            lambda: client.request("GET", API + "/me/progress"), lambda value: not value["rebuilding"])
        evaluation_suite = client.request("POST", API + f"/workspaces/{studio['id']}/evaluation-suites",
            {"title": "HTTP restart retrieval evidence", "description": "Private persistence fixture; labels follow an observed public search, not independent teaching-quality review.",
             "cases": [{"key": "published-pecs", "query": "PECS", "conceptId": None, "expectation": "MATCH",
                        "expectedChunkIds": [search["items"][0]["chunkId"]], "note": "Fixed published result selected through the real search API."}]},
            expected=(201,), key="evaluation-suite-" + uuid.uuid4().hex)
        self.check("evaluation suite freezes the real workspace release and observed knowledge revision",
                   evaluation_suite["workspaceId"] == studio["id"] and evaluation_suite["releaseId"] == release
                   and evaluation_suite["revision"] == 1 and evaluation_suite["caseCount"] == 1
                   and evaluation_suite["cases"][0]["expectedChunks"][0]["chunkId"] == search["items"][0]["chunkId"]
                   and evaluation_suite["cases"][0]["expectedChunks"][0]["revision"] == search["items"][0]["revision"]
                   and evaluation_suite["cases"][0]["expectedChunks"][0]["contentHash"] == search["items"][0]["contentHash"])
        evaluation = client.request("POST", API + f"/evaluation-suites/{evaluation_suite['id']}/runs",
            {"suiteRevision": evaluation_suite["revision"], "topK": 5}, expected=(202,), key="evaluation-run-" + uuid.uuid4().hex)
        evaluation = self.wait_for("durable retrieval evaluation completes with actual case results",
            lambda: client.request("GET", API + "/evaluation-runs/" + evaluation["id"]),
            lambda value: value["status"] in ("COMPLETED", "FAILED", "CANCELLED"))
        self.check("complete evaluation binds metrics and its report to the fixed suite and corpus",
                   evaluation["status"] == "COMPLETED" and evaluation["completedCases"] == evaluation["caseCount"] == 1
                   and evaluation["suiteHash"] == evaluation_suite["contentHash"]
                   and evaluation["corpusHash"] == evaluation_suite["corpusHash"]
                   and evaluation["indexVersion"] == evaluation_suite["indexVersion"] == search["indexVersion"]
                   and evaluation["metrics"]["totalCases"] == evaluation["metrics"]["passedCases"] == 1
                   and evaluation["results"][0]["passed"] is True and evaluation["reportPath"] is not None
                   and bool(re.fullmatch(r"[a-f0-9]{64}", evaluation["resultHash"])))

        def evaluation_attachment(reader):
            path = evaluation["reportPath"]
            if path != API + f"/evaluation-runs/{evaluation['id']}/report":
                raise SmokeFailure("Refusing an evaluation attachment outside its authenticated run")
            started = time.monotonic()
            with reader.opener.open(reader.base + path, timeout=8) as response:
                payload = response.read(MAX_RESPONSE + 1)
                status, headers = response.status, response.headers
            self.report["http"].append({"phase": self.phase, "method": "GET", "path": path,
                "status": status, "milliseconds": round((time.monotonic() - started) * 1000)})
            digest = hashlib.sha256(payload).hexdigest()
            self.check("evaluation JSON attachment is private, bounded and matches its server byte hash",
                       status == 200 and 0 < len(payload) <= 2 * 1024 * 1024
                       and headers.get_content_type() == "application/json"
                       and headers.get("Content-Disposition", "").startswith("attachment;")
                       and headers.get("Cache-Control") == "private, no-store"
                       and headers.get("X-Content-Type-Options") == "nosniff"
                       and headers.get("X-Content-SHA256") == digest)
            return json.loads(payload), digest

        evaluation_report, evaluation_report_hash = evaluation_attachment(client)
        self.check("downloaded evaluation contains the actual frozen run results and explicit private scope",
                   evaluation_report["format"] == "EVALUATION_REPORT_V1"
                   and evaluation_report["scope"] == "PRIVATE_USER_LABELLED_PUBLISHED_RETRIEVAL"
                   and evaluation_report["runId"] == evaluation["id"] and evaluation_report["suiteId"] == evaluation_suite["id"]
                   and evaluation_report["suiteHash"] == evaluation_suite["contentHash"]
                   and evaluation_report["resultHash"] == evaluation["resultHash"]
                   and evaluation_report["metrics"] == evaluation["metrics"] and evaluation_report["results"] == evaluation["results"])
        self.check("retrieval evaluation does not manufacture learner mastery evidence",
                   client.request("GET", API + "/me/progress") == progress_before_evaluation)
        notebook_state = client.request("GET", API + f"/notebooks/{notebook['id']}/state?artifactRevision=2")
        saved_type_state = next(block for block in notebook_state["blocks"] if block["blockId"] == type_block["id"])
        self.check("notebook state retains selected types and the exact persisted server result",
                   saved_type_state["stateRevision"] == 1 and saved_type_state["typeInput"] == type_input
                   and saved_type_state["lastResult"] == type_action)
        extras = {"preference": preference, "lab": lab, "tutor": tutor, "export": export, "documentId": document_id,
                  "study": study, "nativeExports": native_exports, "interactiveVersion": interactive_version,
                  "notebookState": notebook_state, "typeLab": type_action["labSession"], "evaluationReportHash": evaluation_report_hash}
        attacker = Client(self, base)
        other_password = secrets.token_urlsafe(24)
        self.secrets.add(other_password)
        other_username = "smoke-other-" + uuid.uuid4().hex[:16]
        attacker.register_and_login(other_username, other_password)
        private_paths = (f"/workspaces/{workspace_id}", f"/attempts/{attempt['id']}",
                         f"/artifacts/{notebook['id']}", f"/agent-runs/{run['id']}",
                         f"/notebooks/{notebook['id']}/state?artifactRevision=2",
                         f"/evaluation-suites/{evaluation_suite['id']}", f"/evaluation-runs/{evaluation['id']}",
                         f"/evaluation-runs/{evaluation['id']}/report")
        for path in private_paths:
            attacker.request("GET", API + path, expected=(404,))
        self.check("another authenticated learner cannot access private workspace, notebook state, evaluation or report IDs", True)
        projection = self.wait_for("ordered workspace projection catches up without dead letters",
                                   lambda: client.request("GET", API + f"/workspaces/{workspace_id}/projection"),
                                   lambda value: value["caughtUp"] and value["deadLetters"] == 0)
        events = client.request("GET", API + f"/workspaces/{workspace_id}/events?after=0&limit=100")
        self.check("workspace events form a durable contiguous semantic stream",
                   [item["sequence"] for item in events] == list(range(1, len(events) + 1))
                   and len({item["id"] for item in events}) == len(events)
                   and projection["sourceSequence"] == len(events)
                   and {"WORKSPACE_CREATED", "GOAL_CREATED", "ATTEMPT_GRADED", "NOTEBOOK_DRAFT_BUILT"} <= {item["type"] for item in events})
        run_events = client.request("GET", API + f"/agent-runs/{run['id']}/events?after=0&limit=100")
        self.check("agent execution history has a strictly ordered durable cursor",
                   bool(run_events) and [item["sequence"] for item in run_events] == list(range(1, len(run_events) + 1)))
        saved = {"userId": session["userId"], "workspaceId": workspace_id, "attempt": attempt,
                 "notebook": notebook, "version": version, "run": run, "events": events,
                 "runEvents": run_events, "capabilities": capabilities,
                 "evaluationSuite": evaluation_suite, "evaluationRun": evaluation,
                 "progress": client.request("GET", API + "/me/progress"),
                 "reviews": client.request("GET", API + "/me/reviews"),
                 "memories": client.request("GET", API + f"/workspaces/{workspace_id}/memories")}
        self.verify_private_sql(saved)
        # Admit a personal lesson just before stopping the JVM. The next JVM must finish the same run.
        chapter = next(item for item in concepts if item["code"] == "G06")
        personal_space = client.request("POST", API + "/workspaces", {
            "releaseId": release, "title": "Personal lesson restart", "guidanceMode": "COLLABORATIVE"},
            expected=(201,), key="personal-space-" + uuid.uuid4().hex)
        baseline_space = client.request("POST", API + "/workspaces", {
            "releaseId": release, "title": "First lessons", "guidanceMode": "COLLABORATIVE"},
            expected=(201,), key="baseline-space-" + uuid.uuid4().hex)
        client.request("POST", API + f"/workspaces/{personal_space['id']}/memories", {"summary":"我希望先看代码再解释规则。"},
                       expected=(201,), key="course-memory-" + uuid.uuid4().hex)
        editions = {}
        for topic in concepts:
            concept_id = topic["id"]
            def generate(room):
                path = API + f"/workspaces/{room['id']}/courseware/{concept_id}"
                return client.request("POST", path, {"approach":"AUTO"}, expected=(201,), key="lesson-" + uuid.uuid4().hex)
            ordinary, personal = generate(baseline_space), generate(personal_space)
            source = next((scene for scene in personal["scenes"] if scene["kind"] == "EXAMPLE"), None)
            self.check(topic["code"] + " differentiates actual personal lesson activities over the same released topic",
                       ordinary["releaseId"] == personal["releaseId"] == release
                       and ordinary["conceptId"] == personal["conceptId"] == concept_id
                       and ordinary["strategy"] == "FOUNDATION" and personal["strategy"] == "EXAMPLE_FIRST"
                       and ordinary["scenes"][0]["kind"] == "READ" and personal["scenes"][0]["kind"] in ("EXAMPLE","PREDICT")
                       and any(s["kind"] == "PRACTICE" and s["exerciseId"] for s in personal["scenes"])
                       and (source is None or (source == personal["scenes"][0]
                            and source["exampleSha256"] == hashlib.sha256(source["body"].encode()).hexdigest())))
            editions[concept_id] = personal
        personal_base = f"/workspaces/{personal_space['id']}/courseware/{chapter['id']}"
        lesson = editions[chapter['id']]
        self.check("personal lesson before restart opens with the selected published code", lesson["scenes"][0]["kind"] == "EXAMPLE")
        personal_key = "personal-study-" + uuid.uuid4().hex
        personal_input = {"planId": lesson["id"], "mode": "EVIDENCE_ONLY"}
        admitted = client.request("POST", API + personal_base + "/study", personal_input, expected=(202,), key=personal_key)
        version_path = personal_base + "?planId=" + lesson["id"]
        resume_path = personal_base + "/study?planId=" + lesson["id"] + "&mode=EVIDENCE_ONLY"
        private_paths += (version_path, resume_path)
        self.app.stop()
        self.phase = "after-restart"
        base = self.app.start()
        client.base = base
        client.request("GET", API + "/auth/session", expected=(401,))
        restored = Client(self, base)
        session_after = restored.login(username, password)
        self.check("persisted credentials survive process restart and require a fresh session",
                   session_after["userId"] == saved["userId"])
        reloaded = restored.request("GET", API + f"/workspaces/{workspace_id}")
        self.check("workspace and release binding survive restart", reloaded["id"] == workspace_id and reloaded["releaseId"] == release)
        comparisons = [
            ("saved appearance preferences", "/me/preferences", extras["preference"]),
            ("finite type experiment", f"/labs/sessions/{extras['lab']['id']}", extras["lab"]),
            ("tutor answer and accounting", f"/tutor/requests/{extras['tutor']['id']}", extras["tutor"]),
            ("actual export file metadata", f"/export-jobs/{extras['export']['id']}", extras["export"]),
            ("immutable graded attempt", f"/attempts/{attempt['id']}", saved["attempt"]),
            ("notebook DSL and provenance hash", f"/artifacts/{notebook['id']}/revisions?revision=1", saved["version"]),
            ("current notebook activity source", f"/artifacts/{notebook['id']}/revisions?revision=2", extras["interactiveVersion"]),
            ("current notebook metadata", f"/artifacts/{notebook['id']}", saved["notebook"]),
            ("notebook interaction state and receipt", f"/notebooks/{notebook['id']}/state?artifactRevision=2", extras["notebookState"]),
            ("notebook-generated finite type session", f"/labs/sessions/{extras['typeLab']['id']}", extras["typeLab"]),
            ("private retrieval evaluation suite", f"/evaluation-suites/{evaluation_suite['id']}", saved["evaluationSuite"]),
            ("completed retrieval evaluation and metrics", f"/evaluation-runs/{evaluation['id']}", saved["evaluationRun"]),
            ("durable run and output references", f"/agent-runs/{run['id']}", saved["run"]),
            ("workspace event stream", f"/workspaces/{workspace_id}/events?after=0&limit=100", saved["events"]),
            ("agent execution history", f"/agent-runs/{run['id']}/events?after=0&limit=100", saved["runEvents"]),
            ("learning evidence projection", "/me/progress", saved["progress"]),
            ("evidence-linked reviews", "/me/reviews", saved["reviews"]),
            ("user-stated memory", f"/workspaces/{workspace_id}/memories", saved["memories"]),
        ]
        for name, path, expected in comparisons:
            actual = restored.request("GET", API + path)
            self.check(name + " survives process restart unchanged", actual == expected)
        self.check("capability honesty survives restart", self.capabilities(restored) == saved["capabilities"])
        self.verify_private_sql(saved)
        self.check("identical personal storyboard and frozen sources survive an actual process restart",
                   restored.request("GET", API + version_path) == lesson)
        continued = self.wait_for("new JVM completes the previously admitted personal teaching run",
            lambda: restored.request("GET", API + resume_path),
            lambda row: row["status"] in ("COMPLETED","PARTIAL","FAILED","CANCELLED","WAITING_USER"), timeout=90)
        self.check("restored lesson reuses the same run and workspace",
                   continued["id"] == admitted["id"] and continued["status"] == "COMPLETED"
                   and restored.request("POST", API + personal_base + "/study", personal_input, expected=(202,), key=personal_key)["id"] == admitted["id"])
        produced = next(step["outputRefs"][0] for step in continued["steps"] if step["key"] == "output-slides")
        deck = restored.request("GET", API + f"/artifacts/{produced['id']}/revisions?revision={produced['revision']}")
        leading = deck["content"]["slides"][0]
        self.check("persisted editable slides keep the student's original example and its code",
                   deck["contentHash"] == produced["contentHash"] and deck["reviewStatus"] == "DRAFT"
                   and leading["elements"][0]["kind"] == "CODE" and lesson["scenes"][0]["body"].startswith(leading["elements"][0]["text"]))
        restored_evaluation_report, restored_evaluation_hash = evaluation_attachment(restored)
        self.check("private evaluation attachment bytes and frozen JSON survive actual process restart",
                   restored_evaluation_hash == extras["evaluationReportHash"] and restored_evaluation_report == evaluation_report)
        restarted_attacker = Client(self, base)
        restarted_attacker.login(other_username, other_password)
        for path in private_paths:
            restarted_attacker.request("GET", API + path, expected=(404,))
        self.check("private notebook state and evaluation evidence remain inaccessible to another user after restart", True)
        self.check("export bytes survive actual process restart", hashlib.sha256(restored.download(extras["export"]["downloadPath"])).hexdigest() == extras["export"]["contentHash"])
        self.check("study DAG and output references survive restart unchanged", restored.request("GET", API + "/agent-runs/" + extras["study"]["id"]) == extras["study"])
        for native in extras["nativeExports"]:
            self.check(native["format"] + " bytes survive process restart", restored.request("GET", API + "/export-jobs/" + native["id"]) == native and hashlib.sha256(restored.download(native["downloadPath"])).hexdigest() == native["contentHash"])
        restored.request("POST", API + f"/memories/{memory['id']}/revoke", {"expectedRevision": memory["revision"]}, expected=(204,))
        stale_export = restored.request("GET", API + "/export-jobs/" + extras["export"]["id"])
        stale_tutor = restored.request("GET", API + "/tutor/requests/" + extras["tutor"]["id"])
        self.check("memory revocation hides dependent tutor answer and prevents old download",
                   stale_export["downloadPath"] is None and stale_export["sourceState"] == "STALE"
                   and stale_tutor["sourceState"] != "CURRENT" and not stale_tutor["answer"])
        restored.request("GET", extras["export"]["downloadPath"], expected=(409,))
        restored.request("DELETE", API + "/auth/session", expected=(204,))
        restored.request("GET", API + f"/workspaces/{workspace_id}", expected=(401,))
        self.check("logout invalidates subsequent private-resource access", True)
        self.report["resources"] = {"userId": saved["userId"], "workspaceId": workspace_id,
                                    "attemptId": attempt["id"], "notebookId": notebook["id"], "runId": run["id"],
                                    "notebookRevision": interactive_version["revision"], "notebookStateRevision": saved_type_state["stateRevision"],
                                    "evaluationSuiteId": evaluation_suite["id"], "evaluationRunId": evaluation["id"],
                                    "evaluationReportSha256": evaluation_report_hash}
        self.app.stop()
        self.app.check_logs()
        self.check("packaged jar stayed unchanged throughout verification",
                   hashlib.sha256(self.jar.read_bytes()).hexdigest() == self.report["jarSha256"])

    def execute(self):
        output = self.args.output or ROOT / ".work" / ("smoke-runtime-" + self.args.profile + ".json")
        write_report(output, self.report)
        try:
            before = capture_inputs("backend", ("scripts/smoke_runtime.py",))
            self.report["verificationInputs"] = before
            self.prepare()
            self.run_flow()
            self.report["inputsUnchanged"] = require_unchanged(before)
            self.report["result"] = "PASS"
        except Exception as failure:
            self.report["result"] = "FAIL"
            self.report["error"] = self.redact(type(failure).__name__ + ": " + str(failure))
        finally:
            try:
                self.app.stop()
            except Exception as failure:
                self.report["result"] = "FAIL"
                self.report["cleanupError"] = self.redact(str(failure))
            self.report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
            self.report["scope"] = "Real HTTP authentication, learning/artifact/evidence linkage and process-restart persistence; no model/media/compiler adapters are simulated"
            self.report.setdefault("applicationLogs", [str(path) for path in self.app.logs])
            write_report(self.work / "report.json", self.report)
            write_report(output, self.report)
            print(json.dumps({"result": self.report["result"], "profile": self.args.profile,
                              "engine": self.report.get("engine"), "checks": len(self.report["checks"]),
                              "report": str(output), "error": self.report.get("error")}, ensure_ascii=False, indent=2))
        return 0 if self.report["result"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, help="already packaged executable Spring Boot jar")
    parser.add_argument("--java", help="Java executable (default: JAVA_HOME/bin/java or PATH)")
    parser.add_argument("--profile", choices=("h2", "mysql"), default="h2")
    parser.add_argument("--startup-timeout", type=int, default=75)
    parser.add_argument("--output", type=Path, help="JSON report path (default: .work/smoke-runtime-PROFILE.json)")
    args = parser.parse_args()
    if not 5 <= args.startup_timeout <= 180:
        parser.error("--startup-timeout must be between 5 and 180 seconds")
    return Smoke(args).execute()


if __name__ == "__main__":
    sys.exit(main())
