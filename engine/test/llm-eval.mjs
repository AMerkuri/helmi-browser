#!/usr/bin/env node
// Measures the real model on labeled feature records (JSONL: {id, expected, features}).
//   node test/llm-eval.mjs --model models/Qwen3-0.6B-Q8_0.gguf [--data test/data/labeled-features.jsonl] [--mode score|generate]
// Crawl output (results/*/candidates.jsonl) works too once its "label" fields are filled in.
import fs from 'node:fs';
import { parseArgs } from 'node:util';
import { LlamaClassifier, LABELS, buildPrompt } from '../src/host/classifier.mjs';
import { looksLikeChrome, fromModel } from '../src/host/policy.mjs';

const { values: args } = parseArgs({ options: {
  model: { type: 'string' },
  data: { type: 'string', default: new URL('./data/labeled-features.jsonl', import.meta.url).pathname },
  gpu: { type: 'string', default: 'auto' },
  mode: { type: 'string', default: 'score' },
} });
if (!args.model) { console.error('usage: llm-eval.mjs --model <gguf> [--data file.jsonl]'); process.exit(2); }

const rows = fs.readFileSync(args.data, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
  .map((r) => ({ id: r.id ?? `${r.site}/${r.features?.sig}`, expected: r.expected ?? r.label, features: r.features }))
  .filter((r) => r.expected && LABELS.includes(r.expected));
const clf = new LlamaClassifier({ modelPath: args.model, gpu: args.gpu === 'false' ? false : args.gpu, mode: args.mode });

const t0 = Date.now();
await clf.classify(rows[0].features); // load + warm the system-prompt cache
console.log(`load + first call: ${Date.now() - t0} ms\n`);

let correct = 0, guarded = 0, engineOk = 0, annoyVsContent = 0, invalid = 0;
const ms = [], toks = [], confusion = {}, scored = [];
for (const r of rows) {
  const out = await clf.classify(r.features);
  ms.push(out.ms); toks.push(out.promptTokens);
  if (out.invalid) invalid++;
  const ok = out.label === r.expected;
  if (ok) correct++;
  // What the engine does: site headers and nav bars never reach the model (policy.looksLikeChrome).
  if ((looksLikeChrome(r.features) ? 'content' : out.label) === r.expected) guarded++;
  // ...and an unsure annoyance label is treated as content (policy.fromModel).
  if ((looksLikeChrome(r.features) ? 'content' : fromModel(out, r.features).label) === r.expected) engineOk++;
  if ((out.label === 'content') === (r.expected === 'content')) annoyVsContent++;
  if (out.confidence != null) scored.push({ ok, guardedOk: (looksLikeChrome(r.features) ? 'content' : out.label) === r.expected, p: out.confidence, offLabel: out.offLabel });
  (confusion[r.expected] ??= {})[out.label] = (confusion[r.expected][out.label] ?? 0) + 1;
  const conf = out.confidence != null ? ` p=${out.confidence.toFixed(2)}` : '';
  console.log(`${ok ? '✓' : '✗'} ${r.id.padEnd(34)} expected=${r.expected.padEnd(20)} got=${out.label.padEnd(20)}${conf} ${out.ms} ms`);
}
const pct = (n) => `${((100 * n) / rows.length).toFixed(0)}%`;
const sorted = [...ms].sort((a, b) => a - b);
console.log(`\nrecords: ${rows.length}`);
console.log(`label accuracy: ${pct(correct)}  with the header guard: ${pct(guarded)}  with the guard and the confidence floor: ${pct(engineOk)}  annoyance-vs-content accuracy: ${pct(annoyVsContent)}  invalid replies: ${invalid}`);
console.log(`mode: ${args.mode}`);
console.log(`latency per call: median ${sorted[Math.floor(sorted.length / 2)]} ms, p95 ${sorted[Math.floor(sorted.length * 0.95)]} ms`);
console.log(`prompt tokens (system + features): median ${toks.sort((a, b) => a - b)[Math.floor(toks.length / 2)]}`);
if (scored.length) {
  // Calibration: is the model right as often as its confidence says?
  console.log('\nconfidence (single-pass scores): bucket → records, accuracy');
  for (const [lo, hi] of [[0, 0.5], [0.5, 0.7], [0.7, 0.9], [0.9, 1.01]]) {
    const b = scored.filter((x) => x.p >= lo && x.p < hi);
    if (b.length) console.log(`  ${lo.toFixed(1)}–${Math.min(hi, 1).toFixed(1)}  ${String(b.length).padStart(3)}  ${((100 * b.filter((x) => x.ok).length) / b.length).toFixed(0)}%`);
  }
  const off = scored.map((x) => x.offLabel ?? 0).sort((a, b) => a - b);
  console.log(`  probability on non-label tokens: median ${off[Math.floor(off.length / 2)].toFixed(3)}, max ${off[off.length - 1].toFixed(3)}`);
}
console.log('\nconfusion (expected → got):');
for (const [e, got] of Object.entries(confusion)) console.log(`  ${e.padEnd(20)} ${Object.entries(got).map(([g, n]) => `${g}:${n}`).join(' ')}`);
console.log('\nexample prompt:\n' + buildPrompt(rows[0].features));
