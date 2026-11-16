import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { TestWorkspace, testConcept, testWorkspace } from '../test/workspace';
import { ConceptMap, conceptLayout } from './ConceptMap';

it('retains missing or cyclic published concepts without inventing an acyclic path', () => {
  const concepts = [
    testConcept,
    { ...testConcept, id: 'second', prerequisiteIds: ['concept-1'] },
    { ...testConcept, id: 'cycle-a', prerequisiteIds: ['cycle-b'] },
    { ...testConcept, id: 'cycle-b', prerequisiteIds: ['cycle-a'] },
    { ...testConcept, id: 'missing', prerequisiteIds: ['not-published'] },
  ];
  const layout = conceptLayout(concepts);
  expect(layout.nodes).toHaveLength(5);
  expect(layout.nodes.find((item) => item.concept.id === 'second')!.level).toBeGreaterThan(
    layout.nodes.find((item) => item.concept.id === 'concept-1')!.level,
  );
  expect(layout.unresolved).toEqual(['cycle-a', 'cycle-b']);
  expect(layout.missing).toEqual(['not-published']);
});

it('offers keyboard list navigation, separates reading from evidence and opens the selected real concept', async () => {
  vi.spyOn(api, 'get').mockResolvedValue({
    items: [{ conceptId: 'concept-1', independentSamples: 0 }],
    rebuilding: false,
  });
  const context = testWorkspace();
  const onRead = vi.fn();
  const user = userEvent.setup();
  render(
    <TestWorkspace value={context}>
      <ConceptMap
        concepts={[
          testConcept,
          {
            ...testConcept,
            id: 'next',
            code: 'NEXT',
            title: '后续边界推演',
            prerequisiteIds: [testConcept.id],
          },
        ]}
        selectedId={testConcept.id}
        completed={[testConcept.id]}
        onRead={onRead}
      />
    </TestWorkspace>,
  );
  expect(await screen.findByText('0 个独立样本')).toBeInTheDocument();
  const list = screen.getByRole('button', { name: '层次列表' });
  list.focus();
  await user.keyboard('{Enter}');
  expect(list).toHaveAttribute('aria-pressed', 'true');
  const next = screen.getByRole('button', { name: /NEXT.*后续边界推演.*未记录阅读/ });
  next.focus();
  await user.keyboard('{Enter}');
  const detail = document.getElementById('concept-path-detail')!;
  expect(within(detail).getByRole('heading', { name: '后续边界推演' })).toBeInTheDocument();
  expect(within(detail).getByRole('button', { name: 'PECS · 生产者与消费者' })).toBeInTheDocument();
  await user.click(within(detail).getByRole('button', { name: /阅读/ }));
  expect(onRead).toHaveBeenCalledWith('next');
});
