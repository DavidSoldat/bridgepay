import { Component, computed, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap } from 'rxjs';
import { CreditLimits, noCredit } from '../../checkout/credit-limits';
import { CreditLimit } from '../../shared/models/credit-limit';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

@Component({
  selector: 'app-spending-power',
  imports: [DecimalPipe, SkeletonRows],
  templateUrl: './spending-power.html',
  host: { class: 'card mb-6 block p-5' },
})
export class SpendingPower {
  private readonly creditLimits = inject(CreditLimits);

  /** Bump to fetch the limit again (e.g. after an early payment). */
  readonly refresh = input(0);

  protected readonly loadError = signal(false);
  /** undefined while loading. */
  protected readonly limit = toSignal<CreditLimit | null | undefined>(
    toObservable(this.refresh).pipe(
      switchMap(() => {
        this.loadError.set(false);
        return this.creditLimits.mine().pipe(
          catchError(() => {
            this.loadError.set(true);
            return of(null);
          }),
        );
      }),
    ),
    { initialValue: undefined },
  );
  protected readonly noCredit = computed(() => noCredit(this.limit()));
  protected readonly usedPercent = computed(() => {
    const l = this.limit();
    if (!l?.limit) return 0;
    return Math.min(100, Math.round((l.outstanding / l.limit) * 100));
  });
}
