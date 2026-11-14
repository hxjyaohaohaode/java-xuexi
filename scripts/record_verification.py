#!/usr/bin/env python3
"""Assemble current local evidence from observed reports; never infer a pass from source code.

Run after the documented Java, frontend, H2/MySQL HTTP and isolation commands.
--check-snapshot only validates that the versioned report describes the exact source tree.
Browser acceptance belongs to the exact pushed commit's independent GitHub Actions run;
local DOM integration is explicitly recorded separately from a graphical browser.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPORT = 'evidence/runtime-check-results.json'
REVIEW = 'evidence/runtime-review.md'
EXCLUDED = {REPORT, REVIEW}


def read(relative):
    return json.loads((ROOT / relative).read_text(encoding='utf-8'))


def snapshot():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    files = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
             for name in sorted(set(names)) if name and name not in EXCLUDED and (ROOT / name).is_file()}
    digest = hashlib.sha256(''.join(name+'\0'+value+'\n' for name,value in files.items()).encode()).hexdigest()
    return {'algorithm': 'SHA-256 of sorted relative path + NUL + file SHA-256 + LF',
            'digest': digest, 'excludedReports': sorted(EXCLUDED), 'fileCount': len(files), 'files': files}


def require(condition, description):
    if not condition:
        raise RuntimeError(description)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-snapshot', action='store_true')
    args=parser.parse_args()
    current=snapshot()
    if args.check_snapshot:
        recorded=read(REPORT)['sourceSnapshot']
        require(recorded['digest']==current['digest'] and recorded['files']==current['files'],
                'Source differs from the recorded verification snapshot; run applicable gates and refresh the report')
        print(json.dumps({'result':'PASS','sourceFiles':current['fileCount'],'sourceDigest':current['digest']}))
        return
    static=read('.work/verification.json')
    mysql=read('.work/mysql-verification.json')
    isolation=read('.work/mysql-isolation-verification.json')
    editorial=read('.work/mysql-content-verification.json')
    smokes={name:read(f'.work/smoke-runtime-{name}.json') for name in ('h2','mysql')}
    for name,result in [('contracts',static),('MySQL schema',mysql),('MySQL isolation',isolation),('MySQL editorial',editorial),*smokes.items()]:
        require(result.get('result')=='PASS', name+' verification has not passed')
    pom=ET.parse(ROOT/'backend/pom.xml').getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
    version=pom.find('m:version',ns).text
    jar=ROOT/f'backend/target/typeforge-backend-{version}.jar'
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    with zipfile.ZipFile(jar) as package:
        front=list((ROOT/'frontend/dist').rglob('*'))
        require(bool(front),'Frontend build output is missing')
        expected_entries={'BOOT-INF/classes/static/'+file.relative_to(ROOT/'frontend/dist').as_posix() for file in front if file.is_file()}
        actual_entries={name for name in package.namelist() if name.startswith('BOOT-INF/classes/static/') and not name.endswith('/')}
        require(actual_entries==expected_entries,'Packaged frontend contains missing or obsolete assets; use a clean build')
        for file in front:
            if file.is_file():
                entry='BOOT-INF/classes/static/'+file.relative_to(ROOT/'frontend/dist').as_posix()
                require(package.read(entry)==file.read_bytes(),'Packaged frontend differs from current build: '+file.name)
    require(all(r['jarSha256']==jar_hash for r in smokes.values()),'HTTP reports were not produced against this exact jar')
    require(mysql['schemaSha256']==read('database/migrations.lock.json'),'MySQL verification used different migrations')
    suites=[]
    for path in sorted((ROOT/'backend/target/surefire-reports').glob('TEST-*.xml')):
        root=ET.parse(path).getroot()
        suites.append({'name':root.attrib['name'],**{key:int(root.attrib[key]) for key in ('tests','failures','errors','skipped')},
                       'seconds':float(root.attrib['time']),'testCases':[case.attrib['name'] for case in root.findall('testcase')]})
    totals={key:sum(s[key] for s in suites) for key in ('tests','failures','errors','skipped')}
    require(totals['tests']>0 and totals['failures']==totals['errors']==totals['skipped']==0,'Current Java suite has failures, errors, skips or no tests')
    unit=read('frontend/test-results/unit-report.json')
    require(unit.get('success') is True and unit.get('numFailedTests')==0 and unit.get('numTotalTests',0)>0,'Frontend unit tests did not pass')
    dom=read('frontend/test-results/dom-runtime-report.json')
    dom_steps=read('frontend/test-results/live-dom-report.json')
    require(dom.get('status')==dom_steps.get('status')=='PASS','Real HTTP DOM integration did not pass')
    require(dom.get('jarSha256')==jar_hash,'DOM integration must use the exact packaged jar')
    frontend={key:unit[key] for key in ('numTotalTestSuites','numPassedTestSuites','numFailedTestSuites','numTotalTests','numPassedTests','numFailedTests','numPendingTests','success') if key in unit}
    frontend['integration']={'driver':dom_steps['driver'],'status':'PASS','jarSha256':jar_hash,
                            'steps':dom_steps['steps'],'limits':dom_steps['limits']}
    frontend['graphicalBrowser']={'localStatus':'BLOCKED_BY_ENVIRONMENT',
        'reason':'Local Chromium cannot create required AF_UNIX sockets; no sandbox bypass was attempted.',
        'remoteGate':'Real Chromium Playwright integration is required in .github/workflows/baseline.yml on the exact pushed commit.'}
    report={'schemaVersion':'1.1','result':'PASS',
        'scope':'v0.4 implemented runtime/local verification; graphical browser is an independent remote CI gate; not final course acceptance',
        'recordedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
        'baselineCommit':'01d465d9391f5f035f53c3a8b9e8a1f7762af1dc',
        'environment':{'os':'Linux','maven':'3.9.9','jdk':'21.0.13.0.2','compilerRelease':17,
                       'springBoot':pom.find('m:parent/m:version',ns).text,'mysql':mysql['engine'],
                       'node':subprocess.check_output(['node','--version'],text=True).strip(),
                       'python':subprocess.check_output(['python','--version'],text=True).strip(),
                       'localMysqlMode':'official isolated server; not Docker'},
        'sourceSnapshot':current,
        'java':{'command':'mvn -B -ntp -f backend/pom.xml clean verify; package refreshed after verified frontend-only fixes','result':'PASS',**totals,'jarSha256':jar_hash,'suites':suites},
        'frontend':frontend,'contractsAndArchitecture':static,'mysqlSchema':mysql,'mysqlIsolation':isolation,'mysqlEditorial':editorial,
        'httpAndRestart':{profile:{k:v for k,v in value.items() if k in {'result','profile','engine','startedAt','finishedAt','jarSha256','scope','checks','unexpectedLogErrors'}}
                          | {'httpRequests':len(value['http']),'shutdown':'graceful process termination and restart against the same database'} for profile,value in smokes.items()},
        'remoteCi':{'status':'VERIFY_EXACT_COMMIT_IN_GITHUB_ACTIONS','workflow':'.github/workflows/baseline.yml',
                    'note':'This report records local gates. The published commit Actions run independently verifies Java 17, Docker MySQL and real Chromium.'},
        'notVerified':['real supplier model teaching quality or billing invoices','autonomous multi-agent planner and full expert tool roster',
                       'untrusted user-code execution sandbox','image/audio/video generation or binary document export',
                       'formal human review of the complete course corpus','production load/SLO/backup restore',
                       'hard-kill recovery of paid external effects with a real provider','final grading or individual student contributions']}
    (ROOT/REPORT).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    test_table='\n'.join(f"| `{suite['name'].rsplit('.',1)[-1]}` | {suite['tests']} | 0 / 0 / 0 |" for suite in suites)
    review=f'''# v0.4 当前运行验收记录

这是当前实现的实测记录，不是最终课程成绩或完整产品验收。生成时间：{report['recordedAt']}。机器证据见 [runtime-check-results.json](runtime-check-results.json)，由 [record_verification.py](../scripts/record_verification.py)读取实际测试输出生成；源码摘要变化必须重新核对相关门禁。

## 实际结果

| 门禁 | 已观察的结果 | 证明边界 |
|---|---|---|
| Java/Maven | {totals['tests']} 项，失败/错误/跳过均为 0 | 固定语言语义、SQL/HTTP、任务、导师协议夹具、内容发布与来源/导出回归 |
| 契约与架构 | {static['checksPassed']} 项通过；{static['implementedOperations']} 个 implemented / {static['plannedOperations']} 个 planned 操作 | 真实路由、schema、模块依赖、索引和文档对应；数量不是产品完成率 |
| MySQL 8.4 | {len(mysql['checks'])} 项；{mysql['tables']} 张业务表；六条迁移 | 真实 MySQL 建表、复合外键、唯一性、CHECK、JSON与新增队列/审核/导出约束 |
| MySQL 隔离 | {isolation['tests']} 项 | 实际 READ COMMITTED、锁后可见已提交写入，并有 REPEATABLE READ 对照 |
| MySQL 内容服务 | {editorial['tests']} 项 | 实际 JDBC 引擎证明；人工录入、审核hash、发布/激活及并发服务在真实 MySQL 上复跑 |
| H2 实际 HTTP/重启 | {len(smokes['h2']['checks'])} 项；{len(smokes['h2']['http'])} 次请求 | 单独进程、真实会话/CSRF、业务链、真实下载、重启保留与撤回后失效 |
| MySQL 实际 HTTP/重启 | {len(smokes['mysql']['checks'])} 项；{len(smokes['mysql']['http'])} 次请求 | 同一应用包在真实 MySQL 上运行，独立 SQL 查询验证已写入事实 |
| 前端类型/构建/单元 | {unit['numTotalTests']} 项单元通过；TypeScript 与 Vite 构建成功 | 编译与组件/状态逻辑；不等于真实浏览器布局验收 |
| 前端 DOM + 真实 HTTP | {len(dom_steps['steps'])} 条链通过 | 无服务端响应 mock；jsdom 驱动真实后端；不验证浏览器布局/原生下载 |
| Chromium 浏览器 | 本地被环境阻止；精确提交由 GitHub Actions 运行 | 本地 AF_UNIX 创建受限，不记成通过；远端 Playwright失败会阻断CI |

Java 各测试类的实际汇总：

| 测试类 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---|
{test_table}

## 本轮已覆盖的关键联动

真实作答经 outbox 和幂等 receipt 进入独立题目家族证据与复习；错题块调用同一判分服务。阅读/预测/作品生成只记活动，不给虚假的掌握分。

导师会话绑定本人空间和固定课程；请求固定上下文、租约、epoch 与额度。MODEL协议使用本地真实 HTTP 服务夹具测试响应、超时、异常与引用，EVIDENCE_ONLY真实检索。夹具不是付费供应商教学质量证明。取消、账号禁用、归档、deadline、自然过期、来源撤回及未知计量均有独立边界。

回答转文档保存完整来源；作品保存、上下文读取、错题动作、导出与下载复核有界祖先图、实际内容hash和叶子来源。自然到期不依赖提前产生撤回事件。导出固定 renderer 与源 revision，真实字节与终态同事务；HTML/SVG转义、非法XML字符、失效来源和越权均不会变成可用下载。

人工来源录入、独立作者/审核人、完整审核hash、发布manifest、历史激活CAS在真实应用服务中完成。演示内容的REVIEWED标记不被当作正式审核；被损坏的答案结构受控拒绝。

## 可重跑命令

```sh
python -m pip install -r requirements-dev.txt
python scripts/sync_contract_index.py --check
python scripts/verify.py
npm ci --prefix frontend
npm run check --prefix frontend
npm run build --prefix frontend
mvn -B -ntp -f backend/pom.xml clean verify
python scripts/verify_mysql.py
python scripts/smoke_runtime.py --profile h2
# 已准备专用 MYSQL_TEST_CONNECTION 后：
python scripts/smoke_runtime.py --profile mysql
python scripts/verify_transaction_isolation.py
python scripts/verify_content_mysql.py
# Chromium 需按 frontend/README.md 安装：
python scripts/browser_runtime.py --jar backend/target/typeforge-backend-{version}.jar
```

上述为常规开发命令。生成此报告还需把静态输出存到 `.work/verification.json`、Vitest JSON报告存到 `frontend/test-results/unit-report.json`，并用 `browser_runtime.py --driver dom` 对最终同一 JAR 跑真实HTTP DOM测试。隔离测试会单独产生MySQL报告；最终整套 Maven生成完整Surefire汇总。所有报告必须来自当前构建，不用历史成功结果补齐。

## 当前包与源码

- JAR SHA-256：`{jar_hash}`。
- 源码文件数：{current['fileCount']}；SHA-256 摘要：`{current['digest']}`。
- 只有本记录与机器报告自身从源码摘要排除，避免循环hash；迁移、代码、契约、页面、锁文件、文档及CI均包含。
- 发布提交的远端结果在 [GitHub Actions](https://github.com/hxjyaohaohaode/java-xuexi/actions)核对，不能把本地环境结果当成Java17/Ubuntu/Chromium结果。

## 仍未完成和未验证

真实供应商模型的教学效果、费用账单、完整多专家规划、用户代码沙箱、完整多媒体与二进制导出、全课程人工审核、生产负载与备份恢复、正式答辩和个人贡献均不在本轮PASS范围内。逐模块剩余事项及代码入口见 [接手指南](../docs/02-modules.md)。最终40项课程验收保持独立，不因自动测试通过而自动升级。

旧基线报告和重复字段字典已清理；历史保留在Git。V001–V006为合法升级链，由 `database/migrations.lock.json`锁定，不能为清理旧版本而删除。
'''
    (ROOT/REVIEW).write_text(review,encoding='utf-8')
    print(json.dumps({'result':'PASS','javaTests':totals['tests'],'frontendTests':unit['numTotalTests'],'sourceFiles':current['fileCount'],'sourceDigest':current['digest']},indent=2))


if __name__=='__main__':
    main()
