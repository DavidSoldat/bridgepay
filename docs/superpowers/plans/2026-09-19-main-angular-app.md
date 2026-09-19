# Main Angular App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `frontend/main-app` — the role-gated Angular app serving BridgePay's ops review dashboard and merchant payout dashboard, per the approved design.

**Architecture:** Angular 21 (standalone, signals), Tailwind CSS v4, `keycloak-angular@21.0.0` wrapping the `main-app` Keycloak client. Two role-gated route trees (`/ops`, `/merchant`) behind functional guards reading realm roles from the JWT. Plain `HttpClient` + `toSignal()` against the API Gateway — no NgRx, no `resource()`/`httpResource` (developer-preview in this Angular version).

**Tech Stack:** Angular 21.2.x, TypeScript, Tailwind CSS v4 (`ng add tailwindcss`), `keycloak-angular@21.0.0` + `keycloak-js`, Vitest (Angular's default `@angular/build:unit-test` builder — verified empirically, not Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-09-19-main-angular-app-design.md`

## Global Constraints

- Angular **21** specifically, not 22 — pin every `npx @angular/cli` invocation to `@21` so npm's `latest` tag never silently resolves to 22 (which needs a Node version this machine doesn't have).
- `keycloak-angular` **must be installed at exactly `21.0.0`** (`npm install keycloak-angular@21.0.0 keycloak-js`) — its own `latest` tag is `22.0.0`, which peer-depends on `@angular/core@^22` and would break the Angular 21 pin.
- Angular 21's file-naming convention (verified empirically, not assumed): `ng generate` produces **no** `.component.`/`.service.`/`.guard.` infix — a component named `review-queue` is `review-queue.ts`/`.html`/`.css`/`.spec.ts` with class `ReviewQueue`; a service named `applications` is `applications.ts` with class `Applications`; a guard named `ops` is `ops-guard.ts` with export `opsGuard`. Every file path and class name in this plan already reflects this — do not "correct" them to the old `*.component.ts`/`AppComponent`-style naming.
- The default test builder is **Vitest** (`@angular/build:unit-test`), run via `ng test`, using a Jasmine-compatible `describe`/`it`/`expect` surface — but do not assume Jasmine-only globals (`jasmine.createSpy`, `expectAsync`) are present; every test in this plan uses plain manual mocks and `await` instead, to stay portable regardless of exactly how far the compatibility shim goes.
- No gradients, no bright saturated fills, no rounded "SaaS cards," no drop shadows anywhere — the full palette/type/layout tokens are in the spec's Visual Design section and get set up once in Task 3; every later task's templates use only the resulting Tailwind utilities (`bg-paper`, `text-ink`, `text-ink-muted`, `border-hairline`, `bg-accent`/`text-accent`, `bg-status-review`, `bg-status-declined`, `font-mono` for every number/ID).
- All numbers, dates, and IDs render in `font-mono`; all prose renders in the default `font-sans` — never mixed.
- No OpenAPI/codegen — TypeScript interfaces are hand-mirrored against the real backend DTOs added in commit `3886cce`.
- No runtime-configurable API base URL — a build-time `environment.ts` value, hardcoded to `http://localhost:8086` (the API Gateway's published port on this machine), for both `environment.ts` and `environment.development.ts`. This is a known, named gap before any real k3s deployment — do not try to solve it in this plan.

---

## File Structure

```
frontend/main-app/
  (CLI-generated: angular.json, package.json, tsconfig*.json, .postcssrc.json,
   public/favicon.ico, src/main.ts, src/index.html)
  Dockerfile
  src/
    styles.css                              # design tokens + Tailwind import
    environments/
      environment.ts
      environment.development.ts
    app/
      app.ts / .html / .css / .spec.ts       # shell: sidebar nav + top bar + <router-outlet>
      app.config.ts                          # providers: router, HTTP, Keycloak, interceptor
      app.routes.ts
      core/
        auth.ts / .spec.ts                   # Auth service: roles()/merchantId()/username() signals
        ops-guard.ts / .spec.ts
        merchant-guard.ts / .spec.ts
        root-redirect-guard.ts / .spec.ts
      shared/
        models/
          page.ts                            # generic Page<T> matching Spring Data's PageImpl JSON
          application.ts                      # ApplicationResponse, ScoreFactor
          merchant-payout.ts                  # MerchantPayoutResponse
      no-access/
        no-access.ts / .html / .css / .spec.ts
      ops/
        applications.ts / .spec.ts           # service
        review-queue/review-queue.ts / .html / .css / .spec.ts
        review-detail/review-detail.ts / .html / .css / .spec.ts
      merchant/
        payouts.ts / .spec.ts                # service
        payout-ledger/payout-ledger.ts / .html / .css / .spec.ts
```

- `core/` — cross-cutting auth/routing concerns, nothing view-specific.
- `shared/models/` — plain TypeScript interfaces, no logic.
- `ops/` and `merchant/` — one folder per role-gated feature area; each owns its service and its view component(s).

---

### Task 1: Scaffold the app, Tailwind, environments

This is the highest-setup-risk task: proves the whole toolchain (Angular 21, this machine's Node/npm, Tailwind v4) works together before any real code is written.

**Files:**
- Create: `frontend/main-app/` (entire CLI-generated tree)
- Modify: `frontend/main-app/src/environments/environment.ts`
- Modify: `frontend/main-app/src/environments/environment.development.ts`

**Interfaces:**
- Produces: `environment.gatewayBaseUrl: string`, consumed by every later task's HTTP services and the Keycloak interceptor config.

- [ ] **Step 1: Scaffold the Angular 21 project**

From the repo root:

```bash
mkdir -p frontend
cd frontend
npx -y @angular/cli@21 new main-app --routing --style=css --skip-git --defaults
```

`--skip-git` because this lives inside the bridgepay monorepo, not its own repo. `--defaults` suppresses any interactive prompts (SSR, AI-tooling questions, etc.) so this runs non-interactively. This installs dependencies automatically (no `--skip-install`).

- [ ] **Step 2: Add Tailwind CSS**

```bash
cd frontend/main-app
npx ng add tailwindcss --skip-confirmation
```

Verified real output for this exact version: installs `tailwindcss@4.x`, creates `.postcssrc.json` (`{"plugins": {"@tailwindcss/postcss": {}}}`), and appends `@import 'tailwindcss';` to `src/styles.css`. Task 3 rewrites `styles.css` further — leave it as `ng add` produces it for now.

- [ ] **Step 3: Generate environment files**

```bash
npx ng generate environments
```

This creates `src/environments/environment.ts` and `environment.development.ts` (both starting as `export const environment = {};`) and wires `fileReplacements` into `angular.json` automatically.

- [ ] **Step 4: Fill in both environment files**

`src/environments/environment.ts`:

```typescript
export const environment = {
  gatewayBaseUrl: 'http://localhost:8086',
};
```

`src/environments/environment.development.ts` — identical content for now (no dev-specific proxy; both point at the same locally-published gateway port):

```typescript
export const environment = {
  gatewayBaseUrl: 'http://localhost:8086',
};
```

- [ ] **Step 5: Verify the build and default test suite**

```bash
npx ng build
npx ng test --watch=false
```

Expected: `ng build` succeeds (writes to `dist/main-app/`); `ng test --watch=false` runs the CLI-generated `app.spec.ts` (2 tests) and passes. Note the exact output directory name printed by `ng build` (e.g. `dist/main-app/browser`) — Task 7's Dockerfile needs it exactly.

- [ ] **Step 6: Commit**

```bash
cd ../..
git add frontend/main-app
git commit -m "Scaffold frontend/main-app: Angular 21, Tailwind CSS v4, environment config"
```

---

### Task 2: Keycloak auth, role guards, routing skeleton

**Files:**
- Modify: `frontend/main-app/package.json` (via `npm install`)
- Create: `frontend/main-app/src/app/core/auth.ts` (+ `.spec.ts`)
- Create: `frontend/main-app/src/app/core/ops-guard.ts` (+ `.spec.ts`)
- Create: `frontend/main-app/src/app/core/merchant-guard.ts` (+ `.spec.ts`)
- Create: `frontend/main-app/src/app/core/root-redirect-guard.ts` (+ `.spec.ts`)
- Create: `frontend/main-app/src/app/no-access/no-access.ts` (+ `.html`, `.css`, `.spec.ts`)
- Create: `frontend/main-app/src/app/ops/review-queue/review-queue.ts` (+ `.html`, `.css`, `.spec.ts`) — placeholder, filled in by Task 4
- Create: `frontend/main-app/src/app/merchant/payout-ledger/payout-ledger.ts` (+ `.html`, `.css`, `.spec.ts`) — placeholder, filled in by Task 6
- Modify: `frontend/main-app/src/app/app.config.ts`
- Modify: `frontend/main-app/src/app/app.routes.ts`

**Interfaces:**
- Consumes: `environment.gatewayBaseUrl` (Task 1).
- Produces: `Auth` (class, `providedIn: 'root'`) with `roles(): Signal<string[]>`, `merchantId(): Signal<string | null>`, `username(): Signal<string | null>`, `hasRole(role: string): boolean`, `logout(): void` — used by every later task. `opsGuard`/`merchantGuard`: `CanActivateFn`. Routes `/ops`, `/merchant`, `/no-access` exist and are guarded.

- [ ] **Step 1: Install keycloak-angular, pinned**

```bash
cd frontend/main-app
npm install keycloak-angular@21.0.0 keycloak-js
```

- [ ] **Step 2: Write `Auth`**

`src/app/core/auth.ts`:

```typescript
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  private readonly tokenClaims = signal<Record<string, unknown> | undefined>(undefined);

  readonly roles = computed<string[]>(() => {
    const realmAccess = this.tokenClaims()?.['realm_access'] as { roles?: string[] } | undefined;
    return realmAccess?.roles ?? [];
  });

  readonly merchantId = computed<string | null>(() => {
    return (this.tokenClaims()?.['merchantId'] as string | undefined) ?? null;
  });

  readonly username = computed<string | null>(() => {
    return (this.tokenClaims()?.['preferred_username'] as string | undefined) ?? null;
  });

  constructor() {
    effect(() => {
      const event = this.keycloakEvent();
      if (
        event.type === KeycloakEventType.Ready ||
        event.type === KeycloakEventType.AuthSuccess ||
        event.type === KeycloakEventType.AuthRefreshSuccess
      ) {
        this.tokenClaims.set(this.keycloak.tokenParsed);
      }
    });
  }

  hasRole(role: string): boolean {
    return this.roles().includes(role);
  }

  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
```

`import Keycloak from 'keycloak-js'` used directly as the `inject()` token relies on `provideKeycloak` (Step 6) registering the real `keycloak-js` `Keycloak` class as an Angular provider — this is the documented pattern (`keycloak-angular`'s own README shows `constructor(private keycloak: Keycloak)`). If `inject(Keycloak)` throws a `NullInjectorError` once Step 6's `provideKeycloak` is wired in, check `keycloak-angular`'s current README/source for whether it exports its own injection token for the raw instance instead of relying on class-based DI — don't guess a workaround, confirm against the real library first.

- [ ] **Step 3: Write `auth.spec.ts`**

```typescript
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { Auth } from './auth';

describe('Auth', () => {
  function setup(tokenParsed: Record<string, unknown>) {
    const fakeKeycloak = { tokenParsed } as unknown as Keycloak;
    TestBed.configureTestingModule({
      providers: [
        { provide: Keycloak, useValue: fakeKeycloak },
        {
          provide: KEYCLOAK_EVENT_SIGNAL,
          useValue: signal({ type: KeycloakEventType.Ready, args: true }),
        },
      ],
    });
    return TestBed.inject(Auth);
  }

  it('reads realm roles from the token', () => {
    const auth = setup({ realm_access: { roles: ['ops'] } });
    expect(auth.roles()).toEqual(['ops']);
    expect(auth.hasRole('ops')).toBe(true);
    expect(auth.hasRole('merchant')).toBe(false);
  });

  it('reads the merchantId claim when present', () => {
    const auth = setup({ realm_access: { roles: ['merchant'] }, merchantId: 'm-1' });
    expect(auth.merchantId()).toBe('m-1');
  });

  it('returns null merchantId when the claim is absent', () => {
    const auth = setup({ realm_access: { roles: ['ops'] } });
    expect(auth.merchantId()).toBeNull();
  });
});
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS (3/3 new tests, plus the 2 pre-existing `app.spec.ts` tests still passing — 5 total).

- [ ] **Step 5: Write the guards**

`src/app/core/ops-guard.ts`:

```typescript
import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

export const isOpsRole = async (
  _route: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean | UrlTree> => {
  const { authenticated, grantedRoles } = authData;
  if (authenticated && grantedRoles.realmRoles.includes('ops')) {
    return true;
  }
  return inject(Router).parseUrl('/no-access');
};

export const opsGuard: CanActivateFn = createAuthGuard(isOpsRole);
```

`src/app/core/merchant-guard.ts` — identical shape, `'merchant'` role:

```typescript
import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

export const isMerchantRole = async (
  _route: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean | UrlTree> => {
  const { authenticated, grantedRoles } = authData;
  if (authenticated && grantedRoles.realmRoles.includes('merchant')) {
    return true;
  }
  return inject(Router).parseUrl('/no-access');
};

export const merchantGuard: CanActivateFn = createAuthGuard(isMerchantRole);
```

`src/app/core/root-redirect-guard.ts` — sends `/` to whichever dashboard the token's role holds:

```typescript
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

export const rootRedirectGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (auth.hasRole('ops')) return router.parseUrl('/ops');
  if (auth.hasRole('merchant')) return router.parseUrl('/merchant');
  return router.parseUrl('/no-access');
};
```

- [ ] **Step 6: Write the guard tests**

`src/app/core/ops-guard.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthGuardData } from 'keycloak-angular';
import { isOpsRole } from './ops-guard';

describe('isOpsRole', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  it('allows an authenticated ops token', async () => {
    const authData = {
      authenticated: true,
      grantedRoles: { realmRoles: ['ops'], resourceRoles: {} },
    } as AuthGuardData;

    const result = await TestBed.runInInjectionContext(() =>
      isOpsRole({} as any, {} as any, authData),
    );

    expect(result).toBe(true);
  });

  it('redirects a non-ops token to /no-access', async () => {
    const authData = {
      authenticated: true,
      grantedRoles: { realmRoles: ['merchant'], resourceRoles: {} },
    } as AuthGuardData;

    const result = await TestBed.runInInjectionContext(() =>
      isOpsRole({} as any, {} as any, authData),
    );

    expect(result?.toString()).toBe('/no-access');
  });
});
```

`src/app/core/merchant-guard.spec.ts` — same shape, asserting `isMerchantRole` with `'merchant'`/`'ops'` swapped.

`src/app/core/root-redirect-guard.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { rootRedirectGuard } from './root-redirect-guard';
import { Auth } from './auth';

describe('rootRedirectGuard', () => {
  function setup(roles: string[]) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { hasRole: (r: string) => roles.includes(r) } },
      ],
    });
  }

  it('redirects an ops token to /ops', () => {
    setup(['ops']);
    const result = TestBed.runInInjectionContext(() => rootRedirectGuard({} as any, {} as any));
    expect(result?.toString()).toBe('/ops');
  });

  it('redirects a merchant token to /merchant', () => {
    setup(['merchant']);
    const result = TestBed.runInInjectionContext(() => rootRedirectGuard({} as any, {} as any));
    expect(result?.toString()).toBe('/merchant');
  });

  it('redirects a token with neither role to /no-access', () => {
    setup([]);
    const result = TestBed.runInInjectionContext(() => rootRedirectGuard({} as any, {} as any));
    expect(result?.toString()).toBe('/no-access');
  });
});
```

- [ ] **Step 7: Run the guard tests to verify they pass**

Run: `npx ng test --watch=false`
Expected: PASS, all guard tests green.

- [ ] **Step 8: Generate the placeholder views and no-access page**

```bash
npx ng generate component ops/review-queue
npx ng generate component merchant/payout-ledger
npx ng generate component no-access
```

Replace the generated `no-access.html` with:

```html
<div class="max-w-sm mx-auto mt-24 text-center">
  <h1 class="text-lg font-medium mb-2">No access</h1>
  <p class="text-sm text-ink-muted mb-6">
    Your account doesn't have access to this app. Contact an administrator if you think this is a mistake.
  </p>
  <button (click)="auth.logout()" class="text-sm text-accent hover:underline">Log out</button>
