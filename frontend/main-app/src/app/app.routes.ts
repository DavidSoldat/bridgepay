import { Routes } from '@angular/router';
import { ReviewQueue } from './ops/review-queue/review-queue';
import { ReviewDetail } from './ops/review-detail/review-detail';
import { FailedEventsPage } from './ops/failed-events/failed-events';
import { OpsDashboardPage } from './ops/dashboard/ops-dashboard';
import { ShopperSearch } from './ops/shoppers/shopper-search/shopper-search';
import { ShopperPage } from './ops/shoppers/shopper-page/shopper-page';
import { AuditLog } from './ops/audit-log/audit-log';
import { ModelPage } from './ops/model/model-page';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { SalesPage } from './merchant/sales-page/sales-page';
import { NoAccess } from './no-access/no-access';
import { opsGuard } from './core/ops-guard';
import { merchantGuard } from './core/merchant-guard';
import { rootRedirectGuard } from './core/root-redirect-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [rootRedirectGuard], children: [] },
  { path: 'ops', component: ReviewQueue, canActivate: [opsGuard] },
  { path: 'ops/failed-events', component: FailedEventsPage, canActivate: [opsGuard] },
  { path: 'ops/dashboard', component: OpsDashboardPage, canActivate: [opsGuard] },
  { path: 'ops/shoppers', component: ShopperSearch, canActivate: [opsGuard] },
  { path: 'ops/shoppers/:subject', component: ShopperPage, canActivate: [opsGuard] },
  { path: 'ops/audit', component: AuditLog, canActivate: [opsGuard] },
  { path: 'ops/model', component: ModelPage, canActivate: [opsGuard] },
  { path: 'ops/:id', component: ReviewDetail, canActivate: [opsGuard] },
  { path: 'merchant', component: SalesPage, canActivate: [merchantGuard] },
  { path: 'merchant/payouts', component: PayoutLedger, canActivate: [merchantGuard] },
  { path: 'no-access', component: NoAccess },
];
