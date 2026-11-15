"""Validate architecture assets, references, public boundaries and real schema examples."""
from pathlib import Path
import csv
import json
import re
import sys
import hashlib
from sync_contract_index import generated_files
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
errors = []
checks = []

def check(condition, label):
    (checks if condition else errors).append(label)

def read_json(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))

spec = read_json('contracts/openapi.json')
try:
    from openapi_spec_validator import validate_spec
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    sys.exit('Install validation dependencies: python -m pip install -r requirements-dev.txt')
validate_spec(spec)
checks.append('OpenAPI 3.1 specification validates')
schemas = spec['components']['schemas']
for schema in schemas.values():
    Draft202012Validator.check_schema(schema)
checks.append(f'{len(schemas)} JSON Schemas validate')

operations = [(p,m,o) for p,methods in spec['paths'].items() for m,o in methods.items()]
ids = [o['operationId'] for _,_,o in operations]
check(len(ids) == len(set(ids)), 'operationId values are unique')
implemented = {(p,m) for p,m,o in operations if o['x-status']=='implemented'}
controller_routes=set()
for source in (ROOT/'backend/src/main/java').rglob('*Controller.java'):
    code=source.read_text(encoding='utf-8')
    prefix_match=re.search(r'@RequestMapping\("([^"]+)"\)\s*public (?:final )?class',code)
    prefix=prefix_match.group(1) if prefix_match else ''
    for method,args in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?(?=\s|@)',code):
        path_match=re.search(r'"([^"]*)"',args)
        path=path_match.group(1) if path_match else ''
        full=prefix+path
        if full.startswith('/api/v1/'):
            controller_routes.add((full[len('/api/v1'):],method.lower()))
check(implemented == controller_routes, 'implemented OpenAPI operations match every actual controller route exactly')
check(len(implemented) > 2, 'persistent business runtime is represented in the contract')
index=(ROOT/'contracts/README.md').read_text(encoding='utf-8')
indexed=set(re.findall(r'^\| (GET|POST|PUT|PATCH|DELETE) \| `([^`]+)` \| `([^`]+)` \| (M\d{2}) \| (implemented|planned) \|$',index,re.M))
check(indexed=={(m.upper(),p,o['operationId'],o['tags'][0],o['x-status']) for p,m,o in operations},'human-readable API index matches exact operation status and route')
for path, method, operation in operations:
    check(operation['x-status'] in {'planned','implemented'}, f'{method} {path} has honest status')
    parameters = operation.get('parameters', [])
    names = [p['name'] for p in parameters if p['in']=='path']
    check(set(names) == set(re.findall(r'\{(\w+)\}', path)), f'{path} path parameters resolve')
    if path.startswith('/admin/'):
        check('security' not in operation or bool(operation['security']), f'{path} is not anonymously accessible')
    if method in {'post','put','patch','delete'}:
        check(any(p['name']=='X-CSRF-TOKEN' for p in parameters), f'{method} {path} requires CSRF')
check('answerSpec' not in schemas['Exercise']['properties'], 'public exercise DTO excludes answerSpec')
check('answer' in schemas['Attempt']['properties'] and 'answerSpec' not in schemas['Attempt']['properties'], 'own attempt includes submitted answer but not hidden solution')

def validator(name):
    return Draft202012Validator({'$ref':'#/components/schemas/'+name,'components':spec['components']}, format_checker=FormatChecker())
valid_id='13e88a06-8116-4e51-a885-6242ad086660'
valid_attempt={'workspaceId':valid_id,'releaseId':valid_id,'exerciseId':valid_id,'exerciseRevision':1,'answer':{'kind':'CODE','source':'class Demo {}'}}
v=validator('AttemptInput')
check(v.is_valid(valid_attempt), 'code submission example is valid')
check(not v.is_valid({**valid_attempt,'userId':valid_id}), 'client cannot add user identity to submission DTO')
check(not v.is_valid({**valid_attempt,'exerciseRevision':0}), 'revision zero rejected')
check(not v.is_valid({**valid_attempt,'answer':{'kind':'CODE','source':''}}), 'empty source rejected')
check(not v.is_valid({**valid_attempt,'answer':{'kind':'CHOICE','optionIds':['A','A']}}), 'duplicate option ids rejected')
check(not validator('CompileInput').is_valid({'releaseId':valid_id,'source':'x','mode':'SHELL'}), 'unknown execution mode rejected')
check(validator('LabInput').is_valid({'workspaceId':valid_id,'releaseId':valid_id,'labType':'ERASURE','source':'class D {}','prediction':'UNKNOWN'}), 'code laboratory input differs from relation DSL and fixes its workspace')

