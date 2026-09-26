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

  readonly fullName = computed<string>(() => {
    this.keycloakEvent();
    return (this.keycloak.tokenParsed?.['name'] as string | undefined) ?? '';
  });

  login(redirectUri: string): void {
    this.keycloak.login({ redirectUri });
  }

  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
