# CI/CD Pipeline — Design

## Context

First half of the next `docs/PROGRESS.md` item ("k3s manifests, GitHub
Actions CI/CD with ARM64 image builds"), split into two sequential
sub-projects since the manifests need real image tags to reference —
this doc covers CI/CD only; the k3s manifests are a separate follow-up.

Per `docs/bridgepay-platform-spec.md` §13, the build/deploy mechanism was
already decided during initial planning: "GitHub Actions builds and
pushes images to GHCR, then SSHs into the box and runs `kubectl apply -f
k8s/`" and "images must be built for `linux/arm64` ... via QEMU in CI,
since GitHub Actions' default runners are x86_64." This doc only builds
the first half of that (test + build + push); the SSH+`kubectl apply`
deploy step needs a real reachable k3s host and a kubeconfig secret,
neither of which exist yet — that's `docs/PROGRESS.md`'s separate
"Public deployment readiness" item, not this one.

No `.github/workflows/` exists in this repo at all yet — this is the
project's first CI of any kind, for any service.

## Decisions made before writing this doc (see chat for full option sets)

- **CI first, k3s manifests second** — this repo's own established
  precedent (the "frontend" work was decomposed the same way: API
  gateway before the two Angular apps that depend on it existing).
- **Single workflow file**, `.github/workflows/ci.yml`, not one file per
  service — the 9 app projects only differ in language (Java vs.
  Angular) and directory, which a matrix handles without 9 near-duplicate
  files.
- **Always test/build all 9 projects**, no per-service path-filtering.
  Simpler, and CI-minute cost is low for a portfolio project's traffic;
  worth adding later if it becomes annoying, not before.
  `// ponytail: no path-filtering, add dorny/paths-filter if CI minutes
  or wait time ever actually become a problem`.
- **Tag scheme**: `latest` and the short git SHA (`${{ github.sha }}`),
  not semantic versioning — nothing in this project has version numbers
  today, and the SHA tag is what a future rollback/rollout on the k3s
  side would actually pin to.
- **Auth to GHCR via the built-in `GITHUB_TOKEN`**, not a personal access
  token — `permissions: packages: write` at the job level is sufficient
  for a same-repo, same-org publish; no new repo secret needed.
- **No deploy job in this workflow** — see Context. Non-goal, not an
  oversight.

## Workflow shape

`.github/workflows/ci.yml`, triggered on `push` to `main` and on every
`pull_request` targeting `main`. Three jobs:

```
test-java (matrix: 7 services)
test-angular (matrix: 2 frontends)
        \        /
         build-and-push (matrix: 9 images)
         needs: [test-java, test-angular]
         if: push to main only
```

### `test-java`

Matrix over the 7 service directories under `services/`. Per matrix
entry: `actions/setup-java@v4` (Temurin 21, matching every service's
Java 21 + Maven convention from `CLAUDE.md`), then `mvn -f
services/<name> clean verify`. Runs on `ubuntu-latest`, which ships
Docker — Testcontainers (Postgres/Kafka used across several services'
test suites) works without extra setup, the same way it already does
in every contributor's local `mvn clean verify` today.

`applicant-service` has no Kafka by design (`CLAUDE.md`) but that's
transparent to this job — it's still just `mvn clean verify` per
service, no per-service special-casing needed.

### `test-angular`

Matrix over `frontend/main-app` and `frontend/storefront`.
`actions/setup-node@v4` (Node 22, matching the Dockerfiles' `node:22-alpine`
build stage), then in each project directory:

```
npm install -g npm@11.6.1
npm ci
npx ng test --watch=false
```

The `npm install -g npm@11.6.1` line reuses the fix already diagnosed
and documented in `docs/PROGRESS.md` for the Docker build stage: Node
22's bundled npm (10.9.8) can't read a lockfile written by npm 11.6.1
(the version both `package.json`'s `packageManager` field pins and what
generated `package-lock.json`). Same root cause here — a GitHub Actions
runner's Node 22 install ships the same bundled npm 10.9.8 — so the same
one-line fix applies before `npm ci`, not a new problem to re-diagnose.

Angular's test runner here is Vitest + jsdom (confirmed from actual
`npx ng test` output earlier this project — `RUN v4.1.11`), not a real
browser, so no headless-Chrome setup is needed on the runner.

### `build-and-push`

Matrix over all 9 app directories (7 services + 2 frontends), each
entry giving its directory and image name:

| Directory | Image |
|---|---|
| `services/applicant-service` | `bridgepay-applicant-service` |
| `services/application-service` | `bridgepay-application-service` |
| `services/credit-risk-engine` | `bridgepay-credit-risk-engine` |
| `services/mock-credit-bureau` | `bridgepay-mock-credit-bureau` |
| `services/notifications-service` | `bridgepay-notifications-service` |
| `services/repayment-reconciliation-service` | `bridgepay-repayment-reconciliation-service` |
| `services/api-gateway` | `bridgepay-api-gateway` |
| `frontend/main-app` | `bridgepay-main-app` |
| `frontend/storefront` | `bridgepay-storefront` |

`needs: [test-java, test-angular]`, `if: github.event_name == 'push' &&
github.ref == 'refs/heads/main'` — PRs run the test jobs only, nothing
is published from a PR build.

Steps per matrix entry: `docker/setup-qemu-action@v3`,
`docker/setup-buildx-action@v3`, `docker/login-action@v3` (registry
`ghcr.io`, `username: ${{ github.actor }}`, `password: ${{
secrets.GITHUB_TOKEN }}`), then:

```
docker buildx build --platform linux/arm64 --push \
  -t ghcr.io/davidsoldat/<image>:latest \
  -t ghcr.io/davidsoldat/<image>:${{ github.sha }} \
  <directory>
```

Single-platform (`linux/arm64` only), consistent with `CLAUDE.md`'s
standing convention ("Docker images build for `linux/arm64`
specifically... never a plain `docker build`") — no `linux/amd64` variant
is published since nothing runs these images on x86.

Owner `davidsoldat` is hardcoded lowercase (GHCR requires a lowercase
path; the actual GitHub owner is `DavidSoldat`) rather than derived from
`${{ github.repository_owner }}` — this is a fixed personal-portfolio
repo, not something that gets forked under a different owner and needs
the tag to follow automatically.

## Verification plan

Same "verify for real" standard as the rest of this project:

1. Push the workflow to a branch, open a PR against `main`, confirm
   `test-java` and `test-angular` both actually run and pass on GitHub
   (not just locally) — this is the first time any of these 9 test
   suites has run in GitHub's environment rather than a local machine or
   this session's own sandbox, so this is a real, not assumed, check.
2. Merge to `main`, confirm `build-and-push` runs and all 9 images land
   in GHCR with both tags.
3. Pull one Java image and one Angular image from GHCR on this ARM64-
   capable session (if available) or inspect via `docker manifest
   inspect`/GHCR's own UI to confirm the pushed platform is genuinely
   `linux/arm64`, not silently `linux/amd64` from a misconfigured
   buildx step.

## Non-goals for this sub-project

- No deploy job (SSH + `kubectl apply`) — needs a real k3s host and
  kubeconfig secret, tracked under `docs/PROGRESS.md`'s "Public
  deployment readiness" item.
- No path-filtering / selective builds — see the `ponytail:` note above.
- No semantic versioning or release tagging — `latest` + git SHA only.
- No multi-arch (`linux/amd64`) image variant.
- No caching layer (e.g. `actions/cache` for the Maven/npm dependency
  trees) — worth adding if build times become a real annoyance, not
  speculatively now.
