# CI/CD Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the project's first CI/CD: a GitHub Actions workflow that runs every service/frontend's existing test suite on every push/PR to `main`, and on merge to `main` builds and pushes all 9 app images to GHCR for `linux/arm64`.

**Architecture:** One workflow file, `.github/workflows/ci.yml`, three jobs — `test-java` (matrix of 7), `test-angular` (matrix of 2), and `build-and-push` (matrix of 9, `needs` both test jobs, gated to `push` events on `main` only). No deploy step in this workflow — that needs a real reachable k3s host and kubeconfig secret that don't exist yet.

**Tech Stack:** GitHub Actions (`ubuntu-latest` runners), `actions/setup-java@v4` (Temurin 21), `actions/setup-node@v4` (Node 22), `docker/setup-qemu-action@v3` + `docker/setup-buildx-action@v3` + `docker/login-action@v3` + `docker/build-push-action@v6` for the ARM64 builds, GHCR (`ghcr.io`) as the registry, authenticated via the workflow's own `GITHUB_TOKEN` — no new repo secret.

**Spec:** `docs/superpowers/specs/2026-09-21-ci-cd-pipeline-design.md`

## Global Constraints

- No `.github/workflows/` directory exists in this repo yet — this plan creates the first workflow of any kind.
- Trigger: `push` to `main` and `pull_request` targeting `main`. `build-and-push` additionally requires `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` — it must not run on PR builds.
- Java 21, Temurin distribution, Maven (`mvn -f services/<name> clean verify`) — matches every service's own `mvn clean verify` convention from `CLAUDE.md`. Testcontainers needs a real Docker daemon; `ubuntu-latest` ships one, no extra setup.
- Node 22, and `npm install -g npm@11.6.1` before `npm ci` in every Angular job step — this is not new: it's the exact fix already diagnosed and documented in `docs/PROGRESS.md` for the Dockerfiles' build stage (Node 22's bundled npm 10.9.8 can't read a lockfile npm 11.6.1 wrote), reused here because the root cause is identical on a GitHub-hosted runner.
- Images: `linux/arm64` only, never `linux/amd64` — matches `CLAUDE.md`'s standing convention.
- Tags: `latest` and `${{ github.sha }}`. No semantic versioning.
- Image names: `ghcr.io/davidsoldat/bridgepay-<name>` (owner hardcoded lowercase — GHCR requires lowercase, the real GitHub owner is `DavidSoldat`).
- No caching (`actions/cache`, `setup-java`'s `cache:`, `setup-node`'s `cache:`), no path-filtering, no deploy job — explicit non-goals in the spec, not omissions.
- Never guess a GitHub Action's tag/version or its input names — every action and input used in this plan (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`, `docker/setup-qemu-action@v3`, `docker/setup-buildx-action@v3`, `docker/login-action@v3`, `docker/build-push-action@v6`) is what this plan specifies; if `gh` or the Actions run log reports one doesn't exist or its inputs changed, stop and check the action's own repo/marketplace page rather than guessing a substitute.
- `gh` CLI is installed and authenticated as `DavidSoldat` (`repo`, `read:org`, `gist` scopes) — usable for opening/checking PRs. It does **not** yet have `read:packages`, needed in Task 4 for the GHCR manifest check; that task includes the scope refresh.
- The actual `git push`/PR-open/merge steps in this plan touch the real GitHub remote (`git@github.com:DavidSoldat/bridgepay.git`) — real, visible actions, not a sandboxed test repo. Task 4's merge-to-main step is the one that makes `build-and-push` actually publish images under the user's account; confirm with the user immediately before running that specific step, even though the overall branch+PR approach was already approved.

---

## File Structure

```
.github/workflows/ci.yml
```

One file. Three jobs added incrementally across Tasks 1–3 so each addition is independently verifiable against a real PR before the next one is layered on.

---

