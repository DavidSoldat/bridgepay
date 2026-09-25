import { routes } from './app.routes';
import { SalesPage } from './merchant/sales-page/sales-page';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { FailedEventsPage } from './ops/failed-events/failed-events';
import { merchantGuard } from './core/merchant-guard';
import { opsGuard } from './core/ops-guard';

describe('routes', () => {
  it('lands merchants on the sales page and moves payouts under it', () => {
    const merchant = routes.find((r) => r.path === 'merchant');
    const payouts = routes.find((r) => r.path === 'merchant/payouts');

    expect(merchant?.component).toBe(SalesPage);
    expect(merchant?.canActivate).toEqual([merchantGuard]);
    expect(payouts?.component).toBe(PayoutLedger);
    expect(payouts?.canActivate).toEqual([merchantGuard]);
  });

  it('routes /ops/failed-events to the failed events page, not review detail', () => {
    const opsIndex = routes.findIndex((r) => r.path === 'ops/failed-events');
    const detailIndex = routes.findIndex((r) => r.path === 'ops/:id');

    expect(opsIndex).toBeGreaterThan(-1);
    expect(routes[opsIndex].component).toBe(FailedEventsPage);
    expect(routes[opsIndex].canActivate).toEqual([opsGuard]);
    expect(opsIndex).toBeLessThan(detailIndex);
  });
});
