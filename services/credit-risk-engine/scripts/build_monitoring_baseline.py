"""
Training-side facts for model monitoring (D4c), and demo samples for the seed.

Run from anywhere after train_model.py (no retraining happens here):

    python build_monitoring_baseline.py

Re-applies train_model.py's load/clean/split (same constants, same RANDOM_STATE) and scores with
coefficients.json (checked against model.onnx). Writes:
  ../src/main/resources/model/baseline.json - eval-split score bins + default rate per bin, AUC, model hash
  the "generated model samples" block of application-service's demo seed - 300 held-out bureau-pool rows
  (cleaned like mock-credit-bureau/scripts/generate_pool.py) with real labels and the factors the engine stores.
"""
import hashlib
import json
import pathlib
import re

import numpy as np
import onnxruntime as ort
import pandas as pd
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import train_test_split

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
RAW_CSV = SCRIPT_DIR / "../../../data/cs-training.csv"
MODEL_DIR = SCRIPT_DIR / "../src/main/resources/model"
SEED_SQL = SCRIPT_DIR / "../../application-service/src/main/resources/db/demo/R__demo_sales_history.sql"

# Copied from train_model.py - keep in sync.
PAST_DUE_COLUMNS = [
    "NumberOfTime30-59DaysPastDueNotWorse",
    "NumberOfTime60-89DaysPastDueNotWorse",
    "NumberOfTimes90DaysLate",
]
PAST_DUE_CAP = 18
MIN_PLAUSIBLE_AGE = 18
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

SAMPLE_SEED = 7
# The deliberate recent drift: applicants under 45 (training median age is 52).
YOUNG_AGE = 45
BANDS = {"A": (0.0, 0.3, 140), "R": (0.3, 0.7, 100), "D": (0.7, 1.01, 60)}
AGE = FEATURE_NAMES.index("age")

coefficients = json.loads((MODEL_DIR / "coefficients.json").read_text())
assert list(coefficients["features"]) == FEATURE_NAMES, "coefficients.json feature order changed"
MEAN = np.array([coefficients["features"][n]["mean"] for n in FEATURE_NAMES])
SCALE = np.array([coefficients["features"][n]["scale"] for n in FEATURE_NAMES])
COEF = np.array([coefficients["features"][n]["coefficient"] for n in FEATURE_NAMES])
INTERCEPT = coefficients["intercept"]


def clean(frame, income_median):
    frame = frame.copy()
    frame["MonthlyIncome"] = frame["MonthlyIncome"].fillna(income_median)
    frame["NumberOfDependents"] = frame["NumberOfDependents"].fillna(0)
    for col in PAST_DUE_COLUMNS:
        frame[col] = frame[col].clip(upper=PAST_DUE_CAP)
    frame["age"] = frame["age"].clip(lower=MIN_PLAUSIBLE_AGE)
    return frame[KAGGLE_COLS].to_numpy(dtype=np.float64)


def contributions(x):
    return (x - MEAN) / SCALE * COEF


def sigmoid(z):
    return 1 / (1 + np.exp(-z))


def score(x):
    return sigmoid(contributions(x).sum(axis=1) + INTERCEPT)


df = pd.read_csv(RAW_CSV, index_col=0)
held_out = (pd.RangeIndex(len(df)) % 100) < 15

# --- eval split, exactly as train_model.py builds it ---
trainable = df[~held_out]
X_train, X_eval, y_train, y_eval = train_test_split(
    trainable[KAGGLE_COLS], trainable["SeriousDlqin2yrs"],
    test_size=15 / 85, stratify=trainable["SeriousDlqin2yrs"], random_state=RANDOM_STATE,
)
train_income_median = X_train["MonthlyIncome"].median()
x_eval = clean(X_eval, train_income_median)
eval_scores = score(x_eval)

sess = ort.InferenceSession(str(MODEL_DIR / "model.onnx"), providers=["CPUExecutionProvider"])
onnx_out = sess.run(None, {sess.get_inputs()[0].name: x_eval[:20].astype(np.float32)})
onnx_proba = np.array([row[1] for row in onnx_out[1]])
assert np.allclose(onnx_proba, eval_scores[:20], atol=1e-4), "coefficients.json no longer matches model.onnx"

