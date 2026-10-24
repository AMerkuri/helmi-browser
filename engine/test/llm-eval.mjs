#!/usr/bin/env node
// Measures the real model on labeled feature records (JSONL: {id, expected, features}).
//   node test/llm-eval.mjs --model models/Qwen3-0.6B-Q8_0.gguf [--data test/data/labeled-features.jsonl]
// Crawl output (results/*/candidates.jsonl) works too once its "label" fields are filled in.
import fs from 'node:fs';
import { parseArgs } from 'node:util';
import { LlamaClassifier, LABELS, buildPrompt } from '../src/host/classifier.mjs';
import { isSafeTarget } from '../src/host/policy.mjs';

const { values: args } = parseArgs({ options: {
  model: { type: 'string' },
  data: { type: 'string', default: new URL('./data/labeled-features.jsonl', import.meta.url).pathname },
  gpu: { type: 'string', default: 'auto' },
} });
if (!args.model) { console.error('usage: llm-eval.mjs --model <gguf> [--data file.jsonl]'); process.exit(2); }

const rows = fs.readFileSync(args.data, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
  .map((r) => ({ id: r.id ?? `${r.site}/${r.features?.sig}`, expected: r.expected ?? r.label, features: r.features }))
  .filter((r) => r.expected && LABELS.includes(r.expected));
const clf = new LlamaClassifier({ modelPath: args.model, gpu: args.gpu === 'false' ? false : args.gpu });

const t0 = Date.now();
await clf.classify(rows[0].features); // load + warm the system-prompt cache
console.log(`load + first call: ${Date.now() - t0} ms\n`);

let correct = 0, annoyVsContent = 0, agreeClicks = 0, invalid = 0;
const ms = [], toks = [], confusion = {};
for (const r of rows) {
  const out = await clf.classify(r.features);
  ms.push(out.ms); toks.push(out.promptTokens);
  if (out.invalid) invalid++;
  const ok = out.label === r.expected;
  if (ok) correct++;
  if ((out.label === 'content') === (r.expected === 'content')) annoyVsContent++;
  const btn = r.features.buttons[out.target];
  if (out.target >= 0 && !isSafeTarget(btn) && /accept|agree|allow|subscribe|sign|akzept|accetta|akcept|同意/i.test(btn || '')) agreeClicks++;
  (confusion[r.expected] ??= {})[out.label] = (confusion[r.expected][out.label] ?? 0) + 1;
  console.log(`${ok ? '✓' : '✗'} ${r.id.padEnd(34)} expected=${r.expected.padEnd(20)} got=${out.label.padEnd(20)} target=${out.target}${btn ? ` "${btn}"` : ''} ${out.ms} ms`);
}
const pct = (n) => `${((100 * n) / rows.length).toFixed(0)}%`;
const sorted = [...ms].sort((a, b) => a - b);
console.log(`\nrecords: ${rows.length}`);
console.log(`label accuracy: ${pct(correct)}  annoyance-vs-content accuracy: ${pct(annoyVsContent)}  invalid replies: ${invalid}`);
console.log(`model chose an "agree" button: ${agreeClicks} (the policy never clicks those; it hides instead)`);
console.log(`latency per call: median ${sorted[Math.floor(sorted.length / 2)]} ms, p95 ${sorted[Math.floor(sorted.length * 0.95)]} ms`);
console.log(`prompt tokens (system + features): median ${toks.sort((a, b) => a - b)[Math.floor(toks.length / 2)]}`);
console.log('\nconfusion (expected → got):');
for (const [e, got] of Object.entries(confusion)) console.log(`  ${e.padEnd(20)} ${Object.entries(got).map(([g, n]) => `${g}:${n}`).join(' ')}`);
console.log('\nexample prompt:\n' + buildPrompt(rows[0].features));
