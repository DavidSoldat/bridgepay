"""
Generates a small, deterministic ONNX pipeline (StandardScaler + LogisticRegression)
plus a matching coefficients.json, used ONLY by OnnxModelScorerIntegrationTest to
verify the Java ONNX Runtime integration (tensor I/O, zipmap output handling,
scaler-params-driven scoreFactors dot product) end-to-end.

This is NOT the real credit risk model - that comes from the actual "Give Me Some
Credit" training pipeline (spec section 12), a separate, later task. Re-run this
script only if the fixture's feature set changes.
"""
import json

import numpy as np
from onnx.helper import get_attribute_value
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

FEATURES = ["revolvingUtilization", "age", "debtRatio", "monthlyIncome"]

rng = np.random.default_rng(42)
n = 500
X = np.column_stack([
    rng.uniform(0, 1.5, n),      # revolvingUtilization
    rng.uniform(18, 80, n),      # age
    rng.uniform(0, 2.0, n),      # debtRatio
    rng.uniform(500, 15000, n),  # monthlyIncome
])
# Synthetic-but-plausible target: higher utilization/debt ratio and lower
# age/income push toward default (label 1), matching the real dataset's shape.
logit = (3.0 * X[:, 0] + 2.0 * X[:, 2]
         - 0.03 * X[:, 1] - 0.0002 * X[:, 3] - 1.0)
prob = 1 / (1 + np.exp(-logit))
y = (rng.uniform(0, 1, n) < prob).astype(np.int64)

pipeline = Pipeline([
    ("scaler", StandardScaler()),
    ("model", LogisticRegression(class_weight="balanced")),
])
pipeline.fit(X, y)

onnx_model = to_onnx(
    pipeline,
    initial_types=[("float_input", FloatTensorType([None, len(FEATURES)]))],
    target_opset=17,
)
with open("model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())

scaler: StandardScaler = pipeline.named_steps["scaler"]
model: LogisticRegression = pipeline.named_steps["model"]

coefficients = {
    "intercept": float(model.intercept_[0]),
    "features": {
        name: {
            "mean": float(scaler.mean_[i]),
            "scale": float(scaler.scale_[i]),
            "coefficient": float(model.coef_[0][i]),
        }
        for i, name in enumerate(FEATURES)
    },
}
with open("coefficients.json", "w") as f:
    json.dump(coefficients, f, indent=2)

print("Wrote model.onnx and coefficients.json for features:", FEATURES)

# Sanity check + print a sample the Java test can assert against.
import onnxruntime as ort

sess = ort.InferenceSession("model.onnx", providers=["CPUExecutionProvider"])
print("Input names:", [i.name for i in sess.get_inputs()])
print("Output names:", [o.name for o in sess.get_outputs()])

sample = np.array([[0.5, 40.0, 0.3, 5000.0]], dtype=np.float32)
outputs = sess.run(None, {"float_input": sample})
print("Sample prediction outputs:", outputs)

scaled = (sample[0] - scaler.mean_) / scaler.scale_
manual_logit = float(np.dot(scaled, model.coef_[0]) + model.intercept_[0])
manual_prob = 1 / (1 + np.exp(-manual_logit))
print("Manual logit:", manual_logit, "manual prob (class 1):", manual_prob)