</div>
```

Replace `no-access.ts` with:

```typescript
import { Component, inject } from '@angular/core';
import { Auth } from '../core/auth';

@Component({
  selector: 'app-no-access',
  imports: [],
  templateUrl: './no-access.html',
  styleUrl: './no-access.css',
})
export class NoAccess {
  protected readonly auth = inject(Auth);
}
```

Leave `review-queue.ts`/`.html` and `payout-ledger.ts`/`.html` exactly as `ng generate` produced them for now — Tasks 4 and 6 replace their contents.

- [ ] **Step 9: Wire routes**

`src/app/app.routes.ts`:

```typescript
import { Routes } from '@angular/router';
import { ReviewQueue } from './ops/review-queue/review-queue';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { NoAccess } from './no-access/no-access';
import { opsGuard } from './core/ops-guard';
import { merchantGuard } from './core/merchant-guard';
import { rootRedirectGuard } from './core/root-redirect-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [rootRedirectGuard], children: [] },
  { path: 'ops', component: ReviewQueue, canActivate: [opsGuard] },
  { path: 'merchant', component: PayoutLedger, canActivate: [merchantGuard] },
  { path: 'no-access', component: NoAccess },
];
```

(Task 5 adds an `ops/:id` route here for the review-detail view.)

- [ ] **Step 10: Wire `provideKeycloak` and the bearer-token interceptor**

`src/app/app.config.ts`:

```typescript
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  provideKeycloak,
  includeBearerTokenInterceptor,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
} from 'keycloak-angular';

