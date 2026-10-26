#!/usr/bin/env node
// How big are the requests the filter lists block? The app can't know (it never fetches them),
// so its "data saved" figure multiplies its blocked-request counts by a typical size per request
// type, and this measures those sizes: each site loads with nothing blocked, and every request
// the lists would have blocked is recorded with its type (guessed the way the app's requestType
// does) and its transfer size. What a blocked script would have loaded in turn isn't counted, so
// the real saving is larger.
//
//   node harness/blocked-sizes.mjs [--sites harness/sites.json] [--only bbc,spiegel] [--wait 8000]
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { launch, newContext } from './browser.mjs';
import { RulesEngine } from '../src/host/rules.mjs';
import { loadLists } from '../src/host/lists.mjs';
import { isConsentTool } from '../src/host/autoconsent.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const { values: args } = parseArgs({
  options: {
    sites: { type: 'string', default: path.join(ROOT, 'harness/sites.json') },
    only: { type: 'string' },
    wait: { type: 'string', default: '8000' },
  },
});
let sites = JSON.parse(fs.readFileSync(args.sites, 'utf8'));
if (args.only) { const only = args.only.split(','); sites = sites.filter((s) => only.some((o) => s.name.includes(o))); }

const rules = new RulesEngine(await loadLists(path.join(ROOT, '.cache/lists')));

// Same guess as requestType() in android/.../browser/Tab.kt: WebView doesn't say what a request is.
function appType(url, headers) {
  const accept = headers.accept ?? '';
  const p = new URL(url).pathname.toLowerCase();
  if (accept.includes('text/html')) return 'sub_frame';
  if (accept.includes('text/css') || p.endsWith('.css')) return 'stylesheet';
  if (accept.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|avif|ico)$/.test(p)) return 'image';
  if (p.endsWith('.js') || p.endsWith('.mjs')) return 'script';
  if (/\.(woff2?|ttf|otf)$/.test(p)) return 'font';
  if (/\.(mp4|webm|m3u8|mp3|m4a)$/.test(p)) return 'media';
  if (accept.includes('application/json')) return 'xmlhttprequest';
  if (!accept || accept === '*/*') return 'script';
  return 'other';
}

const browser = await launch();
const rows = []; // { site, type, url, bytes }
for (const site of sites) {
  const context = await newContext(browser);
  const page = await context.newPage();
  const pending = [];
  page.on('requestfinished', (req) => {
    const top = page.mainFrame().url();
    if (req.isNavigationRequest() && req.frame() === page.mainFrame()) return;
    const url = req.url();
    if (!/^https?:/.test(url) || isConsentTool(url)) return;
    const type = appType(url, req.headers());
    if (!rules.shouldBlock(url, top, type)) return;
    pending.push(req.sizes().then((s) => rows.push({ site: site.name, type, url: url.slice(0, 160), bytes: s.responseBodySize + s.responseHeadersSize })).catch(() => {}));
  });
  try {
    await page.goto(site.url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(Number(args.wait));
  } catch (err) {
    console.log(`${site.name}: ${err.message.split('\n')[0]}`);
  }
  await Promise.all(pending);
  const mine = rows.filter((r) => r.site === site.name);
  console.log(`${site.name.padEnd(22)} ${mine.length} would be blocked, ${Math.round(mine.reduce((a, r) => a + r.bytes, 0) / 1024)} KB`);
  await context.close();
}
await browser.close();

const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : 0; };
const byType = {};
for (const r of rows) (byType[r.type] ??= []).push(r.bytes);
console.log('\ntype            count   median KB   mean KB');
for (const [t, xs] of Object.entries(byType).sort((a, b) => b[1].length - a[1].length)) {
  console.log(`${t.padEnd(15)} ${String(xs.length).padStart(5)} ${(median(xs) / 1024).toFixed(1).padStart(11)} ${(xs.reduce((a, b) => a + b, 0) / xs.length / 1024).toFixed(1).padStart(9)}`);
}
const total = rows.reduce((a, r) => a + r.bytes, 0);
console.log(`\nall: ${rows.length} requests, mean ${(total / Math.max(rows.length, 1) / 1024).toFixed(1)} KB, ${sites.length} sites`);
fs.mkdirSync(path.join(ROOT, 'results'), { recursive: true });
fs.writeFileSync(path.join(ROOT, 'results', 'blocked-sizes.json'), JSON.stringify(rows, null, 1));
