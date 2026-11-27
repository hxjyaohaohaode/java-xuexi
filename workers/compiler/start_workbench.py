"""Start the packaged teaching app and its isolated Java/Python worker on a local Docker host."""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

WORKER = Path(__file__).resolve().parent
IMAGE = "typeforge-compiler:jdk17"


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def serve(args):
    jar = args.jar.resolve(strict=True)
    subprocess.run(["docker", "build", "-t", IMAGE, str(WORKER)], check=True)
    token = secrets.token_urlsafe(48)
    base = f"http://127.0.0.1:{args.worker_port}"
    env = dict(os.environ, TYPEFORGE_COMPILER_TOKEN=token, TYPEFORGE_COMPILER_BIND="127.0.0.1",
               TYPEFORGE_COMPILER_PORT=str(args.worker_port), TYPEFORGE_COMPILER_IMAGE=IMAGE,
               TYPEFORGE_EXECUTION_ENABLED="true")
    broker = None
    app = None
    try:
        broker = subprocess.Popen([sys.executable, str(WORKER / "server.py")], env=env)
        request = urllib.request.Request(base + "/health", headers={"Authorization": "Bearer " + token})
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(200):
            if broker.poll() is not None:
                raise RuntimeError("Isolated code worker failed to start")
            try:
                with http.open(request, timeout=1) as response:
                    if json.load(response).get("executionEnabled") is True:
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(.2)
        else:
            raise RuntimeError("Isolated code worker did not become ready")
        env = dict(os.environ, TYPEFORGE_COMPILER_WORKER_TOKEN=token, TYPEFORGE_EXECUTION_ENABLED="true")
        app = subprocess.Popen(["java", "-jar", str(jar), "--spring.profiles.active=demo",
            "--server.address=127.0.0.1", f"--server.port={args.port}", "--typeforge.compiler.enabled=true",
            "--typeforge.compiler.worker-url=" + base + "/v1/compile"], env=env)
        print(f"课堂地址 http://127.0.0.1:{args.port}", flush=True)
        while app.poll() is None:
            if broker.poll() is not None:
                raise RuntimeError("Code worker stopped")
            time.sleep(.3)
        return app.returncode
    finally:
        stop(app)
        stop(broker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--worker-port", type=int, default=8091)
    args = parser.parse_args()
    if args.port == args.worker_port or not all(0 < port < 65536 for port in (args.port, args.worker_port)):
        parser.error("App and worker require distinct valid ports")
    def shutdown(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, shutdown)
    try:
        return serve(args)
    except KeyboardInterrupt:
        return 130
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
