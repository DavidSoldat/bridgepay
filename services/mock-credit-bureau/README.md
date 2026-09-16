# BridgePay — Mock Credit Bureau

Stubbed external dependency. Stateless: exposes
`GET /internal/bureau-profile/{applicantId}`, hashes the applicant ID into a
fixed, in-memory pool of 22,500 real (but held-out) Give Me Some Credit rows,
and returns that row every time — same applicant always gets the same
profile, no per-request re-roll, no datastore of any kind (spec section 6).

## Where the pool comes from

`src/main/resources/data/bureau-pool.csv` is generated, not hand-written, by
`scripts/generate_pool.py` from the raw Kaggle dataset — see
`../../data/README.md` for the exact held-out split rule (`rowIndex % 100 <
15`) shared with the (not yet built) model training task, so the bureau's
pool and the model's training data never overlap without needing a separate
manifest file. Re-run the script only if that rule or the cleaning steps
change:

```bash
python scripts/generate_pool.py
```

Cleaning applied to the held-out rows (imputation stats computed from the
other 85% only, to keep the pool informationally separate from anything
training touches): median-impute missing `MonthlyIncome`, 0-impute missing
`NumberOfDependents`, cap the dataset's known 96/98 sentinel placeholder
values in the past-due columns to 18, clip the one `age == 0` row up to 18.

## Run locally

```bash
docker-compose up --build
```

Uses the `local` Spring profile (JWT auth disabled). No other infrastructure
needed — this service has no database, cache, or message broker (spec
section 4's data store column for this service is literally "—").

## Run tests

```bash
mvn clean verify
```

`BureauPoolTest` covers pool loading, lookup determinism, and that different
applicants generally land on different rows. `BureauProfileControllerIntegrationTest`
hits the real HTTP endpoint end-to-end.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/internal/bureau-profile/{applicantId}` | none (network-isolated) | Deterministic synthetic credit profile |
