#!/usr/bin/env python3
"""Prepare a fresh GitHub Actions MySQL service for the HTTP smoke test.

Requires GITHUB_ACTIONS=true, TYPEFORGE_CI_MYSQL_CONTAINER from job.services.mysql.id,
TYPEFORGE_CI_MYSQL_PORT, and a random TYPEFORGE_CI_MYSQL_PASSWORD supplied through env.
Only a verified, loopback-published mysql:8.4.13 container with an empty
typeforge_runtime schema is accepted. No existing schema or account is dropped.
Root is used over the private container socket; the application gets only its own
schema privileges. Credentials remain in mode-0600 files and are never printed.
"""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
DATABASE = "typeforge_runtime"
USERNAME = "typeforge_ci"
IMAGE = "mysql:8.4.13"


class PreparationError(RuntimeError):
    pass


def run(command, statement=None, timeout=20):
    return subprocess.run(command, input=statement, capture_output=True, text=True,
                          encoding="utf-8", timeout=timeout)


def private_file(path, text):
    # Exclusive creation refuses to overwrite an existing credential file.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(text)


def prepare():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise PreparationError("This bootstrap helper is restricted to disposable GitHub Actions services")
    container = os.environ.get("TYPEFORGE_CI_MYSQL_CONTAINER", "")
    if not re.fullmatch(r"[a-f0-9]{12,64}", container):
        raise PreparationError("An explicit job service container ID is required")
    password = os.environ.get("TYPEFORGE_CI_MYSQL_PASSWORD", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,80}", password):
        raise PreparationError("A random URL-safe CI password must be supplied through the environment")
    port = int(os.environ.get("TYPEFORGE_CI_MYSQL_PORT", "0"))
    if not 1 <= port <= 65535:
        raise PreparationError("Invalid loopback service port")
    env_file = os.environ.get("GITHUB_ENV")
    client = shutil.which("mysql")
    if not env_file or not Path(env_file).is_file() or client is None:
        raise PreparationError("GitHub environment file and independent MySQL client are required")
    inspected = run(["docker", "inspect", container])
    if inspected.returncode:
        raise PreparationError("Cannot inspect the explicitly selected CI service")
    details = json.loads(inspected.stdout)[0]
    if details["Config"]["Image"] != IMAGE or not details["State"]["Running"]:
        raise PreparationError("Only a running pinned MySQL CI image is supported")
    settings = set(details["Config"].get("Env", []))
    if not {"MYSQL_DATABASE=" + DATABASE, "MYSQL_ROOT_HOST=localhost", "MYSQL_ALLOW_EMPTY_PASSWORD=yes"} <= settings:
        raise PreparationError("Service is not configured as the expected isolated CI database")
    ports = details["NetworkSettings"]["Ports"].get("3306/tcp") or []
    if len(ports) != 1 or ports[0].get("HostIp") != "127.0.0.1" or ports[0].get("HostPort") != str(port):
        raise PreparationError("Service must publish its MySQL port on loopback only")
    admin = ["docker", "exec", "-i", container, "mysql", "--protocol=SOCKET", "--user=root",
             "--batch", "--raw", "--skip-column-names"]
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ready = run(admin, "SELECT VERSION();", timeout=5)
        if ready.returncode == 0:
            break
        time.sleep(.5)
    else:
        raise PreparationError("MySQL did not become query-ready")
    version = ready.stdout.strip()
    if version != "8.4.13":
        raise PreparationError("Unexpected MySQL server version")
    existing = run(admin, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + DATABASE + "';")
    if existing.returncode or existing.stdout.strip() != "0":
        raise PreparationError("Refusing to prepare a nonempty or inaccessible application schema")
    schemas = run(admin, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + DATABASE + "';")
    if schemas.returncode or schemas.stdout.strip() != "1":
        raise PreparationError("Expected service-owned runtime database is absent")
    # Every interpolated value is fixed or strictly validated above. SQL is passed on
    # stdin, never on argv, and raw SQL errors are not echoed because they may quote it.
    # MySQL database-level GRANT treats '_' as a wildcard unless escaped.
    # Match the official image's escaping so this account cannot create a similarly named schema.
    grant_database = DATABASE.replace("_", "\\_")
    setup = ("CREATE USER '" + USERNAME + "'@'%' IDENTIFIED BY '" + password + "';\n"
             "GRANT ALL PRIVILEGES ON `" + grant_database + "`.* TO '" + USERNAME + "'@'%';\n")
    created = run(admin, setup)
    if created.returncode:
        raise PreparationError("Could not create the new database-scoped CI login; no existing account was changed")
    work = ROOT / ".work/ci-mysql"
    work.mkdir(parents=True, exist_ok=True, mode=0o700)
    work.chmod(0o700)
    defaults = work / "client.cnf"
    connection = work / "connection.json"
    private_file(defaults, "[client]\nuser=" + USERNAME + "\npassword=" + password +
                 "\nhost=127.0.0.1\nport=" + str(port) +
                 "\nprotocol=TCP\nssl-mode=REQUIRED\nlocal-infile=0\n")
    config = {"purpose": "typeforge-isolated-verification", "host": "127.0.0.1", "port": port,
              "runtimeDatabase": DATABASE, "username": USERNAME, "password": password,
              "client": str(Path(client).resolve()), "appClientConfig": str(defaults), "version": version}
    private_file(connection, json.dumps(config, indent=2) + "\n")
    verified = run([client, "--defaults-file=" + str(defaults), "--batch", "--skip-column-names", DATABASE],
                   "SELECT VERSION(),DATABASE();")
    if verified.returncode or verified.stdout.strip() != version + "\t" + DATABASE:
        raise PreparationError("Independent host client could not verify the scoped MySQL connection")
    with Path(env_file).open("a", encoding="utf-8") as output:
        output.write("MYSQL_TEST_CONNECTION=" + str(connection) + "\n")
    print("Prepared isolated MySQL 8.4.13 runtime database with a scoped login and private credential files.")


def main():
    try:
        prepare()
        return 0
    except Exception as failure:
        # No subprocess stdout/stderr, SQL, environment values, or credentials are logged.
        message = str(failure) if isinstance(failure, PreparationError) else type(failure).__name__
        print("CI MySQL preparation failed: " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
