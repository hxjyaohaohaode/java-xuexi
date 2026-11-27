import { useEffect, useState } from 'react';
import { Braces, Play, Square } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { Badge, Dialog, Empty, ErrorNotice, Field, Loading, Status } from './ui';
import type { CompileJob, LabResult } from '../types';

export const starterSource =
  'import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        List<Integer> numbers = List.of(1, 2, 3);\n        List<? extends Number> readable = numbers;\n        System.out.println(readable.get(0));\n    }\n}\n';
export const jobPending = (status?: string) =>
  !!status && ['QUEUED', 'RUNNING', 'COMPILING'].includes(status);
type LabExample = {
  id: string;
  labType: string;
  title: string;
  description: string;
  positiveSource: string;
  negativeSource: string;
  observation: string;
  sourceUrl: string;
};

const pythonStarter = 'numbers = [1, 2, 3]\nprint(numbers[0])\n';
const conceptLabs: Record<string, string> = {
  G04: 'inference',
  G05: 'inference',
  G07: 'bounds',
  G08: 'bounds',
  G12: 'api-design',
  G13: 'capture',
  G14: 'erasure',
  G16: 'raw-refactor',
};
type LessonExample = { id: string; title: string; code: string };

export function CompilePanel({
  initialConceptCode = '',
  lessonExample,
}: {
  initialConceptCode?: string;
  lessonExample?: LessonExample;
} = {}) {
  const { workspace, session, tick, can, refresh } = useWorkspace();
  const draftScope = `${session.userId}.${workspace.id}${initialConceptCode ? '.' + initialConceptCode : ''}`;
  const [javaSource, setJavaSource] = useSessionDraft(
    `tf.compiler.source.${draftScope}`,
    starterSource,
  );
  const [pythonSource, setPythonSource] = useSessionDraft(
    `tf.compiler.python.${draftScope}`,
    pythonStarter,
  );
  const [stdin, setStdin] = useSessionDraft(`tf.compiler.stdin.${draftScope}`, '');
  const [submittedInput, setSubmittedInput] = useSessionDraft(
    `tf.compiler.lastInput.${draftScope}`,
    '',
  );
  const [submittedBuffers, setSubmittedBuffers] = useSessionDraft(
    `tf.compiler.lastBuffers.${draftScope}`,
    { JAVA: '', PYTHON: '' },
  );
  const [jobId, setJobId] = useSessionDraft(`tf.compiler.job.${draftScope}`, '');
  const [submittedSource, setSubmittedSource] = useSessionDraft(
    `tf.compiler.submitted.${draftScope}`,
    '',
  );
  const [labType, setLabType] = useSessionDraft(
    `tf.compiler.labType.${draftScope}`,
    can('UNTRUSTED_CODE_EXECUTION') ? 'RUN_JAVA' : 'COMPILE_ONLY',
  );
  const [prediction, setPrediction] = useSessionDraft(
    `tf.compiler.prediction.${draftScope}`,
    'UNKNOWN',
  );
  const [labId, setLabId] = useSessionDraft(`tf.compiler.labId.${draftScope}`, '');
  const [poll, setPoll] = useState(0);
  const examples = useResource<LabExample[]>('/labs/examples');
  const [exampleId, setExampleId] = useState(conceptLabs[initialConceptCode] || '');
  const isPython = labType === 'RUN_PYTHON';
  const isRun = isPython || labType === 'RUN_JAVA';
  const isExperiment = !isRun && labType !== 'COMPILE_ONLY';
  const canSubmit = can(isRun ? 'UNTRUSTED_CODE_EXECUTION' : 'COMPILE_ONLY');
  const source = isPython ? pythonSource : javaSource;
  const setSource = isPython ? setPythonSource : setJavaSource;
  const stdinBytes = new TextEncoder().encode(stdin).byteLength;
  const [negative, setNegative] = useState(false);
  const [replacement, setReplacement] = useState<{ source: string; labType: string } | null>(null);
  const example = examples.data?.find((item) => item.id === exampleId) || examples.data?.[0];
  const exampleSource = example && (negative ? example.negativeSource : example.positiveSource);
  const mutation = useMutation();
  const job = useResource<CompileJob>(jobId ? `/compile-jobs/${jobId}` : null, poll + tick);
  const history = useResource<CompileJob[]>(`/workspaces/${workspace.id}/compile-jobs`, tick);
  const experiment = useResource<LabResult>(labId ? `/labs/sessions/${labId}` : null, tick + poll);
  const waiting = jobPending(job.data?.status) || (!!jobId && !job.data && job.loading);
  const sourceBytes = new TextEncoder().encode(source).byteLength;
  useUnsavedChanges(
    (javaSource !== starterSource && javaSource !== submittedBuffers.JAVA) ||
      (pythonSource !== pythonStarter && pythonSource !== submittedBuffers.PYTHON) ||
      (isRun && stdin !== submittedInput),
  );
  function loadJava(code: string, nextMode: string) {
    if (javaSource !== starterSource && javaSource.trim() && javaSource !== code) {
      setReplacement({ source: code, labType: nextMode });
    } else {
      setJavaSource(code);
      setLabType(nextMode);
      setPrediction('UNKNOWN');
    }
  }
  usePolling(
    waiting,
    () => setPoll((value) => value + 1),
    job.loading || experiment.loading,
    !!job.error || !!experiment.error,
  );
  useEffect(() => {
    if (job.data && !jobPending(job.data.status)) {
      experiment.reload();
      refresh();
    }
  }, [job.data?.id, job.data?.status]);
  return (
    <section className="panel compile-panel" aria-label="代码工作台">
      <div className="row spread wrap">
        <h2>
          <Braces size={20} />
          代码工作台
        </h2>
        <Badge>
          {can('UNTRUSTED_CODE_EXECUTION') ? '隔离运行 · Java / Python' : 'Java / Python'}
        </Badge>
      </div>
      {lessonExample && (
        <div className="row spread wrap">
          <span>{lessonExample.title}</span>
          <button
            type="button"
            disabled={mutation.busy || waiting}
            onClick={() => loadJava(lessonExample.code, 'RUN_JAVA')}
          >
            载入本课示例
          </button>
        </div>
      )}
      {examples.data?.length ? (
        <details className="lab-examples">
          <summary>从六组类型实验开始</summary>
          <Field label="实验主题">
            <select
              value={example?.id || ''}
              onChange={(event) => setExampleId(event.target.value)}
            >
              {examples.data.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          </Field>
          {example && (
            <>
              <p>{example.description}</p>
              <div className="segments">
                <button
                  aria-pressed={!negative}
                  className={!negative ? 'selected' : ''}
                  onClick={() => setNegative(false)}
                >
                  预期编译通过
                </button>
                <button
                  aria-pressed={negative}
                  className={negative ? 'selected' : ''}
                  onClick={() => setNegative(true)}
                >
                  预期编译拒绝
                </button>
              </div>
              <pre className="lab-example-source">
                <code>{exampleSource}</code>
              </pre>
              <p className="muted small">{example.observation}</p>
              <div className="row spread wrap">
                <a
                  href={
                    /^https:\/\/docs\.oracle\.com\//.test(example.sourceUrl)
                      ? example.sourceUrl
                      : undefined
                  }
                  target="_blank"
                  rel="noreferrer"
                >
                  阅读对应的 Java 语言规范
                </a>
                <button
                  disabled={
                    mutation.busy ||
                    waiting ||
                    (javaSource === exampleSource && labType === example.labType)
                  }
                  onClick={() => loadJava(exampleSource!, example.labType)}
                >
                  填入编辑器
                </button>
              </div>
              <p className="muted small">
                预期结果来自固定教学样例。填入后不会自动提交，实际结果以独立编译服务返回为准。
              </p>
            </>
          )}
        </details>
      ) : (
        <ErrorNotice error={examples.error} retry={examples.reload} />
      )}
      {!canSubmit && (
        <Empty title={isRun ? '隔离运行服务尚未启用' : '独立编译服务尚未配置'}>
          代码可以在这里编辑和保存草稿。
          {isRun && can('COMPILE_ONLY')
            ? '当前可选择 Java 编译检查。'
            : '连接课程的专用 Docker worker 后即可提交。'}
        </Empty>
      )}
      <div className="compiler-layout">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (
              !canSubmit ||
              waiting ||
              sourceBytes > 32768 ||
              !source.trim() ||
              (isRun && stdinBytes > 8192)
            )
              return;
            const current = source;
            void mutation.run(
              async () => {
                if (isExperiment) {
                  const value = await api.request<LabResult>(
                    '/labs/sessions',
                    'POST',
                    {
                      workspaceId: workspace.id,
                      releaseId: workspace.releaseId,
                      source: current,
                      labType,
                      prediction,
                    },
                    { idempotent: true },
                  );
                  return { jobId: value.jobId || '', labId: value.id };
                }
                const value = await api.request<CompileJob>(
                  '/compile-jobs',
                  'POST',
                  {
                    workspaceId: workspace.id,
                    releaseId: workspace.releaseId,
                    source: current,
                    mode: isRun ? 'COMPILE_AND_RUN' : 'COMPILE_ONLY',
                    ...(isRun ? { language: isPython ? 'PYTHON' : 'JAVA', stdin } : {}),
                  },
                  { idempotent: true },
                );
                return { jobId: value.id, labId: '' };
              },
              (value) => {
                setJobId(value.jobId);
                setLabId(value.labId);
                setSubmittedSource(current);
                setSubmittedBuffers((previous) => ({
                  ...previous,
                  [isPython ? 'PYTHON' : 'JAVA']: current,
                }));
                setSubmittedInput(isRun ? stdin : '');
                refresh();
              },
            );
          }}
        >
          <div className="two-column">
            <Field label="语言和任务">
              <select
                disabled={mutation.busy || waiting}
                value={labType}
                onChange={(event) => setLabType(event.target.value)}
              >
                <option value="RUN_JAVA">Java · 运行程序</option>
                <option value="RUN_PYTHON">Python · 运行程序</option>
                <option value="COMPILE_ONLY">Java · 严格编译检查</option>
                {examples.data?.map((item) => (
                  <option value={item.labType} key={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            </Field>
            {isExperiment && (
              <Field label="先预测编译结果">
                <select
                  disabled={mutation.busy || waiting}
                  value={prediction}
                  onChange={(event) => setPrediction(event.target.value)}
                >
                  <option value="UNKNOWN">还不确定</option>
                  <option value="ALLOWED">预计通过</option>
                  <option value="DENIED">预计拒绝</option>
                </select>
              </Field>
            )}
          </div>
          <Field
            label={isPython ? 'main.py' : 'Main.java'}
            hint={
              isPython
                ? '可使用 Python 标准库。'
                : isRun
                  ? '入口为 Main 或课程示例中的 Demo。'
                  : 'Java 17 · 严格类型检查。'
            }
          >
            <textarea
              className="code-editor"
              spellCheck={false}
              rows={16}
              value={source}
              maxLength={32768}
              required
              disabled={mutation.busy}
              onChange={(e) => setSource(e.target.value)}
            />
          </Field>
          {isRun && (
            <Field label="标准输入" hint="按行填写程序读取的数据，最多 8 KiB。">
              <textarea
                rows={2}
                value={stdin}
                spellCheck={false}
                maxLength={8192}
                disabled={mutation.busy}
                onChange={(event) => setStdin(event.target.value)}
              />
            </Field>
          )}
          {isRun && stdinBytes > 8192 && <p className="field-error">标准输入超过 8 KiB。</p>}
          <div className="row spread wrap">
            <span className="muted small">{sourceBytes.toLocaleString()} / 32,768 字节</span>
            <button
              className="primary"
              disabled={
                !canSubmit ||
                (isRun && stdinBytes > 8192) ||
                mutation.busy ||
                waiting ||
                !source.trim() ||
                sourceBytes > 32768
              }
            >
              <Play size={16} />
              {mutation.busy
                ? '正在提交…'
                : waiting
                  ? '任务处理中…'
                  : isRun
                    ? '运行代码'
                    : '提交编译'}
            </button>
          </div>
          {sourceBytes > 32768 && <p className="field-error">源码超过 32 KiB，请缩短后提交。</p>}
          <ErrorNotice error={mutation.error} />
        </form>
        <section className="compile-result" aria-live="polite">
          <div className="row spread">
            <h3>运行结果与诊断</h3>
            {job.data && <Status value={job.data.status} />}
          </div>
          <ErrorNotice error={job.error} retry={job.reload} />
          <ErrorNotice error={experiment.error} retry={experiment.reload} />
          {job.loading && !job.data ? (
            <Loading />
          ) : job.data ? (
            <>
              {!submittedSource && (
                <p className="muted small">正在查看已保存的任务。编辑器保留当前草稿。</p>
              )}
              {submittedSource &&
                (source !== submittedSource ||
                  (isRun && stdin !== submittedInput) ||
                  (job.data.language && job.data.language !== (isPython ? 'PYTHON' : 'JAVA')) ||
                  (job.data.mode && (job.data.mode === 'COMPILE_AND_RUN') !== isRun)) && (
                  <p className="notice warning">
                    下面是上次提交的结果。请重新提交以验证当前代码和输入。
                  </p>
                )}
              <CompileDiagnostics job={job.data} />
              {experiment.data && (
                <div className="lab-observation">
                  <p className="muted small">{experiment.data.explanation}</p>
                  {experiment.data.predictionCorrect !== null &&
                    experiment.data.predictionCorrect !== undefined && (
                      <p className="confirmed">
                        {experiment.data.predictionCorrect
                          ? '这次预测与真实编译结果一致。'
                          : '这次结果与预测不同，可以从诊断位置继续追查。'}
                      </p>
                    )}
                </div>
              )}
              {waiting && (
                <button
                  disabled={mutation.busy}
                  onClick={() =>
                    void mutation.run(
                      () =>
                        api.request(`/compile-jobs/${job.data!.id}/cancel`, 'POST', undefined, {
                          idempotent: true,
                        }),
                      () => {
                        job.reload();
                        refresh();
                      },
                    )
                  }
                >
                  <Square size={14} />
                  取消任务
                </button>
              )}
            </>
          ) : (
            <Empty title="等待提交">运行后可查看程序输出或编译器诊断。</Empty>
          )}
        </section>
      </div>
      {replacement && (
        <Dialog title="替换编辑器内容" onClose={() => setReplacement(null)}>
          <h2>用示例替换当前代码？</h2>
          <p>当前编辑器中已有代码。替换后，上一次编译诊断仍然保留，但编辑内容会改为所选示例。</p>
          <div className="row wrap">
            <button onClick={() => setReplacement(null)}>保留当前代码</button>
            <button
              className="primary"
              onClick={() => {
                setJavaSource(replacement.source);
                setLabType(replacement.labType);
                setPrediction('UNKNOWN');
                setReplacement(null);
              }}
            >
              使用这份示例
            </button>
          </div>
        </Dialog>
      )}
      <details className="compile-history">
        <summary>这个工作空间的近期代码任务</summary>
        <ErrorNotice error={history.error} retry={history.reload} />
        {!history.data?.length ? (
          <p className="muted small">还没有编译任务。</p>
        ) : (
          history.data.map((item) => (
            <div key={item.id} className="history-row">
              <div>
                <Status value={item.status} />
                <small className="muted">
                  {' '}
                  · {item.language === 'PYTHON' ? 'Python' : 'Java'}{' '}
                  {item.mode === 'COMPILE_AND_RUN' ? '运行' : '编译'} ·{' '}
                  {new Date(item.createdAt).toLocaleString('zh-CN')}
                </small>
              </div>
              <button
                className="text-button"
                onClick={() => {
                  setJobId(item.id);
                  setLabId('');
                  setSubmittedSource('');
                }}
              >
                查看诊断
              </button>
            </div>
          ))
        )}
      </details>
    </section>
  );
}

export function CompileDiagnostics({ job }: { job: CompileJob }) {
  return (
    <>
      {jobPending(job.status) && (
        <Loading
          label={job.status === 'QUEUED' ? '任务已入队，等待编译服务。' : '独立编译服务正在处理。'}
        />
      )}
      {job.errorCode && (
        <p className="notice warning">
          本次任务未能完成：{job.errorCode}。这不等同于课程作答错误。
        </p>
      )}
      {job.status === 'CANCELLED' && (
        <p className="muted">任务已取消，迟到结果不会覆盖当前状态。</p>
      )}
      {job.diagnostics.length > 0 && (
        <ol className="diagnostic-list">
          {job.diagnostics.map((item, index) => (
            <li key={`${item.code}:${index}`}>
              <div className="row wrap">
                <Badge tone={item.severity === 'ERROR' ? 'bad' : 'warn'}>{item.severity}</Badge>
                {item.line != null && item.line > 0 && (
                  <span className="small">
                    第 {item.line} 行
                    {item.column != null && item.column > 0 ? `，第 ${item.column} 列` : ''}
                  </span>
                )}
              </div>
              <p className="preserve-text">{item.message}</p>
              <code>{item.code}</code>
            </li>
          ))}
        </ol>
      )}
      {job.mode === 'COMPILE_AND_RUN' && (
        <p className="muted small">
          {job.language === 'PYTHON' ? 'Python' : 'Java'} · 标准输出与错误输出
        </p>
      )}
      {job.output && <pre className="compiler-output">{job.output}</pre>}
      {job.outputTruncated && <p className="muted small">输出超过容量限制，已截断显示。</p>}
      {job.finishedAt &&
        !job.errorCode &&
        !job.diagnostics.length &&
        !job.output &&
        job.status === 'SUCCEEDED' && (
          <p className="confirmed">
            {job.mode === 'COMPILE_AND_RUN'
              ? '程序正常结束，没有输出。'
              : '编译已完成，没有返回诊断。'}
          </p>
        )}
      <details>
        <summary>查看编译证据</summary>
        <dl className="metadata">
          <dt>工具链</dt>
          <dd>{job.runtimeVersion || job.jdkVersion || '尚未返回'}</dd>
          <dt>源码摘要</dt>
          <dd>
            <code>{job.sourceHash}</code>
          </dd>
          <dt>任务编号</dt>
          <dd>{job.id}</dd>
        </dl>
      </details>
    </>
  );
}
