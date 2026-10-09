import { test } from 'node:test';
import assert from 'node:assert/strict';
import { breathDreamContext } from '../src/engine.js';
import { buildContextEnvelope } from '../src/context-envelope.js';

/**
 * 梦正文进信封（2026-10-06，Yuki 接入）。
 *
 * ## 背景（用户报的问题）
 * 「他做梦我问他梦到的内容他不知道，说的和实际梦到的不一样」
 *
 * 根因：`breathDreamContext` 的 map 只留 `awareness`（"半梦半醒…"这类元描述），
 * 把梦的**正文**（`dream.dream`）丢掉了。而 `/v1/now` 却告诉模型"细的在 xinchao_context" ——
 * 模型被告知细节在上下文里，可上下文里根本没有梦的内容，于是只能编。
 */

const NOW = new Date('2026-10-06T12:00:00Z');

/** 一条"刚做的梦"：正文 + 醒来感受 + 意识层描述，全都有。 */
function dreamWithBody(overrides = {}) {
  return {
    id: 'dream-1',
    createdAt: '2026-10-06T03:00:00Z',
    dream: '书店的窗边座位空着，阳光斜斜铺在木桌上。收音机里有个人在读书。',
    residue: '醒来还记得那束光。',
    awareness: '半梦半醒，知道自己在做梦，但懒得睁眼。',
    ...overrides,
  };
}

test('breathDreamContext 带上梦的正文 —— 这是"她知道自己梦了什么"的唯一来源', () => {
  const state = { recentDreams: [dreamWithBody()] };
  const result = breathDreamContext(state, NOW, 18, 3);
  assert.equal(result.available, true);
  assert.equal(result.dreams.length, 1);
  assert.equal(
    result.dreams[0].dream,
    '书店的窗边座位空着，阳光斜斜铺在木桌上。收音机里有个人在读书。',
  );
});

test('breathDreamContext 同时保留 summary 与 residue —— 向后兼容，老消费方不破', () => {
  const state = { recentDreams: [dreamWithBody()] };
  const result = breathDreamContext(state, NOW, 18, 3);
  // summary 仍取 awareness（老语义不变）
  assert.equal(result.dreams[0].summary, '半梦半醒，知道自己在做梦，但懒得睁眼。');
  assert.equal(result.dreams[0].residue, '醒来还记得那束光。');
});

test('只有正文、没有 awareness/residue 的梦也要留下 —— filter 不能把它筛掉', () => {
  const state = {
    recentDreams: [dreamWithBody({ awareness: '', residue: '' })],
  };
  const result = breathDreamContext(state, NOW, 18, 3);
  assert.equal(result.available, true);
  assert.equal(result.dreams.length, 1);
  assert.ok(result.dreams[0].dream.includes('书店的窗边座位'));
});

test('正文超长会截断 —— 不能让它撑爆信封预算', () => {
  const long = '梦'.repeat(500);
  const state = { recentDreams: [dreamWithBody({ dream: long })] };
  const result = breathDreamContext(state, NOW, 18, 3);
  assert.ok(result.dreams[0].dream.length <= 280);
});

test('信封的 dream_residue 段优先放正文 —— 模型要的是"她梦见了什么"', () => {
  const state = {
    recentDreams: [dreamWithBody()],
    // 信封还需要的最小字段（缺了就走不了 dream 段，但这里只关心梦）
    drives: {},
    emotion: {},
  };
  const env = buildContextEnvelope({ state, now: NOW, sessionId: 's1', mode: 'inspect' });
  const section = (env.sections || []).find((s) => s.id === 'dream_residue');
  assert.ok(section, '信封里应该有 dream_residue 段');
  assert.ok(
    section.content.includes('书店的窗边座位空着'),
    `dream_residue 段应含梦的正文，实际内容：${section.content}`,
  );
});

test('没有正文时回落 summary/residue —— 老数据（只有 awareness）不能变成空段', () => {
  const state = {
    recentDreams: [dreamWithBody({ dream: '' })],
    drives: {},
    emotion: {},
  };
  const env = buildContextEnvelope({ state, now: NOW, sessionId: 's1', mode: 'inspect' });
  const section = (env.sections || []).find((s) => s.id === 'dream_residue');
  assert.ok(section);
  assert.ok(section.content.includes('半梦半醒'), `应回落 summary，实际：${section.content}`);
});