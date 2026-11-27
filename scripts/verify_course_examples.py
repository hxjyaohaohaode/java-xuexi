"""Recheck the 16 reviewed, repository-owned Java 17 course fixtures. Takes no learner input."""
import json
import hashlib
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    pack = json.loads((ROOT / 'knowledge/peer-course-20261118.json').read_text(encoding='utf-8'))
    results = []
    for example in pack['examples']:
        source = example['code'].encode('utf-8')
        if hashlib.sha256(source).hexdigest() != example['sha256']:
            raise ValueError('Source digest mismatch: ' + example['id'])
        with tempfile.TemporaryDirectory() as work:
            Path(work, 'Demo.java').write_bytes(source)
            compile_result = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '-proc:none', '--release', '17', '-Xlint:all', '-XDrawDiagnostics', 'Demo.java'],
                cwd=work, capture_output=True, text=True, timeout=15, check=False,
            )
            success = compile_result.returncode == 0
            if success != example['compiles'] or (example['warning'] and example['warning'] not in compile_result.stderr):
                raise RuntimeError('Unexpected compilation: ' + example['id'] + '\n' + compile_result.stderr)
            if success:
                run = subprocess.run(['java', '-cp', '.', 'Demo'], cwd=work, capture_output=True, text=True, timeout=10, check=True)
                if run.stdout.rstrip('\r\n') != example['stdout']:
                    raise RuntimeError('Unexpected stdout: ' + example['id'])
            results.append({'id': example['id'], 'compiled': success, 'executed': success})
    print(json.dumps({'edition': pack['version'], 'fixtures': results, 'result': 'PASS'}, indent=2))


if __name__ == '__main__':
    main()