import { routes } from './app.routes';

const gatewayUrlCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: /^http:\/\/localhost:8086(\/.*)?$/i,
  bearerPrefix: 'Bearer',
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideKeycloak({
      config: {
        url: 'http://localhost:8180',
        realm: 'bridgepay',
        clientId: 'main-app',
      },
      initOptions: {
        onLoad: 'login-required',
        pkceMethod: 'S256',
      },
    }),
    {
      provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
      useValue: [gatewayUrlCondition],
    },
    provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
  ],
};
```

`url`/`realm`/`clientId` match the already-exported `keycloak/bridgepay-realm.json` (`main-app` client, realm `bridgepay`) and the root `docker-compose.yml`'s Keycloak port mapping (`8180:8080`). `onLoad: 'login-required'` is the redirect-immediately flow the spec calls for (not `check-sso`). The interceptor's `urlPattern` must stay in sync with `environment.gatewayBaseUrl` (`http://localhost:8086`) — both are hardcoded for now per this plan's Global Constraints.

- [ ] **Step 11: Build to catch any wiring errors**

Run: `npx ng build`
Expected: builds successfully. A missing export or wrong import from `keycloak-angular` fails here first — if so, re-check the exact export name against `node_modules/keycloak-angular/types/keycloak-angular.d.ts` rather than guessing a different one.

