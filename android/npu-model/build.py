"""Builds Qwen3-0.6B for the Snapdragon 8 Elite's NPU (SM8750, Hexagon V79), ahead of time.

LiteRT-LM can run a plain .litertlm on the NPU by compiling it on the phone, but that keeps most
of the graph on the CPU and took 7.9 s a call on a Galaxy Z Fold 7 (the GPU takes 0.8 s). A model
compiled ahead of time runs LiteRT-LM's NPU executor instead. This follows litert-torch's NPU
pipeline (litert_torch/generative/export_hf/experimental/npu_export) with its Qwen3-0.6B / SM8750
config, in four stages:

  1. export:    Hugging Face weights -> .litertlm (int8 weights, float activations)
  2. calibrate: activation ranges, from Serene's own prompts (calibration.jsonl)
  3. quantize:  static ranges, 16-bit activations and 8-bit weights (a16w8)
  4. compile:   Qualcomm AOT compile for the SM8750 (needs QAIRT, which the
                ai-edge-litert-sdk-qualcomm package downloads when it is installed)

The KV cache is 512 tokens instead of the config's 1024: Serene's prompts are about 240 tokens
and a reply stops after the label, and a smaller cache keeps the attention mask under the size
where Qualcomm NPUs drop the prefill chunks after the first (LiteRT-LM issue 3508).

Run by .github/workflows/npu-model.yml; locally: python build.py --work /tmp/npu (Linux x86_64).
"""

import argparse
import copy
import os
import pathlib
import re

from litert_torch.generative.export_hf.experimental import npu_export
from litert_torch.generative.export_hf.experimental.npu_export import stages
from litert_torch.generative.export_hf.experimental.npu_export.config_manager import NpuPipelineConfig

HERE = pathlib.Path(__file__).parent


def main() -> None:
  ap = argparse.ArgumentParser()
  ap.add_argument("--work", required=True, help="working directory for the stages")
  ap.add_argument("--cache-length", type=int, default=512)
  ap.add_argument("--stages", default="export,calibrate,quantize,compile")
  args = ap.parse_args()
  work = pathlib.Path(args.work)
  work.mkdir(parents=True, exist_ok=True)
  todo = set(args.stages.split(","))

  config = pathlib.Path(npu_export.__file__).parent / "configs" / "qwen3_0.6b_qualcomm_sm8750.json"
  cfg = NpuPipelineConfig.load_json(str(config))
  cfg.cache_length = args.cache_length
  print(cfg)

  exported = work / "exported" / "model.litertlm"
  calib_data = work / "calib_data"
  calib_out = work / "calib_bounds"
  quantized = work / "quantized" / "model.litertlm"
  compiled = work / "npu" / "Qwen3-0.6B.sm8750.litertlm"

  if "export" in todo:
    stages.npu_export(cfg=cfg, output_dir=str(exported.parent))
  if "calibrate" in todo:
    calib_data.mkdir(parents=True, exist_ok=True)
    (calib_data / "serene.jsonl").write_bytes((HERE / "calibration.jsonl").read_bytes())
    stages.npu_calibrate(
        cfg=cfg,
        input_litertlm=str(exported),
        calibration_dataset_dir=str(calib_data),
        calibration_result_save_dir=str(calib_out),
        calibration_eval_task_names=["serene"],
        max_calibration_decode_steps=16,  # replies stop after the label, about 8 tokens
        enable_formatting=False,  # the prompts already carry Qwen3's chat template
    )
  if "quantize" in todo:
    quantized.parent.mkdir(parents=True, exist_ok=True)
    stages.npu_quantize(cfg=cfg, input_litertlm=str(exported), output_litertlm=str(quantized), calibration_dir=str(calib_out))
  if "compile" in todo:
    compiled.parent.mkdir(parents=True, exist_ok=True)
    log_partitions()
    try:
      stages.npu_compile(cfg=cfg, input_litertlm=str(quantized), output_litertlm=str(compiled))
    except ValueError as e:
      # apply_plugin keeps the Qualcomm compiler's output in a file and names it in the message.
      for log in re.findall(r"\S+\.error", str(e)):
        if os.path.exists(log):
          print(f"--- {log} ---\n" + pathlib.Path(log).read_text(errors="replace")[-20000:])
      raise
    print(f"NPU model: {compiled} ({os.path.getsize(compiled) / 1e6:.0f} MB)")


def log_partitions() -> None:
  """Fixes each section before the Qualcomm plugin compiles it, and prints how many of its ops the
  plugin took, which the compiler doesn't."""
  from absl import logging
  from ai_edge_litert.aot.core import apply_plugin
  import ai_edge_litert

  logging.set_verbosity(logging.INFO)
  print(f"ai_edge_litert {getattr(ai_edge_litert, '__version__', '?')} from {ai_edge_litert.__file__}")
  call = apply_plugin.ApplyPlugin.__call__

  def logged(self, input_model, output_model, *args, **kwargs):
    if "prefill_decode" in pathlib.Path(input_model.path).name:
      quantize_unused_kv_inputs(input_model.path)
    result = call(self, input_model, output_model, *args, **kwargs)
    print(f"Partition of {input_model.path}: {output_model.partition_stats}")
    return result

  apply_plugin.ApplyPlugin.__call__ = logged


def quantize_unused_kv_inputs(path: str | os.PathLike) -> None:
  """Gives the prefill signature's last-layer KV cache inputs the int16 type the others have.

  Prefill has no logits, so the last layer's attention is dead code there and its KV cache inputs
  have no consumer. The quantizer only quantizes consumed tensors, so those two inputs stay
  float32 in prefill while decode has them as int16. LiteRT-LM shares one buffer per KV cache
  input between the signatures, and on the phone the prefill failed with "Custom allocation is
  too small" for that tensor. The inputs are unused, so their type changes nothing else.
  """
  from ai_edge_litert import schema_py_generated as schema
  from ai_edge_litert.tools import flatbuffer_utils

  path = str(path)
  model = flatbuffer_utils.read_model(path)
  name = lambda b: b.decode() if isinstance(b, bytes) else b
  kv_inputs = lambda sig: (
      (name(i.name), model.subgraphs[sig.subgraphIndex].tensors[i.tensorIndex])
      for i in sig.inputs if name(i.name).startswith("kv_cache_"))
  quantized = {}
  for sig in model.signatureDefs:
    for n, tensor in kv_inputs(sig):
      if tensor.type != schema.TensorType.FLOAT32:
        quantized.setdefault(n, tensor)
  fixed = []
  for sig in model.signatureDefs:
    for n, tensor in kv_inputs(sig):
      if tensor.type == schema.TensorType.FLOAT32 and n in quantized:
        tensor.type = quantized[n].type
        tensor.quantization = copy.deepcopy(quantized[n].quantization)
        fixed.append(f"{name(sig.signatureKey)}/{n}")
  print(f"KV cache inputs given the quantized type: {fixed or 'none'}")
  if fixed:
    flatbuffer_utils.write_model(model, path + ".fixed")
    os.replace(path + ".fixed", path)


if __name__ == "__main__":
  main()