concepts=read_json('knowledge/concepts.json')
sources=read_json('knowledge/sources.json')
by_code={c['code']:c for c in concepts}
source_codes={s['code'] for s in sources}
check(len(concepts)==16 and len(by_code)==16, '16 distinct concept seeds')
check(all(s['rawCollected'] is False and s['rawFileHash'] is None for s in sources), 'source manifest does not claim full raw corpus')
visited=set()
active=set()
def visit(code):
    if code in active: raise ValueError('prerequisite cycle: '+code)
    if code in visited:return
    active.add(code)
    for predecessor in by_code[code]['prerequisites']: visit(predecessor)
    active.remove(code);visited.add(code)
for code,c in by_code.items():
    check(set(c['sourceRefs']) <= source_codes, f'{code} source references resolve')
    visit(code)
check(len(visited)==16, 'concept prerequisite graph is acyclic and complete')
with (ROOT/'frontend/reference-ledger.csv').open(encoding='utf-8',newline='') as f:
    ledger=list(csv.DictReader(f))
check([int(row['id']) for row in ledger]==list(range(1,210)), 'frontend ledger covers exactly 1..209')
check(all(row['reason'] and row['runtime_review'].startswith('未运行') for row in ledger), 'every frontend reference records a reason and honest runtime boundary')
def luminance(hex_color):
    values=[int(hex_color[i:i+2],16)/255 for i in (1,3,5)]
    linear=[v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4 for v in values]
    return sum(a*b for a,b in zip(linear,(0.2126,0.7152,0.0722)))
for theme,colors in read_json('frontend/design-tokens.json')['colors'].items():
    for name in ('text','muted','accent','warning','danger','inputBorder'):
        low,high=sorted([luminance(colors[name]),luminance(colors['surface'])])
        ratio=(high+0.05)/(low+0.05)
        check(ratio >= (3 if name=='inputBorder' else 4.5),f'{theme} {name} contrast on surface: {ratio:.2f}')
modules=read_json('contracts/modules.json')
check(len(modules)==16 and {o['tags'][0] for _,_,o in operations} <= {m['id'] for m in modules}, '16 module ids match API tags')
for module in modules:
    check((ROOT/f"backend/src/main/java/io/typeforge/{module['package']}/package-info.java").exists(), f"{module['id']} package boundary exists")

# Verify actual source imports, not merely a package inventory. Domain module dependencies must be acyclic.
module_graph={m['package']:set() for m in modules};module_graph['shared']=set()
for source in (ROOT/'backend/src/main/java/io/typeforge').rglob('*.java'):
    code=source.read_text(encoding='utf-8')
    package=re.search(r'^package io\.typeforge\.([a-z]+)',code,re.M)
    if package and package.group(1) in module_graph:
        origin=package.group(1)
        module_graph[origin].update(target for target in re.findall(r'import (?:static )?io\.typeforge\.([a-z]+)\.',code) if target!=origin and target in module_graph)
visiting=set();done=set();cycles=[]
def walk_module(name,path):
    if name in visiting:cycles.append(' -> '.join(path+[name]));return
    if name in done:return
    visiting.add(name)
    for dependency in sorted(module_graph[name]):walk_module(dependency,path+[name])
    visiting.remove(name);done.add(name)
for name in module_graph:walk_module(name,[])
check(not cycles,'actual Java module imports are acyclic: '+('; '.join(cycles) if cycles else 'PASS'))

sql='\n'.join(p.read_text(encoding='utf-8') for p in sorted((ROOT/'database').glob('V*.sql')))
table_names=re.findall(r'CREATE TABLE (\w+)',sql)
dictionary='\n'.join(p.read_text(encoding='utf-8') for p in (ROOT/'database').glob('*data-dictionary.md'))
check(len(table_names)>=56 and len(set(table_names))==len(table_names),'all cumulative SQL table identities are unique')
check(all('## '+t+' ' in dictionary for t in table_names),'all SQL tables documented')
for table,body in re.findall(r'CREATE TABLE (\w+) \((.*?)\) ENGINE=InnoDB',sql,re.S):
    marker='## '+table+' '
    if marker not in dictionary:continue  # The explicit all-tables check above already records this as a failure.
    section=dictionary.split(marker,1)[1].split('\n## ',1)[0]
    columns=re.findall(r"^  ([a-z][a-z0-9_]*) .+",body,re.M)
    columns+=re.findall(r'ALTER TABLE '+re.escape(table)+r' ADD COLUMN (\w+) ',sql)
    documented_columns=set(re.findall(r'^\| `?([a-z][a-z0-9_]*)`? \|',section,re.M))
    check(set(columns)==documented_columns,table+' dictionary covers exactly all columns')
