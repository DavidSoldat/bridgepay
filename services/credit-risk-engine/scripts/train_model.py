"""
Trains the real credit-risk logistic-regression model (spec section 12) on
the Kaggle "Give Me Some Credit" dataset and exports the two artifacts
OnnxModelScorer/CoefficientSet load at startup.

Run from anywhere; paths are resolved relative to this file:

    python train_model.py

Requires ../../../data/cs-training.csv (see data/README.md for how to get it
and the exact held-out split rule, which this script re-applies before doing
its own train/eval split) and these packages: pandas, numpy, scikit-learn,
skl2onnx, onnx, onnxruntime.

Writes ../src/main/resources/model/model.onnx and .../model/coefficients.json.
"""
import json
import pathlib

import numpy as np
import onnxruntime as ort
import pandas as pd
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
RAW_CSV = SCRIPT_DIR / "../../../data/cs-training.csv"
MODEL_DIR = SCRIPT_DIR / "../src/main/resources/model"

# Same sentinel-value cleanup as mock-credit-bureau/scripts/generate_pool.py.
PAST_DUE_COLUMNS = [
    "NumberOfTime30-59DaysPastDueNotWorse",
    "NumberOfTime60-89DaysPastDueNotWorse",
    "NumberOfTimes90DaysLate",
]
PAST_DUE_CAP = 18
MIN_PLAUSIBLE_AGE = 18

# Kaggle column -> FeatureVector/BureauProfile name, in the exact order that
# becomes both the ONNX input tensor's column order and coefficients.json's
# key order.
FEATURE_COLUMNS = [
    ("RevolvingUtilizationOfUnsecuredLines", "revolvingUtilization"),
    ("age", "age"),
    ("NumberOfTime30-59DaysPastDueNotWorse", "numberOfTime30to59DaysPastDueNotWorse"),
    ("DebtRatio", "debtRatio"),
    ("MonthlyIncome", "monthlyIncome"),
    ("NumberOfOpenCreditLinesAndLoans", "numberOfOpenCreditLinesAndLoans"),
    ("NumberOfTimes90DaysLate", "numberOfTimes90DaysLate"),
    ("NumberRealEstateLoansOrLines", "numberRealEstateLoansOrLines"),
    ("NumberOfTime60-89DaysPastDueNotWorse", "numberOfTime60to89DaysPastDueNotWorse"),
    ("NumberOfDependents", "numberOfDependents"),
]
KAGGLE_COLS = [c for c, _ in FEATURE_COLUMNS]
FEATURE_NAMES = [n for _, n in FEATURE_COLUMNS]
RANDOM_STATE = 42

df = pd.read_csv(RAW_CSV, index_col=0)
row_index = pd.RangeIndex(len(df))
held_out = (row_index % 100) < 15
trainable = df[~held_out].copy()

X = trainable[KAGGLE_COLS]
y = trainable["SeriousDlqin2yrs"]

# 15% of the original 150k out of the 85% remainder -> test_size = 15/85.
X_train, X_eval, y_train, y_eval = train_test_split(
    X, y, test_size=15 / 85, stratify=y, random_state=RANDOM_STATE
)
X_train = X_train.copy()
X_eval = X_eval.copy()

monthly_income_median = X_train["MonthlyIncome"].median()
for split in (X_train, X_eval):
    split["MonthlyIncome"] = split["MonthlyIncome"].fillna(monthly_income_median)
    split["NumberOfDependents"] = split["NumberOfDependents"].fillna(0)
    for col in PAST_DUE_COLUMNS:
        split[col] = split[col].clip(upper=PAST_DUE_CAP)
    split["age"] = split["age"].clip(lower=MIN_PLAUSIBLE_AGE)

X_train_arr = X_train[KAGGLE_COLS].to_numpy(dtype=np.float32)
X_eval_arr = X_eval[KAGGLE_COLS].to_numpy(dtype=np.float32)

pipeline = Pipeline([
    ("scaler", StandardScaler()),
    ("clf", LogisticRegression(class_weight="balanced")),
])
pipeline.fit(X_train_arr, y_train)

eval_proba = pipeline.predict_proba(X_eval_arr)[:, 1]
auc = roc_auc_score(y_eval, eval_proba)
print(f"AUC-ROC: {auc:.4f}")
for threshold in (0.3, 0.7):
    predicted = (eval_proba >= threshold).astype(int)
    precision = precision_score(y_eval, predicted, zero_division=0)
    recall = recall_score(y_eval, predicted, zero_division=0)
    print(f"threshold={threshold}: precision={precision:.4f} recall={recall:.4f}")

onnx_model = to_onnx(
    pipeline,
    initial_types=[("input", FloatTensorType([None, len(FEATURE_NAMES)]))],
    target_opset=17,
)

scaler: StandardScaler = pipeline.named_steps["scaler"]
clf: LogisticRegression = pipeline.named_steps["clf"]
coefficients = {
    "intercept": float(clf.intercept_[0]),
    "features": {
        name: {
            "mean": float(scaler.mean_[i]),
            "scale": float(scaler.scale_[i]),
            "coefficient": float(clf.coef_[0][i]),
        }
        for i, name in enumerate(FEATURE_NAMES)
    },
}

# Self-check: the artifacts must reproduce sklearn's own probabilities, the
# same thing OnnxModelScorerIntegrationTest verifies on the Java side.
sess = ort.InferenceSession(onnx_model.SerializeToString(), providers=["CPUExecutionProvider"])
input_name = sess.get_inputs()[0].name
sample = X_eval_arr[:20]
sklearn_proba = pipeline.predict_proba(sample)[:, 1]

onnx_outputs = sess.run(None, {input_name: sample})
onnx_proba = np.array([row[1] for row in onnx_outputs[1]])
assert np.allclose(onnx_proba, sklearn_proba, atol=1e-4), "ONNX export does not match sklearn predict_proba"

mean = np.array([coefficients["features"][n]["mean"] for n in FEATURE_NAMES])
scale = np.array([coefficients["features"][n]["scale"] for n in FEATURE_NAMES])
coef = np.array([coefficients["features"][n]["coefficient"] for n in FEATURE_NAMES])
scaled = (sample - mean) / scale
manual_logit = scaled @ coef + coefficients["intercept"]
manual_proba = 1 / (1 + np.exp(-manual_logit))
assert np.allclose(manual_proba, sklearn_proba, atol=1e-4), "coefficients.json dot product does not match sklearn predict_proba"
print("Self-check passed: ONNX output and coefficients.json dot product both match sklearn predict_proba.")

MODEL_DIR.mkdir(parents=True, exist_ok=True)
(MODEL_DIR / "model.onnx").write_bytes(onnx_model.SerializeToString())
(MODEL_DIR / "coefficients.json").write_text(json.dumps(coefficients, indent=2))
print(f"Wrote model.onnx and coefficients.json to {MODEL_DIR.resolve()}")
