"""Fixed container entry point for a single learning program. Installed in the read-only image."""
import json
import pathlib
import subprocess
import sys
import tempfile
import traceback


def run(language):
    if language == "JAVA":
        # A single private-to-broker header is emitted before student code starts. Keeping
        # compiler diagnostics in this frame preserves stdout even for successful warning cases.
        with tempfile.TemporaryFile() as compiler_log:
            compiled = subprocess.run([
                "javac", "-J-Xmx80m", "-J-XX:ActiveProcessorCount=1", "-J-Duser.language=en",
                "--release", "17", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
                "-classpath", "/input/empty", "-Xlint:all", "-XDrawDiagnostics", "-Xmaxerrs", "25",
                "-Xmaxwarns", "25", "-d", "/tmp", "/input/Main.java",
            ], stdout=compiler_log, stderr=subprocess.STDOUT)
            compiler_log.seek(0)
            diagnostics = compiler_log.read(4096).decode("utf-8", errors="replace")
        print(json.dumps({"compiler": diagnostics}, ensure_ascii=False), flush=True)
        if compiled.returncode:
            if compiled.returncode == 1:
                return 11
            return 12 if compiled.returncode == -9 else 13
        entry = "Main"
        if not pathlib.Path("/tmp/Main.class").exists() and pathlib.Path("/tmp/Demo.class").exists():
            entry = "Demo"
        command = ["java", "-Xms8m", "-Xmx64m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
                   "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-cp", "/tmp", entry]
    elif language == "PYTHON":
        print('{"compiler":""}', flush=True)
        name = "/input/main.py"
        try:
            compile(pathlib.Path(name).read_bytes(), name, "exec")
        except (SyntaxError, ValueError) as error:
            traceback.print_exception(type(error), error, None, limit=0)
            return 11
        command = [sys.executable, "-I", "-B", "-u", "-X", "utf8", name]
    else:
        return 13
    with open("/input/stdin.txt", "rb") as stdin:
        result = subprocess.run(command, stdin=stdin)
    if result.returncode == -9:
        return 12
    return 0 if result.returncode == 0 else 10


if __name__ == "__main__":
    sys.exit(run(sys.argv[1]))
