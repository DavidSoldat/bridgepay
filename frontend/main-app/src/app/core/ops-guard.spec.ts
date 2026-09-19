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
