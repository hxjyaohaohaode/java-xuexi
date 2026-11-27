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
import io
import json
from pathlib import Path
import subprocess
import unittest
import xml.etree.ElementTree as ET
from verification_inputs import (capture_dist, capture_inputs, java_test_inventory, require,
                                 require_current_inputs, require_unchanged,
                                 validate_java_summaries, write_report)
from verify_java import packaged_jar, validate_packaged_dist
from verify_private_modules_mysql import SUITES as PRIVATE_SUITES
from verify_v06_modules_mysql import SUITES as V06_SUITES

ROOT = Path(__file__).resolve().parents[1]
REPORT = 'evidence/runtime-check-results.json'
REVIEW = 'evidence/runtime-review.md'
EXCLUDED = {REPORT, REVIEW}


def read(relative):
    return json.loads((ROOT / relative).read_text(encoding='utf-8'))


def snapshot():
    inputs = capture_inputs('static', root=ROOT)
    return {key: inputs[key] for key in ('algorithm', 'digest', 'fileCount', 'files')} | {'excludedReports': sorted(EXCLUDED)}


def read_bound(relative, scope, extra_files=()):
    report = read(relative)
    require_current_inputs(report, scope, extra_files, ROOT)
    return report


def compact(report):
    """Retain bindings, omit duplicate full file maps already present in sourceSnapshot."""
    result = dict(report)
    for key in ('verificationInputs', 'dist', 'frontendDist'):
        if key in result:
            result[key] = {name: value for name, value in result[key].items() if name != 'files'}
    return result


def verify_offline_python(directory, scope, stem, description):
    destination = ROOT / ('.work/' + stem + '-verification.json')
    report = {'result': 'FAIL', 'scope': description}
    write_report(destination, report)
    before = capture_inputs(scope, ('scripts/record_verification.py',), ROOT)
    output = io.StringIO()
    suite = unittest.TestLoader().discover(str(ROOT / directory), pattern='test_*.py')
    result = unittest.TextTestRunner(stream=output, verbosity=2).run(suite)
    report.update({'result': 'PASS' if result.wasSuccessful() and result.testsRun > 0 and not result.skipped else 'FAIL',
                   'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
                   'skipped': len(result.skipped), 'verificationInputs': before,
                   'inputsUnchanged': require_unchanged(before, ROOT)})
    (ROOT / ('.work/' + stem + '.log')).write_text(output.getvalue(), encoding='utf-8')
    write_report(destination, report)
    return report


def verify_compiler_python():
    return verify_offline_python('workers/compiler', 'compiler', 'compiler-unit',
                                'Offline compiler broker/verifier behavior; no real container execution claim')


def validate_unit_summaries(frontend):
    expected = {path.relative_to(ROOT).as_posix() for suffix in ('*.test.ts', '*.test.tsx')
                for path in (ROOT / 'frontend/src').rglob(suffix)}
    files = frontend.get('unitFiles', [])
    require(expected and len(files) == len(expected) and {item['name'] for item in files} == expected,
            'Bound frontend proof omitted or duplicated a current unit test file')
    for item in files:
        names = item.get('caseNames', [])
        require(item['tests'] == len(names) == len(set(names)) and bool(names),
                'Bound frontend proof omitted or duplicated actual unit cases')
    summary = frontend['unitSummary']
    total = sum(item['tests'] for item in files)
    require(summary.get('success') is True and summary['numTotalTests'] == summary['numPassedTests'] == total
            and summary['numFailedTests'] == summary['numPendingTests'] == summary['numFailedTestSuites'] == 0,
            'Bound frontend proof is not an entirely passing run')
    require(frontend.get('commands') == ['npm run check', 'npm run build'],
            'Frontend proof did not run the required actual type/test/build commands')
    return summary


