// Records (.jsonl) -> the model's prompts, for finetune.py and hf-eval.py: the app's system prompt
// and per-overlay prompt, the label to answer and the decline button (target).
//   node train/prompts.mjs train/train.jsonl > /tmp/train-prompts.jsonl
import fs from 'node:fs';
import { SYSTEM_PROMPT, buildPrompt } from '../src/host/classifier.mjs';

for (const l of fs.readFileSync(process.argv[2], 'utf8').split('\n').filter(Boolean)) {
  const r = JSON.parse(l);
  console.log(JSON.stringify({ id: r.id, expected: r.expected, target: r.target ?? -1, system: SYSTEM_PROMPT, user: buildPrompt(r.features) }));
}
