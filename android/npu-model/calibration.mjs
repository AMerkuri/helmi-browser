// Writes calibration.jsonl for build.py: labelled overlay records that the on-device benchmark
// does not measure, as the prompts LiteRT-LM sends (Qwen3's chat template, thinking off).
//   node calibration.mjs <all records .jsonl> <benchmark records .jsonl> > calibration.jsonl
// The committed file holds the 102 records PR #16 added to labeled-features.jsonl, so the 57
// the benchmark ran until then stay out of the calibration.
import fs from 'node:fs';
import { SYSTEM_PROMPT, buildPrompt } from '../../engine/src/host/classifier.mjs';

const [all, measured] = process.argv.slice(2);
const read = (p) => fs.readFileSync(p, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
const skip = new Set(read(measured).map((r) => r.id));
const text = (f) => `<|im_start|>system\n${SYSTEM_PROMPT}<|im_end|>\n<|im_start|>user\n${buildPrompt(f)}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`;
for (const r of read(all)) if (!skip.has(r.id)) console.log(JSON.stringify({ text: text(r.features) }));
