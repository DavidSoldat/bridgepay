import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { OpsDashboardPage } from './ops-dashboard';
import { Applications } from '../applications';
import { OpsDashboard, OpsPeriodTotals } from '../../shared/models/ops-dashboard';

const totals = (over: Partial<OpsPeriodTotals>): OpsPeriodTotals => ({
  applications: 0, autoDecided: 0, reviewed: 0, waiting: 0, approved: 0, declined: 0,
  approvalRate: null, medianReviewSeconds: null, ...over,
});

const bins = (counts: number[]) => counts.map((count, i) => ({ from: i / 10, to: (i + 1) / 10, count }));

const dashboard = (over: Partial<OpsDashboard> = {}): OpsDashboard => ({
  days: 30, from: '2026-09-01', to: '2026-09-30', bucket: 'DAY',
  queue: { inReview: 3, oldestSubmittedAt: new Date(Date.now() - (2 * 3600 + 14 * 60) * 1000).toISOString() },
  current: totals({
    applications: 70, autoDecided: 57, reviewed: 11, waiting: 2, approved: 49, declined: 18,
    approvalRate: 0.73, medianReviewSeconds: 15480,
  }),
  previous: totals({
    applications: 50, autoDecided: 40, reviewed: 9, approved: 36, declined: 12,
    approvalRate: 0.75, medianReviewSeconds: 18000,
  }),
  series: [
    { start: '2026-09-29', autoApproved: 2, autoDeclined: 1, review: 1 },
    { start: '2026-09-30', autoApproved: 3, autoDeclined: 0, review: 0 },
  ],
  scoreHistogram: bins([5, 20, 17, 6, 5, 4, 2, 3, 4, 4]),
  reviewers: [
    { name: 'ops1', decisions: 7, approved: 5, declined: 2, medianReviewSeconds: 12000 },
    { name: 'ops2', decisions: 4, approved: 2, declined: 2, medianReviewSeconds: 20000 },
  ],
  ...over,
});

async function setup(stub: { dashboard?: (days: number, tz: string) => Observable<OpsDashboard>; url?: string } = {}) {
  TestBed.configureTestingModule({
    imports: [OpsDashboardPage],
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: Applications, useValue: { dashboard: stub.dashboard ?? (() => of(dashboard())) } },
    ],
  });
  const router = TestBed.inject(Router);
  await router.navigateByUrl(stub.url ?? '/ops/dashboard');
  const fixture = TestBed.createComponent(OpsDashboardPage);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, router };
}

const tile = (el: HTMLElement, name: string) => el.querySelector(`[data-tile="${name}"]`) as HTMLElement;
const button = (el: HTMLElement, label: string) =>
  Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

describe('OpsDashboardPage', () => {
  it('asks for the last 30 days in the browser time zone by default', async () => {
    const calls: [number, string][] = [];
    await setup({ dashboard: (days, tz) => (calls.push([days, tz]), of(dashboard())) });
    expect(calls).toEqual([[30, Intl.DateTimeFormat().resolvedOptions().timeZone]]);
  });

  it('reads the period from the URL and switching period updates the URL', async () => {
    const calls: number[] = [];
    const { fixture, el, router } = await setup({
      url: '/ops/dashboard?days=90',
      dashboard: (days) => (calls.push(days), of(dashboard({ days }))),
    });
    expect(button(el, '90 days').getAttribute('aria-pressed')).toBe('true');

    const navigate = vi.spyOn(router, 'navigate');
    button(el, '7 days').click();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { days: 7 } }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(calls).toEqual([90, 7]);
  });

  it('shows how many applications are waiting and for how long', async () => {
    const { el } = await setup();
    const queue = el.querySelector('[data-testid="queue-now"]')!.textContent!;
    expect(queue).toContain('3');
    expect(queue).toContain('waiting for review');
    expect(queue).toContain('oldest 2 h 14 m');
    expect(el.querySelector('[data-testid="queue-now"] a')?.getAttribute('href')).toBe('/ops');
  });

  it('says the queue is clear when nothing waits', async () => {
    const { el } = await setup({ dashboard: () => of(dashboard({ queue: { inReview: 0, oldestSubmittedAt: null } })) });
    expect(el.querySelector('[data-testid="queue-now"]')!.textContent).toContain('Queue is clear.');
    expect(el.querySelector('[data-testid="queue-now"] a')).toBeNull();
  });

  it('renders the tiles with their change lines, review time falling counted as good', async () => {
    const { el } = await setup();
    expect(tile(el, 'applications').textContent).toContain('70');
    expect(tile(el, 'applications').textContent).toContain('▲ 40% vs previous 30 days');
    expect(tile(el, 'auto-decided').textContent).toContain('81%'); // 57 / 70
    expect(tile(el, 'auto-decided').textContent).toContain('▲ 1 pts vs previous 30 days'); // 81.4 - 80.0
    expect(tile(el, 'approval-rate').textContent).toContain('73%');
    expect(tile(el, 'approval-rate').textContent).toContain('▼ 2 pts');
    const review = tile(el, 'review-time');
    expect(review.textContent).toContain('4 h 18 m');
    const change = review.querySelector('[data-change]')!;
    expect(change.textContent).toContain('▼ 14%');
    expect(change.className).toContain('text-approved');
  });

  it('keeps the review-time change flat when one period had no reviews', async () => {
    const { el } = await setup({
      dashboard: () => of(dashboard({ current: totals({ applications: 3, autoDecided: 3 }) })),
    });
    const review = tile(el, 'review-time');
    expect(review.textContent).toContain('—');
    expect(review.querySelector('[data-change]')!.textContent).toContain('— vs previous 30 days');
  });

  it('shows a dash instead of a rate when the period has no applications', async () => {
    const { el } = await setup({
      dashboard: () => of(dashboard({ current: totals({}), previous: totals({}) })),
    });
    expect(tile(el, 'auto-decided').textContent).toContain('—');
    expect(tile(el, 'approval-rate').textContent).toContain('—');
    expect(el.innerHTML).not.toContain('NaN');
  });

  it('adds up routing in the count line and the score bands under the histogram', async () => {
    const { el } = await setup();
    expect(el.textContent).toContain('57 auto-decided · 11 reviewed · 2 waiting of 70 applications');
    // bins 0-2 / 3-6 / 7-9
    expect(el.textContent).toContain('42 auto-approve range · 17 review range · 11 auto-decline range');
  });

  it('lists reviewers with their median review time, or an empty state', async () => {
    const { el } = await setup();
    const rows = Array.from(el.querySelectorAll('[data-testid="reviewers"] tbody tr')).map((r) => r.textContent!);
    expect(rows[0]).toContain('ops1');
    expect(rows[0]).toContain('3 h 20 m');

    TestBed.resetTestingModule();
    const empty = await setup({ dashboard: () => of(dashboard({ reviewers: [] })) });
    expect(empty.el.textContent).toContain('No manual reviews in this period.');
  });

  it('shows skeletons while loading and one message when loading fails', async () => {
    const pending = await setup({ dashboard: () => new Observable<OpsDashboard>() });
    expect(pending.el.querySelector('[data-testid="tiles-skeleton"]')).not.toBeNull();

    TestBed.resetTestingModule();
    const failed = await setup({ dashboard: () => throwError(() => new Error('boom')) });
    expect(failed.el.textContent).toContain('Could not load the operations dashboard. Try refreshing.');
    expect(failed.el.querySelector('[data-testid="queue-now"]')).toBeNull();
  });
});
