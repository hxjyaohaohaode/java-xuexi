#!/usr/bin/env python3
"""Exercise the packaged classroom with two synthetic students and a real, pinned local model."""
import argparse
import contextlib
import hashlib
import json
import os
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from smoke_runtime import API, Client
from verification_inputs import write_report

ROOT = Path(__file__).resolve().parents[1]
WEIGHT_HASH = '9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031'
MODEL = 'Qwen3-0.6B-Q8_0'
CASES = (
    {'interest': '篮球比赛统计', 'requirements': '用通俗例子说明一次传参和一次写入，再让我预测。',
     'identity': 'basketball'},
    {'interest': '天文观测星表', 'requirements': '深入解释编译期拒绝的反例，给我一个可以迁移的变式。',
     'identity': 'astronomy'},
)
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def local_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def wait_for(process, endpoint):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline and process.poll() is None:
        try:
            with HTTP.open(endpoint, timeout=3) as reply:
                if reply.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(.4)
    raise RuntimeError('Local service did not become ready')


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(12)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


class Probe:
    """Keeps only the synthetic experiment's role, budget, usage and answer sample."""
    def __init__(self, provider):
        self.provider = provider
        self.case = None
        self.turns = []

    def handler(self):
        probe = self

        class Forward(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.path != '/v1/chat/completions':
                    self.send_error(404)
                    return
                data = self.rfile.read(int(self.headers['Content-Length']))
                sent = json.loads(data)
                combined = json.dumps(sent['messages'], ensure_ascii=False)
                case = probe.case
                turn = {
                    'schema': sent.get('response_format', {}).get('json_schema', {}).get('name'),
                    'maxOutputTokens': sent.get('max_tokens', sent.get('max_completion_tokens')),
                    'interestInPrompt': case['interest'] in combined,
                    'requirementsInPrompt': case['requirements'] in combined,
                    'otherInterestInPrompt': any(
                        c['identity'] != case['identity'] and c['interest'] in combined for c in CASES
                    ),
                }
                probe.turns.append(turn)
                started = time.monotonic()
                req = urllib.request.Request(probe.provider, data=data, headers={
                    'Content-Type':'application/json', 'Authorization':self.headers['Authorization']})
                try:
                    try:
                        reply = HTTP.open(req, timeout=280)
                    except urllib.error.HTTPError as error:
                        reply = error
                    with reply:
                        code, body = reply.status, reply.read()
                    value = json.loads(body)
                    choices = value.get('choices', [])
                    candidate = choices[0] if choices else {}
                    message = candidate.get('message', {}).get('content') or ''
                    try:
                        answer = json.loads(message)
                    except ValueError:
                        answer = {}
                    turn.update({
                        'http': code,
                        'elapsedSeconds': round(time.monotonic() - started, 2),
                        'finishReason': candidate.get('finish_reason'),
                        'usage': value.get('usage', {}),
                        'responseSha256': hashlib.sha256(message.encode()).hexdigest(),
                        'answerSample': str(answer.get('answer', answer.get('strategy', '')))[:600],
                        'reviewMoves': answer.get('revisions', {}),
                    })
                    self.send_response(code)
                    self.send_header('Content-Type','application/json')
                    self.send_header('Content-Length',str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (OSError, ValueError, KeyError) as err:
                    turn['forwardingError'] = type(err).__name__
                    self.send_error(502, type(err).__name__)
        return Forward


class Session:
    phase = 'MODEL'
    def __init__(self):
        self.secrets = set()
        self.report = {'http':[]}

    def check(self, name, result):
        if not result:
            raise AssertionError(name)

    def redact(self, message):
        for secret in self.secrets:
            message = message.replace(secret, '[redacted]')
        return message


def run_student(base, probe, case):
    probe.case = case
    first_turn = len(probe.turns)
    client = Client(Session(), base, timeout=30)
    client.register_and_login('interests-' + uuid.uuid4().hex[:16], secrets.token_urlsafe(24))

    def get(path):
        return client.request('GET', API + path)

    def post(path, body, expected=201):
        return client.request('POST', API + path, body, expected=(expected,), key=uuid.uuid4().hex)

    release = get('/releases/active')['releaseId']
    topic = next(c for c in get('/concepts?releaseId='+release)['items'] if c['code']=='G06')
    room = post('/workspaces', {'releaseId':release,'title':'个人泛型课堂','guidanceMode':'COLLABORATIVE'})['id']
    path = '/workspaces/'+room+'/courseware/'+topic['id']
    brief = {name:case[name] for name in ('interest','requirements')}
    plan = post(path, {'approach':'AUTO','brief':brief})
    assert plan['brief'] == brief and get(path+'?planId='+plan['id']) == plan
    activity = next(s for s in plan['scenes'] if s.get('selfCheck'))
    check = activity['selfCheck']
    assert len(check['options']) >= 2 and 0 <= check['answerIndex'] < len(check['options']) and check['explanation']
    assert any(s['kind']=='PRACTICE' and s['exerciseId'] for s in plan['scenes'])
    created = post(path+'/study', {'planId':plan['id'],'mode':'MODEL'},202)
    deadline = time.monotonic()+720
    while time.monotonic()<deadline:
        run = get('/agent-runs/'+created['id'])
        if run['status'] not in ('QUEUED','RUNNING'):
            break
        time.sleep(1)
    turns = probe.turns[first_turn:]
    if run['status'] != 'COMPLETED':
        report = get('/agent-runs/'+run['id']+'/study')
        requests = [get('/tutor/requests/'+rid) for rid in report['tutorRequestIds']]
        failures = [{k:r.get(k) for k in ('status','errorCode','usage')} for r in requests]
        raise AssertionError((run['status'], [(s['key'],s['status'],s.get('errorCode')) for s in run['steps']],failures))
    assert all(s['status']=='SUCCEEDED' for s in run['steps'])
    assert get(path+'/study?planId='+plan['id']+'&mode=MODEL')['id']==run['id']
    details = get('/agent-runs/'+run['id']+'/study')
    receipts = [get('/tutor/requests/'+rid) for rid in details['tutorRequestIds']]
    assert receipts and all(r['mode']=='MODEL' and r['usage']['billingState']=='SETTLED' for r in receipts)
    assert len(receipts)==len(turns)>=3
    assert all(t['http']==200 and t['finishReason']=='stop' and not t['otherInterestInPrompt'] for t in turns)
    assert any(t['maxOutputTokens']>1024 for t in turns)
    assert all(t['interestInPrompt'] and t['requirementsInPrompt'] for t in turns)
    ref = next(s for s in run['steps'] if s['key']=='output-slides')['outputRefs'][0]
    artifact = get('/artifacts/'+ref['id']+'/revisions?revision='+str(ref['revision']))
    assert ref['contentHash']==artifact['contentHash'] and artifact['reviewStatus']=='DRAFT'
    pages = artifact['content']['slides']
    moves = next(t['reviewMoves'] for t in turns if t.get('reviewMoves'))
    labels = {'TRACE_TYPES':'类型推导','CHECK_COMPILE':'编译预测','COUNTEREXAMPLE':'对照反例','TRANSFER':'迁移练习'}
    reviewed = [p for p in pages if p['id'].startswith('slide-')]
    assert len(reviewed) == len(moves) > 0
    first_reason = check['explanation'].split('。')[0]
    output_checks = []
    for index, page in enumerate(reviewed):
        move = moves['expert-' + str(index)]
        text = '\n'.join(e.get('text', '') for e in page['elements']) + '\n' + page.get('notes', '')
        assert labels[move] in text and first_reason in text, (index, move, text[:800])
        assert all(type_name in text for type_name in (
            ('Player', 'Guard') if case['identity'] == 'basketball' else ('Observation', 'Transit')
        ))
        output_checks.append({'activity':move,'sourceReasonMatched':True,'sample':text[:600]})
    return {'student':case['identity'],'lesson':'G06','releaseId':release,'brief':brief,
            'lessonSHA256':plan['contentHash'],'exercise':activity['body'],'answer':check['options'][check['answerIndex']],
            'explanation':check['explanation'],'sceneCount':len(plan['scenes']),'slides':len(pages),'slidesSHA256':ref['contentHash'],
            'studyStatus':run['status'],'accountedTokens':run['spentTokens'],'roles':[s['key'] for s in run['steps']],
            'providerTurns':turns,'reviewedActivities':output_checks}


def verify(args, result):
    jar, model, server = (p.resolve(strict=True) for p in (args.jar,args.model,args.server))
    assert digest(model)==WEIGHT_HASH, 'Pinned Qwen weight SHA-256 differs'
    result.update(jarSHA256=digest(jar),weightSHA256=WEIGHT_HASH,model=MODEL,contextWindowTokens=args.context_window)
    with tempfile.TemporaryDirectory() as temp, contextlib.ExitStack() as cleanup:
        temp = Path(temp)
        model_port, app_port = local_port(), local_port()
        app_base = 'http://127.0.0.1:'+str(app_port)
        model_base = 'http://127.0.0.1:'+str(model_port)
        key = secrets.token_urlsafe(36)
        log = cleanup.enter_context((temp/'model.log').open('w'))
        engine = subprocess.Popen([str(server),'-m',str(model),'--alias',MODEL,'--host','127.0.0.1','--port',str(model_port),
            '--ctx-size',str(args.context_window),'-b','128','-ub','64','--no-cache-prompt','--parallel','1','--threads','3','--threads-batch','3','--temp','0','--seed','42',
            '--reasoning','off','--no-warmup','--cache-ram','0','--ctx-checkpoints','0','-ctk','q8_0','-ctv','q8_0','-fa','on'],
            env=dict(os.environ,LD_LIBRARY_PATH=str(server.parent),LLAMA_API_KEY=key),stdout=log,stderr=subprocess.STDOUT)
        cleanup.callback(stop,engine)
        wait_for(engine,model_base+'/health')
        probe = Probe(model_base+'/v1/chat/completions')
        proxy = ThreadingHTTPServer(('127.0.0.1',0),probe.handler())
        threading.Thread(target=proxy.serve_forever,daemon=True).start()
        cleanup.callback(proxy.server_close)
        cleanup.callback(proxy.shutdown)
        log = cleanup.enter_context((temp/'classroom.log').open('w'))
        provider = 'http://127.0.0.1:'+str(proxy.server_port)+'/v1/chat/completions'
        app = subprocess.Popen(['java','-Xmx240m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC','-jar',str(jar),
                '--server.address=127.0.0.1','--server.port='+str(app_port),'--spring.profiles.active=demo',
                '--spring.datasource.url=jdbc:h2:mem:interest;MODE=MySQL;DATABASE_TO_LOWER=TRUE',
                '--typeforge.tutor.timeout-millis=240000','--typeforge.tutor.context-window-tokens='+str(args.context_window),
                '--typeforge.tutor.max-output-tokens=8192','--typeforge.study.poll-millis=300','--typeforge.tutor.poll-millis=300'],
                env=dict(os.environ,TYPEFORGE_TUTOR_MODEL=MODEL,TYPEFORGE_TUTOR_PROVIDER_URL=provider,TYPEFORGE_TUTOR_API_KEY=key),
                cwd=temp,stdout=log,stderr=subprocess.STDOUT)
        cleanup.callback(stop,app)
        wait_for(app,app_base+API+'/health')
        for case in CASES:
            print('Generating G06 teaching materials for '+case['identity'],flush=True)
            try:
                result['students'].append(run_student(app_base,probe,case))
            except Exception:
                result['failedStudent']=case['identity']
                result['observedTurns']=probe.turns
                result['inferenceExitCode']=engine.poll()
                result['inferenceLogTail']=(temp/'model.log').read_text(errors='replace')[-1800:].replace(key,'[redacted]')
                raise
        a,b = result['students']
        assert a['releaseId']==b['releaseId'] and a['exercise']!=b['exercise'] and a['explanation']!=b['explanation'] and a['slidesSHA256']!=b['slidesSHA256']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jar','model','server','report'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--context-window', type=int, choices=(8192,12288,16384), default=16384)
    args = parser.parse_args()
    result = {'result':'FAIL','scope':'Actual local CPU model, synthetic students, same published concept. AI drafts require academic review.','students':[]}
    try:
        verify(args,result)
        result['result']='PASS'
    finally:
        write_report(args.report,result)
    print('Verified both personal editions, model calls and published draft references.')


if __name__=='__main__':
    main()
