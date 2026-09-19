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
