import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PayoutLedger } from './payout-ledger';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { Page } from '../../shared/models/page';
import { MerchantPayoutResponse } from '../../shared/models/merchant-payout';

describe('PayoutLedger', () => {
  it('renders a row per payout returned by the service', () => {
    const page: Page<MerchantPayoutResponse> = {
      content: [
        { id: 'p-1', applicationId: 'app-1', amount: 1240, feeAmount: 43.4, status: 'PAID', paidAt: '2026-09-14T00:00:00Z' },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => of(page) } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });

    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('1,240.00');
    expect(text).toContain('paid');
  });

  it('shows an empty-state message when there are no payouts', () => {
    const emptyPage: Page<MerchantPayoutResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => of(emptyPage) } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });

    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No payouts yet');
  });
});
