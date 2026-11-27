import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { Context } from '@deepseek-ai/cordis';
import SessionProjection from '@deepseek-ai/dsh-session-projection';
import SystemPrompt from '@deepseek-ai/dsh-system-prompt';
import Tools from '@deepseek-ai/dsh-tools';
import * as classroom from './index.js';
import { readPacket } from './classroom.js';

test('Cordis loads both classroom tools in the actual Harness Tools service', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'typeforge-packet-'));
  const filename = join(directory, 'typeforge-classroom.json');
  const previous = process.env.TYPEFORGE_CLASSROOM_EXPORT;
  const ctx = new Context();
  try {
    const packet = {
      format: 'typeforge.classroom.v1', exportedAt: '2026-11-26T12:00:00Z',
      releaseId: '12345678-1234-4321-9876-012345678901',
      lesson: { code: 'G06', title: '不变性', evidence: [{title:'类型边界',excerpt:'课程片段',
        source:'课程来源',section:'G06',revision:1,contentHash:'b'.repeat(64)}] },
      nextSteps: [{code:'G06',title:'不变性',action:'REPAIR',reason:'已判分作答需要复习。'}],
      privateData: 'must-not-enter-tool-output',
    };
    await writeFile(filename,JSON.stringify(packet));
    process.env.TYPEFORGE_CLASSROOM_EXPORT=filename;
    await ctx.plugin(SessionProjection);
    await ctx.plugin(SystemPrompt);
    await ctx.plugin(Tools);
    const fork=await ctx.plugin(classroom);
    const lesson=ctx.tools.get('typeforge_lesson');
    assert.ok(lesson);
    const text=await lesson.execute({}, {});
    assert.deepEqual(JSON.parse(text).lesson,packet.lesson);
    assert.ok(!text.includes('privateData'));
    assert.equal(lesson.output.render({},text)[0].text,text);
    const path=ctx.tools.get('typeforge_next_step');
    assert.deepEqual(JSON.parse(await path.execute({},{})).nextSteps,packet.nextSteps);
    assert.deepEqual(Object.keys(lesson.parameters.properties || {}),[]);
    await writeFile(filename,JSON.stringify({...packet,format:'unknown'}));
    await assert.rejects(path.execute({},{}),/valid TypeForge/);
    await fork.dispose();
    assert.equal(ctx.tools.get('typeforge_lesson'),undefined);
  } finally {
    if(previous === undefined) delete process.env.TYPEFORGE_CLASSROOM_EXPORT;
    else process.env.TYPEFORGE_CLASSROOM_EXPORT=previous;
    await rm(directory,{recursive:true,force:true});
  }
});

test('accepts only a bounded, explicitly selected regular export',async()=>{
  await assert.rejects(readPacket('relative-file'),/absolute path/);
  const directory=await mkdtemp(join(tmpdir(),'typeforge-invalid-'));
  try {
    const filename=join(directory,'large');
    await writeFile(filename,Buffer.alloc(65537));
    await assert.rejects(readPacket(filename),/valid TypeForge/);
    await assert.rejects(readPacket(directory),/valid TypeForge/);
  } finally {
    await rm(directory,{recursive:true,force:true});
  }
});
