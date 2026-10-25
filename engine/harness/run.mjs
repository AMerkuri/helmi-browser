#!/usr/bin/env node
// Real-site crawl: loads each site on an emulated phone in three modes and compares.
//
//   baseline  plain browser; the detector only observes, to list what the user would see
//   rules     filter lists + heuristics, no model
//   serene    filter lists + heuristics + LLM (needs --model)
//
// Each site is then reloaded in the same context to check that the per-site cache handles the
// second visit without the model.
//
//   node harness/run.mjs --model models/Qwen3-0.6B-Q8_0.gguf [--sites harness/sites.json]
//        [--only bbc,spiegel] [--modes baseline,rules,serene] [--wait 10000] [--headed]
//        [--no-autoconsent] [--allow-consent-tools]
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { launch, newContext, residualOverlays, exposure } from './browser.mjs';
import { SereneEngine } from '../src/host/engine.mjs';
import { RulesEngine } from '../src/host/rules.mjs';
import { loadLists } from '../src/host/lists.mjs';
import { VerdictCache } from '../src/host/cache.mjs';
import { LlamaClassifier } from '../src/host/classifier.mjs';
import { AutoconsentRules } from '../src/host/autoconsent.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const { values: args } = parseArgs({
  options: {
    sites: { type: 'string', default: path.join(ROOT, 'harness/sites.json') },
    only: { type: 'string' },
    modes: { type: 'string', default: 'baseline,rules,serene' },
    model: { type: 'string' },
    wait: { type: 'string', default: '10000' },
    locale: { type: 'string', default: 'en-GB' },
    out: { type: 'string' },
    headed: { type: 'boolean', default: false },
    'no-autoconsent': { type: 'boolean', default: false },
    // Let consent tools' scripts load (the lists block most of them) so autoconsent can opt out.
    'allow-consent-tools': { type: 'boolean', default: false },
  },
});

let sites = JSON.parse(fs.readFileSync(args.sites, 'utf8'));
if (args.only) { const only = args.only.split(','); sites = sites.filter((s) => only.some((o) => s.name.includes(o))); }
let modes = args.modes.split(',');
if (modes.includes('serene') && !args.model) {
  console.warn('no --model given: skipping the serene mode (rules + heuristics only)');
  modes = modes.filter((m) => m !== 'serene');
}
const WAIT = Number(args.wait);
const outDir = args.out ?? path.join(ROOT, 'results', new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19));
fs.mkdirSync(path.join(outDir, 'shots'), { recursive: true });

const lists = await loadLists(path.join(ROOT, '.cache/lists'));
const rules = new RulesEngine(lists);
const autoconsent = args['no-autoconsent'] ? null : new AutoconsentRules();
const classifier = args.model ? new LlamaClassifier({ modelPath: args.model }) : null;
if (classifier) { const t0 = Date.now(); await classifier.classify(warmupFeatures()); console.log(`model loaded + warm-up in ${Date.now() - t0} ms`); }

const browser = await launch({ headless: !args.headed });
const results = [];
const candidatesOut = fs.createWriteStream(path.join(outDir, 'candidates.jsonl'));

for (const site of sites) {
  const row = { site: site.name, url: site.url, expect: site.expect, modes: {} };
  for (const mode of modes) {
    try {
      row.modes[mode] = await visit(site, mode);
    } catch (err) {
      row.modes[mode] = { error: err.message.split('\n')[0] };
    }
    const m = row.modes[mode];
    console.log(`${site.name.padEnd(22)} ${mode.padEnd(8)} ${m.error ? 'ERROR ' + m.error : summary(m)}`);
  }
  results.push(row);
}
await browser.close();
candidatesOut.end();
fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(results, null, 1));
fs.writeFileSync(path.join(outDir, 'report.md'), report(results));
console.log(`\nreport: ${path.join(outDir, 'report.md')}`);
process.exit(0);

