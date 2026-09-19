import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

export const rootRedirectGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (auth.hasRole('ops')) return router.parseUrl('/ops');
  if (auth.hasRole('merchant')) return router.parseUrl('/merchant');
  return router.parseUrl('/no-access');
};
