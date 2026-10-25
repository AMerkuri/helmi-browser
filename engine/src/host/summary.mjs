// Page summaries with the on-device model (Qwen3-0.6B): prompts, chunking and reply cleanup.
// The app's port is android/.../reader/Summary.kt; test/summary-eval.mjs runs these prompts on
// the real model. The input is the reader extract's text (src/content/reader-entry.js):
// paragraphs separated by blank lines, headings as "## ...".
//
// A 0.6B model reads a long article badly in one go, so a page is cut into parts that fit its
// window comfortably, each part becomes a few bullet points, and a last call merges them when
// there are too many.

export const SYSTEM = 'You summarize web pages for someone reading on a phone. You only use facts that are in the text you are given. You answer with a short bulleted list and nothing else.';

/**
 * Page text per model call, in estimated tokens: one call for a typical news story. The app's
 * engine has a 4096-token window: this, the prompt around it (~100) and the reply fit with room
 * to spare for a low estimate.
 */
export const CHUNK_TOKENS = 2000;
/** Parts beyond this are left out (the start of a page carries most of it); ~10 calls at most. */
export const MAX_CHUNKS = 8;
/** A summary longer than this many bullets gets merged by one more call. */
export const MERGE_ABOVE = 7;
export const MAX_OUTPUT_TOKENS = 220;

/**
 * A high guess of Qwen3's token count: about 3 characters a token for text in Latin or Cyrillic
 * script (English is nearer 4, Estonian 3), one a token for Chinese, Japanese and Korean.
 */
export function estimateTokens(s) {
  const wide = (s.match(/[\u2E80-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFF00-\uFFEF]/g) || []).length;
  return Math.ceil((s.length - wide) / 3 + wide);
}

/**
 * Cuts text into parts of at most `max` estimated tokens, at paragraph ends where it can, then at
 * sentence ends. A heading stays with the paragraph after it.
 */
export function chunk(text, max = CHUNK_TOKENS) {
  const pieces = [];
  for (const p of text.split(/\n\s*\n/).map((x) => x.trim()).filter(Boolean)) {
    if (estimateTokens(p) <= max) { pieces.push(p); continue; }
    let cur = '';
    // Sentences keep their trailing space, so they join back as they were.
    for (const sentence of p.match(/[^.!?。！？]*[.!?。！？]+\s*|[^.!?。！？]+$/g)) {
      // A sentence too long by itself is cut anywhere.
      const step = Math.max(1, Math.floor(sentence.length * max / estimateTokens(sentence)));
      for (let i = 0; i < sentence.length; i += step) {
        const s = sentence.slice(i, i + step);
        if (cur && estimateTokens(cur) + estimateTokens(s) > max) { pieces.push(cur.trim()); cur = ''; }
        cur += s;
      }
    }
    if (cur.trim()) pieces.push(cur.trim());
  }
  const chunks = [];
  let cur = [];
  let tokens = 0;
  for (const p of pieces) {
    const t = estimateTokens(p);
    if (cur.length && tokens + 1 + t > max) {
      // A heading moves on with the paragraph it belongs to.
      const carry = cur[cur.length - 1].startsWith('## ') && cur.length > 1 ? [cur.pop()] : [];
      chunks.push(cur.join('\n\n'));
      cur = carry;
      tokens = carry.length ? estimateTokens(carry[0]) : 0;
    }
    tokens += (cur.length ? 1 : 0) + t;
    cur.push(p);
  }
  if (cur.length) chunks.push(cur.join('\n\n'));
  return chunks;
}

/**
 * The page text without the lines that only add noise for the model: datelines, photo credits,
 * share buttons ("4 hours ago", "Reuters"). Headings stay, and so does a short paragraph in a
 * script without spaces between words.
 */
export function clean(text) {
  return text.split(/\n\s*\n/).map((p) => p.trim())
    .filter((p) => p.startsWith('## ') || (p.match(/[\p{L}\p{N}]+/gu) || []).length >= 4 || estimateTokens(p) >= 12)
    .join('\n\n');
}

