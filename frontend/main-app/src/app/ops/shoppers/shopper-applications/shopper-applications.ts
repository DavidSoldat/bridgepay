import { Component, computed, input, inject, linkedSignal, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, filter, finalize, of, switchMap } from 'rxjs';
import { ShoppersApi } from '../shoppers-api';
import { StatusBadge } from '../../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';
import { orderRef } from '../timeline';

@Component({
  selector: 'app-shopper-applications',
  imports: [RouterLink, DatePipe, DecimalPipe, StatusBadge, SkeletonRows, EmptyState],
  templateUrl: './shopper-applications.html',
})
export class ShopperApplications {
  private readonly api = inject(ShoppersApi);

  subject = input.required<string>();
  protected readonly page = linkedSignal({ source: this.subject, computation: () => 0 });
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly orderRef = orderRef;

  private readonly result = toSignal(
    toObservable(computed(() => ({ subject: this.subject(), page: this.page() }))).pipe(
      filter(({ subject }) => !!subject),
      switchMap(({ subject, page }) => {
        this.loadError.set(false);
        this.loading.set(true);
        return this.api.applications(subject, page).pipe(
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

  protected decidedBy(source: string | null, by: string | null): string {
    if (source === 'MODEL') return 'Model';
    return by ?? '—';
  }
}
