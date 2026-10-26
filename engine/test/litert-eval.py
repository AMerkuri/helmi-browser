#!/usr/bin/env python3
"""Scores a .litertlm model on the labelled overlays the way the app runs it.

  python3 -m venv ~/venvs/lrt && ~/venvs/lrt/bin/pip install litert-lm==0.18.1
  ~/venvs/lrt/bin/python test/litert-eval.py --model Qwen3-0.6B.litertlm [--data test/data/labeled-features.jsonl]

Same prompt as the app (engine/src/host/classifier.mjs, Prompt.kt), a new conversation per call,
thinking off, greedy, free reply streamed and cut off once the label string closes
(LiteRtClassifier.Reply.FREE). A label outside the nine, or no label, counts as content.
"Guarded" applies policy.looksLikeChrome first; "content hidden" counts content records that
came out as an annoyance after the guard.
"""
import argparse
import json
import pathlib
import re
import statistics
import subprocess
import sys
import time

import litert_lm

HERE = pathlib.Path(__file__).resolve().parent
CLOSED_LABEL = re.compile(r'"label"\s*:\s*"([^"]*)"')

CHATML = (
    "{%- for m in messages -%}"
    "{%- set c = m.content if m.content is string else (m.content | map(attribute='text') | join('')) -%}"
    "<|im_start|>{{ m.role }}\n{{ c }}<|im_end|>\n"
    "{%- endfor -%}"
    "{%- if add_generation_prompt -%}<|im_start|>assistant\n<think>\n\n</think>\n\n{%- endif -%}"
)

EXPORT = """
import fs from 'node:fs';
import { SYSTEM_PROMPT, buildPrompt, LABELS } from '../src/host/classifier.mjs';
import { looksLikeChrome } from '../src/host/policy.mjs';
const rows = fs.readFileSync(process.argv[1], 'utf8').split('\\n').filter(Boolean).map((l) => JSON.parse(l))
  .map((r) => ({ id: r.id ?? `${r.site}/${r.features?.sig}`, expected: r.expected ?? r.label, features: r.features }))
  .filter((r) => r.expected && LABELS.includes(r.expected))
  .map((r) => ({ id: r.id, expected: r.expected, chrome: looksLikeChrome(r.features), user: buildPrompt(r.features) }));
console.log(JSON.stringify({ system: SYSTEM_PROMPT, labels: LABELS, rows }));
"""


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument('--model', required=True)
  ap.add_argument('--data', default=str(HERE / 'data' / 'labeled-features.jsonl'))
  ap.add_argument('--backend', default='cpu', choices=['cpu', 'gpu'])
  ap.add_argument('--no-think-suffix', action='store_true', help='drop the trailing /no_think (a Qwen3 switch) from the prompt')
  ap.add_argument('--fresh-engine', action='store_true',
                  help='load the model again for each record (Qwen3.5: a new conversation keeps the last one\'s recurrent state, LiteRT-LM issue 3165)')
  ap.add_argument('--chatml', action='store_true',
                  help='use a plain Qwen ChatML template with an empty think block (for builds whose own template fails)')
  ap.add_argument('--preface-turn', action='store_true',
                  help='send the instructions as a first user turn answered "OK" instead of a system message '
                       '(Gemma has no system turn, so only this layout can be prefilled ahead of the overlay)')
  ap.add_argument('--limit', type=int, default=0)
  args = ap.parse_args()

  data = json.loads(subprocess.run(['node', '--input-type=module', '-e', EXPORT, args.data],
                                   cwd=HERE, check=True, capture_output=True, text=True).stdout)
  labels, rows = data['labels'], data['rows'][: args.limit or None]
  litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
  backend = litert_lm.Backend.GPU() if args.backend == 'gpu' else litert_lm.Backend.CPU()
  t0 = time.time()
  engine = litert_lm.Engine(args.model, backend=backend, cache_dir=str(pathlib.Path(args.model).parent))
  print(f'model: {pathlib.Path(args.model).name}  backend: {args.backend}  load: {time.time() - t0:.1f} s', flush=True)

  ok = guarded = hidden = no_label = 0
  n_content = sum(r['expected'] == 'content' for r in rows)
  ms, misses, confusion = [], [], {}
  for r in rows:
    user = r['user'].removesuffix('\n/no_think') if args.no_think_suffix else r['user']
    if args.fresh_engine and r is not rows[0]:
      engine.close()
      engine = litert_lm.Engine(args.model, backend=backend, cache_dir=str(pathlib.Path(args.model).parent))
    preface = [{'role': 'user', 'content': data['system']}, {'role': 'model', 'content': 'OK'}] if args.preface_turn else None
    conv = engine.create_conversation(
        messages=preface,
        system_message=None if args.preface_turn else data['system'],
        sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0),
        thinking_config=litert_lm.ThinkingConfig(enable_thinking=False),
        max_output_tokens=32,
        chat_template=CHATML if args.chatml else None,
    )
    t = time.time()
    acc = ''
    try:
      for chunk in conv.send_message_async(user):
        acc += str(chunk)  # each chunk is the new text
        if CLOSED_LABEL.search(acc):
          break
    finally:
      try:
        conv.cancel_process()
      except Exception:
        pass
      conv.close()
    ms.append((time.time() - t) * 1000)
    m = CLOSED_LABEL.search(acc)
    if not m:
      no_label += 1
    got = m.group(1) if m and m.group(1) in labels else 'content'
    g = 'content' if r['chrome'] else got
    ok += got == r['expected']
    guarded += g == r['expected']
    if r['expected'] == 'content' and g != 'content':
      hidden += 1
    confusion.setdefault(r['expected'], {}).setdefault(got, 0)
    confusion[r['expected']][got] += 1
    if g != r['expected']:
      why = ' (header guard)' if r['chrome'] else ' (hidden)' if r['expected'] == 'content' else ''
      misses.append(f"    {r['id']}: {r['expected']} -> {g}{why}  raw={acc[:60]!r}")
    print(f"{'✓' if g == r['expected'] else '✗'} {r['id'][:40]:40} {r['expected']:20} {got:20} {ms[-1]:.0f} ms", flush=True)

  n = len(rows)
  print(f"\n== {pathlib.Path(args.model).name}: {ok}/{n} = {100 * ok / n:.0f}%  guarded {guarded}/{n} = {100 * guarded / n:.0f}%"
        f"  content hidden {hidden}/{n_content}  no label {no_label}  median {statistics.median(ms):.0f} ms")
  print('\n'.join(misses))
  print('\nconfusion (expected -> got):')
  for e, got in confusion.items():
    print(f"  {e:20} {' '.join(f'{k}:{v}' for k, v in got.items())}")


if __name__ == '__main__':
  sys.exit(main())
