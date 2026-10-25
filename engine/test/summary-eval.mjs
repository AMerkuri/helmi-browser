#!/usr/bin/env node
// Runs the summary prompts (src/host/summary.mjs) on the real model, for reading the results.
//   node test/summary-eval.mjs --model models/Qwen3-0.6B-Q8_0.gguf --pages pages.json [--temperature 0]
// pages.json is an array of reader extracts ({title, text, url}): what dist/reader.js's
// __sereneReader() returns on a page. Real articles are not committed (fixtures are imitations),
// so collect them yourself, for example with Playwright: page.evaluate(readerJs + ';__sereneReader()').
import fs from 'node:fs';
import { parseArgs } from 'node:util';
import { SYSTEM, MAX_OUTPUT_TOKENS, summarize } from '../src/host/summary.mjs';

const { values: args } = parseArgs({ options: {
  model: { type: 'string' },
  pages: { type: 'string' },
  gpu: { type: 'string', default: 'auto' },
  temperature: { type: 'string', default: '0' },
  context: { type: 'string', default: '4096' },
} });
if (!args.model || !args.pages) { console.error('usage: summary-eval.mjs --model <gguf> --pages pages.json'); process.exit(2); }

const { getLlama, LlamaChatSession, QwenChatWrapper } = await import('node-llama-cpp');
const llama = await getLlama({ gpu: args.gpu === 'false' ? false : args.gpu });
const model = await llama.loadModel({ modelPath: args.model });
const context = await model.createContext({ contextSize: Number(args.context) });
const sequence = context.getSequence();
const chatWrapper = /qwen3/i.test(args.model) ? new QwenChatWrapper({ thoughts: 'discourage' }) : undefined;
const temperature = Number(args.temperature);

let calls = 0, promptTokens = 0, outTokens = 0;
async function run(prompt) {
  // A fresh conversation per call, as in the app.
  await sequence.clearHistory();
  const session = new LlamaChatSession({ contextSequence: sequence, systemPrompt: SYSTEM, ...(chatWrapper ? { chatWrapper } : {}) });
  const reply = await session.prompt(prompt, { maxTokens: MAX_OUTPUT_TOKENS, temperature, ...(temperature ? { topK: 20, topP: 0.8 } : {}) });
  calls++;
  promptTokens += model.tokenize(SYSTEM + prompt).length;
  outTokens += model.tokenize(reply).length;
  session.dispose({ disposeSequence: false });
  return reply;
}

for (const page of JSON.parse(fs.readFileSync(args.pages, 'utf8'))) {
  calls = promptTokens = outTokens = 0;
  const t0 = performance.now();
  const r = await summarize(page, run);
  const s = ((performance.now() - t0) / 1000).toFixed(1);
  console.log(`\n# ${page.title} (${page.words ?? '?'} words, ${r.parts} part(s)${r.skippedParts ? `, ${r.skippedParts} left out` : ''}; ${calls} calls, ${promptTokens} prompt + ${outTokens} reply tokens, ${s} s)`);
  console.log(page.url ?? '');
  for (const p of r.points) console.log(`- ${p}`);
}
