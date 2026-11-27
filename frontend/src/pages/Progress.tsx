import { useState } from 'react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Loading, SectionTitle } from '../components/ui';
import { LearningAnalytics } from '../components/LearningAnalytics';
import type { Concept, Progress, Review } from '../types';

export function ProgressPage({ lesson }: { lesson?: Concept } = {}) {
  const { workspace, tick, refresh, navigate, can } = useWorkspace();
  const [projectionTick, setProjectionTick] = useState(0);
  const allProgress = useResource<Progress>('/me/progress', tick + projectionTick);
  const progress =
    lesson && allProgress.data
      ? {
          ...allProgress,
          data: {
            ...allProgress.data,
            items: allProgress.data.items.filter((item) => item.conceptId === lesson.id),
          },
        }
      : allProgress;
  const allReviews = useResource<Review[]>('/me/reviews', tick + projectionTick);
  const reviews =
    lesson && allReviews.data
      ? { ...allReviews, data: allReviews.data.filter((item) => item.conceptId === lesson.id) }
      : allReviews;
  const concepts = useResource<{ items: Concept[] }>(`/concepts?releaseId=${workspace.releaseId}`);
  const mutation = useMutation();
  usePolling(
    !!progress.data?.rebuilding,
    () => setProjectionTick((value) => value + 1),
    progress.loading || reviews.loading,
    !!progress.error || !!reviews.error,
  );
  const name = (id: string) => concepts.data?.items.find((c) => c.id === id)?.title || '关联概念';
  const pending =
    reviews.data?.filter(
      (r) =>
        (!lesson || r.conceptId === lesson.id) && (r.status === 'PENDING' || r.status === 'DUE'),
    ) || [];
  return (
    <>
      {lesson && (
        <p className="muted small">这里汇总你在本账号内对「{lesson.title}」的作答与复习安排。</p>
      )}
      {!lesson && (
        <SectionTitle
          eyebrow="让证据决定下一步"
          title="进度与复习"
          description="这里展示账号积累的独立样本与服务端复习安排。阅读时长、点击次数和重复刷题不会冒充掌握程度。"
        />
      )}
      {!lesson && can('LEARNING_ANALYTICS') && <LearningAnalytics version={projectionTick} />}
      <ErrorNotice error={progress.error} retry={progress.reload} />
      {progress.loading && !progress.data ? (
        <Loading />
      ) : (
        progress.data && (
          <>
            <div className="summary-strip">
              <div>
                <span>有作答证据的概念</span>
                <strong>{progress.data.items.length}</strong>
              </div>
              <div>
                <span>独立样本合计</span>
                <strong>{progress.data.items.reduce((n, p) => n + p.independentSamples, 0)}</strong>
              </div>
              <div>
                <span>待处理复习</span>
                <strong>{reviews.error ? '—' : pending.length}</strong>
              </div>
              <div>
                <span>证据状态</span>
                <strong className="small-value">
                  {progress.data.rebuilding ? '正在重建' : '已读取'}
                </strong>
              </div>
            </div>
            <section className="panel">
              <h2>概念证据</h2>
              {progress.data.items.length > 0 && (
                <p id="concept-evidence-scroll-hint" className="muted small evidence-scroll-hint">
                  左右滑动查看全部字段，也可以聚焦表格后用方向键滚动。
                </p>
              )}
              {progress.data.items.length ? (
                <div
                  className="table-scroll concept-evidence-scroll"
                  role="region"
                  aria-label="概念证据表格"
                  aria-describedby="concept-evidence-scroll-hint"
                  tabIndex={0}
                >
                  <table className="concept-evidence-table">
                    <thead>
                      <tr>
                        <th>概念</th>
                        <th>独立样本</th>
                        <th>加权证据</th>
                        <th>当前解释</th>
                        <th>下一步</th>
                      </tr>
                    </thead>
                    <tbody>
                      {progress.data.items.map((p) => (
                        <tr key={p.conceptId}>
                          <td>{name(p.conceptId)}</td>
                          <td>{p.independentSamples}</td>
                          <td>
                            {Number(p.weightedCorrect).toFixed(2)} /{' '}
                            {Number(p.weightedTotal).toFixed(2)}
                          </td>
                          <td>
                            <Badge tone={p.evidenceStatus === 'AVAILABLE' ? 'good' : 'warn'}>
                              {p.evidenceStatus === 'AVAILABLE' ? '已有可用证据' : '证据仍不足'}
                            </Badge>
                          </td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => navigate('practice', p.conceptId)}
                            >
                              继续练习
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty
                  title="还没有独立作答证据"
                  action={
                    <button className="primary" onClick={() => navigate('practice')}>
                      开始练习
                    </button>
                  }
                >
                  完成首道练习后，系统会显示归属概念与样本记录。
                </Empty>
              )}
              <p className="muted small">
                加权证据是算法输入，不等于课程成绩或保证掌握。各概念需足够的独立题目家族才能形成稳定判断。
              </p>
            </section>
          </>
        )
      )}
      <section className="panel">
        <div className="row spread review-section-heading">
          <h2>复习安排</h2>
          <span className="muted">主动练习，或明确移除安排</span>
        </div>
        <ErrorNotice error={reviews.error} retry={reviews.reload} />
        <ErrorNotice error={mutation.error} />
        {reviews.loading && !reviews.data ? (
          <Loading />
        ) : reviews.data?.length ? (
          <div>
            {reviews.data.map((review) => (
              <div className="review-row" key={review.id}>
                <div>
                  <strong>{name(review.conceptId)}</strong>
                  <p>{review.reason}</p>
                  <small className="muted">
                    计划时间 {new Date(review.dueAt).toLocaleString('zh-CN')}
                  </small>
                </div>
                <div className="row wrap">
                  <Badge>
                    {(
                      {
                        DISMISSED: '已移除',
                        PENDING: '待复习',
                        DUE: '可以复习',
                        COMPLETED: '已完成',
                      } as Record<string, string>
                    )[review.status] || review.status}
                  </Badge>
                  <button onClick={() => navigate('practice', review.conceptId)}>去复习</button>
                  {review.status !== 'DISMISSED' && (
                    <button
                      className="text-button"
                      disabled={mutation.busy}
                      onClick={() =>
                        void mutation.run(
                          () =>
                            api.request(`/me/reviews/${review.id}`, 'PATCH', {
                              status: 'DISMISSED',
                            }),
                          () => refresh(),
                        )
                      }
                    >
                      移除此安排
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        ) : (
          !reviews.error && (
            <Empty title="当前没有复习安排">服务端会根据真实作答证据生成后续安排。</Empty>
          )
        )}
        <p className="muted small">移除安排只改变计划，不会把该概念标记为已掌握。</p>
      </section>
    </>
  );
}
