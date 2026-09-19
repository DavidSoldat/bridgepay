import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthGuardData } from 'keycloak-angular';
import { isMerchantRole } from './merchant-guard';

describe('isMerchantRole', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  it('allows an authenticated merchant token', async () => {
    const authData = {
      authenticated: true,
      grantedRoles: { realmRoles: ['merchant'], resourceRoles: {} },
    } as AuthGuardData;

    const result = await TestBed.runInInjectionContext(() =>
      isMerchantRole({} as any, {} as any, authData),
    );

    expect(result).toBe(true);
  });

  it('redirects a non-merchant token to /no-access', async () => {
    const authData = {
      authenticated: true,
      grantedRoles: { realmRoles: ['ops'], resourceRoles: {} },
    } as AuthGuardData;

    const result = await TestBed.runInInjectionContext(() =>
      isMerchantRole({} as any, {} as any, authData),
    );

    expect(result?.toString()).toBe('/no-access');
  });
});