/** The user message for one part of the page (or the whole page, when part is 1 of 1). */
export function partPrompt({ title, text, part = 1, parts = 1 }) {
  const what = parts > 1 ? `This is part ${part} of ${parts} of a web page.` : 'This is a web page.';
  const bullets = parts > 1 ? '2 to 4' : '3 to 6';
  return [
    what,
    title ? `Title: ${title}` : null,
    '',
    text,
    '',
    `Write the ${bullets} most important points of this text as a list. Start each point with "- " and keep it to one short sentence. Use only what the text says. Write in the language of the text.`,
    '/no_think',
  ].filter((l) => l !== null).join('\n');
}

/** The user message that merges the parts' points into one short list. */
export function mergePrompt({ title, points }) {
  return [
    'These are notes on a web page, in the order of the page.',
    title ? `Title: ${title}` : null,
    '',
    points.map((p) => `- ${p}`).join('\n'),
    '',
    'Merge them into the 5 most important points. Start each point with "- " and keep it to one short sentence. Use only what the notes say. Write in the language of the notes.',
    '/no_think',
  ].filter((l) => l !== null).join('\n');
}

// Mostly letters: a line of code or a row of numbers is not a point.
const prose = (s) => (s.match(/\p{L}/gu) || []).length >= s.replace(/\s/g, '').length * 0.5;
const norm = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/**
 * The points in a model reply: list items without their markers, without a <think> block or a
 * preamble, and without repeats (a small model can loop on one sentence).
 */
export function points(reply) {
  const text = String(reply || '').replace(/<think>[\s\S]*?(<\/think>|$)/g, '');
  const out = [];
  const seen = new Set();
  for (const line of text.split('\n')) {
    const m = /^\s*(?:[-*•‣–]|\d{1,2}[.)])\s+(.*\S)\s*$/.exec(line);
    if (!m) continue;
    // Bold lead-ins lose their stars; a point wrapped in quotes or with a second marker loses those.
    let p = m[1].replace(/^\*\*(.+?)\*\*:?\s*/, '$1: ').replace(/\*\*/g, '').trim();
    p = p.replace(/^[-*•]\s+/, '').replace(/^["“]\s*(?:[-*•]\s+)?([^"“”]*?)\s*["”]?$/, '$1').trim();
    const key = norm(p);
    if (key.length < 3 || seen.has(key) || !prose(p)) continue;
    seen.add(key);
    out.push(p);
  }
  // A last point cut off by the reply's token limit: no full stop where the others have one.
  const ended = (p) => /[.!?。！？…"”')\]]$/.test(p);
  if (out.length > 1 && !ended(out[out.length - 1]) && out.slice(0, -1).filter(ended).length * 2 >= out.length - 1) out.pop();
  // A reply with no list at all: its sentences are the points.
  if (!out.length) {
    for (const s of text.split(/(?<=[.!?])\s+/).map((x) => x.trim()).filter((x) => x.length > 2)) {
      const key = norm(s);
      if (!seen.has(key)) { seen.add(key); out.push(s); }
    }
  }
  return out;
}

/**
 * The calls a page needs: one per part, plus a merge when the parts' points add up to more than
 * MERGE_ABOVE. `run(prompt)` asks the model and returns its reply. `onUpdate` gets the points so
 * far and what is running, for a view that fills in as it goes.
 */
export async function summarize({ title, text }, run, onUpdate = () => {}) {
  const all = chunk(clean(text));
  const parts = all.slice(0, MAX_CHUNKS);
  let pts = [];
  for (let i = 0; i < parts.length; i++) {
    onUpdate({ points: pts, step: 'part', part: i + 1, parts: parts.length });
    pts = pts.concat(points(await run(partPrompt({ title, text: parts[i], part: i + 1, parts: parts.length }))));
  }
  if (pts.length > MERGE_ABOVE) {
    onUpdate({ points: pts, step: 'merge', part: parts.length, parts: parts.length });
    const merged = points(await run(mergePrompt({ title, points: pts })));
    if (merged.length >= 2) pts = merged;
  }
  const result = { points: pts, parts: parts.length, skippedParts: all.length - parts.length };
  onUpdate({ ...result, step: 'done' });
  return result;
}