def validate_native_group(report, suite_names, inventory):
    summaries = [{'name': 'io.typeforge.' + item['suite'],
                  **{key: item[key] for key in ('tests', 'failures', 'errors', 'skipped')},
                  'testCases': item['caseNames']} for item in report.get('suites', [])]
    expected = {'io.typeforge.' + name: inventory['io.typeforge.' + name] for name in suite_names}
    totals = validate_java_summaries(summaries, expected)
    require(report.get('tests') == totals['tests'] and report.get('mavenExitCode') == 0,
            'Native MySQL group does not contain the complete successful current suite')


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
    static=read_bound('.work/verification.json', 'static')
    mysql=read_bound('.work/mysql-verification.json', 'schema', ('scripts/verify_mysql.py',))
    isolation=read_bound('.work/mysql-isolation-verification.json', 'backend', ('scripts/verify_transaction_isolation.py',))
    editorial=read_bound('.work/mysql-content-verification.json', 'backend', ('scripts/verify_content_mysql.py', 'scripts/verify_transaction_isolation.py'))
    private_modules=read_bound('.work/mysql-account-assets-verification.json', 'backend', ('scripts/verify_private_modules_mysql.py', 'scripts/verify_transaction_isolation.py'))
    v06_modules=read_bound('.work/mysql-v06-modules-verification.json', 'backend', ('scripts/verify_v06_modules_mysql.py', 'scripts/verify_transaction_isolation.py'))
    java=read_bound('.work/java-build-verification.json', 'backend', ('scripts/verify_java.py',))
    front_build=read_bound('.work/frontend-build-verification.json', 'frontend', ('scripts/verify_frontend.py',))
    inventory=java_test_inventory(ROOT)
    require(java.get('testInventory')==inventory, 'Full Java build did not verify the complete current test-source inventory')
    suites=java.get('suites', [])
    totals=validate_java_summaries(suites, inventory)
    require(all(java.get(key)==value for key,value in totals.items()) and java.get('mavenExitCode')==0
            and java.get('command')=='mvn -B -ntp -f backend/pom.xml clean verify',
            'Java proof is not a successful actual complete clean verify')
    for native in (isolation, editorial):
        test_class=native['testClass']
        validate_java_summaries([{'name': test_class, **{key: native[key] for key in ('tests','failures','errors','skipped')},
                                 'testCases': native['caseNames']}], {test_class: inventory[test_class]})
        require(native.get('mavenExitCode')==0 and native.get('engine')=='MySQL'
                and str(native.get('engineVersion','')).startswith('8.4.'), 'Native gate lacks actual successful MySQL 8.4 evidence')
    validate_native_group(private_modules, PRIVATE_SUITES, inventory)
    validate_native_group(v06_modules, V06_SUITES, inventory)
    unit=validate_unit_summaries(front_build)
    dist=capture_dist(ROOT)
    require(front_build.get('dist')==java.get('frontendDist')==dist
            and java.get('frontendInputDigest')==front_build['verificationInputs']['digest'],
            'Current dist, verified frontend build and packaged Java build do not share the same inputs')
    pom=ET.parse(ROOT/'backend/pom.xml').getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
    version=pom.find('m:version',ns).text
    jar=packaged_jar(ROOT)
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    require(java.get('jarSha256')==jar_hash and java.get('jar')==jar.relative_to(ROOT).as_posix(),
            'Current JAR was not produced by the bound full Java clean-verify run')
    validate_packaged_dist(jar, dist, ROOT)
    smokes={name:read_bound(f'.work/smoke-runtime-{name}.json', 'backend', ('scripts/smoke_runtime.py',)) for name in ('h2','mysql')}
    require(all(r['jarSha256']==jar_hash for r in smokes.values()),'HTTP reports were not produced against this exact jar')
    for name,smoke in smokes.items():
        require(smoke.get('profile')==name and smoke.get('checks') and smoke.get('http')
                and all(item.get('result')=='PASS' for item in smoke['checks']),
                'HTTP report lacks completed passing checks for the requested engine')
    require(mysql['schemaSha256']==read('database/migrations.lock.json'),'MySQL verification used different migrations')
    require(mysql.get('checks') and all(item.get('result')=='PASS' for item in mysql['checks']),
            'MySQL schema report has incomplete or failed constraints')
    dom=read_bound('frontend/test-results/dom-runtime-report.json', 'frontend', ('scripts/browser_runtime.py',))
    dom_steps=read('frontend/test-results/live-dom-report.json')
    require(dom.get('status')==dom_steps.get('status')=='PASS','Real HTTP DOM integration did not pass')
    require(dom.get('jarSha256')==jar_hash and dom.get('jarUnchanged') is True and dom.get('driver')=='dom'
            and dom.get('exitCode')==0, 'DOM integration must successfully use the exact unchanged packaged jar')
    require(dom.get('driverReportSha256')==hashlib.sha256((ROOT/'frontend/test-results/live-dom-report.json').read_bytes()).hexdigest(),
            'DOM step report does not belong to the current successful driver execution')
    require(dom_steps.get('steps') and all(item.get('status')=='PASS' for item in dom_steps['steps']),
            'DOM integration contains missing or incomplete steps')
    compiler_unit=verify_compiler_python()
    verification_guards=verify_offline_python('scripts', 'static', 'verification-guards',
        'Offline verification regressions using temporary Git trees and stub subprocesses: stale reports, changed inputs/JAR, missing suites/cases and failed drivers; no actual Maven/browser/MySQL claim')
    for name,result in [('Compiler Python',compiler_unit),('Verification guards',verification_guards)]:
        require(result['result']=='PASS', name+' verification has not passed')
    frontend={key:unit[key] for key in ('numTotalTestSuites','numPassedTestSuites','numFailedTestSuites','numTotalTests','numPassedTests','numFailedTests','numPendingTests','success') if key in unit}
    frontend.update({'build':compact(front_build), 'unitFiles':front_build['unitFiles']})
    frontend['integration']={'driver':dom_steps['driver'],'status':'PASS','jarSha256':jar_hash,
                            'steps':dom_steps['steps'],'limits':dom_steps['limits'],
                            'runtime':compact(dom)}
    graphics = read_bound('frontend/test-results/browser-runtime-report.json', 'frontend', ('scripts/browser_runtime.py',))
    observations = read('frontend/test-results/browser-report.json')
    require(graphics.get('status') == observations.get('status') == 'PASS'
            and graphics.get('jarSha256') == jar_hash and graphics.get('jarUnchanged') is True
            and graphics.get('driver') == 'browser' and graphics.get('exitCode') == 0,
            'Graphical browser must use the same unchanged packaged JAR')
    require(graphics.get('driverReportSha256') == hashlib.sha256(
            (ROOT/'frontend/test-results/browser-report.json').read_bytes()).hexdigest(),
            'Browser results do not match the driver receipt')
    browser_cases = observations.get('results', [])
    require(browser_cases and all(item.get('status') == 'PASS' for item in browser_cases),
            'Graphical browser checks are incomplete')
    frontend['graphicalBrowser'] = {'localStatus': 'PASS', 'results': browser_cases,
                                    'runtime': compact(graphics)}
    require_unchanged(static['verificationInputs'], ROOT)
    require(hashlib.sha256(jar.read_bytes()).hexdigest()==jar_hash and capture_dist(ROOT)==dist,
            'Verified JAR or frontend dist changed while assembling evidence')
    report={'schemaVersion':'1.2','result':'PASS',
        'scope':'Integrated classroom verification including the packaged browser journey; isolated code execution is separately verified in CI',
        'recordedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
        'baselineCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'environment':{'os':'Linux', 'maven':subprocess.check_output(['mvn','--version'],text=True).splitlines()[0],
                       'jdk':subprocess.check_output(['javac','-version'],text=True).strip(),'compilerRelease':17,
                       'springBoot':pom.find('m:parent/m:version',ns).text,'mysql':mysql['engine'],
                       'node':subprocess.check_output(['node','--version'],text=True).strip(),
                       'python':subprocess.check_output(['python','--version'],text=True).strip(),
                       'localMysqlMode':mysql.get('mode', 'see schema report')},
        'sourceSnapshot':current,
        'java':compact(java),
        'frontend':frontend,'contractsAndArchitecture':compact(static),'mysqlSchema':compact(mysql),'mysqlIsolation':compact(isolation),'mysqlEditorial':compact(editorial),'mysqlAccountsAndAssets':compact(private_modules),'mysqlV06Modules':compact(v06_modules),'compilerPython':compact(compiler_unit),'verificationGuards':compact(verification_guards),
        'httpAndRestart':{profile:{k:v for k,v in compact(value).items() if k in {'result','profile','engine','startedAt','finishedAt','jarSha256','scope','checks','unexpectedLogErrors','verificationInputs','inputsUnchanged'}}
                          | {'httpRequests':len(value['http']),'shutdown':'graceful process termination and restart against the same database'} for profile,value in smokes.items()},
        'remoteCi':{'status':'VERIFY_EXACT_COMMIT_IN_GITHUB_ACTIONS','workflow':'.github/workflows/baseline.yml',
                    'note':'This report records local gates. The published commit Actions run independently verifies Java 17, Docker MySQL, the actual isolated javac worker and real Chromium.'},
        'notVerified':['real supplier model teaching quality or billing invoices','unrestricted autonomous planner and full expert/media tool roster',
                       'production deployment of the Java/Python execution service','generative image/audio/video services and production deployment of the isolated compiler service',
                       'formal human review of the complete course corpus','production load/SLO/backup restore',
                       'hard-kill recovery of paid external effects with a real provider','final grading or individual student contributions']}
    write_report(ROOT/REPORT, report)
    test_table='\n'.join(f"| `{suite['name'].rsplit('.',1)[-1]}` | {suite['tests']} | 0 / 0 / 0 |" for suite in suites)
    review=f'''# 联动课堂运行验收记录

这是当前实现的实测记录，不是最终课程成绩或完整产品验收。生成时间：{report['recordedAt']}。机器证据见 [runtime-check-results.json](runtime-check-results.json)，由 [record_verification.py](../scripts/record_verification.py)读取实际测试输出生成；源码摘要变化必须重新核对相关门禁。

## 课程组交付的来源

同学的 11 月 18 日课程包对应 4a725a4，收录八类材料、可运行 JAR、答辩 PPT、实操视频、16 份原始 Java 样例及 5 道导师问答的历史复核。新课堂将原始代码和订正接入概念阅读与实验。[原始提交](https://github.com/hxjyaohaohaode/java-xuexi/pull/1) · [课程发布包与原始回执](https://github.com/wioo111/java-xuexi/releases/tag/typeforge-course-20261118) · [课堂需求与验收清单](../docs/25-integrated-classroom.md)

## 实际结果

| 门禁 | 已观察的结果 | 证明边界 |
|---|---|---|
| Java/Maven | {totals['tests']} 项，失败/错误/跳过均为 0 | 固定语言语义、账户/会话、SQL/HTTP、编译协议、学习任务/模型协议夹具、发布、素材与原生文件导出回归 |
| 契约与架构 | {static['checksPassed']} 项通过；{static['implementedOperations']} 个 implemented / {static['plannedOperations']} 个 planned 操作 | 真实路由、schema、模块依赖、索引和文档对应；数量不是产品完成率 |
| MySQL 8.4 | {len(mysql['checks'])} 项；{mysql['tables']} 张业务表；{len(mysql['schemaSha256'])} 条迁移 | 真实 MySQL 建表、复合外键、唯一性、CHECK、JSON及评测/导入/审核/状态约束 |
| MySQL 隔离 | {isolation['tests']} 项 | 实际 READ COMMITTED、锁后可见已提交写入，并有 REPEATABLE READ 对照 |
| MySQL 内容服务 | {editorial['tests']} 项 | 实际 JDBC 引擎证明；人工录入、审核hash、发布/激活及并发服务在真实 MySQL 上复跑 |
| MySQL 账户与素材 | {private_modules['tests']} 项 | 实际 JDBC 引擎证明；账户关闭/会话/并发身份、UTC统计、上传/撤回/配额及下载隔离 |
| MySQL v0.6 业务链 | {v06_modules['tests']} 项 | 实际 JDBC 引擎证明；批量导入/原子回滚、修订对比、私有评测/容量/取消、TEXT独立审核判分、六类错题块与管理统计 |
| 编译 broker Python | {compiler_unit['tests']} 项 | 离线协议/边界/清理归属；真实容器隔离在远端 CI 独立执行 |
| 验收脚本回归 | {verification_guards['tests']} 项 | 临时 Git/进程夹具验证旧 PASS 复用、缺失测试、源码/JAR变化与驱动失败；不是实际 Maven、浏览器或数据库执行 |
| H2 实际 HTTP/重启 | {len(smokes['h2']['checks'])} 项；{len(smokes['h2']['http'])} 次请求 | 单独进程、真实会话/CSRF、业务链、真实下载、重启保留与撤回后失效 |
| MySQL 实际 HTTP/重启 | {len(smokes['mysql']['checks'])} 项；{len(smokes['mysql']['http'])} 次请求 | 同一应用包在真实 MySQL 上运行，独立 SQL 查询验证已写入事实 |
| 前端类型/构建/单元 | {unit['numTotalTests']} 项单元通过；TypeScript 与 Vite 构建成功 | 编译与组件/状态逻辑；不等于真实浏览器布局验收 |
| 前端 DOM + 真实 HTTP | {len(dom_steps['steps'])} 条链通过 | 无服务端响应 mock；jsdom 驱动真实后端；不验证浏览器布局/原生下载 |
| Chromium 浏览器 | {len(browser_cases)} 个检查段通过 | 真实浏览器操作同一份 JAR；CI 另行验证推送的提交 |

Java 测试类汇总

| 测试类 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---|
{test_table}

## 三轮审查与关键联动

完整审查记录见 [development-review-v06.md](development-review-v06.md)：第一轮对齐16模块、接口与数据边界并补齐评测/导入/文本/错题链；第二轮交叉审查并修复上传准入内存、DOCX重复分支、评测中间结果容量、旧块兼容、长题/引用上限与跨题提示问题；第三轮完整回归、MySQL、重启与前端DOM验证，精确提交再由远端图形浏览器核验。每轮包含实际发现及修改，不以同一条命令重复三次代替审查。

真实作答经 outbox 和幂等 receipt 进入独立题目家族证据与复习；错题块调用同一判分服务。阅读/预测/作品生成只记活动，不给虚假的掌握分。

导师会话绑定本人空间和固定课程；请求固定上下文、租约、epoch 与额度。MODEL协议使用本地真实 HTTP 服务夹具测试响应、超时、异常与引用，EVIDENCE_ONLY真实检索。夹具不是付费供应商教学质量证明。取消、账号禁用、归档、deadline、自然过期、来源撤回及未知计量均有独立边界。

回答转文档保存完整来源；作品保存、上下文读取、错题动作、导出与下载复核有界祖先图、实际内容hash和叶子来源。自然到期不依赖提前产生撤回事件。导出固定 renderer 与源 revision，真实字节与终态同事务；HTML/SVG转义、非法XML字符、失效来源和越权均不会变成可用下载。

人工来源录入、独立作者/审核人、完整审核hash、发布manifest、历史激活CAS在真实应用服务中完成。演示内容的REVIEWED标记不被当作正式审核；被损坏的答案结构受控拒绝。

## 可重跑命令

```sh
python -m pip install -r requirements-dev.txt
python scripts/sync_contract_index.py --check
python -m unittest discover -s scripts -p 'test_*.py'
npm ci --prefix frontend
python scripts/verify_frontend.py
python scripts/verify_java.py
python scripts/verify_mysql.py
python scripts/smoke_runtime.py --profile h2
# 已准备专用 MYSQL_TEST_CONNECTION 后：
python scripts/smoke_runtime.py --profile mysql
python scripts/verify_transaction_isolation.py
python scripts/verify_content_mysql.py
python scripts/verify_private_modules_mysql.py
python scripts/verify_v06_modules_mysql.py
python scripts/browser_runtime.py --driver dom --jar backend/target/typeforge-backend-{version}.jar
# Chromium 需按 frontend/README.md 安装：
python scripts/browser_runtime.py --jar backend/target/typeforge-backend-{version}.jar
# 文档与源码均冻结后，静态检查和最终汇总必须最后执行：
python scripts/verify.py
python scripts/record_verification.py
python scripts/record_verification.py --check-snapshot
```

`verify_frontend.py` 和 `verify_java.py` 会先移走旧构建/测试结果，再实际执行完整检查，分别输出 `.work/frontend-build-verification.json` 与 `.work/java-build-verification.json`。后者保存本轮全部 JUnit 方法及每个参数样例的结果，后续 MySQL 定向测试改写 Surefire XML 不会混入完整 Java 汇总；不能跳过执行后为旧报告补摘要。

每个门禁记录其实际输入的运行前后摘要：后端包含代码、测试、资源、契约与迁移，前端包含页面、测试和构建锁文件，SQL 门禁包含迁移与种子；各门禁自身脚本及公共摘要实现也受绑定。HTTP 与 DOM 还必须使用相同的最终 JAR，DOM 步骤报告必须匹配本轮驱动输出 hash。最终汇总拒绝旧源码、旧构建、缺少样例或没有新输出的 PASS。仅修改接手文档时，只需重新执行静态检查与汇总；实现、测试、契约或迁移变化必须重跑其相关门禁。

## 当前包与源码

- JAR SHA-256：`{jar_hash}`。
- 源码文件数：{current['fileCount']}；SHA-256 摘要：`{current['digest']}`。
- 只有本记录与机器报告自身从源码摘要排除，避免循环hash；迁移、代码、契约、页面、锁文件、文档及CI均包含。
- 发布提交的远端结果在 [GitHub Actions](https://github.com/hxjyaohaohaode/java-xuexi/actions)核对，不能把本地环境结果当成Java17/Ubuntu/Chromium结果。

## 仍未完成和未验证

真实供应商模型的教学效果、费用账单、通用任意工具调度、隔离编译服务的生产部署、生成式图像/音频/视频、全课程人工审核、生产负载与备份恢复、正式答辩和个人贡献均不在本轮PASS范围内。真实 Docker 编译检查由远端 CI 独立证明，不等于生产部署。逐模块剩余事项及代码入口见 [接手指南](../docs/02-modules.md)。最终40项课程验收保持独立，不因自动测试通过而自动升级。

旧基线报告和重复字段字典已清理；历史保留在Git。V001–V014为合法升级链，由 `database/migrations.lock.json`锁定，不能为清理旧版本而删除。
'''
    (ROOT/REVIEW).write_text(review,encoding='utf-8')
    print(json.dumps({'result':'PASS','javaTests':totals['tests'],'frontendTests':unit['numTotalTests'],'sourceFiles':current['fileCount'],'sourceDigest':current['digest']},indent=2))


if __name__=='__main__':
    main()
