import { useEffect, useState } from 'react';
import { Braces, Play, Square } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { Badge, Dialog, Empty, ErrorNotice, Field, Loading, Status } from './ui';
import type { CompileJob, LabResult } from '../types';

export const starterSource =
  'import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        List<Integer> numbers = List.of(1, 2, 3);\n        List<? extends Number> readable = numbers;\n        Number first = readable.get(0);\n    }\n}\n';
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

export function CompilePanel() {
  const { workspace, session, tick, can, refresh } = useWorkspace();
  const [source, setSource] = useSessionDraft(
    `tf.compiler.source.${session.userId}.${workspace.id}`,
    starterSource,
  );
  const [jobId, setJobId] = useSessionDraft(
    `tf.compiler.job.${session.userId}.${workspace.id}`,
    '',
  );
  const [submittedSource, setSubmittedSource] = useSessionDraft(
    `tf.compiler.submitted.${session.userId}.${workspace.id}`,
    '',
  );
  const [labType, setLabType] = useSessionDraft(
    `tf.compiler.labType.${session.userId}.${workspace.id}`,
    'COMPILE_ONLY',
  );
  const [prediction, setPrediction] = useSessionDraft(
    `tf.compiler.prediction.${session.userId}.${workspace.id}`,
    'UNKNOWN',
  );
  const [labId, setLabId] = useSessionDraft(
    `tf.compiler.labId.${session.userId}.${workspace.id}`,
    '',
  );
  const [poll, setPoll] = useState(0);
  const examples = useResource<LabExample[]>('/labs/examples');
  const [exampleId, setExampleId] = useState('');
  const [negative, setNegative] = useState(false);
  const [replacement, setReplacement] = useState<{ source: string; labType: string } | null>(null);
  const example = examples.data?.find((item) => item.id === exampleId) || examples.data?.[0];
  const exampleSource = example && (negative ? example.negativeSource : example.positiveSource);
  const mutation = useMutation();
  const job = useResource<CompileJob>(
    jobId && can('COMPILE_ONLY') ? `/compile-jobs/${jobId}` : null,
    poll + tick,
  );
  const history = useResource<CompileJob[]>(
    can('COMPILE_ONLY') ? `/workspaces/${workspace.id}/compile-jobs` : null,
    tick,
  );
  const experiment = useResource<LabResult>(labId ? `/labs/sessions/${labId}` : null, tick + poll);
  const waiting = jobPending(job.data?.status);
  const sourceBytes = new TextEncoder().encode(source).byteLength;
  useUnsavedChanges(source !== starterSource && source !== submittedSource);
  usePolling(
    can('COMPILE_ONLY') && (waiting || (!!jobId && !job.data)),
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
    <section className="panel compile-panel" aria-label="Java 编译工作台">
      <div className="row spread wrap">
        <h2>
          <Braces size={20} />
          Java 编译工作台
        </h2>
        <Badge>只编译，不运行用户程序</Badge>
      </div>
      <p className="muted">
        检查完整的
        Main.java，保留编译器诊断和这次源码的摘要。编译结果是代码证据，课程作答会另按题目规则判分。
      </p>
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
                  disabled={mutation.busy || source === exampleSource}
                  onClick={() => {
                    if (source !== starterSource && source.trim() && source !== exampleSource)
                      setReplacement({ source: exampleSource!, labType: example.labType });
                    else {
                      setSource(exampleSource!);
                      setLabType(example.labType);
                      setPrediction('UNKNOWN');
                    }
                  }}
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
      {!can('COMPILE_ONLY') && (
        <Empty title="独立编译服务尚未配置">
          可以阅读示例、编辑代码和进行上方类型规则实验；配置服务后可提交真实编译。
        </Empty>
      )}
      <div className="compiler-layout">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!can('COMPILE_ONLY') || waiting || sourceBytes > 32768 || !source.trim()) return;
            const current = source;
            void mutation.run(
              async () => {
                if (labType !== 'COMPILE_ONLY') {
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
                    mode: 'COMPILE_ONLY',
                  },
                  { idempotent: true },
                );
                return { jobId: value.id, labId: '' };
              },
              (value) => {
                setJobId(value.jobId);
                setLabId(value.labId);
                setSubmittedSource(current);
                refresh();
              },
            );
          }}
        >
          <div className="two-column">
            <Field label="代码实验主题">
              <select
                disabled={mutation.busy || waiting}
                value={labType}
                onChange={(event) => setLabType(event.target.value)}
              >
                <option value="COMPILE_ONLY">自由编译检查</option>
                {examples.data?.map((item) => (
                  <option value={item.labType} key={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            </Field>
            {labType !== 'COMPILE_ONLY' && (
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
          <Field label="Main.java" hint="代码只发送给已配置的独立编译服务。">
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
          <div className="row spread wrap">
            <span className="muted small">{sourceBytes.toLocaleString()} / 32,768 字节</span>
            <button
              className="primary"
              disabled={
                !can('COMPILE_ONLY') ||
                mutation.busy ||
                waiting ||
                !source.trim() ||
                sourceBytes > 32768
              }
            >
              <Play size={16} />
              {mutation.busy ? '正在提交…' : waiting ? '正在编译…' : '提交编译'}
            </button>
          </div>
          {sourceBytes > 32768 && <p className="field-error">源码超过 32 KiB，请缩短后提交。</p>}
          <ErrorNotice error={mutation.error} />
        </form>
        <section className="compile-result" aria-live="polite">
          <div className="row spread">
            <h3>编译与诊断</h3>
            {job.data && <Status value={job.data.status} />}
          </div>
          <ErrorNotice error={job.error} retry={job.reload} />
          <ErrorNotice error={experiment.error} retry={experiment.reload} />
          {job.loading && !job.data ? (
            <Loading />
          ) : job.data ? (
            <>
              {submittedSource && source !== submittedSource && (
                <p className="notice warning">
                  代码已修改。下面显示的是上次提交的结果，尚未验证当前编辑内容。
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
                  取消编译任务
                </button>
              )}
            </>
          ) : (
            <Empty title="等待一次真实编译">先写下你的代码，再检查类型边界与诊断位置。</Empty>
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
                setSource(replacement.source);
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
      {can('COMPILE_ONLY') && (
        <details className="compile-history">
          <summary>这个工作空间的近期编译任务</summary>
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
                    · {new Date(item.createdAt).toLocaleString('zh-CN')}
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
      )}
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
      {job.output && <pre className="compiler-output">{job.output}</pre>}
      {job.outputTruncated && <p className="muted small">诊断输出超过容量限制，已截断显示。</p>}
      {job.finishedAt &&
        !job.errorCode &&
        !job.diagnostics.length &&
        !job.output &&
        job.status === 'SUCCEEDED' && <p className="confirmed">编译已完成，没有返回诊断。</p>}
      <details>
        <summary>查看编译证据</summary>
        <dl className="metadata">
          <dt>JDK</dt>
          <dd>{job.jdkVersion || '尚未返回'}</dd>
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
