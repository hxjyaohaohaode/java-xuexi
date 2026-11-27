#!/usr/bin/env python3
"""Verify real MySQL 8.4 migrations and integrity in an isolated disposable schema.

Default: create/destroy a private Docker container (no published ports).
External task-owned server: MYSQL_TEST_CONNECTION=/path/connection.json python scripts/verify_mysql.py
The JSON must explicitly declare purpose="typeforge-isolated-verification", a loopback
host, and a schemaCheckDatabase named typeforge_schema_check or typeforge_verify_*.
A nonempty external schema is never reset unless --reset-task-schema is supplied.
No application or user database is dropped. Passwords stay in the client option file.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from verification_inputs import capture_inputs, require_unchanged, write_report

ROOT=Path(__file__).resolve().parents[1]
INTEGRITY_ERRORS=("ERROR 1062", "ERROR 1452", "ERROR 3819")

class MysqlHarness:
    def __init__(self,args):
        self.args=args
        self.name="typeforge-verify-"+uuid.uuid4().hex[:12]
        self.created=False
        self.results=[]
        self.env=os.environ.copy()
        self.schema="typeforge_verify"
        self.client=[]
        self.version=""
        self.mode="external-isolated-server" if args.connection else "isolated-docker"

    def run(self,args,**kwargs):
        return subprocess.run(args,capture_output=True,text=True,encoding="utf-8",timeout=180,env=self.env,**kwargs)

    def start(self):
        if self.args.connection:
            config_path=Path(self.args.connection).expanduser().resolve()
            config=json.loads(config_path.read_text(encoding="utf-8"))
            if config.get("purpose")!="typeforge-isolated-verification":
                raise RuntimeError("External connection must explicitly declare purpose=typeforge-isolated-verification")
            if config.get("host") not in ("127.0.0.1","localhost","::1"):
                raise RuntimeError("External verification is restricted to loopback")
            self.schema=config.get("schemaCheckDatabase","")
            if not re.fullmatch(r"typeforge_(?:schema_check|verify_[a-z0-9_]{1,48})",self.schema):
                raise RuntimeError("Refusing a database without the reserved verification schema name")
            if self.schema==config.get("runtimeDatabase"):
                raise RuntimeError("Schema verification must not target the application database")
            executable=Path(config["client"]).expanduser().resolve()
            defaults=Path(config["appClientConfig"]).expanduser().resolve()
            if not executable.is_file() or not defaults.is_file():
                raise RuntimeError("MySQL executable and private client options file must exist")
            port=int(config["port"])
            if not 1<=port<=65535:raise RuntimeError("Invalid test port")
            if config.get("libraryPath"):
                self.env["LD_LIBRARY_PATH"]=config["libraryPath"]+(":"+self.env["LD_LIBRARY_PATH"] if self.env.get("LD_LIBRARY_PATH") else "")
            self.client=[str(executable),"--defaults-file="+str(defaults),"--protocol=TCP",
                         "--host="+config["host"],"--port="+str(port),"--local-infile=0"]
        else:
            if self.args.reset_task_schema:
                raise RuntimeError("--reset-task-schema is only meaningful with an explicit external test connection")
            command=["docker","run","-d","--name",self.name,"--network","none","--memory","1g","--cpus","1",
                     "--tmpfs","/var/lib/mysql:rw,size=512m","-e","MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                     "-e","MYSQL_DATABASE="+self.schema,self.args.image,"--default-time-zone=+00:00"]
            start=self.run(command)
            if start.returncode:raise RuntimeError("Isolated MySQL container failed: "+start.stderr[-2000:])
            self.created=True
            self.client=["docker","exec","-i",self.name,"mysql","--protocol=TCP","-h127.0.0.1","-uroot","--local-infile=0"]
            deadline=time.monotonic()+90
            while time.monotonic()<deadline:
                ping=self.run(self.client+[self.schema,"--batch","--skip-column-names","-e","SELECT 1"])
                if ping.returncode==0:break
                time.sleep(.5)
            else:raise RuntimeError("Isolated MySQL did not become ready")
        self.version=self.sql("SELECT VERSION();",label="query actual database engine version")
        if not re.match(r"8\.4\.",self.version) or "MariaDB" in self.version:
            raise RuntimeError("Expected real MySQL 8.4 LTS; received "+self.version)
        count=int(self.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();",label="inspect isolated verification schema"))
        if count and not self.args.reset_task_schema:
            raise RuntimeError("Refusing nonempty verification schema. Use a fresh task schema, or explicitly pass --reset-task-schema for this marked disposable schema.")
        if self.args.reset_task_schema:
            self.sql("DROP DATABASE `"+self.schema+"`; CREATE DATABASE `"+self.schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;",label="reset explicitly marked task-owned verification schema only")

    def sql(self,statement,should_succeed=True,label="",expected_errors=INTEGRITY_ERRORS):
        result=self.run(self.client+["--batch","--raw","--skip-column-names","--default-character-set=utf8mb4",self.schema],input=statement)
        if (result.returncode==0)!=should_succeed:
            raise AssertionError(label+": "+result.stderr[-2000:])
        if not should_succeed and not any(code in result.stderr for code in expected_errors):
            raise AssertionError(label+": failure was not expected integrity enforcement: "+result.stderr[-2000:])
        self.results.append({"check":label,"result":"PASS"})
        return result.stdout.strip()

    def close(self):
        if self.created:
            cleanup=self.run(["docker","rm","-f",self.name])
            if cleanup.returncode:raise RuntimeError("Task-owned container cleanup failed: "+cleanup.stderr[-2000:])

def uid(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL,"https://typeforge.local/"+value))

def verify(harness):
    sql=harness.sql
    schema_hashes={}
    table_total=0
    for migration in sorted((ROOT/'database').glob('V*.sql')):
        ddl=migration.read_text(encoding='utf-8')
        table_total += len(re.findall(r'CREATE TABLE (\w+)',ddl))
        schema_hashes[migration.name]=hashlib.sha256(ddl.encode("utf-8")).hexdigest()
        sql(ddl,label=migration.name+" applies on MySQL "+harness.version)
    sql((ROOT/'database/seed-demo.sql').read_text(encoding='utf-8'),label='draft seeds import')
    count=sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();",label='table count query')
    assert int(count)==table_total and table_total>=56,count
    assert sql('SELECT COUNT(*) FROM concept;',label='concept count')=='16'
    assert sql('SELECT COUNT(*) FROM knowledge_chunk;',label='knowledge seed count')=='16'
    assert sql('SELECT COUNT(*) FROM active_release;',label='no falsely active release')=='0'
    author=uid('seed-author');rel=uid('seed-release');concept=uid('G01');exercise=uid('test-exercise');attempt=uid('test-attempt')
    sql(f"INSERT INTO user_preference(user_id,code_font_size) VALUES ('{author}',5);",False,'font range CHECK rejects invalid input')
    sql(f"INSERT INTO concept_edge VALUES ('{concept}','{concept}');",False,'self prerequisite CHECK rejects cycle edge')
    sql(f"INSERT INTO concept_edge VALUES ('{concept}','{uuid.uuid4()}');",False,'foreign key rejects missing concept')
    sql(f"INSERT INTO content_review(id,target_kind,target_id,target_revision,author_id,reviewer_id,decision,note) VALUES ('{uuid.uuid4()}','CHUNK','{uid('chunk-G01')}',1,'{author}','{author}','APPROVED','invalid self-review');",False,'independent reviewer CHECK')
    sql(f"INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,author_id) VALUES ('{exercise}',1,'{exercise}','SINGLE',1,'fixture',JSON_OBJECT(),'v1','{author}'); INSERT INTO release_exercise VALUES ('{rel}','{exercise}',1);",label='valid version membership fixture')
    def attempt_sql(revision,score='NULL',maximum='NULL',status='PENDING'):
        return f"INSERT INTO exercise_attempt(id,user_id,release_id,exercise_id,exercise_revision,answer,status,score,maximum) VALUES ('{attempt}','{author}','{rel}','{exercise}',{revision},JSON_OBJECT(),'{status}',{score},{maximum});"
    sql(attempt_sql(2),False,'composite FK rejects wrong published exercise revision')
    sql(attempt_sql(1,'101','100'),False,'score cannot exceed maximum')
    sql(attempt_sql(1),label='valid attempt links to release membership')
    sql(attempt_sql(1),False,'duplicate attempt primary key rejected')
    event=uid('event')
    sql(f"INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES ('{event}','{attempt}','AttemptGraded.v1',JSON_OBJECT()); INSERT INTO event_receipt VALUES ('learning','{event}',UTC_TIMESTAMP(3));",label='event receipt fixture')
    sql(f"INSERT INTO event_receipt VALUES ('learning','{event}',UTC_TIMESTAMP(3));",False,'consumer event uniqueness enforced')
    conv=uid('test-conversation');request=uid('test-request');message=uid('test-message');other=uid('other-release')
    sql(f"INSERT INTO conversation(id,user_id,release_id,title) VALUES ('{conv}','{author}','{rel}','fixture'); INSERT INTO tutor_request(id,conversation_id,question,status,prompt_version) VALUES ('{request}','{conv}','fixture','COMPLETED','v1'); INSERT INTO tutor_message(id,request_id,answer,limitations,suggestions) VALUES ('{message}','{request}','fixture','fixture',JSON_ARRAY()); INSERT INTO content_release(id,version,manifest_hash,created_by) VALUES ('{other}','test-other',REPEAT('0',64),'{author}');",label='citation version fixture')
    sql(f"INSERT INTO message_citation VALUES ('{message}','{other}','{uid('chunk-G01')}',1,'fixture');",False,'citation cannot refer to chunk outside release')
    sql(f"INSERT INTO message_citation VALUES ('{message}','{rel}','{uid('chunk-G01')}',1,'fixture');",label='citation to actual release member succeeds')
    w1,w2,g1,g2,c1,c2,r1,r2,e1=[uid(x) for x in ['workspace-1','workspace-2','goal-1','goal-2','context-1','context-2','run-1','run-2','workspace-event-1']]
    for w,g,c in [(w1,g1,c1),(w2,g2,c2)]:
        sql(f"INSERT INTO learning_workspace(id,owner_id,release_id,title) VALUES ('{w}','{author}','{rel}','test'); INSERT INTO learning_goal(id,workspace_id,goal_text,acceptance) VALUES ('{g}','{w}','PECS',JSON_ARRAY('correct')); INSERT INTO context_snapshot(id,workspace_id,workspace_revision,event_sequence,references_json,content_hash) VALUES ('{c}','{w}',1,0,JSON_ARRAY(),REPEAT('a',64));",label='workspace context fixture '+w[-4:])
    sql(f"INSERT INTO workspace_event(id,workspace_id,sequence_no,event_type,origin,payload) VALUES ('{e1}','{w1}',1,'HELP_REQUESTED','CLIENT',JSON_OBJECT());",label='semantic event fixture')
    e2=uid('workspace-event-2')
    sql(f"INSERT INTO workspace_event(id,workspace_id,sequence_no,event_type,origin,payload) VALUES ('{e2}','{w2}',1,'HELP_REQUESTED','CLIENT',JSON_OBJECT());",label='second workspace event fixture')
    def run_sql(r,w,g,c,trigger='NULL',budget=100):
        return f"INSERT INTO agent_run(id,workspace_id,goal_id,snapshot_id,trigger_event_id,status,plan_json,budget_micros,token_budget,deadline_at) VALUES ('{r}','{w}','{g}','{c}',{trigger},'QUEUED',JSON_OBJECT(),{budget},1000,UTC_TIMESTAMP(3)+INTERVAL 1 HOUR);"
    sql(run_sql(r1,w1,g2,c1),False,'run cannot reference goal from another workspace')
    sql(run_sql(r1,w1,g1,c2),False,'run cannot reference context from another workspace')
    sql(run_sql(r1,w1,g1,c1,"'"+e2+"'"),False,'trigger event cannot cross workspace')
    sql(run_sql(r1,w1,g1,c1,"'"+e1+"'"),label='valid run admits goal and context from same workspace')
    sql(run_sql(r2,w1,g1,c1,"'"+e1+"'"),False,'same event cannot spawn second run')
    sql(f"UPDATE agent_run SET reserved_micros=80,spent_micros=30 WHERE id='{r1}';",False,'concurrent budget oversubscription constrained')
    sql(f"UPDATE agent_run SET reserved_tokens=800,spent_tokens=300 WHERE id='{r1}';",False,'concurrent token oversubscription constrained')
    sql(f"UPDATE agent_run SET status='MADE_UP' WHERE id='{r1}';",False,'unknown run state rejected')
    sql(run_sql(r2,w2,g2,c2),label='second workspace run fixture')
    checkpoint,lesson=uid('learner-checkpoint'),uid('personal-lesson')
    def checkpoint_sql(snapshot):
        return f"INSERT INTO learning_context_checkpoint(id,workspace_id,snapshot_id,concept_id,source_hash,digest,payload) VALUES ('{checkpoint}','{w1}','{snapshot}','{concept}',REPEAT('a',64),REPEAT('b',64),JSON_OBJECT());"
    sql(checkpoint_sql(c2),False,'role memory cannot use another workspace snapshot')
    sql(checkpoint_sql(c1),label='same workspace and snapshot accepted for courseware checkpoint')
    def lesson_sql(snapshot):
        return f"INSERT INTO personal_courseware(id,workspace_id,release_id,concept_id,revision,auto_snapshot,snapshot_id,checkpoint_id,generation_hash,content_hash,plan_json,sources_json) VALUES ('{lesson}','{w1}','{rel}','{concept}',1,TRUE,'{snapshot}','{checkpoint}',REPEAT('c',64),REPEAT('d',64),JSON_OBJECT(),JSON_OBJECT());"
    sql(lesson_sql(c2),False,'courseware checkpoint must identify exactly the workspace snapshot')
    sql(lesson_sql(c1),label='courseware edition pins an existing checkpoint and release')
    def lesson_run(workspace,run,mode):
        return f"INSERT INTO courseware_study_run VALUES ('{workspace}','{lesson}','{run}','{mode}',UTC_TIMESTAMP(3));"
    sql(lesson_run(w1,r2,'MODEL'),False,'courseware cannot attach a foreign run')
    sql(lesson_run(w2,r2,'MODEL'),False,'learner run cannot read courseware from another workspace')
    sql(lesson_run(w1,r1,'SIMULATED'),False,'unregistered courseware execution mode denied')
    sql(lesson_run(w1,r1,'EVIDENCE_ONLY'),label='same workspace edition links to a durable run')
    sql(f"UPDATE learning_goal SET origin_kind='FABRICATED' WHERE id='{g1}';",False,'invalid learning goal provenance denied')
    s1,s2,s3,s4=[uid('step-'+str(i)) for i in range(1,5)]
    for s,r,revision in [(s1,r1,1),(s2,r1,1),(s3,r2,1),(s4,r1,2)]:
        sql(f"INSERT INTO agent_step(id,run_id,plan_revision,step_key,role_key,status,input_refs,output_refs,read_scopes,write_scopes,required_output) VALUES ('{s}','{r}',{revision},'{s}','generics-expert','PENDING',JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY(),TRUE);",label='task step fixture '+s[-4:])
    sql(f"INSERT INTO agent_step_dependency VALUES ('{r1}',1,'{s2}','{s3}');",False,'dependency cannot cross agent run')
    sql(f"INSERT INTO agent_step_dependency VALUES ('{r1}',1,'{s2}','{s4}');",False,'dependency cannot cross plan revision')
    sql(f"INSERT INTO agent_step_dependency VALUES ('{r1}',1,'{s1}','{s1}');",False,'self task edge rejected')
    sql(f"INSERT INTO agent_step_dependency VALUES ('{r1}',1,'{s2}','{s1}');",label='valid task dependency succeeds')
    a1,a2,asset1,asset2=[uid(x) for x in ['artifact-1','artifact-2','asset-1','asset-2']]
    for w,a,asset in [(w1,a1,asset1),(w2,a2,asset2)]:
        sql(f"INSERT INTO learning_artifact(id,workspace_id,kind,title) VALUES ('{a}','{w}','SLIDES','test'); INSERT INTO artifact_revision(artifact_id,revision,schema_version,content_json,content_hash,input_refs,review_status) VALUES ('{a}',1,'1.0',JSON_OBJECT(),REPEAT('b',64),JSON_ARRAY(),'DRAFT'); INSERT INTO asset_object(id,workspace_id,object_key,mime_type,byte_size,content_hash,scan_status) VALUES ('{asset}','{w}','{asset}','image/png',128,REPEAT('c',64),'CLEAN');",label='artifact asset fixture '+a[-4:])
    sql(f"INSERT INTO artifact_asset VALUES ('{w1}','{a1}',1,'{asset2}','IMAGE');",False,'artifact cannot attach asset from another workspace')
    sql(f"INSERT INTO artifact_asset VALUES ('{w1}','{a1}',1,'{asset1}','IMAGE');",label='same-workspace asset reference succeeds')
    sql(f"INSERT INTO artifact_dependency VALUES ('{w1}','{a1}',1,'{a2}',1,'FACT',JSON_ARRAY());",False,'artifact dependency cannot cross workspace')
    sql(f"UPDATE learning_artifact SET goal_id='{g2}' WHERE id='{a1}';",False,'artifact goal cannot cross workspace')
    sql(f"INSERT INTO artifact_write_lease VALUES ('{a1}','{r1}',1,'{uuid.uuid4()}',1,UTC_TIMESTAMP(3)+INTERVAL 1 MINUTE);",label='first artifact write lease succeeds')
    sql(f"INSERT INTO artifact_write_lease VALUES ('{a1}','{r1}',1,'{uuid.uuid4()}',1,UTC_TIMESTAMP(3)+INTERVAL 1 MINUTE);",False,'second concurrent artifact write lease rejected')
    def tool_sql():
        return f"INSERT INTO tool_execution(id,step_id,execution_key,tool_key,input_hash,status,reserved_micros,result_refs) VALUES ('{uuid.uuid4()}','{s1}','same-call','requestCompile',REPEAT('d',64),'ADMITTED',0,JSON_ARRAY());"
    sql(tool_sql(),label='tool execution admission fixture')
    sql(tool_sql(),False,'tool execution key cannot be admitted twice')
    def message_sql():
        return f"INSERT INTO agent_message(id,run_id,sender_role,receiver_role,dedupe_key,kind,payload,evidence_refs) VALUES ('{uuid.uuid4()}','{r1}','code-coach','fact-checker','same-message','FACT',JSON_OBJECT(),JSON_ARRAY());"
    sql(message_sql(),label='durable expert message fixture')
    sql(message_sql(),False,'expert inbox message de-duplication enforced')
    sql(f"INSERT INTO workspace_attempt VALUES ('{w1}','{attempt}');",label='workspace attempt evidence links to immutable attempt')
    sql(f"INSERT INTO workspace_attempt VALUES ('{w2}','{attempt}');",False,'one attempt cannot be counted in two workspaces')
    sql(f"INSERT INTO workspace_attempt VALUES ('{w1}','{uuid.uuid4()}');",False,'workspace evidence requires actual attempt')
    sql(f"INSERT INTO learning_family_evidence VALUES ('{author}','{concept}','{exercise}','{attempt}',1,1);",label='independent family evidence fixture')
    sql(f"INSERT INTO learning_family_evidence VALUES ('{author}','{concept}','{exercise}','{attempt}',1,1);",False,'repeated family cannot inflate independent evidence')
    sql(f"UPDATE learning_family_evidence SET total_weight=0 WHERE user_id='{author}';",False,'zero evidence denominator rejected')
    sql(f"UPDATE learning_family_evidence SET correct_weight=2 WHERE user_id='{author}';",False,'correct weight cannot exceed total')
    sql(f"INSERT INTO workspace_projection VALUES ('{w1}',1,1,UTC_TIMESTAMP(3));",label='durable workspace projection fixture')
    sql(f"UPDATE workspace_projection SET processed_sequence=-1 WHERE workspace_id='{w1}';",False,'negative projection cursor rejected')
    sql(f"UPDATE workspace_projection SET activity_count=-1 WHERE workspace_id='{w1}';",False,'negative activity total rejected')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w1}','{a1}',1,'GOAL','{g1}',1,REPEAT('a',64));",label='artifact lineage stores pinned source reference')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w2}','{a1}',1,'GOAL','{g2}',1,REPEAT('a',64));",False,'artifact lineage cannot claim different workspace')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w1}','{a1}',2,'GOAL','{g1}',1,REPEAT('a',64));",False,'artifact lineage requires actual output revision')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w1}','{a1}',1,'UNKNOWN','{g1}',1,REPEAT('a',64));",False,'artifact lineage requires supported source kind')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w1}','{a1}',1,'MEMORY','{g1}',0,REPEAT('a',64));",False,'artifact lineage requires positive source revision')
    sql(f"INSERT INTO artifact_input_reference VALUES ('{w1}','{a1}',1,'GOAL','{g1}',2,REPEAT('b',64));",False,'same immutable artifact cannot repin same source identity')
    sql(f"INSERT INTO local_evidence_workflow(run_id,query_text,adapter_version) VALUES ('{r1}','PECS','local-search-v1');",label='local evidence workflow binds admitted run')
    sql(f"INSERT INTO local_evidence_workflow(run_id,query_text,adapter_version) VALUES ('{r1}','other','local-search-v1');",False,'duplicate local workflow cannot replace admitted input')
    sql(f"INSERT INTO local_evidence_workflow(run_id,query_text,adapter_version) VALUES ('{uuid.uuid4()}','PECS','local-search-v1');",False,'local evidence workflow cannot bind nonexistent run')
    sql(f"UPDATE outbox_event SET next_attempt_at=UTC_TIMESTAMP(3)+INTERVAL 1 SECOND,last_error_code='SAFE_TEST_CODE' WHERE id='{event}';",label='bounded outbox retry metadata persists')

    # v0.4 assertions use actual MySQL constraints, independently of the HTTP/service tests.
    sql(f"INSERT INTO workspace_conversation VALUES ('{conv}','{w1}');",label='tutor conversation has one persisted workspace')
    sql(f"INSERT INTO workspace_conversation VALUES ('{conv}','{w2}');",False,'conversation cannot bind a second workspace')
    sql(f"INSERT INTO tutor_daily_budget(user_id,budget_date,reserved_tokens) VALUES ('{author}',UTC_DATE(),-1);",False,'tutor quota rejects negative reservations')
    sql(f"INSERT INTO tutor_daily_budget(user_id,budget_date) VALUES ('{author}',UTC_DATE());",label='tutor daily quota fixture')
    def tutor_runtime(snapshot):
        return f"INSERT INTO tutor_request_runtime(request_id,workspace_id,snapshot_id,mode,deadline_at,input_hash,context_hash,evidence_refs,billing_state,budget_date) VALUES ('{request}','{w1}','{snapshot}','EVIDENCE_ONLY',UTC_TIMESTAMP(3)+INTERVAL 1 MINUTE,REPEAT('e',64),REPEAT('a',64),JSON_ARRAY(),'NONE',UTC_DATE());"
    sql(tutor_runtime(c2),False,'tutor runtime snapshot cannot cross workspace')
    sql(tutor_runtime(c1),label='tutor runtime pins an actual same-workspace snapshot')
    for column,value,label in [('mode',"'FAKE_MODEL'",'unknown tutor mode rejected'),('request_epoch','0','zero tutor epoch rejected'),('attempt_count','4','tutor retry bound enforced'),('reserved_tokens','-1','negative request reservation rejected'),('used_tokens','-1','negative provider usage rejected'),('billing_state',"'FREE'",'unknown tutor billing state rejected')]:
        sql(f"UPDATE tutor_request_runtime SET {column}={value} WHERE request_id='{request}';",False,label)
    sql(f"INSERT INTO tutor_artifact_link(artifact_id,artifact_revision,request_id,snapshot_id,response_hash) VALUES ('{a1}',2,'{request}','{c1}',REPEAT('f',64));",False,'tutor document link requires an existing artifact revision')
    sql(f"INSERT INTO tutor_artifact_link(artifact_id,artifact_revision,request_id,snapshot_id,response_hash) VALUES ('{a1}',1,'{request}','{c1}',REPEAT('f',64));",label='tutor response provenance persists against fixed revision')

    source_a,source_b,ingestion=[uid(x) for x in ['manual-source-a','manual-source-b','manual-ingestion']]
    for source in (source_a,source_b):
        sql(f"INSERT INTO knowledge_source(id,title,url,edition,usage_note) VALUES ('{source}','fixture','https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html','17','isolated constraint fixture');",label='manual source fixture '+source[-4:])
    def ingestion_sql(identity):
        return f"INSERT INTO content_ingestion(id,source_id,author_id,original_markdown,cleaned_markdown,original_hash,cleaned_hash,processor_version,processing_note) VALUES ('{identity}','{source_a}','{author}','raw','clean',REPEAT('1',64),REPEAT('2',64),'fixture','test-owned capture');"
    sql(ingestion_sql(ingestion),label='manual capture retains original and cleaned evidence')
    sql(ingestion_sql(uid('duplicate-ingestion')),False,'same source original and cleaned hashes cannot be duplicated')
    chunk=uid('chunk-G01')
    sql(f"INSERT INTO chunk_source VALUES ('{chunk}',1,'{source_b}','fixture');",label='independent knowledge-source binding fixture')
    sql(f"INSERT INTO chunk_ingestion VALUES ('{chunk}',1,'{source_b}','{ingestion}');",False,'chunk cannot claim capture from a different registered source')
    sql(f"INSERT INTO exercise_knowledge VALUES ('{exercise}',1,'{chunk}',2);",False,'exercise evidence must pin an existing knowledge revision')
    sql(f"INSERT INTO exercise_knowledge VALUES ('{exercise}',1,'{chunk}',1);",label='exercise evidence fixes knowledge revision')
    reviewer,review=[uid(x) for x in ['test-independent-reviewer','test-independent-review']]
    sql(f"INSERT INTO app_user(id,username,password_hash,roles) VALUES ('{reviewer}','constraint-reviewer','NO_LOGIN_FIXTURE',JSON_ARRAY('EDITOR'));",label='independent reviewer constraint fixture with no usable credential')
    def review_sql(identity):
        return f"INSERT INTO content_review(id,target_kind,target_id,target_revision,author_id,reviewer_id,decision,note) VALUES ('{identity}','CHUNK','{chunk}',1,'{author}','{reviewer}','APPROVED','isolated SQL fixture only');"
    sql(review_sql(review),label='independent immutable review fixture')
    sql(review_sql(uid('duplicate-review')),False,'same target revision cannot receive a second review decision')
    sql(f"INSERT INTO content_review_evidence VALUES ('{uid('missing-review')}',REPEAT('3',64),'fixture',JSON_ARRAY('EDITOR'));",False,'review evidence cannot exist without its decision')
    sql(f"INSERT INTO release_manifest VALUES ('{uid('missing-release')}',JSON_OBJECT(),'fixture');",False,'release manifest requires an existing course release')

    lab,export=[uid(x) for x in ['interactive-lab','static-export']]
    sql(f"INSERT INTO lab_session(id,user_id,release_id,lab_type,input,rules_version) VALUES ('{lab}','{author}','{rel}','ASSIGNMENT',JSON_OBJECT(),'fixture'); INSERT INTO workspace_lab VALUES ('{w1}','{lab}');",label='finite lab has persisted workspace ownership')
    sql(f"INSERT INTO workspace_lab VALUES ('{w2}','{lab}');",False,'lab cannot bind a second workspace')
    sql(f"INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES ('{uid('invalid-action')}','{a1}',1,'block','executeScript',JSON_OBJECT());",False,'notebook receipt rejects unregistered actions')
    sql(f"INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES ('{uid('old-action')}','{a1}',2,'block','predict',JSON_OBJECT());",False,'notebook receipt requires actual immutable revision')
    sql(f"INSERT INTO export_job(id,artifact_id,artifact_revision,format_key,renderer_version,input_hash,execution_key,status) VALUES ('{export}','{a1}',1,'HTML','fixture',REPEAT('4',64),'static-export-fixture','SUCCEEDED');",label='fixed-version static export fixture')
    def export_sql(mime,size):
        return f"INSERT INTO artifact_export_content VALUES ('{export}','{mime}','fixture.html','text',REPEAT('5',64),{size});"
    sql(export_sql('application/javascript',4),False,'static export MIME allowlist rejects executable script')
    sql(export_sql('text/html',0),False,'static export byte count cannot be empty')
    sql(export_sql('text/html',1048577),False,'static export byte count enforces renderer budget')
    sql(export_sql('text/html',4),label='static export content links to its actual job')

    # V012: native MySQL constraints; synthetic SQL fixtures, not claimed retrieval outcomes.
    ev_suite,ev_run,ev_other=[uid(x) for x in ['evaluation-sql-suite','evaluation-sql-run','evaluation-other-owner']]
    sql(f"INSERT INTO app_user(id,username,password_hash,roles) VALUES ('{ev_other}','evaluation-sql-other','disabled-test-only',JSON_ARRAY('LEARNER'));",label='evaluation ownership negative fixture is an existing separate account')
    def evaluation_suite_sql(owner=author,space=w1,release=rel):
        return f"INSERT INTO evaluation_suite(id,owner_id,workspace_id,release_id,created_at,updated_at) VALUES ('{ev_suite}','{owner}','{space}','{release}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));"
    sql(evaluation_suite_sql(ev_other),False,'evaluation suite cannot claim another workspace owner')
    sql(evaluation_suite_sql(release=uid('other-release')),False,'evaluation suite release must match its workspace release')
    sql(evaluation_suite_sql(),label='evaluation suite binds actual owner workspace and release')
    for column,value,label in [('current_revision','0','evaluation current revision must be positive'),('current_revision','101','evaluation history has a finite revision bound'),('status',"'PUBLISHED'",'a private evaluation suite cannot claim course publication')]:
        sql(f"UPDATE evaluation_suite SET {column}={value} WHERE id='{ev_suite}';",False,label)
    sql(f"INSERT INTO evaluation_suite_revision(suite_id,revision,title,description,case_count,snapshot_json,corpus_json,content_hash,corpus_hash,byte_size,created_at) VALUES ('{ev_suite}',1,'SQL fixture','schema only',2,JSON_OBJECT(),JSON_ARRAY(),REPEAT('a',64),REPEAT('b',64),4,UTC_TIMESTAMP(3));",label='evaluation definition stores a concrete immutable version and bounded manifest bytes')
    for column,value,label in [('case_count','0','empty evaluation definitions are rejected'),('case_count','41','evaluation definitions enforce forty-case limit'),('byte_size','0','evaluation snapshot bytes cannot be empty'),('byte_size','262145','evaluation snapshot storage is capped at 256 KiB')]:
        sql(f"UPDATE evaluation_suite_revision SET {column}={value} WHERE suite_id='{ev_suite}' AND revision=1;",False,label)
    def evaluation_run_sql(revision=1,owner=author,space=w1,release=rel,top_k=3):
        return f"INSERT INTO evaluation_run(id,suite_id,suite_revision,owner_id,workspace_id,release_id,suite_hash,corpus_hash,index_version,evaluator_version,top_k,case_count,created_at) VALUES ('{ev_run}','{ev_suite}',{revision},'{owner}','{space}','{release}',REPEAT('a',64),REPEAT('b',64),'bm25-script-boundaries-v2','retrieval-macro-v1',{top_k},2,UTC_TIMESTAMP(3));"
    sql(evaluation_run_sql(revision=2),False,'evaluation run requires an existing suite revision')
    sql(evaluation_run_sql(owner=ev_other),False,'evaluation run cannot impersonate a different suite owner')
    sql(evaluation_run_sql(space=w2),False,'evaluation run cannot cross suite workspace')
    sql(evaluation_run_sql(release=uid('other-release')),False,'evaluation run cannot replace its frozen release')
    sql(evaluation_run_sql(top_k=0),False,'evaluation retrieval depth must be positive')
    sql(evaluation_run_sql(top_k=9),False,'evaluation retrieval depth cannot exceed eight')
    sql(evaluation_run_sql(),label='evaluation run retains bounded inputs without premature metrics')
    for assignment,label in [("status='COMPLETED'",'evaluation cannot complete without actual report and aggregate evidence'),("metrics_json=JSON_OBJECT('meanRecallAtK',1)",'queued evaluation cannot claim a score'),("result_hash=REPEAT('c',64)",'queued evaluation cannot claim deterministic result hash'),("lease_token='00000000-0000-0000-0000-000000000001'",'queued evaluation cannot carry a running worker lease'),("status='RUNNING'",'running evaluation requires token and deadline'),("completed_cases=1,result_bytes=2",'queued evaluation cannot claim completed cases')]:
        sql(f"UPDATE evaluation_run SET {assignment} WHERE id='{ev_run}';",False,label)
    sql(f"UPDATE evaluation_run SET status='RUNNING',lease_token='{uid('evaluation-lease')}',lease_until=UTC_TIMESTAMP(3)+INTERVAL 90 SECOND,started_at=UTC_TIMESTAMP(3) WHERE id='{ev_run}';",label='evaluation worker state has an explicit bounded lease')
    for assignment,label in [("completed_cases=3",'evaluation completed count cannot exceed admitted cases'),("result_bytes=1",'evaluation evidence bytes require a completed case'),("completed_cases=1,result_bytes=1048577",'evaluation partial evidence cannot exceed one MiB'),("metrics_json=JSON_OBJECT('meanRecallAtK',1)",'partial running results cannot claim complete aggregate scores')]:
        sql(f"UPDATE evaluation_run SET {assignment} WHERE id='{ev_run}';",False,label)
    def evaluation_result_sql(run=ev_run,position=0,size=2):
        return f"INSERT INTO evaluation_case_result(run_id,case_key,case_position,result_json,result_hash,byte_size) VALUES ('{run}','case-1',{position},JSON_OBJECT(),REPEAT('c',64),{size});"
    sql(evaluation_result_sql(run=uid('evaluation-unknown-run')),False,'evaluation case evidence requires its real run')
    sql(evaluation_result_sql(position=40),False,'evaluation case position cannot exceed forty-case bound')
    sql(evaluation_result_sql(size=0),False,'evaluation case byte accounting cannot be zero')
    sql(evaluation_result_sql(size=65537),False,'evaluation individual result is bounded at 64 KiB')
    sql(evaluation_result_sql(),label='evaluation result has stable case identity position and byte accounting')
    sql(evaluation_result_sql(),False,'evaluation cannot write the same case result twice')
    sql(f"UPDATE evaluation_run SET completed_cases=1,result_bytes=2 WHERE id='{ev_run}';",label='partial evaluation progress records actual stored byte capacity')
    complete="status='COMPLETED',completed_cases=2,result_bytes=4,lease_token=NULL,lease_until=NULL,metrics_json=JSON_OBJECT('totalCases',2),result_hash=REPEAT('c',64),report_json=JSON_OBJECT('format','SQL_CONSTRAINT_FIXTURE_ONLY'),report_hash=REPEAT('d',64),report_bytes=64,finished_at=UTC_TIMESTAMP(3)"
    sql(f"UPDATE evaluation_run SET {complete.replace('report_bytes=64','report_bytes=2097153')} WHERE id='{ev_run}';",False,'evaluation report cannot exceed two MiB')
    sql(f"UPDATE evaluation_run SET {complete.replace('completed_cases=2','completed_cases=1')} WHERE id='{ev_run}';",False,'evaluation aggregate evidence requires all admitted cases completed')
    sql(f"UPDATE evaluation_run SET {complete} WHERE id='{ev_run}';",label='complete evaluation state must carry all bounded evidence fields')
    sql(f"UPDATE evaluation_run SET status='FAILED' WHERE id='{ev_run}';",False,'failed evaluation cannot keep complete-score fields')
    sql("INSERT INTO evaluation_admission_lock(singleton_id) VALUES(2);",False,'evaluation global queue admission lock has one fixed identity')

    # V013: real JSON state and immutable source/review foreign keys. Semantic author checks are also exercised through the service gate.
    text_review=uid('text-review-proof')
    sql(f"INSERT INTO content_review(id,target_kind,target_id,target_revision,author_id,reviewer_id,decision,note) VALUES ('{text_review}','EXERCISE','{exercise}',1,'{author}','{reviewer}','APPROVED','isolated text rubric receipt fixture');",label='TEXT grading has an actual independent exercise review')
    def text_receipt_sql(revision=1,review_id=text_review):
        return f"INSERT INTO exercise_text_review_receipt(exercise_id,exercise_revision,review_id,answer_hash,rubric_version) VALUES ('{exercise}',{revision},'{review_id}',REPEAT('6',64),'text-exact-nfkc-v1');"
    sql(text_receipt_sql(2),False,'TEXT review receipt cannot pin nonexistent exercise revision')
    sql(text_receipt_sql(1,uid('missing-text-review')),False,'TEXT review receipt requires actual review identity')
    sql(text_receipt_sql(),label='TEXT exact rubric receipt stores reviewed answer hash and algorithm version')
    sql(text_receipt_sql(),False,'TEXT rubric version cannot acquire a second mutable review receipt')
    def block_state_sql(artifact_revision=1,revision=1,state="JSON_OBJECT('blockId','flow','stateRevision',1)"):
        return f"INSERT INTO notebook_block_state(artifact_id,artifact_revision,block_key,revision,state_json) VALUES ('{a1}',{artifact_revision},'flow',{revision},{state});"
    sql(block_state_sql(2),False,'Notebook state requires a real immutable artifact version')
    sql(block_state_sql(1,0),False,'Notebook durable state revision must be positive')
    sql(block_state_sql(1,1,"'{invalid'"),False,'Notebook state requires native valid JSON',('ERROR 3140',))
    sql(block_state_sql(),label='Notebook state persists an actual source-version-bound JSON value')
    for action in ['selectType','setParameter','navigateArtifact','appendBlock']:
        sql(f"INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES ('{uid('v06-action-'+action)}','{a1}',1,'flow','{action}',JSON_OBJECT());",label='Notebook typed action receipt accepts '+action)

    # v0.6 import constraints use real native SQL, independent of service validation.
    batch,item=uid('bounded-import-batch'),uid('bounded-import-item')
    sql(f"INSERT INTO import_batch(id,source_id,owner_id,processing_note,created_at,expires_at) VALUES ('{batch}','{source_a}','{author}','isolated preview fixture',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 1 DAY);",label='bounded import binds actual source and authenticated owner')
    for column,value,label in [('revision','0','import revision must be positive'),('status',"'PUBLISHED'",'import preview cannot claim content publication'),('expires_at',"created_at",'import preview expiration must follow creation')]:
        sql(f"UPDATE import_batch SET {column}={value} WHERE id='{batch}';",False,label)
    def import_item_sql(source,ordinal=0):
        return f"INSERT INTO import_batch_item(id,batch_id,source_id,ordinal_number,file_name,media_type,byte_size,file_hash,original_hash,parser_version,status,message,original_markdown,cleaned_markdown) VALUES ('{item}','{batch}','{source}',{ordinal},'lesson.md','text/markdown',7,REPEAT('1',64),REPEAT('2',64),'utf8-newlines-v1','READY','actual text preview','original','cleaned');"
    sql(import_item_sql(source_b),False,'import item cannot claim source from another batch')
    sql(import_item_sql(source_a,8),False,'import item ordinal enforces eight-file limit')
    sql(import_item_sql(source_a),label='valid import item retains exact upload and extracted text hashes')
    for column,value,label in [('byte_size','1048577','import file size exceeds one MiB'),('status',"'COMMITTED'",'committed import requires an actual capture receipt'),('status',"'FAILED'",'failed import requires a stable parser error')]:
        sql(f"UPDATE import_batch_item SET {column}={value} WHERE id='{item}';",False,label)
    sql(f"UPDATE import_batch_item SET status='COMMITTED',capture_id='{uid('nonexistent-import-capture')}' WHERE id='{item}';",False,'import cannot invent its capture result')
    sql(f"UPDATE import_batch_item SET status='COMMITTED',capture_id='{ingestion}' WHERE id='{item}';",label='committed import pins an existing capture from its registered source')
    sql(f"UPDATE import_batch_item SET status='SKIPPED' WHERE id='{item}';",False,'skipped import cannot retain a committed capture identity')

    assert sql(f"SELECT JSON_TYPE(answer) FROM exercise_attempt WHERE id='{attempt}';",label="native JSON answer remains structured object")=="OBJECT"
    sql(f"UPDATE exercise_attempt SET answer='{{invalid' WHERE id='{attempt}';",False,"native JSON column rejects malformed documents",("ERROR 3140",))
    assert all(hashlib.sha256((ROOT/'database'/name).read_bytes()).hexdigest()==digest for name,digest in schema_hashes.items()),'SQL changed during verification'
    return {"result":"PASS","engine":"MySQL "+harness.version,"mode":harness.mode,"tables":table_total,
            "checks":harness.results,"schemaSha256":schema_hashes,
            "seedSha256":hashlib.sha256((ROOT/'database/seed-demo.sql').read_bytes()).hexdigest(),
            "scope":"All current forward migrations and schema integrity on real MySQL; application HTTP and transaction smoke tests run separately"}

def main():
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--connection",default=os.environ.get("MYSQL_TEST_CONNECTION"),help="explicit disposable local MySQL connection JSON (default: MYSQL_TEST_CONNECTION)")
    parser.add_argument("--reset-task-schema",action="store_true",help="recreate only the explicitly marked, reserved-name verification schema")
    parser.add_argument("--image",default="mysql:8.4.13",help="isolated Docker image; actual server must be MySQL 8.4 LTS")
    parser.add_argument("--output",type=Path,default=ROOT/".work/mysql-verification.json")
    args=parser.parse_args()
    harness=MysqlHarness(args)
    report={"result":"FAIL","mode":harness.mode,"checks":harness.results}
    write_report(args.output,report)
    try:
        before=capture_inputs('schema',('scripts/verify_mysql.py',))
        report['verificationInputs']=before
        harness.start()
        report.update(verify(harness))
        report['inputsUnchanged']=require_unchanged(before)
    except Exception as failure:
        report["result"]="FAIL"
        report["error"]=str(failure)
    finally:
        try:harness.close()
        except Exception as failure:
            report["result"]="FAIL";report["cleanupError"]=str(failure)
        write_report(args.output,report)
    print(json.dumps({key:value for key,value in report.items() if key!='verificationInputs'},ensure_ascii=False,indent=2))
    return 0 if report["result"]=="PASS" else 1

if __name__=="__main__":
    sys.exit(main())