### Task 1: Workflow skeleton + `test-java` job, verified on a real PR

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: the workflow's trigger block (`on: push`/`pull_request` to `main`) and the `test-java` job name, both reused unchanged by Tasks 2 and 3.

- [ ] **Step 1: Create the branch**

```bash
cd /c/Users/david/Desktop/Projects/bridgepay
git checkout -b ci-cd-github-actions
```

- [ ] **Step 2: Write the workflow file**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test-java:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - applicant-service
          - application-service
          - credit-risk-engine
          - mock-credit-bureau
          - notifications-service
          - repayment-reconciliation-service
          - api-gateway
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: mvn clean verify
        run: mvn -f services/${{ matrix.service }} clean verify
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
Add CI workflow skeleton with the Java test matrix

First GitHub Actions workflow in the repo. Runs mvn clean verify for
each of the 7 Spring Boot services on every push/PR to main.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Push and open a PR**

```bash
git push -u origin ci-cd-github-actions
"/c/Program Files/GitHub CLI/gh.exe" pr create --title "Add CI/CD pipeline" --body "Adds .github/workflows/ci.yml: test matrix for all 9 app projects, plus ARM64 image build/push to GHCR on merge to main. See docs/superpowers/specs/2026-09-21-ci-cd-pipeline-design.md."
```

- [ ] **Step 5: Watch the real run and confirm it's green**

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: 7 `test-java` matrix entries, all passing. This is the first time any of these 7 services' test suites (several use real Testcontainers Postgres/Kafka) has run inside GitHub's own environment rather than this session's sandbox or a contributor's machine — a genuine, not assumed, check. If any entry fails, read that job's log via `gh run view --log-failed` before touching anything — a failure here is telling you something true about the runner environment (e.g. a Testcontainers/Docker quirk specific to GitHub's runners), not a flake to retry past.

---

### Task 2: Add the `test-angular` job

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nothing from Task 1's job (runs independently in parallel).
- Produces: the `test-angular` job name, consumed by Task 3's `needs:` list.

- [ ] **Step 1: Add the job**

Append to the `jobs:` block in `.github/workflows/ci.yml` (after `test-java`):

```yaml
  test-angular:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        app:
          - main-app
          - storefront
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
      - name: Install pinned npm and dependencies
        working-directory: frontend/${{ matrix.app }}
        run: |
          npm install -g npm@11.6.1
          npm ci
      - name: ng test
        working-directory: frontend/${{ matrix.app }}
        run: npx ng test --watch=false
```

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
Add the Angular test matrix to CI

Reuses the npm 11.6.1 pin fix already diagnosed for the Dockerfiles'
build stage - same root cause (Node 22's bundled npm can't read a
lockfile npm 11.6.1 wrote) applies on the GitHub-hosted runner too.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
git push
```

- [ ] **Step 3: Confirm both jobs run and pass on the same PR**

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: 7 `test-java` entries + 2 `test-angular` entries, all passing.

---

### Task 3: Add the `build-and-push` job, verified as correctly skipped on the PR

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `test-java` and `test-angular` job names from Tasks 1–2, in its `needs:` list.

- [ ] **Step 1: Add the job**

Append to the `jobs:` block (after `test-angular`):

```yaml
  build-and-push:
    needs: [test-java, test-angular]
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    strategy:
      matrix:
        include:
          - directory: services/applicant-service
            image: bridgepay-applicant-service
          - directory: services/application-service
            image: bridgepay-application-service
          - directory: services/credit-risk-engine
            image: bridgepay-credit-risk-engine
          - directory: services/mock-credit-bureau
            image: bridgepay-mock-credit-bureau
          - directory: services/notifications-service
            image: bridgepay-notifications-service
          - directory: services/repayment-reconciliation-service
            image: bridgepay-repayment-reconciliation-service
          - directory: services/api-gateway
            image: bridgepay-api-gateway
          - directory: frontend/main-app
            image: bridgepay-main-app
          - directory: frontend/storefront
            image: bridgepay-storefront
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: ${{ matrix.directory }}
          platforms: linux/arm64
          push: true
          tags: |
            ghcr.io/davidsoldat/${{ matrix.image }}:latest
            ghcr.io/davidsoldat/${{ matrix.image }}:${{ github.sha }}
