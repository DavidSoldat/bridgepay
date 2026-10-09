# BridgePay on Kubernetes

Plain manifests + Kustomize (built into `kubectl`), deployed to single-node k3s on an ARM64 OCI
instance. Images come from GHCR, built for `linux/arm64` by CI.

```
base/platform/    Postgres, Kafka (KRaft), Redis, Keycloak         namespace: platform
base/bridgepay/   7 Spring Boot services, 3 frontends, Ingress      namespace: bridgepay
overlays/prod/    the public demo: hosts, TLS, security headers, network policies, nightly reset
prod-host/        the VM's forced-command deploy script
```

Render: `kubectl kustomize --load-restrictor LoadRestrictionsNone infrastructure/k8s/overlays/prod`
(the Keycloak and Postgres ConfigMaps read files from `infrastructure/keycloak` and
`infrastructure/postgres-init`, outside this folder).

## How auth works in the cluster

Browsers log in at `auth.bridgepay.duckdns.org`, so tokens carry that issuer. Services check the
issuer against that public URL (`KEYCLOAK_ISSUER_URI`) but fetch the signing keys from the in-cluster
Keycloak Service (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`), so validation never leaves
the cluster. Every service runs its real `SecurityConfig`. The frontends read the Keycloak URL from
`/config.json`, which a ConfigMap overrides.

## Paddle (sandbox)

Shoppers pay installment 1 through Paddle's overlay checkout; that saves the card, and the Paddle
subscription charges the rest weekly. Paddle reports back through webhooks to
`https://app.bridgepay.duckdns.org/webhooks/paddle` (an Exact-path Ingress rule straight to
repayment-reconciliation), with the events `transaction.completed`, `transaction.payment_failed`,
`subscription.past_due` and `subscription.canceled`. The destination's secret key is
`PADDLE_WEBHOOK_SECRET` in the `paddle-credentials` Secret.

The client-side token (`test_…`) in the `frontend-config` ConfigMap is public by design.

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
