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

# The Keycloak/Postgres ConfigMaps read files from infrastructure/keycloak and
# infrastructure/postgres-init, outside this folder, hence the load restrictor flag.
kubectl kustomize --load-restrictor LoadRestrictionsNone infrastructure/k8s/overlays/local | kubectl apply -f -
kubectl get pods -A -w
```

First start takes a few minutes: Keycloak builds itself, and the services
restart until Postgres and Kafka are up.

| URL | What |
|---|---|
| http://shop.localtest.me | Storefront |
| http://app.localtest.me | Main app |
| http://auth.localtest.me | Keycloak (admin: `admin` / `admin`) |

Demo users: `shopper1`, `ops1`, `merchant1` (password = username).

`*.localtest.me` resolves to 127.0.0.1 via public DNS. If it doesn't on your
network, add this to your hosts file:
`127.0.0.1 app.localtest.me shop.localtest.me auth.localtest.me`

Tear down: `k3d cluster delete bridgepay`.

## How auth works in the cluster

Browsers log in at `auth.localtest.me`, so tokens carry that issuer. Inside a
pod that hostname points at the pod itself, so services check the issuer
against the public URL (`KEYCLOAK_ISSUER_URI`) but fetch signing keys from the
in-cluster Service (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`).
Nothing runs under the `local` Spring profile here. Every service uses its
real `SecurityConfig`. The frontends read the Keycloak URL from `/config.json`,
which a ConfigMap overrides.

## Not here yet

A production overlay for the OCI instance (real hostnames, TLS via
cert-manager, a GHCR pull secret, real Secrets, the public Paddle webhook
route) and the CI deploy step. They wait until that host exists.
