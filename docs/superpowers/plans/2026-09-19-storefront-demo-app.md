# Storefront Demo App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `frontend/storefront` — a mock merchant storefront ("Ridgeline Supply Co.") with BridgePay's "Pay in 4" checkout embedded in it, per the approved design.

**Architecture:** Angular 21 (standalone, signals), no Angular Router — a single root component drives a linear `catalog → signup → confirm → result` flow via one local state signal. `keycloak-angular@21.0.0` in silent `check-sso` mode (not the Main Angular App's `login-required`); login is triggered imperatively by clicking "Pay in 4," with the in-progress checkout persisted to `sessionStorage` across the resulting full-page redirect. Same-origin API access (nginx `/api/` proxy + a relative `gatewayBaseUrl`) from the first commit, not retrofitted after a review catches it — the Main Angular App's final review found exactly that bug as a Critical finding fixed in a later wave; this plan starts from the fix, not the mistake.

**Tech Stack:** Angular 21.2.x, TypeScript, Tailwind CSS v4, `keycloak-angular@21.0.0` + `keycloak-js`, Vitest (`@angular/build:unit-test`, this Angular version's real default — not Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-09-19-storefront-demo-app-design.md`

## Global Constraints

- Angular **21** specifically — pin every `npx @angular/cli` invocation to `@21`. `keycloak-angular` installed at **exactly `21.0.0`** (`npm install keycloak-angular@21.0.0 keycloak-js`) — its `latest` npm tag targets Angular 22.
- `ng generate`'s real Angular 21 naming (already verified empirically while building `frontend/main-app`, not re-verified here): no `.component.`/`.service.` infix. A component named `product-catalog` is `product-catalog.ts`/`.html`/`.css`/`.spec.ts` with class `ProductCatalog`; a service named `applicants` is `applicants.ts` with class `Applicants`.
- Test builder is **Vitest**, run via `ng test`, Jasmine-compatible `describe`/`it`/`expect` — every test in this plan uses plain manual mocks and `await` where needed, never `jasmine.createSpy`/`expectAsync` (same portability reasoning as the Main Angular App's plan).
- **Same-origin from the start**: `environment.gatewayBaseUrl = ''` in both environment files from Task 1 onward — every HTTP call in this app is a relative `/api/v1/...` path. No absolute `http://localhost:8086` URL appears anywhere in this codebase, ever, at any point in this plan. The actual nginx proxy that makes the relative path resolve correctly only gets built in Task 7 (there's no real app to containerize before then), but the URL convention itself is correct from the first commit — there is no "before" state where it was wrong.
- Two visual identities, never mixed: the storefront shell (`--color-paper`, `--color-ink`, `--color-ink-muted`, `--color-hairline`, `--font-sans` = Work Sans, `--font-serif` = Fraunces) and the BridgePay widget (`--color-widget-accent`, `--color-widget-surface`, `--font-widget` = Manrope). Widget components never use shell fonts/colors and vice versa.
- No Angular Router anywhere in this app — the `catalog`/`signup`/`confirm`/`result` flow is one component-owned `signal`, not routes.
- The demo merchant this storefront checks out against is the same seeded row every other part of this project already uses: `00000000-0000-7000-8000-000000000001` (from `V2__seed_demo_merchant.sql`, the same row `merchant1`'s payout ledger in the Main Angular App reads from).
- No OpenAPI/codegen — TypeScript interfaces hand-mirrored against the real backend DTOs (`SignupRequest`/`ApplicantResponse` in `services/applicant-service/src/main/java/com/bridgepay/applicant/dto/`, `CheckoutRequest`/`ApplicationResponse` in `services/application-service/src/main/java/com/bridgepay/application/dto/`).

---

## File Structure

```
frontend/storefront/
  (CLI-generated: angular.json, package.json, tsconfig*.json, .postcssrc.json,
   public/favicon.ico, src/main.ts, src/index.html)
  Dockerfile, nginx.conf, .dockerignore, proxy.conf.json
  public/silent-check-sso.html
  src/
    styles.css                              # both identities' design tokens
    environments/
      environment.ts
      environment.development.ts
    app/
      app.ts / .html / .css / .spec.ts       # root: the catalog->signup->confirm->result flow
      app.config.ts                          # providers: HTTP, Keycloak, interceptor (no router)
      core/
        auth.ts / .spec.ts                   # Auth: authenticated()/username() signals, login()/logout()
      catalog/
        products.ts                          # Product interface + the 4 hardcoded PRODUCTS
        product-catalog/product-catalog.ts / .html / .css / .spec.ts
      signup/
        applicant.model.ts                   # SignupRequest, ApplicantResponse
        applicants.ts / .spec.ts             # service: getMyProfile(), signUp()
        signup-form/signup-form.ts / .html / .css / .spec.ts
      checkout/
        applications.ts / .spec.ts           # service: checkout()
        checkout-confirm/checkout-confirm.ts / .html / .css / .spec.ts
        checkout-result/checkout-result.ts / .html / .css / .spec.ts
      shared/
        models/application.ts                # ApplicationResponse (storefront's own subset)
```

- `core/` — the one cross-cutting concern (auth).
- `catalog/`, `signup/`, `checkout/` — one folder per step of the flow, each owning its own service (where it has one) and component(s).
- `shared/models/` — the one interface used by more than one folder (`checkout/` produces it, `app/` reads it to pick the result template).

---

### Task 1: Scaffold the app, Tailwind, environments

**Files:**
- Create: `frontend/storefront/` (entire CLI-generated tree)
- Modify: `frontend/storefront/src/environments/environment.ts`
- Modify: `frontend/storefront/src/environments/environment.development.ts`

**Interfaces:**
- Produces: `environment.gatewayBaseUrl: string` (always `''` — relative paths only), consumed by every later task's HTTP services.

- [ ] **Step 1: Scaffold the Angular 21 project, without routing**

From the repo root:

```bash
mkdir -p frontend
cd frontend
npx -y @angular/cli@21 new storefront --no-routing --style=css --skip-git --defaults
```

`--no-routing` because this app has no Angular Router at all (see Global Constraints) — the CLI won't generate `app.routes.ts` or wire `provideRouter`. `--skip-git`/`--defaults` for the same reasons as the Main Angular App's scaffold (monorepo, no interactive prompts).

- [ ] **Step 2: Add Tailwind CSS**

```bash
cd frontend/storefront
npx ng add tailwindcss --skip-confirmation
```

- [ ] **Step 3: Generate environment files**

```bash
npx ng generate environments
```

- [ ] **Step 4: Fill in both environment files**

`src/environments/environment.ts` and `src/environments/environment.development.ts` — identical content, both relative:

```typescript
export const environment = {
  gatewayBaseUrl: '',
};
```

- [ ] **Step 5: Verify the build and default test suite**

```bash
npx ng build
npx ng test --watch=false
```

Expected: `ng build` succeeds; `ng test --watch=false` runs the CLI-generated `app.spec.ts` (2 tests) and passes.

- [ ] **Step 6: Commit**

```bash
cd ../..
git add frontend/storefront
git commit -m "Scaffold frontend/storefront: Angular 21 (no router), Tailwind CSS v4, relative API base URL"
```

---

### Task 2: Keycloak check-sso auth

**Files:**
- Modify: `frontend/storefront/package.json` (via `npm install`)
- Create: `frontend/storefront/public/silent-check-sso.html`
- Create: `frontend/storefront/src/app/core/auth.ts` (+ `.spec.ts`)
- Modify: `frontend/storefront/src/app/app.config.ts`

**Interfaces:**
- Consumes: `environment.gatewayBaseUrl` (Task 1).
- Produces: `Auth` (class, `providedIn: 'root'`) with `authenticated(): Signal<boolean>`, `username(): Signal<string | null>`, `login(redirectUri: string): void`, `logout(): void` — used by Task 6's root component.

- [ ] **Step 1: Install keycloak-angular, pinned**

```bash
cd frontend/storefront
npm install keycloak-angular@21.0.0 keycloak-js
```

- [ ] **Step 2: Write the silent check-sso page**

`public/silent-check-sso.html` — the standard `keycloak-js` iframe target (verified against `keycloak-js`'s own documented content, this exact snippet, not improvised):

```html
<!doctype html>
<html>
<body>
<script>
  parent.postMessage(location.href, location.origin);
</script>
</body>
</html>
```

- [ ] **Step 3: Write `Auth`**

`src/app/core/auth.ts` — deliberately leaner than the Main Angular App's `Auth`: this app has no roles or merchant-scoped data, so there's nothing to read from `realm_access`/`merchantId` here, just whether a session exists and who it belongs to:

```typescript
import { Injectable, computed, inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  readonly authenticated = computed<boolean>(() => {
    this.keycloakEvent(); // re-evaluate whenever a Keycloak event fires
    return this.keycloak.authenticated ?? false;
  });

  readonly username = computed<string | null>(() => {
    this.keycloakEvent();
    return (this.keycloak.tokenParsed?.['preferred_username'] as string | undefined) ?? null;
  });

  login(redirectUri: string): void {
    this.keycloak.login({ redirectUri });
  }

  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
```

This uses the same lazy-`computed()`-over-`KEYCLOAK_EVENT_SIGNAL` pattern the Main Angular App's `Auth` was fixed to use after its own review found a real race with an `effect()`-based version — this app starts from that already-correct shape, not the buggy one.

- [ ] **Step 4: Write `auth.spec.ts`**

```typescript
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { Auth } from './auth';

describe('Auth', () => {
  function setup(authenticated: boolean, tokenParsed?: Record<string, unknown>) {
    const fakeKeycloak = { authenticated, tokenParsed } as unknown as Keycloak;
    TestBed.configureTestingModule({
      providers: [
        { provide: Keycloak, useValue: fakeKeycloak },
        {
          provide: KEYCLOAK_EVENT_SIGNAL,
          useValue: signal({ type: KeycloakEventType.Ready, args: authenticated }),
        },
      ],
    });
    return TestBed.inject(Auth);
  }

  it('reflects an authenticated session', () => {
    const auth = setup(true, { preferred_username: 'shopper1' });
    expect(auth.authenticated()).toBe(true);
    expect(auth.username()).toBe('shopper1');
  });

  it('reflects an unauthenticated session', () => {
    const auth = setup(false);
    expect(auth.authenticated()).toBe(false);
    expect(auth.username()).toBeNull();
  });
});
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS (2 new tests, plus the 2 pre-existing `app.spec.ts` tests — 4 total).

- [ ] **Step 6: Wire `provideKeycloak` in check-sso mode, and the bearer-token interceptor**

`src/app/app.config.ts`:

```typescript
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  provideKeycloak,
  includeBearerTokenInterceptor,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
} from 'keycloak-angular';

const gatewayUrlCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: /^\/api\/.*$/i,
  bearerPrefix: 'Bearer',
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideKeycloak({
      config: {
        url: 'http://localhost:8180',
        realm: 'bridgepay',
        clientId: 'storefront',
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
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

Note there is no `provideRouter` here at all (Global Constraint: no router in this app) — this is the entire provider list. `url`/`realm`/`clientId` match the already-exported `keycloak/bridgepay-realm.json` (`storefront` client, redirect `http://localhost:4201/*`) and the root `docker-compose.yml`'s Keycloak port mapping (`8180:8080`), same values already proven correct for the Main Angular App's own `main-app` client entry.

- [ ] **Step 7: Build to catch any wiring errors**

Run: `npx ng build`
Expected: builds successfully.

- [ ] **Step 8: Commit**

```bash
git add frontend/storefront
git commit -m "Wire Keycloak check-sso auth (silent session check, login triggered on demand)"
```

---

### Task 3: Design tokens and the product catalog

**Files:**
- Modify: `frontend/storefront/src/styles.css`
- Create: `frontend/storefront/src/app/catalog/products.ts`
- Create: `frontend/storefront/src/app/catalog/product-catalog/product-catalog.ts` (+ `.html`, `.css`, `.spec.ts`)

**Interfaces:**
- Produces: `Product { id, name, price, swatchColor }`, `PRODUCTS: Product[]` (4 hardcoded items) — consumed by Task 6's root component. `ProductCatalog` component: `payInFour = output<string>()` (emits the clicked product's `id`) — consumed by Task 6.
- Produces: Tailwind utilities `bg-paper`, `text-ink`, `text-ink-muted`, `border-hairline`, `font-serif` (Fraunces), `font-sans` (Work Sans, the default), `bg-widget-accent`/`text-widget-accent`, `bg-widget-surface`, `font-widget` (Manrope) — Tasks 4/5's templates use these exact names.

- [ ] **Step 1: Write `styles.css`**

```css
@import url('https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400;9..144,600&family=Work+Sans:wght@400;500&family=Manrope:wght@500;700&display=swap');
@import 'tailwindcss';

@theme {
  --color-paper: #FAFAF8;
  --color-ink: #26241F;
  --color-ink-muted: #6B6A63;
  --color-hairline: #E4E1DA;
  --color-widget-accent: #4B3F72;
  --color-widget-surface: #F6F4FB;
  --font-sans: "Work Sans", sans-serif;
  --font-serif: "Fraunces", serif;
  --font-widget: "Manrope", sans-serif;
}

@layer base {
  body {
    @apply bg-paper text-ink;
  }
}
```

- [ ] **Step 2: Write the product data**

`src/app/catalog/products.ts`:

```typescript
export interface Product {
  id: string;
  name: string;
  price: number;
  swatchColor: string;
}

export const PRODUCTS: Product[] = [
  { id: 'basin-rain-jacket', name: 'Basin Rain Jacket', price: 198, swatchColor: '#3F5843' },
  { id: 'ridgeline-trail-pack', name: 'Ridgeline Trail Pack', price: 164, swatchColor: '#B08D57' },
  { id: 'camp-multitool', name: 'Camp Multitool', price: 58, swatchColor: '#6B7280' },
  { id: 'insulated-field-bottle', name: 'Insulated Field Bottle', price: 42, swatchColor: '#A24E3A' },
];
```

- [ ] **Step 3: Write the failing test**

```bash
npx ng generate component catalog/product-catalog
```

Replace the generated `product-catalog.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { ProductCatalog } from './product-catalog';

describe('ProductCatalog', () => {
  it('renders every product with its name and price', () => {
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Basin Rain Jacket');
    expect(text).toContain('198.00');
    expect(text).toContain('Ridgeline Trail Pack');
  });

  it('emits the clicked product\'s id on Pay in 4', () => {
    const fixture = TestBed.createComponent(ProductCatalog);
    fixture.detectChanges();
    const emitted: string[] = [];
    fixture.componentInstance.payInFour.subscribe((id) => emitted.push(id));

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    (buttons[0] as HTMLButtonElement).click();

    expect(emitted).toEqual(['basin-rain-jacket']);
  });
});
```

- [ ] **Step 4: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — the generated placeholder renders none of this.

- [ ] **Step 5: Implement the component**

`src/app/catalog/product-catalog/product-catalog.ts`:

```typescript
import { Component, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { PRODUCTS } from '../products';

@Component({
  selector: 'app-product-catalog',
  imports: [DecimalPipe],
  templateUrl: './product-catalog.html',
  styleUrl: './product-catalog.css',
})
export class ProductCatalog {
  protected readonly products = PRODUCTS;
  payInFour = output<string>();
}
```

`src/app/catalog/product-catalog/product-catalog.html`:

```html
<header class="mb-10">
  <h1 class="font-serif text-3xl">Ridgeline Supply Co.</h1>
  <p class="text-ink-muted">Considered gear for slow trips outside.</p>
</header>
<div class="grid grid-cols-1 sm:grid-cols-2 gap-8 max-w-2xl">
  @for (product of products; track product.id) {
    <article class="flex flex-col">
      <div class="aspect-square mb-3" [style.background-color]="product.swatchColor"></div>
      <h2 class="font-serif text-lg">{{ product.name }}</h2>
      <p class="text-ink-muted mb-3">${{ product.price | number: '1.2-2' }}</p>
      <button
        (click)="payInFour.emit(product.id)"
        class="self-start px-4 py-2 rounded-lg bg-widget-accent text-white font-widget font-medium hover:opacity-90"
      >Pay in 4</button>
    </article>
  }
</div>
```

Note the "Pay in 4" button is the one place the widget's own accent color/font (`bg-widget-accent`, `font-widget`) appears inside the storefront shell — intentional, this is the BridgePay brand injecting itself into the host page exactly as it would in a real integration, not a mistake to fix.

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
git add frontend/storefront
git commit -m "Add design tokens (Fraunces/Work Sans shell, Manrope widget) and the product catalog"
```

---

### Task 4: Applicant signup

**Files:**
- Create: `frontend/storefront/src/app/signup/applicant.model.ts`
- Create: `frontend/storefront/src/app/signup/applicants.ts` (+ `.spec.ts`)
- Create: `frontend/storefront/src/app/signup/signup-form/signup-form.ts` (+ `.html`, `.css`, `.spec.ts`)

**Interfaces:**
- Produces: `SignupRequest`, `ApplicantResponse` (matching `services/applicant-service/.../dto/SignupRequest.java` and `ApplicantResponse.java` field-for-field). `Applicants` service: `getMyProfile(): Observable<ApplicantResponse>`, `signUp(request: SignupRequest): Observable<ApplicantResponse>` — both consumed by Task 6's root component. `SignupForm` component: `signedUp = output<void>()` — consumed by Task 6.

Note on scope: the real backend's `SignupRequest` collects only `firstName`/`lastName`/`dateOfBirth`/`email`/`phone` — no payment method field exists anywhere in this endpoint, despite the platform spec's own summary line mentioning one. This is confirmed by reading the actual DTO, not an assumption; the form below asks for exactly these 5 fields and nothing else.

- [ ] **Step 1: Write the models**

`src/app/signup/applicant.model.ts`:

```typescript
export interface SignupRequest {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phone: string;
}

export interface ApplicantResponse {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phone: string;
  createdAt: string;
}
```

- [ ] **Step 2: Write the failing service test**

`src/app/signup/applicants.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applicants } from './applicants';
import { ApplicantResponse } from './applicant.model';

describe('Applicants', () => {
  let service: Applicants;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Applicants);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the current applicant profile', () => {
    let result: ApplicantResponse | undefined;
    service.getMyProfile().subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applicants/me'));
    expect(req.request.method).toBe('GET');
    const fake: ApplicantResponse = {
      id: 'a-1', firstName: 'Shopper', lastName: 'One', dateOfBirth: '1990-01-01',
      email: 'shopper1@example.com', phone: '+15551234567', createdAt: '2026-01-01T00:00:00Z',
    };
    req.flush(fake);

    expect(result).toEqual(fake);
  });

  it('signs up a new applicant', () => {
    const request = {
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    };
    service.signUp(request).subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applicants'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush({ id: 'a-2', ...request, createdAt: '2026-01-01T00:00:00Z' });
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — `Applicants` doesn't exist yet.

- [ ] **Step 4: Write `Applicants`**

`src/app/signup/applicants.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicantResponse, SignupRequest } from './applicant.model';

@Injectable({ providedIn: 'root' })
export class Applicants {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/applicants`;

  getMyProfile(): Observable<ApplicantResponse> {
    return this.http.get<ApplicantResponse>(`${this.baseUrl}/me`);
  }

  signUp(request: SignupRequest): Observable<ApplicantResponse> {
    return this.http.post<ApplicantResponse>(this.baseUrl, request);
  }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 6: Write the failing view test**

```bash
npx ng generate component signup/signup-form
```

Replace the generated `signup-form.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SignupForm } from './signup-form';
import { Applicants } from '../applicants';

describe('SignupForm', () => {
  function setup(signUp: (request: unknown) => any) {
    TestBed.configureTestingModule({
      imports: [SignupForm],
      providers: [{ provide: Applicants, useValue: { signUp, getMyProfile: () => of({}) } }],
    });
    const fixture = TestBed.createComponent(SignupForm);
    fixture.detectChanges();
    return fixture;
  }

  it('emits signedUp after a successful submission', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue({
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    });
    component.submit();

    expect(emitted).toBe(true);
  });

  it('shows an error and does not emit when the request fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue({
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    });
    component.submit();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Could not create your profile');
  });

  it('does not submit an invalid form', () => {
    let submitCalled = false;
    const fixture = setup(() => {
      submitCalled = true;
      return of({ id: 'a-1' });
    });
    fixture.componentInstance.submit();
    expect(submitCalled).toBe(false);
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL.

- [ ] **Step 8: Implement the component**

`src/app/signup/signup-form/signup-form.ts`:

```typescript
import { Component, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Applicants } from '../applicants';

@Component({
  selector: 'app-signup-form',
  imports: [ReactiveFormsModule],
  templateUrl: './signup-form.html',
  styleUrl: './signup-form.css',
})
export class SignupForm {
  private readonly fb = inject(FormBuilder);
  private readonly applicants = inject(Applicants);

  signedUp = output<void>();
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    firstName: ['', Validators.required],
    lastName: ['', Validators.required],
    dateOfBirth: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    phone: ['', Validators.required],
  });

  submit(): void {
    if (this.form.invalid || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applicants.signUp(this.form.getRawValue()).subscribe({
      next: () => this.signedUp.emit(),
      error: () => {
        this.submitting.set(false);
        this.error.set('Could not create your profile. Check your details and try again.');
      },
    });
  }
}
```

`src/app/signup/signup-form/signup-form.html`:

```html
<div class="max-w-md">
  <h1 class="font-serif text-2xl mb-1">Tell us who you are</h1>
  <p class="text-ink-muted mb-6">Just once — we'll remember you next time.</p>

  <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-4">
    <label class="flex flex-col gap-1">
      <span class="text-sm text-ink-muted">First name</span>
      <input formControlName="firstName" class="border border-hairline px-3 py-2 rounded-lg" />
    </label>
    <label class="flex flex-col gap-1">
      <span class="text-sm text-ink-muted">Last name</span>
      <input formControlName="lastName" class="border border-hairline px-3 py-2 rounded-lg" />
    </label>
    <label class="flex flex-col gap-1">
      <span class="text-sm text-ink-muted">Date of birth</span>
      <input formControlName="dateOfBirth" type="date" class="border border-hairline px-3 py-2 rounded-lg" />
    </label>
    <label class="flex flex-col gap-1">
      <span class="text-sm text-ink-muted">Email</span>
      <input formControlName="email" type="email" class="border border-hairline px-3 py-2 rounded-lg" />
    </label>
    <label class="flex flex-col gap-1">
      <span class="text-sm text-ink-muted">Phone</span>
      <input formControlName="phone" type="tel" class="border border-hairline px-3 py-2 rounded-lg" />
    </label>

    @if (error(); as message) {
      <p class="text-sm text-red-700">{{ message }}</p>
    }

    <button
      type="submit"
      [disabled]="submitting()"
      class="mt-2 px-4 py-2 rounded-lg bg-widget-accent text-white font-widget font-medium disabled:opacity-50"
    >Continue</button>
  </form>
</div>
```

- [ ] **Step 9: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 10: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

- [ ] **Step 11: Commit**

```bash
git add frontend/storefront
git commit -m "Add Applicants service and the signup form"
```

---

### Task 5: Checkout and the Pay-in-4 widget

**Files:**
- Create: `frontend/storefront/src/app/shared/models/application.ts`
- Create: `frontend/storefront/src/app/checkout/applications.ts` (+ `.spec.ts`)
- Create: `frontend/storefront/src/app/checkout/checkout-confirm/checkout-confirm.ts` (+ `.html`, `.css`, `.spec.ts`)
- Create: `frontend/storefront/src/app/checkout/checkout-result/checkout-result.ts` (+ `.html`, `.css`, `.spec.ts`)

**Interfaces:**
- Consumes: `Product` (Task 3).
- Produces: `ApplicationResponse { applicationId, status, installmentCount, installmentAmount }` (this app's own subset of the real backend DTO — it never needs `scoreFactors`/`riskScore`/`merchantId`/etc., those are ops-only fields). `Applications.checkout(amount: number): Observable<ApplicationResponse>`. `CheckoutConfirm`: `product = input.required<Product>()`, `decided = output<ApplicationResponse>()`. `CheckoutResult`: `response = input.required<ApplicationResponse>()`, `backToShop = output<void>()` — all three consumed by Task 6.

- [ ] **Step 1: Write the model**

`src/app/shared/models/application.ts`:

```typescript
export interface ApplicationResponse {
  applicationId: string;
  status: string;
  installmentCount: number | null;
  installmentAmount: number | null;
}
```

- [ ] **Step 2: Write the failing service test**

`src/app/checkout/applications.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Applications } from './applications';

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

  it('checks out against the seeded demo merchant with a fresh Idempotency-Key', () => {
    service.checkout(198).subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/v1/applications'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      merchantId: '00000000-0000-7000-8000-000000000001',
      amount: 198,
    });
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    req.flush({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 49.5 });
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — `Applications` doesn't exist yet.

- [ ] **Step 4: Write `Applications`**

`src/app/checkout/applications.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicationResponse } from '../shared/models/application';

const RIDGELINE_MERCHANT_ID = '00000000-0000-7000-8000-000000000001';

@Injectable({ providedIn: 'root' })
export class Applications {
  private readonly http = inject(HttpClient);

  checkout(amount: number): Observable<ApplicationResponse> {
    return this.http.post<ApplicationResponse>(
      `${environment.gatewayBaseUrl}/api/v1/applications`,
      { merchantId: RIDGELINE_MERCHANT_ID, amount },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 6: Write the failing widget test**

```bash
npx ng generate component checkout/checkout-confirm
```

Replace the generated `checkout-confirm.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CheckoutConfirm } from './checkout-confirm';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';

describe('CheckoutConfirm', () => {
  const product: Product = { id: 'basin-rain-jacket', name: 'Basin Rain Jacket', price: 200, swatchColor: '#3F5843' };

  function setup(checkout: (amount: number) => any) {
    TestBed.configureTestingModule({
      imports: [CheckoutConfirm],
      providers: [{ provide: Applications, useValue: { checkout } }],
    });
    const fixture = TestBed.createComponent(CheckoutConfirm);
    fixture.componentRef.setInput('product', product);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the per-installment amount', () => {
    const fixture = setup(() => of({}));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('50.00');
  });

  it('emits decided with the response on a successful checkout', () => {
    const response = { applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 };
    const fixture = setup(() => of(response));
    const component = fixture.componentInstance;
    let emitted: unknown;
    component.decided.subscribe((r) => (emitted = r));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
    button.click();

    expect(emitted).toEqual(response);
  });

  it('shows an error and does not emit when checkout fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    const component = fixture.componentInstance;
    let emitted = false;
    component.decided.subscribe(() => (emitted = true));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Something went wrong');
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL.

- [ ] **Step 8: Implement the widget**

`src/app/checkout/checkout-confirm/checkout-confirm.ts` — this is the plan's one deliberately designed visual moment (see the spec's "Visual design" section):

```typescript
import { Component, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-checkout-confirm',
  imports: [DecimalPipe],
  templateUrl: './checkout-confirm.html',
  styleUrl: './checkout-confirm.css',
})
export class CheckoutConfirm {
  private readonly applications = inject(Applications);

  product = input.required<Product>();
  decided = output<ApplicationResponse>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected installmentAmount(): number {
    return this.product().price / 4;
  }

  confirm(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applications.checkout(this.product().price).subscribe({
      next: (response) => this.decided.emit(response),
      error: () => {
        this.submitting.set(false);
        this.error.set('Something went wrong submitting your order. Try again.');
      },
    });
  }
}
```

`src/app/checkout/checkout-confirm/checkout-confirm.html`:

```html
<div class="max-w-md mx-auto p-6 rounded-2xl bg-widget-surface font-widget">
  <h2 class="text-lg font-semibold text-widget-accent mb-1">Pay in 4 with BridgePay</h2>
  <p class="text-sm text-ink-muted mb-6">{{ product().name }} · ${{ product().price | number: '1.2-2' }}</p>

  <div class="flex items-center mb-6">
    @for (i of [0, 1, 2, 3]; track i) {
      <div class="flex items-center" [class.flex-1]="i < 3">
        <div
          class="w-4 h-4 rounded-full shrink-0"
          [class]="i === 0 ? 'bg-widget-accent' : 'border-2 border-widget-accent bg-widget-surface'"
        ></div>
        @if (i < 3) {
          <div class="flex-1 h-px border-t border-dashed border-widget-accent mx-1"></div>
        }
      </div>
    }
  </div>

  <p class="text-sm text-ink-muted mb-6">
    ${{ installmentAmount() | number: '1.2-2' }} today, then ${{ installmentAmount() | number: '1.2-2' }}
    every week for 3 more weeks.
  </p>

  @if (error(); as message) {
    <p class="text-sm text-red-700 mb-3">{{ message }}</p>
  }

  <button
    (click)="confirm()"
    [disabled]="submitting()"
    class="w-full py-3 rounded-lg bg-widget-accent text-white font-semibold disabled:opacity-50"
  >Confirm purchase</button>
</div>
```

- [ ] **Step 9: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 10: Write the failing result-view test**

```bash
npx ng generate component checkout/checkout-result
```

Replace the generated `checkout-result.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { CheckoutResult } from './checkout-result';

describe('CheckoutResult', () => {
  function setup(status: string) {
    TestBed.configureTestingModule({ imports: [CheckoutResult] });
    const fixture = TestBed.createComponent(CheckoutResult);
    fixture.componentRef.setInput('response', {
      applicationId: 'app-1', status, installmentCount: 4, installmentAmount: 50,
    });
    fixture.detectChanges();
    return fixture;
  }

  it('shows an approval message for APPROVED', () => {
    const fixture = setup('APPROVED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("You're approved");
  });

  it('shows a review message for MANUAL_REVIEW', () => {
    const fixture = setup('MANUAL_REVIEW');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('under review');
  });

  it('shows a decline message for DECLINED', () => {
    const fixture = setup('DECLINED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("couldn't be approved");
  });
});
```

- [ ] **Step 11: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL.

- [ ] **Step 12: Implement the result view**

`src/app/checkout/checkout-result/checkout-result.ts`:

```typescript
import { Component, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-checkout-result',
  imports: [DecimalPipe],
  templateUrl: './checkout-result.html',
  styleUrl: './checkout-result.css',
})
export class CheckoutResult {
  response = input.required<ApplicationResponse>();
  backToShop = output<void>();
}
```

`src/app/checkout/checkout-result/checkout-result.html`:

```html
<div class="max-w-md mx-auto p-6 rounded-2xl bg-widget-surface font-widget text-center">
  @switch (response().status) {
    @case ('APPROVED') {
      <h2 class="text-lg font-semibold text-widget-accent mb-2">You're approved</h2>
      <p class="text-sm text-ink-muted">
        ${{ response().installmentAmount | number: '1.2-2' }} today, then
        {{ (response().installmentCount ?? 1) - 1 }} more weekly payments of the same amount.
      </p>
    }
    @case ('MANUAL_REVIEW') {
      <h2 class="text-lg font-semibold text-widget-accent mb-2">Your order is under review</h2>
      <p class="text-sm text-ink-muted">We're taking a closer look. We'll follow up by email.</p>
    }
    @default {
      <h2 class="text-lg font-semibold text-widget-accent mb-2">This purchase couldn't be approved</h2>
      <p class="text-sm text-ink-muted">Pay in 4 isn't available for this order. Try another payment method.</p>
    }
  }
  <button (click)="backToShop.emit()" class="mt-6 text-widget-accent underline">Back to shop</button>
</div>
```

- [ ] **Step 13: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 14: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

- [ ] **Step 15: Commit**

```bash
git add frontend/storefront
git commit -m "Add Applications service, the Pay-in-4 widget, and the result view"
```

---

### Task 6: Root component — the catalog/signup/confirm/result flow

**Files:**
- Modify: `frontend/storefront/src/app/app.ts`
- Modify: `frontend/storefront/src/app/app.html`
- Modify: `frontend/storefront/src/app/app.spec.ts`

**Interfaces:**
- Consumes: `Auth` (Task 2), `PRODUCTS`/`Product` (Task 3), `ProductCatalog` (Task 3), `Applicants` (Task 4), `SignupForm` (Task 4), `Applications` (Task 5), `CheckoutConfirm`/`CheckoutResult` (Task 5), `ApplicationResponse` (Task 5).

A note on why this uses a plain constructor check rather than an `effect()`: `provideKeycloak`'s own initializer (documented behavior, not assumed) blocks Angular's bootstrap until Keycloak's `check-sso` resolves — by the time this component's constructor runs, `Auth.authenticated()` already reflects the final answer, whether that's the very first page load (silent iframe check already resolved) or a return trip after a real login redirect (a fresh page load, same initializer, same blocking behavior). There is no later moment where it changes out from under a already-constructed `App` — so a one-time synchronous read is correct here, not a simplification. (This is the opposite lesson from the Main Angular App's `Auth` bug, which needed `computed()` specifically because roles *did* need to stay live — here, a plain read is the right call for a different reason, not the same fix applied by habit.)

- [ ] **Step 1: Write the failing test**

Replace `src/app/app.spec.ts` (the CLI-generated version asserts on the default landing page, which no longer exists):

```typescript
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { App } from './app';
import { Auth } from './core/auth';
import { Applicants } from './signup/applicants';

const PENDING_KEY = 'storefront.pendingProductId';

describe('App', () => {
  afterEach(() => sessionStorage.removeItem(PENDING_KEY));

  function setup(authenticated: boolean, getMyProfile: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: Auth, useValue: { authenticated: () => authenticated, login, logout: () => {} } },
        { provide: Applicants, useValue: { getMyProfile, signUp: () => of({}) } },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('triggers login and stays on the catalog step when Pay in 4 is clicked while unauthenticated', () => {
    let loginCalled = false;
    const fixture = setup(false, () => of({}), () => (loginCalled = true));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(loginCalled).toBe(true);
    expect(app.step()).toBe('catalog');
  });

  it('moves to confirm when Pay in 4 is clicked while authenticated and a profile already exists', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(app.step()).toBe('confirm');
  });

  it('moves to signup when Pay in 4 is clicked while authenticated but no profile exists yet', () => {
    const fixture = setup(true, () => throwError(() => new Error('404')));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(app.step()).toBe('signup');
  });

  it('resumes automatically on construction when a pending checkout exists and the user is already authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));

    expect((fixture.componentInstance as any).step()).toBe('confirm');
  });

  it('moves to result and clears the pending checkout when a decision is made', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const app = fixture.componentInstance as any;
    app.onPayInFour('basin-rain-jacket');

    app.onDecided({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 });

    expect(app.step()).toBe('result');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx ng test --watch=false`
Expected: FAIL — `App` doesn't have `onPayInFour`/`step`/`onDecided` yet.

- [ ] **Step 3: Implement the root component**

`src/app/app.ts`:

```typescript
import { Component, inject, signal } from '@angular/core';
import { Auth } from './core/auth';
import { Applicants } from './signup/applicants';
import { ProductCatalog } from './catalog/product-catalog/product-catalog';
import { SignupForm } from './signup/signup-form/signup-form';
import { CheckoutConfirm } from './checkout/checkout-confirm/checkout-confirm';
import { CheckoutResult } from './checkout/checkout-result/checkout-result';
import { PRODUCTS, Product } from './catalog/products';
import { ApplicationResponse } from './shared/models/application';

type Step = 'catalog' | 'signup' | 'confirm' | 'result';

const PENDING_PRODUCT_KEY = 'storefront.pendingProductId';

@Component({
  selector: 'app-root',
  imports: [ProductCatalog, SignupForm, CheckoutConfirm, CheckoutResult],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly auth = inject(Auth);
  private readonly applicants = inject(Applicants);

  protected readonly step = signal<Step>('catalog');
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly result = signal<ApplicationResponse | null>(null);

  constructor() {
    const pendingId = sessionStorage.getItem(PENDING_PRODUCT_KEY);
    if (pendingId && this.auth.authenticated()) {
      const product = PRODUCTS.find((p) => p.id === pendingId);
      if (product) {
        this.selectedProduct.set(product);
        this.resumeAfterLogin();
      } else {
        sessionStorage.removeItem(PENDING_PRODUCT_KEY);
      }
    }
  }

  private resumeAfterLogin(): void {
    this.applicants.getMyProfile().subscribe({
      next: () => this.step.set('confirm'),
      error: () => this.step.set('signup'),
    });
  }

  protected onPayInFour(productId: string): void {
    const product = PRODUCTS.find((p) => p.id === productId);
    if (!product) return;
    this.selectedProduct.set(product);
    sessionStorage.setItem(PENDING_PRODUCT_KEY, productId);
    if (this.auth.authenticated()) {
      this.resumeAfterLogin();
    } else {
      this.auth.login(window.location.origin);
    }
  }

  protected onSignedUp(): void {
    this.step.set('confirm');
  }

  protected onDecided(response: ApplicationResponse): void {
    sessionStorage.removeItem(PENDING_PRODUCT_KEY);
    this.result.set(response);
    this.step.set('result');
  }

  protected backToShop(): void {
    this.step.set('catalog');
    this.selectedProduct.set(null);
    this.result.set(null);
  }
}
```

`src/app/app.html`:

```html
@switch (step()) {
  @case ('catalog') {
    <app-product-catalog (payInFour)="onPayInFour($event)" />
  }
  @case ('signup') {
    <app-signup-form (signedUp)="onSignedUp()" />
  }
  @case ('confirm') {
    @if (selectedProduct(); as product) {
      <app-checkout-confirm [product]="product" (decided)="onDecided($event)" />
    }
  }
  @case ('result') {
    @if (result(); as response) {
      <app-checkout-result [response]="response" (backToShop)="backToShop()" />
    }
  }
}
```

`src/app/app.css` — leave as the CLI-generated empty file.

- [ ] **Step 4: Run it to verify it passes**

Run: `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 5: Run the full suite and build**

```bash
npx ng test --watch=false
npx ng build
```

Expected: all tests green (should be around 20 total across the whole app), build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/storefront
git commit -m "Add the root catalog/signup/confirm/result flow controller"
```

---

### Task 7: Dockerfile, same-origin nginx proxy, docker-compose, manual smoke test, PROGRESS.md

**Files:**
- Create: `frontend/storefront/Dockerfile`
- Create: `frontend/storefront/nginx.conf`
- Create: `frontend/storefront/.dockerignore`
- Create: `frontend/storefront/proxy.conf.json`
- Modify: `frontend/storefront/angular.json`
- Modify: `docker-compose.yml` (repo root)
- Modify: `docs/PROGRESS.md`

This is the one place this plan writes the nginx/proxy configuration — and it's written correctly on arrival, matching the shape the Main Angular App only reached after a post-review fix wave. There is no earlier, wrong version of this file in this app's history.

- [ ] **Step 1: Write `.dockerignore`**

```
node_modules
dist
.angular
*.log
```

- [ ] **Step 2: Write `nginx.conf`** — SPA fallback plus the `/api/` proxy to the gateway, together, from the start:

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://api-gateway:8086;
        proxy_set_header Host $host;
        proxy_set_header Authorization $http_authorization;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 3: Write the Dockerfile**

```dockerfile
# Build stage
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install -g npm@11.6.1
RUN npm ci
COPY . .
RUN npx ng build

# Run stage
FROM nginx:alpine
COPY --from=build /app/dist/storefront/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

The `npm install -g npm@11.6.1` line is carried over from the Main Angular App's own already-diagnosed real issue (`node:22`'s bundled npm 10.x can't read a lockfile written by npm 11.6.1, the version pinned in this project's `packageManager` field) — apply it here from the start rather than re-discovering the same failure. Verify the real `ng build` output path is genuinely `dist/storefront/browser/` (it should be, matching the Main Angular App's confirmed pattern for this Angular version, but confirm with `ls dist/storefront` after Step 6's build rather than assuming).

- [ ] **Step 4: Write the local dev-server proxy**

`proxy.conf.json`:

```json
{
  "/api": {
    "target": "http://localhost:8086",
    "secure": false
  }
}
```

Wire it into `angular.json`: add `"proxyConfig": "proxy.conf.json"` to the `serve` target's top-level `options` (confirmed during the Main Angular App's build to be where `@angular/build:dev-server` actually reads it from — not nested under a specific configuration).

- [ ] **Step 5: Add the service to the root `docker-compose.yml`**

Read the current root `docker-compose.yml` first to match its exact style, then add, after the `main-app` entry and before `volumes:`:

```yaml
  storefront:
    build: ./frontend/storefront
    depends_on:
      - api-gateway
    ports:
      - "4201:80"
```

- [ ] **Step 6: Run the shared stack and smoke test**

```bash
docker-compose up --build
```

Once containers are up:

1. `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4201/` — expect `200` (the SPA shell).
2. `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4201/api/v1/applicants/me` — expect a real response FROM THE GATEWAY (e.g. a 401, not nginx's own 404) — proving the proxy genuinely reaches `api-gateway:8086`, the exact check that would have caught the Main Angular App's original CORS gap immediately if it had been run in Task 1 instead of after the fact.
3. Open `http://localhost:4201` in a browser if one is available in this environment; if not, this specific check (product catalog rendering, clicking "Pay in 4", the Keycloak login redirect, completing signup, seeing a real decision) remains genuinely unverified here — say so plainly in `docs/PROGRESS.md` rather than implying it was checked, matching the Main Angular App's own precedent for this exact limitation.
4. `docker-compose down` — confirm via `docker-compose ps -a` that nothing remains.

- [ ] **Step 7: Update `docs/PROGRESS.md`**

Move "Storefront demo Angular app" from `## Next, in order` into `## Done`, matching the file's existing level of detail: cite the real `ng test` count from this task's actual run, describe exactly what Step 6 confirmed (the SPA shell serving, the `/api/` proxy genuinely reaching the gateway container) versus what it didn't (a real browser click-through of the login-signup-checkout flow, if no browser was available in this environment). Note that `## Next, in order` is now empty of "frontend" sub-projects — all four are done; whatever comes after (k3s manifests, CI/CD) was already the next item on the list before this project started.

- [ ] **Step 8: Commit**

```bash
git add frontend/storefront docker-compose.yml docs/PROGRESS.md
git commit -m "Add Dockerfile, same-origin nginx proxy, and docker-compose wiring for the storefront"
```

---

## Self-Review Notes

- **Spec coverage**: check-sso auth + imperative login (Task 2), storefront shell + product catalog (Task 3), conditional signup (Task 4), Pay-in-4 widget + checkout submission + result states (Task 5), the full linear flow with `sessionStorage` resumption (Task 6), same-origin proxy built correctly from its first commit + docker-compose + smoke test (Task 7) — every spec section has a task. The spec's stated non-goals (cart, order history, real product photography, runtime-configurable API URL, e2e tests) are correctly absent from every task.
- **Placeholder scan**: none found — every step has real, complete code or an exact command.
- **Type consistency**: `Product` (Task 3) is the exact shape Task 5's `CheckoutConfirm` and Task 6's `App` both import and use (`id`/`name`/`price`/`swatchColor`, no drift). `ApplicationResponse` (Task 5) is the exact shape Task 6's `onDecided`/`CheckoutResult` consume. `Auth.authenticated()`/`login()` (Task 2) are the exact names Task 6's `App` calls — no `isAuthenticated()`/`signIn()` naming drift anywhere. `Applicants.getMyProfile()`/`signUp()` (Task 4) match what Task 6's `App` and Task 4's own `SignupForm` call.
- **One deliberate architecture note carried through**: Task 6's constructor-based (not `effect()`-based) resume check is explained inline with the actual reasoning (verified `provideKeycloak` initializer-blocks-bootstrap behavior), not applied as a reflexive copy of the Main Angular App's `computed()` fix — the two apps hit different problems and this plan says so rather than pattern-matching on the surface.
