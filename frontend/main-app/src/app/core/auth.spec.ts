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
