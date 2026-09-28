import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { Sales } from '../sales';
import { Auth } from '../../core/auth';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

export const SALE_FILTERS = ['ALL', 'APPROVED', 'MANUAL_REVIEW', 'DECLINED'] as const;
export type SaleFilter = (typeof SALE_FILTERS)[number];

const SALE_FILTER_LABELS: Record<SaleFilter, string> = {
  ALL: 'All',
  APPROVED: 'Approved',
  MANUAL_REVIEW: 'In review',
  DECLINED: 'Declined',
};

@Component({
  selector: 'app-sales-page',
  imports: [DatePipe, DecimalPipe, PercentPipe, StatusBadge, EmptyState, SkeletonRows],
  templateUrl: './sales-page.html',
  styleUrl: './sales-page.css',
})
export class SalesPage {
  private readonly sales = inject(Sales);
  private readonly merchantId = inject(Auth).merchantId() ?? '';

  protected readonly saleFilters = SALE_FILTERS;
  protected readonly saleFilterLabels = SALE_FILTER_LABELS;
  protected readonly filter = signal<SaleFilter>('ALL');
  protected readonly page = signal(0);
  protected readonly summaryError = signal(false);
  protected readonly listError = signal(false);
  protected readonly loading = signal(true);

  protected readonly summary = toSignal(
    this.sales.summary(this.merchantId).pipe(
      catchError(() => {
        this.summaryError.set(true);
        return of(null);
      }),
    ),
    { initialValue: null },
  );

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
}
