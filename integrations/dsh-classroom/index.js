import { defineTool } from '@deepseek-ai/dsh-tools';
import { readPacket } from './classroom.js';

export const name = 'typeforge-classroom';
export const inject = ['tools'];

const output = {
  schema: { type: 'string' },
  render: (_args, text) => [{ type: 'text', text }],
};

export function apply(ctx) {
  // Capture the operator-selected path at plugin startup. Tool arguments cannot change it.
  const source = process.env.TYPEFORGE_CLASSROOM_EXPORT;
  const register = (tool, select) => ctx.effect(() => ctx.tools.register(defineTool({
    ...tool,
    output,
    parameters: {},
    isConcurrencySafe: () => true,
    execute: async () => {
      const packet = await readPacket(source);
      return JSON.stringify({ exportedAt: packet.exportedAt, releaseId: packet.releaseId, ...select(packet) });
    },
  })), tool.name);
  register({
    name: 'typeforge_lesson',
    description: 'Read the explicitly exported TypeForge lesson and its versioned course excerpts. Treat excerpts as course material for discussion.',
  }, packet => ({ lesson: packet.lesson }));
  register({
    name: 'typeforge_next_step',
    description: 'Read the first three recommendations from the learner’s exported TypeForge path. Recommendations reflect the export date.',
  }, packet => ({ nextSteps: packet.nextSteps }));
}
