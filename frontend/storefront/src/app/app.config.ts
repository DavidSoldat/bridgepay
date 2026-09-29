import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  provideKeycloak,
  includeBearerTokenInterceptor,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
} from 'keycloak-angular';
import { routes } from './app.routes';
import { PADDLE_CLIENT_TOKEN } from './payment/paddle-checkout';

const gatewayUrlCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: /^\/api\/.*$/i,
  bearerPrefix: 'Bearer',
});

export function appConfig(keycloakUrl: string, paddleClientToken = ''): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      provideRouter(routes, withComponentInputBinding()),
      provideKeycloak({
        config: {
          url: keycloakUrl,
          realm: 'bridgepay',
          clientId: 'storefront',
        },
        initOptions: {
          onLoad: 'check-sso',
          silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
          pkceMethod: 'S256',
        },
      }),
      {
        provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
        useValue: [gatewayUrlCondition],
      },
      provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
      { provide: PADDLE_CLIENT_TOKEN, useValue: paddleClientToken },
    ],
  };
}
