// Reader mode: dist/reader.js on hand-written pages in Chromium, as the app runs it, on the
// loaded page.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { launch } from '../harness/browser.mjs';

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

// The article's text, as the reader view shows it.
const textOf = (html) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');

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
  const text = textOf(r.html);
  assert.match(text, /resumed its timetable on Monday/);
  assert.match(text, /Timetable changes/);
  // A collapsed section that find-in-page could open is part of the story.
  assert.match(text, /electric vessel/);
  for (const noise of ['We use cookies', 'Accept all', 'Related', 'water taxi was always late', 'Privacy', 'Sport']) {
    assert.ok(!text.includes(noise), `text has "${noise}"`);
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

test('reader: a page that is not an article says so', async () => {
  const r = await extract('shop-listing.html');
  assert.equal(r.article, false);
  assert.doesNotMatch(r.html, /<button/i);
});
