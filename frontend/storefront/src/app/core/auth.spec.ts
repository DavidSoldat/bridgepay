import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { Auth } from './auth';

describe('Auth', () => {
  function setup(authenticated: boolean, tokenParsed?: Record<string, unknown>) {
    const fakeKeycloak = { authenticated, tokenParsed, logout: () => Promise.resolve() } as unknown as Keycloak;
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

  it('exposes the full name from the token', () => {
    const auth = setup(true, { name: 'Sam Shopper' });
    expect(auth.fullName()).toBe('Sam Shopper');
  });

  it('has no full name when the token carries none', () => {
    const auth = setup(true, { preferred_username: 'shopper1' });
    expect(auth.fullName()).toBe('');
  });

  it('forgets saved checkout addresses on sign out, so the next shopper on this tab never sees them', () => {
    sessionStorage.setItem('storefront.checkout.camp-multitool.address', '{"street":"12 Pine Rd"}');
    sessionStorage.setItem('storefront.checkout.basin-rain-jacket.address', '{"street":"12 Pine Rd"}');
    sessionStorage.setItem('unrelated', 'keep');
    setup(true).logout();
    expect(sessionStorage.getItem('storefront.checkout.camp-multitool.address')).toBeNull();
    expect(sessionStorage.getItem('storefront.checkout.basin-rain-jacket.address')).toBeNull();
    expect(sessionStorage.getItem('unrelated')).toBe('keep');
    sessionStorage.clear();
  });
});
