import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { SalesPage } from './sales-page';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';
import { Page } from '../../shared/models/page';
import { MerchantSaleResponse } from '../../shared/models/merchant-sale';
import { MerchantSummaryResponse } from '../../shared/models/merchant-summary';

const summary: MerchantSummaryResponse = {
  totalCheckouts: 4, approvedCount: 2, inReviewCount: 1, declinedCount: 1,
  approvalRate: 2 / 3, approvedVolume: 300, feesPaid: 10.5, netPaidOut: 289.5, pendingPayout: 96.5,
};

function salesPage(totalPages: number, number = 0): Page<MerchantSaleResponse> {
  return {
    content: [
      {
        id: 's-1', createdAt: '2026-09-20T10:00:00Z', amount: 1500, status: 'DECLINED',
        installmentCount: null, installmentAmount: null, decisionAt: '2026-09-20T10:00:01Z',
      },
      {
        id: 's-2', createdAt: '2026-09-19T10:00:00Z', amount: 100, status: 'APPROVED',
        installmentCount: 4, installmentAmount: 25, decisionAt: '2026-09-19T10:00:01Z',
      },
    ],
    totalElements: 2, totalPages, number, size: 20,
  };
}

function setup(stub: {
  list?: (id: string, status: string, page: number) => Observable<Page<MerchantSaleResponse>>;
  summary?: () => Observable<MerchantSummaryResponse>;
}) {
  TestBed.configureTestingModule({
    imports: [SalesPage],
    providers: [
      {
        provide: Sales,
        useValue: {
          list: stub.list ?? (() => of(salesPage(1))),
          summary: stub.summary ?? (() => of(summary)),
        },
      },
      { provide: Auth, useValue: { merchantId: () => 'm-1' } },
    ],
  });
  const fixture = TestBed.createComponent(SalesPage);
  fixture.detectChanges();
  return fixture;
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

describe('SalesPage', () => {
  it('renders the summary tiles and a row per sale', () => {
    const el = setup({}).nativeElement as HTMLElement;

    expect(el.querySelector('[data-tile="approved-volume"]')?.textContent).toContain('300.00');
    expect(el.querySelector('[data-tile="approval-rate"]')?.textContent).toContain('67%');
    expect(el.querySelector('[data-tile="fees-paid"]')?.textContent).toContain('10.50');
    expect(el.querySelector('[data-tile="net-paid-out"]')?.textContent).toContain('289.50');
    expect(el.querySelector('[data-tile="pending-payout"]')?.textContent).toContain('96.50');
    const text = el.textContent ?? '';
    expect(text).toContain('1,500.00');
    expect(text).toContain('declined');
    expect(text).toContain('4 × 25.00');
  });

  it('shows a dash for the approval rate when nothing has been decided', () => {
    const el = setup({ summary: () => of({ ...summary, approvalRate: null }) }).nativeElement as HTMLElement;

    expect(el.querySelector('[data-tile="approval-rate"]')?.textContent).toContain('—');
  });

  it('fetches all statuses on page 0 by default', () => {
    const calls: [string, number][] = [];
    setup({ list: (_id, status, page) => (calls.push([status, page]), of(salesPage(1))) });

    expect(calls).toEqual([['ALL', 0]]);
  });

  it('pages forward with Next and disables Prev on the first page and Next on the last', () => {
    const calls: [string, number][] = [];
    const fixture = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(2, page))),
    });
    const el = fixture.nativeElement as HTMLElement;

    expect(button(el, 'Prev').disabled).toBe(true);
    button(el, 'Next').click();
    fixture.detectChanges();

    expect(calls).toEqual([['ALL', 0], ['ALL', 1]]);
    expect(button(el, 'Next').disabled).toBe(true);
    expect(button(el, 'Prev').disabled).toBe(false);
  });

  it('resets to page 0 when the status filter changes', () => {
    const calls: [string, number][] = [];
    const fixture = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(3, page))),
    });
    const el = fixture.nativeElement as HTMLElement;

    button(el, 'Next').click();
    fixture.detectChanges();
    button(el, 'Approved').click();
    fixture.detectChanges();

    expect(calls.at(-1)).toEqual(['APPROVED', 0]);
  });

  it('still shows the sales list when the summary fails', () => {
    const el = setup({ summary: () => throwError(() => new Error('500')) }).nativeElement as HTMLElement;
    const text = el.textContent ?? '';

    expect(text).toContain('Could not load your sales summary');
    expect(text).toContain('1,500.00');
  });

  it('keeps the error, not an empty state, when the active filter is clicked after a failed load', () => {
    const fixture = setup({ list: () => throwError(() => new Error('500')) });
    const el = fixture.nativeElement as HTMLElement;

    button(el, 'All').click();
    fixture.detectChanges();

    const text = el.textContent ?? '';
    expect(text).toContain('Could not load your sales');
    expect(text).not.toContain('No sales yet');
  });

  it('still shows the summary tiles when the sales list fails', () => {
    const el = setup({ list: () => throwError(() => new Error('403')) }).nativeElement as HTMLElement;
    const text = el.textContent ?? '';

    expect(text).toContain('Could not load your sales');
    expect(text).not.toContain('No sales yet');
    expect(el.querySelector('[data-tile="approved-volume"]')?.textContent).toContain('300.00');
  });

  it('shows placeholders for the tiles and the list while they load', () => {
    const el = setup({
      list: () => new Subject<Page<MerchantSaleResponse>>(),
      summary: () => new Subject<MerchantSummaryResponse>(),
    }).nativeElement as HTMLElement;

    expect(el.querySelector('[data-testid="tiles-skeleton"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No sales yet');
  });

  it('shows each sale status as a badge', () => {
    const el = setup({}).nativeElement as HTMLElement;
    const tones = Array.from(el.querySelectorAll('tbody [data-tone]')).map((b) => b.getAttribute('data-tone'));
    expect(tones).toEqual(['declined', 'approved']);
  });
});