- [ ] **Step 12: Commit**

```bash
git add frontend/main-app
git commit -m "Wire Keycloak auth, role guards, and routing skeleton"
```

---

### Task 3: App shell layout and design tokens

**Files:**
- Modify: `frontend/main-app/src/styles.css`
- Modify: `frontend/main-app/src/app/app.ts`
- Modify: `frontend/main-app/src/app/app.html`
- Modify: `frontend/main-app/src/app/app.css`
- Modify: `frontend/main-app/src/app/app.spec.ts`

**Interfaces:**
- Consumes: `Auth.hasRole()`/`Auth.username()`/`Auth.logout()` (Task 2).
- Produces: Tailwind utilities `bg-paper`, `text-ink`, `text-ink-muted`, `border-hairline`, `bg-accent`/`text-accent`, `bg-status-review`, `bg-status-declined`, and `font-mono` (IBM Plex Mono) — every later task's templates use these, not raw hex values.

- [ ] **Step 1: Write `styles.css`**

```css
@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap');
@import 'tailwindcss';

@theme {
  --color-paper: #FAFAF9;
  --color-ink: #1C1C1B;
  --color-ink-muted: #6B6963;
  --color-hairline: #D8D6D0;
  --color-accent: #2F5D50;
  --color-status-review: #8A6A2F;
  --color-status-declined: #8A3B32;
  --font-sans: "IBM Plex Sans", sans-serif;
  --font-mono: "IBM Plex Mono", monospace;
}

@layer base {
  body {
    @apply bg-paper text-ink;
  }
}
```

- [ ] **Step 2: Write the app shell component**

`src/app/app.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(Auth);
}
```

`src/app/app.html`:

```html
<div class="flex min-h-screen">
  <aside class="w-56 shrink-0 border-r border-hairline p-4 flex flex-col gap-1">
    <div class="font-medium text-lg mb-4">BridgePay</div>
    @if (auth.hasRole('ops')) {
      <a
        routerLink="/ops"
        routerLinkActive="text-accent font-medium"
        class="px-2 py-1.5 rounded-sm hover:bg-hairline/40"
      >Review Queue</a>
    }
    @if (auth.hasRole('merchant')) {
      <a
        routerLink="/merchant"
        routerLinkActive="text-accent font-medium"
        class="px-2 py-1.5 rounded-sm hover:bg-hairline/40"
      >Payouts</a>
    }
  </aside>
  <div class="flex-1 flex flex-col">
    <header class="border-b border-hairline px-6 py-3 flex justify-end items-center gap-3 text-sm text-ink-muted">
      <span class="font-mono">{{ auth.username() }}</span>
      <button (click)="auth.logout()" class="hover:text-ink">Log out</button>
    </header>
    <main class="flex-1 p-6">
      <router-outlet />
    </main>
  </div>
</div>
```

`src/app/app.css` — leave empty (all styling is Tailwind utility classes).

- [ ] **Step 3: Replace `app.spec.ts`**

The CLI-generated version asserts on the default landing-page title, which no longer exists. Replace it:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(roles: string[]) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        {
          provide: Auth,
          useValue: {
            hasRole: (r: string) => roles.includes(r),
            username: () => 'test-user',
            logout: () => {},
          },
        },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('shows the Review Queue link for an ops role', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Review Queue');
    expect(text).not.toContain('Payouts');
  });

  it('shows the Payouts link for a merchant role', () => {
    const fixture = setup(['merchant']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Payouts');
    expect(text).not.toContain('Review Queue');
  });
});
```

- [ ] **Step 4: Run the tests**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 5: Visual check**

Run: `npx ng serve`, open `http://localhost:4200`. Expected: this redirects into Keycloak's login page (per `onLoad: 'login-required'` from Task 2) — full shell verification happens in Task 7's smoke test once real data exists; for now just confirm no console errors and that the login redirect itself happens.

- [ ] **Step 6: Commit**

```bash
git add frontend/main-app
git commit -m "Add design tokens (IBM Plex, restrained palette) and app shell layout"
```

---

### Task 4: Applications service and review-queue list view

