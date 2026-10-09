# BridgePay on Kubernetes

Plain manifests + Kustomize (built into `kubectl`). `base/` is written for the
real target (k3s on an ARM64 OCI instance, images from GHCR). `overlays/local/`
runs it on a local k3d cluster with locally built images and dev secrets.

```
base/platform/    Postgres, Kafka (KRaft), Redis, Keycloak      namespace: platform
base/bridgepay/   7 Spring Boot services, 2 frontends, Ingress   namespace: bridgepay
overlays/local/   :local image tags, dev Secrets
overlays/prod/    the public demo on the OCI VM (see "Production" below)
prod-host/        the VM's forced-command deploy script
```

## Run locally on k3d

Needs Docker, `kubectl` and [k3d](https://k3d.io). Run from the repo root.

```bash
# --api-port: keeps the kubeconfig on 127.0.0.1 (the default host.docker.internal
#   address can be firewalled on Windows).
# fail-cgroupv1=false: Docker Desktop on WSL2 may still run cgroup v1, which
#   Kubernetes 1.35+ refuses by default. Harmless on cgroup v2 hosts.
k3d cluster create bridgepay --api-port 127.0.0.1:6550 -p "80:80@loadbalancer" \
  --k3s-arg "--kubelet-arg=fail-cgroupv1=false@server:*" --wait

# build and load images (native arch; CI builds linux/arm64 for the real host)
for s in applicant-service application-service credit-risk-engine mock-credit-bureau \
         repayment-reconciliation-service notifications-service api-gateway; do
  docker build -t bridgepay-$s:local services/$s
done
docker build -t bridgepay-main-app:local frontend/main-app
docker build -t bridgepay-storefront:local frontend/storefront
docker build -t bridgepay-landing:local frontend/landing
k3d image import -c bridgepay $(docker images --format '{{.Repository}}:{{.Tag}}' | grep '^bridgepay-.*:local$')

# Paddle keys come from the repo-root .env (gitignored). Placeholders are fine;
# real sandbox keys make repayment plans get created in Paddle.
[ -f .env ] || cp .env.example .env

# The Keycloak/Postgres ConfigMaps read files from infrastructure/keycloak and
# infrastructure/postgres-init, outside this folder, hence the load restrictor flag.
kubectl kustomize --load-restrictor LoadRestrictionsNone infrastructure/k8s/overlays/local | kubectl apply -f -
kubectl get pods -A -w
```

First start takes a few minutes: Keycloak builds itself, and the services
restart until Postgres and Kafka are up.

| URL | What |
|---|---|
| http://localhost | Landing page (demo logins are shown only by `overlays/local`) |
| http://shop.localhost | Storefront |
| http://app.localhost | Main app |
| http://auth.localhost | Keycloak (admin: `admin` / `admin`) |

Demo users: `shopper1`, `ops1`, `merchant1` (password = username).

Why `*.localhost`: browsers resolve it to loopback on their own, and treat it
as a secure context even over plain HTTP. Keycloak's JS adapter needs that,
because PKCE uses `crypto.subtle`, which browsers only expose to secure
contexts. A plain-HTTP hostname like `app.localtest.me` fails with "Web Crypto
API is not available". The production overlay uses real HTTPS instead. For
non-browser tools that don't resolve `*.localhost` themselves, add this to
your hosts file: `127.0.0.1 app.localhost shop.localhost auth.localhost`
(plain `localhost`, the landing page, already resolves everywhere)

Tear down: `k3d cluster delete bridgepay`.

## How auth works in the cluster

Browsers log in at `auth.localhost`, so tokens carry that issuer. Inside a
pod that hostname points at the pod itself, so services check the issuer
against the public URL (`KEYCLOAK_ISSUER_URI`) but fetch signing keys from the
in-cluster Service (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`).
Nothing runs under the `local` Spring profile here. Every service uses its
real `SecurityConfig`. The frontends read the Keycloak URL from `/config.json`,
which a ConfigMap overrides.

## Paddle sandbox: first payment + webhooks

Shoppers pay installment 1 through Paddle's overlay checkout (the storefront opens it after approval,
or from "Action required" on `/account`); that saves the card, and the Paddle subscription charges the
rest weekly. Paddle reports back via webhooks, which need a public URL.

One-time, in the Paddle **sandbox** dashboard:
1. Developer tools → Authentication → create a **client-side token** (`test_…`). Put it in
   `frontend/storefront/public/config.json` and the `frontend-config` literal in
   `base/bridgepay/kustomization.yaml` as `paddleClientToken`. It's public by design.
2. Checkout → Checkout settings → **Default payment link**: `http://shop.localhost/account`.

Each session (the quick-tunnel URL changes every run):
```sh
cloudflared tunnel --url http://localhost:80 --http-host-header app.localhost
```
3. Developer tools → Notifications → destination `https://<random>.trycloudflare.com/webhooks/paddle`,
   events `transaction.completed`, `transaction.payment_failed`, `subscription.past_due`,
   `subscription.canceled`. Put its secret key in the repo-root `.env` as `PADDLE_WEBHOOK_SECRET`, re-apply
   the overlay (command above), and `kubectl -n bridgepay rollout restart deploy/repayment-reconciliation-service`.

Check it's reachable (reached the service, bad signature rejected → `400`):
```sh
curl -s -o /dev/null -w "%{http_code}
" -X POST https://<random>.trycloudflare.com/webhooks/paddle   -H "Paddle-Signature: ts=1;h1=bad" -d '{}'
```
Sandbox test card: `4242 4242 4242 4242`, any future expiry, CVC `100`.

## Production (OCI VM)

A public demo on one OCI `VM.Standard.A1.Flex` (4 OCPU / 24 GB, Ubuntu 24.04 aarch64), single-node
k3s. Everything stays inside OCI Always Free: no load balancer (k3s ServiceLB binds 80/443 on the
node), no block volumes (`local-path` on the boot disk), no reserved IP, no backups, no OCI registry.

| Host | Serves |
|---|---|
| `bridgepay.duckdns.org` | landing |
| `app.bridgepay.duckdns.org` | main-app (ops + merchant), `/webhooks/paddle` |
| `shop.bridgepay.duckdns.org` | storefront |
| `auth.bridgepay.duckdns.org` | Keycloak (`/realms/bridgepay/` and `/resources/` only; no `/admin`, no master realm) |

DNS is DuckDNS (`bridgepay` → the VM's public IP; subdomains resolve too). TLS is cert-manager +
Let's Encrypt (HTTP-01, ClusterIssuer `letsencrypt`), one cert per host.

### Deploys

Every green push to `main` deploys: CI's `deploy` job (environment `production`, `main` only) renders
`overlays/prod` with every image pinned to the commit SHA and pipes the YAML over SSH (port 2222) to
the `deploy` user. Its key is `restrict,command="/usr/local/bin/bridgepay-deploy"` in
`authorized_keys`: it can't get a shell, a PTY or a port forward; it only runs
[`prod-host/bridgepay-deploy`](prod-host/bridgepay-deploy), which `kubectl apply`s stdin and waits for
every rollout. Its kubeconfig is a ServiceAccount bound to `admin` in `platform` and `bridgepay` only
(both namespaces enforce Pod Security `baseline`, so it can't run privileged or hostPath pods).

Rollback: re-run an older green run's `deploy` job. The deploy never prunes; delete removed
resources by hand.

### One-time host setup (already done)

1. Firewall (`/etc/iptables/rules.v4`): INPUT accepts lo, established, ICMP, 2222, 80, 443, and the
   k3s pod/service ranges `10.42.0.0/16` + `10.43.0.0/16` before OCI's catch-all REJECT; FORWARD
   ACCEPT (pods are routed); OCI's `InstanceServices` chain untouched. The OCI security list allows
   ingress on 80, 443, 2222 only (that's what keeps NodePorts private).
2. k3s (`--write-kubeconfig-mode 600`), cert-manager, ClusterIssuer `letsencrypt`.
3. Namespaces `platform` and `bridgepay` created by hand with
   `pod-security.kubernetes.io/enforce=baseline` (CI can't create namespaces).
4. `deploy` user, ServiceAccount `kube-system/deploy` + RoleBindings, `/home/deploy/.kube/config`.
   GitHub environment `production`: secrets `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS` (pinned host key),
   `DEPLOY_HOST`.
5. sshd: `PermitRootLogin no`; rpcbind disabled.

### Secrets (created by hand on the VM, never in git)

| Secret | Keys |
|---|---|
| `platform/platform-credentials` | `POSTGRES_USER`, `POSTGRES_PASSWORD`, `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD` |
| `bridgepay/bridgepay-credentials` | `DB_USERNAME`, `DB_PASSWORD` (same user/password as Postgres) |
| `bridgepay/paddle-credentials` | `PADDLE_API_KEY`, `PADDLE_WEBHOOK_SECRET` (sandbox) |

Generate values on the VM so they never leave it, e.g.
`PG=$(openssl rand -base64 32 | tr -d '/+=')`, then
`sudo k3s kubectl -n platform create secret generic ... --from-literal=POSTGRES_PASSWORD="$PG"`.
To rotate one: `kubectl create secret ... --dry-run=client -o yaml | kubectl apply -f -`, then
`kubectl -n <ns> rollout restart deploy/<name>` (Secrets are read at startup). Postgres only reads
its password on first init, so rotating it also needs `ALTER USER`.

repayment-reconciliation refuses to start in production (`PADDLE_REQUIRE_CREDENTIALS=true`) if
either Paddle value is missing, blank or `changeme`.

Paddle's sandbox notification destination points at
`https://app.bridgepay.duckdns.org/webhooks/paddle`.

### Keycloak admin

Not on the Ingress. Over SSH:

```sh
ssh -p 2222 -L 8080:localhost:8080 ubuntu@<vm>
sudo k3s kubectl -n platform port-forward svc/keycloak 8080:8080   # on the VM
# then http://localhost:8080/admin  (KC_HOSTNAME_ADMIN is http://localhost:8080)
sudo k3s kubectl -n platform get secret platform-credentials -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d
```

The realm export has no signing keys (the repo is public; CI fails if one reappears), so Keycloak
generates its own on first import.

### Public demo hygiene

The landing page publishes the demo logins. Demo users can't change their password or profile
(no account roles), and the storefront signup says not to enter real details. A CronJob,
`bridgepay/demo-reset`, runs at 03:00 UTC: it scales the five database-backed services to 0, drops
their schemas (`applicant`, `application`, `repayment`, `notifications`, `audit`), flushes Redis and
scales back up; Flyway re-creates the schemas and the demo seed. Run it now:

```sh
sudo k3s kubectl -n bridgepay create job --from=cronjob/demo-reset demo-reset-manual
```

If it fails part-way, the services may stay at 0:
`sudo k3s kubectl -n bridgepay scale deploy applicant-service application-service repayment-reconciliation-service notifications-service api-gateway --replicas=1`.
