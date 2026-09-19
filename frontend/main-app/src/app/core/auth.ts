import { Injectable, computed, inject } from '@angular/core';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  private readonly tokenClaims = computed<Record<string, unknown> | undefined>(() => {
    this.keycloakEvent(); // re-evaluate whenever a Keycloak event fires
    return this.keycloak.tokenParsed;
  });

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

  hasRole(role: string): boolean {
    return this.roles().includes(role);
  }

  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