check(not re.search(r'(?im)^\s*(DROP|DELETE|TRUNCATE)\b',sql),'migration contains no destructive statements')
for relative,expected in generated_files().items():
    check((ROOT/relative).read_text(encoding='utf-8')==expected,'generated current index is synchronized: '+relative)
lock_path=ROOT/'database/migrations.lock.json'
if lock_path.exists():
    for filename,digest in json.loads(lock_path.read_text(encoding='utf-8')).items():
        check((ROOT/'database'/filename).exists() and hashlib.sha256((ROOT/'database'/filename).read_bytes()).hexdigest()==digest,'published forward migration is immutable: '+filename)

with (ROOT/'evidence/traceability.csv').open(encoding='utf-8',newline='') as f: trace=list(csv.DictReader(f))
check({r['module'] for r in trace}=={m['id'] for m in modules},'traceability covers all modules')
check(all(set(r['tables'].split(';')) <= set(table_names) for r in trace),'traceability SQL references exist')
mapped_ops=[i for r in trace for i in r['operation_ids'].split(';') if i]
check(set(mapped_ops)==set(ids) and len(mapped_ops)==len(ids),'every API operation mapped exactly once to a module')

roles=read_json('agent/roles.json');tools=read_json('agent/tools.json');policy=read_json('agent/run-policy.json')
tool_ids={t['id'] for t in tools};role_map={r['id']:r for r in roles}
check(len(role_map)==14 and len(roles)==14,'14 unique expert roles')
check(len(tool_ids)==len(tools),'unique tool capability registry')
check(all(set(r['allowedTools']) <= tool_ids for r in roles),'expert tools resolve to admitted registry')
check(all(t['inputSchemaId'] in schemas and t['outputSchemaId'] in schemas for t in tools),'every tool has resolvable input/output schema')
check(all(0 < t['timeoutMillis'] <= policy['complexRun']['maxWallMillis'] for t in tools),'tool deadlines fit overall run bound')
check(all(r['status'] in {'planned','partial'} for r in roles),'role scope distinguishes bounded adapters from unimplemented general specialists')
check({t['id'] for t in tools if t['status']=='implemented'}=={'retrieveContext','searchEvidence','planLearning','explainLearning','composeDocument','composeSlides','composeInteraction'},'only tested evidence and bounded study adapters are marked implemented')
check((ROOT/'backend/src/test/java/io/typeforge/StudyWorkflowIntegrationTest.java').exists() and (ROOT/'backend/src/test/java/io/typeforge/StudyModelWorkflowTest.java').exists(),'study adapters have deterministic and loopback HTTP integration coverage')
capabilities=read_json('agent/capability-matrix.json')
check({c['id'] for c in capabilities['capabilities']}==tool_ids,'capability matrix covers exactly registered tool ids')
check(all(not c['enabled'] or (c['status']=='implemented' and c['adapterReady'] and c['verificationEvidence']) for c in capabilities['capabilities']),'enabled tools require an adapter and verification evidence')
check(all(m['status']==('partial' if any(o['tags'][0]==m['id'] and o['x-status']=='implemented' for _,_,o in operations) else 'planned') for m in modules),'module status distinguishes partial runtime from planned product scope')
state=read_json('frontend/state-contract.json')
check(state.get('frontendImplemented') is True and (ROOT/'frontend/src/App.tsx').exists() and (ROOT/'frontend/package-lock.json').exists(),'frontend state contract points to implemented React source and locked dependencies')
for entity in state['entityVersions']:
    fields=set(entity['idFields']+entity['versionFields']+entity['scopeFields'])
    check(fields <= set(schemas[entity['schema']]['properties']),entity['schema']+' frontend state fields exist in the current wire contract')
for name in ('run','workspace'):
    stream=state['eventStreams'][name]
    op=next(o for _,_,o in operations if o['operationId']==stream['operationId'])
    check(op['x-status']=='implemented' and stream['cursorParameter'] in {p['name'] for p in op.get('parameters',[])},name+' polling cursor agrees with the actual API')