async function visit(site, mode) {
  const events = [];
  const engine = mode === 'baseline'
    ? new SereneEngine({ cache: new VerdictCache(), contentConfig: { observeOnly: true } })
    : new SereneEngine({ rules, classifier: mode === 'serene' ? classifier : null, cache: new VerdictCache(), autoconsent });
  const context = await newContext(browser, { engine, onEvent: (e) => events.push({ ...e, at: Date.now() }), locale: args.locale, probe: true, allowConsentTools: args['allow-consent-tools'] && mode !== 'baseline' });
  const page = await context.newPage();
  const t0 = Date.now();
  try {
    await page.goto(site.url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(WAIT);
    const first = await snapshot(page, site, mode, 'first', events, t0);
    let second = null;
    if (mode !== 'baseline') {
      // Second visit, same profile: cookies from a clicked "reject" and the verdict cache both apply.
      const mark = events.length, t1 = Date.now();
      await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 });
      await page.waitForTimeout(Math.min(WAIT, 6000));
      second = await snapshot(page, site, mode, 'second', events.slice(mark), t1);
    }
    return { ...first, second };
  } finally {
    await context.close();
  }
}

async function snapshot(page, site, mode, visitName, events, t0) {
  const slug = `${site.name.replace(/[^a-z0-9.]+/gi, '_')}-${mode}-${visitName}`;
  await page.screenshot({ path: path.join(outDir, 'shots', slug + '.png') }).catch(() => {});
  const residual = (await residualOverlays(page).catch(() => null)) ?? { overlays: [], scrollLocked: null };
  const seen = (await exposure(page).catch(() => null)) ?? [];
  const cands = events.filter((e) => e.type === 'candidate');
  const byId = new Map(cands.map((c) => [c.id, c]));
  for (const c of cands) candidatesOut.write(JSON.stringify({ site: site.name, mode, visit: visitName, features: c.features, verdict: c.verdict, decidedMs: c.decidedMs, label: null }) + '\n');
  const llm = cands.filter((c) => c.verdict?.llm).map((c) => c.verdict.llm);
  const firstAction = events.find((e) => e.type === 'applied');
  return {
    shot: `shots/${slug}.png`,
    blockedRequests: events.filter((e) => e.type === 'blocked').length,
    cosmetic: events.find((e) => e.type === 'cosmetic' && e.url?.startsWith('http')) ?? null,
    genericSelectors: events.filter((e) => e.type === 'generic').reduce((n, e) => n + e.selectors.length, 0),
    candidates: cands.map((c) => ({
      sig: c.features.sig, edge: c.features.edge, coverage: c.features.coverage,
      text: c.features.text.slice(0, 90), heuristic: c.features.heuristic,
      verdict: c.verdict ? { label: c.verdict.label, action: c.verdict.action, source: c.verdict.source, buttonLabel: c.verdict.buttonLabel } : null,
      decidedMs: c.decidedMs,
    })),
    // Autoconsent: which consent tool it found and whether its opt-out (a real "reject") worked.
    consent: events.filter((e) => e.type === 'autoconsent').map((e) => ({ event: e.event, cmp: e.cmp, result: e.result, isCosmetic: e.isCosmetic, isTop: e.isTop, origin: e.origin })),
    consentRejected: [...new Set(events.filter((e) => e.type === 'autoconsent' && e.event === 'optOutResult' && e.result).map((e) => e.cmp))]
      .filter((cmp) => !events.some((e) => e.type === 'autoconsent' && e.event === 'autoconsentDone' && e.cmp === cmp && e.isCosmetic)),
    actions: events.filter((e) => e.type === 'applied').map((e) => ({ action: e.action, ok: e.ok, fallback: e.fallback, source: e.source, sig: e.sig })),
    msToFirstAction: firstAction ? firstAction.at - t0 : null,
    // Exposure: how long each overlay the engine acted on was visible before it went (probe,
    // 100 ms resolution). List hides and cached hides never show, so they are not here.
    exposure: seen.map((o) => {
      const v = o.candidateId != null ? byId.get(o.candidateId)?.verdict : null;
      return { ...o, action: v?.action ?? null, source: v?.source ?? null };
    }),
    actedExposureMs: seen.filter((o) => { const v = o.candidateId != null ? byId.get(o.candidateId)?.verdict : null; return v && v.action !== 'ignore'; }).map((o) => o.visibleMs),
    llmCalls: llm.length,
    llmMs: llm.map((r) => r.ms),
    llmPromptTokens: llm.map((r) => r.promptTokens),
    // What is still on screen: annoyance-looking overlays by the detector's own heuristics.
    residual: residual.overlays.map((o) => ({ sig: o.sig, edge: o.edge, coverage: o.coverage, label: o.heuristic.label, score: o.heuristic.score, text: o.text.slice(0, 90) })),
    residualAnnoyances: residual.overlays.filter((o) => o.heuristic.label !== 'content' && o.heuristic.score >= 0.5).length,
    // Anything large still covering the page, whatever the heuristics think it is.
    residualBlocking: residual.overlays.filter((o) => o.coverage >= 0.3 && !o.looksLikeLayout && !o.isBackdrop).length,
    scrollLocked: residual.scrollLocked,
    engineStarted: residual.started ?? null, // false: the content script never got its init reply
    detectorScans: residual.scanMs?.length ?? 0,
    detectorScanMsMax: residual.scanMs?.length ? Math.max(...residual.scanMs) : null,
    detectorScanMsTotal: residual.scanMs?.length ? Math.round(residual.scanMs.reduce((a, b) => a + b, 0)) : null,
  };
}

