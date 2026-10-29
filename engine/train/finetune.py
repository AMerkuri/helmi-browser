"""LoRA fine-tune of Qwen3-0.6B on Serene's overlay prompts (train/prompts.mjs output).

The model learns to answer the app's prompt with {"label": "...", "target": N}; the loss is on that
reply only. The LoRA weights are merged into a plain Hugging Face checkpoint in --out, which the
litert-torch export (and android/npu-model/build.py) takes like the original Qwen/Qwen3-0.6B.

  python train/finetune.py --prompts train-prompts.jsonl --out qwen3-serene  (CPU: about 4.5 s per record, 1.5 hours)
"""

import argparse
import json
import random
import time

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer


def main() -> None:
  ap = argparse.ArgumentParser()
  ap.add_argument("--prompts", required=True)
  ap.add_argument("--out", required=True)
  ap.add_argument("--base", default="Qwen/Qwen3-0.6B")
  ap.add_argument("--epochs", type=float, default=1)
  ap.add_argument("--lr", type=float, default=2e-4)
  ap.add_argument("--rank", type=int, default=16)
  ap.add_argument("--batch", type=int, default=8, help="records per optimizer step (gradient accumulation)")
  ap.add_argument("--limit", type=int, default=0, help="stop after this many records (timing runs)")
  ap.add_argument("--seed", type=int, default=1)
  args = ap.parse_args()
  random.seed(args.seed)
  torch.manual_seed(args.seed)
  torch.set_num_threads(max(1, torch.get_num_threads()))

  tok = AutoTokenizer.from_pretrained(args.base)
  model = AutoModelForCausalLM.from_pretrained(args.base, dtype=torch.float32)
  model = get_peft_model(model, LoraConfig(
      r=args.rank, lora_alpha=2 * args.rank, lora_dropout=0.05, task_type="CAUSAL_LM",
      target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
  model.print_trainable_parameters()

  def encode(r):
    prompt = tok.apply_chat_template(
        [{"role": "system", "content": r["system"]}, {"role": "user", "content": r["user"]}],
        tokenize=False, add_generation_prompt=True, enable_thinking=False)
    reply = json.dumps({"label": r["expected"], "target": r["target"]}) + "<|im_end|>"
    p = tok(prompt, add_special_tokens=False).input_ids
    a = tok(reply, add_special_tokens=False).input_ids
    return torch.tensor([p + a]), torch.tensor([[-100] * len(p) + a])

  records = [json.loads(l) for l in open(args.prompts) if l.strip()]
  data = [encode(r) for r in records]
  steps = int(len(data) * args.epochs)
  if args.limit:
    steps = min(steps, args.limit)
  opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=0.0)
  sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 10) * max(0.0, 1 - s / max(1, steps // args.batch)))
  model.train()
  order = []
  t0 = time.time()
  running = 0.0
  for i in range(steps):
    if not order:
      order = list(range(len(data)))
      random.shuffle(order)
    ids, labels = data[order.pop()]
    loss = model(input_ids=ids, labels=labels).loss / args.batch
    loss.backward()
    running += loss.item()
    if (i + 1) % args.batch == 0 or i + 1 == steps:
      opt.step()
      sched.step()
      opt.zero_grad()
    if (i + 1) % 50 == 0 or i + 1 == steps:
      el = time.time() - t0
      print(f"{i + 1}/{steps} loss {running * args.batch / 50:.4f} {el / (i + 1):.2f} s/record, {el / 60:.1f} min", flush=True)
      running = 0.0

  model = model.merge_and_unload().to(torch.bfloat16)  # the original's dtype, half the size of float32
  model.save_pretrained(args.out)
  tok.save_pretrained(args.out)
  print("saved", args.out)


if __name__ == "__main__":
  main()
