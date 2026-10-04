import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
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
import { Change, changeClass, parseDays, pointsChange, relativeChange } from '../../shared/charts/change';
import { MerchantDashboard } from '../../shared/models/merchant-dashboard';
import { MerchantSaleResponse } from '../../shared/models/merchant-sale';
import { ToastService } from '../../shared/ui/toast-service';

export const SALE_FILTERS = ['ALL', 'APPROVED', 'MANUAL_REVIEW', 'DECLINED', 'REFUNDED'] as const;
export type SaleFilter = (typeof SALE_FILTERS)[number];

const SALE_FILTER_LABELS: Record<SaleFilter, string> = {
  ALL: 'All',
  APPROVED: 'Approved',
  MANUAL_REVIEW: 'In review',
  DECLINED: 'Declined',
  REFUNDED: 'Refunded',
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
  protected readonly changeClass = changeClass;
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
  private readonly toast = inject(ToastService);
  /** Bumped after a refund so the list refetches the same filter and page. */
  private readonly reload = signal(0);
  protected readonly confirmingId = signal<string | null>(null);
  protected readonly refundingId = signal<string | null>(null);

  private readonly query = computed(() => ({ status: this.filter(), page: this.page(), reload: this.reload() }));

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

  protected selectFilter(status: SaleFilter): void {
    this.filter.set(status);
    this.page.set(0);
  }

  protected canRefund(row: MerchantSaleResponse): boolean {
    return row.status === 'APPROVED' || row.status === 'COMPLETED';
  }

  protected netOf(row: MerchantSaleResponse): number | null {
    return row.feeAmount === null ? null : row.amount - row.feeAmount;
  }

  protected confirmRefund(row: MerchantSaleResponse): void {
    this.refundingId.set(row.id);
    this.sales
      .refund(this.merchantId, row.id)
      .pipe(finalize(() => this.refundingId.set(null)))
      .subscribe({
        next: () => {
          this.confirmingId.set(null);
          this.toast.show('success', 'Refund requested');
          this.reload.update((n) => n + 1);
        },
        error: (err: unknown) => {
          const message =
            err instanceof HttpErrorResponse && err.status === 409 && err.error?.message
              ? err.error.message
              : 'Refund failed — try again.';
          this.toast.show('error', message);
        },
      });
  }

  protected exportCsv(): void {
    this.sales.exportCsv(this.merchantId, this.filter()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `bridgepay-sales-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.toast.show('error', 'Couldn’t export sales.'),
    });
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