y = y_eval.to_numpy()
bins = np.minimum((eval_scores * 10).astype(int), 9)
score_bins = []
for b in range(10):
    in_bin = bins == b
    score_bins.append({
        "from": round(b / 10, 1),
        "to": round((b + 1) / 10, 1),
        "share": float(in_bin.mean()),
        "defaultRate": float(y[in_bin].mean()) if in_bin.any() else None,
    })
assert abs(sum(s["share"] for s in score_bins) - 1) < 1e-9

baseline = {
    "modelVersion": hashlib.sha256((MODEL_DIR / "model.onnx").read_bytes()).hexdigest()[:12],
    "aucRoc": round(float(roc_auc_score(y, eval_scores)), 4),
    "evalRows": int(len(y)),
    "evalDefaultRate": round(float(y.mean()), 4),
    "features": FEATURE_NAMES,
    "scoreBins": score_bins,
}
(MODEL_DIR / "baseline.json").write_text(json.dumps(baseline, indent=2) + "\n", newline="\n")
print(f"baseline.json: model {baseline['modelVersion']}, AUC {baseline['aucRoc']}, {baseline['evalRows']} eval rows")

# --- demo samples from the held-out bureau pool (cleaned like generate_pool.py) ---
pool = df[held_out]
pool_income_median = df[~held_out]["MonthlyIncome"].median()
x_pool = clean(pool, pool_income_median)
pool_scores = score(x_pool)
pool_labels = pool["SeriousDlqin2yrs"].to_numpy().astype(bool)
young = x_pool[:, AGE] < YOUNG_AGE

rng = np.random.default_rng(SAMPLE_SEED)
rows = []
young_age_contribs = []
defaulted = 0
for band, (low, high, size) in BANDS.items():
    in_band = (pool_scores >= low) & (pool_scores < high)
    # Up to half young (low-score bands have few young applicants), the rest older.
    young_candidates = np.flatnonzero(in_band & young)
    old_candidates = np.flatnonzero(in_band & ~young)
    n_young = min(size // 2, len(young_candidates))
    assert n_young >= 20 and len(old_candidates) >= size - n_young, f"band {band}: too few candidates"
    picks = list(rng.choice(young_candidates, size=n_young, replace=False))
    picks.extend(rng.choice(old_candidates, size=size - n_young, replace=False))
    for k, i in enumerate(sorted(picks), start=1):
        contrib = contributions(x_pool[i:i + 1])[0]
        factors = sorted(
            ({"feature": n, "contribution": round(float(c), 4)} for n, c in zip(FEATURE_NAMES, contrib)),
            key=lambda f: -abs(f["contribution"]),
        )
        s = round(float(pool_scores[i]), 3)
        rebuilt = sigmoid(INTERCEPT + sum(f["contribution"] for f in factors))
        assert abs(rebuilt - s) < 1e-3, f"sample {band}{k} factors don't reproduce its score"
        if young[i]:
            young_age_contribs.append(contrib[AGE])
        defaulted += int(pool_labels[i])
        rows.append(f"('{band}', {k}, {s}, {str(bool(pool_labels[i])).lower()}, {str(bool(young[i])).lower()}, "
                    f"'{json.dumps(factors, separators=(',', ':'))}')")
assert np.mean(young_age_contribs) >= 0.3, "young samples don't shift age enough to be flagged"
print(f"samples: {len(rows)}; young mean age contribution {np.mean(young_age_contribs):.3f}; defaulted {defaulted}")

block = "\n".join([
    "-- BEGIN generated model samples",
    "-- Written by credit-risk-engine/scripts/build_monitoring_baseline.py - do not edit by hand.",
    "-- Held-out bureau-pool rows scored by the real model, with their real SeriousDlqin2yrs labels.",
    "DROP TABLE IF EXISTS demo_model_samples;",
    "CREATE TEMP TABLE demo_model_samples (band text, k int, score numeric, defaulted boolean, young boolean, factors text);",
    "INSERT INTO demo_model_samples VALUES",
    ",\n".join(rows) + ";",
    "-- END generated model samples",
])
sql = SEED_SQL.read_text()
pattern = re.compile(r"-- BEGIN generated model samples.*?-- END generated model samples", re.S)
assert pattern.search(sql), "markers missing in the demo seed"
SEED_SQL.write_text(pattern.sub(lambda _: block, sql), newline="\n")
print(f"wrote {len(rows)} samples into {SEED_SQL.name}")
