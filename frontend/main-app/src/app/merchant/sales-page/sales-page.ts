import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, finalize, map, of, startWith, switchMap } from 'rxjs';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { SalesOverTime } from '../../shared/charts/sales-over-time/sales-over-time';
import { Sparkline } from '../../shared/charts/sparkline/sparkline';
import { FunnelBars, FunnelStep } from '../../shared/charts/funnel-bars/funnel-bars';
import { Change, parseDays, pointsChange, relativeChange } from './change';
import { MerchantDashboard } from '../../shared/models/merchant-dashboard';

export const SALE_FILTERS = ['ALL', 'APPROVED', 'MANUAL_REVIEW', 'DECLINED'] as const;
export type SaleFilter = (typeof SALE_FILTERS)[number];

const SALE_FILTER_LABELS: Record<SaleFilter, string> = {
  ALL: 'All',
  APPROVED: 'Approved',
  MANUAL_REVIEW: 'In review',
  DECLINED: 'Declined',
};

const CHANGE_CLASS: Record<Change['direction'], string> = {
  up: 'text-approved',
  down: 'text-declined',
  flat: 'text-ink-muted',
};

@Component({
  selector: 'app-sales-page',
  imports: [DatePipe, DecimalPipe, PercentPipe, StatusBadge, EmptyState, SkeletonRows, SalesOverTime, Sparkline, FunnelBars],
  templateUrl: './sales-page.html',
  styleUrl: './sales-page.css',
})
export class SalesPage {
  private readonly sales = inject(Sales);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly merchantId = inject(Auth).merchantId() ?? '';
  private readonly tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  protected readonly periods = [7, 30, 90] as const;
  protected readonly days = toSignal(this.route.queryParamMap.pipe(map((p) => parseDays(p.get('days')))), {
    initialValue: 30 as const,
  });
  protected readonly dashboardError = signal(false);

  /** switchMap drops a slower answer for a period the user has already left. */
  protected readonly dashboard = toSignal(
    toObservable(this.days).pipe(
      switchMap((days) => {
        this.dashboardError.set(false);
        return this.sales.dashboard(this.merchantId, days, this.tz).pipe(
          catchError(() => {
            this.dashboardError.set(true);
            return of(null);
          }),
          startWith(null),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly volumeChange = computed(() => this.changeOf((d) => relativeChange(d.current.approvedVolume, d.previous.approvedVolume, d.days)));
  protected readonly rateChange = computed(() => this.changeOf((d) => pointsChange(d.current.approvalRate, d.previous.approvalRate, d.days)));
  protected readonly feesChange = computed(() => this.changeOf((d) => relativeChange(d.current.feesPaid, d.previous.feesPaid, d.days)));
  protected readonly netChange = computed(() => this.changeOf((d) => relativeChange(d.current.netPaidOut, d.previous.netPaidOut, d.days)));
  protected readonly volumeTrend = computed(() => this.dashboard()?.series.map((p) => p.approvedVolume) ?? []);
  protected readonly funnel = computed<FunnelStep[]>(() => {
    const c = this.dashboard()?.current;
    return c ? [
      { label: 'Checkouts', count: c.checkouts },
      { label: 'Approved', count: c.approved },
      { label: 'Paid', count: c.paid },
    ] : [];
  });

  protected readonly saleFilters = SALE_FILTERS;
  protected readonly saleFilterLabels = SALE_FILTER_LABELS;
  protected readonly filter = signal<SaleFilter>('ALL');
  protected readonly page = signal(0);
  protected readonly listError = signal(false);
  protected readonly loading = signal(true);

  // filter and page change together on a filter click; one computed keeps that to a single fetch.
  private readonly query = computed(() => ({ status: this.filter(), page: this.page() }));

  private readonly result = toSignal(
    toObservable(this.query).pipe(
      // Reset the error only when a fetch actually starts - re-clicking the active filter fetches nothing.
      switchMap(({ status, page }) => {
        this.listError.set(false);
        this.loading.set(true);
        return this.sales.list(this.merchantId, status, page).pipe(
          catchError(() => {
            this.listError.set(true);
            return of(null);
          }),
          finalize(() => this.loading.set(false)),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);

  protected selectDays(days: number): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: { days }, queryParamsHandling: 'merge' });
  }

  protected changeClass(change: Change): string {
    return CHANGE_CLASS[change.direction];
  }

  protected selectFilter(status: SaleFilter): void {
    this.filter.set(status);
    this.page.set(0);
  }

  protected prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  protected nextPage(): void {
    this.page.update((p) => p + 1);
  }

  private changeOf(fn: (d: MerchantDashboard) => Change): Change | null {
    const d = this.dashboard();
    return d ? fn(d) : null;
  }
}
