import { Routes } from '@angular/router';
import { ReviewQueue } from './ops/review-queue/review-queue';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { NoAccess } from './no-access/no-access';
import { opsGuard } from './core/ops-guard';
import { merchantGuard } from './core/merchant-guard';
import { rootRedirectGuard } from './core/root-redirect-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [rootRedirectGuard], children: [] },
  { path: 'ops', component: ReviewQueue, canActivate: [opsGuard] },
  { path: 'merchant', component: PayoutLedger, canActivate: [merchantGuard] },
  { path: 'no-access', component: NoAccess },
];
