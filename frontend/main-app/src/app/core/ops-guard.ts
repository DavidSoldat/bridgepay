import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

export const isOpsRole = async (
  _route: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean | UrlTree> => {
  const { authenticated, grantedRoles } = authData;
  if (authenticated && grantedRoles.realmRoles.includes('ops')) {
    return true;
  }
  return inject(Router).parseUrl('/no-access');
};

export const opsGuard: CanActivateFn = createAuthGuard(isOpsRole);
