import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';
import { SalesPage } from './sales-page';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';
import { Page } from '../../shared/models/page';
import { MerchantSaleResponse } from '../../shared/models/merchant-sale';
import { MerchantDashboard, PeriodTotals } from '../../shared/models/merchant-dashboard';

const totals = (over: Partial<PeriodTotals>): PeriodTotals => ({
  checkouts: 0, approved: 0, declined: 0, inReview: 0, paid: 0,
  approvedVolume: 0, approvalRate: null, feesPaid: 0, netPaidOut: 0, ...over,
});

const dashboard = (days = 30): MerchantDashboard => ({
  days, from: '2026-08-31', to: '2026-09-29', bucket: 'DAY',
  current: totals({
    checkouts: 5, approved: 3, declined: 1, inReview: 1, paid: 2,
    approvedVolume: 300, approvalRate: 0.75, feesPaid: 10.5, netPaidOut: 289.5,
  }),
  previous: totals({
    checkouts: 4, approved: 2, declined: 0, paid: 2,
    approvedVolume: 200, approvalRate: 0.8, feesPaid: 10.5, netPaidOut: 100,
  }),
  pendingPayout: 96.5,
  series: [
    { start: '2026-09-28', checkouts: 2, approvedVolume: 100 },
    { start: '2026-09-29', checkouts: 3, approvedVolume: 200 },
  ],
});

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
  dashboard?: (id: string, days: number, tz: string) => Observable<MerchantDashboard>;
  query?: Record<string, string>;
}) {
  const params = new BehaviorSubject(convertToParamMap(stub.query ?? {}));
  const navigate = vi.fn();
  TestBed.configureTestingModule({
    imports: [SalesPage],
    providers: [
      {
        provide: Sales,
        useValue: {
          list: stub.list ?? (() => of(salesPage(1))),
          dashboard: stub.dashboard ?? ((_id: string, days: number) => of(dashboard(days))),
        },
      },
      { provide: Auth, useValue: { merchantId: () => 'm-1' } },
      { provide: ActivatedRoute, useValue: { queryParamMap: params } },
      { provide: Router, useValue: { navigate } },
    ],
  });
  const fixture = TestBed.createComponent(SalesPage);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, params, navigate };
}