```

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
Add the build-and-push job for ARM64 images to GHCR

Gated to push events on main only (if: github.event_name == 'push' &&
github.ref == 'refs/heads/main') - a PR's checks should show this job
present but skipped, never actually publishing from a PR build.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
git push
```

- [ ] **Step 3: Confirm the job is present but skipped on this PR, not run**

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr checks
```

Expected: `test-java` (×7) and `test-angular` (×2) entries all passing, and `build-and-push` entries either absent from the checks list or shown as `skipped` — confirming the `if:` condition correctly excludes PR builds. If any `build-and-push` entry shows as having actually run on this PR, stop — the `if:` condition is wrong and must be fixed before merging, since merging would otherwise be the first time that gating is ever tested.

---

### Task 4: Merge to `main` and verify the real GHCR publish

**Files:** none — this task is verification only, against the real repo.

**Interfaces:** none — terminal task.

- [ ] **Step 1: Confirm with the user before merging**

This is the step called out in Global Constraints: merging makes `build-and-push` actually run against `main` and publish 9 real container images to GHCR under the user's account. Ask for explicit go-ahead immediately before running the merge command in Step 2, even though the branch+PR approach itself was already approved earlier in this conversation.

- [ ] **Step 2: Merge the PR**

```bash
"/c/Program Files/GitHub CLI/gh.exe" pr merge --squash --delete-branch
```

- [ ] **Step 3: Watch the real run on `main`**

```bash
"/c/Program Files/GitHub CLI/gh.exe" run list --branch main --limit 1
"/c/Program Files/GitHub CLI/gh.exe" run watch
```

Expected: all three jobs run this time (not skipped), `build-and-push` matrix has 9 green entries.

- [ ] **Step 4: Confirm all 9 packages actually exist in GHCR**

```bash
"/c/Program Files/GitHub CLI/gh.exe" api /user/packages?package_type=container --jq '.[].name'
```

Expected: 9 names, each `bridgepay-<name>` matching the image list in Task 3's matrix.

- [ ] **Step 5: Confirm the published platform is genuinely `linux/arm64`**

`docker buildx imagetools inspect` needs registry read access; the current `gh` token lacks the `read:packages` scope, so refresh it first (interactive — ask the user to complete it, same as the original `gh auth login`):

```bash
"/c/Program Files/GitHub CLI/gh.exe" auth refresh -h github.com -s read:packages
"/c/Program Files/GitHub CLI/gh.exe" auth token | docker login ghcr.io -u DavidSoldat --password-stdin
docker buildx imagetools inspect ghcr.io/davidsoldat/bridgepay-applicant-service:latest
```

Expected: the output's `Platform:` line reads `linux/arm64`, not `linux/amd64` — this is the one thing a misconfigured `platforms:` input would get silently wrong (the build would still succeed and push, just for the wrong architecture), so it's worth checking directly rather than assuming the `platforms: linux/arm64` line in Task 3 did what it says.

- [ ] **Step 6: Update the progress tracker**

Add a `[x]` entry to `docs/PROGRESS.md`'s Done section (after the most recent entry, before `## Next, in order`) summarizing what was built and verified — follow this file's own established style (what was built, what was verified for real and how, what's explicitly not done yet). Mention: this is the first CI of any kind in the project; the k3s manifests that will reference these GHCR image tags are the deliberate next follow-up, not bundled into this task. Remove `k3s manifests, GitHub Actions CI/CD with ARM64 image builds` from `## Next, in order` and replace it with just the k3s-manifests half, since the CI/CD half is now done.

```bash
git add docs/PROGRESS.md
git commit -m "$(cat <<'EOF'
Record the CI/CD pipeline in PROGRESS.md

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```
