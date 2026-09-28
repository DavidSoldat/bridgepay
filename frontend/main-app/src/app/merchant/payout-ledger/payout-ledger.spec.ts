import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
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
    expect(text).toContain('Paid');
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

  it('shows a distinct error message instead of the empty state when the request fails', () => {
    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => throwError(() => new Error('403')) } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });

    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Could not load your payouts');
    expect(text).not.toContain('No payouts yet');
  });

  it('shows a skeleton while payouts load, not the empty message', () => {
    TestBed.configureTestingModule({
      imports: [PayoutLedger],
      providers: [
        { provide: Payouts, useValue: { listPayouts: () => new Subject<Page<MerchantPayoutResponse>>() } },
        { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      ],
    });
    const fixture = TestBed.createComponent(PayoutLedger);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No payouts yet');
  });

  it('tells a cancelled payout apart from a pending one', () => {
    const page: Page<MerchantPayoutResponse> = {
      content: [
        { id: 'p-1', applicationId: 'a-1', amount: 10, feeAmount: 1, status: 'PENDING', paidAt: null },
        { id: 'p-2', applicationId: 'a-2', amount: 20, feeAmount: 2, status: 'CANCELLED', paidAt: null },
      ],
      totalElements: 2, totalPages: 1, number: 0, size: 20,
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

    const tones = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody [data-tone]')).map((b) =>
      b.getAttribute('data-tone'),
    );
    expect(tones).toEqual(['review', 'neutral']);
  });
});

