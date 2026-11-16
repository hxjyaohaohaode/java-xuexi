import { useId } from 'react';
import { textAnswerLimits } from '../answers';
import type { TextPolicy } from '../types';

export function TextAnswer({
  value,
  onChange,
  policy,
  disabled,
  label = '写下你的文本答案',
}: {
  value: string;
  onChange: (value: string) => void;
  policy: TextPolicy;
  disabled?: boolean;
  label?: string;
}) {
  const id = useId();
  const limits = textAnswerLimits(policy);
  const bytes = new TextEncoder().encode(value).byteLength;
  const invalid = value.length > limits.characters || bytes > limits.bytes || value.includes('\0');
  return (
    <div className="text-answer">
      <div className="text-policy" id={`${id}-policy`}>
        <strong>这道题如何核验</strong>
        <p className="preserve-text">{policy.scope}</p>
        <p className="muted small">
          按审核后的完整答案精确匹配。兼容字符与连续空白会统一，
          {policy.caseSensitive ? '区分' : '不区分'}大小写；标点和词序保留。这里不进行长文语义评分。
        </p>
      </div>
      <label className="field" htmlFor={id}>
        <span>{label}</span>
        <textarea
          id={id}
          rows={5}
          required
          maxLength={limits.characters}
          value={value}
          disabled={disabled}
          aria-invalid={invalid || undefined}
          aria-describedby={`${id}-policy ${id}-count`}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
      <p id={`${id}-count`} className={invalid ? 'field-error' : 'muted small'}>
        {value.length.toLocaleString()} / {limits.characters.toLocaleString()} 字符 ·{' '}
        {bytes.toLocaleString()} / {limits.bytes.toLocaleString()} UTF-8 字节
        {invalid && '；请缩短内容或移除空字符后提交。'}
      </p>
    </div>
  );
}
