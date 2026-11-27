import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { CourseExamples } from './CourseExamples';
import { testConcept } from '../test/workspace';
import type { Concept } from '../types';

vi.mock('./Compiler', () => ({
  CompilePanel: ({ lessonExample }: { lessonExample: { code: string } }) => (
    <output>{lessonExample.code}</output>
  ),
}));

it('opens the selected original source and keeps its historical result separate from editing', () => {
  const fixture: Concept = {
    ...testConcept,
    companion: {
      version: 'classroom-edition',
      examples: ['Box', 'Bound'].map((name) => ({
        id: name,
        title: name,
        code: 'class ' + name + ' {}',
        conceptIds: [testConcept.id],
        sha256: '0'.repeat(64),
        section: 'JLS 4.5',
        sourceUrl: 'https://docs.oracle.com/',
        compiles: true,
        stdout: null,
        warning: null,
      })),
      reviews: [],
    },
  };
  const page = render(<CourseExamples concept={fixture} />);
  expect(screen.queryByRole('status')).toBeNull();
  fireEvent.change(screen.getByLabelText('选择课程样例'), { target: { value: 'Bound' } });
  fireEvent.click(screen.getByRole('button', { name: '动手改这段代码' }));
  expect(screen.getByRole('status')).toHaveTextContent('class Bound {}');
  page.rerender(<CourseExamples concept={{ ...testConcept, id: 'unrelated' }} />);
  expect(screen.queryByRole('region', { name: '配套课程实验' })).toBeNull();
});
