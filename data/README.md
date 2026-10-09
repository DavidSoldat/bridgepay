# Data

`cs-training.csv` — the raw Kaggle ["Give Me Some Credit"](https://www.kaggle.com/c/GiveMeSomeCredit/data)
dataset (~150k rows). **Not committed to git** (see `.gitignore` in this
folder): Kaggle competition data isn't ours to redistribute. Everything
derived from it (the bureau pool and the trained model) is committed.

This single raw file feeds two separate, independent tasks (spec section 12),
and both derive their split from it using the **same rule** rather than a
shared manifest file, so there's one source of truth instead of two files
that can drift out of sync:

```
heldOut = (rowIndex % 100) < 15   # rowIndex = 0-based position in the file, after the header
```

- **Held out (`heldOut == true`, ~15% / 22.5k rows)** — belongs to the Mock
  Credit Bureau's simulation pool. Never used for training. See
  `services/mock-credit-bureau/scripts/generate_pool.py`, which reads this
  file and writes the derived, cleaned pool to
  `services/mock-credit-bureau/src/main/resources/data/bureau-pool.csv`
  (that derived file *is* committed — it's small, and it's the actual
  runtime data the service needs).
- **Not held out (`heldOut == false`, ~85% / 127.5k rows)**: the model's
  training data (70% train / 15% eval out of the *original* 150k, i.e.
  roughly 82%/18% of this 85% remainder).
  `services/credit-risk-engine/scripts/train_model.py` applies the same
  `rowIndex % 100 < 15` rule first to exclude the bureau's rows, then does
  its own stratified train/eval split.

Interleaving by `rowIndex % 100` rather than taking a contiguous last-15%
slice avoids any bias if the original file happens to be sorted by some
feature.
