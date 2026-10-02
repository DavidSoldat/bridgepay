import { ApplicationRef, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ViewportScroller } from '@angular/common';
import { Router, RouterOutlet } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { routerProviders } from './app.config';
import { Auth } from './core/auth';
import { of } from 'rxjs';
import { CreditLimits } from './checkout/credit-limits';

describe('routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        routerProviders,
        { provide: Auth, useValue: { authenticated: () => true, login: () => {} } },
        { provide: CreditLimits, useValue: { mine: () => of({ limit: null, outstanding: 0, available: null, band: null }) } },
      ],
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it('redirects an unknown URL to the catalog', async () => {
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/definitely-not-a-page');
    expect(router.url).toBe('/');
  });

  it('has a product page and a checkout per product', () => {
    expect(routes.map((r) => r.path)).toEqual(['', 'products/:id', 'checkout/:id', 'account', '**']);
  });

  it('binds the :id param into the page with the real router config', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/products/camp-multitool');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Camp Multitool');
  });

  it('opens each new page at the top instead of the previous scroll position', async () => {
    // The router only starts scroll handling when the app bootstraps, so bootstrap a bare root like main.ts does.
    @Component({ selector: 'scroll-test-root', imports: [RouterOutlet], template: '<router-outlet />' })
    class Root {}
    const host = document.body.appendChild(document.createElement('scroll-test-root'));
    const scrollTo = vi.spyOn(TestBed.inject(ViewportScroller), 'scrollToPosition').mockImplementation(() => {});
    const settle = () => new Promise((resolve) => setTimeout(resolve, 50));
    TestBed.inject(ApplicationRef).bootstrap(Root);
    await settle();
    scrollTo.mockClear();

    await TestBed.inject(Router).navigateByUrl('/products/camp-multitool');
    await settle();

    expect(scrollTo).toHaveBeenCalledWith([0, 0]);
    host.remove();
  });
});
