import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SourcePreview } from './SourcePreview';
describe('safe source preview', () => {
  it('renders untrusted HTML and script as literal text, without executable elements', () => {
    const payload = '<img src=x onerror="window.stolen=true"><script>alert(1)</script>';
    const { container } = render(
      <SourcePreview
        content={{
          kind: 'DOCUMENT',
          schemaVersion: '1.0',
          title: 'A real draft',
          blocks: [{ id: 'p', kind: 'PARAGRAPH', text: payload, evidenceRefs: [] }],
        }}
      />,
    );
    expect(screen.getByText(payload)).toBeInTheDocument();
    expect(container.querySelector('script,img,iframe,object')).toBeNull();
  });
  it('renders code as code and slide notes as text', () => {
    const payload = '<T extends Number>';
    render(
      <SourcePreview
        content={{
          kind: 'SLIDES',
          schemaVersion: '1.0',
          aspect: '16:9',
          slides: [
            {
              id: 's',
              title: 'Bound',
              elements: [
                {
                  id: 'e',
                  kind: 'CODE',
                  x: 0.1,
                  y: 0.3,
                  width: 0.8,
                  height: 0.5,
                  text: payload,
                  assetId: null,
                  alt: '',
                },
              ],
              notes: 'Explain the bound',
              evidenceRefs: [],
            },
          ],
        }}
      />,
    );
    // Both the spatial slide and its full-text reading view keep code as literal text.
    expect(screen.getAllByText(payload).every((element) => element.tagName === 'CODE')).toBe(true);
    expect(screen.getByText('Explain the bound')).toBeInTheDocument();
  });
});
