import { Component, computed, inject, input, Signal, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, concatMap, EMPTY, interval, of, startWith, switchMap, take, takeWhile, tap } from 'rxjs';
import { CreditLimits, noCredit } from '../../checkout/credit-limits';
import { CreditLimit } from '../../shared/models/credit-limit';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

const REFRESH_POLL_MS = 2000;
const REFRESH_POLL_ATTEMPTS = 6;

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
  protected readonly limit: Signal<CreditLimit | null | undefined> = toSignal<CreditLimit | null | undefined>(
    toObservable(this.refresh).pipe(
      switchMap((refresh) => (refresh > 0 ? this.pollForChange() : this.load())),
    ),
    { initialValue: undefined },
  );
  protected readonly noCredit = computed(() => noCredit(this.limit()));
  protected readonly usedPercent = computed(() => {
    const l = this.limit();
    if (!l?.limit) return 0;
    return Math.min(100, Math.round((l.outstanding / l.limit) * 100));
  });

  private load() {
    this.loadError.set(false);
    return this.creditLimits.mine().pipe(
      catchError(() => {
        this.loadError.set(true);
        return of(null);
      }),
    );
  }

  /**
   * application-service only sees a payment after its outbox poll + Kafka, so right after one the limit is usually
   * still the old value: keep showing it and ask again every 2 s until it changes (or we give up).
   */
  private pollForChange() {
    const before = this.limit()?.available;
    return interval(REFRESH_POLL_MS).pipe(
      startWith(-1), // first ask right away, synchronously
      take(REFRESH_POLL_ATTEMPTS),
      concatMap(() => this.creditLimits.mine().pipe(catchError(() => EMPTY))),
      tap(() => this.loadError.set(false)),
      takeWhile((l) => l?.available === before, true),
    );
  }
}