check(not validator('WorkspaceUpdate').is_valid({'expectedRevision':4294967297,'title':'Overflow','guidanceMode':'QUIET'}),'int32 revision overflow rejected before narrowing')
check(not validator('RunCommand').is_valid({'kind':'PAUSE','expectedPlanRevision':1,'expectedRunEpoch':18446744073709551617}),'epoch overflow rejected before narrowing')
check(policy['maxPlanNodes']==24 and policy['maxConcurrentSteps']==3,'policy limits agree with Java kernel')
plan=read_json('agent/example-plan.json')
check(validator('AgentPlan').is_valid(plan),'complex learning task plan matches strict schema')
steps={s['id']:s for s in plan['steps']}
check(len(steps)==len(plan['steps']),'sample step keys unique')
remaining=set(steps);finished=set()
while remaining:
    ready={n for n in remaining if set(steps[n]['dependencies']) <= finished}
    if not ready:break
    remaining-=ready;finished|=ready
check(not remaining,'sample task graph has no cycles or missing dependencies')
check(all(set(s['tools']) <= set(role_map[s['role']]['allowedTools']) for s in steps.values()),'sample plan respects per-role capabilities')
check(all(not s['required'] or all(steps[d]['required'] for d in s['dependencies']) for s in steps.values()),'required plan nodes do not depend on optional outputs')
check(not validator('AgentPlan').is_valid({**plan,'runEpoch':0}),'zero run epoch rejected at transport boundary')
check(not validator('AgentPlan').is_valid({**plan,'budget':{**plan['budget'],'maxParallel':10}}),'unbounded parallel budget rejected')
check(not validator('AgentRunInput').is_valid({'goalId':valid_id,'snapshotId':valid_id,'requestedOutputs':['VIDEO'],'budget':plan['budget'],'tools':['shell']}),'client cannot grant arbitrary tool authority')
check(not validator('RunCommand').is_valid({'kind':'STEER','expectedPlanRevision':1,'expectedRunEpoch':1}),'steer without instruction rejected')
check(not validator('WorkspaceEventInput').is_valid({'id':valid_id,'type':'ANSWER_CORRECT','references':[],'expectedRevision':1}),'client cannot forge learning fact via workspace event')
check(not validator('ArtifactRevisionInput').is_valid({'baseRevision':0,'content':{'kind':'HTML','script':'alert(1)'},'inputRefs':[]}),'arbitrary script DSL rejected')
for example in read_json('agent/artifact-examples.json'):
    check(validator('ArtifactDsl').is_valid(example),'artifact schema example: '+example['kind'])
    check(not validator('ArtifactDsl').is_valid({**example,'script':'alert(1)'}),'unknown script field rejected: '+example['kind'])
interaction=next(x for x in read_json('agent/artifact-examples.json') if x['kind']=='NOTEBOOK')
unsafe=json.loads(json.dumps(interaction));unsafe['blocks'][0]['allowedActions']=['executeJavascript']
check(not validator('ArtifactDsl').is_valid(unsafe),'notebook actions are strictly allowlisted')
check(not validator('AssetTicketInput').is_valid({'mimeType':'application/x-msdownload','byteSize':1,'contentHash':'a'*64}),'executable upload MIME rejected')
check(not validator('AssetTicketInput').is_valid({'mimeType':'image/png','byteSize':104857601,'contentHash':'a'*64}),'oversized asset rejected')

for path in ROOT.rglob('*.md'):
    if any(part in {'.git','.work','target','node_modules'} for part in path.parts):continue
    for target in re.findall(r'(?<!!)\[[^\]]+\]\(([^)]+)\)',path.read_text(encoding='utf-8')):
        target=target.strip('<>').split('#')[0]
        if not target or re.match(r'^[a-zA-Z]+:',target):continue
        check((path.parent/unquote(target)).exists(),f'local link resolves: {path.relative_to(ROOT)} -> {target}')

report={'result':'PASS' if not errors else 'FAIL','checksPassed':len(checks),'errors':errors,'operations':len(operations),'implementedOperations':len(implemented),'plannedOperations':len(operations)-len(implemented),'schemas':len(schemas),'conceptSeeds':len(concepts),'frontendReferences':len(ledger),'sqlTables':len(table_names),'expertRoles':len(roles),'toolCapabilities':len(tools)}
(ROOT/'.work').mkdir(exist_ok=True)
(ROOT/'.work/verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
sys.exit(bool(errors))