**Files:**
- Create: `frontend/main-app/src/app/shared/models/page.ts`
- Create: `frontend/main-app/src/app/shared/models/application.ts`
- Create: `frontend/main-app/src/app/ops/applications.ts` (+ `.spec.ts`)
- Modify: `frontend/main-app/src/app/ops/review-queue/review-queue.ts`
- Modify: `frontend/main-app/src/app/ops/review-queue/review-queue.html`
- Modify: `frontend/main-app/src/app/ops/review-queue/review-queue.spec.ts`

**Interfaces:**
- Produces: `Page<T>` (`content`, `totalElements`, `totalPages`, `number`, `size`), `ApplicationResponse`, `ScoreFactor` — reused by Task 5. `Applications` service: `listManualReview(page?, size?)`, `getApplication(id)`, `reviewDecision(id, decision, reviewerNote?)` — `getApplication`/`reviewDecision` consumed by Task 5.

Note on the data shown: the backend's `ApplicationResponse` (commit `3886cce`) exposes `merchantId` (a UUID), not a merchant name, and `decisionAt` (set whenever the application last changed status, including entering `MANUAL_REVIEW`), not a separate submission timestamp — there is no merchant-name field or created-at field on this DTO. The list below shows the merchant ID (monospaced, per the design's "IDs are always monospace" rule) and labels the date column "Decisioned," not "Submitted" — a corrected assumption from the spec's illustrative wireframe, which showed a merchant name as a placeholder.

- [ ] **Step 1: Write the shared models**

`src/app/shared/models/page.ts`:

```typescript
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
```

`src/app/shared/models/application.ts`:

```typescript
export interface ScoreFactor {
  feature: string;
  contribution: number;
}

export interface ApplicationResponse {
  applicationId: string;
  applicantId: string;
  merchantId: string;
  amount: number;
  status: string;
  riskScore: number | null;
  scoreFactors: ScoreFactor[];
  installmentCount: number | null;
  installmentAmount: number | null;
  decisionAt: string | null;
}
```

- [ ] **Step 2: Write the failing service test**

`src/app/ops/applications.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applications } from './applications';
import { ApplicationResponse } from '../shared/models/application';

describe('Applications', () => {
  let service: Applications;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Applications);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists manual-review applications', () => {
    let result: ApplicationResponse[] | undefined;
    service.listManualReview().subscribe((page) => (result = page.content));

    const req = httpMock.expectOne(
      (r) => r.url.endsWith('/api/v1/applications') && r.params.get('status') === 'MANUAL_REVIEW',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    expect(result).toEqual([]);
  });

  it('fetches a single application', () => {
    let result: ApplicationResponse | undefined;
    service.getApplication('app-1').subscribe((app) => (result = app));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1'));
    expect(req.request.method).toBe('GET');
    const fake: ApplicationResponse = {
      applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
      status: 'MANUAL_REVIEW', riskScore: 0.5, scoreFactors: [],
      installmentCount: null, installmentAmount: null, decisionAt: null,
    };
    req.flush(fake);

    expect(result).toEqual(fake);
  });

  it('submits a review decision', () => {
    service.reviewDecision('app-1', 'APPROVE', 'looks fine').subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications/app-1/review-decision'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVE', reviewerNote: 'looks fine' });
    req.flush({});
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — `Applications` doesn't exist yet.

- [ ] **Step 4: Write `Applications`**

`src/app/ops/applications.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';

@Injectable({ providedIn: 'root' })
export class Applications {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/applications`;

  listManualReview(page = 0, size = 20): Observable<Page<ApplicationResponse>> {
    return this.http.get<Page<ApplicationResponse>>(this.baseUrl, {
      params: { status: 'MANUAL_REVIEW', page, size },
    });
  }

  getApplication(id: string): Observable<ApplicationResponse> {
    return this.http.get<ApplicationResponse>(`${this.baseUrl}/${id}`);
  }

  reviewDecision(
    id: string,
    decision: 'APPROVE' | 'DECLINE',
    reviewerNote?: string,
  ): Observable<ApplicationResponse> {
    return this.http.post<ApplicationResponse>(`${this.baseUrl}/${id}/review-decision`, {
      decision,
      reviewerNote,
    });
  }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 6: Write the failing view test**

`src/app/ops/review-queue/review-queue.spec.ts` (replacing the CLI-generated stub):

```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ReviewQueue } from './review-queue';
import { Applications } from '../applications';
import { Page } from '../../shared/models/page';
import { ApplicationResponse } from '../../shared/models/application';

