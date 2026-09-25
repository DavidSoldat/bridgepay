import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { catchError, of, switchMap } from 'rxjs';
import { FailedEventsApi } from '../failed-events-api';
import { FailedEvent } from '../../shared/models/failed-event';

export const FAILED_EVENT_FILTERS = ['FAILED', 'RESOLVED', 'ALL'] as const;
export type FailedEventFilter = (typeof FAILED_EVENT_FILTERS)[number];

const FILTER_LABELS: Record<FailedEventFilter, string> = { FAILED: 'Failed', RESOLVED: 'Resolved', ALL: 'All' };

@Component({
  selector: 'app-failed-events',
  imports: [DatePipe],
  templateUrl: './failed-events.html',
  styleUrl: './failed-events.css',
})
export class FailedEventsPage {
  private readonly api = inject(FailedEventsApi);

  protected readonly filters = FAILED_EVENT_FILTERS;
  protected readonly filterLabels = FILTER_LABELS;
  protected readonly filter = signal<FailedEventFilter>('FAILED');
  protected readonly page = signal(0);
  protected readonly listError = signal(false);
  protected readonly retrying = signal<string | null>(null);
  protected readonly retryMessage = signal<string | null>(null);
  // Bumped after a retry so the current page refetches with the row's new status.
  private readonly reload = signal(0);

  private readonly query = computed(() => ({ status: this.filter(), page: this.page(), reload: this.reload() }));

  private readonly result = toSignal(
    toObservable(this.query).pipe(
      switchMap(({ status, page }) => {
        this.listError.set(false);
        return this.api.list(status, page).pipe(
          catchError(() => {
            this.listError.set(true);
            return of(null);
          }),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);

  protected selectFilter(status: FailedEventFilter): void {
    this.filter.set(status);
    this.page.set(0);
  }

  protected prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  protected nextPage(): void {
    this.page.update((p) => p + 1);
  }

  protected retry(row: FailedEvent): void {
    if (this.retrying()) return;
    this.retrying.set(row.id);
    this.retryMessage.set(null);
    this.api.retry(row.id).subscribe({
      next: (updated) => {
        this.retrying.set(null);
        if (updated.status === 'FAILED') this.retryMessage.set(`Retry failed: ${updated.errorMessage}`);
        this.reload.update((n) => n + 1);
      },
      error: () => {
        this.retrying.set(null);
        this.retryMessage.set('Could not retry this event. Try again.');
      },
    });
  }
}
