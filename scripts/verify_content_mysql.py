#!/usr/bin/env python3
"""Verify new editorial transactions against explicitly configured, task-owned real MySQL.

Uses the same restricted connection loader as the transaction-isolation gate. Tests
create random synthetic fixtures and never truncate/reset the runtime database.
Only aggregate results and an actual JDBC engine/version proof enter the report.
"""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from verify_transaction_isolation import mysql_environment, GateFailure

ROOT=Path(__file__).resolve().parents[1]
CLASS='io.typeforge.publishing.ContentWorkbenchIntegrationTest'
REPORT=ROOT/'.work/mysql-content-verification.json'
PREFIX='TYPEFORGE_CONTENT_DATABASE_EVIDENCE '


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven',default='mvn')
    args=parser.parse_args()
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix='mysql-content-',dir=ROOT/'.work'));work.chmod(0o700)
    report={'result':'FAIL','execution':'targeted-real-mysql','testClass':CLASS,'engine':'UNCONFIRMED','tests':0}
    xml=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
    started=time.monotonic()
    try:
        env=mysql_environment();env.pop('TYPEFORGE_MYSQL_ISOLATION_TEST',None);env['TYPEFORGE_MYSQL_CONTENT_TEST']='true'
        executable=shutil.which(args.maven)
        if executable is None and Path(args.maven).is_file():executable=str(Path(args.maven).resolve())
        if executable is None:raise GateFailure('Maven executable not found')
        if xml.exists():xml.replace(work/'previous-junit.xml')
        with (work/'maven.log').open('w',encoding='utf-8') as output:
            result=subprocess.run([executable,'-B','-ntp','-f',str(ROOT/'backend/pom.xml'),'-Dtest=ContentWorkbenchIntegrationTest','-DfailIfNoTests=true','test'],
                                  cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,timeout=240)
        report['mavenExitCode']=result.returncode
        if not xml.is_file():raise GateFailure('No fresh editorial test report was produced')
        suite=ET.parse(xml).getroot()
        report.update({key:int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')})
        report['caseNames']=[case.attrib['name'] for case in suite.findall('testcase')]
        proofs=[]
        for out in suite.iter('system-out'):
            for line in (out.text or '').splitlines():
                if line.strip().startswith(PREFIX):proofs.append(json.loads(line.strip()[len(PREFIX):]))
        for proof in proofs:
            if proof.get('engine')=='MySQL' and str(proof.get('version','')).startswith('8.4.'):
                report['engine']='MySQL';report['engineVersion']=proof['version']
        if result.returncode or report['tests']<14 or any(report[key] for key in ('failures','errors','skipped')) or report['engine']!='MySQL':
            raise GateFailure('Editorial gate failed or lacks actual MySQL 8.4 evidence; inspect the private Maven report')
        report['result']='PASS'
    except Exception as failure:
        report['error']=str(failure) if isinstance(failure,GateFailure) else type(failure).__name__
    finally:
        report['durationSeconds']=round(time.monotonic()-started,3)
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 0 if report['result']=='PASS' else 1


if __name__=='__main__':
    sys.exit(main())