describe('ReviewQueue', () => {
  it('renders a row per application returned by the service', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'MANUAL_REVIEW', riskScore: 0.5, scoreFactors: [],
          installmentCount: null, installmentAmount: null, decisionAt: '2026-09-12T00:00:00Z',
        },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { listManualReview: () => of(page) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('m-1');
    expect(text).toContain('200.00');
  });

  it('shows an empty-state message when there are no applications', () => {
    const emptyPage: Page<ApplicationResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { listManualReview: () => of(emptyPage) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'No applications waiting for review',
    );
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — the placeholder `review-queue.html` doesn't render any of this.

- [ ] **Step 8: Implement the view**

`src/app/ops/review-queue/review-queue.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-review-queue',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './review-queue.html',
  styleUrl: './review-queue.css',
})
export class ReviewQueue {
  private readonly applications = inject(Applications);

  protected readonly rows = toSignal(
    this.applications.listManualReview().pipe(map((page) => page.content)),
    { initialValue: [] as ApplicationResponse[] },
  );
}
```

`src/app/ops/review-queue/review-queue.html`:

```html
<h1 class="text-xl font-medium mb-4">Review queue</h1>
<table class="w-full text-sm">
  <thead>
    <tr class="border-b border-hairline text-left text-ink-muted">
      <th class="py-2 font-normal">Merchant</th>
      <th class="py-2 font-normal text-right">Amount</th>
      <th class="py-2 font-normal">Decisioned</th>
      <th class="py-2"></th>
    </tr>
  </thead>
  <tbody>
    @for (row of rows(); track row.applicationId) {
      <tr class="border-b border-hairline">
        <td class="py-2 font-mono">{{ row.merchantId }}</td>
        <td class="py-2 font-mono text-right">{{ row.amount | number: '1.2-2' }}</td>
        <td class="py-2 font-mono">{{ row.decisionAt ? (row.decisionAt | date: 'MMM d, y') : '—' }}</td>
        <td class="py-2 text-right">
          <a [routerLink]="['/ops', row.applicationId]" class="text-accent hover:underline">Review</a>
        </td>
      </tr>
    } @empty {
      <tr>
        <td colspan="4" class="py-6 text-center text-ink-muted">
          No applications waiting for review.
        </td>
      </tr>
    }
  </tbody>
</table>
```

- [ ] **Step 9: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 10: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

Expected: all tests green, build succeeds.

- [ ] **Step 11: Commit**

```bash
git add frontend/main-app
git commit -m "Add Applications service and the review-queue list view"
```

---

### Task 5: Review-detail view with score-factors explainability chart

**Files:**
- Modify: `frontend/main-app/src/app/app.routes.ts`
- Create: `frontend/main-app/src/app/ops/review-detail/review-detail.ts` (+ `.html`, `.css`, `.spec.ts`)

**Interfaces:**
- Consumes: `Applications.getApplication(id)`, `Applications.reviewDecision(id, decision)` (Task 4).

- [ ] **Step 1: Add the route**

In `src/app/app.routes.ts`, add one entry (after the `'ops'` route):

```typescript
  { path: 'ops/:id', component: ReviewDetail, canActivate: [opsGuard] },
```

Add the import: `import { ReviewDetail } from './ops/review-detail/review-detail';`

- [ ] **Step 2: Generate the component**

```bash
npx ng generate component ops/review-detail
```

- [ ] **Step 3: Write the failing test**

`src/app/ops/review-detail/review-detail.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ReviewDetail } from './review-detail';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';

describe('ReviewDetail', () => {
  const baseApp: ApplicationResponse = {
    applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 500,
    status: 'MANUAL_REVIEW', riskScore: 0.4, scoreFactors: [],
    installmentCount: null, installmentAmount: null, decisionAt: null,
  };

  it('renders each score factor', () => {
    const app: ApplicationResponse = {
      ...baseApp,
      scoreFactors: [
        { feature: 'revolving_util', contribution: 0.12 },
        { feature: 'debt_ratio', contribution: -0.05 },
      ],
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: app.applicationId })) } },
        { provide: Applications, useValue: { getApplication: () => of(app), reviewDecision: () => of(app) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('revolving_util');
    expect(text).toContain('debt_ratio');
  });

  it('calls reviewDecision with APPROVE when Approve is clicked', () => {
    const decisionCalls: unknown[][] = [];
    const reviewDecision = (id: string, decision: string) => {
      decisionCalls.push([id, decision]);
      return of(baseApp);
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: baseApp.applicationId })) },
        },
        { provide: Applications, useValue: { getApplication: () => of(baseApp), reviewDecision } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    const approveButton = Array.from(buttons).find((b) => b.textContent?.includes('Approve')) as HTMLButtonElement;
    approveButton.click();

    expect(decisionCalls).toEqual([['app-1', 'APPROVE']]);
  });

  it('calls reviewDecision with DECLINE when Decline is clicked', () => {
    const decisionCalls: unknown[][] = [];
    const reviewDecision = (id: string, decision: string) => {
      decisionCalls.push([id, decision]);
      return of(baseApp);
    };

    TestBed.configureTestingModule({
      imports: [ReviewDetail],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: baseApp.applicationId })) },
        },
        { provide: Applications, useValue: { getApplication: () => of(baseApp), reviewDecision } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewDetail);
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    const declineButton = Array.from(buttons).find((b) => b.textContent?.includes('Decline')) as HTMLButtonElement;
    declineButton.click();

    expect(decisionCalls).toEqual([['app-1', 'DECLINE']]);
  });
});
```

- [ ] **Step 4: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — the placeholder component renders none of this.

- [ ] **Step 5: Implement the component**

`src/app/ops/review-detail/review-detail.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { switchMap } from 'rxjs';
import { Applications } from '../applications';

@Component({
  selector: 'app-review-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './review-detail.html',
  styleUrl: './review-detail.css',
})
export class ReviewDetail {
  private readonly applications = inject(Applications);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly application = toSignal(
    this.route.paramMap.pipe(switchMap((params) => this.applications.getApplication(params.get('id')!))),
  );

  protected maxContribution(): number {
    const factors = this.application()?.scoreFactors ?? [];
    return Math.max(1e-9, ...factors.map((f) => Math.abs(f.contribution)));
  }

  decide(decision: 'APPROVE' | 'DECLINE'): void {
    const id = this.application()?.applicationId;
    if (!id) return;
    this.applications.reviewDecision(id, decision).subscribe(() => this.router.navigateByUrl('/ops'));
  }
}
```

`src/app/ops/review-detail/review-detail.html` — the diverging-bar explainability chart is the one designed moment per the spec's Visual Design section:

```html
@if (application(); as app) {
  <a routerLink="/ops" class="text-sm text-accent hover:underline">← Review queue</a>

  <h1 class="text-xl font-medium mt-3 mb-1 font-mono">{{ app.amount | number: '1.2-2' }}</h1>
  <p class="text-sm text-ink-muted mb-6">
    Merchant <span class="font-mono">{{ app.merchantId }}</span>
    @if (app.decisionAt) { · decisioned {{ app.decisionAt | date: 'MMM d, y' }} }
  </p>

  <h2 class="text-sm font-medium mb-3">Score factors</h2>
  <div class="space-y-2 mb-8">
    @for (factor of app.scoreFactors; track factor.feature) {
      <div class="flex items-center gap-3 text-sm">
        <div class="w-40 shrink-0 truncate">{{ factor.feature }}</div>
        <div class="flex-1 h-4 relative bg-hairline/30">
          <div class="absolute inset-y-0 left-1/2 w-px bg-hairline"></div>
          @if (factor.contribution >= 0) {
            <div
              class="absolute inset-y-0 left-1/2 bg-accent"
              [style.width.%]="(factor.contribution / maxContribution()) * 50"
            ></div>
          } @else {
            <div
              class="absolute inset-y-0 right-1/2 bg-status-declined"
              [style.width.%]="(-factor.contribution / maxContribution()) * 50"
            ></div>
          }
        </div>
        <div class="w-16 shrink-0 text-right font-mono">{{ factor.contribution | number: '1.3-3' }}</div>
      </div>
    } @empty {
      <p class="text-sm text-ink-muted">No score breakdown available for this application.</p>
    }
  </div>

  <div class="flex gap-3">
    <button
      (click)="decide('DECLINE')"
      class="px-4 py-2 border border-hairline hover:border-status-declined hover:text-status-declined"
    >Decline</button>
    <button (click)="decide('APPROVE')" class="px-4 py-2 bg-accent text-paper hover:opacity-90">
      Approve
    </button>
  </div>
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 7: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

Expected: all tests green, build succeeds.

- [ ] **Step 8: Commit**

```bash
git add frontend/main-app
git commit -m "Add review-detail view with score-factors explainability chart"
```

---

### Task 6: Payouts service and merchant payout-ledger view

**Files:**
- Create: `frontend/main-app/src/app/shared/models/merchant-payout.ts`
- Create: `frontend/main-app/src/app/merchant/payouts.ts` (+ `.spec.ts`)
- Modify: `frontend/main-app/src/app/merchant/payout-ledger/payout-ledger.ts`
- Modify: `frontend/main-app/src/app/merchant/payout-ledger/payout-ledger.html`
- Modify: `frontend/main-app/src/app/merchant/payout-ledger/payout-ledger.spec.ts`

**Interfaces:**
- Consumes: `Page<T>` (Task 4), `Auth.merchantId()` (Task 2).
- Produces: `MerchantPayoutResponse`, `Payouts.listPayouts(merchantId, page?, size?)`.

- [ ] **Step 1: Write the shared model**

`src/app/shared/models/merchant-payout.ts`:

```typescript
export interface MerchantPayoutResponse {
  id: string;
  applicationId: string;
  amount: number;
  feeAmount: number;
  status: string;
  paidAt: string | null;
}
```

- [ ] **Step 2: Write the failing service test**

`src/app/merchant/payouts.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Payouts } from './payouts';
import { MerchantPayoutResponse } from '../shared/models/merchant-payout';

describe('Payouts', () => {
  let service: Payouts;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Payouts);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists payouts for a merchant', () => {
    let result: MerchantPayoutResponse[] | undefined;
    service.listPayouts('m-1').subscribe((page) => (result = page.content));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/merchants/m-1/payouts'));
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    expect(result).toEqual([]);
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — `Payouts` doesn't exist yet.

- [ ] **Step 4: Write `Payouts`**

`src/app/merchant/payouts.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { MerchantPayoutResponse } from '../shared/models/merchant-payout';

@Injectable({ providedIn: 'root' })
export class Payouts {
  private readonly http = inject(HttpClient);

  listPayouts(merchantId: string, page = 0, size = 20): Observable<Page<MerchantPayoutResponse>> {
    return this.http.get<Page<MerchantPayoutResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/payouts`,
      { params: { page, size } },
    );
  }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 6: Write the failing view test**

`src/app/merchant/payout-ledger/payout-ledger.spec.ts` (replacing the CLI-generated stub):

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PayoutLedger } from './payout-ledger';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { Page } from '../../shared/models/page';
import { MerchantPayoutResponse } from '../../shared/models/merchant-payout';

