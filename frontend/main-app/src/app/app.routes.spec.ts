import { routes } from './app.routes';
import { SalesPage } from './merchant/sales-page/sales-page';
import { PayoutLedger } from './merchant/payout-ledger/payout-ledger';
import { merchantGuard } from './core/merchant-guard';

describe('routes', () => {
  it('lands merchants on the sales page and moves payouts under it', () => {
    const merchant = routes.find((r) => r.path === 'merchant');
    const payouts = routes.find((r) => r.path === 'merchant/payouts');

    expect(merchant?.component).toBe(SalesPage);
    expect(merchant?.canActivate).toEqual([merchantGuard]);
    expect(payouts?.component).toBe(PayoutLedger);
    expect(payouts?.canActivate).toEqual([merchantGuard]);
  });
});
