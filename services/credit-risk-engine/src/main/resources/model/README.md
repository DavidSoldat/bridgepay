# Model artifacts

`model.onnx` and `coefficients.json` belong here once the training pipeline
(spec section 12) produces them. Until then this directory is empty on
purpose: `OnnxModelScorer` detects the missing files at startup, logs a
warning, and every `/internal/score` call fail-safes to `MANUAL_REVIEW`.

See `src/test/resources/fixtures/generate_fixture.py` for the schema these
two files need to follow (it's a toy stand-in used only by
`OnnxModelScorerIntegrationTest`, not the real model).

`baseline.json` holds the training-side facts the ops model monitoring page
compares production against (eval-split score bins with their default rates,
AUC, and `modelVersion` = the first 12 hex of `model.onnx`'s SHA-256). It is
written by `scripts/build_monitoring_baseline.py`, which also regenerates the
demo seed's model samples. Rerun it after every retrain: on a version mismatch
`GET /api/v1/model` returns 503 instead of serving a stale baseline.