describe('PayoutLedger', () => {
  it('renders a row per payout returned by the service', () => {
    const page: Page<MerchantPayoutResponse> = {
      content: [
        { id: 'p-1', applicationId: 'app-1', amount: 1240, feeAmount: 43.4, status: 'PAID', paidAt: '2026-09-14T00:00:00Z' },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => of(page) } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });

    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('1240.00');
    expect(text).toContain('paid');
  });

  it('shows an empty-state message when there are no payouts', () => {
    const emptyPage: Page<MerchantPayoutResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => of(emptyPage) } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });

    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No payouts yet');
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL.

- [ ] **Step 8: Implement the view**

`src/app/merchant/payout-ledger/payout-ledger.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, LowerCasePipe } from '@angular/common';
import { map } from 'rxjs';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { MerchantPayoutResponse } from '../../shared/models/merchant-payout';

@Component({
  selector: 'app-payout-ledger',
  imports: [DatePipe, DecimalPipe, LowerCasePipe],
  templateUrl: './payout-ledger.html',
  styleUrl: './payout-ledger.css',
})
export class PayoutLedger {
  private readonly payouts = inject(Payouts);
  private readonly auth = inject(Auth);

  protected readonly rows = toSignal(
    this.payouts.listPayouts(this.auth.merchantId() ?? '').pipe(map((page) => page.content)),
    { initialValue: [] as MerchantPayoutResponse[] },
  );
}
```

`src/app/merchant/payout-ledger/payout-ledger.html`:

