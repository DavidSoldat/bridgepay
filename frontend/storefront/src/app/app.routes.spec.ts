import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';

describe('routes', () => {
  it('redirects an unknown URL to the catalog', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes, withComponentInputBinding())] });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/definitely-not-a-page');
    expect(router.url).toBe('/');
  });

  it('has a product page and a checkout per product', () => {
    expect(routes.map((r) => r.path)).toEqual(['', 'products/:id', 'checkout/:id', 'account', '**']);
  });
});
