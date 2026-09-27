# BridgePay on Kubernetes

Plain manifests + Kustomize (built into `kubectl`). `base/` is written for the
real target (k3s on an ARM64 OCI instance, images from GHCR). `overlays/local/`
runs it on a local k3d cluster with locally built images and dev secrets.

```
base/platform/    Postgres, Kafka (KRaft), Redis, Keycloak      namespace: platform
base/bridgepay/   7 Spring Boot services, 2 frontends, Ingress   namespace: bridgepay
overlays/local/   :local image tags, dev Secrets
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

## Not here yet

A production overlay for the OCI instance (real hostnames, TLS via
cert-manager, a GHCR pull secret, real Secrets, the live Paddle client token;
the `/webhooks/paddle` route is in `base/` and just follows the real app host)
and the CI deploy step. They wait until that host exists.

The Keycloak realm ConfigMap (`keycloak-realm`) is supplied by each overlay,
not by `base/`, because the dev realm has demo users with known passwords.
A production overlay must provide its own realm before Keycloak's first boot:
Keycloak skips importing a realm that already exists.
