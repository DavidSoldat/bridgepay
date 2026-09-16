"""
Derives the Mock Credit Bureau's static in-memory pool from the raw Give Me
Some Credit dataset (spec section 6 - Mock Credit Bureau Mechanics).

Run from anywhere; paths are resolved relative to this file:

    python generate_pool.py

Requires ../../../data/cs-training.csv (see data/README.md for how to get it
and the exact held-out split rule, which this script implements).
"""
import pathlib

import pandas as pd

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
RAW_CSV = SCRIPT_DIR / "../../../data/cs-training.csv"
OUTPUT_CSV = SCRIPT_DIR / "../src/main/resources/data/bureau-pool.csv"

# Same three columns share this dataset's known sentinel/data-quality values
# (96, 98) for the exact same ~269 rows - see spec section 12's preprocessing
# note. Capped to 18, comfortably above every legitimate observed value
# (<=17) without being a nonsense placeholder.
PAST_DUE_COLUMNS = [
    "NumberOfTime30-59DaysPastDueNotWorse",
    "NumberOfTime60-89DaysPastDueNotWorse",
    "NumberOfTimes90DaysLate",
]
PAST_DUE_CAP = 18
MIN_PLAUSIBLE_AGE = 18

df = pd.read_csv(RAW_CSV, index_col=0)
row_index = pd.RangeIndex(len(df))
held_out = (row_index % 100) < 15

training_side = df[~held_out]
pool = df[held_out].copy()

# Impute using stats from the training-side 85% only - the bureau pool must
# stay informationally separate from anything the model training touches.
monthly_income_median = training_side["MonthlyIncome"].median()
pool["MonthlyIncome"] = pool["MonthlyIncome"].fillna(monthly_income_median)
pool["NumberOfDependents"] = pool["NumberOfDependents"].fillna(0)

for col in PAST_DUE_COLUMNS:
    pool[col] = pool[col].clip(upper=PAST_DUE_CAP)

pool["age"] = pool["age"].clip(lower=MIN_PLAUSIBLE_AGE)

pool = pool.drop(columns=["SeriousDlqin2yrs"])
pool["NumberOfDependents"] = pool["NumberOfDependents"].astype(int)

OUTPUT_CSV.parent.mkdir(parents=True, exist_ok=True)
pool.to_csv(OUTPUT_CSV, index=False)

print(f"Wrote {len(pool)} rows to {OUTPUT_CSV.resolve()}")
print(f"(training-side median MonthlyIncome used for imputation: {monthly_income_median})")
print(pool.describe())
