"""Writes scale.onnx, the tiny model OrtApiTest runs through the C API binding.

Inputs like the embedding model's: input_ids and attention_mask, int64 [batch, length].
Output last_hidden_state, float [batch, length, 4] = (input_ids * attention_mask) * [1, 2, 3, 4].

Usage: python make_test_model.py  (needs the `onnx` package; writes next to this script)
"""
from pathlib import Path

import onnx
from onnx import TensorProto, helper

ids = helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch", "length"])
mask = helper.make_tensor_value_info("attention_mask", TensorProto.INT64, ["batch", "length"])
out = helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch", "length", 4])
weights = helper.make_tensor("weights", TensorProto.FLOAT, [4], [1.0, 2.0, 3.0, 4.0])
axes = helper.make_tensor("axes", TensorProto.INT64, [1], [-1])
nodes = [
    helper.make_node("Mul", ["input_ids", "attention_mask"], ["masked"]),
    helper.make_node("Cast", ["masked"], ["masked_f"], to=TensorProto.FLOAT),
    helper.make_node("Unsqueeze", ["masked_f", "axes"], ["column"]),
    helper.make_node("Mul", ["column", "weights"], ["last_hidden_state"]),
]
graph = helper.make_graph(nodes, "scale", [ids, mask], [out], initializer=[weights, axes])
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="watch-audio test")
model.ir_version = 8
onnx.checker.check_model(model)
onnx.save(model, str(Path(__file__).with_name("scale.onnx")))
