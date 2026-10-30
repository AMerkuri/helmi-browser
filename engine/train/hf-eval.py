"""Label accuracy of a Hugging Face Qwen3 checkpoint on train/prompts.mjs output: greedy, stops after the label.

  python train/hf-eval.py Qwen/Qwen3-0.6B test-prompts.jsonl [lora-adapter]
"""
import json, re, sys, time, torch
from transformers import AutoTokenizer, AutoModelForCausalLM
LABELS = ["cookie_consent","newsletter","notification_prompt","paywall_teaser","app_install","login_wall","chat_widget","survey","content"]
model_id, prompts = sys.argv[1], sys.argv[2]
adapter = sys.argv[3] if len(sys.argv) > 3 else None
tok = AutoTokenizer.from_pretrained(model_id)
m = AutoModelForCausalLM.from_pretrained(model_id, dtype=torch.float32)
if adapter:
    from peft import PeftModel
    m = PeftModel.from_pretrained(m, adapter).merge_and_unload()
m.eval(); torch.set_num_threads(4)
def text(r):
    return tok.apply_chat_template([{"role":"system","content":r["system"]},{"role":"user","content":r["user"]}], tokenize=False, add_generation_prompt=True, enable_thinking=False)
ok = 0; n = 0; ms = []; out = []
for line in open(prompts):
    r = json.loads(line); ids = tok(text(r), return_tensors="pt").input_ids
    t0 = time.time()
    with torch.no_grad():
        g = m.generate(ids, max_new_tokens=14, do_sample=False)
    ms.append((time.time()-t0)*1000)
    reply = tok.decode(g[0, ids.shape[1]:], skip_special_tokens=True)
    mm = re.search(r'"label"\s*:\s*"([a-z_]+)', reply)
    label = mm.group(1) if mm and mm.group(1) in LABELS else "content"
    n += 1; ok += label == r["expected"]
    out.append({"id": r["id"], "expected": r["expected"], "label": label})
    print(("✓" if label == r["expected"] else "✗"), r["id"][:40].ljust(40), r["expected"].ljust(20), label, flush=True)
ms.sort()
print(f"== {ok}/{n} = {100*ok/n:.0f}%  median {ms[len(ms)//2]:.0f} ms")
