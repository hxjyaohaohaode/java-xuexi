import { Plus, Trash2 } from 'lucide-react';
import { Field } from './ui';

export interface TextRubric {
  version: 'text-exact-nfkc-v1';
  scope: string;
  caseSensitive: boolean;
  acceptedAnswers: string[];
  rejectedAnswers: string[];
}
export const freshTextRubric = (): TextRubric => ({
  version: 'text-exact-nfkc-v1',
  scope: '',
  caseSensitive: false,
  acceptedAnswers: [''],
  rejectedAnswers: [''],
});

export function TextRuleEditor({
  value,
  onChange,
}: {
  value: TextRubric;
  onChange: (value: TextRubric) => void;
}) {
  return (
    <section className="text-rule-editor">
      <h3>有限文本答案的审核规则</h3>
      <p className="muted small">
        规范化兼容字符与连续空白后，整段精确匹配；标点和词序保留。请提供有限答案和明确反例，不把开放式论述放进这套规则。下面的答案与反例只对编辑和独立审核者开放。
      </p>
      <Field label="向学习者说明核验范围">
        <textarea
          required
          rows={3}
          maxLength={800}
          value={value.scope}
          onChange={(event) => onChange({ ...value, scope: event.target.value })}
          placeholder="说明需要回答的术语、类型表达式或短句，以及标点与词序要求。"
        />
      </Field>
      <label className="check-row">
        <input
          type="checkbox"
          checked={value.caseSensitive}
          onChange={(event) => onChange({ ...value, caseSensitive: event.target.checked })}
        />{' '}
        区分大小写
      </label>
      {(['acceptedAnswers', 'rejectedAnswers'] as const).map((key) => (
        <fieldset key={key}>
          <legend>
            {key === 'acceptedAnswers' ? '允许的完整答案' : '必须拒绝的反例'} · 1 至 16 项
          </legend>
          {value[key].map((answer, index) => (
            <div className="text-rule-variant" key={index}>
              <Field label={`${key === 'acceptedAnswers' ? '允许答案' : '拒绝反例'} ${index + 1}`}>
                <textarea
                  required
                  rows={2}
                  maxLength={4000}
                  value={answer}
                  onChange={(event) =>
                    onChange({
                      ...value,
                      [key]: value[key].map((item, i) => (i === index ? event.target.value : item)),
                    })
                  }
                />
              </Field>
              <button
                type="button"
                className="icon-button"
                aria-label={`移除${key === 'acceptedAnswers' ? '允许答案' : '拒绝反例'} ${index + 1}`}
                disabled={value[key].length <= 1}
                onClick={() =>
                  onChange({ ...value, [key]: value[key].filter((_, i) => i !== index) })
                }
              >
                <Trash2 size={15} />
              </button>
            </div>
          ))}
          <button
            type="button"
            className="text-button"
            disabled={value[key].length >= 16}
            onClick={() => onChange({ ...value, [key]: [...value[key], ''] })}
          >
            <Plus size={15} /> 添加{key === 'acceptedAnswers' ? '允许答案' : '拒绝反例'}
          </button>
        </fieldset>
      ))}
      <p className="muted small">
        服务端会检查规范化后的重复与正反例冲突。每项最多 4,000
        字符，规则修改必须保存为新版本并经另一位编辑审核。
      </p>
    </section>
  );
}
