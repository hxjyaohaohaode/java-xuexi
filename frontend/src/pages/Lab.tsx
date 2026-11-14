import { useState } from 'react';
import { ArrowRight, FlaskConical } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Field, Loading, SectionTitle } from '../components/ui';
import type { LabResult } from '../types';

export function Lab() {
  const { workspace, can, tick, refresh } = useWorkspace();
  const [sourceType, setSourceType] = useState('List<Integer>');
  const [targetType, setTargetType] = useState('List<Number>');
  const [labType, setLabType] = useState('ASSIGNMENT');
  const [operation, setOperation] = useState('READ');
  const [prediction, setPrediction] = useState('UNKNOWN');
  const [result, setResult] = useState<LabResult | null>(null);
  const mutation = useMutation();
  const history = useResource<LabResult[]>(
    can('FINITE_TYPE_LAB') ? `/workspaces/${workspace.id}/labs` : null,
    tick,
  );
  if (!can('FINITE_TYPE_LAB'))
    return (
      <>
        <SectionTitle title="类型实验" />
        <Empty title="当前服务未启用类型实验">可先阅读知识卡并进行客观练习。</Empty>
      </>
    );
  return (
    <>
      <SectionTitle
        eyebrow="把类型关系变成一个可以检验的问题"
        title="类型实验"
        description="有限规则求值器支持常见类型、List 泛型赋值及通配符读写。它不编译或执行 Java；超出规则范围会明确返回“不支持”。"
      />
      <div className="two-column">
        <section className="panel">
          <h2>
            <FlaskConical size={20} />
            先预测，再验证
          </h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void mutation.run(
                () =>
                  api.request<LabResult>(
                    '/labs/sessions',
                    'POST',
                    {
                      workspaceId: workspace.id,
                      releaseId: workspace.releaseId,
                      labType,
                      sourceType,
                      targetType,
                      prediction,
                      ...(labType === 'READ_WRITE' ? { operation } : {}),
                    },
                    { idempotent: true },
                  ),
                (r) => {
                  setResult(r);
                  refresh();
                },
              );
            }}
          >
            <Field label="实验关系">
              <select
                value={labType}
                onChange={(e) => {
                  setLabType(e.target.value);
                  setResult(null);
                }}
              >
                <option value="ASSIGNMENT">赋值：源类型 → 目标类型</option>
                <option value="READ_WRITE">容器的读取或写入</option>
              </select>
            </Field>
            {labType === 'READ_WRITE' && (
              <Field label="操作">
                <select value={operation} onChange={(e) => setOperation(e.target.value)}>
                  <option value="READ">读取：容器 → 接收值类型</option>
                  <option value="WRITE">写入：值类型 → 容器</option>
                </select>
              </Field>
            )}
            <div className="type-flow-input">
              <Field
                label={labType === 'READ_WRITE' && operation === 'READ' ? '容器类型' : '源类型'}
              >
                <input
                  className="code-input"
                  value={sourceType}
                  onChange={(e) => {
                    setSourceType(e.target.value);
                    setResult(null);
                  }}
                  maxLength={500}
                  required
                />
              </Field>
              <ArrowRight size={20} />
              <Field
                label={labType === 'READ_WRITE' && operation === 'WRITE' ? '容器类型' : '目标类型'}
              >
                <input
                  className="code-input"
                  value={targetType}
                  onChange={(e) => {
                    setTargetType(e.target.value);
                    setResult(null);
                  }}
                  maxLength={500}
                  required
                />
              </Field>
            </div>
            <Field label="你的预测">
              <select value={prediction} onChange={(e) => setPrediction(e.target.value)}>
                <option value="UNKNOWN">暂不确定</option>
                <option value="ALLOWED">允许</option>
                <option value="DENIED">拒绝</option>
              </select>
            </Field>
            <ErrorNotice error={mutation.error} />
            <button className="primary" disabled={mutation.busy}>
              {mutation.busy ? '正在求值…' : '检验类型关系'}
            </button>
          </form>
          <div className="lab-examples">
            <span className="muted small">载入例子</span>
            <button
              className="text-button"
              onClick={() => {
                setLabType('ASSIGNMENT');
                setSourceType('List<Integer>');
                setTargetType('List<? extends Number>');
                setResult(null);
              }}
            >
              上界通配符
            </button>
            <button
              className="text-button"
              onClick={() => {
                setLabType('READ_WRITE');
                setOperation('WRITE');
                setSourceType('Integer');
                setTargetType('List<? super Integer>');
                setResult(null);
              }}
            >
              下界写入
            </button>
          </div>
        </section>
        <section className="panel lab-result">
          {result ? (
            <>
              <div className="row spread">
                <h2>规则求值结果</h2>
                <Badge
                  tone={
                    result.verdict === 'ALLOWED'
                      ? 'good'
                      : result.verdict === 'DENIED'
                        ? 'bad'
                        : 'warn'
                  }
                >
                  {result.verdict === 'ALLOWED'
                    ? '允许'
                    : result.verdict === 'DENIED'
                      ? '拒绝'
                      : '不支持此表达式'}
                </Badge>
              </div>
              <p className="preserve-text">{result.explanation}</p>
              <dl className="metadata">
                <dt>规则</dt>
                <dd>
                  <code>{result.ruleCode}</code>
                </dd>
                <dt>求值器版本</dt>
                <dd>{result.rulesVersion}</dd>
                <dt>编译任务</dt>
                <dd>无 · 此功能不是 Java 编译器</dd>
              </dl>
              <p className="muted small">探索记录不会作为独立掌握证据。</p>
            </>
          ) : (
            <Empty title="等待你的第一个预测">
              输入类型关系并提交，结果将由服务端规则求值器返回。
            </Empty>
          )}
        </section>
      </div>
      <section className="panel">
        <h2>最近的实验</h2>
        <ErrorNotice error={history.error} retry={history.reload} />
        {history.loading && !history.data ? (
          <Loading />
        ) : history.data?.length ? (
          <div className="lab-history">
            {history.data.map((item) => (
              <article key={item.id}>
                <Badge
                  tone={
                    item.verdict === 'ALLOWED' ? 'good' : item.verdict === 'DENIED' ? 'bad' : 'warn'
                  }
                >
                  {item.verdict}
                </Badge>
                <p>{item.explanation}</p>
                <code>{item.ruleCode}</code>
              </article>
            ))}
          </div>
        ) : (
          <p className="muted">还没有实验记录。</p>
        )}
      </section>
    </>
  );
}
