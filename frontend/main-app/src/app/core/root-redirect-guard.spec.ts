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
