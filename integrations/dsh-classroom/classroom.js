import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';

const MAX_BYTES = 64 * 1024;
const ACTIONS = new Set(['READ', 'PRACTICE', 'REPAIR', 'REVISIT']);

function string(value, limit) {
  if (typeof value !== 'string' || value.length > limit) throw new Error('Invalid classroom field');
  return value;
}

export function validatePacket(packet) {
  if (packet?.format !== 'typeforge.classroom.v1' || !Array.isArray(packet.lesson?.evidence) ||
      packet.lesson.evidence.length > 4 || !Array.isArray(packet.nextSteps) || packet.nextSteps.length > 3) {
    throw new Error('Export classroom materials from the TypeForge lesson before opening them in Harness.');
  }
  const code = string(packet.lesson.code, 16);
  const title = string(packet.lesson.title, 128);
  const exportedAt = string(packet.exportedAt, 40);
  if (!Number.isFinite(Date.parse(exportedAt))) throw new Error('Invalid classroom export date');
  const releaseId = string(packet.releaseId, 36);
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(releaseId)) throw new Error('Invalid course release');
  const evidence = packet.lesson.evidence.map(card => {
    if (!Number.isSafeInteger(card?.revision) || card.revision < 1 ||
        typeof card.contentHash !== 'string' || !/^[0-9a-f]{64}$/.test(card.contentHash)) throw new Error('Invalid citation');
    return { title: string(card.title, 512), excerpt: string(card.excerpt, 1500), source: string(card.source, 512),
      section: string(card.section, 512), revision: card.revision, contentHash: card.contentHash };
  });
  const nextSteps = packet.nextSteps.map(step => {
    if (!ACTIONS.has(step?.action)) throw new Error('Invalid lesson action');
    return {code: string(step.code, 16), title: string(step.title, 128), action: step.action, reason: string(step.reason, 512)};
  });
  // Select declared fields. A modified export cannot append unrelated private fields to agent output.
  return { exportedAt, releaseId, lesson: { code, title, evidence }, nextSteps };
}

export async function readPacket(path) {
  if (!path || !isAbsolute(path)) throw new Error('Set TYPEFORGE_CLASSROOM_EXPORT to the absolute path of your classroom export.');
  let file;
  try {
    file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
    const info = await file.stat();
    if (!info.isFile() || info.size > MAX_BYTES) throw new Error();
    const buffer = Buffer.alloc(MAX_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await file.read(buffer, length, buffer.length - length, length);
      if (bytesRead === 0) break;
      length += bytesRead;
    }
    if (length !== info.size || length > MAX_BYTES) throw new Error();
    return validatePacket(JSON.parse(buffer.toString('utf8', 0, length)));
  } catch {
    throw new Error('Cannot read a valid TypeForge classroom export. Export the current lesson again and select that file.');
  } finally {
    await file?.close();
  }
}
