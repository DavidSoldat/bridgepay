import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

@Component({
  selector: 'app-payout-ledger',
  imports: [DatePipe, DecimalPipe, StatusBadge, EmptyState, SkeletonRows],
  templateUrl: './payout-ledger.html',
  styleUrl: './payout-ledger.css',
})
export class PayoutLedger {
  private readonly payouts = inject(Payouts);
  private readonly auth = inject(Auth);

  private readonly merchantId = this.auth.merchantId() ?? '';

  protected readonly loadError = signal(false);
  protected readonly loading = signal(true);
  protected readonly page = signal(0);

  private readonly result = toSignal(
    toObservable(this.page).pipe(
      switchMap((page) => {
        this.loadError.set(false);
        this.loading.set(true);
        return this.payouts.listPayouts(this.merchantId, page).pipe(
          catchError(() => {
            this.loadError.set(true);
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

  protected prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  protected nextPage(): void {
    this.page.update((p) => p + 1);
  }
}
