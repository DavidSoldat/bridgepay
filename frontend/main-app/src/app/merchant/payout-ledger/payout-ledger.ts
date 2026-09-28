import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, finalize, map, of } from 'rxjs';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { MerchantPayoutResponse } from '../../shared/models/merchant-payout';
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

  protected readonly loadError = signal(false);
  protected readonly loading = signal(true);

  protected readonly rows = toSignal(
    this.payouts.listPayouts(this.auth.merchantId() ?? '').pipe(
      map((page) => page.content),
      catchError(() => {
        this.loadError.set(true);
        return of([] as MerchantPayoutResponse[]);
      }),
      finalize(() => this.loading.set(false)),
    ),
    { initialValue: [] as MerchantPayoutResponse[] },
  );
}