function button(el: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

const tile = (el: HTMLElement, name: string) => el.querySelector(`[data-tile="${name}"]`) as HTMLElement;

describe('SalesPage', () => {
  it('asks for the last 30 days in the browser time zone by default', () => {
    const calls: [number, string][] = [];
    setup({ dashboard: (_id, days, tz) => (calls.push([days, tz]), of(dashboard(days))) });
    expect(calls).toEqual([[30, Intl.DateTimeFormat().resolvedOptions().timeZone]]);
  });

  it('reads the period from the URL and switching period updates the URL', () => {
    const calls: number[] = [];
    const { fixture, el, params, navigate } = setup({
      query: { days: '90' },
      dashboard: (_id, days) => (calls.push(days), of(dashboard(days))),
    });
    expect(button(el, '90 days').getAttribute('aria-pressed')).toBe('true');

    button(el, '7 days').click();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { days: 7 } }));

    params.next(convertToParamMap({ days: '7' }));
    fixture.detectChanges();
    expect(calls).toEqual([90, 7]);
    expect(button(el, '7 days').getAttribute('aria-pressed')).toBe('true');
  });

  it('keeps only the newest period when an older request answers late', () => {
    const slow = new Subject<MerchantDashboard>();
    const { fixture, el, params } = setup({
      dashboard: (_id, days) => (days === 30 ? slow : of(dashboard(days))),
    });
    params.next(convertToParamMap({ days: '7' }));
    fixture.detectChanges();
    slow.next({ ...dashboard(30), pendingPayout: 1234 });
    fixture.detectChanges();
    expect(tile(el, 'pending-payout').textContent).toContain('96.50');
  });

  it('renders the period tiles with their change against the previous period', () => {
    const { el } = setup({});
    expect(tile(el, 'approved-volume').textContent).toContain('300.00');
    expect(tile(el, 'approved-volume').querySelector('[data-change]')?.textContent?.trim()).toBe('▲ 50% vs previous 30 days');
    expect(tile(el, 'approved-volume').querySelector('[data-change]')?.getAttribute('data-direction')).toBe('up');
    expect(tile(el, 'approved-volume').querySelector('app-sparkline')).not.toBeNull();
    expect(tile(el, 'approval-rate').textContent).toContain('75%');
    expect(tile(el, 'approval-rate').querySelector('[data-change]')?.textContent?.trim()).toBe('▼ 5 pts vs previous 30 days');
    expect(tile(el, 'fees-paid').querySelector('[data-change]')?.getAttribute('data-direction')).toBe('flat');
    expect(tile(el, 'net-paid-out').textContent).toContain('289.50');
    expect(tile(el, 'pending-payout').textContent).toContain('96.50');
    expect(tile(el, 'pending-payout').textContent).toContain('now');
    expect(tile(el, 'pending-payout').querySelector('[data-change]')).toBeNull();
  });

  it('shows a dash for the approval rate when nothing was decided', () => {
    const { el } = setup({
      dashboard: () => of({ ...dashboard(), current: totals({ approvalRate: null }) }),
    });
    expect(tile(el, 'approval-rate').textContent).toContain('—');
  });

  it('summarises the period counts, the chart and the funnel', () => {
    const { el } = setup({});
    expect(el.textContent).toContain('3 approved · 1 in review · 1 declined of 5 checkouts');
    expect(el.querySelector('app-sales-over-time')).not.toBeNull();
    const steps = Array.from(el.querySelectorAll('app-funnel-bars li')).map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(steps[0]).toContain('Checkouts 5');
    expect(steps[1]).toContain('Approved 3');
    expect(steps[2]).toContain('Paid 2');
    expect(el.textContent).toContain('1 declined · 1 still in review · 1 approved but not paid');
  });

  it('shows placeholders while the dashboard and the list load', () => {
    const { el } = setup({
      list: () => new Subject<Page<MerchantSaleResponse>>(),
      dashboard: () => new Subject<MerchantDashboard>(),
    });
    expect(el.querySelector('[data-testid="tiles-skeleton"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="chart-skeleton"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No sales yet');
  });

  it('still shows the sales list when the dashboard fails', () => {
    const { el } = setup({ dashboard: () => throwError(() => new Error('500')) });
    expect(el.textContent).toContain('Could not load your sales dashboard');
    expect(el.textContent).toContain('1,500.00');
    expect(el.querySelector('app-sales-over-time')).toBeNull();
  });

  it('still shows the dashboard when the sales list fails', () => {
    const { el } = setup({ list: () => throwError(() => new Error('403')) });
    expect(el.textContent).toContain('Could not load your sales');
    expect(el.textContent).not.toContain('No sales yet');
    expect(tile(el, 'approved-volume').textContent).toContain('300.00');
  });

  it('fetches all statuses on page 0 by default', () => {
    const calls: [string, number][] = [];
    setup({ list: (_id, status, page) => (calls.push([status, page]), of(salesPage(1))) });
    expect(calls).toEqual([['ALL', 0]]);
  });

  it('pages forward with Next and disables Prev on the first page and Next on the last', () => {
    const calls: [string, number][] = [];
    const { fixture, el } = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(2, page))),
    });
    expect(button(el, 'Prev').disabled).toBe(true);
    button(el, 'Next').click();
    fixture.detectChanges();
    expect(calls).toEqual([['ALL', 0], ['ALL', 1]]);
    expect(button(el, 'Next').disabled).toBe(true);
    expect(button(el, 'Prev').disabled).toBe(false);
  });

  it('resets to page 0 when the status filter changes', () => {
    const calls: [string, number][] = [];
    const { fixture, el } = setup({
      list: (_id, status, page) => (calls.push([status, page]), of(salesPage(3, page))),
    });
    button(el, 'Next').click();
    fixture.detectChanges();
    button(el, 'Approved').click();
    fixture.detectChanges();
    expect(calls.at(-1)).toEqual(['APPROVED', 0]);
  });

  it('keeps the error, not an empty state, when the active filter is clicked after a failed load', () => {
    const { fixture, el } = setup({ list: () => throwError(() => new Error('500')) });
    button(el, 'All').click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Could not load your sales');
    expect(el.textContent).not.toContain('No sales yet');
  });

  it('shows each sale status as a badge', () => {
    const { el } = setup({});
    const tones = Array.from(el.querySelectorAll('tbody [data-tone]')).map((b) => b.getAttribute('data-tone'));
    expect(tones).toEqual(['declined', 'approved']);
  });
});
