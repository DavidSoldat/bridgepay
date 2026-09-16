# Model artifacts

`model.onnx` and `coefficients.json` belong here once the training pipeline
(spec section 12) produces them. Until then this directory is empty on
purpose: `OnnxModelScorer` detects the missing files at startup, logs a
warning, and every `/internal/score` call fail-safes to `MANUAL_REVIEW`.

See `src/test/resources/fixtures/generate_fixture.py` for the schema these
two files need to follow (it's a toy stand-in used only by
`OnnxModelScorerIntegrationTest`, not the real model).