```html
<h1 class="text-xl font-medium mb-4">Payouts</h1>
<table class="w-full text-sm">
  <thead>
    <tr class="border-b border-hairline text-left text-ink-muted">
      <th class="py-2 font-normal text-right">Amount</th>
      <th class="py-2 font-normal text-right">Fee</th>
      <th class="py-2 font-normal">Status</th>
      <th class="py-2 font-normal">Paid</th>
    </tr>
  </thead>
  <tbody>
    @for (row of rows(); track row.id) {
      <tr class="border-b border-hairline">
        <td class="py-2 font-mono text-right">{{ row.amount | number: '1.2-2' }}</td>
        <td class="py-2 font-mono text-right">{{ row.feeAmount | number: '1.2-2' }}</td>
        <td class="py-2">
          @if (row.status === 'PAID') {
            <span class="inline-block w-1.5 h-1.5 rounded-full bg-accent mr-1.5"></span>
          } @else {
            <span class="inline-block w-1.5 h-1.5 rounded-full bg-status-review mr-1.5"></span>
          }
          {{ row.status | lowercase }}
        </td>
        <td class="py-2 font-mono">{{ row.paidAt ? (row.paidAt | date: 'MMM d, y') : '—' }}</td>
      </tr>
    } @empty {
      <tr>
        <td colspan="4" class="py-6 text-center text-ink-muted">No payouts yet.</td>
      </tr>
    }
  </tbody>
</table>
```

- [ ] **Step 9: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 10: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

Expected: all tests green, build succeeds.

- [ ] **Step 11: Commit**

```bash
git add frontend/main-app
git commit -m "Add Payouts service and the merchant payout-ledger view"
```

---

### Task 7: Dockerfile, docker-compose wiring, manual smoke test, PROGRESS.md

**Files:**
- Create: `frontend/main-app/Dockerfile`
- Modify: `docker-compose.yml` (repo root)
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Confirm the real build output directory**

Run: `cd frontend/main-app && npx ng build` (if not already built from Task 6's step 10) and note the exact path printed (Angular's `@angular/build:application` builder outputs to `dist/main-app/browser/` for a client-only app — confirm this by running `ls dist/main-app` rather than assuming, since Task 1's Step 5 already surfaced this once).

- [ ] **Step 2: Write the Dockerfile**

`frontend/main-app/Dockerfile` (adjust the `COPY --from=build` source path if Step 1 found a different real directory name):

```dockerfile
# Build stage
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npx ng build

# Run stage
FROM nginx:alpine
COPY --from=build /app/dist/main-app/browser /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 3: Add the service to the root `docker-compose.yml`**

Read the current root `docker-compose.yml` first (it now has 7 backend services plus postgres/kafka/redis/keycloak) to match its exact indentation/style, then add, after the `api-gateway` entry and before `volumes:`:

```yaml
  main-app:
    build: ./frontend/main-app
    ports:
      - "4200:80"
```

No `depends_on` — this is a browser-side SPA reaching the gateway over `localhost:8086` from the browser, not over the compose network.

- [ ] **Step 4: Run the shared stack and smoke test**

```bash
docker-compose up --build
```

Once all containers are up:

1. Open `http://localhost:4200` — expect an immediate redirect to Keycloak's login page.
2. Log in as `ops1` (demo user, password `ops1` per the Keycloak realm's seeded users) — expect a redirect back to `/ops`, sidebar showing only "Review Queue", the queue table rendering (likely showing the empty-state message unless a `MANUAL_REVIEW` application already exists in this stack's Postgres).
3. Log out, log in as `merchant1` (password `merchant1`) — expect redirect to `/merchant`, sidebar showing only "Payouts".
4. Confirm no browser console errors on either page (check the interceptor actually attached a bearer token to the outgoing API calls — inspect the Network tab for an `Authorization: Bearer ...` header on the `applications`/`payouts` request).

- [ ] **Step 5: Tear down cleanly**

```bash
docker-compose down
```

Confirm via `docker-compose ps -a` that no containers remain.

- [ ] **Step 6: Update `docs/PROGRESS.md`**

Move "Main Angular app" from `## Next, in order` into `## Done`, matching the file's existing level of detail — cite the real `ng test` count from this task's actual run, and describe exactly what Step 4's manual smoke test confirmed (real login redirect, real role-gated nav, real bearer-token-attached API calls) versus what it didn't (an actual non-empty review queue, since no `MANUAL_REVIEW` application exists in a fresh stack without walking a real checkout through the full decision pipeline — note this as an honest gap, not a claimed success). Update `## Next, in order` so "Storefront demo Angular app" is now first.

- [ ] **Step 7: Commit**

```bash
git add frontend/main-app docker-compose.yml docs/PROGRESS.md
git commit -m "Add Dockerfile and docker-compose wiring for the Main Angular app, verify end-to-end"
```

---

## Self-Review Notes

- **Spec coverage**: Angular 21 + Tailwind + keycloak-angular pin (Task 1/2), redirect-login auth + role guards + root-redirect (Task 2), design tokens + shell (Task 3), `ApplicationsService`/review-queue (Task 4), review-detail + explainability chart (Task 5), `PayoutsService`/payout-ledger (Task 6), Dockerfile/compose/smoke test/PROGRESS.md (Task 7) — every spec section has a task. The spec's stated non-goals (storefront app, runtime-configurable API URL, OpenAPI codegen, e2e browser tests) are correctly absent from every task.
- **Placeholder scan**: none found — every step has real, complete code or an exact command.
- **Type consistency**: `ApplicationResponse`/`ScoreFactor` (Task 4) match field-for-field what Task 5's `review-detail.ts`/`.html` consume (`scoreFactors`, `amount`, `merchantId`, `decisionAt`, `applicationId`). `Page<T>` (Task 4) is reused unchanged by Task 6's `Payouts`. `Auth.roles()`/`hasRole()`/`merchantId()`/`username()`/`logout()` (Task 2) are the exact names Task 3's shell and Task 6's `PayoutLedger` call. `opsGuard`/`merchantGuard`/`rootRedirectGuard` (Task 2) are the exact names Task 2's own `app.routes.ts` and Task 5's added route import.
- **One corrected assumption during writing**: the spec's review-queue wireframe showed an illustrative merchant name; the real `ApplicationResponse` only has `merchantId` (a UUID) and `decisionAt` (not a separate submission date) — Task 4 explicitly documents this correction rather than silently inventing a field the backend doesn't return.
