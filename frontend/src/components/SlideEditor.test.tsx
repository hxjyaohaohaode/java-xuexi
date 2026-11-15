import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { SlideEditor } from './SlideEditor';
import type { ArtifactContent } from '../types';

it('keeps keyboard layout changes within the slide and preserves evidence and immutable source values', () => {
  const content: Extract<ArtifactContent, { kind: 'SLIDES' }> = {
    kind: 'SLIDES',
    schemaVersion: '1.0',
    aspect: '16:9',
    slides: [
      {
        id: 'slide',
        title: '类型规则',
        notes: '演讲备注',
        evidenceRefs: [{ id: 'evidence', revision: 1 }],
        elements: [
          {
            id: 'element',
            kind: 'TEXT',
            x: 0.1,
            y: 0.1,
            width: 0.9,
            height: 0.8,
            text: '需要保留的文字',
            assetId: null,
            alt: '',
          },
        ],
      },
    ],
  };
  const change = vi.fn();
  render(<SlideEditor content={content} onChange={change} />);
  fireEvent.keyDown(screen.getByRole('button', { name: '布局元素 需要保留的文字' }), {
    key: 'ArrowRight',
    shiftKey: true,
  });
  const updated = change.mock.calls[0][0] as typeof content;
  expect(updated.slides[0].elements[0].x + updated.slides[0].elements[0].width).toBeLessThanOrEqual(
    1,
  );
  expect(updated.slides[0].evidenceRefs).toEqual([{ id: 'evidence', revision: 1 }]);
  expect(updated.slides[0].notes).toBe('演讲备注');
  expect(content.slides[0].elements[0].x).toBe(0.1);
});
