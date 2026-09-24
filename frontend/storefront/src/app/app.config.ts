import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import {
  provideKeycloak,
  includeBearerTokenInterceptor,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
} from 'keycloak-angular';
import { routes } from './app.routes';

const gatewayUrlCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: /^\/api\/.*$/i,
  bearerPrefix: 'Bearer',
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideKeycloak({
      config: {
        url: 'http://localhost:8180',
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
  ],
};