function summary(m) {
  const acted = m.candidates.filter((c) => c.verdict && c.verdict.source !== 'cache' && c.verdict.action !== 'ignore');
  const s = `${m.engineStarted === false ? 'NOT STARTED ' : ''}cand=${m.candidates.length} acted=${acted.map((c) => `${c.verdict.label}:${c.verdict.action}/${c.verdict.source}`).join(',') || '-'} ` +
    `residual=${m.residualAnnoyances}/${m.residualBlocking} locked=${m.scrollLocked} rejected=${m.consentRejected.join(',') || '-'} llm=${m.llmCalls}${m.llmMs.length ? ` (${m.llmMs.join('/')} ms)` : ''}`;
  const exp = m.actedExposureMs?.length ? ` exposed=${m.actedExposureMs.join('/')}ms` : '';
  return m.second ? `${s}${exp} | 2nd: residual=${m.second.residualAnnoyances} llm=${m.second.llmCalls}` : s + exp;
}

function report(rows) {
  const L = [];
  L.push(`# Serene engine crawl, ${new Date().toISOString().slice(0, 16)}Z`, '');
  L.push(`Viewport: Pixel 7 emulation, locale ${args.locale}, wait ${WAIT} ms. Modes: ${modes.join(', ')}. Model: ${args.model ? path.basename(args.model) : 'none'}. Autoconsent: ${autoconsent ? 'on' : 'off'}. Consent tools' scripts: ${args['allow-consent-tools'] ? 'allowed' : 'blocked by the lists'}.`, '');
  L.push('After each run: "annoyances" counts overlays still on screen that the detector\'s heuristics read as annoyances (score ≥ 0.5); "blocking" counts any overlay still covering ≥ 30% of the screen; "locked" is page scrolling. Check the screenshots: heuristics are not ground truth.', '');
  L.push('| Site | Baseline overlays | Rules: annoyances / blocking / locked | Serene: annoyances / blocking / locked | Consent rejected (autoconsent) | Model calls (ms) | 2nd visit residual / model calls |');
  L.push('|---|---|---|---|---|---|---|');
  for (const r of rows) {
    const b = r.modes.baseline, ru = r.modes.rules, se = r.modes.serene;
    const cell = (m) => (!m ? '–' : m.error ? `error` : `${m.residualAnnoyances} / ${m.residualBlocking} / ${m.scrollLocked ? 'yes' : 'no'}`);
    const base = !b ? '–' : b.error ? 'error' : b.residual.filter((o) => o.label !== 'content' && o.score >= 0.5).map((o) => o.label).join(', ') || 'none';
    const llm = se && !se.error ? `${se.llmCalls}${se.llmMs.length ? ` (${se.llmMs.join(', ')})` : ''}` : '–';
    const last = se ?? ru;
    const second = last && !last.error && last.second ? `${last.second.residualAnnoyances} / ${last.second.llmCalls}` : '–';
    const rejected = last && !last.error ? last.consentRejected.join(', ') || '–' : '–';
    L.push(`| ${r.site} | ${base} | ${cell(ru)} | ${cell(se)} | ${rejected} | ${llm} | ${second} |`);
  }
  L.push('', '## Exposure', '');
  L.push('How long each overlay the engine acted on was visible on the first visit before it went, from the in-page probe (sampled every 100 ms). "Appeared" is ms after navigation start. List-hidden and cached overlays never show and are not listed. Baseline shows when the annoyances first appeared in a plain browser.', '');
  const exposures = {};
  L.push('| Site | Baseline: annoyance appeared (ms) | ' + modes.filter((m) => m !== 'baseline').map((m) => `${m}: appeared → visible for (ms)`).join(' | ') + ' |');
  L.push('|---|---|' + modes.filter((m) => m !== 'baseline').map(() => '---|').join(''));
  for (const r of rows) {
    const b = r.modes.baseline;
    const base = !b || b.error ? '–' : (b.exposure ?? []).filter((o) => o.heuristic && o.heuristic.label !== 'content' && o.heuristic.score >= 0.5).map((o) => `${o.heuristic.label} ${o.firstMs}`).join(', ') || '–';
    const cells = modes.filter((m) => m !== 'baseline').map((mode) => {
      const m = r.modes[mode];
      if (!m || m.error) return '–';
      const acted = (m.exposure ?? []).filter((o) => o.action && o.action !== 'ignore');
      (exposures[mode] ??= []).push(...acted.map((o) => o.visibleMs));
      return acted.map((o) => `${o.firstMs} → ${o.visibleMs} (${o.source})`).join(', ') || '–';
    });
    if (base === '–' && cells.every((c) => c === '–')) continue;
    L.push(`| ${r.site} | ${base} | ${cells.join(' | ')} |`);
  }
  const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : '–'; };
  L.push('');
  for (const [mode, a] of Object.entries(exposures)) L.push(`- ${mode}: ${a.length} overlays acted on, visible for median ${pct(a, 0.5)} ms, p90 ${pct(a, 0.9)} ms, max ${a.length ? Math.max(...a) : '–'} ms.`);
  L.push('', '## Decisions per site', '');
  for (const r of rows) {
    L.push(`### ${r.site}`, '');
    for (const [mode, m] of Object.entries(r.modes)) {
      if (m.error) { L.push(`- **${mode}**: error: ${m.error}`); continue; }
      L.push(`- **${mode}**: ${m.blockedRequests} requests blocked, ${m.cosmetic?.hideSelectors ?? 0} site hide rules + ${m.genericSelectors} generic, ${m.candidates.length} candidates, ${m.detectorScans} detector scans (max ${m.detectorScanMsMax} ms, total ${m.detectorScanMsTotal} ms), first action at ${m.msToFirstAction ?? '–'} ms, screenshot \`${m.shot}\``);
      for (const c of m.candidates) {
        const v = c.verdict?.source === 'cache' ? 'cached site rule, applied in the page' : c.verdict ? `${c.verdict.label} → ${c.verdict.action}${c.verdict.buttonLabel ? ` "${c.verdict.buttonLabel}"` : ''} (${c.verdict.source}${c.decidedMs != null ? `, ${c.decidedMs} ms` : ''})` : 'observed';
        L.push(`  - \`${c.sig}\` ${c.edge} ${Math.round(c.coverage * 100)}%, h=${c.heuristic.label}:${c.heuristic.score}: ${v} · "${c.text}"`);
      }
      for (const e of m.consent.filter((e) => e.event !== 'popupFound')) L.push(`  - autoconsent ${e.isTop ? 'top' : `frame ${e.origin}`}: ${e.event} ${e.cmp ?? ''}${e.result != null ? ` → ${e.result}` : ''}${e.isCosmetic ? ' (cosmetic only)' : ''}`);
      for (const o of m.residual.filter((o) => o.label !== 'content')) L.push(`  - still visible: \`${o.sig}\` ${o.label}:${o.score} "${o.text}"`);
    }
    L.push('');
  }
  return L.join('\n');
}

function warmupFeatures() {
  return { sig: 'div#x', tag: 'div', role: '', ariaModal: false, ariaLabel: '', tokens: '', text: 'We use cookies.', textLen: 15, buttons: ['Accept', 'Reject'], hasEmail: false, hasPassword: false, hasVideo: false, iframeOrigins: [], coverage: 0.2, edge: 'bottom', zIndex: 10, scrollLocked: false };
}
