// Reader mode and summaries: dist/reader.js on hand-written pages in Chromium (as the app runs
// it, on the loaded page), and the summary prompts, chunking and reply parsing without a model
// (test/summary-eval.mjs runs those on the real one).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { launch } from '../harness/browser.mjs';
import { chunk, clean, estimateTokens, partPrompt, mergePrompt, points, summarize, CHUNK_TOKENS, MAX_CHUNKS } from '../src/host/summary.mjs';

const DIR = new URL('./reader/', import.meta.url).pathname;
const READER = fs.readFileSync(new URL('../dist/reader.js', import.meta.url), 'utf8');

let server, base, browser;

before(async () => {
  server = http.createServer((req, res) => {
    const file = path.join(DIR, path.basename(new URL(req.url, 'http://x').pathname));
    if (!file.endsWith('.html') || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    fs.createReadStream(file).pipe(res);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}/`;
  browser = await launch();
});

after(async () => { await browser?.close(); server?.close(); });

async function extract(file, prepare) {
  const page = await browser.newPage({ viewport: { width: 412, height: 915 } });
  try {
    await page.goto(base + file);
    if (prepare) await page.evaluate(prepare);
    const before = await page.content();
    const r = await page.evaluate(`${READER}\n;__sereneReader()`);
    // The live page is left as it was.
    assert.equal(await page.content(), before);
    return r;
  } finally {
    await page.close();
  }
}

test('reader: the story without the page around it', async () => {
  const r = await extract('news-article.html');
  assert.equal(r.article, true);
  assert.equal(r.title, 'Harbour ferry returns after storm repairs');
  assert.equal(r.siteName, 'The Coastal Gazette');
  assert.equal(r.lang, 'en');
  assert.match(r.text, /resumed its timetable on Monday/);
  assert.match(r.text, /## Timetable changes/);
  // A collapsed section that find-in-page could open is part of the story.
  assert.match(r.text, /electric vessel/);
  for (const noise of ['We use cookies', 'Accept all', 'Related', 'water taxi was always late', 'Privacy', 'Sport', 'Photo: Gazette archive']) {
    assert.ok(!r.text.includes(noise), `text has "${noise}"`);
  }
  assert.ok(r.words > 200 && r.words < 320, `words ${r.words}`);
});

test('reader: the HTML it hands over cannot run anything', async () => {
  const { html } = await extract('news-article.html');
  assert.match(html, /<img[^>]+ferry\.jpg/);
  assert.match(html, /<h2[^>]*>Timetable changes<\/h2>/);
  for (const bad of [/onerror/i, /javascript:/i, /<iframe/i, /<input/i, /<button/i, /<script/i, /\sstyle=/i]) assert.doesNotMatch(html, bad);
});

test('reader: overlays Serene hid stay out', async () => {
  // As serene.js hides an overlay: display:none plus its data-serene mark (here on the newsletter box).
  const r = await extract('news-article.html', () => {
    const el = document.querySelector('.newsletter-signup');
    el.style.setProperty('display', 'none', 'important');
    el.setAttribute('data-serene', 'newsletter');
  });
  assert.ok(!r.html.includes('Morning Tide'));
});

test('reader: a page that is not an article still gives its text', async () => {
  const r = await extract('shop-listing.html');
  assert.equal(r.article, false);
  assert.match(r.text, /Rain jackets/);
  assert.match(r.text, /Skerry shell/);
});

test('summary: chunks stay under the budget and keep every word, headings with their paragraph', () => {
  const para = (n, w) => Array.from({ length: n }, (_, i) => `${w} sentence number ${i} is here.`).join(' ');
  const text = ['## One', para(150, 'alpha'), '## Two', para(150, 'beta'), '## Three', para(10, 'gamma'), '中文句子。'.repeat(3000)].join('\n\n');
  const parts = chunk(text);
  assert.ok(parts.length > 3);
  for (const p of parts) assert.ok(estimateTokens(p) <= CHUNK_TOKENS, `${estimateTokens(p)} tokens`);
  assert.equal(parts.join('').replace(/\s/g, ''), text.replace(/\s/g, ''));
  for (const p of parts) assert.ok(!/## \w+$/.test(p), 'a part ends with a heading');
  // Chinese text is cut at sentence ends and joined back without spaces.
  assert.ok(parts.some((p) => p.startsWith('中文句子。中文句子。')));
});

test('summary: datelines and credits are dropped, headings and Chinese paragraphs kept', () => {
  const t = ['## Venezuela', '4 hours ago', 'Reuters', 'Machado returned home on Sunday after ten months in exile.', '她周日回到了委内瑞拉，结束了十个月的流亡。'].join('\n\n');
  assert.deepEqual(clean(t).split('\n\n'), ['## Venezuela', 'Machado returned home on Sunday after ten months in exile.', '她周日回到了委内瑞拉，结束了十个月的流亡。']);
});

test('summary: prompts', () => {
  const one = partPrompt({ title: 'Ferry returns', text: 'Body.' });
  assert.match(one, /^This is a web page\.\nTitle: Ferry returns\n\nBody\.\n\nWrite the 3 to 6 most important points/);
  assert.ok(one.endsWith('\n/no_think'));
  assert.match(partPrompt({ title: '', text: 'Body.', part: 2, parts: 3 }), /^This is part 2 of 3 of a web page\.\n\nBody\.\n\nWrite the 2 to 4/);
  assert.match(mergePrompt({ title: 'T', points: ['a.', 'b.'] }), /\n- a\.\n- b\.\n\nMerge them into the 5 most important points/);
});

test('summary: points from what Qwen3 writes', () => {
  assert.deepEqual(points('<think>\n\n</think>\n\nHere are the points:\n- One fact.\n* Two fact.\n1. **Three**: fact.\n- One fact.\n'), ['One fact.', 'Two fact.', 'Three: fact.']);
  // Merges sometimes come back quoted and double-marked.
  assert.deepEqual(points('- "- Eva helped a client."\n- - "The polygon() function."\n- "I am here," she wrote.'), ['Eva helped a client.', 'The polygon() function.', '"I am here," she wrote.']);
  // The reply ran out of tokens in the middle of its last point.
  assert.deepEqual(points('- First.\n- Second.\n- Riigieelarve eelnõus jättis valits'), ['First.', 'Second.']);
  // A model that writes no full stops keeps its last point.
  assert.deepEqual(points('- First\n- Second\n- Third'), ['First', 'Second', 'Third']);
  // Code the model copied out of a tutorial is not a point.
  assert.deepEqual(points('- Use clip-path for shapes.\n- 50% 0 /* (1) */\n- 79% 90%, 2% 35%'), ['Use clip-path for shapes.']);
  // No list at all: sentences.
  assert.deepEqual(points('The ferry is back. It cost 1.2 million euros.'), ['The ferry is back.', 'It cost 1.2 million euros.']);
});

test('summary: one call for a short page, parts and a merge for a long one', async () => {
  const prompts = [];
  const run = async (p) => { prompts.push(p); return '- A point.\n- Another point.\n- A third point.'; };
  const short = await summarize({ title: 'T', text: 'The ferry runs again from Monday morning.' }, run);
  assert.equal(prompts.length, 1);
  assert.equal(short.parts, 1);
  assert.deepEqual(short.points, ['A point.', 'Another point.', 'A third point.']);

  prompts.length = 0;
  const long = Array.from({ length: 12 }, (_, i) => `Paragraph ${i}: ` + 'words in a long essay. '.repeat(280)).join('\n\n');
  const updates = [];
  let n = 0;
  const r = await summarize({ title: 'T', text: long }, async (p) => { prompts.push(p); n++; return `- Point ${n}a.\n- Point ${n}b.`; }, (u) => updates.push(u.step));
  assert.equal(r.parts, MAX_CHUNKS);
  assert.ok(r.skippedParts > 0);
  assert.equal(prompts.length, MAX_CHUNKS + 1);
  assert.match(prompts.at(-1), /^These are notes on a web page/);
  assert.deepEqual(r.points, [`Point ${n}a.`, `Point ${n}b.`]);
  assert.equal(updates.at(-1), 'done');
  assert.equal(updates.filter((s) => s === 'merge').length, 1);
});
